package io.github.wandersalamander.vocabtrainer.quiz

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cleans up a recorded SPEAK take for playback: decodes the AAC/M4A take to
 * PCM, gates the background noise around/between the words, lifts the speech
 * to the model TTS's loudness by scaling the samples directly, and writes a
 * 16-bit WAV.
 *
 * This replaces the earlier LoudnessEnhancer approach, which was doubly
 * unreliable: the gain was *estimated* from a handful of maxAmplitude polls
 * (noisy for a one-word take → varying loudness), and the enhancer itself is
 * capped/ignored on many devices (→ takes stuck far below the TTS). Measuring
 * and scaling the actual samples makes playback loudness deterministic.
 */
internal object TakeNormalizer {

    /**
     * Decode [input], gate the noise, normalize the loudness, and write the
     * result to [output]. Returns true when [output] is ready to play; false
     * when the take should be played as-is (pure silence or decode failure).
     */
    fun normalize(input: File, output: File): Boolean {
        return try {
            val pcm = decode(input) ?: return false
            if (pcm.samples.isEmpty()) return false
            val samplesPerSecond = pcm.sampleRate * pcm.channels
            applyNoiseGate(pcm.samples, samplesPerSecond)
            val gain = computeGain(pcm.samples, samplesPerSecond)
            if (gain > 1f) applyGain(pcm.samples, gain)
            writeWav(output, pcm.samples, pcm.sampleRate, pcm.channels)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Normalization failed, playing raw take", e)
            false
        }
    }

    private class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /**
     * Gain that lifts the take's speech loudness to [TARGET_SPEECH_RMS].
     * Speech level is the RMS over the loud frames only (≥20% of the loudest
     * frame), so leading/trailing silence and breath gaps don't drag the
     * estimate down and over-boost. Boost-only — a take is never attenuated
     * (cutting loud takes is what once made replays "extremely silent").
     */
    private fun computeGain(samples: ShortArray, samplesPerSecond: Int): Float {
        val rms = frameRms(samples, frameSize(samplesPerSecond))
        val loudest = rms.maxOrNull() ?: return 1f
        // Nothing but room tone: boosting would only amplify noise.
        if (loudest < NOISE_RMS_FLOOR) return 1f
        val speechFrames = rms.filter { it >= loudest * SPEECH_FRAME_CUTOFF }
        val speechRms =
            sqrt(speechFrames.sumOf { (it * it).toDouble() } / speechFrames.size).toFloat()
        val rmsGain = TARGET_SPEECH_RMS / speechRms
        // Clip guard on a high-percentile peak (not the absolute max, so one
        // stray pop can't veto the whole boost); the rare samples above it are
        // hard-clamped in applyGain, which is inaudible at this rate.
        val sortedAbs = IntArray(samples.size) { abs(samples[it].toInt()) }.sorted()
        val peak = sortedAbs[(sortedAbs.size * 999 / 1000).coerceAtMost(sortedAbs.size - 1)]
            .coerceAtLeast(1)
        val peakGain = PEAK_CEILING / peak
        return rmsGain.coerceAtMost(peakGain).coerceIn(1f, MAX_BOOST)
    }

    private fun applyGain(samples: ShortArray, gain: Float) {
        for (i in samples.indices) {
            samples[i] = (samples[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Attenuate the background noise around and between the words: frames well
     * below the speech level are ducked to [GATE_ATTENUATION] (not to zero —
     * hard muting sounds unnatural). Neighbors of speech frames stay open so
     * word onsets and tails aren't chopped, and the per-sample gain is
     * interpolated between frame centers so the gate never clicks or pumps.
     * Runs before the loudness boost — otherwise the boost would amplify the
     * room hiss right along with the voice.
     */
    private fun applyNoiseGate(samples: ShortArray, samplesPerSecond: Int) {
        val frame = frameSize(samplesPerSecond)
        val rms = frameRms(samples, frame)
        val loudest = rms.maxOrNull() ?: return
        // All noise (or all speech in a very quiet room): nothing to separate.
        if (loudest < NOISE_RMS_FLOOR) return
        val threshold = loudest * GATE_CUTOFF
        val open = BooleanArray(rms.size) { rms[it] >= threshold }
        val gains = FloatArray(rms.size) { i ->
            val nearSpeech = open[i] ||
                (i > 0 && open[i - 1]) ||
                (i < open.size - 1 && open[i + 1])
            if (nearSpeech) 1f else GATE_ATTENUATION
        }
        for (i in samples.indices) {
            // Position relative to frame centers → linear crossfade over ~20ms.
            val pos = i.toFloat() / frame - 0.5f
            val f0 = pos.toInt().coerceIn(0, gains.size - 1)
            val f1 = (f0 + 1).coerceAtMost(gains.size - 1)
            val t = (pos - f0).coerceIn(0f, 1f)
            val g = gains[f0] + (gains[f1] - gains[f0]) * t
            if (g < 1f) samples[i] = (samples[i] * g).roundToInt().toShort()
        }
    }

    private fun frameSize(samplesPerSecond: Int): Int =
        (samplesPerSecond * FRAME_MS / 1000).coerceAtLeast(1)

    private fun frameRms(samples: ShortArray, frame: Int): FloatArray {
        val rms = FloatArray((samples.size + frame - 1) / frame)
        var i = 0
        var f = 0
        while (i < samples.size) {
            val end = (i + frame).coerceAtMost(samples.size)
            var sum = 0.0
            for (j in i until end) {
                val v = samples[j].toDouble()
                sum += v * v
            }
            rms[f++] = sqrt(sum / (end - i)).toFloat()
            i = end
        }
        return rms
    }

    /** Decode the take to 16-bit PCM. Null on any failure or unexpected format. */
    private fun decode(input: File): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            val trackFormat = format ?: return null
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }
            val pcmBytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var outFormat = codec.outputFormat
            var inputDone = false
            var idleSpins = 0
            while (true) {
                var progressed = false
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        progressed = true
                        val buf = codec.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    progressed = true
                    val buf = codec.getOutputBuffer(outIndex) ?: return null
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    pcmBytes.write(bytes)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    outFormat = codec.outputFormat
                }
                // A take is a couple of seconds; a codec making no progress for
                // ~1s is wedged — bail out rather than hang the playback flow.
                idleSpins = if (progressed) 0 else idleSpins + 1
                if (idleSpins > MAX_IDLE_SPINS) return null
            }
            val encoding =
                if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    outFormat.getInt(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null
            val bytes = pcmBytes.toByteArray()
            val samples = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return Pcm(
                samples,
                outFormat.getInt(MediaFormat.KEY_SAMPLE_RATE),
                outFormat.getInt(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                   // fmt chunk size
        buf.putShort(1)                                  // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)            // byte rate
        buf.putShort((channels * 2).toShort())           // block align
        buf.putShort(16)                                 // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) buf.putShort(s)
        file.writeBytes(buf.array())
    }

    private const val TAG = "TakeNormalizer"

    // Loudness target for the speech-portion RMS, in 16-bit full-scale units
    // (~-15 dBFS — around where TTS voices sit). Nudge up/down if takes still
    // sound quieter/louder than the model.
    private const val TARGET_SPEECH_RMS = 6000f

    // A frame counts as speech when its RMS is within this factor of the
    // loudest frame; quieter frames are gaps/breaths and are excluded.
    private const val SPEECH_FRAME_CUTOFF = 0.2f
    private const val FRAME_MS = 20

    // Noise gate: frames below this factor of the loudest frame (-20 dB) are
    // background, ducked by GATE_ATTENUATION (~-16 dB — quiet, not dead air).
    private const val GATE_CUTOFF = 0.1f
    private const val GATE_ATTENUATION = 0.15f

    // Loudest frame below this → the take is just room tone; don't boost noise.
    private const val NOISE_RMS_FLOOR = 40f

    // Boost cap (+26 dB) so a barely-audible take can't become full-blast hiss.
    private const val MAX_BOOST = 20f

    // Post-gain ceiling for the 99.9th-percentile sample (leaves ~0.2 dB headroom).
    private const val PEAK_CEILING = 32000f

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_SPINS = 100
}
