package com.jarvis.omega

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Speech-to-text using the phone's built-in recognizer. Must be used from the main thread. */
class Voice(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onState(listening: Boolean)

        /** retryable = silence / no match (safe to listen again). */
        fun onError(message: String, retryable: Boolean)
    }

    private var rec: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!t.isNullOrBlank()) cb.onPartial(t)
            }

            override fun onResults(results: Bundle?) {
                val t = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                cb.onState(false)
                if (t.isNullOrBlank()) cb.onError("", true) else cb.onFinal(t)
            }

            override fun onError(error: Int) {
                cb.onState(false)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cb.onError("", true)
                    SpeechRecognizer.ERROR_CLIENT -> {}
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        cb.onError("Microphone permission is needed", false)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> cb.onError("", true)
                    else -> cb.onError("Speech recognition error ($error)", false)
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        r.startListening(i)
        cb.onState(true)
    }

    fun stop() {
        rec?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        rec = null
        cb.onState(false)
    }
}

/** Text-to-speech using the phone's built-in engine. */
class Speaker(ctx: Context, private val onFinished: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    var speaking = false
        private set
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        main.post {
                            speaking = false
                            onFinished()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post {
                            speaking = false
                            onFinished()
                        }
                    }
                })
            }
        }
    }

    /** Strips markdown noise so it isn't read aloud. */
    private fun clean(t: String): String =
        t.replace(Regex("[*_#\u0060>]+"), "").replace(Regex("\\s+"), " ").trim()

    fun say(text: String, rate: Float) {
        val e = tts
        val spoken = clean(text).take(3500)
        if (!ready || e == null || spoken.isEmpty()) {
            onFinished()
            return
        }
        e.setSpeechRate(rate)
        speaking = true
        e.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun stop() {
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
