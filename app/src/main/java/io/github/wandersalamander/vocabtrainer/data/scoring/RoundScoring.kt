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

    /**
     * SPEAK opt-in hint: penalty for tapping the foreign word to hear the model
     * pronunciation *before* recording an attempt. Charged once per round (it's a
     * tip flavor — [TipUsed.SPEAK_REVEAL]); replaying the model *after* a take to
     * compare is free. Sized like the sentence tip — a real but non-fatal hint.
     */
    const val SPEAK_REVEAL_PENALTY: Float = 0.25f

    /** Penalty per tap on a masked element (masked word or masked sentence) to replay TTS. */
    const val MASK_TAP_PENALTY: Float = 0.10f

    /** Penalty for an outright fail (Reveal click, or spot-check "Wrong"). */
    const val FAIL_PENALTY: Float = 1.0f

    /**
     * SPEAK only: penalty per *re-record* — each take after the first. The first
     * take is the task itself (free); re-recording signals the user wasn't happy
     * with their attempt, the same kind of "struggle tell" as a mask tap. Capped
     * at [SPEAK_MAX_PENALIZED_RERECORDS] re-records so a fussy-but-eventually-
     * "Good" round stays at/under one [FAIL_PENALTY] rather than ballooning.
     */
    const val SPEAK_RERECORD_PENALTY: Float = 0.25f

    /** Re-records beyond this many stop accruing penalty (cap = N × penalty). */
    const val SPEAK_MAX_PENALIZED_RERECORDS: Int = 4

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
     * Penalty for a round in which the user recorded [recordings] SPEAK takes.
     * The first take is free; each subsequent take costs [SPEAK_RERECORD_PENALTY],
     * capped at [SPEAK_MAX_PENALIZED_RERECORDS] re-records. Returns 0 for
     * [recordings] ≤ 1 (and for non-SPEAK rounds, which log 0).
     */
    fun speakReRecordPenalty(recordings: Int): Float {
        val penalized = (recordings - 1).coerceIn(0, SPEAK_MAX_PENALIZED_RERECORDS)
        return penalized * SPEAK_RERECORD_PENALTY
    }

    /**
     * Total failAmount the round contributes to the (vocab, skill) pair's
     * stats. Sum of every penalty event encoded in [entry]:
     *
     *  - [CommitButton.FAIL] (raw Reveal click or spot-check Wrong) → [FAIL_PENALTY]
     *  - [TipUsed.SENTENCE] → [SENTENCE_TIP_PENALTY]
     *  - [TipUsed.SPEAK_REVEAL] → [SPEAK_REVEAL_PENALTY] (SPEAK pre-attempt hint)
     *  - [TipUsed.PROGRESSIVE_REVEAL] → sum of [progressiveRevealPenalty]
     *    over the count in `entry.progressiveReveals`
     *  - each `wordMaskTaps` / `sentenceMaskTaps` → [MASK_TAP_PENALTY]
     *  - SPEAK re-records (takes after the first) → [speakReRecordPenalty]
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
            TipUsed.SPEAK_REVEAL -> amount += SPEAK_REVEAL_PENALTY
            TipUsed.PROGRESSIVE_REVEAL -> {
                for (i in 1..entry.progressiveReveals) {
                    amount += progressiveRevealPenalty(i, foreignLetterCount)
                }
            }
            TipUsed.NONE -> Unit
        }
        amount += entry.wordMaskTaps * MASK_TAP_PENALTY
        amount += entry.sentenceMaskTaps * MASK_TAP_PENALTY
        amount += speakReRecordPenalty(entry.speakRecordings)
        return amount
    }
}
