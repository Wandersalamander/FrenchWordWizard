package com.example.myapplication.quiz

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.example.myapplication.R
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.SentenceSource
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.SkillStats
import com.example.myapplication.dictionary.Vocab
import com.example.myapplication.llm.LlmService
import com.example.myapplication.dictionary.ActiveSetCounts
import com.example.myapplication.setDebouncedOnClickListener
import com.example.myapplication.streak.MasteryTracker
import com.example.myapplication.streak.SkillFinishedTracker
import com.example.myapplication.streak.StreakTracker
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuizViews(
    val buttonFail: Button,
    val buttonNext: Button,
    val buttonNew: Button,
    val buttonTip: Button,
    val buttonHard: Button,
    val buttonLearned: Button,
    val cardVocab: SwipeableVocabCard,
    // Roots of the two bottom progress cards, captured so refreshLifetimeBar /
    // refreshActiveBar can attach a contentDescription that summarises the
    // bar for screen readers (raw segment Views have no semantics on their
    // own).
    val cardLifetime: View,
    val cardActive: View,
    val textForeign: TextView,
    val textScore: TextView,
    val textEn: TextView,
    val textGuessLong: TextView,
    val textStreak: TextView,
    val textStreakShield: TextView,
    // Two progress cards in activity_main.xml: lifetime mastered (single fill
    // of the deck) on cardLifetime, and the composition of the
    // currently-drilling pool, segmented by skill, on cardActive.
    val textLifetimeLabel: TextView,
    val segLifetimeMastered: View,
    val segLifetimeRemaining: View,
    val textActiveLabel: TextView,
    // Three goldenrod "-N" floaters that lift off the matching digit in
    // textActiveLabel whenever a (word, skill) pair retires below the mastery
    // threshold. Driven by QuizController.animateActiveDelta.
    val textActiveDeltaRead: TextView,
    val textActiveDeltaListen: TextView,
    val textActiveDeltaInvert: TextView,
    val segActiveRead: View,
    val segActiveListen: View,
    val segActiveInvert: View,
    val thinkingSparkle: ImageView,
)

interface TtsHelper {
    fun speakForeignWord(vocab: Vocab, flush: Boolean = false)
    fun speakEnglishWord(vocab: Vocab)
    /** Speaks [sentence] in the foreign locale, flushing any in-flight speech. */
    fun speakSentence(sentence: CharSequence)
    /**
     * Speaks [sentence] in the foreign locale and suspends until TTS has
     * finished playing it. Replaces the previous CountDownLatch-based pattern
     * (which had a stale-reference race when the latch field was reassigned
     * while a worker was already mid-await).
     */
    suspend fun speakSentenceAndAwait(sentence: CharSequence)
}

private const val SPOT_CHECK_PROBABILITY = 0.2
private const val RECENT_WORDS_CAPACITY = 20
private const val RECENT_FOREIGN_AVOID_COUNT = 4
private const val VIEW_TIME_EMA_ALPHA = 0.3
private const val WRONG_VOCAB_PICK_ATTEMPTS = 50
private const val SENTENCE_FADE_IN_DURATION_MS = 220L
// Every Nth displayed round (counting committed words, not picker retries) is
// granted to a due mastered (word, skill) pair, so post-mastery retention gets
// a guaranteed slot regardless of how crowded the active pool is. Skipped when
// no mastered pair has gone stale.
private const val MASTERED_REFRESH_INTERVAL = 20
// Backup safety floor on the active pool: every Nth round is granted to a
// uniform random pick from active pairs, ignoring sortValue weights. Catches
// near-mastered (word, skill) pairs whose weighted-picker probability has
// shrunk too far to ever finish them. Independent of MASTERED_REFRESH_INTERVAL;
// on the rare round both fire, mastered wins and uniform retries next round.
private const val UNIFORM_ACTIVE_INTERVAL = 20
// How long the success chime lasts; TTS for the next round is held off this
// long when a mastery just triggered, so the chime isn't drowned by speech.
private const val POST_SUCCESS_TTS_DELAY_MS = 500L

// Distance the goldenrod "-N" floater rises above the active label before
// fading out. ~24dp is enough to clear the bar row underneath so the floater
// reads as lifting off the number, not the segment.
private const val FLOATER_LIFT_DP = 24f

// Gap between a digit's right edge and the floater's left edge. The floater
// sits NEXT to the number ("7 -1"), not on top of it, so the user can read
// both the new count and the delta at the same time.
private const val FLOATER_SIDE_GAP_DP = 2f

// ACTIVE_LABEL_* and ActiveSkillFinishedToday live in ProgressBars.kt so the
// renderers and the floater-offset math share one source of truth.

/**
 * Drives the quiz flow: which vocab is showing, the tip/reveal/spot-check
 * state machine, and the per-word save-and-advance logic. Lifted out of
 * MainActivity.onCreate so the flow is one readable file instead of a stack
 * of inner functions over class fields.
 */
class QuizController(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val sounds: SoundEffects,
    private val tts: TtsHelper,
    private val vocabDictionary: MyDictionary,
    private val language: Language,
) {
    var currentVocab: Vocab? = null
        private set
    private val recentWords = ArrayDeque<String>()
    private var startTime: Long = System.currentTimeMillis()
    // Foreground-only round time: each onPause flushes the active interval into
    // here, and the next resume restarts the live segment. saveCurrentVocab sums
    // both so a settings detour or screen-off gap doesn't get counted as
    // thinking time, while pre-pause attention isn't thrown away either.
    private var elapsedBeforePause: Long = 0L
    private var inSpotCheck = false

    // Skill being practiced for the currently-displayed word. Set in updateVocab
    // when a new word is picked and used for all stat writes during the round.
    private var currentSkill: Skill = Skill.READ

    // Session-scoped: when false, LISTEN words and their tip sentences are
    // shown unmasked (e.g. the user is on a train without headphones).
    // Toggled via the menu; the next word picks up the new state.
    private var listeningEnabled: Boolean = true

    // Unmasked sentence kept around so the tap-to-re-read handler can speak
    // the original even when [QuizViews.textGuessLong] displays the masked form.
    private var currentSentence: String? = null

    // Snapshot of the active skill's stats at round start. Used to roll back
    // any mid-round mutations if the round is later flagged as compromised
    // (e.g. listening toggled off in a LISTEN round — user could read the
    // foreign word so the round shouldn't count toward LISTEN's stats).
    private var roundStartSnapshot: SkillStats? = null
    private var roundCompromised: Boolean = false

    // Number of letters already revealed via Tip in the current round; only
    // meaningful when the active skill has ProgressiveReveal tip behavior.
    private var progressiveRevealCount: Int = 0

    // Ticks once per active-pool pick; when it hits [MASTERED_REFRESH_INTERVAL]
    // the next pick is forced through the mastered-refresh picker.
    private var picksSinceMasteredRefresh: Int = 0

    // Independent cadence counter for the uniform-active backup slot. See
    // [UNIFORM_ACTIVE_INTERVAL]. Reset only when the slot is actually granted —
    // an empty active pool leaves the counter past threshold so the next
    // round retries.
    private var picksSinceUniformActive: Int = 0

    // Held when the round-start TTS is being deferred so a success chime can
    // finish unmuffled. Cancelled at the start of the next updateVocab so the
    // previous round's word never speaks over the new one.
    private var pendingRoundStartTtsJob: Job? = null

    fun onFailClick() {
        if (inSpotCheck) exitSpotCheck(wasWrong = true)
        else revealAllVocabData(showNextVocab = true)
    }

    fun onNextClick() {
        if (inSpotCheck) {
            exitSpotCheck(wasWrong = false)
        } else if (currentVocab != null &&
            (wouldFinishSkillOnNext() || Random.nextDouble() < SPOT_CHECK_PROBABILITY)) {
            startSpotCheck()
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    /**
     * True when a clean commit of the current round would push the active
     * skill's failureProbability across the mastery threshold. Used to force
     * a spot check so a streak of fast Next-presses can't sneak a (word,
     * skill) pair into mastery without a verification step.
     *
     * Simulates the commit on a copy of the stats — same math as
     * [saveCurrentVocab]'s update plus [SkillStats.failureProbability] —
     * rather than computing analytically, so the slow-floor and any future
     * tweaks to the FP formula stay in one place.
     */
    private fun wouldFinishSkillOnNext(): Boolean {
        val vocab = currentVocab ?: return false
        val stats = vocab.stats(currentSkill)
        if (stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD) return false
        val timeElapsed = elapsedBeforePause + (System.currentTimeMillis() - startTime)
        val sim = stats.copy()
        sim.nTimesViewed += 1
        sim.viewTimeMilli_prev = sim.viewTimeMilli
        sim.viewTimeMilli =
            (VIEW_TIME_EMA_ALPHA * timeElapsed + (1.0 - VIEW_TIME_EMA_ALPHA) * sim.viewTimeMilli).toLong()
        return sim.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
    }

    fun onNewClick() = updateVocab(0, newCandidates = true)
    fun onTipClick() = showTip()

    /**
     * Wire the vocab card's bouncy pull-down gesture. First pull on a READ /
     * LISTEN round fires Tip; once Tip is consumed (or unavailable for the
     * skill) the same gesture fires Fail. INVERT rounds keep [pullEnabled]
     * false so the gesture is inert. Called once from [MainActivity] after
     * construction.
     */
    fun setupVocabCardPull() {
        views.cardVocab.onPullCommitted = { onCardPulled() }
    }

    private fun onCardPulled() {
        when {
            views.buttonTip.isEnabled && views.buttonTip.isClickable -> onTipClick()
            views.buttonFail.isEnabled && views.buttonFail.isClickable -> onFailClick()
        }
    }

    private fun isPullDownSkill(skill: Skill): Boolean =
        skill == Skill.READ || skill == Skill.LISTEN

    /** True when listening practice is active (masking happens for LISTEN words). */
    fun isListeningModeEnabled(): Boolean = listeningEnabled

    /**
     * Toggle session-scoped listening mode and refresh the current word's
     * display. Both [QuizViews.textForeign] and [QuizViews.textGuessLong] (if
     * currently visible) are re-rendered so masking stays consistent
     * across the two views.
     */
    fun setListeningModeEnabled(enabled: Boolean) {
        listeningEnabled = enabled
        // If the new state compromises the current round, latch the flag so
        // toggling back on later doesn't un-void what the user already saw.
        if (currentSkill.flow.isCompromisedByListening(enabled)) {
            roundCompromised = true
        }
        // Listening turned off mid-LISTEN-round: discard the current word and
        // pick a new (non-LISTEN) one. updateVocab honours roundCompromised so
        // the LISTEN stats are rolled back before advancing.
        if (!enabled && currentSkill == Skill.LISTEN && currentVocab != null) {
            updateVocab(0, newCandidates = false)
            return
        }
        val vocab = currentVocab ?: return
        displayForeignWord(vocab)
        if (views.textGuessLong.visibility == View.VISIBLE) {
            val sentence = currentSentence ?: return
            renderSentence(sentence, currentSkill.flow.shouldMaskSentenceOnTip(listeningEnabled))
        }
    }

    fun onHardClick() {
        val vocab = currentVocab ?: return
        vocab.flaggedHard = !vocab.flaggedHard
        vocab.savePreferences()
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
    }

    /** Resume the live time segment; call when the activity resumes. */
    fun markResumed() {
        startTime = System.currentTimeMillis()
    }

    /**
     * Activity-resume entry point. Resumes the timer and repaints both
     * progress bars so any midnight rollover that happened while the app was
     * backgrounded is reflected immediately — without this, the bars keep
     * showing yesterday's gold "+N today" suffixes until the next swipe
     * fires refreshAllProgressBars().
     *
     * Re-baselines [renderedTodayCount] and [renderedActiveCounts] to the
     * post-rollover snapshot so the next genuine action still detects a real
     * delta (and isn't fooled by the artificial yesterday→0 drop on resume).
     */
    fun onAppResumed() {
        markResumed()
        if (vocabDictionary.csvData.isEmpty()) return
        val ctx = activity.applicationContext
        refreshLifetimeBar(ctx, views, vocabDictionary)
        refreshActiveBar(ctx, views, vocabDictionary)
        renderedTodayCount = MasteryTracker.todayCount(ctx)
        renderedActiveCounts = vocabDictionary.computeActiveSetCounts()
    }

    /**
     * Flush the active foreground segment into [elapsedBeforePause] so the
     * paused interval is excluded from the round's time. Call from the host
     * activity's onPause — safe to call repeatedly (subsequent calls add zero
     * because markResumed reanchors startTime).
     */
    fun markPaused() {
        elapsedBeforePause += System.currentTimeMillis() - startTime
        startTime = System.currentTimeMillis()
    }

    /**
     * Attach a Button role to the streak/shield TextViews so non-TalkBack
     * assistive tech (Switch Access, TV remotes, BrailleBack) sees them as
     * buttons. TextView + clickable=true announces correctly through TalkBack
     * already, but other services key off the node's class name.
     */
    fun setupStreakBadgeRoles() {
        listOf(views.textStreak, views.textStreakShield).forEach { badge ->
            ViewCompat.setAccessibilityDelegate(badge, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })
        }
    }

    fun setupLearnedButton() {
        views.buttonLearned.text = "✓"
        views.buttonLearned.setDebouncedOnClickListener {
            val vocab = currentVocab ?: return@setDebouncedOnClickListener
            // First tap reveals everything so the user can verify they
            // actually know the word before committing on the second tap.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            currentSentence?.let { renderSentence(it, mask = false) }
            views.textGuessLong.visibility = View.VISIBLE
            views.buttonLearned.text = "✓?"
            views.buttonLearned.setDebouncedOnClickListener {
                vocab.ignore = true
                vocab.savePreferences()
                setupLearnedButton()
                updateVocab(0, newCandidates = false)
            }
        }
    }

    private fun showTip() {
        val vocab = currentVocab ?: return
        when (currentSkill.flow.tipBehavior) {
            TipBehavior.Sentence -> showSentenceTip(vocab)
            TipBehavior.ProgressiveReveal -> showProgressiveRevealTip(vocab)
            TipBehavior.None -> Unit
        }
    }

    private fun showSentenceTip(vocab: Vocab) {
        vocab.stats(currentSkill).recordFailure(0.25f)

        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            val sentence = generateAndShowExampleSentence(vocab, maskWhenListening = true)
            tts.speakSentenceAndAwait(sentence)
            delay(1000)
            views.buttonFail.isEnabled = true
            views.buttonFail.isClickable = true
            views.buttonNext.isEnabled = true
            views.buttonNext.isClickable = true
            // Tip stays disabled — one tip per word.
            // Re-enable the pull-down so a second pull can fire Fail. The
            // wider setQuizButtonsEnabled isn't used here because it would
            // un-disable Tip.
            views.cardVocab.pullEnabled = isPullDownSkill(currentSkill)
        }
    }

    private fun showProgressiveRevealTip(vocab: Vocab) {
        val letterCount = vocab.foreign.count { it.isLetter() }
        if (progressiveRevealCount >= letterCount) return
        progressiveRevealCount++
        // Triangular weighting: the i-th of N reveals costs
        // 2(N - i + 1) / (N(N+1)), so first reveal is the most expensive and
        // the total over a full unmask is exactly 1.0 (≈ one Fail).
        val penalty = 2f * (letterCount - progressiveRevealCount + 1) /
            (letterCount * (letterCount + 1))
        vocab.stats(currentSkill).recordFailure(penalty)
        currentSkill.flow.applyProgressiveReveal(vocab, views, progressiveRevealCount)
        if (progressiveRevealCount >= letterCount) {
            views.buttonTip.isEnabled = false
            views.buttonTip.isClickable = false
            onFailClick()
        }
    }

    private fun revealAllVocabData(showNextVocab: Boolean) {
        val vocab = currentVocab ?: return
        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            // User admitted they don't know. Reveal whichever side was hidden
            // for this skill: foreign-was-hidden in INVERT, English-was-hidden
            // in READ / LISTEN. TTS speaks the side that was previously kept
            // out of earshot.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            currentSkill.flow.ttsOnReveal(vocab, tts)

            // Some skills (LISTEN with listening on) reveal the cached sentence
            // for consistency with what the user just heard; others regenerate.
            val useExisting = currentSkill.flow.reuseSentenceOnReveal(listeningEnabled)
            val sentence = generateAndShowExampleSentence(vocab, useExisting = useExisting)
            tts.speakSentenceAndAwait(sentence)
            delay(1000)

            if (showNextVocab) {
                updateVocab(2000, newCandidates = false)
                delay(100)
            }
            // setQuizButtonsEnabled honours the per-skill Tip rule, so an
            // INVERT round won't accidentally re-enable the Tip button here.
            setQuizButtonsEnabled(true)
        }
    }

    private fun startSpotCheck() {
        inSpotCheck = true
        currentSkill.flow.setupSpotCheck(views, ::revealCurrentForeignWord)

        views.buttonFail.text = "✗ Wrong"
        views.buttonNext.text = "✓ Correct"
        views.buttonNew.isEnabled = false
        views.buttonNew.isClickable = false
        views.buttonTip.isEnabled = false
        views.buttonTip.isClickable = false
        views.cardVocab.pullEnabled = false

        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        sounds.playSpotCheck()

        maybeShowSpotCheckTutorial()
    }

    /**
     * First-spot-check explainer. The mode change (buttons relabel to ✓/✗,
     * answer reveals, vibration + chime fire) is genuinely surprising the
     * first time it triggers — without an explanation new users don't know
     * whether the buttons still mean what they did a moment ago. Skipped
     * when a screen reader is active (same focus-trap rationale as the main
     * TapTargetView tutorial).
     */
    private fun maybeShowSpotCheckTutorial() {
        val prefs = AppPrefs.get(activity)
        if (prefs.getBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, false)) return
        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isTouchExplorationEnabled) {
            prefs.edit().putBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, true).apply()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Spot check!")
            .setMessage(
                "Every now and then, the app pauses to verify you actually knew the word. " +
                    "The answer is revealed above.\n\n" +
                    "Tap ✓ Correct if you got it right, or ✗ Wrong if not."
            )
            .setPositiveButton("Got it", null)
            .show()
        prefs.edit().putBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, true).apply()
    }

    private fun exitSpotCheck(wasWrong: Boolean) {
        inSpotCheck = false
        views.buttonFail.text = "✗ Reveal"
        views.buttonNext.text = "✓ Know it"
        if (wasWrong) {
            currentVocab?.let { it.stats(currentSkill).recordFailure(1.0f) }
            updateVocab(2000, newCandidates = false)
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    fun updateVocab(penalty: Long, newCandidates: Boolean, saveVocab: Boolean = true) {
        // Cancel any deferred TTS from the previous round so a rapid Next press
        // doesn't let the prior word speak over the new one.
        pendingRoundStartTtsJob?.cancel()
        pendingRoundStartTtsJob = null

        val successPlayed = applyFailurePenaltyAndPersist(penalty, saveVocab)
        val vocab = advanceToNextDistinctVocab(newCandidates)

        resetRoundState(vocab)
        refreshAllProgressBars()
        refreshStreakBadge()
        renderScoreText(vocab)
        setQuizButtonsEnabled(true)

        displayForeignWord(vocab)
        views.textEn.visibility =
            if (currentSkill.flow.englishIsPrompt) View.VISIBLE else View.INVISIBLE
        views.textEn.text = vocab.english
        views.textGuessLong.visibility = View.INVISIBLE
        seedSentenceCache(vocab)
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
        setupLearnedButton()
        applyCardAccent(views.cardVocab, currentSkill)
        popView(views.cardVocab, peakScale = 1.04f)

        startTime = System.currentTimeMillis()
        elapsedBeforePause = 0L
        if (successPlayed) {
            pendingRoundStartTtsJob = activity.lifecycleScope.launch {
                delay(POST_SUCCESS_TTS_DELAY_MS)
                currentSkill.flow.ttsAtRoundStart(vocab, tts)
            }
        } else {
            currentSkill.flow.ttsAtRoundStart(vocab, tts)
        }
    }

    /** Returns true if the persistence step triggered the mastery success chime. */
    private fun applyFailurePenaltyAndPersist(penalty: Long, saveVocab: Boolean): Boolean {
        if (penalty > 0) {
            currentVocab?.let { it.stats(currentSkill).recordFailure(1.0f) }
        }
        return if (saveVocab) saveCurrentVocab(penalty) else false
    }

    /**
     * Pick a new vocab/skill pair, retrying briefly to avoid repeating the
     * same foreign word back-to-back. Updates [currentVocab] and [currentSkill]
     * in place and returns the new vocab non-null.
     */
    private fun advanceToNextDistinctVocab(newCandidates: Boolean): Vocab {
        // Tick the cadence once per displayed round (NOT once per pickNextVocab
        // call) so the anti-repeat retries below don't race the counter past
        // threshold faster than every Nth round.
        val allowMasteredRefresh = !newCandidates && tickMasteredCadence()
        val allowUniformActive = !newCandidates && tickUniformActiveCadence()
        var current: Vocab = currentVocab ?: run {
            val (v, s) = pickNextVocab(newCandidates, allowMasteredRefresh, allowUniformActive)
            currentVocab = v
            currentSkill = s
            v
        }
        // Avoid the last few foreign words so a heavy-weight pair can't
        // ping-pong (X-Y-X-Y) within a small active pool. The just-displayed
        // word is in [recentWords] (added by saveCurrentVocab), but include
        // current defensively for the first-ever pick when the deque is empty.
        val avoidForeign = recentWords.toList()
            .takeLast(RECENT_FOREIGN_AVOID_COUNT)
            .toSet() + current.foreign
        var attempts = 0
        while (current.foreign in avoidForeign && attempts < WRONG_VOCAB_PICK_ATTEMPTS) {
            val (v, s) = pickNextVocab(newCandidates, allowMasteredRefresh, allowUniformActive)
            current = v
            currentVocab = v
            currentSkill = s
            attempts++
        }
        return current
    }

    /**
     * Bumps the once-per-round cadence counter and reports whether this round
     * should attempt a mastered-refresh pick. The counter is only reset by
     * [pickNextVocab] when the slot is actually granted — a round with no
     * eligible mastered pair leaves the counter past threshold so the next
     * round retries.
     */
    private fun tickMasteredCadence(): Boolean {
        picksSinceMasteredRefresh++
        return picksSinceMasteredRefresh >= MASTERED_REFRESH_INTERVAL
    }

    private fun tickUniformActiveCadence(): Boolean {
        picksSinceUniformActive++
        return picksSinceUniformActive >= UNIFORM_ACTIVE_INTERVAL
    }

    private fun resetRoundState(vocab: Vocab) {
        // Snapshot the active skill's stats so we can roll back if the round
        // turns out to be compromised (e.g. a LISTEN round started with
        // listening already off — the user could read the foreign word).
        roundStartSnapshot = vocab.stats(currentSkill).copy()
        roundCompromised = currentSkill.flow.isCompromisedByListening(listeningEnabled)
        progressiveRevealCount = 0
        inSpotCheck = false
        views.buttonFail.text = "✗ Reveal"
        views.buttonNext.text = "✓ Know it"
    }

    // Last today-counter value reflected on screen; lets refreshAllProgressBars
    // detect an increment and pop the label without firing the animation on
    // every round.
    private var renderedTodayCount: Int = 0

    // Last per-group active counts mirrored on screen. Used to detect a drop
    // (a pair retired below the mastery threshold this round) and fire a
    // goldenrod "-N" delta floater off the matching digit. Null until the
    // first refresh so the first render doesn't claim a phantom drop.
    private var renderedActiveCounts: ActiveSetCounts? = null

    private fun refreshAllProgressBars() {
        if (vocabDictionary.csvData.isEmpty()) return
        val ctx = activity.applicationContext
        refreshLifetimeBar(ctx, views, vocabDictionary)
        val newCounts = vocabDictionary.computeActiveSetCounts()
        val todayFinished = ActiveSkillFinishedToday(
            read = SkillFinishedTracker.todayCount(ctx, Skill.READ),
            listen = SkillFinishedTracker.todayCount(ctx, Skill.LISTEN),
            invert = SkillFinishedTracker.todayCount(ctx, Skill.INVERT),
        )
        refreshActiveBar(ctx, views, newCounts, todayFinished)
        val todayNow = MasteryTracker.todayCount(ctx)
        if (todayNow > renderedTodayCount) popTodayCounter()
        renderedTodayCount = todayNow
        maybeFireActiveDeltas(newCounts, todayFinished)
        renderedActiveCounts = newCounts
    }

    private fun popTodayCounter() = popView(views.textLifetimeLabel, peakScale = 1.18f)

    /**
     * If any of the three active-pool counts dropped since the last render,
     * pop the whole label and fire a goldenrod "-N" floater off each affected
     * segment. The first render (renderedActiveCounts == null) is treated as
     * a baseline so a fresh-launch hydration doesn't claim a phantom drop.
     */
    private fun maybeFireActiveDeltas(
        newCounts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
    ) {
        val prev = renderedActiveCounts ?: return
        val dropRead = prev.read - newCounts.read
        val dropListen = prev.listen - newCounts.listen
        val dropInvert = prev.invert - newCounts.invert
        if (dropRead <= 0 && dropListen <= 0 && dropInvert <= 0) return
        popView(views.textActiveLabel, peakScale = 1.18f)
        // The label needs a layout pass after setText before
        // layout.getPrimaryHorizontal is meaningful; defer to the next frame.
        views.textActiveLabel.post {
            if (dropRead > 0) {
                animateActiveDelta(views.textActiveDeltaRead, dropRead, ActiveGroup.READ, newCounts, todayFinished)
            }
            if (dropListen > 0) {
                animateActiveDelta(views.textActiveDeltaListen, dropListen, ActiveGroup.LISTEN, newCounts, todayFinished)
            }
            if (dropInvert > 0) {
                animateActiveDelta(views.textActiveDeltaInvert, dropInvert, ActiveGroup.INVERT, newCounts, todayFinished)
            }
        }
    }

    /**
     * Fires a single goldenrod "-N" floater off the right edge of [group]'s
     * "{n_active} -{n_today}" run in textActiveLabel. The floater is anchored
     * immediately after the gold today-suffix, then lifts up ~24dp while
     * fading. Re-entrant: any in-flight animation on the same floater is
     * cancelled and restarted.
     */
    private fun animateActiveDelta(
        floater: TextView,
        drop: Int,
        group: ActiveGroup,
        counts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
    ) {
        val label = views.textActiveLabel
        val layout = label.layout ?: return
        val segmentRightX = segmentRightEdgeXInLabel(layout, counts, todayFinished, group) ?: return

        floater.animate().cancel()
        floater.text = "-$drop"
        val density = activity.resources.displayMetrics.density
        // Floater sits next to (not over) the segment. translationX is the
        // floater's own left edge inside the ConstraintLayout, and the
        // floater is start-constrained to textActiveLabel so X=0 there means
        // the label's left edge — so segmentRightX (a label-local X) maps 1:1.
        floater.translationX = segmentRightX + FLOATER_SIDE_GAP_DP * density
        floater.translationY = 0f
        floater.alpha = 0f
        floater.scaleX = 0.85f
        floater.scaleY = 0.85f
        floater.visibility = View.VISIBLE

        val liftPx = FLOATER_LIFT_DP * density
        floater.animate()
            .alpha(1f)
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(120L)
            .withEndAction {
                floater.animate()
                    .translationY(-liftPx)
                    .alpha(0f)
                    .scaleX(0.95f).scaleY(0.95f)
                    .setDuration(640L)
                    .withEndAction {
                        floater.visibility = View.INVISIBLE
                        floater.translationX = 0f
                        floater.translationY = 0f
                        floater.alpha = 1f
                        floater.scaleX = 1f
                        floater.scaleY = 1f
                    }
                    .start()
            }
            .start()
    }

    /**
     * Pixel X of the right edge of [group]'s "{active} -{today}" run inside
     * textActiveLabel's own coordinate space (NOT the parent's). Returns null
     * when the rendered text doesn't match the expected shape — a paranoid
     * fallback that just skips the floater rather than crashing.
     */
    private fun segmentRightEdgeXInLabel(
        layout: android.text.Layout,
        counts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
        group: ActiveGroup,
    ): Float? {
        val readSeg = perSkillRunLength(counts.read, todayFinished.read)
        val listenSeg = perSkillRunLength(counts.listen, todayFinished.listen)
        val invertSeg = perSkillRunLength(counts.invert, todayFinished.invert)
        val sepLen = ACTIVE_LABEL_SEPARATOR.length
        val expectedEnd = readSeg + sepLen + listenSeg + sepLen + invertSeg
        if (layout.text.length < expectedEnd) return null
        val end = when (group) {
            ActiveGroup.READ -> readSeg
            ActiveGroup.LISTEN -> readSeg + sepLen + listenSeg
            ActiveGroup.INVERT -> expectedEnd
        }
        return layout.getPrimaryHorizontal(end)
    }

    private fun perSkillRunLength(active: Int, finishedToday: Int): Int {
        val activeLen = active.toString().length
        if (finishedToday <= 0) return activeLen
        return activeLen +
            ACTIVE_LABEL_TODAY_JOIN.length +
            ACTIVE_LABEL_TODAY_PREFIX.length +
            finishedToday.toString().length
    }

    private enum class ActiveGroup { READ, LISTEN, INVERT }

    /**
     * Recolour the vocab card's top accent stripe to match [skill]. The stripe
     * is the second item in [R.drawable.bg_card_pink]'s layer-list; we mutate
     * its in-memory copy so other views referencing the same drawable
     * resource aren't dragged along. Mutating an already-mutated drawable is
     * a no-op, so calling this each round is safe.
     */
    private fun applyCardAccent(card: View, skill: Skill) {
        val bg = card.background.mutate() as LayerDrawable
        val stripe = bg.getDrawable(1) as GradientDrawable
        stripe.setColor(ContextCompat.getColor(activity, skill.accentColorRes()))
    }

    /**
     * Brief scale-up-then-back "pop" used to draw attention to a view that just
     * updated. [peakScale] is the apex of the animation; defaults match the
     * small-label feel of the lifetime counter, but callers animating larger
     * views (e.g. an entire card) should pass something modest like 1.04f so
     * the scaled view doesn't overlap its neighbors.
     */
    private fun popView(view: View, peakScale: Float = 1.18f) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(peakScale).scaleY(peakScale)
            .setDuration(120L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
            .start()
    }

    private fun renderScoreText(vocab: Vocab) {
        val currentStats = vocab.stats(currentSkill)
        if (currentStats.nTimesViewed == 0) {
            val msg = if (currentSkill == Skill.ladder.first()) "A new word!"
            else "${currentSkill.displayName} exercise unlocked!"
            views.textScore.text = msg
            views.textScore.contentDescription = msg
        } else {
            views.textScore.text = currentStats.getInfoString()
            // The visible string is glyph-heavy ("⧖ 1.4 s\n★★★☆☆") and
            // reads terribly through TalkBack. Build a sentence form from
            // the same data so SR users get "1.4 seconds average, 3 of 5
            // stars" instead.
            val seconds = currentStats.meanTimeViewedMilli() / 1000.0
            val successProbability =
                (1.0f - currentStats.failureProbability()).coerceIn(0.0f, 1.0f)
            val stars = (5 * successProbability).toInt()
            views.textScore.contentDescription =
                String.format("%.1f seconds average, %d of 5 stars", seconds, stars)
        }
    }

    /**
     * Seed the per-word sentence cache with the CSV fallback so a reveal
     * (Fail) without a prior Tip still has something to display without
     * burning an LLM call. Honour the user's easy/hard preference so the
     * seed matches what they'd see if Tip were pressed.
     */
    private fun seedSentenceCache(vocab: Vocab) {
        val seed = vocab.csvSentenceFor(SentenceSource.fromContext(activity))
        views.textGuessLong.text = seed
        views.textGuessLong.setOnClickListener(null)
        views.textGuessLong.isClickable = false
        currentSentence = seed
    }

    private fun pickNextVocab(
        newCandidates: Boolean,
        allowMasteredRefresh: Boolean,
        allowUniformActive: Boolean,
    ): Pair<Vocab, Skill> {
        // When listening is off the LISTEN skill is excluded from the pool —
        // getInactiveVocab always returns READ so it doesn't need the filter.
        val skillFilter: (Skill) -> Boolean =
            if (listeningEnabled) ({ true }) else ({ it != Skill.LISTEN })
        if (newCandidates) return vocabDictionary.getInactiveVocab()
        if (allowMasteredRefresh) {
            val mastered = vocabDictionary.pickMasteredVocabToRefresh(skillFilter)
            if (mastered != null) {
                picksSinceMasteredRefresh = 0
                return mastered
            }
            // No mastered pair is due — leave the counter past threshold so
            // the next round tries again instead of waiting a full cycle.
        }
        if (allowUniformActive) {
            val uniform = vocabDictionary.pickUniformActiveVocab(skillFilter)
            if (uniform != null) {
                picksSinceUniformActive = 0
                return uniform
            }
            // Empty active pool — leave the counter past threshold so the next
            // round retries once the pool has been seeded.
        }
        return vocabDictionary.getActiveVocabWeighted(skillFilter)
    }

    /** Returns true if a mastery transition triggered the success chime. */
    private fun saveCurrentVocab(penalty: Long): Boolean {
        val vocab = currentVocab ?: return false

        // Always update recent-words: the user has seen this word regardless
        // of whether the round counts, and we don't want to surface it again
        // back-to-back.
        rememberRecentWord(vocab.foreign)

        val snapshot = roundStartSnapshot
        if (roundCompromised && snapshot != null) {
            // Listening was off at some point during a LISTEN round — the
            // user could read the foreign word, so roll back any in-memory
            // mutations from the round and persist the rolled-back state.
            vocab.stats(currentSkill).copyFrom(snapshot)
            vocab.savePreferences()
            return false
        }

        val wasFullyMastered = isFullyMasteredNow(vocab)

        val timeElapsed =
            elapsedBeforePause + (System.currentTimeMillis() - startTime) + penalty
        val stats = vocab.stats(currentSkill)
        val prevFailureProbability = stats.failureProbability()
        val wasIntroduced = vocab.hasBeenIntroduced()

        stats.nTimesViewed += 1
        // Snapshot the pre-update EMA into _prev so the slow-gate sum in
        // failureProbability() spans the last two rounds rather than just one.
        stats.viewTimeMilli_prev = stats.viewTimeMilli
        stats.viewTimeMilli =
            (VIEW_TIME_EMA_ALPHA * timeElapsed + (1.0 - VIEW_TIME_EMA_ALPHA) * stats.viewTimeMilli).toLong()
        stats.lastDisplayed = System.currentTimeMillis()
        vocab.savePreferences()

        // First time this word entered the rotation — keep the LLM's
        // "previously studied words" cache in sync without rescanning csvData.
        if (!wasIntroduced && vocab.hasBeenIntroduced()) {
            vocabDictionary.notifyVocabIntroduced(vocab)
        }

        // A committed (non-compromised) round is what counts for the daily
        // streak. Idempotent within a day — first round of the day bumps the
        // count, subsequent rounds are no-ops.
        val result = StreakTracker.recordPracticeToday(activity.applicationContext)
        surfaceStreakResult(result)

        // Word just crossed from "still has a skill to master" to "every
        // ladder skill below threshold" — feed the "+N today" highlight on
        // the lifetime bar. The bar refresh later in the round flow picks
        // this up via MasteryTracker.todayCount.
        if (!wasFullyMastered && isFullyMasteredNow(vocab)) {
            MasteryTracker.recordMasteredToday(activity.applicationContext)
        }

        if (stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD &&
            prevFailureProbability >= Vocab.SKILL_FINISHED_THRESHOLD
        ) {
            // Per-skill "-N today" suffix on the ACTIVE WORDS label — same
            // crossing event that plays the success chime, recorded once per
            // (word, skill) so the suffix matches what just retired from the
            // active pool.
            SkillFinishedTracker.recordFinishedToday(activity.applicationContext, currentSkill)
            sounds.playSuccess()
            return true
        }
        return false
    }

    private fun isFullyMasteredNow(vocab: Vocab): Boolean {
        if (vocab.ignore) return true
        return Skill.ladder.all { skill ->
            val s = vocab.stats(skill)
            s.nTimesViewed > 0 && s.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
        }
    }

    private fun rememberRecentWord(word: String) {
        recentWords.remove(word)
        recentWords.addLast(word)
        while (recentWords.size > RECENT_WORDS_CAPACITY) {
            recentWords.removeFirst()
        }
    }

    /**
     * Refresh the streak badge from persistent state. Cheap: just a prefs
     * read + LocalDate comparison. Public so MainActivity.onResume can poke it
     * after returning from Settings (where the user may have toggled or reset
     * the streak indirectly via reminder changes).
     */
    fun refreshStreakBadge() {
        val ctx = activity.applicationContext
        val streak = StreakTracker.currentStreak(ctx)
        val shields = StreakTracker.freezesAvailable(ctx)
        views.textStreak.text = if (streak > 0) "🔥 $streak" else ""
        // Without a dynamic contentDescription, TalkBack reads the literal
        // glyphs ("fire emoji 12") or skips the value entirely. Spell out
        // the count and unit so SR users get the same information as the
        // visible badge.
        views.textStreak.contentDescription = when {
            streak <= 0 -> null
            streak == 1 -> "1 day streak, tap for details"
            else -> "$streak day streak, tap for details"
        }
        // Only expose the flame as a tap target when there's a streak to
        // explain — wrap_content collapses the empty state, so there's
        // nothing meaningful to tap on day 0.
        views.textStreak.isClickable = streak > 0
        views.textStreak.isFocusable = streak > 0
        views.textStreak.setOnClickListener { showStreakExplanation() }
        views.textStreakShield.text = "🛡️ $shields"
        views.textStreakShield.contentDescription = when (shields) {
            1 -> "1 streak shield available, tap for details"
            else -> "$shields streak shields available, tap for details"
        }
        views.textStreakShield.visibility = View.VISIBLE
        views.textStreakShield.setOnClickListener { showShieldExplanation() }
    }

    private fun showShieldExplanation() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_shield_info, null)
        view.findViewById<TextView>(R.id.shieldDialogEarn).text =
            "Earn one shield every ${StreakTracker.FREEZE_AWARD_INTERVAL_DAYS} days of streak " +
                "(up to ${StreakTracker.MAX_FREEZES} stockpiled)."
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showStreakExplanation() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_streak_info, null)
        val longest = StreakTracker.longestStreak(activity.applicationContext)
        if (longest > 0) {
            val plural = if (longest == 1) "" else "s"
            val longestView = view.findViewById<TextView>(R.id.streakDialogLongest)
            longestView.text = "Best so far: $longest day$plural"
            longestView.visibility = View.VISIBLE
        }
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun surfaceStreakResult(result: StreakTracker.RecordResult) {
        if (result !is StreakTracker.RecordResult.Counted) return
        val ctx = activity.applicationContext
        when {
            result.freezesConsumed > 0 -> {
                val plural = if (result.freezesConsumed == 1) "" else "s"
                Toast.makeText(
                    ctx,
                    "🛡️ Shield used — streak saved (${result.freezesConsumed} day$plural covered)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            result.freezeAwarded -> {
                Toast.makeText(
                    ctx,
                    "🛡️ Shield earned — keep your streak safe",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Generates (or falls back to) the example sentence, displays it in
     * [QuizViews.textGuessLong], and returns the unmasked text so the caller
     * can hand it to TTS. When [maskWhenListening] is true and the current
     * round is a listening one, the on-screen text is replaced with bullets
     * — the user must rely on audio rather than reading the word inside the
     * sentence. When [useExisting] is true (reveal flow), the cached
     * [currentSentence] is reused instead of calling the LLM again.
     */
    private suspend fun generateAndShowExampleSentence(
        vocab: Vocab,
        maskWhenListening: Boolean = false,
        useExisting: Boolean = false,
    ): String {
        val source = SentenceSource.fromContext(activity)
        val sentence: String = when {
            useExisting -> currentSentence ?: vocab.csvSentenceFor(source)
            source == SentenceSource.LLM && LlmService.isReady -> generateFromLlmOrFallback(vocab, source)
            else -> vocab.csvSentenceFor(source)
        }
        currentSentence = sentence
        val maskNow = maskWhenListening &&
            currentSkill.flow.shouldMaskSentenceOnTip(listeningEnabled)
        renderSentence(sentence, maskNow)
        views.textGuessLong.alpha = 0f
        views.textGuessLong.visibility = View.VISIBLE
        views.textGuessLong.animate().alpha(1f).setDuration(SENTENCE_FADE_IN_DURATION_MS).start()
        return sentence
    }

    private suspend fun generateFromLlmOrFallback(vocab: Vocab, source: SentenceSource): String {
        views.textGuessLong.visibility = View.INVISIBLE
        startThinkingAnimation()
        val generated = try {
            LlmService.generate(
                word = vocab.foreign,
                translation = vocab.english,
                recent = recentWords.toList(),
                everSeen = vocabDictionary.getIntroducedForeignWords(),
                language = language,
            )
        } finally {
            stopThinkingAnimation()
        }
        return generated ?: vocab.csvSentenceFor(source)
    }

    /** Sets [QuizViews.textGuessLong]'s text and tap-handler based on [mask]. */
    private fun renderSentence(sentence: String, mask: Boolean) {
        if (mask) {
            views.textGuessLong.text = maskSentence(sentence)
            views.textGuessLong.setDebouncedOnClickListener { onMaskedSentenceTapped() }
            views.textGuessLong.isClickable = true
            // The visible mask is all underscores — TalkBack reads each one
            // ("underscore underscore underscore…"). Swap in a sentence form
            // describing the tap action so SR users get something useful.
            views.textGuessLong.contentDescription =
                "Example sentence hidden, tap to hear it read aloud"
        } else {
            views.textGuessLong.text = sentence
            views.textGuessLong.setOnClickListener(null)
            views.textGuessLong.isClickable = false
            views.textGuessLong.contentDescription = null
        }
    }

    private fun maskSentence(sentence: String): String =
        sentence.map { if (it.isWhitespace()) it else '_' }.joinToString("")

    private fun startThinkingAnimation() {
        views.thinkingSparkle.visibility = View.VISIBLE
        (views.thinkingSparkle.drawable as? Animatable)?.start()
    }

    fun stopThinkingAnimation() {
        (views.thinkingSparkle.drawable as? Animatable)?.stop()
        views.thinkingSparkle.visibility = View.GONE
    }

    private fun setQuizButtonsEnabled(enabled: Boolean) {
        views.buttonFail.isEnabled = enabled
        views.buttonFail.isClickable = enabled
        views.buttonNext.isEnabled = enabled
        views.buttonNext.isClickable = enabled
        views.buttonNew.isEnabled = enabled
        views.buttonNew.isClickable = enabled
        // Tip's applicability is skill-dependent — e.g. INVERT disables it
        // because the example sentence would contain the answer word.
        val tipApplicable = enabled && currentSkill.flow.tipAllowed
        views.buttonTip.isEnabled = tipApplicable
        views.buttonTip.isClickable = tipApplicable
        views.cardVocab.pullEnabled = enabled && isPullDownSkill(currentSkill)
    }

    private fun displayForeignWord(vocab: Vocab) {
        currentSkill.flow.setupForeignText(
            vocab,
            views,
            listeningEnabled,
            ::onMaskedWordTapped,
            ::onWordReplayed,
        )
    }

    /** Replay the foreign word (with a small penalty) when the mask is tapped. */
    private fun onMaskedWordTapped() {
        val vocab = currentVocab ?: return
        vocab.stats(currentSkill).recordFailure(0.1f)
        tts.speakForeignWord(vocab, flush = true)
    }

    /**
     * Replay the foreign word when the user taps the already-visible word —
     * e.g. in READ, or in LISTEN with listening disabled. No penalty: the
     * answer was on screen anyway, the tap is just a "say it again" convenience.
     */
    private fun onWordReplayed() {
        val vocab = currentVocab ?: return
        tts.speakForeignWord(vocab, flush = true)
    }

    /** Replay the example sentence (with a small penalty) when its mask is tapped. */
    private fun onMaskedSentenceTapped() {
        val vocab = currentVocab ?: return
        val sentence = currentSentence ?: return
        vocab.stats(currentSkill).recordFailure(0.1f)
        tts.speakSentence(sentence)
    }

    /**
     * Called by [revealAllVocabData] when the user gives up: unmask the
     * foreign word and detach its tap-to-replay handler.
     */
    private fun revealCurrentForeignWord() {
        val vocab = currentVocab ?: return
        views.textForeign.text = vocab.foreign
        views.textForeign.setOnClickListener(null)
        views.textForeign.isClickable = false
    }
}
