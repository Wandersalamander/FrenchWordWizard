package io.github.wandersalamander.vocabtrainer.llm

import io.github.wandersalamander.vocabtrainer.dictionary.Language
import kotlin.random.Random

/**
 * One stylistic flavour layered onto an LLM example-sentence prompt. Each
 * generation runs through [SentenceStyles.pick] which picks exactly one
 * implementation; the chosen style's [promptFragment] is folded into the
 * prompt so consecutive sentences for the same word don't collapse to the
 * same shape ("Io sono X." every time for *essere*, etc.).
 *
 * Adding a new flavour = adding an implementation here and listing it in
 * [SentenceStyles.pick]; nothing else has to change.
 */
sealed interface SentenceStyle {
    /**
     * The text appended to the LLM prompt. Should start with a leading
     * space so it concatenates cleanly with the rest of the prompt and end
     * with a period so the next clause begins on a sentence boundary.
     */
    fun promptFragment(language: Language, random: Random = Random.Default): String
}

/**
 * Asks the model to weave the sentence around something culturally tied to
 * the target language — local food, a city, a daily-life setting. The
 * theme pool is language-specific so an Italian sentence references Italy
 * rather than a generic "café somewhere". Stateless object: each call
 * picks a fresh theme.
 */
object ForeignCultureStyle : SentenceStyle {
    override fun promptFragment(language: Language, random: Random): String {
        val theme = themesFor(language).random(random)
        return " The sentence should naturally reference $theme."
    }

    private fun themesFor(language: Language): List<String> = when (language) {
        Language.FRENCH -> listOf(
            "French cuisine",
            "a Paris café",
            "the French countryside",
            "a boulangerie",
            "French cinema",
            "the Côte d'Azur",
            "a French wine region",
            "the Alps",
            "a Provençal market",
            "a Sunday family lunch in France",
        )
        Language.GERMAN -> listOf(
            "German cuisine",
            "a Berlin neighbourhood",
            "the Black Forest",
            "a Bavarian village",
            "a Christmas market",
            "the Rhine valley",
            "a German bakery",
            "Hamburg's harbour",
            "Oktoberfest",
            "a hike in the Alps",
        )
        Language.ITALIAN -> listOf(
            "Italian cuisine",
            "a Roman piazza",
            "the Tuscan countryside",
            "a Venetian canal",
            "an Italian family meal",
            "Sicilian coastal life",
            "a Milanese café",
            "the Amalfi coast",
            "an Italian gelateria",
            "a small village in the Dolomites",
        )
        Language.PORTUGUESE -> listOf(
            "Portuguese cuisine",
            "a Lisbon tram",
            "the Algarve coast",
            "a pastelaria with pastéis de nata",
            "fado music",
            "the Douro valley vineyards",
            "Porto's riverside",
            "a village in the Alentejo",
            "a Portuguese family meal",
            "the Sintra hills",
        )
        Language.FINNISH -> listOf(
            "Finnish cuisine",
            "a lakeside summer cottage",
            "a sauna evening",
            "a Helsinki tram",
            "the Finnish forest while picking berries",
            "a Midsummer celebration",
            "a snowy day in Lapland",
            "a coffee break with cinnamon buns",
            "the northern lights",
            "a cross-country skiing trip",
        )
        Language.CHINESE -> listOf(
            "Chinese cuisine",
            "a hutong in Beijing",
            "the Great Wall",
            "a tea house",
            "Chinese New Year",
            "a Shanghai street market",
            "Chinese calligraphy",
            "a Sichuan kitchen",
            "the Yangtze river",
            "a temple festival",
        )
        Language.JAPANESE -> listOf(
            "Japanese cuisine",
            "a Tokyo neighbourhood",
            "a ramen shop",
            "cherry-blossom season",
            "a traditional ryokan inn",
            "Mount Fuji",
            "a Shinto shrine",
            "a Kyoto temple",
            "a konbini (convenience store)",
            "the shinkansen bullet train",
        )
    }
}

/**
 * Asks the model to use one specific grammar construct, chosen at random
 * from a ladder that opens up as the user's [maxImportance] climbs. The
 * dictionary tier system goes 0 (most common) → 10 (most specialized), so
 * a learner whose introduced words top out at importance 2 isn't ready to
 * be quizzed with subjunctive-heavy sentences; importance acts as a
 * budget for how complex a construct the small on-device model can be
 * asked to produce without dropping the target word entirely.
 */
data class GrammarStyle(val maxImportance: Int) : SentenceStyle {
    override fun promptFragment(language: Language, random: Random): String {
        val feature = featuresUpTo(maxImportance).random(random)
        return " Use this grammar construct: $feature."
    }

    private fun featuresUpTo(max: Int): List<String> {
        val out = mutableListOf<String>()
        out += FEATURES_TIER_0
        if (max >= 2) out += FEATURES_TIER_2
        if (max >= 4) out += FEATURES_TIER_4
        if (max >= 6) out += FEATURES_TIER_6
        if (max >= 8) out += FEATURES_TIER_8
        return out
    }

    companion object {
        // Always available — even a brand-new learner has present-tense
        // statements and questions in their toolkit.
        private val FEATURES_TIER_0 = listOf(
            "present tense, simple statement",
            "a simple yes/no question",
        )

        // Importance 2+: basic temporal / polarity / mood constructs that
        // assume the learner has enough common-word vocabulary to anchor a
        // non-present-tense clause.
        private val FEATURES_TIER_2 = listOf(
            "past tense",
            "a negation (e.g. \"not\", \"never\")",
            "an imperative (a command)",
        )

        // Importance 4+: comparing, quantifying, talking about the future.
        private val FEATURES_TIER_4 = listOf(
            "future tense",
            "a comparative (\"more X than Y\")",
            "a quantifier (\"some\", \"many\", \"few\")",
        )

        // Importance 6+: two-clause sentences — the user is past the
        // single-clause comfort zone and can parse conjoined ideas.
        private val FEATURES_TIER_6 = listOf(
            "a conditional (\"if ... then ...\")",
            "a subordinate clause with \"because\"",
            "a relative clause (\"the X that ...\")",
            "the conditional mood (\"would\")",
        )

        // Importance 8+: advanced / formal moods that mostly appear in
        // higher-tier words anyway.
        private val FEATURES_TIER_8 = listOf(
            "passive voice",
            "a hypothetical / counterfactual (\"if I had X, I would Y\")",
            "subjunctive mood",
            "reported speech (\"she said that ...\")",
        )
    }
}

/**
 * Single entry point that picks one [SentenceStyle] per generation. New
 * flavours get added here and immediately fold into the rotation; callers
 * never branch on style type. [random] is overridable so tests can pin
 * the choice deterministically.
 */
object SentenceStyles {
    fun pick(maxImportance: Int, random: Random = Random.Default): SentenceStyle {
        val implementations: List<SentenceStyle> = listOf(
            ForeignCultureStyle,
            GrammarStyle(maxImportance),
        )
        return implementations.random(random)
    }
}
