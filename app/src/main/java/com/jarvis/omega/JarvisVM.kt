package com.jarvis.omega

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisVM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val main = Handler(Looper.getMainLooper())

    var settings by mutableStateOf(store.loadSettings())
        private set
    val messages = mutableStateListOf<ChatMsg>()
    val notes = mutableStateListOf<Note>()
    val reminders = mutableStateListOf<Reminder>()

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set

    /** Hands-free (wake word) mode. Never persisted: it is always off when the app starts. */
    var handsFree by mutableStateOf(false)
        private set

    private var requireWake = false
    private var job: Job? = null

    private val speaker = Speaker(app) { onSpeechDone() }

    private val voice = Voice(
        app,
        object : Voice.Callback {
            override fun onPartial(text: String) {
                partial = text
            }

            override fun onFinal(text: String) {
                partial = ""
                handleHeard(text)
            }

            override fun onState(listening: Boolean) {
                this@JarvisVM.listening = listening
            }

            override fun onError(message: String, retryable: Boolean) {
                partial = ""
                if (retryable) {
                    if (handsFree) restartSoon() else status = ""
                } else {
                    status = message
                    handsFree = false
                }
            }
        },
    )

    init {
        messages.addAll(store.loadChat())
        notes.addAll(store.loadNotes())
        reminders.addAll(store.loadReminders())
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
        status = "Settings saved"
    }

    // ---------------------------------------------------------------- chat

    fun send(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        messages.add(ChatMsg("user", text))

        val local = tryLocal(text)
        if (local != null) {
            messages.add(ChatMsg("assistant", local, "on-device"))
            persist()
            finishTurn(local, fromVoice)
            return
        }

        val s = settings
        val order = Router.plan(text, s)
        if (order.isEmpty()) {
            val msg = "No usable AI provider yet. Open Settings, add an API key, and check the provider mode."
            messages.add(ChatMsg("assistant", msg))
            persist()
            finishTurn(null, fromVoice)
            return
        }

        messages.add(ChatMsg("assistant", ""))
        val idx = messages.lastIndex
        val history = messages.take(idx)
            .takeLast(20)
            .map { it.role to it.text }
            .filter { it.second.isNotBlank() }
            .dropWhile { it.first != "user" }

        busy = true
        status = ""
        job = viewModelScope.launch {
            var answer = ""
            var err = ""
            var cancelled = false
            try {
                for (p in order) {
                    val sb = StringBuilder()
                    status = "Thinking with ${p.label}…"
                    try {
                        p.stream(s, history, s.systemPrompt) { d ->
                            sb.append(d)
                            val t = sb.toString()
                            withContext(Dispatchers.Main.immediate) {
                                messages[idx] = ChatMsg("assistant", t, p.label)
                            }
                        }
                        answer = sb.toString()
                        if (answer.isNotBlank()) break
                        err = "${p.label}: empty reply"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ensureActive() // a cancelled request surfaces as an IOException; treat it as a stop
                        err = "${p.label}: ${e.message}"
                        if (sb.isNotBlank()) {
                            answer = sb.toString()
                            break
                        }
                    }
                }
                if (answer.isBlank()) {
                    answer = "⚠️ No provider answered. $err"
                    messages[idx] = ChatMsg("assistant", answer)
                }
            } catch (e: CancellationException) {
                cancelled = true
                answer = messages[idx].text
                if (answer.isBlank()) {
                    answer = "(stopped)"
                    messages[idx] = ChatMsg("assistant", answer)
                }
            } finally {
                busy = false
                status = ""
                persist()
            }
            if (cancelled) {
                if (handsFree) restartSoon()
            } else {
                finishTurn(answer, fromVoice)
            }
        }
    }

    fun stopGeneration() {
        job?.cancel()
    }

    fun clearChat() {
        job?.cancel()
        messages.clear()
        persist()
    }

    fun exportText(): String =
        messages.joinToString("\n\n") { (if (it.role == "user") "You: " else "JARVIS: ") + it.text }

    private fun persist() {
        store.saveChat(messages.toList())
    }

    private fun finishTurn(answer: String?, fromVoice: Boolean) {
        val speakIt = answer != null && !answer.startsWith("⚠️") && (settings.speak || fromVoice)
        if (speakIt) {
            speaker.say(answer!!, settings.speechRate)
        } else if (handsFree) {
            restartSoon()
        }
    }

    private fun onSpeechDone() {
        if (handsFree) restartSoon()
    }

    // ---------------------------------------------------------------- on-device commands

    private val unitMs = { u: String ->
        when {
            u.startsWith("s", ignoreCase = true) -> 1_000L
            u.startsWith("m", ignoreCase = true) -> 60_000L
            else -> 3_600_000L
        }
    }

    private val remindA = Regex(
        "(?i)^(?:jarvis[,. ]*)?remind me in (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s+to\\s+(.+)$",
    )
    private val remindB = Regex(
        "(?i)^(?:jarvis[,. ]*)?remind me to\\s+(.+?)\\s+in (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\.?$",
    )
    private val noteCmd = Regex(
        "(?i)^(?:jarvis[,. ]*)?(?:(?:take a note|make a note|note down)\\s*[:,-]?\\s*|note\\s*[:,-]\\s*)(.+)$",
    )

    private fun tryLocal(text: String): String? {
        remindA.find(text)?.let {
            val ms = it.groupValues[1].toLong() * unitMs(it.groupValues[2])
            return addReminderAt(it.groupValues[3].trim(), System.currentTimeMillis() + ms)
        }
        remindB.find(text)?.let {
            val ms = it.groupValues[2].toLong() * unitMs(it.groupValues[3])
            return addReminderAt(it.groupValues[1].trim(), System.currentTimeMillis() + ms)
        }
        noteCmd.find(text)?.let {
            addNote(it.groupValues[1].trim())
            return "Noted: ${it.groupValues[1].trim()}"
        }
        return null
    }

    // ---------------------------------------------------------------- notes

    fun addNote(text: String) {
        if (text.isBlank()) return
        notes.add(0, Note(System.currentTimeMillis(), text.trim()))
        store.saveNotes(notes.toList())
    }

    fun deleteNote(n: Note) {
        notes.remove(n)
        store.saveNotes(notes.toList())
    }

    // ---------------------------------------------------------------- reminders

    private fun addReminderAt(text: String, at: Long): String {
        val r = Reminder(System.currentTimeMillis(), text, at)
        reminders.add(r)
        store.saveReminders(reminders.toList())
        Reminders.schedule(getApplication(), r)
        val whenText = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(at))
        return "Reminder set for $whenText: $text"
    }

    fun addReminder(text: String, minutes: Long) {
        if (text.isBlank() || minutes <= 0) return
        addReminderAt(text.trim(), System.currentTimeMillis() + minutes * 60_000L)
    }

    fun deleteReminder(r: Reminder) {
        Reminders.cancel(getApplication(), r)
        reminders.remove(r)
        store.saveReminders(reminders.toList())
    }

    // ---------------------------------------------------------------- voice

    private fun restartSoon() {
        main.postDelayed({
            if (handsFree && !busy && !speaker.speaking && !listening) startListening(true)
        }, 600)
    }

    fun startListening(wake: Boolean) {
        if (!voice.available()) {
            status = "Speech recognition isn't available on this phone"
            handsFree = false
            return
        }
        speaker.stop()
        requireWake = wake
        status = if (wake) "Listening for “${settings.wakeWord}”…" else "Listening…"
        voice.start()
    }

    fun micTap() {
        if (listening) {
            voice.stop()
            status = ""
        } else {
            startListening(false)
        }
    }

    fun setHandsFree(on: Boolean) {
        handsFree = on
        if (on) {
            startListening(true)
        } else {
            voice.stop()
            status = ""
        }
    }

    /** Called when the app goes to the background: microphone off, speech stopped. */
    fun pauseVoice() {
        handsFree = false
        voice.stop()
        speaker.stop()
    }

    private fun handleHeard(text: String) {
        if (!requireWake) {
            status = ""
            send(text, fromVoice = true)
            return
        }
        val wake = settings.wakeWord.lowercase().ifBlank { "jarvis" }
        val i = text.lowercase().indexOf(wake)
        if (i < 0) {
            restartSoon()
            return
        }
        val cmd = text.substring(i + wake.length).trim().trimStart(',', '.', ':', ' ')
        if (cmd.isBlank()) {
            // Wake word alone: listen once more for the actual request.
            startListening(false)
            status = "Yes? Go ahead…"
        } else {
            status = ""
            send(cmd, fromVoice = true)
        }
    }

    override fun onCleared() {
        voice.stop()
        speaker.shutdown()
        super.onCleared()
    }
}
