package com.example.myapplication.quiz

import android.view.View
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.Vocab

/**
 * Per-skill behavior strategy. Centralizes every place the quiz flow has to
 * branch on the active skill: initial display, TTS direction, spot-check
 * reveal side, tip allowance, and the masking / compromise rules for the
 * example sentence.
 *
 * To add a new skill:
 *   1. Add the entry to [Skill] (and to its `ladder` list).
 *   2. Add a new `object` implementing [SkillFlow] here.
 *   3. Add it to the [flow] dispatch — the exhaustive `when` makes the
 *      compiler point you at the missing case.
 * No edits to [QuizController] needed.
 */
internal sealed interface SkillFlow {
    /** Whether the Tip button is meaningful for this skill. */
    val tipAllowed: Boolean

    /**
     * True when the English meaning is the prompt at round start (the user
     * has to produce the foreign word); false when the foreign word is the
     * prompt (the user has to recall the meaning).
     */
    val englishIsPrompt: Boolean

    /** Set up [QuizViews.textFr] for the start of a round. */
    fun setupTextFr(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
    )

    /** Speak the prompt side at round start. */
    fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper)

    /** Speak the answer side when the user gives up. */
    fun ttsOnReveal(vocab: Vocab, tts: TtsHelper)

    /** Reveal whichever side the spot-check should show. */
    fun setupSpotCheck(views: QuizViews, revealForeign: () -> Unit)

    /** Should the example sentence (shown via Tip) be masked under this skill? */
    fun shouldMaskSentenceOnTip(listeningEnabled: Boolean): Boolean

    /** On Fail/reveal, reuse the cached sentence instead of regenerating? */
    fun reuseSentenceOnReveal(listeningEnabled: Boolean): Boolean

    /**
     * True if a round of this skill is compromised given the current
     * [listeningEnabled] state. Drives the rollback in `saveCurrentVocab`.
     */
    fun isCompromisedByListening(listeningEnabled: Boolean): Boolean
}

internal object ReadFlow : SkillFlow {
    override val tipAllowed = true
    override val englishIsPrompt = false

    override fun setupTextFr(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
    ) {
        views.textFr.text = vocab.french
        views.textFr.setOnClickListener(null)
        views.textFr.isClickable = false
    }

    override fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper) =
        tts.speakForeignWord(vocab, flush = true)

    override fun ttsOnReveal(vocab: Vocab, tts: TtsHelper) =
        tts.speakEnglishWord(vocab)

    override fun setupSpotCheck(views: QuizViews, revealForeign: () -> Unit) {
        views.textEn.visibility = View.VISIBLE
    }

    override fun shouldMaskSentenceOnTip(listeningEnabled: Boolean): Boolean = false
    override fun reuseSentenceOnReveal(listeningEnabled: Boolean): Boolean = false
    override fun isCompromisedByListening(listeningEnabled: Boolean): Boolean = false
}

internal object ListenFlow : SkillFlow {
    override val tipAllowed = true
    override val englishIsPrompt = false

    override fun setupTextFr(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
    ) {
        if (listeningEnabled) {
            views.textFr.text = maskWord(vocab.french)
            views.textFr.setOnClickListener { onMaskedWordTapped() }
            views.textFr.isClickable = true
        } else {
            views.textFr.text = vocab.french
            views.textFr.setOnClickListener(null)
            views.textFr.isClickable = false
        }
    }

    override fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper) =
        tts.speakForeignWord(vocab, flush = true)

    override fun ttsOnReveal(vocab: Vocab, tts: TtsHelper) =
        tts.speakEnglishWord(vocab)

    override fun setupSpotCheck(views: QuizViews, revealForeign: () -> Unit) {
        views.textEn.visibility = View.VISIBLE
    }

    override fun shouldMaskSentenceOnTip(listeningEnabled: Boolean): Boolean = listeningEnabled
    override fun reuseSentenceOnReveal(listeningEnabled: Boolean): Boolean = listeningEnabled
    override fun isCompromisedByListening(listeningEnabled: Boolean): Boolean = !listeningEnabled
}

private fun maskWord(word: String): String =
    word.map { if (it.isLetter()) '⬤' else it }.joinToString("")

internal object InvertFlow : SkillFlow {
    override val tipAllowed = false
    override val englishIsPrompt = true

    override fun setupTextFr(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
    ) {
        views.textFr.text = maskWord(vocab.french)
        views.textFr.setOnClickListener(null)
        views.textFr.isClickable = false
    }

    override fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper) =
        tts.speakEnglishWord(vocab)

    override fun ttsOnReveal(vocab: Vocab, tts: TtsHelper) =
        tts.speakForeignWord(vocab, flush = true)

    override fun setupSpotCheck(views: QuizViews, revealForeign: () -> Unit) {
        // English already on screen as the prompt; reveal the foreign answer.
        revealForeign()
    }

    override fun shouldMaskSentenceOnTip(listeningEnabled: Boolean): Boolean = false
    override fun reuseSentenceOnReveal(listeningEnabled: Boolean): Boolean = false
    override fun isCompromisedByListening(listeningEnabled: Boolean): Boolean = false
}

internal val Skill.flow: SkillFlow
    get() = when (this) {
        Skill.READ -> ReadFlow
        Skill.LISTEN -> ListenFlow
        Skill.INVERT -> InvertFlow
    }
