package io.github.wandersalamander.vocabtrainer.data.scoring

import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.dictionary.SkillStats
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab

/**
 * Reconstructs a [SkillStats] from a chronological log of [ReviewLogEntry]s
 * for one (vocab, skill) pair. Pure — no I/O, no Android, no clocks.
 *
 * The point: makes the event-sourcing claim actually true. Today
 * `vocab_stats` is maintained incrementally by `saveCurrentVocab`; the log is
 * persisted in lockstep but never read back as the source. With this in
 * place we can:
 *
 *  - re-derive history under new scoring rules (change a constant in
 *    [RoundScoring], rebuild, the past now reflects the new policy);
 *  - recover from cache drift (a bug that updates `vocab_stats` without
 *    writing to the log, or vice versa — rebuild gives us back the truth);
 *  - feed a clean stats projection into future training pipelines without
 *    trusting the live cache's accumulated history.
 *
 * The applied operations mirror `QuizController.saveCurrentVocab` step-for-
 * step, sharing the same scoring constants ([RoundScoring]), EMA smoothing
 * ([SkillStats.VIEW_TIME_EMA_ALPHA]), fast-track gate ([FastTrack.isCandidate])
 * and slow-revocation rule. Drift between incremental and replay is therefore
 * a one-place edit error rather than two.
 *
 * Compromised entries are skipped — same semantics as the live rollback path
 * (the round happened, the user saw the word, but the stats deliberately
 * don't reflect it).
 */
object StatsReplay {

    /**
     * Replay [entries] (any order — sorted internally by timestamp) into a
     * fresh [SkillStats]. [foreignLetterCount] is the letter count of the
     * vocab's foreign-language form; needed only to evaluate the progressive-
     * reveal triangular penalty.
     */
    fun rebuild(entries: List<ReviewLogEntry>, foreignLetterCount: Int): SkillStats {
        val stats = SkillStats()
        for (entry in entries.sortedBy { it.timestampMs }) {
            applyEntry(stats, entry, foreignLetterCount)
        }
        return stats
    }

    /**
     * Apply one log entry to [stats] in place. Visible-for-testing; production
     * callers should use [rebuild] (which also handles sorting and the empty
     * case). Mirrors the non-compromised branch of `saveCurrentVocab`.
     */
    internal fun applyEntry(stats: SkillStats, entry: ReviewLogEntry, foreignLetterCount: Int) {
        if (entry.compromised) return

        // Capture priorViews BEFORE we increment — the fast-track gate keys
        // off it (matches saveCurrentVocab using the round-start snapshot).
        val priorViews = stats.nTimesViewed
        // Round-start mastery, captured before the mid-round failure / commit
        // mutate the stats — mirrors QuizController's round-start snapshot.
        // Drives the re-verified-mastery branch below.
        val wasMasteredBefore = stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
        val failAmount = RoundScoring.failAmount(entry, foreignLetterCount)
        val penaltyMs = if (entry.commitButton == CommitButton.FAIL) RoundScoring.FAIL_PENALTY_MS else 0L

        // Mid-round recordFailure events in the live path bump nFailed /
        // lastTimeFailed / clear fastTracked. We collapse them into one
        // application here since the log only carries the final per-round
        // failAmount (derived above).
        if (failAmount > 0f) {
            stats.nTimesFailed += failAmount
            stats.lastTimeFailed = entry.timestampMs
            stats.fastTracked = false
        }

        // Entry timestamp drives both lastDisplayed and the rolling-window
        // entry so the rebuilt history mirrors what happened, not the wall
        // clock at the moment of replay.
        stats.applyRoundCommit(
            timeElapsedMs = entry.viewTimeMs + penaltyMs,
            failAmount = failAmount,
            nowMs = entry.timestampMs,
        )

        // Fast-track gate: same predicate as the live path, keyed on the
        // pre-increment priorViews. Setting wins over revocation in the same
        // round (it can't be both a fresh-trivial pass AND retroactively
        // slow), so the if/else mirrors saveCurrentVocab exactly.
        //
        // NB: [FastTrack]'s build-up policy requires the predecessor skill's
        // fastTracked state at this entry's timestamp. Single-skill replay
        // can't see that — [rebuild] processes one skill in isolation — so
        // we pass the parameter's vacuous default (`true`). This is correct
        // for the first ladder skill and a TODO for the rest: a future
        // cross-skill replay walker should thread the actual predecessor
        // state per timestamp, restoring full live↔replay equivalence.
        // Re-verified mastery: an already-mastered pair re-shown and passed on
        // its spot check stays mastered regardless of recall time. Mirrors
        // QuizController.applyFastTrackGate's verifiedAlreadyMastered branch
        // exactly, keyed on the log's spot-check result + round-start mastery.
        val verifiedAlreadyMastered = penaltyMs == 0L &&
            entry.spotCheckResult == SpotCheckResult.PASSED &&
            wasMasteredBefore
        if (verifiedAlreadyMastered) {
            stats.fastTracked = true
            return
        }

        val fastTracking = penaltyMs == 0L && FastTrack.isCandidate(
            priorViews = priorViews,
            tipUsed = entry.tipUsed,
            wordMaskTaps = entry.wordMaskTaps,
            sentenceMaskTaps = entry.sentenceMaskTaps,
            progressiveReveals = entry.progressiveReveals,
            elapsedMs = entry.viewTimeMs,
        )
        if (fastTracking) {
            // First-exposure mastery: push the first refresh gap out so the
            // word isn't resurfaced tomorrow. Only on the flag's flip to true,
            // never clobbering a longer interval a prior clean refresh earned.
            // Mirrors QuizController.applyFastTrackGate's fastTracking branch —
            // including the per-(hash, skill) jitter, which must match the live
            // path exactly (keyed on identity, not a clock, for that reason).
            if (!stats.fastTracked) {
                stats.refreshIntervalDays = stats.refreshIntervalDays.coerceAtLeast(
                    SkillStats.fastTrackInitialRefreshIntervalDays(entry.vocabHash, entry.skill),
                )
            }
            stats.fastTracked = true
        } else if (stats.fastTracked) {
            // Mirror the live path: use the round's actual elapsed time, not
            // the EMA. The EMA is anchored to the DEFAULT_VIEW_TIME_MS sentinel
            // for the first 2-3 rounds of a pair's history, which would falsely
            // revoke fast-track on the very first refresh round even when the
            // user actually answered quickly. See QuizController.applyFastTrackGate.
            if (entry.viewTimeMs > SkillStats.SLOW_VIEW_TIME_MAX_MS) {
                stats.fastTracked = false
            }
        }
    }
}
