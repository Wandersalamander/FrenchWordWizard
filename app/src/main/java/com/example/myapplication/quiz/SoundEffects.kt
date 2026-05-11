package com.example.myapplication.quiz

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * Owns the SoundPool plus the WAV synthesis used to generate the success
 * chime and spot-check "blub" the first time the app runs. The two tones are
 * synthesized rather than shipped as assets so we don't carry a copyrighted
 * sample.
 *
 * SoundPool mixes audio without requesting audio focus, so playback doesn't
 * interfere with music apps (Spotify etc).
 */
class SoundEffects(context: Context) {
    private val soundPool: SoundPool
    private val successSoundId: Int
    private val spotCheckSoundId: Int

    init {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttrs)
            .build()

        val chimeFile = File(context.cacheDir, "success_chime.wav")
        generateSuccessChimeWav(chimeFile)
        successSoundId = soundPool.load(chimeFile.absolutePath, 1)

        val blubFile = File(context.cacheDir, "blub.wav")
        generateBlubWav(blubFile)
        spotCheckSoundId = soundPool.load(blubFile.absolutePath, 1)
    }

    fun playSuccess() {
        soundPool.play(successSoundId, 1f, 1f, 1, 0, 1f)
    }

    fun playSpotCheck() {
        soundPool.play(spotCheckSoundId, 0.7f, 0.7f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }

    private fun generateSuccessChimeWav(file: File) {
        val sampleRate = 22050
        // Two ascending notes — perfect fourth (G5 -> C6): bright, positive, distinct from the
        // low spotcheck "blub" so users can tell success apart at a glance.
        val note1Freq = 783.99 // G5
        val note2Freq = 1046.50 // C6
        val note1DurMs = 110
        val note2DurMs = 380
        val totalDurMs = note1DurMs + note2DurMs
        val numSamples = sampleRate * totalDurMs / 1000
        val note1Samples = sampleRate * note1DurMs / 1000
        val attackSamples = sampleRate * 5 / 1000
        val note2DecaySamples = numSamples - note1Samples
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            var s = 0.0
            // Note 1: decays across the full buffer so it overlaps with note 2 like a real chime.
            val t1 = i.toDouble() / sampleRate
            val env1 = if (i < attackSamples) i.toDouble() / attackSamples
                else Math.exp(-4.0 * (i - attackSamples) / numSamples)
            val fund1 = Math.sin(2.0 * Math.PI * note1Freq * t1)
            val harm1 = 0.25 * Math.sin(2.0 * Math.PI * note1Freq * 2.0 * t1)
            s += 0.45 * env1 * (fund1 + harm1)
            // Note 2: starts after note 1 begins; longer tail.
            if (i >= note1Samples) {
                val li = i - note1Samples
                val lt = li.toDouble() / sampleRate
                val env2 = if (li < attackSamples) li.toDouble() / attackSamples
                    else Math.exp(-3.0 * (li - attackSamples) / note2DecaySamples)
                val fund2 = Math.sin(2.0 * Math.PI * note2Freq * lt)
                val harm2 = 0.25 * Math.sin(2.0 * Math.PI * note2Freq * 2.0 * lt)
                s += 0.55 * env2 * (fund2 + harm2)
            }
            val intSample = (s * Short.MAX_VALUE * 0.9).toInt()
            samples[i] = intSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        writeWav(file, samples, sampleRate)
    }

    private fun generateBlubWav(file: File) {
        val sampleRate = 22050
        val durationMs = 120
        val numSamples = sampleRate * durationMs / 1000
        val freq = 220.0 // low A note — gives a "blub" feel
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            // Fade envelope: quick attack, fast decay
            val envelope = if (i < numSamples / 10) i.toFloat() / (numSamples / 10)
                else 1.0f - (i.toFloat() / numSamples)
            val sample = (envelope * Short.MAX_VALUE * Math.sin(2.0 * Math.PI * freq * t)).toInt()
            samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        writeWav(file, samples, sampleRate)
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        file.outputStream().use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToBytes(36 + dataSize))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16))    // chunk size
            out.write(shortToBytes(1))   // PCM
            out.write(shortToBytes(1))   // mono
            out.write(intToBytes(sampleRate))
            out.write(intToBytes(sampleRate * 2)) // byte rate
            out.write(shortToBytes(2))   // block align
            out.write(shortToBytes(16))  // bits per sample
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize))
            for (s in samples) {
                out.write(s.toInt() and 0xFF)
                out.write((s.toInt() shr 8) and 0xFF)
            }
        }
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte()
    )

    private fun shortToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()
    )
}
