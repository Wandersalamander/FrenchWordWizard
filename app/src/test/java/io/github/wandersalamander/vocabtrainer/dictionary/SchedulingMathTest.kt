package io.github.wandersalamander.vocabtrainer.dictionary

import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import io.github.wandersalamander.vocabtrainer.data.scoring.FastTrack
import org.junit.Assert.assertEquals
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
        persistence = null,
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
        // Fast view times so the sustained-slowness floor doesn't kick in.
        val s = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 100,
            nTimesFailed = 0f,
        )
        assertTrue("fpb should be small after many clean views, was ${s.failureProbability()}",
            s.failureProbability() < 0.01f)
    }

    @Test fun fpb_isFlooredWhenSlowAndWouldBeMastered() {
        // Many clean views (raw fpb ~0) but the last two rounds were slow:
        // mastery gate must NOT trip.
        val s = SkillStats(
            viewTimeMilli = 7_000L,
            viewTimeMilli_prev = 6_000L,
            nTimesViewed = 100,
            nTimesFailed = 0f,
        )
        assertEquals(SkillStats.SLOW_FPB_FLOOR, s.failureProbability(), 1e-6f)
    }

    @Test fun fpb_floorIsInactiveWhenAlreadyAboveThreshold() {
        // Slow AND struggling: raw fpb is already well above the floor, so the
        // gate must not lower it.
        val s = SkillStats(
            viewTimeMilli = 7_000L,
            viewTimeMilli_prev = 6_000L,
            nTimesViewed = 5,
            nTimesFailed = 3f,
        )
        assertTrue("raw fpb should dominate, was ${s.failureProbability()}",
            s.failureProbability() > SkillStats.SLOW_FPB_FLOOR)
    }

    @Test fun fpb_floorIsInactiveWhenFastEnough() {
        // Max just at the threshold: gate should NOT trip (strictly greater).
        val s = SkillStats(
            viewTimeMilli = 5_000L,
            viewTimeMilli_prev = 5_000L,
            nTimesViewed = 100,
            nTimesFailed = 0f,
        )
        assertTrue("raw fpb should pass through when not slow, was ${s.failureProbability()}",
            s.failureProbability() < 0.01f)
    }

    @Test fun fpb_clampsAtOneEvenIfFailuresExceedViews() {
        // recordFailure(1.0f) called more times than views — shouldn't go > 1.0.
        val s = SkillStats(nTimesViewed = 2, nTimesFailed = 10f)
        assertEquals(1.0f, s.failureProbability(), 1e-6f)
    }

    // ---------- Blended window/counter fpb ----------

    /** Build a fail/pass window from a list of failAmounts in chronological order (oldest first). */
    private fun window(vararg failAmounts: Float): List<VocabInteraction> =
        failAmounts.mapIndexed { i, f -> VocabInteraction(timestampMs = i.toLong(), failAmount = f) }

    @Test fun fpb_emptyWindowMatchesCounterOnlyFormula() {
        // With no rolling-window history, the new blended formula must
        // collapse exactly to the legacy counter-only result so existing
        // migrated rows behave identically until they accumulate fresh data.
        val fast = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 10,
            nTimesFailed = 3f,
            recentInteractions = emptyList(),
        )
        val base = 0.5f / 10f
        val expected = base + (1f - base) * (3f / 10f)
        assertEquals(expected, fast.failureProbability(), 1e-6f)
    }

    @Test fun fpb_recentFailuresPullUpVsRecentPasses() {
        // Same counters, opposite window orderings: a pair finishing on
        // failures must score higher than the same counters finishing on
        // passes. This is the core "5 right then 2 wrong vs 2 wrong then 5
        // right" distinction the rolling window was added to express.
        val counters = mapOf("nTimesViewed" to 10, "nTimesFailed" to 2f)
        val recentlyFailing = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = counters["nTimesViewed"] as Int,
            nTimesFailed = counters["nTimesFailed"] as Float,
            // 5 passes then 2 fails (newest = fails)
            recentInteractions = window(0f, 0f, 0f, 0f, 0f, 1f, 1f),
        )
        val recentlyImproving = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = counters["nTimesViewed"] as Int,
            nTimesFailed = counters["nTimesFailed"] as Float,
            // 2 fails then 5 passes (newest = passes)
            recentInteractions = window(1f, 1f, 0f, 0f, 0f, 0f, 0f),
        )
        assertTrue(
            "recently-failing fpb (${recentlyFailing.failureProbability()}) " +
                "should exceed recently-improving fpb (${recentlyImproving.failureProbability()})",
            recentlyFailing.failureProbability() > recentlyImproving.failureProbability(),
        )
    }

    @Test fun fpb_singleEntryHasSmallWindowWeight() {
        // One window entry → window blend weight ≈ 1/(1+3) = 0.25, so a
        // single fresh fail can't override 100 views of clean history.
        val stats = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 100,
            nTimesFailed = 0f,
            recentInteractions = window(1f),
        )
        // counterRate = 0, windowRate = 1, blend = 0.25 → effective = 0.25.
        // raw = base + (1-base) * 0.25, base = 0.005, so raw ≈ 0.254.
        val fpb = stats.failureProbability()
        assertTrue("single fail should bump but not dominate, was $fpb", fpb in 0.20f..0.30f)
    }

    @Test fun fpb_slowFloorStillAppliesWithWindow() {
        // Even with a clean window the slow-view gate should keep a slow
        // pair out of mastery — the window must not bypass the floor.
        val stats = SkillStats(
            viewTimeMilli = 7_000L,
            viewTimeMilli_prev = 6_000L,
            nTimesViewed = 100,
            nTimesFailed = 0f,
            recentInteractions = window(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        )
        assertEquals(SkillStats.SLOW_FPB_FLOOR, stats.failureProbability(), 1e-6f)
    }

    // ---------- Time-spaced clean-streak counter discount ----------

    @Test fun fpb_spacedCleanStreakPushesBadHistoryThroughThreshold() {
        // A pair failed heavily in the past (10/20) but answered cleanly for
        // the last 10 rounds, with the clean run spread across 8 calendar days.
        // The time-spaced discount fades the stale counter out far enough to
        // drop below the mastery threshold — while a no-streak twin (identical
        // counters/window, timestamps unset) must NOT cross, proving the
        // elapsed streak is what moved it rather than the window alone.
        val cleanWindow = window(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val anchor = 1_000_000_000_000L
        val spaced = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 20,
            nTimesFailed = 10f,
            lastTimeFailed = anchor,
            lastDisplayed = anchor + 8L * DAY_MS,
            recentInteractions = cleanWindow,
        )
        val noStreak = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 20,
            nTimesFailed = 10f,
            recentInteractions = cleanWindow,
        )
        assertTrue(
            "spaced clean streak should master the pair, was ${spaced.failureProbability()}",
            spaced.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD,
        )
        assertTrue(
            "no-streak twin should not be mastered yet, was ${noStreak.failureProbability()}",
            noStreak.failureProbability() >= Vocab.SKILL_FINISHED_THRESHOLD,
        )
        assertTrue(
            "the streak must lower fpb relative to no streak",
            spaced.failureProbability() < noStreak.failureProbability(),
        )
    }

    @Test fun fpb_crammedCleanStreakDoesNotMaster() {
        // Identical counters and a fully clean 10-round window, but the clean
        // run happened inside one short session (≈0 calendar days). Massed
        // practice must not discount the bad history: fpb stays above threshold
        // and strictly above the same reps spaced across days.
        val cleanWindow = window(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val anchor = 1_000_000_000_000L
        val crammed = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 20,
            nTimesFailed = 10f,
            lastTimeFailed = anchor,
            lastDisplayed = anchor + 60_000L, // one minute later — same session
            recentInteractions = cleanWindow,
        )
        val spaced = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 20,
            nTimesFailed = 10f,
            lastTimeFailed = anchor,
            lastDisplayed = anchor + 8L * DAY_MS,
            recentInteractions = cleanWindow,
        )
        assertTrue(
            "crammed clean streak should not master, was ${crammed.failureProbability()}",
            crammed.failureProbability() >= Vocab.SKILL_FINISHED_THRESHOLD,
        )
        assertTrue(
            "spacing the same reps must lower fpb below cramming",
            spaced.failureProbability() < crammed.failureProbability(),
        )
    }

    @Test fun fpb_singleRepAcrossIdleGapDoesNotMaster() {
        // Failed heavily, then answered correctly exactly once and left the
        // word idle for 20 days. The long elapsed gap alone must not collapse
        // the counter — the rep gate needs several clean reps — so the pair
        // stays well above the mastery threshold.
        val s = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 30,
            nTimesFailed = 18f,
            lastTimeFailed = 1_000_000_000_000L,
            lastDisplayed = 1_000_000_000_000L + 20L * DAY_MS,
            recentInteractions = window(0f), // exactly one clean rep
        )
        val fpb = s.failureProbability()
        assertTrue("single rep across a long gap must not master, was $fpb",
            fpb > Vocab.SKILL_FINISHED_THRESHOLD)
        assertTrue("counter should still dominate (fpb high), was $fpb", fpb > 0.3f)
    }

    @Test fun fpb_noStreakIdenticalToOldBlend() {
        // With fail/display timestamps unset (cleanStreakDays == 0) the discount
        // is inert and the blend must equal the pre-discount formula exactly.
        // Pins the "no regression for rows without a measurable streak" promise.
        val s = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 10,
            nTimesFailed = 2f,
            recentInteractions = window(0f, 0f, 0f, 1f, 0f), // n=5, one fail 2nd-from-newest
        )
        // Old blend, recomputed by hand:
        //   windowWeight = 5/(5+3) = 0.625
        //   windowRate   = 0.8 / 3.3616 = 0.237983  (decay-weighted)
        //   effective    = 0.625*0.237983 + 0.375*0.2 = 0.223739
        //   base = 0.05 → raw = 0.05 + 0.95*0.223739 = 0.262552
        assertEquals(0.262552f, s.failureProbability(), 1e-4f)
    }

    @Test fun recordInteraction_appendsAndTrimsToMax() {
        val s = SkillStats(nTimesViewed = 0, nTimesFailed = 0f)
        repeat(SkillStats.MAX_INTERACTIONS + 5) { i ->
            s.recordInteraction(failAmount = if (i % 2 == 0) 0f else 1f)
        }
        assertEquals(SkillStats.MAX_INTERACTIONS, s.recentInteractions.size)
        // Trimmed window keeps the tail (newest entries), so the last one
        // matches the last failAmount we pushed.
        val lastIdx = SkillStats.MAX_INTERACTIONS + 5 - 1
        val expectedLastFail = if (lastIdx % 2 == 0) 0f else 1f
        assertEquals(expectedLastFail, s.recentInteractions.last().failAmount, 1e-6f)
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
        // Fast view times so the sustained-slowness floor doesn't keep the
        // pair in the active pool.
        v.stats(Skill.READ).nTimesViewed = 100
        v.stats(Skill.READ).nTimesFailed = 0f
        v.stats(Skill.READ).viewTimeMilli = 1_500L
        v.stats(Skill.READ).viewTimeMilli_prev = 1_500L
        assertEquals(0.0, v.sortValue(Skill.READ), 1e-9)
    }

    @Test fun sortValue_isNonZeroForSlowButOtherwiseMasteredSkill() {
        // Raw fpb is mastery-level but the user has been slow on the last two
        // rounds — the pair should remain in the active picker.
        val v = newVocab()
        val s = v.stats(Skill.READ)
        s.nTimesViewed = 100
        s.nTimesFailed = 0f
        s.viewTimeMilli = 7_000L
        s.viewTimeMilli_prev = 6_000L
        s.lastDisplayed = System.currentTimeMillis()
        assertTrue("slow mastered-candidate should stay in active pool, was ${v.sortValue(Skill.READ)}",
            v.sortValue(Skill.READ) > 0.0)
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

    // ---------- Vocab.closingBoost ----------

    @Test fun closingBoost_isMaxAtThreshold() {
        // A pair sitting right at the mastery threshold (and fast) gets the
        // full boost so it gets surfaced and closes out.
        assertEquals(
            Vocab.CLOSING_BOOST_MAX,
            Vocab.closingBoost(Vocab.SKILL_FINISHED_THRESHOLD),
            1e-9,
        )
    }

    @Test fun closingBoost_isOneAtSlowFloor() {
        // Band top IS the slow floor: a pair pinned there by the slow gate was
        // answered too slowly, so it must NOT be boosted — it stays in normal
        // rotation until the user's recall for it speeds up.
        assertEquals(1.0, Vocab.closingBoost(SkillStats.SLOW_FPB_FLOOR), 1e-9)
    }

    @Test fun closingBoost_isOneWellAboveBand() {
        // A genuinely-hard pair (high fpb) gets no closing help.
        assertEquals(1.0, Vocab.closingBoost(0.30f), 1e-9)
    }

    @Test fun closingBoost_rampsDownTowardBandTop() {
        val nearThreshold = Vocab.closingBoost(0.105f)
        val midBand = Vocab.closingBoost(0.125f)
        val nearTop = Vocab.closingBoost(0.145f)
        assertTrue("boost should stay above 1.0 inside the band, was $nearTop", nearTop > 1.0)
        assertTrue(
            "boost should decrease toward the band top " +
                "($nearThreshold → $midBand → $nearTop)",
            nearThreshold > midBand && midBand > nearTop,
        )
        assertTrue("peak must not exceed the configured max", nearThreshold <= Vocab.CLOSING_BOOST_MAX)
    }

    @Test fun sortValue_closingBoostFlipsOrderingForFastNearMastered() {
        // Two fast pairs, identical except failure count. nearDone sits in the
        // closing band (fpb just above the mastery threshold); onTrack is
        // further out (fpb above the slow floor, so no boost). Raw fpb would
        // order onTrack ABOVE nearDone (more failures → bigger weight). The
        // closing boost must flip that so the about-to-finish pair actually
        // gets surfaced — this is the near-mastered-starvation fix.
        val now = System.currentTimeMillis()
        val nearDone = newVocab().apply {
            stats(Skill.READ).apply {
                nTimesViewed = 50; nTimesFailed = 5f
                viewTimeMilli = 1_500L; viewTimeMilli_prev = 1_500L
                lastDisplayed = now
            }
        }
        val onTrack = newVocab().apply {
            stats(Skill.READ).apply {
                nTimesViewed = 50; nTimesFailed = 9f
                viewTimeMilli = 1_500L; viewTimeMilli_prev = 1_500L
                lastDisplayed = now
            }
        }
        val fpbNear = nearDone.stats(Skill.READ).failureProbability()
        val fpbOn = onTrack.stats(Skill.READ).failureProbability()
        // Preconditions: nearDone is in the boost band, onTrack is above it,
        // and raw fpb orders them the "wrong" way for closing out.
        assertTrue("nearDone should be in the closing band, was $fpbNear",
            fpbNear >= Vocab.SKILL_FINISHED_THRESHOLD && fpbNear < SkillStats.SLOW_FPB_FLOOR)
        assertTrue("onTrack should be at/above the slow floor, was $fpbOn",
            fpbOn >= SkillStats.SLOW_FPB_FLOOR)
        assertTrue("raw fpb should order onTrack above nearDone", fpbOn > fpbNear)
        // The actual fix: weighted-picker ordering is flipped by the boost.
        assertTrue(
            "closing boost should make nearDone (${nearDone.sortValue(Skill.READ)}) " +
                "outrank onTrack (${onTrack.sortValue(Skill.READ)})",
            nearDone.sortValue(Skill.READ) > onTrack.sortValue(Skill.READ),
        )
    }

    // ---------- FastTrack calibration ----------

    @Test fun fastTrackedFlagShortCircuitsFpbBelowMasteryThreshold() {
        // FAST_TRACKED_FPB must stay strictly below SKILL_FINISHED_THRESHOLD so
        // a flagged pair retires from the active pool. Pinned here so a future
        // tweak to either constant that breaks the calibration trips a test
        // rather than silently breaking fast-track.
        val stats = SkillStats(
            // Honest counters — a single (fresh) view, not the old "bump to 6" hack.
            // The flag does the work; nTimesViewed/nFailed stay truthful.
            nTimesViewed = 1,
            nTimesFailed = 0f,
            // Slow view times deliberately — the flag must override the slow gate
            // until QuizController revokes it on a real slow round (covered below).
            viewTimeMilli = 10_000L,
            viewTimeMilli_prev = 10_000L,
            fastTracked = true,
        )
        assertEquals(SkillStats.FAST_TRACKED_FPB, stats.failureProbability(), 1e-6f)
        assertTrue(
            "FAST_TRACKED_FPB=${SkillStats.FAST_TRACKED_FPB} must stay below " +
                "SKILL_FINISHED_THRESHOLD=${Vocab.SKILL_FINISHED_THRESHOLD}",
            SkillStats.FAST_TRACKED_FPB < Vocab.SKILL_FINISHED_THRESHOLD,
        )
    }

    @Test fun fastTrackedFlagClearedByRecordFailureWithPenalty() {
        val stats = SkillStats(
            nTimesViewed = 1,
            nTimesFailed = 0f,
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            fastTracked = true,
        )
        // Any penalty (tip, mask tap, full fail) contradicts the trust.
        stats.recordFailure(0.25f)
        assertEquals(false, stats.fastTracked)
        // And the formula computes normally again — proves the short-circuit is gone.
        assertTrue(stats.failureProbability() > SkillStats.FAST_TRACKED_FPB)
    }

    @Test fun fastTrackMaxViewMsStaysBelowSlowGate() {
        // The "fast" threshold for fast-track candidacy must stay strictly
        // below the slow-gate ceiling so a candidate round is genuinely fast,
        // not just "not slow". FastTrack's require() catches this at runtime,
        // but the test catches it earlier.
        assertTrue(
            "MAX_VIEW_MS=${FastTrack.MAX_VIEW_MS} must be < " +
                "SLOW_VIEW_TIME_MAX_MS=${SkillStats.SLOW_VIEW_TIME_MAX_MS}",
            FastTrack.MAX_VIEW_MS < SkillStats.SLOW_VIEW_TIME_MAX_MS,
        )
    }

    // ---------- failureProbabilityMasteredWords ----------

    @Test fun masteredRefresh_isZeroForActivePair() {
        val v = newVocab()
        // fpb >= 0.1 → not mastered → refresh score is 0.
        v.stats(Skill.READ).nTimesViewed = 1
        v.stats(Skill.READ).nTimesFailed = 1f
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now = 0L), 1e-9)
    }

    @Test fun masteredRefresh_isZeroWithinInterval() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        stats.viewTimeMilli = 1_500L
        stats.viewTimeMilli_prev = 1_500L
        val now = 1_700_000_000_000L
        stats.lastDisplayed = now  // just shown — 0 days < default 1-day interval
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now), 1e-9)
    }

    @Test fun masteredRefresh_growsWhenOverdue() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        stats.viewTimeMilli = 1_500L
        stats.viewTimeMilli_prev = 1_500L
        val now = 1_700_000_000_000L
        // 90 days idle on a default 1-day interval → very overdue, score caps.
        stats.lastDisplayed = now - 90L * DAY_MS
        val score = v.failureProbabilityMasteredWords(Skill.READ, now)
        assertTrue("should be overdue (>0), was $score", score > 0.0)
    }

    @Test fun masteredRefresh_overdueScoreCapped() {
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        stats.viewTimeMilli = 1_500L
        stats.viewTimeMilli_prev = 1_500L
        val now = 1_700_000_000_000L
        // Years idle — overdue ratio would be huge without the cap.
        stats.lastDisplayed = now - 365L * 9 * DAY_MS
        val score = v.failureProbabilityMasteredWords(Skill.READ, now)
        assertTrue("score should be finite", score.isFinite())
        assertTrue("score should be bounded (<=3.0 per OVERDUE_CAP)", score <= 3.0 + 1e-9)
    }

    @Test fun masteredRefresh_eligibilityGatedByRefreshIntervalDays() {
        // Within the current interval → 0; past it → > 0. nTimesViewed and
        // nTimesFailed no longer affect the gate, only the per-pair schedule.
        val v = newVocab()
        val stats = v.stats(Skill.READ)
        stats.nTimesViewed = 100
        stats.nTimesFailed = 0f
        stats.viewTimeMilli = 1_500L
        stats.viewTimeMilli_prev = 1_500L
        stats.refreshIntervalDays = 4f
        val now = 1_700_000_000_000L

        // 3 days idle, schedule says 4 → not yet due.
        stats.lastDisplayed = now - 3L * DAY_MS
        assertEquals(0.0, v.failureProbabilityMasteredWords(Skill.READ, now), 1e-9)

        // 5 days idle, schedule says 4 → overdue ratio 5/4.
        stats.lastDisplayed = now - 5L * DAY_MS
        assertEquals(5.0 / 4.0, v.failureProbabilityMasteredWords(Skill.READ, now), 1e-9)
    }

    // ---------- Post-mastery refresh schedule (SkillStats.applyRoundCommit) ----------

    /**
     * Build a SkillStats that's already considered mastered, by setting
     * fastTracked=true so [SkillStats.failureProbability] short-circuits to
     * [SkillStats.FAST_TRACKED_FPB] (< Vocab.SKILL_FINISHED_THRESHOLD). Avoids
     * the need to engineer a real ~10-clean-view sequence in setup.
     */
    private fun masteredStats(refreshIntervalDays: Float = 1f): SkillStats = SkillStats(
        viewTimeMilli = 1_500L,
        viewTimeMilli_prev = 1_500L,
        nTimesViewed = 5,
        nTimesFailed = 0f,
        fastTracked = true,
        refreshIntervalDays = refreshIntervalDays,
    )

    @Test fun refreshInterval_doublesOnCleanRoundAfterMastery() {
        // 2 → ×2 = 4, then jittered by the per-round factor (seeded on nowMs so
        // it matches what StatsReplay reconstructs from the log).
        val s = masteredStats(refreshIntervalDays = 2f)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = 1_000L)
        assertEquals(4f * SkillStats.refreshJitterFactor(1_000L), s.refreshIntervalDays, 1e-4f)
    }

    @Test fun refreshInterval_lateCleanRecallDoublesActualElapsedNotSchedule() {
        // Scheduled at 2 days but the user didn't return for 7 and still recalled
        // it cleanly: the next gap anchors on the demonstrated ~7-day retention
        // (×2 = 14), NOT the stale 2-day schedule (which would give 4).
        val s = masteredStats(refreshIntervalDays = 2f)
        val now = 1_700_000_000_000L
        s.lastDisplayed = now - 7L * DAY_MS
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = now)
        assertEquals(14f * SkillStats.refreshJitterFactor(now), s.refreshIntervalDays, 1e-3f)
    }

    @Test fun refreshInterval_earlyCleanRecallStillDoublesSchedule() {
        // If a mastered pair is refreshed before its interval elapses, the
        // elapsed-time anchor must not shrink the schedule below the old
        // doubling: maxOf(schedule, elapsed) keeps it at schedule × 2.
        val s = masteredStats(refreshIntervalDays = 8f)
        val now = 1_700_000_000_000L
        s.lastDisplayed = now - 1L * DAY_MS  // only 1 day idle, schedule says 8
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = now)
        assertEquals(16f * SkillStats.refreshJitterFactor(now), s.refreshIntervalDays, 1e-3f)
    }

    @Test fun refreshInterval_lateCleanRecallStillCapsAtMaximum() {
        // A very late recall (×2 would overshoot) is still bounded by the cap.
        val s = masteredStats(refreshIntervalDays = 4f)
        val now = 1_700_000_000_000L
        s.lastDisplayed = now - 200L * DAY_MS  // 200 days late → ×2 = 400, capped
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = now)
        assertEquals(SkillStats.MAX_REFRESH_INTERVAL_DAYS, s.refreshIntervalDays, 1e-6f)
    }

    @Test fun refreshInterval_lateHintDoesNotGetElapsedBonus() {
        // A late round that needed a hint is only partial retention evidence:
        // it holds the schedule steady (no elapsed bonus, same as an on-time hint).
        val s = masteredStats(refreshIntervalDays = 4f)
        val now = 1_700_000_000_000L
        s.lastDisplayed = now - 30L * DAY_MS
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0.5f, nowMs = now)
        assertEquals(4f, s.refreshIntervalDays, 1e-6f)
    }

    @Test fun refreshInterval_holdsSteadyOnHint() {
        val s = masteredStats(refreshIntervalDays = 4f)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0.5f, nowMs = 1_000L)
        assertEquals("hint must not advance the schedule", 4f, s.refreshIntervalDays, 1e-6f)
    }

    @Test fun refreshInterval_resetsToOneOnFullFail() {
        val s = masteredStats(refreshIntervalDays = 16f)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 1f, nowMs = 1_000L)
        assertEquals(1f, s.refreshIntervalDays, 1e-6f)
    }

    @Test fun refreshInterval_doesNotDoubleOnMasteryEntryRound() {
        // Pre-round: nViewed=5, nFailed=0, fast → fpb = 0.5/5 = 0.1, which is
        // exactly at the threshold (not strictly below) so wasMasteredBefore
        // is false. The clean round drops post-round fpb under 0.1, crossing
        // into mastery — the first gap is seeded around the 1-day base (NOT
        // doubled to 2), then jittered, so the user sees the first refresh
        // roughly a day later rather than two.
        val s = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 5,
            nTimesFailed = 0f,
        )
        assertEquals("setup precondition: pre-round not yet mastered",
            0.1f, s.failureProbability(), 1e-6f)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = 1_000L)
        assertTrue("setup precondition: post-round now mastered",
            s.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD)
        assertEquals(
            "entry round seeds the jittered 1-day base, not a doubled 2",
            SkillStats.BASE_REFRESH_INTERVAL_DAYS * SkillStats.refreshJitterFactor(1_000L),
            s.refreshIntervalDays,
            1e-4f,
        )
        assertTrue(
            "entry-round gap must stay within ±25% of the 1-day base",
            s.refreshIntervalDays in 0.75f..1.25f,
        )
    }

    @Test fun refreshInterval_capsAtMaximum() {
        val s = masteredStats(refreshIntervalDays = SkillStats.MAX_REFRESH_INTERVAL_DAYS)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = 1_000L)
        assertEquals(
            SkillStats.MAX_REFRESH_INTERVAL_DAYS,
            s.refreshIntervalDays,
            1e-6f,
        )
    }

    @Test fun refreshInterval_doesNotDoubleOnCleanActiveRound() {
        // Clean round on an un-mastered pair — the field is stored on every
        // SkillStats but must not start growing until the pair is actually in
        // the refresh pool, otherwise heavily-drilled words would saturate the
        // schedule before their first refresh ever fires.
        val s = SkillStats(
            viewTimeMilli = 1_500L,
            viewTimeMilli_prev = 1_500L,
            nTimesViewed = 2,
            nTimesFailed = 1f,  // fpb = 0.5/2 + (1 - 0.25) * 0.5 ≈ 0.625
        )
        assertTrue("setup precondition: pre-round far from mastery",
            s.failureProbability() > Vocab.SKILL_FINISHED_THRESHOLD)
        s.applyRoundCommit(timeElapsedMs = 1_500L, failAmount = 0f, nowMs = 1_000L)
        assertEquals(1f, s.refreshIntervalDays, 1e-6f)
    }
}
