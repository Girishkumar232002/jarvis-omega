package com.jarvis.omega

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local storage. Everything stays on the phone (private app storage). */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            anthropicKey = prefs.getString("anthropicKey", d.anthropicKey) ?: "",
            openaiKey = prefs.getString("openaiKey", d.openaiKey) ?: "",
            geminiKey = prefs.getString("geminiKey", d.geminiKey) ?: "",
            anthropicModel = prefs.getString("anthropicModel", d.anthropicModel) ?: d.anthropicModel,
            openaiModel = prefs.getString("openaiModel", d.openaiModel) ?: d.openaiModel,
            geminiModel = prefs.getString("geminiModel", d.geminiModel) ?: d.geminiModel,
            mode = prefs.getString("mode", d.mode) ?: d.mode,
            localModel = prefs.getString("localModel", d.localModel) ?: d.localModel,
            useGpu = prefs.getBoolean("useGpu", d.useGpu),
            ollamaUrl = prefs.getString("ollamaUrl", d.ollamaUrl) ?: d.ollamaUrl,
            ollamaModel = prefs.getString("ollamaModel", d.ollamaModel) ?: d.ollamaModel,
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: d.systemPrompt,
            speak = prefs.getBoolean("speak", d.speak),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            wakeWord = prefs.getString("wakeWord", d.wakeWord) ?: d.wakeWord,
        )
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("anthropicKey", s.anthropicKey)
            .putString("openaiKey", s.openaiKey)
            .putString("geminiKey", s.geminiKey)
            .putString("anthropicModel", s.anthropicModel)
            .putString("openaiModel", s.openaiModel)
            .putString("geminiModel", s.geminiModel)
            .putString("mode", s.mode)
            .putString("localModel", s.localModel)
            .putBoolean("useGpu", s.useGpu)
            .putString("ollamaUrl", s.ollamaUrl)
            .putString("ollamaModel", s.ollamaModel)
            .putString("systemPrompt", s.systemPrompt)
            .putBoolean("speak", s.speak)
            .putFloat("speechRate", s.speechRate)
            .putString("wakeWord", s.wakeWord)
            .apply()
    }

    private fun file(name: String) = File(ctx.filesDir, name)

    private fun readArr(name: String): JSONArray =
        try {
            JSONArray(file(name).readText())
        } catch (_: Exception) {
            JSONArray()
        }

    fun loadChat(): List<ChatMsg> {
        val a = readArr("chat.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ChatMsg(o.getString("role"), o.getString("text"), o.optString("provider", ""))
        }
    }

    fun saveChat(list: List<ChatMsg>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("role", it.role).put("text", it.text).put("provider", it.provider))
        }
        file("chat.json").writeText(a.toString())
    }

    fun loadNotes(): List<Note> {
        val a = readArr("notes.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Note(o.getLong("id"), o.getString("text"))
        }
    }

    fun saveNotes(list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text)) }
        file("notes.json").writeText(a.toString())
    }

    fun loadReminders(): List<Reminder> {
        val a = readArr("reminders.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Reminder(o.getLong("id"), o.getString("text"), o.getLong("at"))
        }
    }

    fun saveReminders(list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.atMillis)) }
        file("reminders.json").writeText(a.toString())
    }
}
