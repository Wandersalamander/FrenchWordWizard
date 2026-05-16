package com.example.myapplication.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for the consolidated progress-count and introduced-words cache
 * APIs on MyDictionary. These replaced a per-skill scan per progress-bar
 * refresh (called every round) and a full-csvData rescan on every LLM call.
 */
class MyDictionaryProgressTest {

    /** Build a dictionary from in-memory CSV lines with no SharedPreferences. */
    private fun dictionaryOf(lines: List<String>): MyDictionary {
        val csv = lines.joinToString("\n")
        return MyDictionary(ByteArrayInputStream(csv.toByteArray()))
    }

    @Test fun progress_emptyDictionaryHasZeroCounts() {
        val dict = dictionaryOf(emptyList())
        val counts = dict.computeAllSkillProgress()
        for (skill in Skill.ladder) {
            assertEquals("$skill introduced", 0, counts.getValue(skill).introduced)
            assertEquals("$skill finished", 0, counts.getValue(skill).finished)
        }
    }

    @Test fun progress_countsIntroducedAndFinishedPerSkill() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",
            "Buch\tbook\t1\thash3\tDas Buch ist neu.\tBuch.",
        ))
        // Word 0: introduced + finished on READ. Word 1: introduced only on READ. Word 2: untouched.
        dict.csvData[0].stats(Skill.READ).apply { nTimesViewed = 100; nTimesFailed = 0f }
        dict.csvData[1].stats(Skill.READ).apply { nTimesViewed = 2; nTimesFailed = 2f }
        // Word 2 stays untouched (nTimesViewed == 0).
        val counts = dict.computeAllSkillProgress()
        assertEquals(2, counts.getValue(Skill.READ).introduced)
        assertEquals(1, counts.getValue(Skill.READ).finished)
        // Other skills untouched.
        assertEquals(0, counts.getValue(Skill.LISTEN).introduced)
        assertEquals(0, counts.getValue(Skill.INVERT).introduced)
    }

    @Test fun progress_ignoredWordCountsAsFinishedOnceIntroduced() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).apply { nTimesViewed = 1; nTimesFailed = 1f }  // high fpb
        vocab.ignore = true
        val counts = dict.computeAllSkillProgress()
        // Introduced (viewed at least once) and finished (ignore short-circuit).
        assertEquals(1, counts.getValue(Skill.READ).introduced)
        assertEquals(1, counts.getValue(Skill.READ).finished)
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
