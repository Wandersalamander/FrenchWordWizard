package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Tests for the calibration-frontier bias in new-word introduction
 * ([getInactiveVocab]): geometric decay toward the frontier tier
 * ([INTRODUCTION_TIER_DECAY]), the hard ceiling at the frontier, and the
 * exhaustion-based hand-off to the lowest un-introduced tier above it. The
 * frontier integer is never mutated — advancement is purely "the band ran out".
 */
class VocabSelectorIntroductionTest {

    private fun dictionaryOf(lines: List<String>): MyDictionary {
        val csv = lines.joinToString("\n")
        return MyDictionary(ByteArrayInputStream(csv.toByteArray()))
    }

    /** One un-introduced word per importance tier; hash "h<tier>". */
    private fun dictWithTiers(tiers: List<Int>): MyDictionary =
        dictionaryOf(tiers.map { t -> "word$t\tw$t\t$t\th$t\tHard $t.\tEasy $t." })

    /** Flip a word to "introduced" so it leaves the inactive pool. */
    private fun markIntroduced(vocab: Vocab) {
        vocab.stats(Skill.READ).nTimesViewed = 1
    }

    @Test fun nullFrontierAlwaysIntroducesLowestTierFirst() {
        // No calibration → today's behavior: the lowest un-introduced tier
        // leads, always via the first skill (new words enter through READ).
        val dict = dictWithTiers(listOf(0, 1, 2, 3))
        repeat(100) {
            val (vocab, skill) = dict.getInactiveVocab(frontier = null)
            assertEquals(Skill.ladder.first(), skill)
            assertEquals("lowest tier must lead with no frontier", 0, vocab.importance)
        }
    }

    @Test fun frontierNeverIntroducesAboveTheFrontierTier() {
        // F = 2: tiers 3,4,5 exist but must never surface while [0,2] still has
        // un-introduced words. This is the "don't show above frontier" rule.
        val dict = dictWithTiers(listOf(0, 1, 2, 3, 4, 5))
        repeat(2_000) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 2)
            assertTrue(
                "tier ${vocab.importance} leaked above frontier 2",
                vocab.importance <= 2,
            )
        }
    }

    @Test fun frontierPeaksAtFrontierTierAndDecaysDownward() {
        // One word per tier 0..3, F = 3. Decay 0.5 → expected proportions
        // ~0.53 / 0.27 / 0.13 / 0.07. Assert the strict monotonic ordering (gaps
        // are ~10+ sigma over 4k draws) and that no lower tier is starved.
        val dict = dictWithTiers(listOf(0, 1, 2, 3))
        val counts = IntArray(4)
        repeat(4_000) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 3)
            counts[vocab.importance]++
        }
        assertTrue("frontier tier should dominate: ${counts.toList()}", counts[3] > counts[2])
        assertTrue("tier2 > tier1: ${counts.toList()}", counts[2] > counts[1])
        assertTrue("tier1 > tier0: ${counts.toList()}", counts[1] > counts[0])
        assertTrue("no tier starved: ${counts.toList()}", counts[0] > 0)
        // Frontier tier carries a clear plurality (expected ~0.53 >> 0.33).
        assertTrue(
            "frontier tier should be the plurality: ${counts.toList()}",
            counts[3] > 4_000 / 3,
        )
    }

    @Test fun frontierAboveHighestTierPeaksAtHighestAvailable() {
        // Calibrated F = 10 but the CSV tops out at tier 2 (a language with
        // fewer tiers, or calibration overshoot). The whole pool is in band;
        // relative decay still makes the highest available tier dominate (the
        // weights stay in 4:2:1 ratio regardless of the absolute exponent).
        val dict = dictWithTiers(listOf(0, 1, 2))
        val counts = IntArray(3)
        repeat(4_000) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 10)
            counts[vocab.importance]++
        }
        assertTrue("highest available tier should dominate: ${counts.toList()}", counts[2] > counts[1])
        assertTrue("tier1 > tier0: ${counts.toList()}", counts[1] > counts[0])
        assertTrue("no tier starved: ${counts.toList()}", counts[0] > 0)
    }

    @Test fun frontierZeroRespectsCsvOrderWithinEssentials() {
        // F = 0 ("knows only essentials"): only tier 0 is eligible and tier-0
        // introduction is strict CSV order. Each introduced word hands off to
        // the next in the file sequence.
        val dict = dictionaryOf(listOf(
            "alpha\ta\t0\tha\tHard a.\tEasy a.",
            "bravo\tb\t0\thb\tHard b.\tEasy b.",
            "charlie\tc\t0\thc\tHard c.\tEasy c.",
        ))
        val order = mutableListOf<String>()
        repeat(3) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 0)
            order += vocab.hash
            markIntroduced(vocab)
        }
        assertEquals(listOf("ha", "hb", "hc"), order)
    }

    @Test fun exhaustedBandClimbsToNextTierAboveFrontier() {
        // F = 2 with tiers 0..4. Introduce every word in the band [0,2]; the
        // picker must fall through to the lowest un-introduced tier (3),
        // proving exhaustion-based advancement without mutating the frontier.
        val dict = dictWithTiers(listOf(0, 1, 2, 3, 4))
        dict.csvData.filter { it.importance <= 2 }.forEach { markIntroduced(it) }
        repeat(100) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 2)
            assertEquals("must climb to the lowest un-introduced tier", 3, vocab.importance)
        }
    }

    @Test fun partiallyExhaustedFrontierTierStillBiasesRemainingBand() {
        // The frontier tier (2) is fully introduced but lower tiers (0,1)
        // remain. The band is now {0,1}; decay should peak at the highest
        // still-available tier (1) and never resurface the exhausted tier 2.
        val dict = dictWithTiers(listOf(0, 1, 2))
        dict.csvData.filter { it.importance == 2 }.forEach { markIntroduced(it) }
        val counts = IntArray(2)
        repeat(2_000) {
            val (vocab, _) = dict.getInactiveVocab(frontier = 2)
            assertTrue("exhausted frontier tier must not resurface", vocab.importance < 2)
            counts[vocab.importance]++
        }
        assertTrue("highest remaining tier should dominate: ${counts.toList()}", counts[1] > counts[0])
        assertTrue("lowest tier not starved: ${counts.toList()}", counts[0] > 0)
    }
}
