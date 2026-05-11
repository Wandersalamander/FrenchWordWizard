package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale

private const val UTTERANCE_AWAITABLE = "awaitable_sentence"

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, TtsHelper {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var language: Language
    private lateinit var vocabDictionary: MyDictionary
    private lateinit var quizController: QuizController
    private lateinit var sounds: SoundEffects
    private lateinit var serviceIntent: Intent
    private lateinit var views: QuizViews

    // Completed by the TTS UtteranceProgressListener when the awaitable
    // utterance finishes. Each call to speakSentenceAndAwait creates its own
    // deferred; the field just gives onDone something to call into.
    @Volatile
    private var pendingTtsDone: CompletableDeferred<Unit>? = null

    private val channelId = MyForegroundService.NOTIFICATION_CHANNEL_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceIntent = Intent(this, MyForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        // Fix a bug seen via the AndroidStudio IDE that prevented the
        // application from running several times without uninstalling first.
        val dexOutputDir: File = codeCacheDir
        dexOutputDir.setReadOnly()

        sounds = SoundEffects(this)

        setContentView(R.layout.activity_main)

        // Kick off LLM init in the background. No-op on devices without a
        // model; isReady stays false and the quiz silently falls back to CSV
        // sentences.
        LlmService.warmup(applicationContext)

        textToSpeech = TextToSpeech(this, this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onDone(utteranceId: String) {
                if (utteranceId == UTTERANCE_AWAITABLE) {
                    pendingTtsDone?.complete(Unit)
                }
            }

            override fun onError(p0: String?) {
                println("TTS error: $p0")
                pendingTtsDone?.complete(Unit)
            }
        })

        createNotificationChannel()

        views = QuizViews(
            buttonFail = findViewById(R.id.button_fail),
            buttonNext = findViewById(R.id.button_next),
            buttonNew = findViewById(R.id.button_new),
            buttonTip = findViewById(R.id.button_tip),
            buttonHard = findViewById(R.id.button_hard),
            buttonLearned = findViewById(R.id.button_learned),
            textFr = findViewById(R.id.textGuess),
            textScore = findViewById(R.id.textScore),
            textEn = findViewById(R.id.textReal),
            textGuessLong = findViewById(R.id.textGuessLong),
            textProgressFinished = findViewById(R.id.textProgressFinished),
            textProgressActive = findViewById(R.id.textProgressActive),
            textProgressUnseen = findViewById(R.id.textProgressUnseen),
            progressBar = findViewById(R.id.progressBar),
            progressBarFinished = findViewById(R.id.progressBarFinished),
            thinkingSparkle = findViewById(R.id.thinking_sparkle),
        )

        views.buttonFail.isClickable = false
        views.buttonFail.isEnabled = false
        views.buttonNew.isClickable = false
        views.buttonNew.isEnabled = false
        views.buttonTip.isClickable = false
        views.buttonTip.isEnabled = false

        val sharedPreferences = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
        language = Language.fromCode(sharedPreferences.getString("app_language", null))
        val inputStream = openDictionaryStream(this, language)

        views.textProgressFinished.text = ""
        views.textProgressActive.text = ""
        views.textProgressUnseen.text = ""
        views.textScore.text = ""
        views.textGuessLong.text = ""
        views.textFr.text = language.greeting
        views.textEn.text = getString(R.string.START_INFO_TEXT)

        vocabDictionary = MyDictionary(inputStream, sharedPreferences)
        val initTotalSize = vocabDictionary.csvData.size.toFloat()
        views.progressBar.progress = (vocabDictionary.getActiveDataSize().toFloat() / initTotalSize * 100).toInt()
        views.progressBarFinished.progress = (vocabDictionary.getFinishedDataSize().toFloat() / initTotalSize * 100).toInt()

        quizController = QuizController(
            activity = this,
            views = views,
            sounds = sounds,
            tts = this,
            vocabDictionary = vocabDictionary,
            language = language,
        )

        views.buttonFail.setOnClickListener { quizController.onFailClick() }
        views.buttonNext.setOnClickListener { quizController.onNextClick() }
        views.buttonNew.setOnClickListener { quizController.onNewClick() }
        views.buttonTip.setOnClickListener { quizController.onTipClick() }
        views.buttonHard.setOnClickListener { quizController.onHardClick() }
        quizController.setupLearnedButton()

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
                target(views.textFr, "The word", "This is where the word you need to translate appears. Try to recall its meaning before tapping anything."),
                target(views.buttonNext, "Next", "Tap when you have a guess. The answer is revealed, and occasionally you'll be asked to confirm whether you actually got it right."),
                target(views.buttonFail, "I don't know", "Tap if you can't recall the meaning. The translation is revealed and you move on."),
                target(views.buttonTip, "Tip", "Plays an example sentence using the word, to jog your memory."),
                target(views.buttonNew, "A new word", "Skip the current word and pull a brand new one you haven't seen yet."),
                target(views.buttonHard, "Flag as hard", "Marks this word as difficult so it appears more often."),
                target(views.buttonLearned, "Mark as learned", "Tap once to reveal the answer, then tap again to confirm you've learned it and stop seeing it.")
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
            val result = textToSpeech.setLanguage(language.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                println("Language ${language.locale} is not supported.")
            }

            // Audio attributes that don't steal focus from music apps.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            textToSpeech.setAudioAttributes(audioAttributes)
        } else {
            println("TextToSpeech initialization failed.")
        }
    }

    override fun speakForeignWord(vocab: Vocab, flush: Boolean) {
        val word = vocab.pronounceableForeign(language)
        textToSpeech.language = language.locale
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        textToSpeech.speak(word, mode, null, null)
    }

    override fun speakEnglishWord(vocab: Vocab) {
        val word = vocab.pronounceableEn()
        textToSpeech.language = Locale.ENGLISH
        textToSpeech.speak(word, TextToSpeech.QUEUE_ADD, null, null)
    }

    override suspend fun speakSentenceAndAwait(sentence: CharSequence) {
        val done = CompletableDeferred<Unit>()
        pendingTtsDone = done
        textToSpeech.language = language.locale
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_AWAITABLE)
        }
        textToSpeech.speak(sentence, TextToSpeech.QUEUE_ADD, params, UTTERANCE_AWAITABLE)
        try {
            done.await()
        } catch (e: CancellationException) {
            // Activity tearing down or coroutine cancelled — stop any pending
            // speech so we don't leak audio into the next session.
            textToSpeech.stop()
            throw e
        }
    }

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        if (::quizController.isInitialized) {
            quizController.stopThinkingAnimation()
        }
        stopService(serviceIntent)
        sounds.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
        val savedLanguage = Language.fromCode(prefs.getString("app_language", null))
        if (savedLanguage != language) {
            recreate()
            return
        }
        quizController.markResumed()
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
