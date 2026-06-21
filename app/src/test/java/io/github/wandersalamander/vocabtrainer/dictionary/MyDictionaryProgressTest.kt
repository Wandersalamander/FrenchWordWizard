package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for the lifetime-mastery and active-set count APIs on
 * MyDictionary that drive the two main-screen progress bars, plus the
 * introduced-words cache used by the LLM prompt.
 */
class MyDictionaryProgressTest {

    /** Build a dictionary from in-memory CSV lines with no persistence. */
    private fun dictionaryOf(lines: List<String>): MyDictionary {
        val csv = lines.joinToString("\n")
        return MyDictionary(ByteArrayInputStream(csv.toByteArray()))
    }

    /**
     * Mark this (word, skill) pair as mastered — many clean views, zero
     * failures, plus snug view-time samples so SkillStats' sustained-slowness
     * gate doesn't floor failureProbability above SKILL_FINISHED_THRESHOLD.
     */
    private fun SkillStats.markMastered() {
        nTimesViewed = 100
        nTimesFailed = 0f
        viewTimeMilli = 1_000L
        viewTimeMilli_prev = 1_000L
    }

    /** Mark as struggling — high failure rate, default view times are fine. */
    private fun SkillStats.markStruggling() {
        nTimesViewed = 2
        nTimesFailed = 2f
    }

    private val dayMs = 86_400_000L

    /** Mastered, with an explicit refresh interval and last-shown time. */
    private fun SkillStats.markMasteredWithSchedule(intervalDays: Float, lastDisplayedMs: Long) {
        markMastered()
        refreshIntervalDays = intervalDays
        lastDisplayed = lastDisplayedMs
    }

    @Test fun lifetimeMastered_emptyDictionaryIsZero() {
        assertEquals(0, dictionaryOf(emptyList()).computeLifetimeMasteredCount())
    }

    @Test fun lifetimeMastered_untouchedWordIsNotMastered() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        assertEquals(0, dict.computeLifetimeMasteredCount())
    }

    @Test fun lifetimeMastered_requiresAllSkillsIntroducedAndBelowThreshold() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",  // READ mastered only
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",        // all three mastered
        ))
        // READ stats look done but the downstream skills are untouched — practice
        // is still ahead, so this word must not count as lifetime-mastered.
        dict.csvData[0].stats(Skill.READ).markMastered()
        for (skill in Skill.ladder) {
            dict.csvData[1].stats(skill).markMastered()
        }
        assertEquals(1, dict.computeLifetimeMasteredCount())
    }

    @Test fun lifetimeMastered_ignoredWordShortCircuitsToMastered() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).markStruggling()  // high fpb
        vocab.ignore = true
        assertEquals(1, dict.computeLifetimeMasteredCount())
    }

    @Test fun activeSet_emptyDictionaryIsZero() {
        assertEquals(ActiveSetCounts(0, 0, 0, 0), dictionaryOf(emptyList()).computeActiveSetCounts())
    }

    @Test fun activeSet_untouchedWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        assertEquals(ActiveSetCounts(0, 0, 0, 0), dict.computeActiveSetCounts())
    }

    @Test fun activeSet_perSkillPairsCountIndependently() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",   // active in READ only
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",         // READ mastered, LISTEN active
            "Buch\tbook\t1\thash3\tDas Buch ist neu.\tBuch.",      // READ + LISTEN mastered, INVERT active
            "Tisch\ttable\t1\thash4\tDer Tisch ist alt.\tTisch.",  // untouched
        ))
        dict.csvData[0].stats(Skill.READ).markStruggling()
        dict.csvData[1].stats(Skill.READ).markMastered()
        dict.csvData[1].stats(Skill.LISTEN).markStruggling()
        dict.csvData[2].stats(Skill.READ).markMastered()
        dict.csvData[2].stats(Skill.LISTEN).markMastered()
        dict.csvData[2].stats(Skill.INVERT).markStruggling()
        assertEquals(ActiveSetCounts(read = 1, listen = 1, invert = 1, speak = 0), dict.computeActiveSetCounts())
    }

    @Test fun activeSet_ignoredWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).markStruggling()
        vocab.ignore = true
        assertEquals(ActiveSetCounts(0, 0, 0, 0), dict.computeActiveSetCounts())
    }

    // ---------- computeDueMasteredCount: snooze-aware "N due" badge ----------

    @Test fun dueMastered_emptyDictionaryIsZero() {
        assertEquals(0, dictionaryOf(emptyList()).computeDueMasteredCount(now = 1_700_000_000_000L))
    }

    @Test fun dueMastered_countsFullyMasteredWordWhoseHardestSkillIsDue() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        // All three skills mastered and well overdue → INVERT (hardest) is due
        // and nothing harder can snooze it, so the word is surfaceable.
        for (skill in Skill.ladder) {
            v.stats(skill).markMasteredWithSchedule(intervalDays = 1f, lastDisplayedMs = now - 5 * dayMs)
        }
        assertEquals(1, dict.computeDueMasteredCount(now))
    }

    @Test fun dueMastered_excludesWordWhoseOnlyDueSkillIsSnoozedBehindHarder() {
        // The over-count the snooze introduced: READ is past its interval, but
        // the harder skills were refreshed moments ago. The picker would snooze
        // READ for a day, so the badge must not count this word as due.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).markMasteredWithSchedule(intervalDays = 1f, lastDisplayedMs = now - 5 * dayMs)
        v.stats(Skill.LISTEN).markMasteredWithSchedule(intervalDays = 8f, lastDisplayedMs = now)
        v.stats(Skill.INVERT).markMasteredWithSchedule(intervalDays = 8f, lastDisplayedMs = now)
        assertEquals(0, dict.computeDueMasteredCount(now))
    }

    @Test fun dueMastered_countsWordAgainOnceSnoozeExpires() {
        // Same shape, but the harder skills were refreshed 2 days ago — past the
        // 1-day snooze — so READ resurfaces and the word is due again.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).markMasteredWithSchedule(intervalDays = 1f, lastDisplayedMs = now - 5 * dayMs)
        v.stats(Skill.LISTEN).markMasteredWithSchedule(intervalDays = 8f, lastDisplayedMs = now - 2 * dayMs)
        v.stats(Skill.INVERT).markMasteredWithSchedule(intervalDays = 8f, lastDisplayedMs = now - 2 * dayMs)
        assertEquals(1, dict.computeDueMasteredCount(now))
    }

    @Test fun dueMastered_excludesPartiallyMasteredWord() {
        // READ mastered and due, but INVERT is still being drilled — the word
        // isn't fully mastered, so it never enters the lifetime "N due" count.
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val now = 1_700_000_000_000L
        val v = dict.csvData[0]
        v.stats(Skill.READ).markMasteredWithSchedule(intervalDays = 1f, lastDisplayedMs = now - 5 * dayMs)
        v.stats(Skill.LISTEN).markMasteredWithSchedule(intervalDays = 1f, lastDisplayedMs = now - 5 * dayMs)
        v.stats(Skill.INVERT).markStruggling()
        assertEquals(0, dict.computeDueMasteredCount(now))
    }

    // ---------- computeStudiedPairCounts: per-skill mastered/active split ----------

    @Test fun studiedPairs_emptyDictionaryIsZero() {
        assertEquals(StudiedPairCounts(0, 0), dictionaryOf(emptyList()).computeStudiedPairCounts())
    }

    @Test fun studiedPairs_untouchedWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        assertEquals(StudiedPairCounts(0, 0), dict.computeStudiedPairCounts())
    }

    @Test fun studiedPairs_ignoredWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).markStruggling()
        vocab.ignore = true
        assertEquals(StudiedPairCounts(0, 0), dict.computeStudiedPairCounts())
    }

    @Test fun studiedPairs_countsPairsPerSkillAndBucketsByMastery() {
        // Same fixture as activeSet_perSkillPairsCountIndependently: pairs are
        // counted per (word, skill) and summed across skills, then split by the
        // mastery threshold (mastered = fpb below threshold, active = at/above).
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",   // READ active
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",         // READ mastered, LISTEN active
            "Buch\tbook\t1\thash3\tDas Buch ist neu.\tBuch.",      // READ + LISTEN mastered, INVERT active
            "Tisch\ttable\t1\thash4\tDer Tisch ist alt.\tTisch.",  // untouched → ignored
        ))
        dict.csvData[0].stats(Skill.READ).markStruggling()
        dict.csvData[1].stats(Skill.READ).markMastered()
        dict.csvData[1].stats(Skill.LISTEN).markStruggling()
        dict.csvData[2].stats(Skill.READ).markMastered()
        dict.csvData[2].stats(Skill.LISTEN).markMastered()
        dict.csvData[2].stats(Skill.INVERT).markStruggling()
        // mastered = Auto.READ + Buch.READ + Buch.LISTEN = 3
        // active   = Haus.READ + Auto.LISTEN + Buch.INVERT = 3
        assertEquals(StudiedPairCounts(mastered = 3, active = 3), dict.computeStudiedPairCounts())
    }

    @Test fun studiedPairs_activeHalfEqualsActiveSetCountsSum() {
        // Documented invariant the mastered-refresh cadence relies on: the
        // active half of computeStudiedPairCounts is exactly the sum of the
        // per-skill active-set bar (same gating, only the bucketing differs).
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",
            "Buch\tbook\t1\thash3\tDas Buch ist neu.\tBuch.",
        ))
        dict.csvData[0].stats(Skill.READ).markStruggling()
        dict.csvData[1].stats(Skill.READ).markMastered()
        dict.csvData[1].stats(Skill.LISTEN).markStruggling()
        dict.csvData[2].stats(Skill.READ).markMastered()
        dict.csvData[2].stats(Skill.LISTEN).markMastered()
        dict.csvData[2].stats(Skill.INVERT).markStruggling()
        val active = dict.computeActiveSetCounts()
        val studied = dict.computeStudiedPairCounts()
        assertEquals(active.read + active.listen + active.invert + active.speak, studied.active)
    }

    @Test fun introducedForeignWords_startsEmpty() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        // Word has never been shown → not introduced.
        assertTrue(dict.getIntroducedForeignWords().isEmpty())
    }

    @Test fun introducedForeignWords_includesWordsWithViewsOnFirstSkill() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",
        ))
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 1
        val result = dict.getIntroducedForeignWords()
        assertEquals(listOf("Haus"), result)
    }

    @Test fun notifyVocabIntroduced_addsToCacheIdempotently() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",
        ))
        // Prime the cache (empty).
        dict.getIntroducedForeignWords()
        // Simulate the QuizController flow: mutate stats first, then notify.
        val vocab = dict.csvData[1]
        vocab.stats(Skill.ladder.first()).nTimesViewed = 1
        dict.notifyVocabIntroduced(vocab)
        dict.notifyVocabIntroduced(vocab)  // idempotent
        assertEquals(listOf("Auto"), dict.getIntroducedForeignWords())
    }

    @Test fun maxIntroducedImportance_isZeroWhenNothingIntroduced() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Wissenschaft\tscience\t10\thash2\tDie Wissenschaft schreitet voran.\tWissenschaft.",
        ))
        assertEquals(0, dict.getMaxIntroducedImportance())
    }

    @Test fun maxIntroducedImportance_tracksHighestIntroducedTier() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t3\thash2\tDas Auto fährt.\tAuto.",
            "Wissenschaft\tscience\t10\thash3\tDie Wissenschaft schreitet voran.\tWissenschaft.",
        ))
        // Introduce the tier-1 and tier-3 words but not the tier-10 word.
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 1
        dict.csvData[1].stats(Skill.ladder.first()).nTimesViewed = 1
        assertEquals(3, dict.getMaxIntroducedImportance())
    }

    @Test fun reloadPreferences_resetsIntroducedCache() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        // Prime, then mutate the underlying stat as if a reload pulled different
        // persisted values; reload should clear the stale cache.
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 1
        assertEquals(listOf("Haus"), dict.getIntroducedForeignWords())
        // Simulate a wipe.
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 0
        dict.reloadPreferences()
        assertTrue("cache should rebuild from current state", dict.getIntroducedForeignWords().isEmpty())
    }
}
