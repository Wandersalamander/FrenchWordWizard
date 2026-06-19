package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [Vocab.state] — the single (word, skill) classifier added
 * in ADR 0001 step 1. Pins each [PairState] branch and asserts the carried
 * weights are exactly the values the legacy [Vocab.sortValue] /
 * [Vocab.failureProbabilityMasteredWords] returned, proving the consolidation
 * changed no math.
 */
class PairStateTest {

    private val DAY_MS = 86_400_000L
    private val NOW = 1_700_000_000_000L

    private fun newVocab(): Vocab = Vocab(
        foreign = "Haus",
        english = "house",
        importance = 1,
        hash = "h",
        exampleHard = "Das Haus ist groß.",
        exampleEasy = "Haus.",
        persistence = null,
    )

    /** Many clean fast views → fpb well below the mastery threshold. */
    private fun SkillStats.markMastered() {
        nTimesViewed = 100
        nTimesFailed = 0f
        viewTimeMilli = 1_000L
        viewTimeMilli_prev = 1_000L
    }

    @Test fun lockedWhenPredecessorNotMasteredAndUnviewed() {
        // LISTEN on a fresh word: never viewed, and READ (its predecessor) is
        // nowhere near mastered, so the unlock gate keeps it Locked.
        assertEquals(PairState.Locked, newVocab().state(Skill.LISTEN, NOW))
    }

    @Test fun newWhenUnlockedButUnviewed() {
        // READ is always unlocked; unviewed → New (eligible for introduction only).
        assertEquals(PairState.New, newVocab().state(Skill.READ, NOW))
    }

    @Test fun activeWhenViewedAndAboveThreshold() {
        val v = newVocab()
        v.stats(Skill.READ).apply { nTimesViewed = 2; nTimesFailed = 2f }
        val state = v.state(Skill.READ, NOW)
        assertTrue("expected Active, was $state", state is PairState.Active)
        // Weight is byte-identical to the legacy active-pool weight.
        assertEquals(v.sortValue(Skill.READ), (state as PairState.Active).weight, 1e-9)
        assertTrue("active weight must be positive", state.weight > 0.0)
    }

    @Test fun thresholdEdgeAtExactlyPointOneIsActiveNotMastered() {
        // fpb == 0.5 / 5 == 0.10, exactly SKILL_FINISHED_THRESHOLD. Mastery is
        // strictly-below, so the pair is still Active. Pins the < vs <= edge,
        // matching refreshInterval_doesNotDoubleOnMasteryEntryRound.
        val v = newVocab()
        v.stats(Skill.READ).apply {
            nTimesViewed = 5
            nTimesFailed = 0f
            viewTimeMilli = 1_500L
            viewTimeMilli_prev = 1_500L
        }
        assertEquals(0.1f, v.stats(Skill.READ).failureProbability(), 1e-6f)
        assertTrue(
            "at exactly the threshold the pair is still Active",
            v.state(Skill.READ, NOW) is PairState.Active,
        )
    }

    @Test fun masteredWhenBelowThresholdAndWithinInterval() {
        val v = newVocab()
        v.stats(Skill.READ).apply {
            markMastered()
            refreshIntervalDays = 1f
            lastDisplayed = NOW  // just shown → 0 days < 1-day interval
        }
        assertEquals(PairState.Mastered, v.state(Skill.READ, NOW))
    }

    @Test fun dueForRefreshWhenMasteredAndOverdue() {
        val v = newVocab()
        v.stats(Skill.READ).apply {
            markMastered()
            refreshIntervalDays = 1f
            lastDisplayed = NOW - 5 * DAY_MS  // well past the interval
        }
        val state = v.state(Skill.READ, NOW)
        assertTrue("expected DueForRefresh, was $state", state is PairState.DueForRefresh)
        // Overdue weight is byte-identical to the legacy refresh-pool weight.
        assertEquals(
            v.failureProbabilityMasteredWords(Skill.READ, NOW),
            (state as PairState.DueForRefresh).overdue,
            1e-9,
        )
        assertTrue("overdue weight must be positive", state.overdue > 0.0)
    }

    @Test fun activeAndRefreshPoolsAreDisjointAcrossStatGrid() {
        // Ported from the deleted PickerOverlapTest: the active draw pool
        // (Active/New) and the refresh pool (DueForRefresh) must never both
        // claim the same pair. With state() this is structural — a pair is
        // exactly one PairState branch — but we keep the brute-force grid as a
        // regression guard against a future mis-edit of the classifier.
        val now = System.currentTimeMillis()
        for (nViewed in intArrayOf(0, 1, 2, 5, 20, 100)) {
            var nFailedX10 = 0
            while (nFailedX10 <= nViewed * 10) {
                for (viewMs in longArrayOf(1_000L, 3_000L, 8_000L)) {
                    val v = newVocab()
                    v.stats(Skill.READ).apply {
                        nTimesViewed = nViewed
                        nTimesFailed = nFailedX10 / 10f
                        viewTimeMilli = viewMs
                        viewTimeMilli_prev = viewMs
                        // Last seen long ago so the refresh pool would gladly
                        // take this pair IF it were mastered (non-trivial test).
                        lastDisplayed = now - 60L * DAY_MS
                        lastTimeFailed = 0L
                    }
                    val st = v.state(Skill.READ, now)
                    val inActive = st.isActiveDraw
                    val inRefresh = st is PairState.DueForRefresh
                    assertFalse(
                        "pair landed in BOTH pools — nViewed=$nViewed " +
                            "nFailed=${v.stats(Skill.READ).nTimesFailed} viewMs=$viewMs state=$st",
                        inActive && inRefresh,
                    )
                }
                nFailedX10 += 3
            }
        }
    }

    @Test fun isFullyLearnedTrueOnlyWhenEverySkillIsLearned() {
        val v = newVocab()
        // Only READ mastered, the downstream skills untouched → not fully learned.
        v.stats(Skill.READ).apply { markMastered(); lastDisplayed = NOW }
        assertFalse("READ alone is not fully learned", v.isFullyLearned())
        // Master every ladder skill → fully learned (Mastered and DueForRefresh
        // both count as learned).
        for (skill in Skill.ladder) v.stats(skill).apply { markMastered(); lastDisplayed = NOW }
        assertTrue("all skills mastered → fully learned", v.isFullyLearned())
    }
}
