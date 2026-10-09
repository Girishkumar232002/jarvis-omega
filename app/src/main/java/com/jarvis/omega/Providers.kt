package com.jarvis.omega

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A chat model provider. history = list of (role, text) where role is "user" or "assistant".
 * Streams text deltas through onDelta. Throws on any failure.
 */
interface Provider {
    val id: String
    val label: String

    /** Capability registry: what this adapter supports in this app. */
    val capabilities: String

    fun key(s: Settings): String

    suspend fun stream(
        s: Settings,
        history: List<Pair<String, String>>,
        system: String,
        onDelta: suspend (String) -> Unit,
    )
}

object Providers {
    val all: List<Provider> = listOf(AnthropicProvider, OpenAiProvider, GeminiProvider)
}

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** Reads a server-sent-events response, calling onData with each "data:" payload. */
private suspend fun sse(req: Request, onData: suspend (String) -> Unit) {
    withContext(Dispatchers.IO) {
        val call = http.newCall(req)
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        call.execute().use { resp ->
            val body = resp.body ?: throw IOException("Empty response")
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${body.string().take(300)}")
            }
            val src = body.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val d = line.removePrefix("data:").trim()
                    if (d.isNotEmpty() && d != "[DONE]") onData(d)
                }
            }
        }
    }
}

private fun JSONObject.str(name: String): String =
    if (has(name) && !isNull(name)) optString(name, "") else ""

object AnthropicProvider : Provider {
    override val id = "anthropic"
    override val label = "Claude"
    override val capabilities = "text, streaming, long context"
    override fun key(s: Settings) = s.anthropicKey

    override suspend fun stream(
        s: Settings,
        history: List<Pair<String, String>>,
        system: String,
        onDelta: suspend (String) -> Unit,
    ) {
        val msgs = JSONArray()
        history.forEach { (role, text) ->
            msgs.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", s.anthropicModel)
            .put("max_tokens", 4096)
            .put("stream", true)
            .put("system", system)
            .put("messages", msgs)
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", s.anthropicKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        sse(req) { data ->
            val o = JSONObject(data)
            when (o.str("type")) {
                "content_block_delta" -> {
                    val d = o.optJSONObject("delta")
                    if (d != null && d.str("type") == "text_delta") {
                        val t = d.str("text")
                        if (t.isNotEmpty()) onDelta(t)
                    }
                }
                "error" -> throw IOException(o.optJSONObject("error")?.str("message") ?: "stream error")
                else -> {}
            }
        }
    }
}

object OpenAiProvider : Provider {
    override val id = "openai"
    override val label = "OpenAI"
    override val capabilities = "text, streaming"
    override fun key(s: Settings) = s.openaiKey

    override suspend fun stream(
        s: Settings,
        history: List<Pair<String, String>>,
        system: String,
        onDelta: suspend (String) -> Unit,
    ) {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (role, text) ->
            msgs.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", s.openaiModel)
            .put("stream", true)
            .put("messages", msgs)
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer ${s.openaiKey}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        sse(req) { data ->
            val o = JSONObject(data)
            val choices = o.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val delta = choices.getJSONObject(0).optJSONObject("delta")
                val t = delta?.str("content") ?: ""
                if (t.isNotEmpty()) onDelta(t)
            }
        }
    }
}

object GeminiProvider : Provider {
    override val id = "gemini"
    override val label = "Gemini"
    override val capabilities = "text, streaming, long context"
    override fun key(s: Settings) = s.geminiKey

    override suspend fun stream(
        s: Settings,
        history: List<Pair<String, String>>,
        system: String,
        onDelta: suspend (String) -> Unit,
    ) {
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            .put("contents", contents)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${s.geminiModel}:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", s.geminiKey)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        sse(req) { data ->
            val o = JSONObject(data)
            val cands = o.optJSONArray("candidates")
            if (cands != null && cands.length() > 0) {
                val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val t = parts.getJSONObject(i).str("text")
                        if (t.isNotEmpty()) onDelta(t)
                    }
                }
            }
        }
    }
}

/**
 * Chooses which provider(s) to try, in order. Simple, transparent heuristics:
 * - manual mode: only the chosen provider
 * - auto: code-like requests prefer Claude, very long inputs prefer Gemini, otherwise Claude -> Gemini -> OpenAI
 * Only providers that have an API key are considered. Later providers act as fallbacks.
 */
object Router {
    private val codeHint =
        Regex("(?i)\\b(code|function|bug|stack ?trace|compile|python|kotlin|java|sql|regex|script|debug)\\b")

    fun plan(text: String, s: Settings): List<Provider> {
        val avail = Providers.all.filter { it.key(s).isNotBlank() }
        if (s.mode != "auto") return avail.filter { it.id == s.mode }
        val pref = when {
            codeHint.containsMatchIn(text) || text.contains("\u0060\u0060\u0060") -> listOf("anthropic", "openai", "gemini")
            text.length > 4000 -> listOf("gemini", "anthropic", "openai")
            else -> listOf("anthropic", "gemini", "openai")
        }
        return avail.sortedBy { pref.indexOf(it.id) }
    }
}
