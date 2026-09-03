package io.github.wandersalamander.vocabtrainer.dictionary

import android.content.Context
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import java.util.Locale

private val pinyinParenRegex = Regex("""\s*\([^)]*\)""")

// "der/die", "he/she", "aller / venir" alternative markers: some TTS engines
// (seen on a Samsung device; Pixel reads it silently) voice the "/" as the
// word "slash". A comma is a natural pause on every engine, so slashes become
// commas in the spoken form only — the on-screen text keeps the slash.
private val slashRegex = Regex("""\s*/\s*""")

enum class Language(
    val code: String,
    val locale: Locale,
    val assetFolder: String,
    val greeting: String,
    val englishName: String,
    val flag: String,
    private val abbreviations: Map<String, String>,
) {
    FRENCH(
        code = "fr",
        locale = Locale.FRENCH,
        assetFolder = "french",
        greeting = "Apprenez le français !",
        englishName = "French",
        flag = "🇫🇷",
        abbreviations = mapOf(
            "Jr." to "Junior",
            "qc." to "quelque chose",
            "Dr." to "Docteur",
            "M." to "Monsieur",
            "Mme." to "Madame",
            "Mlle." to "Mademoiselle",
            "Pr." to "Professeur",
            "etc." to "et cetera",
            "sec." to "seconde",
            "min." to "minute",
            "qn." to "quelqu'un",
            "St." to "Saint",
            "p. ex." to "par exemple",
            "c.-à-d." to "c'est-à-dire",
            "s.v.p." to "s'il vous plaît",
            "env." to "environ",
        ),
    ),
    GERMAN(
        code = "de",
        locale = Locale.GERMAN,
        assetFolder = "german",
        greeting = "Lerne Deutsch!",
        englishName = "German",
        flag = "🇩🇪",
        abbreviations = mapOf(
            "z.B." to "zum Beispiel",
            "usw." to "und so weiter",
            "d.h." to "das heißt",
            "bzw." to "beziehungsweise",
            "Dr." to "Doktor",
            "Prof." to "Professor",
            "etc." to "et cetera",
            "St." to "Sankt",
            "Nr." to "Nummer",
            "ca." to "circa",
            "Mio." to "Million",
            "Mrd." to "Milliarde",
            "Min." to "Minute",
            "Std." to "Stunde",
            "jmd." to "jemand",
            "etw." to "etwas",
            "evtl." to "eventuell",
            "vgl." to "vergleiche",
            "u.a." to "unter anderem",
            "v.a." to "vor allem",
        ),
    ),
    ITALIAN(
        code = "it",
        locale = Locale.ITALIAN,
        assetFolder = "italian",
        greeting = "Impara l'italiano!",
        englishName = "Italian",
        flag = "🇮🇹",
        abbreviations = mapOf(
            "Sig.ra" to "Signora",
            "Sig.na" to "Signorina",
            "Sig." to "Signor",
            "Dr." to "Dottore",
            "Prof." to "Professore",
            "Avv." to "Avvocato",
            "Ing." to "Ingegnere",
            "Arch." to "Architetto",
            "ecc." to "eccetera",
            "etc." to "eccetera",
            "p.es." to "per esempio",
            "es." to "esempio",
            "p.p." to "participio passato",
            "pag." to "pagina",
            "num." to "numero",
            "tel." to "telefono",
            "St." to "Santo",
            "a.C." to "avanti Cristo",
            "d.C." to "dopo Cristo",
            "N.B." to "Nota Bene",
            "P.S." to "Post Scriptum",
        ),
    ),
    SPANISH(
        code = "es",
        // European Spanish (es-ES): the bundled dictionary uses peninsular
        // vocabulary (e.g. coche, ordenador, móvil, zumo, patata, vosotros) and
        // this locale drives TTS toward a Spain voice rather than es-MX/es-US.
        locale = Locale("es", "ES"),
        assetFolder = "spanish",
        greeting = "¡Aprende español!",
        englishName = "Spanish",
        flag = "🇪🇸",
        abbreviations = mapOf(
            "Srta." to "Señorita",
            "Sra." to "Señora",
            "Sr." to "Señor",
            "Dra." to "Doctora",
            "Dr." to "Doctor",
            "Prof." to "Profesor",
            "Uds." to "ustedes",
            "Ud." to "usted",
            "etc." to "etcétera",
            "p. ej." to "por ejemplo",
            "ej." to "ejemplo",
            "núm." to "número",
            "pág." to "página",
            "tel." to "teléfono",
            "a. C." to "antes de Cristo",
            "d. C." to "después de Cristo",
            "EE. UU." to "Estados Unidos",
            "Sto." to "Santo",
            "Sta." to "Santa",
        ),
    ),
    PORTUGUESE(
        code = "pt",
        // European Portuguese (pt-PT): the bundled dictionary uses pt-PT
        // vocabulary (e.g. comboio, autocarro, telemóvel, pequeno-almoço) and
        // this locale drives TTS toward a Portugal voice rather than pt-BR.
        locale = Locale("pt", "PT"),
        assetFolder = "portuguese",
        greeting = "Aprenda português!",
        englishName = "Portuguese",
        flag = "🇵🇹",
        abbreviations = mapOf(
            "Sr." to "Senhor",
            "Sra." to "Senhora",
            "Dr." to "Doutor",
            "Dra." to "Doutora",
            "Prof." to "Professor",
            "Eng." to "Engenheiro",
            "etc." to "et cetera",
            "p. ex." to "por exemplo",
            "ex." to "exemplo",
            "n.º" to "número",
            "pág." to "página",
            "séc." to "século",
            "tel." to "telefone",
            "a.C." to "antes de Cristo",
            "d.C." to "depois de Cristo",
            "St." to "São",
            "i.e." to "isto é",
            "cf." to "confira",
        ),
    ),
    FINNISH(
        code = "fi",
        // Finnish (fi-FI). There is no Locale.FINNISH constant, so construct it
        // (as PORTUGUESE does); this drives TTS toward a Finnish voice and tags
        // the foreign text views with the right locale.
        locale = Locale("fi", "FI"),
        assetFolder = "finnish",
        greeting = "Opi suomea!",
        englishName = "Finnish",
        flag = "🇫🇮",
        // The bundled corpus is fully spelled out, so these only matter if
        // abbreviated vocab is added later. Kept to safe multi-letter keys with
        // a mandatory trailing period — never single letters like "n.", which
        // would fire on the sentence-final period of any word ending in -n.
        abbreviations = mapOf(
            "esim." to "esimerkiksi",
            "jne." to "ja niin edelleen",
            "yms." to "ynnä muuta sellaista",
        ),
    ),
    CHINESE(
        code = "zh",
        locale = Locale.SIMPLIFIED_CHINESE,
        assetFolder = "chinese",
        greeting = "学习中文吧！",
        englishName = "Chinese",
        flag = "🇨🇳",
        abbreviations = emptyMap(),
    ) {
        override fun expandAbbreviations(input: String): String =
            input.replace(pinyinParenRegex, "").replace(slashRegex, "、").trim()
    },
    JAPANESE(
        code = "ja",
        locale = Locale.JAPANESE,
        assetFolder = "japanese",
        greeting = "日本語を学ぼう！",
        englishName = "Japanese",
        flag = "🇯🇵",
        abbreviations = emptyMap(),
    ) {
        // Headwords are stored as "漢字かな (rōmaji)"; strip the reading paren the
        // same way Chinese strips pinyin so TTS speaks the kanji/kana while the
        // on-screen headword keeps its reading.
        override fun expandAbbreviations(input: String): String =
            input.replace(pinyinParenRegex, "").replace(slashRegex, "、").trim()
    };

    private val abbreviationRegex: Regex by lazy {
        abbreviations.keys.joinToString("|") { Regex.escape(it) }.toRegex()
    }

    open fun expandAbbreviations(input: String): String =
        abbreviationRegex.replace(input) { abbreviations[it.value] ?: it.value }
            .replace("(", "")
            .replace(")", "")
            .replace(slashRegex, ", ")

    companion object {
        val DEFAULT = FRENCH

        fun fromCode(code: String?): Language =
            values().firstOrNull { it.code == code } ?: DEFAULT

        /**
         * The language the user is currently configured to study, read from
         * [AppPrefs]. Falls back to [DEFAULT] on first launch (no preference
         * stored) or post-wipe. Shared entry point — every component that
         * needs the active language should call this rather than re-reading
         * the SharedPreferences key, so the boot path stays a single line.
         */
        fun fromPrefs(context: Context): Language =
            fromCode(AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null))
    }
}

object EnglishAbbreviations {
    private val map = mapOf(
        "vs." to "versus",
        "e.g." to "for example",
        "i.e." to "that is",
        "sth." to "something",
        "stb." to "somebody",
        "Mr." to "Mister",
        "Jr." to "junior",
        "etc." to "etcetera",
        "Dr." to "Doctor",
        "qn." to "someone",
        "qc." to "something",
        "jmd." to "someone",
        "jmdn." to "someone",
        "sb." to "somebody",
        "Ms." to "Miss",
        "Mrs." to "Misses",
        "St." to "Saint",
        "Ph.D." to "P H D ",
    )
    private val regex = map.keys.joinToString("|") { Regex.escape(it) }.toRegex()

    // Grammatical tags used in the gloss, e.g. "you make (pl)", "(subj. pl)",
    // "(adj.) superior", "excuse (n.)", "player (m/f)". Stripping the parens
    // leaves the bare abbreviation, which the TTS engine then voices as the
    // wrong word — "pl" becomes "place", "m." becomes "metres". Expanding each
    // tag to the term it stands for fixes the audio; the on-screen gloss still
    // shows the compact form (only pronounceableEn() runs through here).
    //
    // Multi-letter tags are matched as whole words (\b…\b, trailing "."
    // optional) so they never fire inside "simple" or "adjust". Single-letter
    // tags would be hopelessly greedy that way, so they're recognised only in
    // the parenthesised form they always take — "(m.)", "(f.)", "(n.)",
    // "(m/f)" — via a "(" look-behind, with the "." kept mandatory so "(n.)"
    // is never confused with the word "(noun)".
    private val wordTags = mapOf(
        "pl" to "plural",
        "subj" to "subjunctive",
        "adj" to "adjective",
    )
    private val parenTags = mapOf(
        "m/f" to "masculine or feminine",
        "m." to "masculine",
        "f." to "feminine",
        "n." to "noun",
    )
    private val wordTagRegex =
        wordTags.keys.joinToString("|") { Regex.escape(it) }
            .let { """\b(?:$it)\b\.?""".toRegex() }
    private val parenTagRegex =
        parenTags.keys.joinToString("|") { Regex.escape(it) }
            .let { """(?<=\()(?:$it)""".toRegex() }

    // The slash swap must run AFTER the paren-tag pass: "(m/f)" has to reach
    // parenTagRegex intact to expand to "masculine or feminine".
    fun expand(input: String): String =
        regex.replace(input) { map[it.value] ?: it.value }
            .let { s -> wordTagRegex.replace(s) { wordTags[it.value.trimEnd('.')] ?: it.value } }
            .let { s -> parenTagRegex.replace(s) { parenTags[it.value] ?: it.value } }
            .replace("(", "")
            .replace(")", "")
            .replace(slashRegex, ", ")
}
