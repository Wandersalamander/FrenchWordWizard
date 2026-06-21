package io.github.wandersalamander.vocabtrainer.quiz

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
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
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    private val outputFile: File by lazy { File(activity.cacheDir, "speak_take.m4a") }

    private var phase: SpeakPhase = SpeakPhase.IDLE
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null  // boosts a quiet take on playback
    private var playbackJob: Job? = null   // the auto-play (model + take) after a record
    private var replayJob: Job? = null     // a manual tap-to-replay of the take
    private var ampJob: Job? = null        // amplitude polling while recording
    private var pendingStartJob: Job? = null // delayed (reaction-skip) recorder start
    private var recordStartMs: Long = 0L
    private var foreignToSpeak: CharSequence = ""

    // Per-poll mic peaks (linear, 0..32767) for the current take; used to pick a
    // playback gain that matches the take's loudness to the model TTS.
    private val recLevels = ArrayList<Float>()
    private var playbackGain = 1f

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
        recLevels.clear()
        playbackGain = 1f
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
     * the actual capture [RECORD_START_SKIP_MS] later — the user needs ~200ms to
     * react and start talking after seeing the hint, and that leading silence
     * shouldn't be recorded (the bars then start right as they begin speaking).
     */
    fun startRecording() {
        abortPlayback()
        abortRecorder()
        // A fresh take invalidates any prior one (the file is overwritten), so
        // clear the waveform and the recorded flag until this take completes.
        hasRecorded = false
        recLevels.clear()
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
            playbackGain = computeNormalizationGain()
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
        if (!hasRecorded || isBusy || !outputFile.exists()) return
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

    /**
     * Pick a playback gain that lifts this take toward the model TTS's loudness.
     * The take (MediaPlayer, USAGE_MEDIA) and the TTS both play on STREAM_MUSIC
     * at the same media volume, so matching their *digital* levels matches what
     * the ear hears. We estimate the take's speech level from a high percentile
     * of the per-poll mic peaks — ignoring silent gaps and the odd transient —
     * and scale it toward [TARGET_LEVEL]. Clamped so a near-silent take can't
     * amplify room noise to full volume.
     */
    private fun computeNormalizationGain(): Float {
        if (recLevels.isEmpty()) return 1f
        val sorted = recLevels.sorted()
        val level = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        if (level < SILENCE_FLOOR) return 1f
        return (TARGET_LEVEL / level).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /**
     * Apply [playbackGain] to [mp]: attenuation rides MediaPlayer's own volume,
     * but a boost needs a LoudnessEnhancer (player volume can't exceed 1.0; the
     * enhancer also has an internal limiter, so the lift won't clip harshly).
     */
    private fun applyGain(mp: MediaPlayer) {
        if (playbackGain <= 1f) {
            mp.setVolume(playbackGain, playbackGain)
            return
        }
        enhancer = try {
            LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain((2000.0 * log10(playbackGain.toDouble())).toInt())
                enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer unavailable", e)
            null
        }
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
                recLevels.add(linear)
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
            mp.setDataSource(outputFile.absolutePath)
            mp.prepare()
            applyGain(mp)
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
            enhancer?.release()
        } catch (_: Exception) {
        }
        enhancer = null
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

        // The capture window is shifted +200ms on both ends: skip the user's
        // reaction time after the "recording" hint (so the leading silence isn't
        // recorded), and keep the mic open a beat past release so the tail of the
        // word isn't clipped.
        private const val RECORD_START_SKIP_MS = 200L
        private const val RECORD_TAIL_MS = 200L

        // Amplitude poll cadence while recording → one waveform bar each.
        private const val AMP_POLL_MS = 60L

        // Playhead refresh cadence during playback.
        private const val PLAYHEAD_TICK_MS = 40L

        // MediaRecorder.getMaxAmplitude() is 16-bit full scale; speech rarely
        // nears it, so the sqrt scaling lifts the typical range into view.
        private const val MAX_AMPLITUDE = 32767f

        // Loudness-match target for a take's 90th-percentile peak, in 16-bit
        // full-scale units (~-6 dBFS). Most TTS voices master around here; nudge
        // up/down if the model still sounds louder/quieter than the take.
        private const val TARGET_LEVEL = 16_000f

        // Below this the take is treated as silence (don't amplify room noise).
        private const val SILENCE_FLOOR = 800f

        // Gain clamp: never cut a loud take below half, never boost past +18 dB.
        private const val MIN_GAIN = 0.5f
        private const val MAX_GAIN = 8f
    }
}
