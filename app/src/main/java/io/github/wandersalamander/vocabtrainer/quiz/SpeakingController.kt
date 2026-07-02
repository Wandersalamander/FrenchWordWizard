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
import io.github.wandersalamander.vocabtrainer.quiz.ui.RecordingWaveformView
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a SPEAK round is in the record → compare → judge loop. */
internal enum class SpeakPhase {
    /** Waiting for the user to hold the widget and speak. */
    IDLE,

    /** Mic is live, capturing the user's attempt. */
    RECORDING,

    /** Auto-playing the model foreign-word TTS, then the user's take. */
    PLAYING,

    /** Take has played; the user can self-assess and replay freely. */
    REVIEWED,
}

/**
 * Owns the Speaking-tier recording loop: capture the user's pronunciation while
 * they hold the waveform widget, then on release auto-play the model foreign-word
 * TTS followed by their own take for self-comparison — after which they can
 * replay either side as many times as they like before judging. A delegated
 * component of [QuizController] (mirrors SpotCheckController).
 *
 * Drives [waveform] directly: live amplitude bars while recording and the
 * playhead fill during playback. Phase transitions are reported through [onState]
 * so the controller updates buttons/status in one place. The single take is
 * written to a reused temp file in cacheDir, overwritten on each attempt.
 */
internal class SpeakingController(
    private val activity: AppCompatActivity,
    private val tts: TtsHelper,
    private val language: Language,
    private val waveform: RecordingWaveformView,
    private val onState: (SpeakPhase) -> Unit,
    // Fired once each time a take finishes recording (the file is ready), before
    // the model+take auto-play. The host counts these to penalize re-records.
    private val onTakeRecorded: () -> Unit = {},
) {
    private val outputFile: File by lazy { File(activity.cacheDir, "speak_take.m4a") }
    private val normalizedFile: File by lazy { File(activity.cacheDir, "speak_take.wav") }

    private var phase: SpeakPhase = SpeakPhase.IDLE
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var playbackJob: Job? = null   // the auto-play (model + take) after a record
    private var replayJob: Job? = null     // a manual tap-to-replay of the take
    private var ampJob: Job? = null        // amplitude polling while recording
    private var pendingStartJob: Job? = null // delayed (reaction-skip) recorder start
    private var recordStartMs: Long = 0L
    private var foreignToSpeak: CharSequence = ""

    // What playback uses: the loudness-normalized WAV when TakeNormalizer
    // succeeds, otherwise the raw recording.
    private var takeToPlay: File = File("")

    /** True once a take has been recorded and reviewed this round (gates Next). */
    var hasRecorded: Boolean = false
        private set

    /** Recording or auto-playing — the judging buttons stay disabled while true. */
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
        waveform.reset()
        phase = SpeakPhase.IDLE
    }

    /**
     * Release the mic/player when the app backgrounds (called from markPaused).
     * Drops to a safe phase silently so a non-SPEAK round's buttons aren't
     * stomped (markPaused runs for every skill); the next SPEAK render restores UI.
     */
    fun releaseForPause() {
        abortPlayback()
        abortRecorder()
        phase = if (hasRecorded) SpeakPhase.REVIEWED else SpeakPhase.IDLE
    }

    /**
     * Hold engaged on the widget: show the "recording" UI immediately, but begin
     * the actual capture [RECORD_START_SKIP_MS] later — the user needs ~100ms to
     * react and start talking after seeing the hint, and that leading silence
     * shouldn't be recorded (the bars then start right as they begin speaking).
     */
    fun startRecording() {
        abortPlayback()
        abortRecorder()
        // A fresh take invalidates any prior one (the file is overwritten), so
        // clear the waveform and the recorded flag until this take completes.
        hasRecorded = false
        waveform.reset()
        setPhase(SpeakPhase.RECORDING)   // immediate visual hint; capture starts below
        pendingStartJob = activity.lifecycleScope.launch {
            delay(RECORD_START_SKIP_MS)
            val rec = try {
                buildRecorder().apply {
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recorder init failed", e)
                releaseRecorderQuietly()
                Toast.makeText(activity, "Couldn't access the microphone", Toast.LENGTH_SHORT)
                    .show()
                setPhase(SpeakPhase.IDLE)
                return@launch
            }
            recorder = rec
            recordStartMs = SystemClock.elapsedRealtime()
            startAmpPolling()
        }
    }

    /**
     * Hold released: keep recording [RECORD_TAIL_MS] longer so the end of the word
     * isn't clipped, then auto-play the foreign-word TTS and the take. A hold too
     * short for capture to have begun — or too short overall — is discarded.
     */
    fun stopAndPlayback() {
        if (phase != SpeakPhase.RECORDING) return
        if (recorder == null) {
            // Released before capture even began (held < RECORD_START_SKIP_MS):
            // nothing to keep, so drop the pending start and reset.
            pendingStartJob?.cancel()
            pendingStartJob = null
            cancelAmpPolling()
            waveform.reset()
            setPhase(SpeakPhase.IDLE)
            return
        }
        playbackJob = activity.lifecycleScope.launch {
            // Tail: keep the mic open a beat past the release, then stop.
            delay(RECORD_TAIL_MS)
            cancelAmpPolling()
            val heldMs = SystemClock.elapsedRealtime() - recordStartMs
            val stopped = stopRecorderQuietly()
            if (!stopped || heldMs < MIN_RECORD_MS || !outputFile.exists()) {
                waveform.reset()
                setPhase(SpeakPhase.IDLE)
                return@launch
            }
            // Lift the take to the model TTS's loudness by rewriting its actual
            // samples (deterministic, unlike per-device playback effects). Falls
            // back to the raw recording if the decode/normalize step fails.
            takeToPlay = withContext(Dispatchers.IO) {
                if (TakeNormalizer.normalize(outputFile, normalizedFile)) {
                    normalizedFile
                } else {
                    outputFile
                }
            }
            onTakeRecorded()
            setPhase(SpeakPhase.PLAYING)
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
                Log.w(TAG, "Auto-play failed", e)
                releasePlayerQuietly()
                hasRecorded = true
                setPhase(SpeakPhase.REVIEWED)
            }
        }
    }

    /** Hold cancelled (pointer left / interrupted): discard the in-flight take. */
    fun cancelRecording() {
        if (phase != SpeakPhase.RECORDING) return
        pendingStartJob?.cancel()
        pendingStartJob = null
        cancelAmpPolling()
        stopRecorderQuietly()
        waveform.reset()
        setPhase(SpeakPhase.IDLE)
    }

    /**
     * Replay just the take (tap on the widget), animating the playhead. Runs in
     * the REVIEWED phase without flipping to PLAYING, so the judging buttons stay
     * live — the user can replay as many times as they want before deciding.
     */
    fun replayRecording() {
        if (!hasRecorded || isBusy || !takeToPlay.exists()) return
        replayJob?.cancel()
        replayJob = activity.lifecycleScope.launch {
            try {
                playRecordingAndAwait()
            } catch (e: CancellationException) {
                releasePlayerQuietly()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Replay failed", e)
                releasePlayerQuietly()
            } finally {
                waveform.setPlayedFraction(0f)
            }
        }
    }

    /** Stop a manual replay (e.g. the user tapped the foreign word instead). */
    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        releasePlayerQuietly()
        waveform.setPlayedFraction(0f)
    }

    // ─────────────────────────── internals ───────────────────────────

    private fun setPhase(p: SpeakPhase) {
        phase = p
        onState(p)
    }

    private fun startAmpPolling() {
        ampJob = activity.lifecycleScope.launch {
            while (true) {
                delay(AMP_POLL_MS)
                val rec = recorder ?: break
                val amp = try {
                    rec.maxAmplitude
                } catch (e: Exception) {
                    0
                }
                val linear = amp.coerceAtLeast(0).toFloat()
                // Perceptual (sqrt) scaling so quiet speech still moves the bars.
                waveform.addSample(sqrt(linear / MAX_AMPLITUDE))
            }
        }
    }

    private fun cancelAmpPolling() {
        ampJob?.cancel()
        ampJob = null
    }

    private fun buildRecorder(): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(activity)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return rec.apply {
            // VOICE_RECOGNITION is tuned for speech and skips the aggressive AGC
            // of the default MIC source, so levels stay consistent (better input
            // for normalization) and the timbre stays true for self-comparison.
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(outputFile.absolutePath)
        }
    }

    /**
     * Stop + release the recorder. [MediaRecorder.stop] throws when no data was
     * captured (an over-short take); returns true only on a clean stop.
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
        pendingStartJob?.cancel()
        pendingStartJob = null
        cancelAmpPolling()
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
    }

    private fun releaseRecorderQuietly() {
        cancelAmpPolling()
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    /** Play the take to completion, driving the waveform playhead. */
    private suspend fun playRecordingAndAwait() {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(takeToPlay.absolutePath)
            mp.prepare()
            mp.setVolume(1f, 1f)
            mp.start()
            val duration = mp.duration.coerceAtLeast(1)
            // isPlaying flips false at completion; delay() throws on cancellation.
            while (mp.isPlaying) {
                waveform.setPlayedFraction(mp.currentPosition.toFloat() / duration)
                delay(PLAYHEAD_TICK_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recording playback failed", e)
        } finally {
            releasePlayerQuietly()
            // Reset the playhead so the resting waveform looks the same after an
            // auto-play and a manual replay.
            waveform.setPlayedFraction(0f)
        }
    }

    private fun abortPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        replayJob?.cancel()
        replayJob = null
        tts.stop()
        releasePlayerQuietly()
        waveform.setPlayedFraction(0f)
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

        // Recordings shorter than this (hold-engage to release) are discarded
        // (mostly silence / a slip).
        private const val MIN_RECORD_MS = 250L

        // The capture window is shifted +100ms on both ends: skip the user's
        // reaction time after the "recording" hint (so the leading silence isn't
        // recorded), and keep the mic open a beat past release so the tail of the
        // word isn't clipped.
        private const val RECORD_START_SKIP_MS = 100L
        private const val RECORD_TAIL_MS = 100L

        // Amplitude poll cadence while recording → one waveform bar each.
        private const val AMP_POLL_MS = 60L

        // Playhead refresh cadence during playback.
        private const val PLAYHEAD_TICK_MS = 40L

        // MediaRecorder.getMaxAmplitude() is 16-bit full scale; speech rarely
        // nears it, so the sqrt scaling lifts the typical range into view.
        private const val MAX_AMPLITUDE = 32767f
    }
}
