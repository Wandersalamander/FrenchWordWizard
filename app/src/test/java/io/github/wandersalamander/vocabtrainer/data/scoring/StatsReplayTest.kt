package io.github.wandersalamander.vocabtrainer.data.scoring

import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.SkillStats
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StatsReplay correctness: same operations the live commit path applies, just
 * driven by a log instead of a running quiz. These tests pin the contract
 * `incremental and replay produce identical stats` *by construction* (both
 * paths share [RoundScoring], the EMA constant, and the fast-track gate).
 */
class StatsReplayTest {

    private fun entry(
        t: Long,
        viewMs: Long = 1_500L,
        commit: CommitButton = CommitButton.NEXT,
        tip: TipUsed = TipUsed.NONE,
        reveals: Int = 0,
        wordMask: Int = 0,
        sentenceMask: Int = 0,
        spot: SpotCheckResult = SpotCheckResult.NONE,
        compromised: Boolean = false,
    ) = ReviewLogEntry(
        vocabHash = "h", skill = Skill.READ,
        timestampMs = t, viewTimeMs = viewMs,
        timeToFirstActionMs = viewMs, pausedMs = 0L,
        commitButton = commit, tipUsed = tip,
        progressiveReveals = reveals,
        wordMaskTaps = wordMask, sentenceMaskTaps = sentenceMask,
        wordReplays = 0,
        spotCheckResult = spot,
        sentenceSource = SentenceSource.EASY,
        listeningEnabled = true,
        compromised = compromised,
    )

    @Test fun emptyLogReturnsFreshStats() {
        val stats = StatsReplay.rebuild(emptyList(), foreignLetterCount = 5)
        assertEquals(0, stats.nTimesViewed)
        assertEquals(0f, stats.nTimesFailed, 1e-6f)
        assertEquals(0L, stats.lastDisplayed)
        assertEquals(0L, stats.lastTimeFailed)
        assertFalse(stats.fastTracked)
        assertTrue("fresh stats should report fpb = 1.0", stats.failureProbability() > 0.99f)
    }

    @Test fun singleCleanFastFirstRoundFastTracksTheSkill() {
        val stats = StatsReplay.rebuild(
            listOf(entry(t = 1_000L, viewMs = 1_500L)),
            foreignLetterCount = 5,
        )
        assertEquals(1, stats.nTimesViewed)
        assertEquals(0f, stats.nTimesFailed, 1e-6f)
        assertEquals(1_000L, stats.lastDisplayed)
        assertEquals(0L, stats.lastTimeFailed)
        assertTrue("trivial first round must fast-track", stats.fastTracked)
        assertTrue(
            "fast-tracked pair must report fpb below mastery threshold",
            stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD,
        )
    }

    @Test fun firstRoundFastTrackBumpsRefreshInterval() {
        // Drift guard: QuizController.applyFastTrackGate pushes the first refresh
        // gap out to the jittered fast-track interval when the flag flips on.
        // Replay must compute the SAME per-(hash, skill) gap, else rebuilding a
        // fast-tracked word from the log (backup restore / cache rebuild) lands
        // on a different schedule than the live path chose. entry() uses
        // vocabHash="h", skill=READ, so we expect exactly that pair's gap.
        val stats = StatsReplay.rebuild(
            listOf(entry(t = 1_000L, viewMs = 1_500L)),
            foreignLetterCount = 5,
        )
        assertTrue("trivial first round must fast-track", stats.fastTracked)
        assertEquals(
            "fast-track flip must bump the refresh interval, mirroring the live path",
            SkillStats.fastTrackInitialRefreshIntervalDays("h", Skill.READ),
            stats.refreshIntervalDays,
            1e-6f,
        )
        assertTrue(
            "jittered gap must stay within the configured window",
            stats.refreshIntervalDays >= SkillStats.FAST_TRACK_REFRESH_MIN_DAYS &&
                stats.refreshIntervalDays < SkillStats.FAST_TRACK_REFRESH_MAX_DAYS,
        )
    }

    @Test fun failuresAccumulateAndStampLastFailed() {
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L),                                        // clean
                entry(t = 2_000L, commit = CommitButton.FAIL),            // fail
                entry(t = 3_000L),                                        // clean
            ),
            foreignLetterCount = 5,
        )
        assertEquals(3, stats.nTimesViewed)
        assertEquals(RoundScoring.FAIL_PENALTY, stats.nTimesFailed, 1e-6f)
        assertEquals(3_000L, stats.lastDisplayed)
        assertEquals("lastTimeFailed should mark the fail round, not the latest clean one",
            2_000L, stats.lastTimeFailed)
    }

    @Test fun compromisedEntriesAreSkippedEntirely() {
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L),
                entry(t = 2_000L, commit = CommitButton.FAIL, compromised = true),
                entry(t = 3_000L),
            ),
            foreignLetterCount = 5,
        )
        // Compromised row contributes neither a view nor a failure.
        assertEquals(2, stats.nTimesViewed)
        assertEquals(0f, stats.nTimesFailed, 1e-6f)
        assertEquals(0L, stats.lastTimeFailed)
    }

    @Test fun rebuildIsOrderIndependent() {
        val sorted = listOf(entry(t = 1_000L), entry(t = 2_000L, commit = CommitButton.FAIL))
        val shuffled = sorted.reversed()
        val a = StatsReplay.rebuild(sorted, foreignLetterCount = 5)
        val b = StatsReplay.rebuild(shuffled, foreignLetterCount = 5)
        // Sortable round-by-round so the equality holds: same counters, same
        // timestamps. (SkillStats is a data class so structural equality works.)
        assertEquals(a.nTimesViewed, b.nTimesViewed)
        assertEquals(a.nTimesFailed, b.nTimesFailed, 1e-6f)
        assertEquals(a.lastDisplayed, b.lastDisplayed)
        assertEquals(a.lastTimeFailed, b.lastTimeFailed)
        assertEquals(a.fastTracked, b.fastTracked)
    }

    @Test fun fastTrackedFlagRevokedByLaterPenaltyRound() {
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L),                              // fast-track set
                entry(t = 2_000L, tip = TipUsed.SENTENCE),      // tip → penalty → revoke
            ),
            foreignLetterCount = 5,
        )
        assertFalse("any penalty in a later round must clear the fast-track flag", stats.fastTracked)
        // And the formula computes naturally — no longer short-circuiting.
        assertNotEquals(SkillStats.FAST_TRACKED_FPB, stats.failureProbability())
    }

    @Test fun fastTrackedFlagRevokedByLaterSlowRound() {
        // A later slow round (this round's actual elapsedMs > SLOW_VIEW_TIME_MAX_MS)
        // must revoke fast-track trust so the slow gate can reactivate the pair.
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L, viewMs = 1_500L),              // fast-track set
                entry(t = 2_000L, viewMs = 8_000L),              // slow → revoke
            ),
            foreignLetterCount = 5,
        )
        assertFalse("slow later rounds must revoke fast-track trust", stats.fastTracked)
    }

    @Test fun fastTrackedFlagSurvivesCleanFastRefresh() {
        // Regression for the "refresh kicked mastered words to active drilling"
        // bug: a fast-tracked pair has nViewed=1, so viewTimeMilli_prev is still
        // pinned to DEFAULT_VIEW_TIME_MS (10s) from the sentinel. The prior
        // EMA-based revocation check fired on every refresh round regardless
        // of actual user speed; the fix uses the round's real elapsedMs.
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L, viewMs = 1_500L),              // fast-track set
                entry(t = 2_000L, viewMs = 2_000L),              // fast refresh → keep
            ),
            foreignLetterCount = 5,
        )
        assertTrue(
            "a clean fast refresh must preserve fast-track",
            stats.fastTracked,
        )
        assertTrue(
            "fast-tracked pair must remain below mastery threshold",
            stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD,
        )
    }

    @Test fun passedSpotCheckKeepsMasteryDespiteSlowRecall() {
        // Regression for "mastered words that get checked still slip back into
        // the active pool": a mastered pair re-shown from the refresh pool is
        // always spot-checked. Recall after a multi-day gap is legitimately
        // slow (viewMs > SLOW_VIEW_TIME_MAX_MS), which WOULD trip the slow gate
        // and revoke mastery — but a PASSED spot check is direct verification,
        // so the pair must stay mastered. Contrast fastTrackedFlagRevokedBy-
        // LaterSlowRound, where the identical slow round has no passed check.
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L, viewMs = 1_500L),                       // mastered
                entry(t = 2_000L, viewMs = 8_000L, spot = SpotCheckResult.PASSED), // slow but verified
            ),
            foreignLetterCount = 5,
        )
        assertTrue(
            "a passed spot check on an already-mastered pair must keep it mastered",
            stats.fastTracked,
        )
        assertTrue(
            stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD,
        )
    }

    @Test fun passedSpotCheckDoesNotMasterANotYetMasteredPair() {
        // Guard the round-start-mastery key: a passed spot check on a pair that
        // was NOT mastered at round start (here a slow first exposure, so no
        // fast-track and fpb still high) must NOT promote it. The re-verified-
        // mastery branch only protects pairs that were already mastered.
        val stats = StatsReplay.rebuild(
            listOf(entry(t = 1_000L, viewMs = 8_000L, spot = SpotCheckResult.PASSED)),
            foreignLetterCount = 5,
        )
        assertFalse(
            "a passed check can't master a pair that wasn't mastered at round start",
            stats.fastTracked,
        )
        assertTrue(
            stats.failureProbability() >= Vocab.SKILL_FINISHED_THRESHOLD,
        )
    }

    @Test fun failedSpotCheckStillRevokesMastery() {
        // The complement: a mastered pair whose spot check FAILS (FAIL commit →
        // penalty) loses mastery as usual — the re-verified branch is gated on
        // a passed check, so the penalty path's revocation still applies.
        val stats = StatsReplay.rebuild(
            listOf(
                entry(t = 1_000L, viewMs = 1_500L),                       // mastered
                entry(
                    t = 2_000L, viewMs = 8_000L,
                    commit = CommitButton.FAIL, spot = SpotCheckResult.FAILED,
                ),
            ),
            foreignLetterCount = 5,
        )
        assertFalse("a failed spot check must revoke mastery", stats.fastTracked)
    }

    @Test fun recentInteractionsWindowReflectsTheLog() {
        // The rolling 10-entry window is bookkeeping inside SkillStats — replay
        // calling recordInteraction(failAmount) per round must produce the same
        // window the live path would.
        val entries = (1..12).map { i ->
            entry(t = (i * 1_000L), commit = if (i % 4 == 0) CommitButton.FAIL else CommitButton.NEXT)
        }
        val stats = StatsReplay.rebuild(entries, foreignLetterCount = 5)
        assertEquals(
            "window must trim to MAX_INTERACTIONS",
            SkillStats.MAX_INTERACTIONS, stats.recentInteractions.size,
        )
        // Last entry's outcome ends the window.
        val last = stats.recentInteractions.last()
        assertEquals(entries.last().timestampMs, last.timestampMs)
    }
}
