package com.example.myapplication

import android.animation.ValueAnimator
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
import android.view.Menu
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.sin
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var textToSpeech: TextToSpeech

    private var currentLanguage: String = "fr"
    private var foreignLocale: Locale = Locale.FRENCH

    private lateinit var vocabDictionary: MyDictionary
    private var currentVocab: Vocab? = null
    private var startTime: Long? = null
    private var endTime: Long? = null
    private var timeElapsed: Long? = null
    @Volatile
    private var latch = CountDownLatch(1)

    private val recentWords = ArrayDeque<String>()
    private val recentWordsCapacity = 5

    private lateinit var buttonFail: Button
    private lateinit var buttonNext: Button
    private lateinit var buttonNew: Button
    private lateinit var buttonTip: Button
    private lateinit var buttonHard: Button
    private lateinit var buttonLearned: Button

    private lateinit var textFr: TextView
    private lateinit var textScore: TextView
    private lateinit var textProgressFinished: TextView
    private lateinit var textProgressActive: TextView
    private lateinit var textProgressUnseen: TextView
    private lateinit var textEn: TextView
    private lateinit var textGuessLong: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressBarFinished: ProgressBar
    private lateinit var thinkingDots: LinearLayout
    private var pulseAnimator: ValueAnimator? = null
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

        // Kick off Gemini Nano init in the background.
        // No-op on devices without AICore; isReady stays false and Tip silently uses CSV sentences.
        LlmService.warmup(applicationContext)

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
        buttonHard = findViewById(R.id.button_hard)
        buttonLearned = findViewById(R.id.button_learned)


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
        thinkingDots = findViewById(R.id.thinking_dots)


        val sharedPreferences: SharedPreferences = getSharedPreferences(
            "vocabulary_preferences", Context.MODE_PRIVATE
        )
        currentLanguage = sharedPreferences.getString("app_language", "fr") ?: "fr"
        foreignLocale = when (currentLanguage) {
            "de" -> Locale.GERMAN
            "it" -> Locale.ITALIAN
            else -> Locale.FRENCH
        }
        val inputStream: InputStream = openDictionaryStream(this, currentLanguage)
        textProgressFinished.text = ""
        textProgressActive.text = ""
        textProgressUnseen.text = ""
        textScore.text = ""
        textGuessLong.text = ""
        textFr.text = when (currentLanguage) {
            "de" -> "Lerne Deutsch!"
            "it" -> "Impara l'italiano!"
            else -> "Apprenez le français !"
        }
        textEn.text = getString(R.string.START_INFO_TEXT)

        vocabDictionary = MyDictionary(
            inputStream, sharedPreferences
        )
        // vocabDictionary.debugDictionary()
        val initTotalSize = vocabDictionary.csvData.size.toFloat()
        progressBar.progress = (vocabDictionary.getActiveDataSize()
            .toFloat() / initTotalSize * 100).toInt()
        progressBarFinished.progress = (vocabDictionary.getFinishedDataSize()
            .toFloat() / initTotalSize * 100).toInt()
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

            lifecycleScope.launch {
                val vocab = currentVocab ?: return@launch

                generateAndShowExampleSentence(vocab)

                val capturedSentence = textGuessLong.text

                Thread {
                    speakText(vocab, en = false, fr = false, exampleSentence = true, sentenceText = capturedSentence)
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

            lifecycleScope.launch {
                val vocab = currentVocab ?: return@launch

                // User tapped "I don't know" — reveal the English translation
                // and the example sentence immediately.
                textEn.visibility = View.VISIBLE

                // Speak the English word right away — the user just admitted
                // they don't know it, so they want to HEAR the answer
                // immediately. Don't make this wait for the LLM.
                Thread {
                    speakText(vocab, en = true, fr = false, exampleSentence = false)
                }.start()

                // In parallel, regenerate the example sentence with the LLM
                // (same behaviour as showTip). Falls back to the CSV sentence
                // already sitting in textGuessLong.text from updateVocab() if
                // the LLM isn't ready or generation fails/times out.
                generateAndShowExampleSentence(vocab)

                val capturedSentence = textGuessLong.text

                // Speak the example sentence. The TTS engine queues this
                // after the English word — if the LLM took longer than
                // pronouncing "book", the queue may already be empty and it
                // plays immediately; otherwise it follows naturally. This
                // utterance carries "yourUtteranceIdEn" which releases the
                // latch on completion (advances to next word).
                Thread {
                    speakText(vocab, en = false, fr = false, exampleSentence = true, sentenceText = capturedSentence)
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

        buttonHard.setOnClickListener {
            if (currentVocab != null) {
                currentVocab!!.flaggedHard = !currentVocab!!.flaggedHard
                currentVocab!!.savePreferences()
                buttonHard.text = if (currentVocab!!.flaggedHard) "⚑!" else "⚑"
            }
        }

        setupLearnedButton()

        if (!sharedPreferences.getBoolean("tutorial_shown", false)) {
            findViewById<View>(android.R.id.content).post { showTutorial() }
        }

    }

    private fun showTutorial() {
        fun target(view: View, title: String, description: String) =
            TapTarget.forView(view, title, description)
                .tintTarget(false)
                .cancelable(true)

        TapTargetSequence(this)
            .targets(
                target(textFr, "The word", "This is where the word you need to translate appears. Try to recall its meaning before tapping anything."),
                target(buttonNext, "Next", "Tap when you have a guess. The answer is revealed, and occasionally you'll be asked to confirm whether you actually got it right."),
                target(buttonFail, "I don't know", "Tap if you can't recall the meaning. The translation is revealed and you move on."),
                target(buttonTip, "Tip", "Plays an example sentence using the word, to jog your memory."),
                target(buttonNew, "A new word", "Skip the current word and pull a brand new one you haven't seen yet."),
                target(buttonHard, "Flag as hard", "Marks this word as difficult so it appears more often."),
                target(buttonLearned, "Mark as learned", "Tap once to reveal the answer, then tap again to confirm you've learned it and stop seeing it.")
            )
            .continueOnCancel(true)
            .listener(object : TapTargetSequence.Listener {
                override fun onSequenceFinish() {}
                override fun onSequenceStep(target: TapTarget, targetClicked: Boolean) {}
                override fun onSequenceCanceled(target: TapTarget) {}
            })
            .start()

        getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("tutorial_shown", true).apply()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.menu_word_list -> {
                startActivity(Intent(this, WordListActivity::class.java))
                true
            }
            R.id.menu_tutorial -> {
                showTutorial()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(foreignLocale)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                println("Language $foreignLocale is not supported.")
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

    private fun speakText(vocab: Vocab, en: Boolean, fr: Boolean, exampleSentence: Boolean, sentenceText: CharSequence? = null, flush: Boolean = false) {
        val wordForeign = vocab.pronounceableForeign(currentLanguage)
        val wordEn = vocab.pronounceableEn()
        val sentenceForeign = sentenceText ?: textGuessLong.text

        val params = Bundle()
        params.putString(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "yourUtteranceIdEn"
        )

        var queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        if (fr) {
            textToSpeech.language = foreignLocale
            textToSpeech.speak(
                wordForeign, queueMode, null, null
            )
            queueMode = TextToSpeech.QUEUE_ADD
        }
        if (en) {
            textToSpeech.language = Locale.ENGLISH
            textToSpeech.speak(
                wordEn, queueMode, null, null
            )
            queueMode = TextToSpeech.QUEUE_ADD
        }
        if (exampleSentence) {
            textToSpeech.language = foreignLocale
            textToSpeech.speak(
                sentenceForeign, queueMode, params, "yourUtteranceIdEn"
            )
        }

        }

        override fun onDestroy() {
            // Shutdown TextToSpeech when the activity is destroyed
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
            stopThinkingAnimation()
            stopService(serviceIntent)
            releaseSoundPool()
            super.onDestroy()
        }

        override fun onResume() {
            super.onResume()
            val prefs = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
            val savedLang = prefs.getString("app_language", "fr") ?: "fr"
            if (savedLang != currentLanguage) {
                recreate()
                return
            }
            startTime = System.currentTimeMillis()
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

                val justFinished = currentVocab!!.french
                recentWords.remove(justFinished)
                recentWords.addLast(justFinished)
                while (recentWords.size > recentWordsCapacity) {
                    recentWords.removeFirst()
                }

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
                val totalSize = vocabDictionary.csvData.size.toFloat()
                progressBar.progress = (vocabDictionary.getActiveDataSize()
                    .toFloat() / totalSize * 100).toInt()
                progressBarFinished.progress = (vocabDictionary.getFinishedDataSize()
                    .toFloat() / totalSize * 100).toInt()
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
                val total = vocabDictionary.csvData.size
                val finished = vocabDictionary.getFinishedDataSize()
                val active = vocabDictionary.getActiveDataSize() - finished
                val unseen = total - vocabDictionary.getActiveDataSize()
                textProgressFinished.text = finished.toString()
                textProgressActive.text = active.toString()
                textProgressUnseen.text = unseen.toString()
                progressBar.post {
                    val totalF = total.toFloat()
                    val finishedFraction = finished.toFloat() / totalF
                    val seenFraction = vocabDictionary.getActiveDataSize().toFloat() / totalF
                    val barWidth = progressBar.width
                    val center = (finishedFraction + seenFraction) / 2f * barWidth
                    textProgressActive.translationX = center - textProgressActive.width / 2f
                }
                textFr.text = currentVocab!!.french

                textEn.visibility = View.INVISIBLE
                textEn.text = currentVocab!!.english

                textGuessLong.visibility = View.INVISIBLE
                textGuessLong.text = currentVocab!!.getSomeFrenchLong()

                buttonHard.text = if (currentVocab!!.flaggedHard) "⚑!" else "⚑"
                setupLearnedButton()

            }
            startTime = System.currentTimeMillis()
            speakText(currentVocab!!, en = false, fr = true, exampleSentence = false, flush = true)
        }

        private fun setupLearnedButton() {
            buttonLearned.text = "✓"
            buttonLearned.setOnClickListener {
                if (currentVocab != null) {
                    textEn.visibility = View.VISIBLE
                    textGuessLong.visibility = View.VISIBLE
                    buttonLearned.text = "✓?"
                    buttonLearned.setOnClickListener {
                        currentVocab!!.ignore = true
                        currentVocab!!.savePreferences()
                        setupLearnedButton()
                        updateVocab(0, false)
                    }
                }
            }
        }

        private fun playSuccessSound() {
            soundPool?.play(successSoundId, 1f, 1f, 1, 0, 1f)
        }

        // Shared by Tip and "I don't know": run the LLM to produce an example
        // sentence while the dots animate in textGuessLong's place, then fade
        // the generated text in. If the LLM isn't ready, just show whatever
        // CSV-fallback text is already in textGuessLong.
        private suspend fun generateAndShowExampleSentence(vocab: Vocab) {
            if (LlmService.isReady) {
                textGuessLong.visibility = View.INVISIBLE
                startThinkingAnimation()
                val generated = try {
                    LlmService.generate(
                        word = vocab.french,
                        translation = vocab.english,
                        recent = recentWords.toList(),
                        lang = currentLanguage
                    )
                } finally {
                    stopThinkingAnimation()
                }
                textGuessLong.text = generated ?: vocab.getSomeFrenchLong()
                textGuessLong.alpha = 0f
                textGuessLong.visibility = View.VISIBLE
                textGuessLong.animate().alpha(1f).setDuration(220).start()
            } else {
                textGuessLong.visibility = View.VISIBLE
            }
        }

        // Three dots that breathe with a continuous sine wave, each offset by
        // a third of the cycle so a soft ripple appears to flow across them.
        // Driven by a single ValueAnimator (vs. 12 ObjectAnimators) so cancel
        // is atomic and there are no per-dot cycle-boundary glitches.
        private fun startThinkingAnimation() {
            stopThinkingAnimation()

            val dots = listOf(
                findViewById<View>(R.id.thinking_dot1),
                findViewById<View>(R.id.thinking_dot2),
                findViewById<View>(R.id.thinking_dot3)
            )
            dots.forEach {
                it.scaleX = 1f
                it.scaleY = 1f
                it.alpha = 1f
            }

            thinkingDots.alpha = 0f
            thinkingDots.visibility = View.VISIBLE
            thinkingDots.animate()
                .alpha(1f)
                .setDuration(160L)
                .start()

            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1300L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    dots.forEachIndexed { index, dot ->
                        // Offset each dot by 1/3 of the cycle so the wave ripples through.
                        val raw = t - index / 3f
                        val phase = raw - floor(raw)
                        val wave = (sin(phase * 2.0 * Math.PI).toFloat() + 1f) / 2f
                        dot.alpha = 0.3f + 0.7f * wave
                        val scale = 0.7f + 0.4f * wave
                        dot.scaleX = scale
                        dot.scaleY = scale
                    }
                }
                start()
            }
        }

        private fun stopThinkingAnimation() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            thinkingDots.animate().cancel()
            thinkingDots.visibility = View.GONE
            thinkingDots.alpha = 1f
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


