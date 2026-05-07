package com.example.myapplication

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

object LlmService {
    private const val TAG = "LlmService"

    // Default model: Gemma 4 E2B-it (Apache 2.0, ungated). ~2.59 GB.
    // Multilingual: 35+ languages out-of-box, 140+ pre-trained — strong on
    // German/French/Italian (where Qwen 2.5 was producing garbage).
    //
    // Per Google's Samsung S26 Ultra benchmarks: 52 tk/s decode on GPU,
    // 0.3s time-to-first-token, 676 MB GPU memory. A ~10-word sentence
    // generates in well under 1s on flagship phones; even with the
    // fallback CPU backend it should fit a 3s budget on mid-range hardware.
    //
    // Note: this model uses the LiteRT-LM SDK (.litertlm format), NOT the
    // older MediaPipe LLM Inference API (.task format) we used for Qwen.
    // The runtime swap is why the dependency in app/build.gradle changed.
    //
    // To sideload instead of downloading the 2.59 GB file:
    //   adb push gemma-4-E2B-it.litertlm \
    //     /sdcard/Android/data/com.example.myapplication/files/llm/model.litertlm
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    private const val PREFS = "llm"
    private const val KEY_DOWNLOADED_URL = "model_url"

    sealed class Status {
        object Unknown : Status()
        object NotDownloaded : Status()
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : Status()
        object Initializing : Status()
        object Ready : Status()
        data class Unavailable(val reason: String) : Status()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var engine: Engine? = null
    @Volatile private var initInFlight: Boolean = false
    @Volatile private var downloadJob: Job? = null

    val isReady: Boolean get() = _status.value is Status.Ready

    fun modelFile(context: Context): File {
        // External app-specific storage so the model can be sideloaded via
        // `adb push` without root:
        //   /sdcard/Android/data/<app id>/files/llm/model.litertlm
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, "llm"), "model.litertlm")
    }

    fun warmup(context: Context) {
        if (initInFlight) return
        val current = _status.value
        if (current is Status.Ready ||
            current is Status.Downloading ||
            current is Status.Initializing) return

        val appContext = context.applicationContext
        val file = modelFile(appContext)
        // Pre-create the directory so users can `adb push` here without mkdir.
        file.parentFile?.mkdirs()

        // One-time cleanup: remove any leftover MediaPipe .task file from the
        // pre-Gemma-4 era. The new runtime can't load it and it just wastes
        // ~1.6 GB of disk if we leave it.
        val staleTask = File(file.parentFile, "model.task")
        if (staleTask.exists()) {
            Log.i(TAG, "Removing stale MediaPipe model.task (${staleTask.length()} bytes)")
            staleTask.delete()
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_DOWNLOADED_URL).apply()
        }

        // If the .litertlm on disk was downloaded from a URL that no longer
        // matches MODEL_URL (e.g. we updated the constant), delete it so the
        // next download picks up the new model. Sideloaded files (no saved
        // URL) are preserved.
        if (file.exists()) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(KEY_DOWNLOADED_URL, null)
            if (savedUrl != null && savedUrl != MODEL_URL) {
                Log.i(TAG, "MODEL_URL changed — deleting stale model.\n  was: $savedUrl\n  now: $MODEL_URL")
                file.delete()
                prefs.edit().remove(KEY_DOWNLOADED_URL).apply()
            }
        }

        if (!file.exists() || file.length() == 0L) {
            Log.i(TAG, "Model file not present at ${file.absolutePath}")
            _status.value = Status.NotDownloaded
            return
        }
        initInFlight = true
        _status.value = Status.Initializing
        Log.i(TAG, "Loading model from ${file.absolutePath} (${file.length()} bytes)")
        scope.launch {
            try {
                val e = createEngine(file)
                engine = e
                _status.value = Status.Ready
                Log.i(TAG, "LiteRT-LM engine ready")
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                Log.e(TAG, "Engine init failed — $reason", t)
                _status.value = Status.Unavailable(reason)
            } finally {
                initInFlight = false
            }
        }
    }

    private fun createEngine(file: File): Engine {
        // Try GPU first — substantially faster on supported devices. Fall back
        // to CPU if GPU init fails (driver issue, unsupported tensor shape,
        // insufficient GPU memory, etc.).
        return try {
            val gpuConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.GPU()
            )
            val e = Engine(gpuConfig)
            e.initialize()
            Log.i(TAG, "Inference backend: GPU")
            e
        } catch (gpuError: Throwable) {
            Log.w(TAG, "GPU backend unavailable, falling back to CPU", gpuError)
            val cpuConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU()
            )
            val e = Engine(cpuConfig)
            e.initialize()
            Log.i(TAG, "Inference backend: CPU")
            e
        }
    }

    fun startDownload(context: Context) {
        if (downloadJob?.isActive == true) return
        if (MODEL_URL.isBlank()) {
            _status.value = Status.Unavailable(
                "No download URL configured. Sideload the .litertlm file via adb push to:\n" +
                    modelFile(context).absolutePath
            )
            return
        }
        val appContext = context.applicationContext
        val target = modelFile(appContext)
        downloadJob = scope.launch {
            try {
                _status.value = Status.Downloading(0L, 0L)
                Log.i(TAG, "Starting model download from $MODEL_URL")
                ModelDownloader.download(MODEL_URL, target) { downloaded, total ->
                    _status.value = Status.Downloading(downloaded, total)
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DOWNLOADED_URL, MODEL_URL).apply()
                Log.i(TAG, "Model download complete (${target.length()} bytes)")
                // Reset to NotDownloaded then re-warmup so it transitions Initializing -> Ready.
                _status.value = Status.NotDownloaded
                warmup(appContext)
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                Log.e(TAG, "Model download failed — $reason", t)
                if (target.exists()) target.delete()
                _status.value = Status.Unavailable(reason)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _status.value = Status.NotDownloaded
    }

    fun deleteModel(context: Context) {
        if (downloadJob?.isActive == true) {
            downloadJob?.cancel()
            downloadJob = null
        }
        val appContext = context.applicationContext
        val file = modelFile(appContext)
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Engine.close() failed", t)
        }
        engine = null
        val deleted = if (file.exists()) file.delete() else true
        Log.i(TAG, "Model deleted: $deleted (${file.absolutePath})")
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_DOWNLOADED_URL).apply()
        _status.value = Status.NotDownloaded
    }

    fun retry(context: Context) {
        if (initInFlight) return
        if (_status.value is Status.Ready) return
        warmup(context)
    }

    suspend fun generate(
        word: String,
        translation: String,
        recent: List<String>,
        lang: String,
        timeoutMs: Long = 5000L
    ): String? {
        val e = engine ?: return null
        if (!isReady) return null

        val langName = when (lang) {
            "de" -> "German"
            "it" -> "Italian"
            else -> "French"
        }
        val recentClause = if (recent.isNotEmpty()) {
            " You may also naturally include one of these recently studied words if it fits: ${recent.joinToString(", ")}."
        } else ""
        val prompt = "Write one short example sentence in $langName " +
                "(about 10 words) using the word \"$word\" (English meaning: \"$translation\")." +
                recentClause +
                " Output ONLY the sentence, no quotes, no translation, no explanation."

        return mutex.withLock {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(timeoutMs) {
                    try {
                        // Each generate() call is independent — open a fresh
                        // conversation so prior prompts don't bias the output.
                        //
                        // Sampling config notes:
                        //   - SamplerConfig.seed defaults to 0 in the SDK,
                        //     which makes generation deterministic — same
                        //     prompt + seed = same sentence every time. We
                        //     randomize the seed per call so the user sees
                        //     genuinely fresh sentences, not a hidden cache.
                        //   - temperature 0.9 + topK 40 + topP 0.95 are
                        //     standard "creative but coherent" values; tune
                        //     down to ~0.7 if outputs drift off-topic.
                        //
                        // Message has no .text accessor; toString() returns
                        // the concatenated Contents text (verified against
                        // litert-lm source: Message.kt overrides
                        // toString() = contents.toString()).
                        val convConfig = ConversationConfig(
                            samplerConfig = SamplerConfig(
                                topK = 40,
                                topP = 0.95,
                                temperature = 0.9,
                                seed = Random.nextInt(),
                            )
                        )
                        val responseText: String = e.createConversation(convConfig).use { conv ->
                            conv.sendMessage(prompt).toString()
                        }
                        cleanup(responseText)
                    } catch (t: Throwable) {
                        Log.w(TAG, "generate failed", t)
                        null
                    }
                }
            }
        }
    }

    private fun cleanup(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val quoteChars = charArrayOf(
            '"', '\'', '`',
            '«', '»',           // « »
            '“', '”',           // “ ”
            '‘', '’'            // ‘ ’
        )
        val s = firstLine.trim(*quoteChars)
        return s.ifBlank { null }
    }
}
