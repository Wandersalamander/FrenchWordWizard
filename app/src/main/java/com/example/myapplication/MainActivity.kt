package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var textToSpeech: TextToSpeech

    private lateinit var vocabDictionary: MyDictionary
    private var currentVocab: Vocab? = null
    private var startTime: Long? = null
    private var endTime: Long? = null
    private var timeElapsed: Long? = null
    @Volatile
    private var latch = CountDownLatch(1)

    private lateinit var buttonFail: Button
    private lateinit var buttonNext: Button
    private lateinit var buttonNew: Button
    private lateinit var buttonTip: Button

    private lateinit var textFr: TextView
    private lateinit var textScore: TextView
    private lateinit var textProgressFinished: TextView
    private lateinit var textProgressActive: TextView
    private lateinit var textProgressUnseen: TextView
    private lateinit var textEn: TextView
    private lateinit var textGuessLong: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressBarFinished: ProgressBar
    private lateinit var serviceIntent: Intent
    private var soundPool: SoundPool? = null
    private var successSoundId: Int = 0
    private var spotCheckSoundId: Int = 0
    private var inSpotCheck: Boolean = false
    private val spotCheckProbability = 0.2


    private val channelId = MyForegroundService.NOTIFICATION_CHANNEL_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceIntent = Intent(
            this, MyForegroundService::class.java
        )
        ContextCompat.startForegroundService(
            this, serviceIntent
        )
        // fix bug that occurred using AndroidStudio IDE that prevented
        // the application from running several times
        // without this fix the app had to be uninstalled each time executing

        val dexOutputDir: File = codeCacheDir
        dexOutputDir.setReadOnly()

        // Initialize SoundPool for the success sound
        // SoundPool mixes audio without requesting audio focus, so it won't interfere with Spotify etc.
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttrs)
            .build()
        successSoundId = soundPool!!.load(this, R.raw.duolingo_sucess, 1)

        // Generate a short "blub" tone as a WAV file and load into SoundPool
        val blubFile = File(cacheDir, "blub.wav")
        generateBlubWav(blubFile)
        spotCheckSoundId = soundPool!!.load(blubFile.absolutePath, 1)



        setContentView(R.layout.activity_main)
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {

            }

            override fun onDone(utteranceId: String) {
                if (utteranceId == "yourUtteranceIdEn") {
                    latch.countDown()
                }

            }

            override fun onError(p0: String?) {
                println("TTS error: $p0")
                latch.countDown()
            }


        })

        createNotificationChannel()



        buttonFail = findViewById(R.id.button_fail)
        buttonNext = findViewById(R.id.button_next)
        buttonNew = findViewById(R.id.button_new)
        buttonTip = findViewById(R.id.button_tip)


        buttonFail.isClickable = false
        buttonFail.isEnabled = false

        buttonNew.isClickable = false
        buttonNew.isEnabled = false
        buttonTip.isClickable = false
        buttonTip.isEnabled = false

        textFr = findViewById(R.id.textGuess)
        textScore = findViewById(R.id.textScore)
        textProgressFinished = findViewById(R.id.textProgressFinished)
        textProgressActive = findViewById(R.id.textProgressActive)
        textProgressUnseen = findViewById(R.id.textProgressUnseen)

        textEn = findViewById(R.id.textReal)
        textGuessLong = findViewById(R.id.textGuessLong)
        progressBar = findViewById(R.id.progressBar)
        progressBarFinished = findViewById(R.id.progressBarFinished)


        val sharedPreferences: SharedPreferences = getSharedPreferences(
            "vocabulary_preferences", Context.MODE_PRIVATE
        )
        val inputStream: InputStream = resources.openRawResource(
            R.raw.dictionary_sorted_2
        )
        textProgressFinished.text = ""
        textProgressActive.text = ""
        textProgressUnseen.text = ""
        textScore.text = ""
        textGuessLong.text = ""
        textFr.text = "Apprenez le français !"
        textEn.text = getString(R.string.START_INFO_TEXT)

        vocabDictionary = MyDictionary(
            inputStream, sharedPreferences
        )
        // vocabDictionary.debugDictionary()
        progressBar.progress = ((vocabDictionary.getActiveDataSize() + 1)
            .toFloat() / vocabDictionary.csvData.size.toFloat() * 100).toInt()
        progressBarFinished.progress = (vocabDictionary.getFinishedDataSize()
            .toFloat() / vocabDictionary.csvData.size.toFloat() * 100).toInt()
        //vocabDictionary.debugDictionary()

        startTime = System.currentTimeMillis()

        fun showTip() {
            if (currentVocab == null) return
            currentVocab!!.nTimesFailed += 0.25f

            buttonFail.isEnabled = false
            buttonFail.isClickable = false

            buttonNext.isEnabled = false
            buttonNext.isClickable = false


            buttonNew.isEnabled = false
            buttonNew.isClickable = false

            buttonTip.isEnabled = false
            buttonTip.isClickable = false
            latch = CountDownLatch(1)
            val capturedSentence = textGuessLong.text

            Thread {
                if (currentVocab != null) {
                    runOnUiThread {
                        textGuessLong.visibility = View.VISIBLE
                    }
                    speakText(currentVocab!!, en = false, fr = false, exampleSentence=true, sentenceText=capturedSentence)
                }
            }.start()
            Thread {
                latch.await()
                Thread.sleep(1000)
                runOnUiThread {
                    buttonFail.isEnabled = true
                    buttonFail.isClickable = true

                    buttonNext.isEnabled = true
                    buttonNext.isClickable = true
                }
            }.start()

        }

        fun revealAllVocabData(showNextVocab: Boolean) {
            if (currentVocab == null) return
            buttonFail.isEnabled = false
            buttonFail.isClickable = false

            buttonNext.isEnabled = false
            buttonNext.isClickable = false


            buttonNew.isEnabled = false
            buttonNew.isClickable = false

            buttonTip.isEnabled = false
            buttonTip.isClickable = false
            latch = CountDownLatch(1)
            val capturedSentence = textGuessLong.text

            Thread {
                if (currentVocab != null) {
                    runOnUiThread {
                        textEn.visibility = View.VISIBLE
                        textGuessLong.visibility = View.VISIBLE
                    }
                    speakText(currentVocab!!, en = true, fr = false, exampleSentence = true, sentenceText = capturedSentence)
                }
            }.start()
            Thread {
                latch.await()
                Thread.sleep(1000)
                if (showNextVocab) {
                    updateVocab(2000, false)
                    Thread.sleep(100)
                }
                runOnUiThread {
                    buttonFail.isEnabled = true
                    buttonFail.isClickable = true

                    buttonNext.isEnabled = true
                    buttonNext.isClickable = true



                    buttonTip.isEnabled = true
                    buttonTip.isClickable = true
                }
            }.start()
        }

        fun exitSpotCheck(wasWrong: Boolean) {
            inSpotCheck = false
            buttonFail.text = "I don't know"
            buttonNext.text = "Next"
            if (wasWrong) {
                currentVocab?.nTimesFailed = (currentVocab?.nTimesFailed ?: 0f) + 1.0f
                updateVocab(2000, false)
            } else {
                updateVocab(0, false)
            }
        }

        fun startSpotCheck() {
            inSpotCheck = true
            textEn.visibility = View.VISIBLE

            buttonFail.text = "✗ Wrong"
            buttonNext.text = "✓ Correct"
            buttonNew.isEnabled = false
            buttonNew.isClickable = false
            buttonTip.isEnabled = false
            buttonTip.isClickable = false

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

            playSpotCheckSound()
        }

        buttonFail.setOnClickListener {
            if (inSpotCheck) {
                exitSpotCheck(wasWrong = true)
            } else {
                revealAllVocabData(showNextVocab = true)
            }
        }
        buttonNext.setOnClickListener {
            if (inSpotCheck) {
                exitSpotCheck(wasWrong = false)
            } else if (currentVocab != null && Math.random() < spotCheckProbability) {
                startSpotCheck()
            } else {
                updateVocab(0, false)
            }
        }
        buttonNew.setOnClickListener { updateVocab(0, true) }
        buttonTip.setOnClickListener { showTip() }

    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set the language to French
            val result = textToSpeech.setLanguage(Locale.FRENCH)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Handle the case where French language data is missing or not supported
                println("French language is not supported.")
            }

            // Use audio attributes that don't steal focus from music apps
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            textToSpeech.setAudioAttributes(audioAttributes)
        } else {
            // Handle initialization failure
            println("TextToSpeech initialization failed.")
        }
    }

    private fun speakText(vocab: Vocab, en: Boolean, fr: Boolean, exampleSentence: Boolean, sentenceText: CharSequence? = null) {
        val wordFr = vocab.pronounceableFr()
        val wordEn = vocab.pronounceableEn()
        val sentenceFr = sentenceText ?: textGuessLong.text

        val params = Bundle()
        params.putString(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "yourUtteranceIdEn"
        )

        if (fr) {
            textToSpeech.language = Locale.FRENCH
            textToSpeech.speak(
                wordFr, TextToSpeech.QUEUE_ADD, null, null
            )
        }
        if (en) {
            textToSpeech.language = Locale.ENGLISH
            textToSpeech.speak(
                wordEn, TextToSpeech.QUEUE_ADD, null, null
            )


        }
        if (exampleSentence) {
            textToSpeech.language = Locale.FRENCH
            textToSpeech.speak(
                sentenceFr, TextToSpeech.QUEUE_ADD, params, "yourUtteranceIdEn"
            )
        }

        }

        override fun onDestroy() {
            // Shutdown TextToSpeech when the activity is destroyed
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
            stopService(serviceIntent)
            releaseSoundPool()
            super.onDestroy()
        }

        override fun onResume() {
            super.onResume()
            startTime = System.currentTimeMillis()

            //updateVocab(0,false,false)
        }


        private fun saveCurrentVocab(penalty: Long) {
            if (startTime != null && currentVocab != null) {
                endTime = System.currentTimeMillis()
                timeElapsed = endTime!! - startTime!! + penalty

                val prevFailureProbability = currentVocab!!.failureProbability()

                currentVocab!!.nTimesViewed += 1
                val alpha = 0.3
                currentVocab!!.viewTimeMilli = (alpha * timeElapsed!! + (1.0 - alpha) * currentVocab!!.viewTimeMilli).toLong()
                currentVocab!!.lastDisplayed = System.currentTimeMillis()
                currentVocab!!.savePreferences()
                if (currentVocab!!.failureProbability() < 0.1 && prevFailureProbability >= 0.1) {
                    // Play the success sound
                    playSuccessSound()
                }
            }

        }


        private fun updateVocab(penalty: Long, newCandidates: Boolean, saveVocab: Boolean = true) {
            if (penalty > 0) {
                if (currentVocab != null) {
                    currentVocab!!.nTimesFailed += 1.0f
                }
            }

            if (saveVocab) {
                saveCurrentVocab(penalty)
            }
            if (currentVocab == null) {
                currentVocab = if (newCandidates) {
                    vocabDictionary.getInactiveVocab()
                } else {
                    vocabDictionary.getActiveVocabWeightened()
                }
            }
            val previousVocabFrench = currentVocab!!.french
            var attempts = 0
            while (currentVocab!!.french == previousVocabFrench && attempts < 50) {
                currentVocab = if (newCandidates) {
                    vocabDictionary.getInactiveVocab()
                } else {
                    vocabDictionary.getActiveVocabWeightened()
                }
                attempts++
            }

            runOnUiThread {
                inSpotCheck = false
                buttonFail.text = "I don't know"
                buttonNext.text = "Next"
                progressBar.progress = ((vocabDictionary.getActiveDataSize() + 1)
                    .toFloat() / vocabDictionary.csvData.size.toFloat() * 100).toInt()
                progressBarFinished.progress = (vocabDictionary.getFinishedDataSize()
                    .toFloat() / vocabDictionary.csvData.size.toFloat() * 100).toInt()
                print(currentVocab!!.meanTimeViewedMilli())
                if (currentVocab!!.meanTimeViewedMilli() == (10 * 1e3)) {
                    textScore.text =
                        String.format("A new word!")
                    buttonFail.isClickable = true
                    buttonFail.isEnabled = true

                    buttonNext.isClickable = true
                    buttonNext.isEnabled = true

                    buttonNew.isClickable = true
                    buttonNew.isEnabled = true


                    buttonTip.isClickable = true
                    buttonTip.isEnabled = true


                } else {
                    textScore.text = currentVocab!!.getInfoString()
                    buttonFail.isClickable = true
                    buttonFail.isEnabled = true

                    buttonNext.isClickable = true
                    buttonNext.isEnabled = true

                    buttonNew.isClickable = true
                    buttonNew.isEnabled = true

                    buttonTip.isClickable = true
                    buttonTip.isEnabled = true
                }
                val finished = vocabDictionary.getFinishedDataSize()
                val active = vocabDictionary.getActiveDataSize() - finished
                val unseen = vocabDictionary.csvData.size - vocabDictionary.getActiveDataSize()
                textProgressFinished.text = finished.toString()
                textProgressActive.text = active.toString()
                textProgressUnseen.text = unseen.toString()
                progressBar.post {
                    val total = vocabDictionary.csvData.size.toFloat()
                    val finishedFraction = finished.toFloat() / total
                    val seenFraction = vocabDictionary.getActiveDataSize().toFloat() / total
                    val barWidth = progressBar.width
                    val center = (finishedFraction + seenFraction) / 2f * barWidth
                    textProgressActive.translationX = center - textProgressActive.width / 2f
                }
                textFr.text = currentVocab!!.french

                textEn.visibility = View.INVISIBLE
                textEn.text = currentVocab!!.english

                textGuessLong.visibility = View.INVISIBLE
                textGuessLong.text = currentVocab!!.getSomeFrenchLong()

            }
            startTime = System.currentTimeMillis()
            speakText(currentVocab!!, en = false, fr = true, exampleSentence = false)
        }

        private fun playSuccessSound() {
            soundPool?.play(successSoundId, 1f, 1f, 1, 0, 1f)
        }

        private fun playSpotCheckSound() {
            soundPool?.play(spotCheckSoundId, 0.7f, 0.7f, 1, 0, 1f)
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
            val dataSize = numSamples * 2
            file.outputStream().use { out ->
                // WAV header
                out.write("RIFF".toByteArray())
                out.write(intToBytes(36 + dataSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToBytes(16)) // chunk size
                out.write(shortToBytes(1)) // PCM
                out.write(shortToBytes(1)) // mono
                out.write(intToBytes(sampleRate))
                out.write(intToBytes(sampleRate * 2)) // byte rate
                out.write(shortToBytes(2)) // block align
                out.write(shortToBytes(16)) // bits per sample
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

        private fun releaseSoundPool() {
            soundPool?.release()
            soundPool = null
        }

        private fun createNotificationChannel() {
            val name = "Show vocabs"
            val descriptionText = "This channel shows continuously vocabs to practice"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }

    }


