package io.github.wandersalamander.vocabtrainer.quiz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.applySystemBarAndActionBarInsetsAsPadding
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.onboarding.OnboardingActivity
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.LanguageWipeRepository
import io.github.wandersalamander.vocabtrainer.enableEdgeToEdge
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener
import io.github.wandersalamander.vocabtrainer.setInteractive
import io.github.wandersalamander.vocabtrainer.themeActionBarHeightPx
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MAX
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MIN
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
import io.github.wandersalamander.vocabtrainer.streak.StreakTracker
import io.github.wandersalamander.vocabtrainer.wordlist.WordListActivity
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

private const val TAG = "MainActivity"
private const val UTTERANCE_AWAITABLE = "awaitable_sentence"

// Group id for the dynamically-built language submenu items (radio group).
private const val MENU_GROUP_LANGUAGE = 1

// Offer streak reminders (and request notification permission) only after the
// user has actually used the app this long in total. A cold-start permission
// prompt gets reflexively denied; an in-context ask once the habit is forming
// converts far better. Foreground time only — see onResume/onPause.
private const val REMINDER_USAGE_THRESHOLD_MS = 10 * 60 * 1000L

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

    // Wall-clock anchor (elapsedRealtime) for the current foreground session,
    // stamped in onResume and drained into the cumulative usage total in onPause.
    // 0 means "no live session" so onPause doesn't double-count across recreate.
    private var sessionStartRealtimeMs: Long = 0L

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Only ever launched from the in-context streak-reminder opt-in (see
        // maybeAskAboutReminders). On grant, arm the daily reminder; on denial
        // the reminder option stays on but silent until the user enables
        // notifications in system settings.
        if (granted) {
            StreakAlarmScheduler.schedule(this)
            Toast.makeText(this, "Daily reminder set", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First-ever launch: bounce to setup before touching services, TTS, or
        // the dictionary. OnboardingActivity persists the language + active-pool
        // choice and relaunches us (CLEAR_TASK), so by the time we get back here
        // onboarding is done and the rest of onCreate runs normally.
        if (!AppPrefs.isOnboardingDone(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        serviceIntent = Intent(this, MyForegroundService::class.java)
        // The passive "word in the notification" glance service is opt-in
        // (Settings → Word notification, default off), so only start it when the
        // user has turned it on. On Android 13+ it also needs notification
        // permission to actually show — requested when they flip it on in Settings.
        if (AppPrefs.isVocabServiceEnabled(this)) startVocabService()
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
            // After the level check + placement dialog, MainActivity (later
            // languages only) asks for the active-pool size, then begins drilling.
            onAssessmentComplete = ::onAssessmentComplete,
            // Just-in-time onboarding: each control introduces itself the first
            // time it's relevant, instead of one up-front tour over everything.
            coachEvents = CoachMarks(this, views),
        )
        wireQuizControllerListeners()
        quizController.refreshStreakBadge()

        // Launched with the device already muted: start with listening off so
        // the user isn't dropped into silent listening rounds. The onResume that
        // follows won't double-toast — listening is already off by then.
        syncListeningModeWithVolume()

        startFirstRunFlow()
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
        listOf(views.buttonFail, views.buttonTip).forEach { it.setInteractive(false) }
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
     * First-launch sequencing. Language selection already happened in
     * OnboardingActivity, so what's left for a fresh language is the level check;
     * everything after it (the active-pool size for later languages, and the
     * first drill word) is driven from [onAssessmentComplete]. Onboarding itself
     * is now just-in-time: each control coaches itself from the round flow (see
     * [CoachMarks]) rather than an up-front tour, so there's nothing to show
     * proactively for an already-calibrated language — the else case is
     * intentionally absent.
     */
    private fun startFirstRunFlow() {
        if (willCalibrate()) {
            findViewById<View>(android.R.id.content).post { quizController.startCalibration() }
        }
    }

    /**
     * Called by QuizController once the level check and its placement dialog
     * have been dismissed. Asks for the active-pool size here — after the
     * assessment, so language placement and drill configuration stay separate —
     * unless this language already has a chosen size (any language tuned earlier
     * in Settings). Then drilling begins and, first run only, the tutorial.
     *
     * Run once per unlearned language, not just first install: switching to a
     * fresh language via the selector lands here the same way.
     */
    private fun onAssessmentComplete() {
        if (AppPrefs.hasAutoAddThreshold(this, language.code)) {
            startDrilling()
        } else {
            showActivePoolSetupDialog { startDrilling() }
        }
    }

    /**
     * Hand off from assessment to normal play by loading the first real drill
     * word. The core-loop coach mark is raised from
     * [QuizController.beginDrillingAfterAssessment] the moment that word is on
     * the card; every other control coaches itself later via the round flow.
     */
    private fun startDrilling() {
        quizController.beginDrillingAfterAssessment()
    }

    /**
     * Once-per-language active-pool chooser, shown after the level check.
     * Pre-filled with the inherited default, persists the chosen size for
     * [language] on close, then invokes [onChosen] (begin drilling).
     * Non-cancelable with a single button so the size is always committed first.
     */
    private fun showActivePoolSetupDialog(onChosen: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_active_pool_setup, null)
        val headline = view.findViewById<TextView>(R.id.poolSetupHeadline)
        val sub = view.findViewById<TextView>(R.id.poolSetupSub)
        val valueText = view.findViewById<TextView>(R.id.poolSetupValue)
        val decrease = view.findViewById<TextView>(R.id.poolSetupDecrease)
        val increase = view.findViewById<TextView>(R.id.poolSetupIncrease)
        val button = view.findViewById<TextView>(R.id.poolSetupButton)

        headline.text = "${language.flag}  ${language.englishName}"
        sub.text = "New language! How many words should stay in rotation while you " +
            "learn ${language.englishName}? You can change this any time in Settings."

        var value = AppPrefs.autoAddThreshold(this, language.code)
        fun render() {
            valueText.text = value.toString()
            valueText.contentDescription = "Words in rotation: $value"
            decrease.isEnabled = value > AUTO_INTRODUCE_THRESHOLD_MIN
            decrease.alpha = if (decrease.isEnabled) 1f else 0.35f
            increase.isEnabled = value < AUTO_INTRODUCE_THRESHOLD_MAX
            increase.alpha = if (increase.isEnabled) 1f else 0.35f
        }
        decrease.setOnClickListener {
            value = (value - 1).coerceAtLeast(AUTO_INTRODUCE_THRESHOLD_MIN); render()
        }
        increase.setOnClickListener {
            value = (value + 1).coerceAtMost(AUTO_INTRODUCE_THRESHOLD_MAX); render()
        }
        render()

        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(false).create()
        var committed = false
        dialog.setOnDismissListener {
            if (committed) return@setOnDismissListener
            committed = true
            AppPrefs.setAutoAddThreshold(this, language.code, value)
            onChosen()
        }
        button.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Whether the once-per-language calibration probe is due: it hasn't already
     * run for this language ([AppPrefs.isCalibrationDone]) and nothing has been
     * introduced yet. The emptiness check stops upgrading or mid-learning users
     * from being probed retroactively; a language wipe clears both signals (see
     * SettingsActivity.wipeProgressFor) so a wiped language re-calibrates.
     */
    private fun willCalibrate(): Boolean {
        if (AppPrefs.isCalibrationDone(this, language.code)) return false
        return vocabDictionary.csvData.none { it.hasBeenIntroduced() }
    }

    /**
     * The full guided tour over every control, now shown only on demand from the
     * ⋮ overflow menu ("Tutorial"). First-run onboarding no longer fires this
     * automatically — [CoachMarks] introduces each control just-in-time instead —
     * but a user who wants the whole map at once can still summon it, and doing so
     * sets [AppPrefs.KEY_TUTORIAL_SHOWN], which suppresses the per-control coach
     * marks (the tour already covered them).
     *
     * TapTargetView overlays each highlighted view in turn and isn't navigable
     * via TalkBack, so this is best-effort for sighted users; the controls all
     * carry contentDescriptions for screen readers regardless.
     */
    private fun showTutorial() {
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
        setupLanguageMenu(menu)
        return true
    }

    /**
     * Fill in the action-bar flag button: its title becomes the current
     * language's flag, and tapping it opens a submenu listing every language.
     * Built from [Language.values] so it stays in sync with the enum — the menu
     * XML carries only a placeholder submenu item, cleared here. Picking a
     * language persists it and recreates the activity, the same path [onResume]
     * takes when returning from Settings with a changed language.
     */
    private fun setupLanguageMenu(menu: Menu) {
        val item = menu.findItem(R.id.menu_language) ?: return
        // Custom action view (not a text menu item) so the flag emoji renders at
        // full opacity — the default action-item text color is <100% alpha, which
        // fades color-emoji glyphs and leaves the flag looking greyed out.
        val flagView = item.actionView as? TextView ?: return
        flagView.text = language.flag
        // A bare flag emoji says nothing to TalkBack — give the action a label.
        flagView.contentDescription = "Change language, currently ${language.englishName}"
        flagView.setOnClickListener { showLanguagePopup(it) }
    }

    /**
     * Drop-down language chooser anchored to the flag button. Built from
     * [Language.values] as an exclusive (radio) group with the current language
     * checked; picking one routes through [onLanguageSelected].
     */
    private fun showLanguagePopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        var current: MenuItem? = null
        Language.values().forEachIndexed { index, lang ->
            val mi = popup.menu.add(MENU_GROUP_LANGUAGE, index, index, "${lang.flag}  ${lang.englishName}")
            mi.setOnMenuItemClickListener { onLanguageSelected(lang); true }
            if (lang == language) current = mi
        }
        popup.menu.setGroupCheckable(MENU_GROUP_LANGUAGE, true, true)
        current?.isChecked = true
        popup.show()
    }

    private fun onLanguageSelected(selected: Language) {
        if (selected == language) return
        // Mirror SettingsActivity's language write, then recreate so onCreate
        // re-reads the language and rebuilds the dictionary/views around it.
        AppPrefs.get(this).edit().putString(AppPrefs.KEY_APP_LANGUAGE, selected.code).apply()
        recreate()
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

    /**
     * Single entry point for every TTS utterance: select the [locale] (foreign
     * vs. English voice) and enqueue [text] under [queueMode]. A non-null
     * [utteranceId] wires the awaitable progress callback (see
     * [speakSentenceAndAwait]); null is a fire-and-forget speak.
     */
    private fun speak(
        text: CharSequence,
        locale: Locale,
        queueMode: Int,
        utteranceId: String? = null,
    ) {
        textToSpeech.language = locale
        val params = utteranceId?.let {
            Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, it) }
        }
        textToSpeech.speak(text, queueMode, params, utteranceId)
    }

    override fun speakForeignWord(vocab: Vocab, flush: Boolean) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        speak(vocab.pronounceableForeign(language), language.locale, mode)
    }

    override fun speakEnglishWord(vocab: Vocab) {
        speak(vocab.pronounceableEn(), Locale.ENGLISH, TextToSpeech.QUEUE_ADD)
    }

    override fun speakSentence(sentence: CharSequence) {
        speak(sentence, language.locale, TextToSpeech.QUEUE_FLUSH)
    }

    override suspend fun speakSentenceAndAwait(sentence: CharSequence) {
        val done = CompletableDeferred<Unit>()
        pendingTtsDone = done
        speak(sentence, language.locale, TextToSpeech.QUEUE_ADD, UTTERANCE_AWAITABLE)
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
        // Both are set only after the first-run onboarding redirect early-returns
        // out of onCreate; guard so a redirected-and-finished instance tears down
        // cleanly instead of hitting an uninitialized lateinit.
        if (::serviceIntent.isInitialized) stopService(serviceIntent)
        if (::sounds.isInitialized) sounds.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // On the first-run onboarding redirect, onCreate early-returns before
        // any of these are set. onResume is normally skipped when finishing in
        // onCreate, but guard anyway so no OEM lifecycle quirk dereferences an
        // uninitialized lateinit here.
        if (!::quizController.isInitialized) return
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
        // Start counting this foreground session, then (once enough total usage
        // has accrued) offer streak reminders in context.
        sessionStartRealtimeMs = SystemClock.elapsedRealtime()
        maybeAskAboutReminders()
    }

    override fun onPause() {
        if (::quizController.isInitialized) {
            quizController.markPaused()
        }
        contentResolver.unregisterContentObserver(volumeObserver)
        if (sessionStartRealtimeMs > 0L) {
            AppPrefs.addUsageMs(this, SystemClock.elapsedRealtime() - sessionStartRealtimeMs)
            sessionStartRealtimeMs = 0L
        }
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

    /**
     * Once the user has spent [REMINDER_USAGE_THRESHOLD_MS] in the app, offer
     * daily streak reminders — and only then request notification permission.
     * Shown at most once (the flag is set the moment it appears, so dismissing it
     * counts as "not now"). Skipped on pre-13 devices (permission is implicit)
     * and once permission is already granted. "Yes" keeps the reminder option on,
     * arms the alarm, and launches the system permission prompt; the grant itself
     * is handled by [requestNotificationPermission].
     */
    private fun maybeAskAboutReminders() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        if (AppPrefs.isReminderPromptDone(this)) return
        if (AppPrefs.totalUsageMs(this) < REMINDER_USAGE_THRESHOLD_MS) return

        AppPrefs.setReminderPromptDone(this)
        val time = String.format(
            Locale.US,
            "%02d:%02d",
            StreakTracker.reminderHour(this),
            StreakTracker.reminderMinute(this),
        )
        AlertDialog.Builder(this)
            .setTitle("Keep your streak alive?")
            .setMessage(
                "Want a daily reminder at $time so a busy day doesn't cost you your " +
                    "streak? You can change the time or turn it off anytime in Settings."
            )
            .setPositiveButton("Yes, remind me") { _, _ ->
                StreakTracker.setReminderEnabled(this, true)
                StreakAlarmScheduler.schedule(this)
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun startVocabService() {
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
