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
        val frame = FRAMES.random(random)
        return frame.format(theme)
    }

    // Every frame must read naturally with any theme noun phrase (a place,
    // an event, a dish, an activity) substituted for %s.
    private val FRAMES = listOf(
        " The sentence should naturally reference %s.",
        " The sentence should naturally involve %s.",
        " Set the sentence in a scene involving %s.",
        " The sentence should be about %s.",
    )

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
            "a croissant breakfast",
            "a Paris Métro ride",
            "Mont Saint-Michel",
            "a fromagerie (cheese shop)",
            "a game of pétanque",
            "the Tour de France",
            "Bastille Day celebrations",
            "a château in the Loire valley",
            "the lavender fields of Provence",
            "a crêperie in Brittany",
            "a bouchon restaurant in Lyon",
            "the Louvre",
            "a Paris flea market",
            "a TGV train journey",
            "a café terrace in the evening",
            "the Normandy coast",
            "a picnic by the Seine",
            "the old port of Marseille",
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
            "an afternoon in a Biergarten",
            "a currywurst stand in Berlin",
            "Neuschwanstein castle",
            "Kaffee und Kuchen with family",
            "the Baltic Sea coast",
            "Cologne cathedral",
            "a Bundesliga football match",
            "returning Pfand bottles at the supermarket",
            "an S-Bahn commute",
            "carnival season in Cologne",
            "a Munich beer hall",
            "a cycling trip along a river",
            "a busy German train station",
            "a Schrebergarten (allotment garden)",
            "fresh pretzels from a bakery",
            "a winter walk in the Harz mountains",
            "a spa day in Baden-Baden",
            "a mountain hut above an Alpine valley",
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
            "an espresso at the bar counter",
            "a Vespa ride through town",
            "an opera night at La Scala",
            "the Colosseum",
            "a grandmother cooking fresh pasta",
            "the evening passeggiata",
            "a local football derby",
            "the Cinque Terre",
            "a fish market in Naples",
            "Carnival in Venice",
            "a vineyard in Chianti",
            "an aperitivo with friends",
            "the ruins of Pompeii",
            "a beach in Sardinia",
            "an agriturismo farm stay",
            "Lake Como",
            "a pizzeria in Naples",
            "a morning cappuccino with a cornetto",
        )
        Language.SPANISH -> listOf(
            "Spanish cuisine",
            "a tapas bar in the evening",
            "a plaza mayor at dusk",
            "an Andalusian white village",
            "a Spanish family lunch",
            "the beaches of the Costa Brava",
            "a Madrid café terrace",
            "the Alhambra in Granada",
            "a flamenco tablao in Seville",
            "the Camino de Santiago",
            "a paella cooked over an open fire",
            "the Sagrada Família",
            "a football match at the Bernabéu",
            "an olive grove in Jaén",
            "the running of the bulls in Pamplona",
            "a mercado full of jamón and cheese",
            "the Basque coast",
            "a siesta on a hot afternoon",
            "Semana Santa processions",
            "a vineyard in La Rioja",
            "the Prado museum",
            "a chiringuito on the beach",
            "the windmills of La Mancha",
            "churros con chocolate for breakfast",
            "the Canary Islands",
            "a night out that lasts until sunrise",
            "the Picos de Europa mountains",
            "La Tomatina in Buñol",
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
            "a football match in Lisbon",
            "azulejo tiles on old façades",
            "a bacalhau dinner",
            "the giant waves of Nazaré",
            "the Belém tower",
            "a tiny ginjinha bar",
            "the island of Madeira",
            "the Azores",
            "a cork oak landscape",
            "the old university of Coimbra",
            "grilled sardines at a street festival",
            "port wine cellars in Vila Nova de Gaia",
            "a beach day in Cascais",
            "a busy mercado in Lisbon",
            "the Chiado district",
            "a ferry crossing the Tagus",
            "a coffee at a miradouro viewpoint",
            "a surf lesson on the Atlantic coast",
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
            "ice swimming in winter",
            "an ice hockey match",
            "the Moomins",
            "the market square in Helsinki",
            "fresh rye bread",
            "foraging mushrooms in autumn",
            "the midnight sun",
            "reindeer in Lapland",
            "the Turku archipelago",
            "a May Day (vappu) picnic",
            "a quiet train ride through endless forests",
            "Karelian pies with egg butter",
            "walking across a frozen lake",
            "blueberry picking in summer",
            "a summer cabin without electricity",
            "the first snow of the year",
            "salmiakki (salty liquorice)",
            "a public sauna in Tampere",
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
            "making dumplings with family",
            "a high-speed train journey",
            "morning tai chi in the park",
            "the Mid-Autumn Festival with mooncakes",
            "a night market",
            "hot pot with friends",
            "the Terracotta Army",
            "West Lake in Hangzhou",
            "bargaining at a market stall",
            "a bamboo forest",
            "pandas in Chengdu",
            "the Forbidden City",
            "playing mahjong with grandparents",
            "a bubble tea shop",
            "the karst mountains of Guilin",
            "red envelopes at Lunar New Year",
            "a jianbing breakfast from a street cart",
            "the Bund in Shanghai",
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
            "an onsen hot spring",
            "a sumo tournament",
            "a bento lunch box",
            "autumn leaves in Kyoto",
            "a summer matsuri festival",
            "a row of vending machines",
            "karaoke with friends",
            "a zen rock garden",
            "a sushi counter",
            "Shibuya crossing",
            "a tatami room",
            "a hanami picnic under the blossoms",
            "a manga café",
            "street food in Osaka",
            "a tea ceremony",
            "the beaches of Okinawa",
            "a snowy day in Hokkaido",
            "summer fireworks (hanabi)",
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
