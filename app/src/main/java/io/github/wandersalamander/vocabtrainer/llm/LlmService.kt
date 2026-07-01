package io.github.wandersalamander.vocabtrainer.llm

import android.content.Context
import android.util.Log
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
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
    //     /sdcard/Android/data/io.github.wandersalamander.vocabtrainer/files/llm/model.litertlm
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    // SHA256 of the model file at MODEL_URL. ModelDownloader hashes the
    // downloaded bytes and rejects a mismatch, turning a corrupted/truncated
    // 2.59 GB transfer into a clean, retryable "integrity check failed" instead
    // of a cryptic engine-init crash on a half-written file.
    //
    // Value is the git-LFS object id (= sha256 of the file content) HuggingFace
    // publishes for gemma-4-E2B-it.litertlm (2,588,147,712 bytes), read from the
    // repo tree API on 2026-05-22. If MODEL_URL is repointed at a different
    // model, update this too — or set it back to null to capture the new value
    // from ModelDownloader's "sha256=..." log line on the next download.
    private val MODEL_SHA256: String? =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

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

    // Sampler config for sentence generation. Kept deliberately cool:
    // "natural / the way people actually say it" IS the high-probability path
    // for a small model, so heat trades naturalness for novelty and produces
    // grammatical-but-stilted sentences. Variety across calls comes from the
    // prompt instead — SentenceStyles rotates theme × phrasing × grammar
    // construct, and the seed is randomized per call — so the sampler doesn't
    // have to carry it.
    private const val SAMPLER_TOP_K = 40
    private const val SAMPLER_TOP_P = 0.95
    private const val SAMPLER_TEMPERATURE = 0.6

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
                _status.value = Status.Unavailable(friendlyEngineError())
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
                ModelDownloader.download(MODEL_URL, target, MODEL_SHA256) { downloaded, total ->
                    _status.value = Status.Downloading(downloaded, total)
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DOWNLOADED_URL, MODEL_URL).apply()
                Log.i(TAG, "Model download complete (${target.length()} bytes)")
                // A user-initiated download that succeeds is an explicit opt-in
                // to AI sentences — switch the example-sentence preference to LLM
                // so the feature turns on without a second trip to Settings.
                // Done here (not in SettingsActivity) so it sticks even if the
                // user left the screen during the multi-GB download. The quiz
                // only actually uses the LLM once warmup reaches Ready; until
                // then it falls back to CSV sentences.
                SentenceSource.persist(appContext, SentenceSource.LLM)
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
        language: Language,
        maxImportance: Int,
        timeoutMs: Long = 5000L
    ): String? {
        val e = engine ?: return null
        if (!isReady) return null

        val prompt = buildPrompt(word, translation, language, maxImportance)
        val result = runInference(e, prompt, timeoutMs, word)
        return result
    }

    /**
     * Compose the model prompt. Mixes:
     *  - the target word + its English gloss,
     *  - a single [SentenceStyle] flavour chosen per call (cultural framing
     *    or a difficulty-gated grammar construct — see [SentenceStyles.pick]).
     *
     * Tuned for a small on-device instruct model (Gemma E2B):
     *  - one constraint per sentence, task first, output format last — the
     *    positions an instruct model weights most;
     *  - the style clause sits second-to-last, in the recency slot: it's the
     *    constraint most at risk of being dropped, and the one whose loss
     *    hurts most (all variety collapses back to one sentence shape);
     *  - explicit permission to conjugate/inflect the word, otherwise small
     *    models force the citation form in awkwardly ("Io essere felice");
     *  - a shortness cue, which both reads more natural and keeps decode
     *    time inside the caller's timeout budget.
     */
    private fun buildPrompt(
        word: String,
        translation: String,
        language: Language,
        maxImportance: Int,
    ): String {
        val styleClause = SentenceStyles.pick(maxImportance).promptFragment(language)
        return "Write one short example sentence in ${language.englishName} for a vocabulary learner." +
                " The sentence must use the word \"$word\" (English meaning: \"$translation\")." +
                " You may conjugate or inflect the word so it fits the sentence naturally." +
                " Use everyday ${language.englishName}, the way a native speaker would actually say it." +
                styleClause +
                " Reply with ONLY the ${language.englishName} sentence — no introduction, no quotes, no translation, no explanation."
    }

    /**
     * Drive the LLM through one prompt under the engine mutex (one call in
     * flight at a time) on the default dispatcher, capped by [timeoutMs].
     *
     * Per-call we open a fresh conversation and randomize [SamplerConfig.seed]
     * — the SDK's seed defaults to 0, which makes generation deterministic
     * (same prompt = same sentence every call) and silently caches behind the
     * user's back. [word] is logged for diagnostic correlation with the
     * caller's fallback path.
     *
     * Returns the cleaned sentence on success, or null on timeout / engine
     * error (caller falls back to a CSV sentence).
     */
    private suspend fun runInference(
        engine: Engine,
        prompt: String,
        timeoutMs: Long,
        word: String,
    ): String? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    Log.i(TAG, "generating with LLM (prompt=$prompt)")
                    val convConfig = ConversationConfig(samplerConfig = newSamplerConfig())
                    val responseText: String = engine.createConversation(convConfig).use { conv ->
                        // Message.toString() returns the concatenated Contents
                        // text — the SDK's own getting-started guide prints the
                        // returned Message directly, relying on this. (0.13.1
                        // also added a Message.text accessor; toString() is kept
                        // as the equivalent so this line is unchanged by the bump.)
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
            } else {
                val tag = if (elapsed >= timeoutMs) "TIMEOUT" else "FAIL"
                Log.w(TAG, "generate returned null ($tag) in ${elapsed}ms (word=$word) — caller will fall back to CSV sentence")
            }
            result
        }
    }

    private fun newSamplerConfig(): SamplerConfig = SamplerConfig(
        topK = SAMPLER_TOP_K,
        topP = SAMPLER_TOP_P,
        temperature = SAMPLER_TEMPERATURE,
        seed = Random.nextInt(),
    )

    private fun cleanup(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Despite "no introduction" in the prompt, a small instruct model
        // occasionally emits a preamble line ("Here is a sentence:") before
        // the sentence. Preambles end with a colon — skip past them instead
        // of returning one as the "sentence".
        val line = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .firstOrNull { !it.endsWith(":") && !it.endsWith("：") }
            ?: return null
        val edgeNoise = charArrayOf(
            '"', '\'', '`',
            '«', '»',           // « »
            '“', '”',           // “ ”
            '‘', '’',           // ‘ ’
            '*', '_',           // markdown bold/italics wrapping
            '-',                // list bullet / dialogue dash
            ' ',                // space freed up by trimming the above
        )
        val s = line.trim(*edgeNoise)
        return s.ifBlank { null }
    }

    // Translates download failures into something the user can actually read
    // in the AI Status section of Settings. Keeps the technical exception
    // string in logs (see Log.e in startDownload) for debugging.
    private fun friendlyDownloadError(t: Throwable): String = when (t) {
        is UnknownHostException -> "No internet connection. Connect and try again."
        is SocketTimeoutException -> "The download timed out. Try again on a faster or more stable network."
        is ConnectException -> "Couldn't reach the download server. Try again later."
        is SSLException -> "Secure connection failed. Try again later."
        else -> messageBasedDownloadError(t.message.orEmpty())
    }

    private fun messageBasedDownloadError(msg: String): String = when {
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
        msg.startsWith("SHA256 mismatch") ->
            "The downloaded model failed an integrity check. The file on the server may have changed; check for an app update."
        else -> "Download failed. Tap retry to try again."
    }

    // LiteRT-LM doesn't expose typed exceptions for "device too weak" — GPU
    // init failure, CPU fallback failure, missing native libs, OOM, and
    // unsupported tensor shapes all surface as generic throwables. We collapse
    // them to one message because the user's recourse is the same: live
    // without the offline model. The quiz falls back to CSV sentences.
    private fun friendlyEngineError(): String =
        "This device can't run the offline language model. " +
                "The app will keep working with built-in example sentences."
}
