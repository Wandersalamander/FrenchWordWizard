package io.github.wandersalamander.vocabtrainer.quiz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.applySystemBarAndActionBarInsetsAsPadding
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.LanguageWipeRepository
import io.github.wandersalamander.vocabtrainer.enableEdgeToEdge
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener
import io.github.wandersalamander.vocabtrainer.themeActionBarHeightPx
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import io.github.wandersalamander.vocabtrainer.dictionary.openDictionaryStream
import io.github.wandersalamander.vocabtrainer.llm.LlmService
import io.github.wandersalamander.vocabtrainer.quiz.ui.refreshActiveBar
import io.github.wandersalamander.vocabtrainer.quiz.ui.refreshLifetimeBar
import io.github.wandersalamander.vocabtrainer.quiz.ui.refreshLifetimeScoreBadge
import io.github.wandersalamander.vocabtrainer.service.MyForegroundService
import io.github.wandersalamander.vocabtrainer.settings.SettingsActivity
import io.github.wandersalamander.vocabtrainer.streak.StreakAlarmScheduler
import io.github.wandersalamander.vocabtrainer.streak.StreakNotifications
import io.github.wandersalamander.vocabtrainer.wordlist.WordListActivity
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var audioManager: AudioManager

    // Listening exercises are pointless with no audio, so we watch the media
    // volume while in the foreground and auto-disable listening mode when it
    // hits zero. Settings.System fires for any system setting; the handler just
    // re-checks the volume, which is cheap. Registered in onResume / dropped in
    // onPause so it never fires while backgrounded.
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncListeningModeWithVolume()
        }
    }

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Push the root LinearLayout below the status bar + window-decor action
        // bar (which under edge-to-edge no longer reserves a strip of its own)
        // and above the gesture-nav bar. See ViewExt for why this preserves the
        // cards' clip-to-padding floater behaviour.
        findViewById<View>(R.id.contentRoot)
            .applySystemBarAndActionBarInsetsAsPadding(themeActionBarHeightPx())

        // Kick off LLM init in the background. No-op on devices without a
        // model; isReady stays false and the quiz silently falls back to CSV
        // sentences.
        LlmService.warmup(applicationContext)

        setupTextToSpeech()
        setupNotificationChannels()

        val prefs = AppPrefs.get(this)
        language = Language.fromPrefs(this)
        languageWipeTimestampAtStart = LanguageWipeRepository.timestampFor(language.code)
        vocabDictionary = MyDictionary(openDictionaryStream(this, language), DataLayer.vocabPersistence)

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

        // Launched with the device already muted: start with listening off so
        // the user isn't dropped into silent listening rounds. The onResume that
        // follows won't double-toast — listening is already off by then.
        syncListeningModeWithVolume()

        startFirstRunFlow(prefs)
    }

    private fun workAroundDexReloadBug() {
        // Debug-builds-only workaround for an AndroidStudio IDE issue that
        // prevented re-runs without uninstalling. Marking codeCacheDir read-only
        // blocks DEX optimization caching, which would hurt cold-start in a
        // release build — gate strictly to debuggable APKs.
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!isDebuggable) return
        codeCacheDir.setReadOnly()
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
        // Foreground-service channel is owned by MyForegroundService — it
        // creates the channel itself before calling startForeground(), so we
        // don't duplicate (and possibly contradict) the channel metadata here.
        StreakNotifications.ensureChannel(this)
        // Idempotent — re-arms today if the alarm exists, or schedules a fresh
        // one on first launch / after the user re-enabled the reminder.
        StreakAlarmScheduler.schedule(this)
    }

    private fun bindViews(): QuizViews {
        val views = QuizViews(
            buttonFail = findViewById(R.id.button_fail),
            buttonNext = findViewById(R.id.button_next),
            buttonTip = findViewById(R.id.button_tip),
            buttonHard = findViewById(R.id.button_hard),
            buttonLearned = findViewById(R.id.button_learned),
            cardVocab = findViewById(R.id.cardVocab),
            cardLifetime = findViewById(R.id.cardLifetime),
            cardActive = findViewById(R.id.cardActive),
            textVocabEyebrow = findViewById(R.id.textVocabEyebrow),
            textForeign = findViewById(R.id.textGuess),
            textScore = findViewById(R.id.textScore),
            textEn = findViewById(R.id.textReal),
            textGuessLong = findViewById(R.id.textGuessLong),
            textStreak = findViewById(R.id.textStreak),
            textStreakShield = findViewById(R.id.textStreakShield),
            textStreakScore = findViewById(R.id.textStreakScore),
            textStreakScoreFloater = findViewById(R.id.textStreakScoreFloater),
            textLifetimeLabel = findViewById(R.id.textLifetimeLabel),
            segLifetimeMastered = findViewById(R.id.segLifetimeMastered),
            segLifetimeRemaining = findViewById(R.id.segLifetimeRemaining),
            textActiveLabel = findViewById(R.id.textActiveLabel),
            textActiveDeltaRead = findViewById(R.id.textActiveDeltaRead),
            textActiveDeltaListen = findViewById(R.id.textActiveDeltaListen),
            textActiveDeltaInvert = findViewById(R.id.textActiveDeltaInvert),
            segActiveRead = findViewById(R.id.segActiveRead),
            segActiveListen = findViewById(R.id.segActiveListen),
            segActiveInvert = findViewById(R.id.segActiveInvert),
            thinkingSparkle = findViewById(R.id.thinking_sparkle),
        )
        // Tag the foreign/English word TextViews with the right locale so
        // TalkBack picks the matching voice — without this, French/German
        // words are pronounced through the English engine and come out
        // unintelligible. textLongGuess holds example sentences in the
        // foreign language too.
        views.textForeign.textLocale = language.locale
        views.textGuessLong.textLocale = language.locale
        views.textEn.textLocale = Locale.ENGLISH
        return views
    }

    private fun disableQuizButtonsUntilFirstWord() {
        // These click-triggered actions need an active vocab to operate on.
        // QuizController re-enables them via setQuizButtonsEnabled once
        // updateVocab has installed the first word.
        listOf(views.buttonFail, views.buttonTip).forEach {
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
        refreshLifetimeScoreBadge(applicationContext, views, vocabDictionary)
    }

    private fun wireQuizControllerListeners() {
        views.buttonFail.setDebouncedOnClickListener { quizController.onFailClick() }
        views.buttonNext.setDebouncedOnClickListener { quizController.onNextClick() }
        views.buttonTip.setOnClickListener { quizController.onTipClick() }
        views.buttonHard.setDebouncedOnClickListener { quizController.onHardClick() }
        quizController.setupLearnedButton()
        quizController.setupStreakBadgeRoles()
        quizController.setupProgressCardInfo()
        quizController.setupVocabCardPull()
    }

    /**
     * First-launch sequencing: show the button tutorial (when unseen and no
     * screen reader is active), then start calibration once the tour ends.
     * When the tutorial is skipped, go straight to the calibration check.
     *
     * TapTargetView overlays each highlighted view in turn; it's not navigable
     * via TalkBack swipe gestures and effectively traps SR users on a screen
     * they can't operate, so the visual tour is skipped when a screen reader is
     * active (and the seen-flag isn't persisted in that case — see
     * showTutorial). Calibration uses the regular buttons and announces itself
     * with a banner, so it still runs for SR users.
     */
    private fun startFirstRunFlow(prefs: SharedPreferences) {
        val tutorialSeen = prefs.getBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, false)
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val screenReaderActive = am.isTouchExplorationEnabled
        val root = findViewById<View>(android.R.id.content)
        if (!tutorialSeen && !screenReaderActive) {
            root.post { showTutorial(onFinished = ::maybeStartCalibration) }
        } else {
            root.post { maybeStartCalibration() }
        }
    }

    /**
     * Start the once-per-language calibration probe, but only on a genuinely
     * fresh language: calibration hasn't already run for it
     * ([AppPrefs.isCalibrationDone]) and nothing has been introduced yet. The
     * emptiness check stops upgrading or mid-learning users from being probed
     * retroactively; a language wipe clears both signals (see
     * SettingsActivity.wipeProgressFor) so a wiped language re-calibrates.
     */
    private fun maybeStartCalibration() {
        if (AppPrefs.isCalibrationDone(this, language.code)) return
        if (vocabDictionary.csvData.any { it.hasBeenIntroduced() }) return
        quizController.startCalibration()
    }

    private fun showTutorial(onFinished: () -> Unit = {}) {
        fun target(view: View, title: String, description: String) =
            TapTarget.forView(view, title, description)
                .tintTarget(false)
                .cancelable(true)

        // The streak badge always renders a real value ("🔥 0" on day 0; see
        // StreakBadgeView.refresh), so the "Daily streak" TapTarget below has a
        // concrete, visible target — no placeholder seeding needed.
        val steps = mutableListOf(
            target(views.textForeign, "The word", "Recall what this foreign word means before tapping any button below."),
            target(views.buttonNext, "Know it", "Tap when you know the word. The next one loads — occasionally a spot-check pops up so you can confirm you really got it right."),
            target(views.buttonFail, "Reveal", "Tap when the word stumped you. Reveals the translation and an example sentence, then loads the next word."),
            target(views.buttonTip, "Hint", "Reveals an example sentence using the word and reads it aloud. Costs a small score penalty — one hint per word."),
            target(views.buttonHard, "Flag as hard", "Marks the word as tough so it shows up more often. Tap again to unflag."),
            target(views.buttonLearned, "Mark as learned", "First tap reveals the answer so you can check yourself. Tap again to retire the word — you won't see it again."),
            target(views.textStreak, "Daily streak", "Counts consecutive days you've practiced. Even one round keeps the streak alive. Tap any time for a refresher."),
            target(views.textStreakShield, "Streak shield", "Earn a shield every ${io.github.wandersalamander.vocabtrainer.streak.StreakTracker.FREEZE_AWARD_INTERVAL_DAYS} days of practice (up to ${io.github.wandersalamander.vocabtrainer.streak.StreakTracker.MAX_FREEZES} stockpiled — enough for a holiday or a sick week). Miss a day and a shield is spent automatically, so your streak survives. Tap any time to see how many you have."),
        )

        findActionBarToolbar()?.let { toolbar ->
            steps += TapTarget.forToolbarOverflow(
                toolbar,
                "Settings & more",
                "Open this menu to reach Settings, where you can download an advanced on-device AI that writes richer Tip sentences.",
            ).tintTarget(false).cancelable(true)
        }

        TapTargetSequence(this)
            .targets(steps)
            .continueOnCancel(true)
            .listener(object : TapTargetSequence.Listener {
                // Fire once whether the tour completes or is dismissed, so the
                // first-run handoff to calibration runs in either case.
                private var ended = false
                private fun end() {
                    if (ended) return
                    ended = true
                    onFinished()
                }
                override fun onSequenceFinish() = end()
                override fun onSequenceStep(target: TapTarget, targetClicked: Boolean) {}
                override fun onSequenceCanceled(target: TapTarget) = end()
            })
            .start()

        AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, true).apply()
    }

    /**
     * Walk the decor view to find the AppCompat-managed toolbar so TapTargetView
     * can drop a target on its overflow menu button. We don't call
     * [setSupportActionBar], so the toolbar isn't directly addressable — but
     * it's always somewhere under the action-bar container in the decor tree.
     * Returns null on unusual themes (e.g. NoActionBar without our own
     * toolbar); the caller skips the tutorial step gracefully when null.
     */
    private fun findActionBarToolbar(): Toolbar? {
        val root = window.decorView as? ViewGroup ?: return null
        return findToolbarIn(root)
    }

    private fun findToolbarIn(group: ViewGroup): Toolbar? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is Toolbar) return child
            if (child is ViewGroup) findToolbarIn(child)?.let { return it }
        }
        return null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Greyed out (not just unchecked) while muted: there's nothing to hear,
        // so the toggle is unavailable until the user restores sound.
        val muted = isMediaMuted()
        menu.findItem(R.id.menu_listening_mode)?.apply {
            isEnabled = !muted
            isChecked = quizController.isListeningModeEnabled()
        }
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
        // USAGE_MEDIA so speech follows the active output device (Bluetooth
        // A2DP, wired headset). USAGE_GAME is not reliably routed to A2DP and
        // can stay on the built-in speaker. We never request audio focus, so
        // this still mixes over music apps without interrupting them.
        textToSpeech.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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

    override fun stop() {
        if (!::textToSpeech.isInitialized) return
        textToSpeech.stop()
        // stop() doesn't deliver onDone, so release any coroutine parked in
        // speakSentenceAndAwait rather than leaving it suspended until resume.
        pendingTtsDone?.complete(Unit)
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
        val savedLanguage = Language.fromPrefs(this)
        if (savedLanguage != language) {
            recreate()
            return
        }
        val savedWipeTimestamp = LanguageWipeRepository.timestampFor(language.code)
        if (savedWipeTimestamp > languageWipeTimestampAtStart) {
            recreate()
            return
        }
        quizController.onAppResumed()
        quizController.refreshStreakBadge()
        // Catch a volume change that happened while we were backgrounded, then
        // keep watching for live changes (e.g. the user thumbing volume to zero
        // mid-session).
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        syncListeningModeWithVolume()
    }

    override fun onPause() {
        if (::quizController.isInitialized) {
            quizController.markPaused()
        }
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onPause()
    }

    /** True when the media stream — where TTS plays — is at zero volume. */
    private fun isMediaMuted(): Boolean =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    /**
     * Listening exercises need audible TTS, so when the device media volume is
     * muted we auto-disable listening mode (with a hint) and refresh the
     * overflow menu so its toggle reflects the change. We deliberately do NOT
     * auto-re-enable on unmute: the user opts back in from the menu so we never
     * surprise them with sudden speech.
     */
    private fun syncListeningModeWithVolume() {
        if (!::quizController.isInitialized) return
        if (isMediaMuted() && quizController.isListeningModeEnabled()) {
            quizController.setListeningModeEnabled(false)
            Toast.makeText(
                this,
                "Sound off — listening paused",
                Toast.LENGTH_LONG,
            ).show()
        }
        invalidateOptionsMenu()
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
}
