package com.example.myapplication.quiz

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.setDebouncedOnClickListener
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.Vocab
import com.example.myapplication.dictionary.openDictionaryStream
import com.example.myapplication.llm.LlmService
import com.example.myapplication.service.MyForegroundService
import com.example.myapplication.settings.SettingsActivity
import com.example.myapplication.streak.StreakAlarmScheduler
import com.example.myapplication.streak.StreakNotifications
import com.example.myapplication.wordlist.WordListActivity
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale

private const val TAG = "MainActivity"
private const val UTTERANCE_AWAITABLE = "awaitable_sentence"

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, TtsHelper {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var language: Language
    private lateinit var vocabDictionary: MyDictionary
    private lateinit var quizController: QuizController
    private lateinit var sounds: SoundEffects
    private lateinit var serviceIntent: Intent
    private lateinit var views: QuizViews

    // Snapshot of the per-language wipe timestamp at startup. If the user wipes
    // this language from Settings, the stored timestamp moves forward and we
    // recreate() on resume so the dictionary reflects the cleared state.
    private var languageWipeTimestampAtStart: Long = 0L

    // Completed by the TTS UtteranceProgressListener when the awaitable
    // utterance finishes. Each call to speakSentenceAndAwait creates its own
    // deferred; the field just gives onDone something to call into.
    @Volatile
    private var pendingTtsDone: CompletableDeferred<Unit>? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVocabService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceIntent = Intent(this, MyForegroundService::class.java)
        ensureNotificationPermissionThenStartService()
        workAroundDexReloadBug()

        sounds = SoundEffects(this)
        setContentView(R.layout.activity_main)

        // Kick off LLM init in the background. No-op on devices without a
        // model; isReady stays false and the quiz silently falls back to CSV
        // sentences.
        LlmService.warmup(applicationContext)

        setupTextToSpeech()
        setupNotificationChannels()

        val prefs = AppPrefs.get(this)
        language = Language.fromCode(prefs.getString(AppPrefs.KEY_APP_LANGUAGE, null))
        languageWipeTimestampAtStart = prefs.getLong(AppPrefs.progressWipedAtKey(language.code), 0L)
        vocabDictionary = MyDictionary(openDictionaryStream(this, language), prefs)

        views = bindViews()
        disableQuizButtonsUntilFirstWord()
        renderInitialIdleState()
        renderInitialProgressBars()

        quizController = QuizController(
            activity = this,
            views = views,
            sounds = sounds,
            tts = this,
            vocabDictionary = vocabDictionary,
            language = language,
        )
        wireQuizControllerListeners()
        quizController.refreshStreakBadge()

        maybeShowTutorial(prefs)
    }

    private fun workAroundDexReloadBug() {
        // Fix a bug seen via the AndroidStudio IDE that prevented the
        // application from running several times without uninstalling first.
        val dexOutputDir: File = codeCacheDir
        dexOutputDir.setReadOnly()
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onDone(utteranceId: String) {
                if (utteranceId == UTTERANCE_AWAITABLE) {
                    pendingTtsDone?.complete(Unit)
                }
            }

            override fun onError(p0: String?) {
                Log.w(TAG, "TTS error: $p0")
                pendingTtsDone?.complete(Unit)
            }
        })
    }

    private fun setupNotificationChannels() {
        createForegroundServiceChannel()
        StreakNotifications.ensureChannel(this)
        // Idempotent — re-arms today if the alarm exists, or schedules a fresh
        // one on first launch / after the user re-enabled the reminder.
        StreakAlarmScheduler.schedule(this)
    }

    private fun bindViews(): QuizViews {
        val progressBarsContainer = findViewById<FrameLayout>(R.id.progressBarsContainer)
        val row = layoutInflater.inflate(R.layout.item_progress_bars, progressBarsContainer, false) as ViewGroup
        progressBarsContainer.addView(row)

        return QuizViews(
            buttonFail = findViewById(R.id.button_fail),
            buttonNext = findViewById(R.id.button_next),
            buttonNew = findViewById(R.id.button_new),
            buttonTip = findViewById(R.id.button_tip),
            buttonHard = findViewById(R.id.button_hard),
            buttonLearned = findViewById(R.id.button_learned),
            textForeign = findViewById(R.id.textGuess),
            textScore = findViewById(R.id.textScore),
            textEn = findViewById(R.id.textReal),
            textGuessLong = findViewById(R.id.textGuessLong),
            textStreak = findViewById(R.id.textStreak),
            textStreakShield = findViewById(R.id.textStreakShield),
            textLifetimeLabel = row.findViewById(R.id.textLifetimeLabel),
            segLifetimeMastered = row.findViewById(R.id.segLifetimeMastered),
            segLifetimeRemaining = row.findViewById(R.id.segLifetimeRemaining),
            textActiveLabel = row.findViewById(R.id.textActiveLabel),
            segActiveRead = row.findViewById(R.id.segActiveRead),
            segActiveListen = row.findViewById(R.id.segActiveListen),
            segActiveInvert = row.findViewById(R.id.segActiveInvert),
            thinkingSparkle = findViewById(R.id.thinking_sparkle),
        )
    }

    private fun disableQuizButtonsUntilFirstWord() {
        // These three are click-triggered actions that need an active vocab to
        // operate on. QuizController re-enables them via setQuizButtonsEnabled
        // once updateVocab has installed the first word.
        listOf(views.buttonFail, views.buttonNew, views.buttonTip).forEach {
            it.isClickable = false
            it.isEnabled = false
        }
    }

    private fun renderInitialIdleState() {
        views.textScore.text = ""
        views.textGuessLong.text = ""
        views.textForeign.text = language.greeting
        views.textEn.text = getString(R.string.START_INFO_TEXT)
    }

    private fun renderInitialProgressBars() {
        if (vocabDictionary.csvData.isEmpty()) return
        refreshLifetimeBar(applicationContext, views, vocabDictionary)
        refreshActiveBar(applicationContext, views, vocabDictionary)
    }

    private fun wireQuizControllerListeners() {
        views.buttonFail.setDebouncedOnClickListener { quizController.onFailClick() }
        views.buttonNext.setDebouncedOnClickListener { quizController.onNextClick() }
        views.buttonNew.setDebouncedOnClickListener { quizController.onNewClick() }
        views.buttonTip.setDebouncedOnClickListener { quizController.onTipClick() }
        views.buttonHard.setDebouncedOnClickListener { quizController.onHardClick() }
        quizController.setupLearnedButton()
    }

    private fun maybeShowTutorial(prefs: SharedPreferences) {
        if (prefs.getBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, false)) return
        findViewById<View>(android.R.id.content).post { showTutorial() }
    }

    private fun showTutorial() {
        fun target(view: View, title: String, description: String) =
            TapTarget.forView(view, title, description)
                .tintTarget(false)
                .cancelable(true)

        // The streak badge is normally empty until the user has practiced;
        // seed a placeholder value so the TapTarget has something visible to
        // highlight during the first-launch tour. The shield badge already
        // shows "🛡️ 0" by default, so no seeding is needed for it. Cleared
        // back to the real state when the sequence ends.
        val seededStreak = views.textStreak.text.isNullOrEmpty()
        if (seededStreak) views.textStreak.text = "🔥 0"
        val restoreBadges = { if (seededStreak) quizController.refreshStreakBadge() }

        val steps = mutableListOf(
            target(views.textForeign, "The word", "This is where the word you need to translate appears. Try to recall its meaning before tapping anything."),
            target(views.buttonNext, "Next", "Tap when you have a guess. The answer is revealed, and occasionally you'll be asked to confirm whether you actually got it right."),
            target(views.buttonFail, "I don't know", "Tap if you can't recall the meaning. The translation is revealed and you move on."),
            target(views.buttonTip, "Tip", "Plays an example sentence using the word, to jog your memory."),
            target(views.buttonNew, "A new word", "Skip the current word and pull a brand new one you haven't seen yet."),
            target(views.buttonHard, "Flag as hard", "Marks this word as difficult so it appears more often."),
            target(views.buttonLearned, "Mark as learned", "Tap once to reveal the answer, then tap again to confirm you've learned it and stop seeing it."),
            target(views.textStreak, "Daily streak", "Practice at least one round per day to build a streak. The fire counts how many days in a row you've kept it going."),
            target(views.textStreakShield, "Streak shield", "Every week of practice earns you a shield (up to ${com.example.myapplication.streak.StreakTracker.MAX_FREEZES} stockpiled, enough to cover a holiday or a sick week). Miss a day and a shield is spent automatically — your streak survives. Tap the shield any time to see how many you have left."),
        )

        findOverflowMenuButton()?.let { overflow ->
            steps += target(
                overflow,
                "Settings & more",
                "Open this menu to reach Settings, where you can download an advanced on-device AI that writes richer Tip sentences."
            )
        }

        TapTargetSequence(this)
            .targets(steps)
            .continueOnCancel(true)
            .listener(object : TapTargetSequence.Listener {
                override fun onSequenceFinish() { restoreBadges() }
                override fun onSequenceStep(target: TapTarget, targetClicked: Boolean) {}
                override fun onSequenceCanceled(target: TapTarget) { restoreBadges() }
            })
            .start()

        AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, true).apply()
    }

    private fun findOverflowMenuButton(): View? {
        val root = window.decorView as? ViewGroup ?: return null
        return findOverflowMenuButton(root)
    }

    private fun findOverflowMenuButton(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.simpleName == "OverflowMenuButton") return child
            if (child is ViewGroup) {
                findOverflowMenuButton(child)?.let { return it }
            }
        }
        return null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_listening_mode)?.isChecked =
            quizController.isListeningModeEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_listening_mode -> {
                val newState = !item.isChecked
                quizController.setListeningModeEnabled(newState)
                item.isChecked = newState
                true
            }
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
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech initialization failed.")
            return
        }
        val result = textToSpeech.setLanguage(language.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language ${language.locale} is not supported.")
        }
        // Audio attributes that don't steal focus from music apps.
        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
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

    override fun speakSentence(sentence: CharSequence) {
        textToSpeech.language = language.locale
        textToSpeech.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, null)
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
        val prefs = AppPrefs.get(this)
        val savedLanguage = Language.fromCode(prefs.getString(AppPrefs.KEY_APP_LANGUAGE, null))
        if (savedLanguage != language) {
            recreate()
            return
        }
        val savedWipeTimestamp = prefs.getLong(AppPrefs.progressWipedAtKey(language.code), 0L)
        if (savedWipeTimestamp > languageWipeTimestampAtStart) {
            recreate()
            return
        }
        quizController.markResumed()
        quizController.refreshStreakBadge()
    }

    override fun onPause() {
        if (::quizController.isInitialized) {
            quizController.markPaused()
        }
        super.onPause()
    }

    private fun ensureNotificationPermissionThenStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
        ) {
            startVocabService()
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startVocabService() {
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun createForegroundServiceChannel() {
        val channel = NotificationChannel(
            MyForegroundService.NOTIFICATION_CHANNEL_ID,
            "Show vocabs",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "This channel shows continuously vocabs to practice"
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
