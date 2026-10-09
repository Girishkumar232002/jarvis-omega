package com.jarvis.omega

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A free, open model that runs fully on the phone. No account or key needed. */
data class LocalModel(
    val id: String,
    val name: String,
    val size: String,
    val note: String,
    val url: String,
    val fileName: String,
    val minBytes: Long,
    val noThinking: Boolean = false,
)

sealed class ModelState {
    object Missing : ModelState()
    data class Downloading(val percent: Int) : ModelState()
    object Installed : ModelState()
    data class Failed(val reason: String) : ModelState()
}

object LocalModels {
    private const val HF = "https://huggingface.co/litert-community"

    val all = listOf(
        LocalModel(
            "qwen3-0.6b", "Qwen3 0.6B · fastest", "614 MB",
            "Quick, basic answers. Works on most phones (4 GB+ RAM).",
            "$HF/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            "Qwen3-0.6B.litertlm", 500_000_000L, noThinking = true,
        ),
        LocalModel(
            "qwen2.5-1.5b", "Qwen2.5 1.5B · balanced", "1.6 GB",
            "Recommended. Good everyday answers (6 GB+ RAM).",
            "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm", 1_400_000_000L,
        ),
        LocalModel(
            "gemma4-e2b", "Gemma 4 E2B · smartest", "2.6 GB",
            "Best quality, slower. Flagship phones (8 GB+ RAM).",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "gemma-4-E2B-it.litertlm", 2_300_000_000L,
        ),
    )

    lateinit var app: Context
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }

    private val prefs get() = app.getSharedPreferences("jarvis_models", Context.MODE_PRIVATE)
    private val dm get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun file(m: LocalModel): File = File(app.getExternalFilesDir(null) ?: app.filesDir, m.fileName)

    fun start(m: LocalModel) {
        remove(m)
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("JARVIS AI model")
            .setDescription(m.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, null, m.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.edit().putLong("dl_" + m.id, id).putBoolean("ok_" + m.id, false).apply()
    }

    fun remove(m: LocalModel) {
        val id = prefs.getLong("dl_" + m.id, -1L)
        if (id >= 0) {
            try {
                dm.remove(id)
            } catch (_: Exception) {
            }
        }
        file(m).delete()
        prefs.edit().remove("dl_" + m.id).remove("ok_" + m.id).apply()
        LocalLlm.forget(file(m).absolutePath)
    }

    fun state(m: LocalModel): ModelState {
        val f = file(m)
        if (prefs.getBoolean("ok_" + m.id, false) && f.exists()) return ModelState.Installed
        val id = prefs.getLong("dl_" + m.id, -1L)
        if (id < 0) return ModelState.Missing
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return ModelState.Missing
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    if (f.exists() && f.length() >= m.minBytes) {
                        prefs.edit().putBoolean("ok_" + m.id, true).apply()
                        ModelState.Installed
                    } else {
                        ModelState.Failed("file incomplete")
                    }
                DownloadManager.STATUS_FAILED -> ModelState.Failed("error $reason")
                else -> ModelState.Downloading(if (total > 0) ((done * 100) / total).toInt() else 0)
            }
        }
    }

    fun installed(id: String): Boolean = byId(id)?.let { state(it) == ModelState.Installed } ?: false
}

/** Keeps one on-device model loaded in memory. */
object LocalLlm {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null

    fun forget(path: String) {
        if (loadedKey?.startsWith(path) == true) {
            try {
                engine?.close()
            } catch (_: Exception) {
            }
            engine = null
            loadedKey = null
        }
    }

    suspend fun get(path: String, gpu: Boolean): Engine = lock.withLock {
        withContext(Dispatchers.IO) {
            val key = "$path|$gpu"
            val current = engine
            if (current != null && loadedKey == key) {
                current
            } else {
                try {
                    current?.close()
                } catch (_: Exception) {
                }
                engine = null
                loadedKey = null
                val e = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        cacheDir = LocalModels.app.cacheDir.absolutePath,
                    ),
                )
                e.initialize()
                engine = e
                loadedKey = key
                e
            }
        }
    }
}

/** Runs the selected model on the phone itself. */
object LocalProvider : Provider {
    override val id = "local"
    override val label = "On-device"
    override val capabilities = "text, streaming, fully offline, no key"
    override fun key(s: Settings) = ""
    override fun available(s: Settings) = LocalModels.installed(s.localModel)

    private val thinkBlock = Regex("<think>[\\s\\S]*?(</think>|$)")

    override suspend fun stream(
        s: Settings,
        history: List<Pair<String, String>>,
        system: String,
        onDelta: suspend (String) -> Unit,
    ) {
        val m = LocalModels.byId(s.localModel) ?: throw IllegalStateException("No on-device model selected")
        val engine = LocalLlm.get(LocalModels.file(m).absolutePath, s.useGpu)
        val recent = history.takeLast(9)
        val earlier = recent.dropLast(1).map { (role, text) ->
            if (role == "user") Message.user(text) else Message.model(text)
        }
        val last = recent.lastOrNull()?.second ?: return
        val conv = withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = earlier,
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                    thinkingConfig = if (m.noThinking) ThinkingConfig(enableThinking = false) else null,
                ),
            )
        }
        val chunks = Channel<String>(Channel.UNLIMITED)
        try {
            conv.sendMessageAsync(
                Message.user(last),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val t = message.toString()
                        if (t.isNotEmpty()) chunks.trySend(t)
                    }

                    override fun onDone() {
                        chunks.close()
                    }

                    override fun onError(throwable: Throwable) {
                        chunks.close(throwable)
                    }
                },
            )
            val raw = StringBuilder()
            var shown = ""
            for (c in chunks) {
                raw.append(c)
                val visible = thinkBlock.replace(raw.toString(), "").trimStart()
                if (visible.length > shown.length && visible.startsWith(shown)) {
                    onDelta(visible.substring(shown.length))
                    shown = visible
                }
            }
        } catch (e: CancellationException) {
            try {
                conv.cancelProcess()
            } catch (_: Exception) {
            }
            throw e
        } finally {
            try {
                conv.close()
            } catch (_: Exception) {
            }
        }
    }
}
