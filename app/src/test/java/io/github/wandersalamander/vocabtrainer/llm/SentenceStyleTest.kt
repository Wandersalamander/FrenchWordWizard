package io.github.wandersalamander.vocabtrainer.llm

import io.github.wandersalamander.vocabtrainer.dictionary.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Behavioural tests for the LLM sentence-style picker. We don't probe
 * private feature lists directly — instead we sample [SentenceStyle] over
 * many seeds and inspect the resulting prompt fragments, which is what the
 * LLM actually sees.
 */
class SentenceStyleTest {

    /** Collect prompt fragments across many seeds so we sample the full pool. */
    private fun sampleFragments(
        style: SentenceStyle,
        language: Language = Language.ITALIAN,
        n: Int = 400,
    ): Set<String> = (0 until n).mapTo(mutableSetOf()) { style.promptFragment(language, Random(it)) }

    @Test fun grammarStyle_tier0_onlyExposesPresentTenseAndSimpleQuestion() {
        val style = GrammarStyle(maxImportance = 0)
        val fragments = sampleFragments(style)

        // Tier 0 features (the always-on baseline) must appear.
        assertTrue("expected present-tense to appear", fragments.any { it.contains("present tense") })
        assertTrue("expected yes/no question to appear", fragments.any { it.contains("yes/no question") })

        // Higher-tier constructs must not leak in.
        assertFalse("past tense leaked into tier 0", fragments.any { it.contains("past tense") })
        assertFalse("future tense leaked into tier 0", fragments.any { it.contains("future tense") })
        assertFalse("conditional leaked into tier 0", fragments.any { it.contains("conditional") })
        assertFalse("subjunctive leaked into tier 0", fragments.any { it.contains("subjunctive") })
    }

    @Test fun grammarStyle_tier2_unlocksPastTenseAndNegation() {
        val fragments = sampleFragments(GrammarStyle(maxImportance = 2))
        assertTrue(fragments.any { it.contains("past tense") })
        assertTrue(fragments.any { it.contains("negation") })
        // But still no tier-4+ constructs.
        assertFalse(fragments.any { it.contains("future tense") })
        assertFalse(fragments.any { it.contains("subjunctive") })
    }

    @Test fun grammarStyle_tier6_unlocksConditionalButNotSubjunctive() {
        val fragments = sampleFragments(GrammarStyle(maxImportance = 6))
        assertTrue(fragments.any { it.contains("conditional") })
        assertTrue(fragments.any { it.contains("relative clause") })
        // Subjunctive is gated at tier 8 — the user isn't there yet.
        assertFalse(fragments.any { it.contains("subjunctive") })
        assertFalse(fragments.any { it.contains("passive voice") })
    }

    @Test fun grammarStyle_tier10_unlocksTheFullLadder() {
        val fragments = sampleFragments(GrammarStyle(maxImportance = 10))
        assertTrue(fragments.any { it.contains("present tense") })
        assertTrue(fragments.any { it.contains("past tense") })
        assertTrue(fragments.any { it.contains("future tense") })
        assertTrue(fragments.any { it.contains("conditional") })
        assertTrue(fragments.any { it.contains("subjunctive") })
        assertTrue(fragments.any { it.contains("passive voice") })
    }

    @Test fun grammarStyle_oddImportanceFloorsToPreviousEvenTier() {
        // The ladder unlocks at even tiers; importance 3 should behave like 2.
        val tier2 = sampleFragments(GrammarStyle(maxImportance = 2))
        val tier3 = sampleFragments(GrammarStyle(maxImportance = 3))
        assertEquals(tier2, tier3)
    }

    @Test fun grammarStyle_fragmentStartsWithSpaceForCleanConcatenation() {
        val fragment = GrammarStyle(maxImportance = 4).promptFragment(Language.ITALIAN, Random(0))
        assertTrue("fragment should start with a leading space, was: '$fragment'", fragment.startsWith(" "))
        assertTrue("fragment should end with a period, was: '$fragment'", fragment.endsWith("."))
    }

    @Test fun foreignCulture_emitsAThemeReferenceForEveryLanguage() {
        for (language in Language.values()) {
            val fragments = sampleFragments(ForeignCultureStyle, language = language, n = 200)
            assertTrue(
                "no themes emitted for $language",
                fragments.isNotEmpty(),
            )
            assertTrue(
                "all $language themes should be wrapped in the reference clause",
                fragments.all { it.contains("naturally reference") },
            )
        }
    }

    @Test fun foreignCulture_italianThemesAreItalian() {
        val fragments = sampleFragments(ForeignCultureStyle, language = Language.ITALIAN)
        // Smoke check: at least one Italian-specific theme shows up; no other
        // country's theme should leak.
        assertTrue(fragments.any { it.contains("Italian", ignoreCase = true) || it.contains("Rom") || it.contains("Tuscan") || it.contains("Venet") || it.contains("Amalfi") || it.contains("Sicilian") || it.contains("Milanese") || it.contains("Dolomites") })
        assertFalse("French theme leaked into Italian pool", fragments.any { it.contains("Paris") || it.contains("Provence") || it.contains("Côte d'Azur") })
        assertFalse("German theme leaked into Italian pool", fragments.any { it.contains("Berlin") || it.contains("Bavarian") || it.contains("Oktoberfest") })
    }

    @Test fun pick_drawsBothImplementationsAcrossSeeds() {
        val picks = (0 until 200).map { SentenceStyles.pick(maxImportance = 4, random = Random(it)) }
        assertTrue("ForeignCultureStyle was never picked", picks.any { it is ForeignCultureStyle })
        assertTrue("GrammarStyle was never picked", picks.any { it is GrammarStyle })
    }

    @Test fun pick_isDeterministicForAGivenSeed() {
        val a = SentenceStyles.pick(maxImportance = 4, random = Random(42))
        val b = SentenceStyles.pick(maxImportance = 4, random = Random(42))
        assertEquals(a::class, b::class)
    }
}
