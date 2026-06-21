package io.github.wandersalamander.vocabtrainer.quiz

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Where a SPEAK round is in the record → compare → judge loop. */
internal enum class SpeakPhase {
    /** Waiting for the user to hold the card and speak. */
    IDLE,

    /** Mic is live, capturing the user's attempt. */
    RECORDING,

    /** Playing the model foreign-word TTS, then the user's own take. */
    PLAYING,

    /** Take has played; the user can now self-assess (Fail / Next). */
    REVIEWED,
}

/**
 * Owns the Speaking-tier recording loop: capture the user's pronunciation while
 * they hold the card, then on release play the model foreign-word TTS followed
 * by their own recording for self-comparison. A delegated component of
 * [QuizController] (mirrors SpotCheckController), driven by the card's
 * press-and-hold callbacks.
 *
 * It owns no views; every phase transition is reported through [onState] so the
 * controller can relabel buttons / status text in one place. The single take is
 * written to a reused temp file in cacheDir and overwritten on each attempt.
 */
internal class SpeakingController(
    private val activity: AppCompatActivity,
    private val tts: TtsHelper,
    private val language: Language,
    private val onState: (SpeakPhase) -> Unit,
) {
    private val outputFile: File by lazy { File(activity.cacheDir, "speak_take.m4a") }

    private var phase: SpeakPhase = SpeakPhase.IDLE
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var playbackJob: Job? = null
    private var recordStartMs: Long = 0L
    private var foreignToSpeak: CharSequence = ""

    /** True once at least one take has played this round (gates the Next button). */
    var hasRecorded: Boolean = false
        private set

    /** Recording or playing — the judging buttons should stay disabled while true. */
    val isBusy: Boolean
        get() = phase == SpeakPhase.RECORDING || phase == SpeakPhase.PLAYING

    /** Stash the prompt word's foreign pronunciation. Call from renderNewRound. */
    fun bind(vocab: Vocab) {
        foreignToSpeak = vocab.pronounceableForeign(language)
    }

    /** Silent reset for a fresh round — renderNewRound sets the initial UI itself. */
    fun reset() {
        abortPlayback()
        abortRecorder()
        hasRecorded = false
        phase = SpeakPhase.IDLE
    }

    /**
     * Release the mic/player when the app backgrounds (called from markPaused).
     * Re-emits a safe phase so the on-screen controls match on resume: a take
     * already reviewed stays judgeable, anything mid-flight drops to IDLE.
     */
    fun releaseForPause() {
        abortPlayback()
        abortRecorder()
        // Silent (no onState): markPaused runs for every skill, so emitting a
        // phase here would stomp a non-SPEAK round's buttons. The next SPEAK
        // render (configureSpeakRoundUi) restores the UI from IDLE.
        phase = if (hasRecorded) SpeakPhase.REVIEWED else SpeakPhase.IDLE
    }

    /** ACTION_DOWN on the card: start (or re-start) capturing the user's attempt. */
    fun startRecording() {
        // Re-record: tear down any in-flight playback / recorder first.
        abortPlayback()
        abortRecorder()
        val rec = try {
            buildRecorder().apply {
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recorder init failed", e)
            releaseRecorderQuietly()
            Toast.makeText(activity, "Couldn't access the microphone", Toast.LENGTH_SHORT).show()
            return
        }
        recorder = rec
        recordStartMs = SystemClock.elapsedRealtime()
        setPhase(SpeakPhase.RECORDING)
    }

    /**
     * ACTION_UP on the card: stop recording and, if the hold was long enough,
     * play the foreign-word TTS and then the user's take. Too-short holds (taps)
     * are discarded and revert to the pre-record phase.
     */
    fun stopAndPlayback() {
        if (phase != SpeakPhase.RECORDING) return
        val heldMs = SystemClock.elapsedRealtime() - recordStartMs
        val stopped = stopRecorderQuietly()
        if (!stopped || heldMs < MIN_HOLD_MS || !outputFile.exists()) {
            setPhase(if (hasRecorded) SpeakPhase.REVIEWED else SpeakPhase.IDLE)
            return
        }
        setPhase(SpeakPhase.PLAYING)
        playbackJob = activity.lifecycleScope.launch {
            try {
                // Model pronunciation first (foreign locale, awaits TTS done)...
                tts.speakSentenceAndAwait(foreignToSpeak)
                // ...then the user's own attempt, for direct comparison.
                playRecordingAndAwait()
                hasRecorded = true
                setPhase(SpeakPhase.REVIEWED)
            } catch (e: CancellationException) {
                releasePlayerQuietly()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playback failed", e)
                releasePlayerQuietly()
                // A take exists even if comparison playback failed — let them judge.
                hasRecorded = true
                setPhase(SpeakPhase.REVIEWED)
            }
        }
    }

    /** ACTION_CANCEL on the card: discard the in-flight take. */
    fun cancelRecording() {
        if (phase != SpeakPhase.RECORDING) return
        stopRecorderQuietly()
        setPhase(if (hasRecorded) SpeakPhase.REVIEWED else SpeakPhase.IDLE)
    }

    // ─────────────────────────── internals ───────────────────────────

    private fun setPhase(p: SpeakPhase) {
        phase = p
        onState(p)
    }

    private fun buildRecorder(): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(activity)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
        }
    }

    /**
     * Stop + release the recorder. [MediaRecorder.stop] throws when no data was
     * captured (an over-short take); returns true only on a clean stop with a
     * usable file.
     */
    private fun stopRecorderQuietly(): Boolean {
        val rec = recorder ?: return false
        recorder = null
        return try {
            rec.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            rec.release()
        }
    }

    private fun abortRecorder() {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
    }

    private fun releaseRecorderQuietly() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    private suspend fun playRecordingAndAwait() = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        cont.invokeOnCancellation { releasePlayerQuietly() }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(outputFile.absolutePath)
            mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            mp.setOnErrorListener { _, _, _ ->
                if (cont.isActive) cont.resume(Unit)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Recording playback failed", e)
            releasePlayerQuietly()
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun abortPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        tts.stop()
        releasePlayerQuietly()
    }

    private fun releasePlayerQuietly() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    companion object {
        private const val TAG = "SpeakingController"

        // Holds shorter than this are treated as accidental taps: discarded, no
        // playback. Also guards MediaRecorder.stop()'s "no data" exception.
        private const val MIN_HOLD_MS = 350L
    }
}
