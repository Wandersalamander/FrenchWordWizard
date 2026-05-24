package io.github.wandersalamander.vocabtrainer.quiz

import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.dictionary.SkillStats

/**
 * First-exposure fast-track: a brand-new (vocab, skill) pair that the user
 * clears trivially on its first round (fast, clean, no tip, no mask taps) is
 * flagged as mastered via [SkillStats.fastTracked], so an intermediate user
 * with a large pre-existing vocabulary doesn't grind through hundreds of known
 * words one ladder step at a time.
 *
 * Zero UI — purely a speed-signal heuristic. Counters stay honest; the flag
 * only short-circuits [SkillStats.failureProbability]. Wrong-call risk (lucky
 * guess on a word the user didn't actually know) is caught downstream by:
 *  - [SkillStats.recordFailure], which clears the flag the moment any penalty
 *    is recorded (tip, mask tap, fail click),
 *  - QuizController's slow-round check, which clears the flag when later
 *    view-time samples cross [SkillStats.SLOW_VIEW_TIME_MAX_MS],
 *  - the refresh pool, which re-surfaces a "mastered" pair after a few weeks
 *    so a falsely fast-tracked word still gets a verification chance.
 *
 * All thresholds are tunable knobs; if fast-track proves too eager or too
 * timid in practice, edit the constants and re-run [FastTrackTest].
 */
internal object FastTrack {

    /**
     * Max prior nTimesViewed for the round to be a fast-track candidate. `0`
     * means only the very first exposure qualifies — once a pair has any prior
     * history, normal progression applies. Raise to allow more grace rounds
     * before the speed signal stops counting.
     */
    const val MAX_PRIOR_VIEWS: Int = 0

    /**
     * Max round elapsed time for fast-track eligibility. Must stay strictly
     * below [SkillStats.SLOW_VIEW_TIME_MAX_MS] so the slow gate would catch a
     * round that's borderline (i.e., "fast" here is genuinely fast, not just
     * "not slow").
     */
    const val MAX_VIEW_MS: Long = 3_000L

    init {
        require(MAX_VIEW_MS < SkillStats.SLOW_VIEW_TIME_MAX_MS) {
            "MAX_VIEW_MS ($MAX_VIEW_MS) must stay below SLOW_VIEW_TIME_MAX_MS " +
                "(${SkillStats.SLOW_VIEW_TIME_MAX_MS}) so fast-track candidacy is strictly fast."
        }
    }

    /**
     * Pure-function fast-track gate. Returns true when the just-committed
     * round was a fresh, fast, clean pass with no help — the signals that
     * together justify promoting the pair past the mastery threshold without
     * grinding further rounds.
     *
     * Caller (QuizController) is responsible for excluding compromised rounds
     * and rounds with a non-zero penalty before checking — those are scored
     * out of band.
     */
    fun isCandidate(
        priorViews: Int,
        tipUsed: TipUsed,
        wordMaskTaps: Int,
        sentenceMaskTaps: Int,
        progressiveReveals: Int,
        elapsedMs: Long,
    ): Boolean {
        if (priorViews > MAX_PRIOR_VIEWS) return false
        if (tipUsed != TipUsed.NONE) return false
        if (wordMaskTaps > 0 || sentenceMaskTaps > 0 || progressiveReveals > 0) return false
        return elapsedMs in 0..MAX_VIEW_MS
    }
}
