package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gloss and foreign columns carry compact grammatical tags ("(pl)",
 * "(subj. pl)", "(m/f)", "letto (p.p.)"). They look fine on screen but the TTS
 * engine voices the bare abbreviation as the wrong word — "pl" -> "place",
 * "p.p." -> "pee pee". [EnglishAbbreviations.expand] and
 * [Language.expandAbbreviations] expand them to the full term before speaking;
 * these tests pin both the fixes and the words that must be left alone.
 */
class AbbreviationExpansionTest {

    private fun en(input: String) = EnglishAbbreviations.expand(input)

    @Test fun pluralTagIsSpokenInFull() {
        // The reported bug: "(pl)" was voiced as "place".
        assertEquals("you make plural", en("you make (pl)"))
        assertEquals("you were doing plural, used to do", en("you were doing (pl) / used to do"))
    }

    @Test fun subjunctiveAndPluralTagsExpandTogether() {
        assertEquals("that you be subjunctive plural", en("(that) you be (subj. pl)"))
        assertEquals("that he, she come subjunctive", en("(that) he/she come (subj.)"))
    }

    @Test fun pluralAfterAnotherAnnotationStillExpands() {
        assertEquals("you were passato remoto, plural", en("you were (passato remoto, pl)"))
    }

    @Test fun partOfSpeechTagsExpand() {
        assertEquals("excuse me, sorry, excuse noun", en("excuse me / sorry / excuse (n.)"))
        assertEquals(
            "verb to think about, consider; adjective superior",
            en("(verb) to think about / consider; (adj.) superior"),
        )
    }

    @Test fun genderTagsExpand() {
        assertEquals("lake masculine; die See: sea feminine", en("lake (m.); die See: sea (f.)"))
        assertEquals("player masculine or feminine", en("player (m/f)"))
        assertEquals("axis masculine, board, plank feminine", en("axis (m.) / board, plank (f.)"))
    }

    @Test fun realWordsContainingTagLettersAreUntouched() {
        // The tag letters appear inside ordinary words and full-word
        // parentheticals — none of these may be rewritten.
        for (word in listOf("simple", "people", "couple", "adjust", "explain", "plan")) {
            assertEquals(word, en(word))
        }
        assertEquals("formal", en("(formal)"))
        assertEquals("informal", en("(informal)"))
        assertEquals("noun", en("(noun)"))
        assertEquals("number", en("(number)"))
        assertEquals("female", en("(female)"))
        assertEquals("male", en("(male)"))
        assertEquals("verb", en("(verb)"))
    }

    @Test fun existingHonorificExpansionStillWorks() {
        assertEquals("versus", en("vs."))
        assertEquals("Doctor Rossi", en("Dr. Rossi"))
    }

    @Test fun latinGlossAbbreviationsExpand() {
        assertEquals("star self-luminous, for example the Sun", en("star (self-luminous, e.g. the Sun)"))
    }

    @Test fun germanSomeoneAbbreviationIsSpokenAsAWord() {
        // German verb-frame glosses embed "jmd." / "jmdn." (jemand); the English
        // voice otherwise reads the bare letters. Mirrors the "qn." -> "someone"
        // expansion the map already does for the French frames.
        assertEquals("someone erinnern an to remind", en("(jmd. erinnern an) to remind"))
        assertEquals("someone fragen to ask", en("(jmdn. fragen) to ask"))
    }

    @Test fun italianPastParticipleTagIsSpokenInFull() {
        // Foreign side: "letto (p.p.)" was voiced as "letto pee pee".
        assertEquals("letto participio passato", Language.ITALIAN.expandAbbreviations("letto (p.p.)"))
    }

    @Test fun italianPlainWordIsUnchanged() {
        assertEquals("voi fate", Language.ITALIAN.expandAbbreviations("voi fate"))
    }

    @Test fun slashesAreSpokenAsPauses() {
        // Some engines (seen on Samsung TTS) voice "/" as the word "slash";
        // a comma reads as a natural pause everywhere. Display keeps the slash —
        // only the pronounceable form is rewritten.
        assertEquals("he, she", en("he/she"))
        assertEquals("der, die Lehrer", Language.GERMAN.expandAbbreviations("der/die Lehrer"))
        assertEquals("aller, venir", Language.FRENCH.expandAbbreviations("aller / venir"))
        assertEquals("行く、来る", Language.JAPANESE.expandAbbreviations("行く/来る"))
    }
}
