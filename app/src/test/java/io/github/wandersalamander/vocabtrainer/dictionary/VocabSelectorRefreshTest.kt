package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Selection-policy tests for [pickMasteredVocabToRefresh]: the two-tier
 * priority (shortest [SkillStats.refreshIntervalDays] first; within the band,
 * weighted by overdue × (nFailed + 1)).
 */
class VocabSelectorRefreshTest {

    private val dayMs = 86_400_000L

    private fun dictionaryOf(lines: List<String>): MyDictionary {
        val csv = lines.joinToString("\n")
        return MyDictionary(ByteArrayInputStream(csv.toByteArray()))
    }

    /**
     * Force a (vocab, skill) into the mastered/refresh pool with a given
     * interval and idle days.
     *
     * Defaults to [SkillStats.fastTracked] = true, which short-circuits fpb
     * below the mastery threshold so a test doesn't need to engineer a long
     * clean-view sequence (and so a high [nFailed] still reads as mastered).
     * Pass `fastTracked = false` for a pair "drilled" to mastery the long way:
     * fast view samples + many clean views drive the natural fpb below the cut
     * (0.5 / nViewed = 0.05 here) without the slow gate flooring it back up.
     */
    private fun SkillStats.makeRefreshEligible(
        intervalDays: Float,
        idleDays: Long,
        now: Long,
        nFailed: Float = 0f,
        fastTracked: Boolean = true,
    ) {
        nTimesViewed = 10
        nTimesFailed = nFailed
        viewTimeMilli = 1_000L
        viewTimeMilli_prev = 1_000L
        this.fastTracked = fastTracked
        refreshIntervalDays = intervalDays
        lastDisplayed = now - idleDays * dayMs
    }

    @Test fun returnsNullWhenNothingIsDue() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        // Mastered but just shown: well within its 1-day interval.
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 0, now = now,
        )
        assertNull(dict.pickMasteredVocabToRefresh(now = now))
    }

    @Test fun pickPrefersShortestIntervalBand() {
        // Two due pairs: one at 1-day interval, one at 16-day interval. Both
        // way overdue. The 16-day word must never surface while the 1-day is
        // also eligible — that's the fragility-first priority.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 30, now = now,
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 16f, idleDays = 100, now = now,
        )

        repeat(200) {
            val pick = dict.pickMasteredVocabToRefresh(now = now)
            assertNotNull(pick)
            assertEquals(
                "long-interval word leaked through fragility priority",
                "h1", pick!!.first.hash,
            )
        }
    }

    @Test fun pickWeightsByFailureCountWithinBand() {
        // Three pairs all at the same 1-day band, all equally overdue. The
        // high-failure word should dominate selections, but never-failed words
        // still get a non-zero share (nFailed + 1 floor).
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
            "Buch\tbook\t1\th3\tDas Buch ist gut.\tBuch.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 2, now = now, nFailed = 0f,
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 2, now = now, nFailed = 2f,
        )
        dict.csvData[2].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 2, now = now, nFailed = 10f,
        )

        val picks = mutableMapOf<String, Int>()
        repeat(3_000) {
            val (vocab, _) = dict.pickMasteredVocabToRefresh(now = now)!!
            picks[vocab.hash] = (picks[vocab.hash] ?: 0) + 1
        }
        val h1 = picks["h1"] ?: 0  // weight 1
        val h2 = picks["h2"] ?: 0  // weight 3
        val h3 = picks["h3"] ?: 0  // weight 11
        // Ordering must reflect the weights, well above sampling noise across 3k draws.
        assertTrue("hardest word should dominate: h1=$h1 h2=$h2 h3=$h3", h3 > h2)
        assertTrue("medium-fail should beat clean: h1=$h1 h2=$h2", h2 > h1)
        // Clean word still gets some share (no starvation).
        assertTrue("clean word should not be starved: h1=$h1", h1 > 0)
    }

    @Test fun longIntervalBandSurfacesWhenShortBandIsEmpty() {
        // Only a 32-day mastered pair is due — picker must pick it rather than
        // returning null just because no 1-day candidates exist.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 32f, idleDays = 60, now = now,
        )
        val pick = dict.pickMasteredVocabToRefresh(now = now)
        assertNotNull(pick)
        assertEquals("h1", pick!!.first.hash)
    }

    @Test fun nonOverdueShortIntervalDoesNotMaskOverdueLongInterval() {
        // A 1-day pair that's NOT actually due (score == 0) shouldn't be a
        // candidate at all — so the longer-interval overdue pair should win.
        // Pins the precondition: the interval-band priority only applies to
        // the eligible pool, not to all mastered pairs.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 0, now = now,  // not due
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 8f, idleDays = 30, now = now,  // overdue
        )
        val pick = dict.pickMasteredVocabToRefresh(now = now)
        assertNotNull(pick)
        assertEquals("h2", pick!!.first.hash)
    }

    @Test fun drilledPairIsPreferredOverFastTrackedInSameBand() {
        // Two due pairs in the same 1-day band: one drilled through the active
        // pool, one fast-tracked. The drilled pair must take every slot — a
        // fast-tracked pair (cleared on first sight, never really practised) is
        // the weakest evidence of learning and shouldn't consume a retention
        // slot while a drilled pair is due.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 5, now = now, fastTracked = false,
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 5, now = now, fastTracked = true,
        )

        repeat(200) {
            val pick = dict.pickMasteredVocabToRefresh(now = now)
            assertNotNull(pick)
            assertEquals(
                "fast-tracked pair leaked through while a drilled pair was due",
                "h1", pick!!.first.hash,
            )
        }
    }

    @Test fun drilledPairOutranksFastTrackedEvenAtLongerInterval() {
        // Drilled-pair priority precedes the interval tier. The drilled pair is
        // due at a long 16-day interval; the fast-tracked pair is due at the
        // fragile 1-day interval. The drilled pair must still win — the
        // drilled-vs-fast-tracked split is applied before the shortest-interval
        // band, not the other way round.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 16f, idleDays = 100, now = now, fastTracked = false,
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 5, now = now, fastTracked = true,
        )

        repeat(200) {
            val pick = dict.pickMasteredVocabToRefresh(now = now)
            assertNotNull(pick)
            assertEquals(
                "short-interval fast-tracked pair beat the drilled pair",
                "h1", pick!!.first.hash,
            )
        }
    }

    // ---------- Tier 0: one-per-word, hardest skill, sibling snooze ----------

    @Test fun surfacesHardestSkillWhenMultipleSkillsDueForSameWord() {
        // READ and INVERT both due for the same word. The refresh pool must
        // surface only the hardest (INVERT) — the easier skill is represented
        // by it, not shown alongside it.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)
        v.stats(Skill.INVERT).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)

        repeat(50) {
            val pick = dict.pickMasteredVocabToRefresh(now = now)
            assertNotNull(pick)
            assertEquals("hardest skill should represent the word", Skill.INVERT, pick!!.second)
        }
    }

    @Test fun easierSkillSnoozedWhileHarderRefreshedWithinWindow() {
        // INVERT was refreshed just now (not due — long interval). READ is due,
        // but it must be snoozed because a harder mastered sibling was shown
        // inside the snooze window — so the only due word yields nothing.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)
        v.stats(Skill.INVERT).makeRefreshEligible(intervalDays = 8f, idleDays = 0, now = now)

        assertNull(
            "easier skill must stay snoozed behind a freshly refreshed harder one",
            dict.pickMasteredVocabToRefresh(now = now),
        )
    }

    @Test fun easierSkillResurfacesAfterSnoozeExpires() {
        // Same setup, but the harder skill (INVERT) was refreshed 2 days ago —
        // past the 1-day snooze — so READ is free to surface again. INVERT
        // itself is still well within its 8-day interval, so it isn't due.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)
        v.stats(Skill.INVERT).makeRefreshEligible(intervalDays = 8f, idleDays = 2, now = now)

        val pick = dict.pickMasteredVocabToRefresh(now = now)
        assertNotNull(pick)
        assertEquals("easier skill should resurface once the snooze lapses", Skill.READ, pick!!.second)
    }

    @Test fun otherWordsStillSurfaceWhileOneWordIsSnoozed() {
        // Word A's READ is snoozed behind its freshly-refreshed INVERT, but a
        // second word B is independently due — the snooze is per-word and must
        // not suppress unrelated words.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        val a = dict.csvData[0]
        a.stats(Skill.READ).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)
        a.stats(Skill.INVERT).makeRefreshEligible(intervalDays = 8f, idleDays = 0, now = now)
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(intervalDays = 1f, idleDays = 5, now = now)

        repeat(50) {
            val pick = dict.pickMasteredVocabToRefresh(now = now)
            assertNotNull(pick)
            assertEquals("only the un-snoozed word should surface", "h2", pick!!.first.hash)
        }
    }

    @Test fun fastTrackedPairSurfacesWhenNoDrilledPairIsDue() {
        // Only a fast-tracked pair is due (the drilled pair is still well within
        // its interval). The picker must fall back to the fast-tracked pair
        // rather than returning null — "only pick fast-tracked if there are no
        // other words to choose from".
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\th1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\th2\tDas Auto fährt.\tAuto.",
        ))
        val now = 1_700_000_000_000L
        dict.csvData[0].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 8f, idleDays = 0, now = now, fastTracked = false,  // not due
        )
        dict.csvData[1].stats(Skill.READ).makeRefreshEligible(
            intervalDays = 1f, idleDays = 5, now = now, fastTracked = true,  // due
        )
        val pick = dict.pickMasteredVocabToRefresh(now = now)
        assertNotNull(pick)
        assertEquals("h2", pick!!.first.hash)
    }
}
