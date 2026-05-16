package com.example.myapplication.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the (word, skill) scheduling math: SkillStats.failureProbability,
 * Vocab.sortValue, and the post-mastery refresh score from
 * Vocab.failureProbabilityMasteredWords. The refresh formula in particular went
 * through several recent revisions ("Added last time failed" commits) — these
 * tests pin the boundary behavior so the next revision can be made safely.
 */
class SchedulingMathTest {

    private val DAY_MS = 86_400_000L

    private fun newVocab(): Vocab = Vocab(
        foreign = "Haus",
        english = "house",
        importance = 1,
        hash = "h",
        exampleHard = "Das Haus ist groß.",
        exampleEasy = "Haus.",
        sharedPreferences = null,
    )

    // ---------- SkillStats.failureProbability ----------

    @Test fun fpb_isOneWhenUnseen() {
        val s = SkillStats(nTimesViewed = 0, nTimesFailed = 0f)
        assertEquals(1.0f, s.failureProbability(), 1e-6f)
    }

    @Test fun fpb_seededWithHalfPenaltyOnFirstView() {
        // First clean view: base = 0.5/1 = 0.5, raw = 0.5 + 0.5 * 0/1 = 0.5
        val s = SkillStats(nTimesViewed = 1, nTimesFailed = 0f)
        assertEquals(0.5f, s.failureProbability(), 1e-6f)
    }

    @Test fun fpb_droppsWithCleanRepetition() {
        // After many clean views, fpb should converge toward 0.
        val s = SkillStats(nTimesViewed = 100, nTimesFailed = 0f)
        assertTrue("fpb should be small after many clean views, was ${s.failureProbability()}",
            s.failureProbability() < 0.01f)
    }

    @Test fun fpb_clampsAtOneEvenIfFailuresExceedViews() {
        // recordFailure(1.0f) called more times than views — shouldn't go > 1.0.
        val s = SkillStats(nTimesViewed = 2, nTimesFailed = 10f)
        assertEquals(1.0f, s.failureProbability(), 1e-6f)
    }

    @Test fun recordFailure_setsLastTimeFailed() {
        val s = SkillStats(nTimesViewed = 5, nTimesFailed = 0f, lastTimeFailed = 0L)
        val before = System.currentTimeMillis()
        s.recordFailure(1.0f)
        val after = System.currentTimeMillis()
        assertEquals(1.0f, s.nTimesFailed, 1e-6f)
        assertTrue("lastTimeFailed should be set to ~now", s.lastTimeFailed in before..after)
    }

    // ---------- Vocab.sortValue ----------

    @Test fun sortValue_isZeroForMasteredSkill() {
        val v = newVocab()
        // Drive fpb well below SKILL_FINISHED_THRESHOLD (0.1f).
        v.stats(Skill.READ).nTimesViewed = 100
        v.stats(Skill.READ).nTimesFailed = 0f
        assertEquals(0.0, v.sortValue(Skill.READ), 1e-9)
    }

    @Test fun sortValue_isPositiveForUnseenButActivePair() {
        val v = newVocab()
        // nTimesViewed == 0 takes the "seed at 2.0 hours" branch.
        v.stats(Skill.LISTEN).nTimesViewed = 0
        assertTrue("sortValue should be positive for an unseen active pair",
            v.sortValue(Skill.LISTEN) > 0.0)
    }

    @Test fun sortValue_hardMultiplierTriples() {
        val v = newVocab()
        v.stats(Skill.READ).nTimesViewed = 1
        v.stats(Skill.READ).nTimesFailed = 1f
        v.stats(Skill.READ).lastDisplayed = System.currentTimeMillis()
        val base = v.sortValue(Skill.READ)
        v.flaggedHard = true
        val hard = v.sortValue(Skill.READ)
        assertEquals(3.0 * base, hard, 1e-6)
    }

    // ---------- failureProbabilityMasteredWords ----------

    @Test fun masteredRefresh_isZeroForActivePair() {
        val v = newVocab()
        // fpb >= 0.1 → not mastered → refresh score is 0.
        v.stats(Skill.READ).nTimesViewed = 1
        v.stats(Skill.READ).nTimesFailed = 1f
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now = 0L), 1e-9)
    }

    @Test fun masteredRefresh_isZeroWithinStabilityWindow() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        // Mastered (many clean views).
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        val now = 1_700_000_000_000L
        stats.lastDisplayed = now  // just shown
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now), 1e-9)
    }

    @Test fun masteredRefresh_growsWhenOverdue() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        // Failed once long ago, then a long clean streak ending at lastDisplayed.
        val now = 1_700_000_000_000L
        stats.lastTimeFailed = now - 100L * DAY_MS
        stats.lastDisplayed = now - 90L * DAY_MS  // 10 days since the failure, then 90 days idle
        val score = v.failureProbabilityMasteredWords(Skill.READ, now)
        assertTrue("should be overdue (>0), was $score", score > 0.0)
    }

    @Test fun masteredRefresh_overdueScoreCapped() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        val now = 1_700_000_000_000L
        // Streak: 30 days; idle: years -> overdue ratio absurdly high.
        stats.lastTimeFailed = now - 365L * 10 * DAY_MS
        stats.lastDisplayed = now - 365L * 9 * DAY_MS
        val score = v.failureProbabilityMasteredWords(Skill.READ, now)
        // The implementation caps the overdue ratio; verify it doesn't blow up.
        assertTrue("score should be finite", score.isFinite())
        assertTrue("score should be bounded (<=3.0 per OVERDUE_CAP)", score <= 3.0 + 1e-9)
    }

    @Test fun masteredRefresh_legacyWithFailuresUsesFloor() {
        // Regression for the legacy bug: a mastered record with nTimesFailed > 0
        // but lastTimeFailed == 0L (pre-feature install) used to fall through to
        // the implicit-views branch, which could over-stabilize the word for up
        // to REFRESH_CAP_DAYS. The fix pins it at the 7-day floor so the word
        // cycles back into refresh on its normal cadence.
        val legacy = newVocab()
        val legacyStats = legacy.stats(Skill.READ)
        legacyStats.nTimesViewed = 100
        legacyStats.nTimesFailed = 0.5f  // historical failures but timestamp lost
        legacyStats.lastTimeFailed = 0L
        val now = 1_700_000_000_000L
        legacyStats.lastDisplayed = now - 30L * DAY_MS  // 30 days idle

        val cleanRecord = newVocab()
        val cleanStats = cleanRecord.stats(Skill.READ)
        cleanStats.nTimesViewed = 100
        cleanStats.nTimesFailed = 0f
        cleanStats.lastTimeFailed = 0L
        cleanStats.lastDisplayed = now - 30L * DAY_MS

        val legacyScore = legacy.failureProbabilityMasteredWords(Skill.READ, now)
        val cleanScore = cleanRecord.failureProbabilityMasteredWords(Skill.READ, now)

        assertTrue("legacy mastered with history should be considered overdue at 30d idle, was $legacyScore",
            legacyScore > 0.0)
        // The truly-clean record (nTimesFailed == 0) gets the implicit views
        // streak, which at nTimesViewed=100 saturates the cap — it should NOT
        // be flagged as overdue at the same 30-day idle window.
        assertEquals("clean record should be within stability", 0.0, cleanScore, 1e-9)
        assertNotEquals("legacy and clean should now diverge", legacyScore, cleanScore)
    }

    @Test fun masteredRefresh_cleanRecordUsesImplicitViewsStreak() {
        // No failures ever, no timestamp — implicit streak from nTimesViewed.
        // With high nTimesViewed the streak hits the cap and the word is stable
        // through a moderate idle window.
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 50
        stats.nTimesFailed = 0f
        stats.lastTimeFailed = 0L
        val now = 1_700_000_000_000L
        stats.lastDisplayed = now - 10L * DAY_MS  // small idle, well within implicit streak
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now), 1e-9)
    }
}
