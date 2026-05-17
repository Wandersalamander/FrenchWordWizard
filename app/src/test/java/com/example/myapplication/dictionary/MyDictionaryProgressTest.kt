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

    @Test fun stageBuckets_emptyDictionaryHasZeroCounts() {
        val counts = dictionaryOf(emptyList()).computeStageBucketCounts()
        assertEquals(StageBucketCounts(0, 0, 0, 0, 0), counts)
    }

    @Test fun stageBuckets_untouchedWordIsUnknown() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val counts = dict.computeStageBucketCounts()
        assertEquals(StageBucketCounts(finished = 0, invertActive = 0, listenActive = 0,
            readActive = 0, unknown = 1), counts)
    }

    @Test fun stageBuckets_deepestIntroducedSkillSelectsBucket() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",          // READ only, struggling
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",                // READ + LISTEN, LISTEN struggling
            "Buch\tbook\t1\thash3\tDas Buch ist neu.\tBuch.",             // all three, INVERT struggling
            "Tisch\ttable\t1\thash4\tDer Tisch ist alt.\tTisch.",         // untouched
        ))
        dict.csvData[0].stats(Skill.READ).apply { nTimesViewed = 2; nTimesFailed = 2f }
        dict.csvData[1].stats(Skill.READ).apply { nTimesViewed = 100; nTimesFailed = 0f }
        dict.csvData[1].stats(Skill.LISTEN).apply { nTimesViewed = 2; nTimesFailed = 2f }
        dict.csvData[2].stats(Skill.READ).apply { nTimesViewed = 100; nTimesFailed = 0f }
        dict.csvData[2].stats(Skill.LISTEN).apply { nTimesViewed = 100; nTimesFailed = 0f }
        dict.csvData[2].stats(Skill.INVERT).apply { nTimesViewed = 2; nTimesFailed = 2f }
        val counts = dict.computeStageBucketCounts()
        assertEquals(StageBucketCounts(finished = 0, invertActive = 1, listenActive = 1,
            readActive = 1, unknown = 1), counts)
    }

    @Test fun stageBuckets_finishedRequiresAllSkillsIntroducedAndMastered() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",       // READ mastered, LISTEN/INVERT never started
            "Auto\tcar\t1\thash2\tDas Auto fährt.\tAuto.",             // all three mastered
        ))
        // Word 0: READ stats look "done" but downstream skills are untouched —
        // shouldn't count as fully finished, the user still has practice ahead.
        dict.csvData[0].stats(Skill.READ).apply { nTimesViewed = 100; nTimesFailed = 0f }
        for (skill in Skill.ladder) {
            dict.csvData[1].stats(skill).apply { nTimesViewed = 100; nTimesFailed = 0f }
        }
        val counts = dict.computeStageBucketCounts()
        assertEquals(StageBucketCounts(finished = 1, invertActive = 0, listenActive = 0,
            readActive = 1, unknown = 0), counts)
    }

    @Test fun stageBuckets_ignoredWordShortCircuitsToFinished() {
        val dict = dictionaryOf(listOf(
            "Haus\thouse\t1\thash1\tDas Haus ist groß.\tHaus.",
        ))
        val vocab = dict.csvData[0]
        vocab.stats(Skill.READ).apply { nTimesViewed = 1; nTimesFailed = 1f }  // high fpb
        vocab.ignore = true
        val counts = dict.computeStageBucketCounts()
        assertEquals(StageBucketCounts(finished = 1, invertActive = 0, listenActive = 0,
            readActive = 0, unknown = 0), counts)
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
