package io.github.wandersalamander.vocabtrainer.data.scoring

import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.TipUsed

/**
 * Single source of truth for the per-round penalty weights and the function
 * that derives a round's total failAmount from its [ReviewLogEntry].
 *
 * The same constants are referenced inline by QuizController's mid-round
 * recordFailure calls AND by [StatsReplay] when reconstructing stats from a
 * log replay. Centralising them is what lets a future tuning of, say, the
 * mask-tap penalty automatically affect both the live drilling flow AND any
 * historical re-derivation — no two-place edit, no silent drift.
 *
 * The log captures raw user actions (tip used, mask taps, commit button, …);
 * this object encodes the scoring POLICY that turns those facts into a number.
 * Change a constant here and the policy changes everywhere.
 */
object RoundScoring {

    /** Penalty for using the sentence tip ("show me an example"). */
    const val SENTENCE_TIP_PENALTY: Float = 0.25f

    /** Penalty per tap on a masked element (masked word or masked sentence) to replay TTS. */
    const val MASK_TAP_PENALTY: Float = 0.10f

    /** Penalty for an outright fail (Reveal click, or spot-check "Wrong"). */
    const val FAIL_PENALTY: Float = 1.0f

    /**
     * View-time padding the EMA absorbs on a fail round (the "fail flash" pause
     * before advancing). Added to the raw `view_time_ms` before the EMA update
     * so a fail round's effective timeElapsed matches the live commit path.
     */
    const val FAIL_PENALTY_MS: Long = 2_000L

    /**
     * Penalty for the [revealIndex]th (1-indexed) progressive-reveal tap on a
     * word with [letterCount] letters. Triangular weighting: the i-th of N
     * reveals costs 2·(N − i + 1) / (N·(N + 1)), so the first reveal is the
     * most expensive and the sum across all N reveals is exactly 1.0
     * (≈ one Fail click). Returns 0 if [letterCount] ≤ 0 (defensive — the
     * formula would divide by zero).
     */
    fun progressiveRevealPenalty(revealIndex: Int, letterCount: Int): Float {
        if (letterCount <= 0 || revealIndex < 1 || revealIndex > letterCount) return 0f
        return 2f * (letterCount - revealIndex + 1) / (letterCount * (letterCount + 1).toFloat())
    }

    /**
     * Total failAmount the round contributes to the (vocab, skill) pair's
     * stats. Sum of every penalty event encoded in [entry]:
     *
     *  - [CommitButton.FAIL] (raw Reveal click or spot-check Wrong) → [FAIL_PENALTY]
     *  - [TipUsed.SENTENCE] → [SENTENCE_TIP_PENALTY]
     *  - [TipUsed.PROGRESSIVE_REVEAL] → sum of [progressiveRevealPenalty]
     *    over the count in `entry.progressiveReveals`
     *  - each `wordMaskTaps` / `sentenceMaskTaps` → [MASK_TAP_PENALTY]
     *
     * [foreignLetterCount] is the letter count of the word's foreign text,
     * needed only to evaluate the progressive-reveal triangular formula.
     * Returns 0 for compromised entries (which the replay skips anyway, but
     * defensive).
     */
    fun failAmount(entry: ReviewLogEntry, foreignLetterCount: Int): Float {
        if (entry.compromised) return 0f
        var amount = 0f
        if (entry.commitButton == CommitButton.FAIL) amount += FAIL_PENALTY
        when (entry.tipUsed) {
            TipUsed.SENTENCE -> amount += SENTENCE_TIP_PENALTY
            TipUsed.PROGRESSIVE_REVEAL -> {
                for (i in 1..entry.progressiveReveals) {
                    amount += progressiveRevealPenalty(i, foreignLetterCount)
                }
            }
            TipUsed.NONE -> Unit
        }
        amount += entry.wordMaskTaps * MASK_TAP_PENALTY
        amount += entry.sentenceMaskTaps * MASK_TAP_PENALTY
        return amount
    }
}
