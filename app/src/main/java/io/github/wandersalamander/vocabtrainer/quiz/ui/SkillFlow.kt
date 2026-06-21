package io.github.wandersalamander.vocabtrainer.quiz.ui

import android.view.View
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import io.github.wandersalamander.vocabtrainer.quiz.QuizViews
import io.github.wandersalamander.vocabtrainer.quiz.TtsHelper
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener

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
/** What the Tip button does for a given skill. */
internal sealed interface TipBehavior {
    /** Tip button is hidden/disabled. */
    object None : TipBehavior
    /** Tip shows an example sentence (with optional masking). */
    object Sentence : TipBehavior
    /** Tip reveals one more letter of the foreign word. */
    object ProgressiveReveal : TipBehavior
}

internal sealed interface SkillFlow {
    /** What pressing Tip does for this skill. */
    val tipBehavior: TipBehavior

    /** Convenience flag derived from [tipBehavior]. */
    val tipAllowed: Boolean get() = tipBehavior !is TipBehavior.None

    /**
     * True when the English meaning is the prompt at round start (the user
     * has to produce the foreign word); false when the foreign word is the
     * prompt (the user has to recall the meaning).
     */
    val englishIsPrompt: Boolean

    /**
     * Set up [QuizViews.textForeign] for the start of a round.
     *
     * [onMaskedWordTapped] — invoked when the user taps a *masked* foreign word
     * to reveal it via TTS. This is a hint and costs a small failure increment.
     *
     * [onWordReplayed] — invoked when the user taps an *already visible*
     * foreign word to hear it spoken again. No penalty; pure convenience.
     */
    fun setupForeignText(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
        onWordReplayed: () -> Unit,
    )

    /** Speak the prompt side at round start. */
    fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper)

    /** Speak the answer side when the user gives up. */
    fun ttsOnReveal(vocab: Vocab, tts: TtsHelper)

    /**
     * Whether a clean Next on this skill routes through the mastery / refresh /
     * random spot check. Default true; [SpeakFlow] overrides to false because
     * the record-and-compare loop already verifies recall, so a follow-up
     * "are you sure?" adds nothing (and the user can't fast-press past it).
     */
    val usesSpotCheck: Boolean get() = true

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

    /**
     * Render the foreign word with [revealedLetters] letters unmasked, the
     * rest still hidden. Only meaningful when [tipBehavior] is
     * [TipBehavior.ProgressiveReveal]; default is a no-op.
     */
    fun applyProgressiveReveal(vocab: Vocab, views: QuizViews, revealedLetters: Int) {}
}

internal object ReadFlow : SkillFlow {
    override val tipBehavior = TipBehavior.Sentence
    override val englishIsPrompt = false

    override fun setupForeignText(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
        onWordReplayed: () -> Unit,
    ) {
        views.textForeign.text = vocab.foreign
        // Tap the visible foreign word to hear it again (no penalty).
        views.textForeign.setDebouncedOnClickListener { onWordReplayed() }
        views.textForeign.isClickable = true
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
    override val tipBehavior = TipBehavior.Sentence
    override val englishIsPrompt = false

    override fun setupForeignText(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
        onWordReplayed: () -> Unit,
    ) {
        if (listeningEnabled) {
            views.textForeign.text = maskWord(vocab.foreign)
            views.textForeign.setDebouncedOnClickListener { onMaskedWordTapped() }
            views.textForeign.isClickable = true
        } else {
            // Listening disabled → word is shown like in READ. Allow no-penalty replays.
            views.textForeign.text = vocab.foreign
            views.textForeign.setDebouncedOnClickListener { onWordReplayed() }
            views.textForeign.isClickable = true
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

private fun maskWord(word: String, maskChar: Char = '●'): String =
    word.map { if (it.isLetter()) maskChar else it }.joinToString("")

private fun revealLetterPrefix(word: String, revealedLetters: Int, maskChar: Char): String {
    var remaining = revealedLetters
    return word.map { c ->
        when {
            !c.isLetter() -> c
            remaining > 0 -> { remaining--; c }
            else -> maskChar
        }
    }.joinToString("")
}

internal object InvertFlow : SkillFlow {
    override val tipBehavior = TipBehavior.ProgressiveReveal
    override val englishIsPrompt = true

    private const val MASK_CHAR = '○'

    override fun setupForeignText(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
        onWordReplayed: () -> Unit,
    ) {
        // INVERT keeps the foreign word fully masked until progressive reveal —
        // tapping it must not leak audio of the answer.
        views.textForeign.text = maskWord(vocab.foreign, MASK_CHAR)
        views.textForeign.setOnClickListener(null)
        views.textForeign.isClickable = false
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

    override fun applyProgressiveReveal(vocab: Vocab, views: QuizViews, revealedLetters: Int) {
        views.textForeign.text = revealLetterPrefix(vocab.foreign, revealedLetters, MASK_CHAR)
    }
}

internal object SpeakFlow : SkillFlow {
    override val tipBehavior = TipBehavior.None

    // Both the foreign word and its English meaning are shown from the start
    // (englishIsPrompt drives the textEn render path; setupForeignText shows the
    // word in full). Speaking is therefore pure pronunciation — recall plays no
    // part, so there's no hiding and no cross-tier penalty.
    override val englishIsPrompt = true

    // The record → hear-model → hear-self → judge loop is its own verification,
    // so SPEAK never routes a clean Next through the spot check.
    override val usesSpotCheck = false

    override fun setupForeignText(
        vocab: Vocab,
        views: QuizViews,
        listeningEnabled: Boolean,
        onMaskedWordTapped: () -> Unit,
        onWordReplayed: () -> Unit,
    ) {
        // Show the foreign word in full, but keep it non-clickable so a press
        // anywhere on the card drives the hold-to-record gesture rather than a
        // tap-to-replay (which would swallow the press).
        views.textForeign.text = vocab.foreign
        views.textForeign.setOnClickListener(null)
        views.textForeign.isClickable = false
    }

    // Speak the foreign word at round start so the user hears the target to say.
    // (No longer an answer to hide — it's on screen — so there's nothing to leak.)
    override fun ttsAtRoundStart(vocab: Vocab, tts: TtsHelper) =
        tts.speakForeignWord(vocab, flush = true)

    override fun ttsOnReveal(vocab: Vocab, tts: TtsHelper) =
        tts.speakForeignWord(vocab, flush = true)

    // Required by the interface but never invoked, since usesSpotCheck = false.
    override fun setupSpotCheck(views: QuizViews, revealForeign: () -> Unit) {
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
        Skill.SPEAK -> SpeakFlow
    }
