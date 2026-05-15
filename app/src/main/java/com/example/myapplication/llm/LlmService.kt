package com.example.myapplication.llm

import android.content.Context
import android.util.Log
import com.example.myapplication.dictionary.Language
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    // Sliding window of recently generated sentences. We pass these back to
    // the model as a "do NOT produce these" list. Without it, common words
    // (e.g. "essere" → always "Io sono felice.") collapse to the same output
    // every call regardless of seed/temperature, because the probability mass
    // for short sentences is too concentrated.
    //
    // Cross-language and cross-word — small enough (5 entries) that mixing
    // languages is harmless. Lost on app restart, which is fine; the goal is
    // anti-repetition within a session, not a permanent record.
    private const val RECENT_SENTENCES_CAPACITY = 5
    private val recentSentences = ArrayDeque<String>()

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
                if (t is CancellationException) throw t
                Log.e(TAG, "Engine init failed — ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", t)
                _status.value = Status.Unavailable(friendlyEngineError(t))
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
                if (target.exists()) target.delete()
                // Re-throw cancellation so cancelDownload's NotDownloaded state
                // isn't overwritten with an Unavailable("CancellationException…").
                if (t is CancellationException) throw t
                Log.e(TAG, "Model download failed — ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", t)
                _status.value = Status.Unavailable(friendlyDownloadError(t))
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
        everSeen: List<String>,
        language: Language,
        timeoutMs: Long = 5000L
    ): String? {
        val e = engine ?: return null
        if (!isReady) return null

        val langName = language.englishName
        // Pool of "previously studied" words we offer the model as optional
        // ingredients: 5 sampled from the recent sliding window + 5 sampled
        // from everything ever introduced. The split keeps freshness (recent)
        // alongside breadth (long-tail vocabulary) so sentences don't just
        // recycle the last 20 words. We deliberately sub-sample rather than
        // pass the full lists — sending too many candidates makes the model
        // squeeze several of them into one sentence and locks common short
        // words ("libro", "haus") into a self-reinforcing loop.
        val sampledRecent = recent.takeLast(20).shuffled().take(5)
        val recentSet = sampledRecent.toSet()
        val sampledEverSeen = everSeen
            .filter { it != word && it !in recentSet }
            .shuffled()
            .take(5)
        val sampledWords = sampledRecent + sampledEverSeen
        val recentClause = if (sampledWords.isNotEmpty()) {
            " You may also naturally include some previously studied words if it fits: ${sampledWords.joinToString(", ")}."
        } else ""
        // Anti-repetition: tell the model the last few sentences it produced
        // and ask for a clearly different one. Snapshot under lock so we
        // don't race with a concurrent successful generation appending below.
        val avoidClause = synchronized(recentSentences) {
            if (recentSentences.isEmpty()) "" else {
                val list = recentSentences.joinToString("; ") { "\"$it\"" }
                " IMPORTANT: do NOT produce any of these previously generated sentences (or trivial paraphrases). Make a clearly different one: $list."
            }
        }
        val prompt = "Write one short example sentence in $langName " +
                " using the word \"$word\" (English meaning: \"$translation\")." +
                recentClause +
                avoidClause +
                " Output ONLY the sentence, no quotes, no translation, no explanation."

        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val result = withTimeoutOrNull(timeoutMs) {
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
                        Log.i(TAG, "generating with LLM (prompt=$prompt)")

                        val convConfig = ConversationConfig(
                            samplerConfig = SamplerConfig(
                                topK = 80,
                                topP = 0.98,
                                temperature = 1.15,
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
                val elapsed = System.currentTimeMillis() - started
                if (result != null) {
                    Log.i(TAG, "generate ok in ${elapsed}ms (word=$word) -> $result")
                    // Remember this output so the next call's prompt can ask
                    // the model to avoid it. Bounded to RECENT_SENTENCES_CAPACITY.
                    synchronized(recentSentences) {
                        recentSentences.addLast(result)
                        while (recentSentences.size > RECENT_SENTENCES_CAPACITY) {
                            recentSentences.removeFirst()
                        }
                    }
                } else {
                    val timedOut = elapsed >= timeoutMs
                    val tag = if (timedOut) "TIMEOUT" else "FAIL"
                    Log.w(TAG, "generate returned null ($tag) in ${elapsed}ms (word=$word) — caller will fall back to CSV sentence")
                }
                result
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

    // Translates download failures into something the user can actually read
    // in the AI Status section of Settings. Keeps the technical exception
    // string in logs (see Log.e in startDownload) for debugging.
    private fun friendlyDownloadError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            t is UnknownHostException ->
                "No internet connection. Connect and try again."
            t is SocketTimeoutException ->
                "The download timed out. Try again on a faster or more stable network."
            t is ConnectException ->
                "Couldn't reach the download server. Try again later."
            t is SSLException ->
                "Secure connection failed. Try again later."
            // FileOutputStream surfaces ENOSPC as IOException with this text on
            // Android/Linux. Model is 2.59 GB so this is the common failure.
            msg.contains("No space left", ignoreCase = true) ||
                msg.contains("ENOSPC", ignoreCase = true) ->
                "Not enough storage on this device. Free up about 3 GB and try again."
            // ModelDownloader throws IOException("HTTP $code from $url") on non-2xx.
            msg.startsWith("HTTP 4") ->
                "The model file is no longer available at the configured URL."
            msg.startsWith("HTTP 5") ->
                "The download server is temporarily unavailable. Try again later."
            else -> "Download failed. Tap retry to try again."
        }
    }

    // LiteRT-LM doesn't expose typed exceptions for "device too weak" — GPU
    // init failure, CPU fallback failure, missing native libs, OOM, and
    // unsupported tensor shapes all surface as generic throwables. We collapse
    // them to one message because the user's recourse is the same: live
    // without the offline model. The quiz falls back to CSV sentences.
    private fun friendlyEngineError(@Suppress("UNUSED_PARAMETER") t: Throwable): String {
        return "This device can't run the offline language model. " +
                "The app will keep working with built-in example sentences."
    }
}
