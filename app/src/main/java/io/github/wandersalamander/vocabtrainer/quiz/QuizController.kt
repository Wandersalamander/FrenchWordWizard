package io.github.wandersalamander.vocabtrainer.quiz

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.scoring.FastTrack
import io.github.wandersalamander.vocabtrainer.data.scoring.RoundScoring
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.SkillStats
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import io.github.wandersalamander.vocabtrainer.pop
import io.github.wandersalamander.vocabtrainer.progress.MasteryTracker
import io.github.wandersalamander.vocabtrainer.progress.SkillFinishedTracker
import io.github.wandersalamander.vocabtrainer.quiz.picker.QuizPicker
import io.github.wandersalamander.vocabtrainer.quiz.ui.ProgressBarAnimator
import io.github.wandersalamander.vocabtrainer.quiz.ui.StreakBadgeView
import io.github.wandersalamander.vocabtrainer.quiz.ui.SwipeableVocabCard
import io.github.wandersalamander.vocabtrainer.quiz.ui.TipBehavior
import io.github.wandersalamander.vocabtrainer.quiz.ui.accentColorRes
import io.github.wandersalamander.vocabtrainer.quiz.ui.flow
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener
import io.github.wandersalamander.vocabtrainer.streak.StreakTracker
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
    val textVocabEyebrow: TextView,
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
// How long the success chime lasts; TTS for the next round is held off this
// long when a mastery just triggered, so the chime isn't drowned by speech.
private const val POST_SUCCESS_TTS_DELAY_MS = 500L
// Backgrounding past this duration discards the currently-displayed word on
// resume: the user has lost context on it, so saving a Next/Fail based on a
// stale view would corrupt the stats.
private const val LONG_PAUSE_DISMISS_THRESHOLD_MS = 30 * 1000L

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
    private val streakBadge = StreakBadgeView(activity, views)
    private val progressBars = ProgressBarAnimator(activity, views, vocabDictionary)
    private val picker = QuizPicker(vocabDictionary)
    private val sentenceManager = SentenceManager(
        activity = activity,
        views = views,
        dictionary = vocabDictionary,
        language = language,
        onMaskTapped = { onMaskedSentenceTapped() },
        recentWords = { picker.recent() },
    )
    private val spotCheck = SpotCheckController(
        activity = activity,
        views = views,
        sounds = sounds,
        tts = tts,
        revealForeignWord = ::revealCurrentForeignWord,
    )

    var currentVocab: Vocab? = null
        private set
    private var startTime: Long = System.currentTimeMillis()
    // Foreground-only round time: each onPause flushes the active interval into
    // here, and the next resume restarts the live segment. saveCurrentVocab sums
    // both so a settings detour or screen-off gap doesn't get counted as
    // thinking time, while pre-pause attention isn't thrown away either.
    private var elapsedBeforePause: Long = 0L
    // Wall-clock time stamped at markPaused so onAppResumed can measure the gap
    // and decide whether the displayed word is still fresh. 0 means "no pause
    // recorded yet" (fresh process), which is why onAppResumed gates on > 0.
    private var pauseWallClockMs: Long = 0L

    // Skill being practiced for the currently-displayed word. Set in updateVocab
    // when a new word is picked and used for all stat writes during the round.
    private var currentSkill: Skill = Skill.READ

    // Session-scoped: when false, LISTEN words and their tip sentences are
    // shown unmasked (e.g. the user is on a train without headphones).
    // Toggled via the menu; the next word picks up the new state.
    private var listeningEnabled: Boolean = true

    // Snapshot of the active skill's stats at round start. Used to roll back
    // any mid-round mutations if the round is later flagged as compromised
    // (e.g. listening toggled off in a LISTEN round — user could read the
    // foreign word so the round shouldn't count toward LISTEN's stats).
    private var roundStartSnapshot: SkillStats? = null
    private var roundCompromised: Boolean = false

    // Number of letters already revealed via Tip in the current round; only
    // meaningful when the active skill has ProgressiveReveal tip behavior.
    private var progressiveRevealCount: Int = 0

    // Held when the round-start TTS is being deferred so a success chime can
    // finish unmuffled. Cancelled at the start of the next updateVocab so the
    // previous round's word never speaks over the new one.
    private var pendingRoundStartTtsJob: Job? = null

    // Per-round observation accumulator. Reset in resetRoundState, mutated
    // from every user-action handler, and frozen into a ReviewLogEntry by
    // saveCurrentVocab. See RoundFacts kdoc for the raw-fact philosophy.
    private val roundFacts = RoundFacts()

    // True iff the just-finished round was terminated by onNewClick. Lets a
    // SECOND consecutive +New press commit the just-shown word as a clean NEW
    // outcome (rapid skipping ⇒ "I know it", so the fresh word should enter
    // the active pool instead of vanishing). Updated in resetRoundState from
    // [advancingByNewClick], which is set for the duration of the onNewClick
    // call so the marker survives the synchronous resetRoundState inside it.
    private var lastAdvancedByNewClick: Boolean = false
    private var advancingByNewClick: Boolean = false

    // True iff the currently-active spot check was triggered by an +New press
    // (as opposed to Next or a random check). Read by [exitSpotCheck] so the
    // resolution advances with newCandidates=true and the +New-flow marker
    // survives, instead of falling back to the Next-flow defaults.
    private var spotCheckOriginatedFromNewClick: Boolean = false

    fun onFailClick() {
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        if (spotCheck.isActive) exitSpotCheck(wasWrong = true)
        else revealAllVocabData(showNextVocab = true)
    }

    fun onNextClick() {
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        if (spotCheck.isActive) {
            exitSpotCheck(wasWrong = false)
        } else if (currentVocab != null && wouldMasterOnCommit()) {
            // Mastery is gated: no (word, skill) pair retires from active
            // without a spot check, regardless of which button or which path
            // (threshold crossing, fast-track) would have caused it.
            startSpotCheck()
        } else if (currentVocab != null && Random.nextDouble() < SPOT_CHECK_PROBABILITY) {
            startSpotCheck()
        } else {
            updateVocab(0, newCandidates = false, commitButton = CommitButton.NEXT)
        }
    }

    /**
     * True when a clean commit of the current round would cause the (word,
     * skill) pair to retire from the active pool — either by crossing the
     * [Vocab.SKILL_FINISHED_THRESHOLD] failureProbability gate, or by
     * tripping the [FastTrack] first-exposure short-circuit. Used to force a
     * spot check on every mastery route so no pair can sneak past the
     * verification step, regardless of which button or which threshold drove
     * the transition.
     */
    private fun wouldMasterOnCommit(): Boolean =
        wouldFinishSkillOnNext() || wouldFastTrackToMastery()

    /**
     * True when a clean commit of the current round would push the active
     * skill's failureProbability across the mastery threshold.
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
        val nowMs = System.currentTimeMillis()
        val timeElapsed = elapsedBeforePause + (nowMs - startTime)
        val sim = stats.copy()
        // Mid-round penalties (tip = 0.25, mask tap = 0.1, etc.) bump nFailed
        // but not the rolling window; the window delta is reconstructed the
        // same way saveCurrentVocab will, instead of assuming a clean 0.
        val simFailAmount = roundFailAmountDelta(stats, roundStartSnapshot)
        sim.applyRoundCommit(timeElapsedMs = timeElapsed, failAmount = simFailAmount, nowMs = nowMs)
        return sim.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
    }

    /**
     * Per-round failure delta = how much nFailed grew since the round-start
     * snapshot, clamped to non-negative. Compromised rollbacks restore the
     * snapshot in place, so the live commit path uses an identical
     * computation in [saveCurrentVocab].
     */
    private fun roundFailAmountDelta(stats: SkillStats, snapshot: SkillStats?): Float =
        (stats.nTimesFailed - (snapshot?.nTimesFailed ?: stats.nTimesFailed))
            .coerceAtLeast(0f)

    /**
     * True when a clean commit of the current round would trip the
     * [FastTrack] gate and flip [SkillStats.fastTracked] on, short-circuiting
     * failureProbability to [SkillStats.FAST_TRACKED_FPB] (sub-threshold).
     * [wouldFinishSkillOnNext] doesn't catch this case because the simulated
     * stats keep their default nFailed/nTimesViewed → fpb stays above the
     * threshold; the mastery comes entirely from the flag, not the counters.
     *
     * Already-fast-tracked pairs return false (no mastery transition to gate).
     */
    private fun wouldFastTrackToMastery(): Boolean {
        val vocab = currentVocab ?: return false
        val snapshot = roundStartSnapshot ?: return false
        if (snapshot.fastTracked) return false
        val activeViewTime = elapsedBeforePause + (System.currentTimeMillis() - startTime)
        return FastTrack.isCandidate(
            priorViews = snapshot.nTimesViewed,
            tipUsed = roundFacts.tipUsed,
            wordMaskTaps = roundFacts.wordMaskTaps,
            sentenceMaskTaps = roundFacts.sentenceMaskTaps,
            progressiveReveals = roundFacts.progressiveReveals,
            elapsedMs = activeViewTime,
            predecessorFastTracked = predecessorFastTracked(vocab, currentSkill),
        )
    }

    /**
     * True when [skill]'s predecessor in [Skill.ladder] has
     * [SkillStats.fastTracked] set on [vocab] — the build-up gate for whether
     * [skill] itself is eligible for the FastTrack shortcut. The first ladder
     * skill returns true vacuously (no predecessor to compound).
     *
     * Encodes the policy: the "I clearly know this" signal must keep being
     * earned at every tier; one tier mastered the long way (counters /
     * window) or a flag later revoked by a failure breaks the chain for
     * every subsequent tier. See [FastTrack] kdoc for the rationale.
     */
    private fun predecessorFastTracked(vocab: Vocab, skill: Skill): Boolean {
        val idx = Skill.ladder.indexOf(skill)
        if (idx <= 0) return true
        val predecessor = Skill.ladder[idx - 1]
        return vocab.stats(predecessor).fastTracked
    }

    fun onNewClick() {
        val isRepeat = lastAdvancedByNewClick
        advancingByNewClick = true
        if (isRepeat && currentVocab != null) {
            // Repeated +New ⇒ the user is rapidly skipping, signalling they
            // know these words. Commit the just-shown word as a clean NEW
            // round (so a fresh inactive word enters the active pool, and
            // FastTrack may even promote it past mastery on a zero-elapsed
            // round) instead of dismissing it into the void. The FIRST press
            // of a run still pure-dismisses, so a single skip of an unwanted
            // active review doesn't ghost-commit it.
            roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
            if (wouldMasterOnCommit()) {
                // Same gate as onNextClick: every mastery route goes through
                // a spot check. Remember the +New origin so the resolution
                // advances with fresh candidates (matching the +New intent)
                // rather than the Next-flow default.
                spotCheckOriginatedFromNewClick = true
                startSpotCheck()
            } else {
                updateVocab(0, newCandidates = true, commitButton = CommitButton.NEW)
            }
        } else {
            dismissCurrentVocab(newCandidates = true)
        }
        advancingByNewClick = false
    }

    fun onTipClick() {
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        showTip()
    }

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
            updateVocab(0, newCandidates = false, commitButton = CommitButton.OTHER)
            return
        }
        val vocab = currentVocab ?: return
        displayForeignWord(vocab)
        if (views.textGuessLong.visibility == View.VISIBLE) {
            sentenceManager.reRender(currentSkill, listeningEnabled)
        }
    }

    fun onHardClick() {
        val vocab = currentVocab ?: return
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        vocab.flaggedHard = !vocab.flaggedHard
        vocab.savePreferences()
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
    }

    /** Resume the live time segment; call when the activity resumes. */
    fun markResumed() {
        startTime = System.currentTimeMillis()
    }

    /**
     * Activity-resume entry point. Always rebaselines the progress bars so
     * any midnight rollover that happened in the background is absorbed
     * quietly (without it, bars would either keep yesterday's "+N today"
     * suffix or fire a spurious "-N" drop animation on the next refresh).
     *
     * On top of that, a long backgrounding ([LONG_PAUSE_DISMISS_THRESHOLD_MS])
     * discards the currently-displayed word: the user has lost context, and
     * a Next/Fail based on a stale view would corrupt the stats. Short pauses
     * just re-anchor the round timer.
     */
    fun onAppResumed() {
        val pausedMs = if (pauseWallClockMs > 0L) {
            System.currentTimeMillis() - pauseWallClockMs
        } else 0L
        pauseWallClockMs = 0L
        progressBars.rebaseline()
        if (pausedMs >= LONG_PAUSE_DISMISS_THRESHOLD_MS && currentVocab != null) {
            // dismissCurrentVocab's downstream updateVocab re-anchors startTime,
            // so markResumed isn't needed on this branch.
            dismissCurrentVocab(newCandidates = false)
        } else {
            markResumed()
        }
    }

    /**
     * Flush the active foreground segment into [elapsedBeforePause] so the
     * paused interval is excluded from the round's time. Also stamps wall-clock
     * so [onAppResumed] can size the gap. Call from the host activity's
     * onPause — safe to call repeatedly (subsequent calls add zero because
     * markResumed reanchors startTime).
     */
    fun markPaused() {
        val now = System.currentTimeMillis()
        elapsedBeforePause += now - startTime
        startTime = now
        pauseWallClockMs = now
    }

    /**
     * Discard the currently-displayed word without saving anything: roll any
     * mid-round in-memory penalties (tip, mask taps) back to the round-start
     * snapshot, mark the foreign as recently-shown so the picker avoids
     * back-to-back repeats, then advance to a fresh word. No stats update, no
     * review log, no streak credit, no mastery tracker tick — the user's view
     * of this word leaves no trace.
     *
     * Used when the user actively asks for a different word (+new), and when
     * the app resumes after a long absence that's invalidated the displayed
     * word. Safe to call when no vocab is currently displayed.
     */
    fun dismissCurrentVocab(newCandidates: Boolean) {
        val current = currentVocab
        val snapshot = roundStartSnapshot
        if (current != null && snapshot != null) {
            // Undo any mid-round penalties so the in-memory state matches
            // what's on disk. Without this, the dismissed word's stats would
            // carry tip/mask-tap fails until the next session reload.
            current.stats(currentSkill).copyFrom(snapshot)
            // Keep the anti-repeat deque consistent with what the user saw:
            // without this, a small active pool could roll the same word
            // again on the very next pick.
            picker.rememberWord(current.foreign)
        }
        updateVocab(
            penalty = 0,
            newCandidates = newCandidates,
            // commitButton is unused when saveVocab=false (no log entry written),
            // but updateVocab's signature requires it; OTHER fits semantically.
            commitButton = CommitButton.OTHER,
            saveVocab = false,
        )
    }

    fun setupStreakBadgeRoles() = streakBadge.setupRoles()

    fun setupLearnedButton() {
        views.buttonLearned.text = "✓"
        views.buttonLearned.setDebouncedOnClickListener {
            val vocab = currentVocab ?: return@setDebouncedOnClickListener
            roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
            // First tap reveals everything so the user can verify they
            // actually know the word before committing on the second tap.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            sentenceManager.renderUnmasked()
            views.textGuessLong.visibility = View.VISIBLE
            views.buttonLearned.text = "✓?"
            views.buttonLearned.setDebouncedOnClickListener {
                vocab.ignore = true
                vocab.savePreferences()
                setupLearnedButton()
                updateVocab(0, newCandidates = false, commitButton = CommitButton.LEARNED)
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
        vocab.stats(currentSkill).recordFailure(RoundScoring.SENTENCE_TIP_PENALTY)
        roundFacts.recordSentenceTip()

        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            val sentence = sentenceManager.generateAndShow(
                vocab,
                currentSkill,
                listeningEnabled,
                maskWhenListening = true,
            )
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
        // Triangular weighting (full formula in RoundScoring): the i-th of N
        // reveals costs 2·(N − i + 1) / (N·(N + 1)); the full unmask sums to
        // exactly 1.0 (≈ one Fail click). Shared with StatsReplay so changing
        // the curve updates both the live flow and any historical replay.
        val penalty = RoundScoring.progressiveRevealPenalty(progressiveRevealCount, letterCount)
        vocab.stats(currentSkill).recordFailure(penalty)
        roundFacts.recordProgressiveReveal()
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
            val sentence = sentenceManager.generateAndShow(
                vocab,
                currentSkill,
                listeningEnabled,
                useExisting = useExisting,
            )
            tts.speakSentenceAndAwait(sentence)
            delay(1000)

            if (showNextVocab) {
                updateVocab(
                    penalty = RoundScoring.FAIL_PENALTY_MS,
                    newCandidates = false,
                    commitButton = CommitButton.FAIL,
                )
                delay(100)
            }
            // setQuizButtonsEnabled honours the per-skill Tip rule, so an
            // INVERT round won't accidentally re-enable the Tip button here.
            setQuizButtonsEnabled(true)
        }
    }

    private fun startSpotCheck() {
        val vocab = currentVocab ?: return
        val viewTimeMs = elapsedBeforePause + (System.currentTimeMillis() - startTime)
        spotCheck.start(vocab, currentSkill, viewTimeMs)
    }

    private fun exitSpotCheck(wasWrong: Boolean) {
        spotCheck.exit()
        roundFacts.recordSpotCheckResult(passed = !wasWrong)
        val fromNew = spotCheckOriginatedFromNewClick
        spotCheckOriginatedFromNewClick = false
        // Carry the +New marker across this resolution iff the user passed:
        // a passing spot check on a +New-initiated check is the user
        // confirming they really do know the just-shown word, so the next
        // round should continue treating subsequent +New presses as part of
        // the same "rapidly skipping known words" run. A FAIL contradicts the
        // assumption — fall back to single-press dismiss for the next round.
        if (fromNew && !wasWrong) advancingByNewClick = true
        // updateVocab → applyFailurePenaltyAndPersist records the failure
        // because penalty > 0; don't double-count it here. The commit kind
        // reflects both the spot-check outcome AND the originating button:
        // ✓ Correct via +New stays a NEW commit, ✓ Correct via Next a NEXT,
        // ✗ Wrong is always FAIL regardless of origin. newCandidates honors
        // the originating button's intent so a passed +New still pulls a
        // fresh inactive word.
        updateVocab(
            penalty = if (wasWrong) RoundScoring.FAIL_PENALTY_MS else 0L,
            newCandidates = fromNew,
            commitButton = when {
                wasWrong -> CommitButton.FAIL
                fromNew -> CommitButton.NEW
                else -> CommitButton.NEXT
            },
        )
        // updateVocab → resetRoundState has already absorbed advancingByNewClick
        // into lastAdvancedByNewClick; clear the transient flag so it doesn't
        // bleed into any later non-+New path.
        advancingByNewClick = false
    }

    fun updateVocab(
        penalty: Long,
        newCandidates: Boolean,
        commitButton: CommitButton,
        saveVocab: Boolean = true,
    ) {
        cancelPendingRoundStartTts()
        val successPlayed = applyFailurePenaltyAndPersist(penalty, commitButton, saveVocab)
        val vocab = advanceToNextDistinctVocab(newCandidates)

        resetRoundState(vocab)
        renderNewRound(vocab)
        resetRoundTiming()
        scheduleRoundStartTts(vocab, deferForSuccessChime = successPlayed)
    }

    /**
     * Cancel any deferred round-start TTS from the previous round. A rapid
     * Next press would otherwise let the prior word speak over the new one,
     * because the post-success delay [POST_SUCCESS_TTS_DELAY_MS] keeps the
     * earlier launch alive across the round boundary.
     */
    private fun cancelPendingRoundStartTts() {
        pendingRoundStartTtsJob?.cancel()
        pendingRoundStartTtsJob = null
    }

    /**
     * Repaint every per-round UI surface for [vocab]: the score line, score
     * eyebrow, foreign and English prompts, sentence manager, hard/learned
     * buttons, card accent, and the card-pop entrance animation. Called once
     * per round transition right after [resetRoundState].
     */
    private fun renderNewRound(vocab: Vocab) {
        progressBars.refreshAll()
        refreshStreakBadge()
        renderScoreText(vocab)
        setQuizButtonsEnabled(true)

        displayForeignWord(vocab)
        views.textEn.visibility =
            if (currentSkill.flow.englishIsPrompt) View.VISIBLE else View.INVISIBLE
        views.textEn.text = vocab.english
        views.textGuessLong.visibility = View.INVISIBLE
        sentenceManager.seed(vocab)
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
        setupLearnedButton()
        applyCardAccent(views.cardVocab, currentSkill)
        views.cardVocab.pop(peakScale = 1.04f)
    }

    /**
     * Zero the per-round timing fields. [startTime] is the wall-clock anchor
     * the active view-time math is measured from; [elapsedBeforePause]
     * accumulates time across backgrounding events within a single round.
     */
    private fun resetRoundTiming() {
        startTime = System.currentTimeMillis()
        elapsedBeforePause = 0L
    }

    /**
     * Speak the round-opening prompt for [vocab]. When the previous round
     * just triggered a mastery success chime, hold the TTS off by
     * [POST_SUCCESS_TTS_DELAY_MS] so the chime isn't drowned by speech;
     * otherwise speak immediately. The deferred path stores its job in
     * [pendingRoundStartTtsJob] so [cancelPendingRoundStartTts] can interrupt
     * it on a rapid advance.
     */
    private fun scheduleRoundStartTts(vocab: Vocab, deferForSuccessChime: Boolean) {
        if (deferForSuccessChime) {
            pendingRoundStartTtsJob = activity.lifecycleScope.launch {
                delay(POST_SUCCESS_TTS_DELAY_MS)
                currentSkill.flow.ttsAtRoundStart(vocab, tts)
            }
        } else {
            currentSkill.flow.ttsAtRoundStart(vocab, tts)
        }
    }

    /** Returns true if the persistence step triggered the mastery success chime. */
    private fun applyFailurePenaltyAndPersist(
        penalty: Long,
        commitButton: CommitButton,
        saveVocab: Boolean,
    ): Boolean {
        if (penalty > 0) {
            currentVocab?.let { it.stats(currentSkill).recordFailure(RoundScoring.FAIL_PENALTY) }
        }
        return if (saveVocab) saveCurrentVocab(penalty, commitButton) else false
    }

    /**
     * Pick a new vocab/skill pair. Updates [currentVocab] and [currentSkill]
     * in place and returns the new vocab non-null.
     */
    private fun advanceToNextDistinctVocab(newCandidates: Boolean): Vocab {
        val (v, s) = picker.pickNext(
            newCandidates = newCandidates,
            listeningEnabled = listeningEnabled,
            avoidCurrentForeign = currentVocab?.foreign,
        )
        currentVocab = v
        currentSkill = s
        return v
    }

    private fun resetRoundState(vocab: Vocab) {
        // Snapshot the active skill's stats so we can roll back if the round
        // turns out to be compromised (e.g. a LISTEN round started with
        // listening already off — the user could read the foreign word).
        roundStartSnapshot = vocab.stats(currentSkill).copy()
        roundCompromised = currentSkill.flow.isCompromisedByListening(listeningEnabled)
        progressiveRevealCount = 0
        roundFacts.reset(System.currentTimeMillis())
        spotCheck.reset()
        views.textVocabEyebrow.text = "WORD"
        views.buttonFail.text = "✗ Reveal"
        views.buttonNext.text = "✓ Know it"
        // Carry the +New marker across the round boundary: true only when
        // updateVocab was reached via onNewClick. Any other path (Next, Fail,
        // Tip-then-Fail, listening toggle, long-pause dismiss) clears it, so
        // the "second consecutive +New" check fires only on genuinely
        // back-to-back +New presses.
        lastAdvancedByNewClick = advancingByNewClick
        // Defensive: spot-check origin shouldn't survive a round transition.
        // exitSpotCheck clears it on the normal path; this catches any path
        // that bypasses exitSpotCheck (e.g. future code that advances while
        // spot check was still active).
        spotCheckOriginatedFromNewClick = false
    }

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

    /** Returns true if a mastery transition triggered the success chime. */
    private fun saveCurrentVocab(penalty: Long, commitButton: CommitButton): Boolean {
        val vocab = currentVocab ?: return false

        // Always update recent-words: the user has seen this word regardless
        // of whether the round counts, and we don't want to surface it again
        // back-to-back.
        picker.rememberWord(vocab.foreign)

        // Captured once so the log entry's timestamp_ms, the stats'
        // lastDisplayed, and any view-time math all agree on a single "now".
        val nowMs = System.currentTimeMillis()
        val activeViewTime = spotCheck.frozenViewTimeMs
            ?: (elapsedBeforePause + (nowMs - startTime))

        val snapshot = roundStartSnapshot
        if (roundCompromised && snapshot != null) {
            persistCompromisedRound(vocab, snapshot, commitButton, activeViewTime, nowMs)
            return false
        }

        val stats = vocab.stats(currentSkill)
        // Use the round-start snapshot for the pre-round fpb, not the live
        // stats — mid-round penalties (tip, mask taps) have already bumped
        // nFailed on the live stats, which would make the mastery-crossing
        // comparison fire on rounds that started below the threshold,
        // briefly dipped above mid-round, and ended below again. The chime
        // should only fire on a genuine active → mastered transition.
        val prevFailureProbability = snapshot?.failureProbability() ?: stats.failureProbability()
        val wasFullyMastered = isFullyMasteredNow(vocab)
        val wasIntroduced = vocab.hasBeenIntroduced()

        // failAmount = nFailed delta since round start, so the rolling window
        // captures THIS round's outcome rather than the lifetime counter.
        stats.applyRoundCommit(
            timeElapsedMs = activeViewTime + penalty,
            failAmount = roundFailAmountDelta(stats, snapshot),
            nowMs = nowMs,
        )
        applyFastTrackGate(vocab, stats, snapshot, penalty, activeViewTime)

        persistRoundLog(vocab, commitButton, activeViewTime, nowMs)

        if (!wasIntroduced && vocab.hasBeenIntroduced()) {
            // First time this word entered the rotation — keep the LLM's
            // "previously studied words" cache in sync without rescanning csvData.
            vocabDictionary.notifyVocabIntroduced(vocab)
        }
        recordDailyAchievements(vocab, wasFullyMastered)
        return announceSkillFinishedIfCrossed(stats, prevFailureProbability)
    }

    /**
     * Listening was off at some point during a LISTEN round — the user could
     * read the foreign word, so roll back any in-memory mutations from the
     * round and persist the rolled-back state. Still log the round
     * (compromised=true) so we keep "user saw this word at this time" —
     * downstream training should exclude these from outcome inference.
     */
    private fun persistCompromisedRound(
        vocab: Vocab,
        snapshot: SkillStats,
        commitButton: CommitButton,
        activeViewTime: Long,
        nowMs: Long,
    ) {
        vocab.stats(currentSkill).copyFrom(snapshot)
        val logEntry = buildLogEntry(
            vocab = vocab,
            commitButton = commitButton,
            compromised = true,
            foregroundViewTimeMs = activeViewTime,
            nowMs = nowMs,
        )
        vocab.savePreferences(reviewLog = logEntry)
    }

    /**
     * First-exposure fast-track: a brand-new pair the user clears trivially
     * (fast, clean, no help, no penalty) gets flagged so failureProbability
     * short-circuits to [SkillStats.FAST_TRACKED_FPB] (sub-threshold). The
     * counters stay honest — nViewed is still 1, the WordList view sees
     * the truth, the refresh-pool stability calc uses real history, and a
     * future event-sourcing replay can re-derive the flag from the log
     * under different rules. The flag is revoked the moment evidence
     * contradicts it: [SkillStats.recordFailure] clears it on any penalty,
     * and the slow-round check below clears it when later view-time samples
     * cross the slow-gate ceiling (preserving the slow gate's safety
     * property the formula-short-circuit would otherwise bypass).
     */
    private fun applyFastTrackGate(
        vocab: Vocab,
        stats: SkillStats,
        snapshot: SkillStats?,
        penalty: Long,
        activeViewTime: Long,
    ) {
        val fastTracking = penalty == 0L && FastTrack.isCandidate(
            priorViews = snapshot?.nTimesViewed ?: 0,
            tipUsed = roundFacts.tipUsed,
            wordMaskTaps = roundFacts.wordMaskTaps,
            sentenceMaskTaps = roundFacts.sentenceMaskTaps,
            progressiveReveals = roundFacts.progressiveReveals,
            elapsedMs = activeViewTime,
            predecessorFastTracked = predecessorFastTracked(vocab, currentSkill),
        )
        if (fastTracking) {
            stats.fastTracked = true
        } else if (stats.fastTracked) {
            val slowNow = maxOf(stats.viewTimeMilli, stats.viewTimeMilli_prev) >
                SkillStats.SLOW_VIEW_TIME_MAX_MS
            if (slowNow) stats.fastTracked = false
        }
    }

    private fun persistRoundLog(
        vocab: Vocab,
        commitButton: CommitButton,
        activeViewTime: Long,
        nowMs: Long,
    ) {
        val logEntry = buildLogEntry(
            vocab = vocab,
            commitButton = commitButton,
            compromised = false,
            foregroundViewTimeMs = activeViewTime,
            nowMs = nowMs,
        )
        vocab.savePreferences(reviewLog = logEntry)
    }

    /**
     * Trackers tied to a committed (non-compromised) round: the daily streak
     * (idempotent within a day — first round bumps, the rest no-op) and the
     * lifetime mastered-today highlight (fires only on the active → fully
     * mastered transition for [vocab], compared against the [wasFullyMastered]
     * pre-state captured before [applyRoundCommit] mutated the stats).
     */
    private fun recordDailyAchievements(vocab: Vocab, wasFullyMastered: Boolean) {
        val streak = StreakTracker.recordPracticeToday(activity.applicationContext)
        streakBadge.surfaceResult(streak)
        if (!wasFullyMastered && isFullyMasteredNow(vocab)) {
            MasteryTracker.recordMasteredToday(activity.applicationContext)
        }
    }

    /**
     * Mastery-crossing event: this round drove the current skill's fpb from
     * "still active" to "below threshold". Records the per-skill "-N today"
     * suffix that decorates the ACTIVE WORDS label, plays the success chime,
     * and returns true so [updateVocab] can hold off the next round's TTS
     * long enough not to drown the chime.
     */
    private fun announceSkillFinishedIfCrossed(
        stats: SkillStats,
        prevFailureProbability: Float,
    ): Boolean {
        val crossedNow = stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD &&
            prevFailureProbability >= Vocab.SKILL_FINISHED_THRESHOLD
        if (!crossedNow) return false
        SkillFinishedTracker.recordFinishedToday(activity.applicationContext, currentSkill)
        sounds.playSuccess()
        return true
    }

    /**
     * Freeze the in-progress [roundFacts] plus the round context into an
     * immutable [io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry]. The
     * [SentenceSource] is captured at this point so the log reflects what
     * the user was set up to see for this round.
     */
    private fun buildLogEntry(
        vocab: Vocab,
        commitButton: CommitButton,
        compromised: Boolean,
        foregroundViewTimeMs: Long,
        nowMs: Long,
    ) = roundFacts.build(
        vocabHash = vocab.hash,
        skill = currentSkill,
        commitButton = commitButton,
        sentenceSource = SentenceSource.fromContext(activity),
        listeningEnabled = listeningEnabled,
        compromised = compromised,
        foregroundViewTimeMs = foregroundViewTimeMs,
        nowMs = nowMs,
    )

    private fun isFullyMasteredNow(vocab: Vocab): Boolean {
        if (vocab.ignore) return true
        return Skill.ladder.all { skill ->
            val s = vocab.stats(skill)
            s.nTimesViewed > 0 && s.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
        }
    }

    /**
     * Refresh the streak badge from persistent state. Public so
     * MainActivity.onResume can poke it after returning from Settings (where
     * the user may have toggled or reset the streak indirectly via reminder
     * changes).
     */
    fun refreshStreakBadge() = streakBadge.refresh()

    fun stopThinkingAnimation() = sentenceManager.stopThinkingAnimation()

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
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        roundFacts.recordWordMaskTap()
        vocab.stats(currentSkill).recordFailure(RoundScoring.MASK_TAP_PENALTY)
        tts.speakForeignWord(vocab, flush = true)
    }

    /**
     * Replay the foreign word when the user taps the already-visible word —
     * e.g. in READ, or in LISTEN with listening disabled. No penalty: the
     * answer was on screen anyway, the tap is just a "say it again" convenience.
     */
    private fun onWordReplayed() {
        val vocab = currentVocab ?: return
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        roundFacts.recordWordReplay()
        tts.speakForeignWord(vocab, flush = true)
    }

    /** Replay the example sentence (with a small penalty) when its mask is tapped. */
    private fun onMaskedSentenceTapped() {
        val vocab = currentVocab ?: return
        val sentence = sentenceManager.currentSentence ?: return
        roundFacts.recordFirstActionIfNeeded(System.currentTimeMillis())
        roundFacts.recordSentenceMaskTap()
        vocab.stats(currentSkill).recordFailure(RoundScoring.MASK_TAP_PENALTY)
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
