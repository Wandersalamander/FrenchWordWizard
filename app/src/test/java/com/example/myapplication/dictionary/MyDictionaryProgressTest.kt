package com.example.myapplication.dictionary

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

    /** Build a dictionary from in-memory CSV lines with no SharedPreferences. */
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
        assertEquals(ActiveSetCounts(0, 0, 0), dictionaryOf(emptyList()).computeActiveSetCounts())
    }

    @Test fun activeSet_untouchedWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        assertEquals(ActiveSetCounts(0, 0, 0), dict.computeActiveSetCounts())
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
        assertEquals(ActiveSetCounts(read = 1, listen = 1, invert = 1), dict.computeActiveSetCounts())
    }

    @Test fun activeSet_ignoredWordContributesNothing() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).markStruggling()
        vocab.ignore = true
        assertEquals(ActiveSetCounts(0, 0, 0), dict.computeActiveSetCounts())
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

    @Test fun reloadPreferences_resetsIntroducedCache() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        // Prime, then mutate the underlying stat as if a reload pulled different
        // SharedPreferences values; reload should clear the stale cache.
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 1
        assertEquals(listOf("Haus"), dict.getIntroducedForeignWords())
        // Simulate a wipe.
        dict.csvData[0].stats(Skill.ladder.first()).nTimesViewed = 0
        dict.reloadPreferences()
        assertTrue("cache should rebuild from current state", dict.getIntroducedForeignWords().isEmpty())
    }
}
