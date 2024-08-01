package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
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

    private lateinit var buttonFail: Button
    private lateinit var buttonNext: Button
    private lateinit var buttonNew: Button

    private lateinit var textFr: TextView
    private lateinit var textScore: TextView
    private lateinit var textProgress: TextView
    private lateinit var textEn: TextView
    private lateinit var textGuessLong: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var serviceIntent: Intent
    private var mediaPlayer: MediaPlayer? = null


    private val channelId = "MyChannelId"

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

        // Initialize MediaPlayer with the success sound
        mediaPlayer = MediaPlayer.create(
            this, R.raw.duolingo_sucess
        )

        // Set completion listener to release resources when playback completes
        mediaPlayer?.setOnCompletionListener {
            releaseMediaPlayer()
        }



        setContentView(R.layout.activity_main)
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        // Initialize the latch with a count of 1
        var latch = CountDownLatch(1)

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {

            }

            override fun onDone(utteranceId: String) {
                if (utteranceId == "yourUtteranceIdEn") {
                    // Update your UI or perform other
                    // actions after TTS is finished
                    print("this.latch.countDown")
                    // Decrement the this.latch count
                    // to release the waiting thread
                    latch.countDown()
                }

            }

            override fun onError(p0: String?) {
                TODO("Not yet implemented")
            }


        })

        createNotificationChannel()



        buttonFail = findViewById(R.id.button_fail)
        buttonNext = findViewById(R.id.button_next)
        buttonNew = findViewById(R.id.button_new)

        buttonFail.isClickable = false
        buttonFail.isEnabled = false

        buttonNew.isClickable = false
        buttonNew.isEnabled = false

        textFr = findViewById(R.id.textGuess)
        textScore = findViewById(R.id.textScore)
        textProgress = findViewById(R.id.textProgress)

        textEn = findViewById(R.id.textReal)
        textGuessLong = findViewById(R.id.textGuessLong)
        progressBar = findViewById(R.id.progressBar)


        val sharedPreferences: SharedPreferences = getSharedPreferences(
            "vocabulary_preferences", Context.MODE_PRIVATE
        )
        val inputStream: InputStream = resources.openRawResource(
            R.raw.dictionary_sorted_2
        )
        textProgress.text = ""
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


        startTime = System.currentTimeMillis()

        fun revealAllVocabData(showNextVocab: Boolean) {
            buttonFail.isEnabled = false
            buttonFail.isClickable = false

            buttonNext.isEnabled = false
            buttonNext.isClickable = false


            buttonNew.isEnabled = false
            buttonNew.isClickable = false
            // Initialize the latch with a count of 1
            latch = CountDownLatch(1)

            Thread {

                if (currentVocab != null) {
                    runOnUiThread {
                        textEn.visibility = View.VISIBLE
                        textGuessLong.visibility = View.VISIBLE
                    }
                    speakText(currentVocab!!, en = true, fr = false)
                }
            }.start()
            Thread {
                // Block the main thread
                // until the latch count is 0
                latch.await()
                Thread.sleep(1000)
                runOnUiThread {

                }
                if (showNextVocab) {
                    updateVocab(2000, false)
                    Thread.sleep(100)
                }
                runOnUiThread {
                    buttonFail.isEnabled = true
                    buttonFail.isClickable = true

                    buttonNext.isEnabled = true
                    buttonNext.isClickable = true
                }
            }.start()
        }

        buttonFail.setOnClickListener { revealAllVocabData(showNextVocab = true) }
        buttonNext.setOnClickListener { updateVocab(0, false) }
        buttonNew.setOnClickListener { updateVocab(0, true) }


    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set the language to French
            val result = textToSpeech.setLanguage(Locale.FRENCH)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Handle the case where French language data is missing or not supported
                println("French language is not supported.")
            }
        } else {
            // Handle initialization failure
            println("TextToSpeech initialization failed.")
        }
    }

    private fun speakText(vocab: Vocab, en: Boolean, fr: Boolean) {
        // Speak the provided text#

        val wordFr = vocab.pronounceableFr()
        val wordEn = vocab.pronounceableEn()
        val sentenceFr = textGuessLong.text

        val params = Bundle()
        params.putString(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "yourUtteranceIdEn"
        )

        if (fr) {
            textToSpeech.language = Locale.FRENCH
            textToSpeech.speak(
                wordFr, TextToSpeech.QUEUE_FLUSH, null, null
            )
        }
        if (en) {
            textToSpeech.language = Locale.ENGLISH
            textToSpeech.speak(
                wordEn, TextToSpeech.QUEUE_ADD, null, null
            )

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
        releaseMediaPlayer()
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
            currentVocab!!.viewTimeMilli_prev = currentVocab!!.viewTimeMilli
            currentVocab!!.viewTimeMilli = timeElapsed as Long
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
                currentVocab!!.nTimesFailed += 1
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
        while (currentVocab!!.french == previousVocabFrench) {
            currentVocab = if (newCandidates) {
                vocabDictionary.getInactiveVocab()
            } else {
                vocabDictionary.getActiveVocabWeightened()
            }
        }

        runOnUiThread {
            progressBar.progress = ((vocabDictionary.getActiveDataSize() + 1)
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


            } else {
                textScore.text = currentVocab!!.getInfoString()
                buttonFail.isClickable = true
                buttonFail.isEnabled = true

                buttonNext.isClickable = true
                buttonNext.isEnabled = true

                buttonNew.isClickable = true
                buttonNew.isEnabled = true
            }
            textProgress.text = String.format(
                "%d / %d", (vocabDictionary.getActiveDataSize() + 1), vocabDictionary.csvData.size
            )
            textFr.text = currentVocab!!.french

            textEn.visibility = View.INVISIBLE
            textEn.text = currentVocab!!.english

            textGuessLong.visibility = View.INVISIBLE
            textGuessLong.text = currentVocab!!.getSomeFrenchLong()

        }
        startTime = System.currentTimeMillis()
        speakText(currentVocab!!, en = false, fr = true)
    }

    private fun playSuccessSound() {
        mediaPlayer?.start()
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
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


