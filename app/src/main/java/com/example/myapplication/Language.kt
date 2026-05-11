package com.example.myapplication

import java.util.Locale

enum class Language(
    val code: String,
    val locale: Locale,
    val assetFolder: String,
    val greeting: String,
    val englishName: String,
    private val abbreviations: Map<String, String>,
) {
    FRENCH(
        code = "fr",
        locale = Locale.FRENCH,
        assetFolder = "french",
        greeting = "Apprenez le français !",
        englishName = "French",
        abbreviations = mapOf(
            "Jr." to "Junior",
            "qc." to "quelque chose",
            "Dr." to "Docteur",
            "M." to "Monsieur",
            "etc." to "et cetera",
            "sec." to "seconde",
            "qn." to "quelqu'un",
            "St." to "Saint",
        ),
    ),
    GERMAN(
        code = "de",
        locale = Locale.GERMAN,
        assetFolder = "german",
        greeting = "Lerne Deutsch!",
        englishName = "German",
        abbreviations = mapOf(
            "z.B." to "zum Beispiel",
            "usw." to "und so weiter",
            "d.h." to "das heißt",
            "bzw." to "beziehungsweise",
            "Dr." to "Doktor",
            "etc." to "et cetera",
            "St." to "Sankt",
            "Nr." to "Nummer",
            "ca." to "circa",
        ),
    ),
    ITALIAN(
        code = "it",
        locale = Locale.ITALIAN,
        assetFolder = "italian",
        greeting = "Impara l'italiano!",
        englishName = "Italian",
        abbreviations = mapOf(
            "Sig." to "Signor",
            "Sig.ra" to "Signora",
            "Sig.na" to "Signorina",
            "Dr." to "Dottore",
            "Prof." to "Professore",
            "ecc." to "eccetera",
            "etc." to "eccetera",
            "es." to "esempio",
            "pag." to "pagina",
            "St." to "Santo",
        ),
    );

    private val abbreviationRegex: Regex by lazy {
        abbreviations.keys.joinToString("|") { Regex.escape(it) }.toRegex()
    }

    fun expandAbbreviations(input: String): String =
        abbreviationRegex.replace(input) { abbreviations[it.value] ?: it.value }
            .replace("(", "")
            .replace(")", "")

    companion object {
        val DEFAULT = FRENCH

        fun fromCode(code: String?): Language =
            values().firstOrNull { it.code == code } ?: DEFAULT
    }
}

object EnglishAbbreviations {
    private val map = mapOf(
        "vs." to "versus",
        "sth." to "something",
        "stb." to "somebody",
        "Mr." to "Mister",
        "Jr." to "junior",
        "etc." to "etcetera",
        "Dr." to "Doctor",
        "qn." to "someone",
        "qc." to "something",
        "sb." to "somebody",
        "Ms." to "Miss",
        "Mrs." to "Misses",
        "St." to "Saint",
        "Ph.D." to "P H D ",
    )
    private val regex = map.keys.joinToString("|") { Regex.escape(it) }.toRegex()

    fun expand(input: String): String =
        regex.replace(input) { map[it.value] ?: it.value }
            .replace("(", "")
            .replace(")", "")
}
