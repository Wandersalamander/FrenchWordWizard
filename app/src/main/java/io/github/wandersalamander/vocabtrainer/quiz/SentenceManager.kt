package io.github.wandersalamander.vocabtrainer.quiz

import android.graphics.drawable.Animatable
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import io.github.wandersalamander.vocabtrainer.llm.LlmService
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener

private const val SENTENCE_FADE_IN_DURATION_MS = 220L

/**
 * Manages the example sentence shown in [QuizViews.textGuessLong]: LLM call,
 * fallback to bundled CSV sentences, masking for LISTEN rounds, the thinking
 * animation, and the cached unmasked text used for re-speaking when the
 * masked form is tapped.
 *
 * Round state (current vocab, current skill) is passed in at every call —
 * the manager doesn't observe it, so QuizController stays the source of
 * truth. Mask taps call back via [onMaskTapped] so the per-word failure
 * penalty + TTS replay stays with the round-state owner.
 */
internal class SentenceManager(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val dictionary: MyDictionary,
    private val language: Language,
    private val onMaskTapped: () -> Unit,
    private val recentWords: () -> List<String>,
) {
    /**
     * Last unmasked sentence shown to the user. Stays around even when
     * [QuizViews.textGuessLong] displays the masked form so a tap-to-re-read
     * handler can speak the original. Cleared/replaced each round via [seed].
     */
    var currentSentence: String? = null
        private set

    /**
     * Seed the per-word sentence cache with the CSV fallback so a reveal
     * (Fail) without a prior Tip still has something to display without
     * burning an LLM call. Honour the user's easy/hard preference so the
     * seed matches what they'd see if Tip were pressed.
     */
    fun seed(vocab: Vocab) {
        val seed = vocab.csvSentenceFor(SentenceSource.fromContext(activity))
        views.textGuessLong.text = seed
        views.textGuessLong.setOnClickListener(null)
        views.textGuessLong.isClickable = false
        currentSentence = seed
    }

    /**
     * Generates (or falls back to) the example sentence, displays it in
     * [QuizViews.textGuessLong], and returns the unmasked text so the caller
     * can hand it to TTS. When [maskWhenListening] is true and the current
     * skill masks sentences for listening practice, the on-screen text is
     * replaced with bullets. When [useExisting] is true (reveal flow), the
     * cached [currentSentence] is reused instead of calling the LLM again.
     */
    suspend fun generateAndShow(
        vocab: Vocab,
        skill: Skill,
        listeningEnabled: Boolean,
        maskWhenListening: Boolean = false,
        useExisting: Boolean = false,
    ): String {
        val source = SentenceSource.fromContext(activity)
        val sentence: String = when {
            useExisting -> currentSentence ?: vocab.csvSentenceFor(source)
            source == SentenceSource.LLM && LlmService.isReady -> generateFromLlmOrFallback(vocab, source)
            else -> vocab.csvSentenceFor(source)
        }
        currentSentence = sentence
        val maskNow = maskWhenListening && skill.flow.shouldMaskSentenceOnTip(listeningEnabled)
        render(sentence, maskNow)
        views.textGuessLong.alpha = 0f
        views.textGuessLong.visibility = View.VISIBLE
        views.textGuessLong.animate().alpha(1f).setDuration(SENTENCE_FADE_IN_DURATION_MS).start()
        return sentence
    }

    /**
     * Re-render the cached sentence with mask state recomputed from the
     * given [skill] / [listeningEnabled]. Used by the listening-mode menu
     * toggle, which needs the masking to flip without picking a new sentence.
     */
    fun reRender(skill: Skill, listeningEnabled: Boolean) {
        val sentence = currentSentence ?: return
        render(sentence, skill.flow.shouldMaskSentenceOnTip(listeningEnabled))
    }

    /**
     * Render the cached sentence unmasked (e.g. on Reveal — the round is
     * over, so the masking penalty no longer applies).
     */
    fun renderUnmasked() {
        val sentence = currentSentence ?: return
        render(sentence, mask = false)
    }

    private suspend fun generateFromLlmOrFallback(vocab: Vocab, source: SentenceSource): String {
        views.textGuessLong.visibility = View.INVISIBLE
        startThinkingAnimation()
        val generated = try {
            LlmService.generate(
                word = vocab.foreign,
                translation = vocab.english,
                recent = recentWords(),
                everSeen = dictionary.getIntroducedForeignWords(),
                language = language,
            )
        } finally {
            stopThinkingAnimation()
        }
        return generated ?: vocab.csvSentenceFor(source)
    }

    /** Sets [QuizViews.textGuessLong]'s text and tap-handler based on [mask]. */
    private fun render(sentence: String, mask: Boolean) {
        if (mask) {
            views.textGuessLong.text = maskSentence(sentence)
            views.textGuessLong.setDebouncedOnClickListener { onMaskTapped() }
            views.textGuessLong.isClickable = true
            // The visible mask is all underscores — TalkBack reads each one
            // ("underscore underscore underscore…"). Swap in a sentence form
            // describing the tap action so SR users get something useful.
            views.textGuessLong.contentDescription =
                "Example sentence hidden, tap to hear it read aloud"
        } else {
            views.textGuessLong.text = sentence
            views.textGuessLong.setOnClickListener(null)
            views.textGuessLong.isClickable = false
            views.textGuessLong.contentDescription = null
        }
    }

    private fun maskSentence(sentence: String): String =
        sentence.map { if (it.isWhitespace()) it else '_' }.joinToString("")

    private fun startThinkingAnimation() {
        views.thinkingSparkle.visibility = View.VISIBLE
        (views.thinkingSparkle.drawable as? Animatable)?.start()
    }

    fun stopThinkingAnimation() {
        (views.thinkingSparkle.drawable as? Animatable)?.stop()
        views.thinkingSparkle.visibility = View.GONE
    }
}
