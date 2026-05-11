package com.example.myapplication.quiz

import android.content.Context
import android.graphics.drawable.Animatable
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.SkillStats
import com.example.myapplication.dictionary.Vocab
import com.example.myapplication.llm.LlmService
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuizViews(
    val buttonFail: Button,
    val buttonNext: Button,
    val buttonNew: Button,
    val buttonTip: Button,
    val buttonHard: Button,
    val buttonLearned: Button,
    val textFr: TextView,
    val textScore: TextView,
    val textEn: TextView,
    val textGuessLong: TextView,
    val textProgressTotal: TextView,
    val progressBars: Map<Skill, ProgressBar>,
    val progressBarsFinished: Map<Skill, ProgressBar>,
    val thinkingSparkle: ImageView,
)

interface TtsHelper {
    fun speakForeignWord(vocab: Vocab, flush: Boolean = false)
    fun speakEnglishWord(vocab: Vocab)
    /** Speaks [sentence] in the foreign locale, flushing any in-flight speech. */
    fun speakSentence(sentence: CharSequence)
    /**
     * Speaks [sentence] in the foreign locale and suspends until TTS has
     * finished playing it. Replaces the previous CountDownLatch-based pattern
     * (which had a stale-reference race when the latch field was reassigned
     * while a worker was already mid-await).
     */
    suspend fun speakSentenceAndAwait(sentence: CharSequence)
}

/**
 * Drives the quiz flow: which vocab is showing, the tip/reveal/spot-check
 * state machine, and the per-word save-and-advance logic. Lifted out of
 * MainActivity.onCreate so the flow is one readable file instead of a stack
 * of inner functions over class fields.
 */
class QuizController(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val sounds: SoundEffects,
    private val tts: TtsHelper,
    private val vocabDictionary: MyDictionary,
    private val language: Language,
) {
    var currentVocab: Vocab? = null
        private set
    private val recentWords = ArrayDeque<String>()
    private val recentWordsCapacity = 20
    private var startTime: Long = System.currentTimeMillis()
    private var inSpotCheck = false
    private val spotCheckProbability = 0.2

    // Skill being practiced for the currently-displayed word. Set in updateVocab
    // when a new word is picked and used for all stat writes during the round.
    private var currentSkill: Skill = Skill.READ

    // Session-scoped: when false, LISTEN words and their tip sentences are
    // shown unmasked (e.g. the user is on a train without headphones).
    // Toggled via the menu; the next word picks up the new state.
    private var listeningEnabled: Boolean = true

    // Unmasked sentence kept around so the tap-to-re-read handler can speak
    // the original even when [QuizViews.textGuessLong] displays the masked form.
    private var currentSentence: String? = null

    // Snapshot of the active skill's stats at round start. Used to roll back
    // any mid-round mutations if the round is later flagged as compromised
    // (e.g. listening toggled off in a LISTEN round — user could read the
    // foreign word so the round shouldn't count toward LISTEN's stats).
    private var roundStartSnapshot: SkillStats? = null
    private var roundCompromised: Boolean = false

    fun onFailClick() {
        if (inSpotCheck) exitSpotCheck(wasWrong = true)
        else revealAllVocabData(showNextVocab = true)
    }

    fun onNextClick() {
        if (inSpotCheck) {
            exitSpotCheck(wasWrong = false)
        } else if (currentVocab != null && Math.random() < spotCheckProbability) {
            startSpotCheck()
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    fun onNewClick() = updateVocab(0, newCandidates = true)
    fun onTipClick() = showTip()

    /** True when listening practice is active (masking happens for LISTEN words). */
    fun isListeningModeEnabled(): Boolean = listeningEnabled

    /**
     * Toggle session-scoped listening mode and refresh the current word's
     * display. Both [QuizViews.textFr] and [QuizViews.textGuessLong] (if
     * currently visible) are re-rendered so masking stays consistent
     * across the two views.
     */
    fun setListeningModeEnabled(enabled: Boolean) {
        listeningEnabled = enabled
        // Disabling mid-round means the user can now read the foreign word
        // straight off the screen — flag the round so stats don't get credit.
        if (!enabled && currentSkill == Skill.LISTEN) {
            roundCompromised = true
        }
        val vocab = currentVocab ?: return
        displayForeignWord(vocab)
        if (views.textGuessLong.visibility == View.VISIBLE) {
            val sentence = currentSentence ?: return
            val maskNow = currentSkill == Skill.LISTEN && listeningEnabled
            renderSentence(sentence, maskNow)
        }
    }

    fun onHardClick() {
        val vocab = currentVocab ?: return
        vocab.flaggedHard = !vocab.flaggedHard
        vocab.savePreferences()
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
    }

    /** Reset the response timer; call when the activity resumes. */
    fun markResumed() {
        startTime = System.currentTimeMillis()
    }

    fun setupLearnedButton() {
        views.buttonLearned.text = "✓"
        views.buttonLearned.setOnClickListener {
            val vocab = currentVocab ?: return@setOnClickListener
            // First tap reveals everything so the user can verify they
            // actually know the word before committing on the second tap.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            currentSentence?.let { renderSentence(it, mask = false) }
            views.textGuessLong.visibility = View.VISIBLE
            views.buttonLearned.text = "✓?"
            views.buttonLearned.setOnClickListener {
                vocab.ignore = true
                vocab.savePreferences()
                setupLearnedButton()
                updateVocab(0, newCandidates = false)
            }
        }
    }

    private fun showTip() {
        val vocab = currentVocab ?: return
        vocab.stats(currentSkill).nTimesFailed += 0.25f

        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            val sentence = generateAndShowExampleSentence(vocab, maskWhenListening = true)
            tts.speakSentenceAndAwait(sentence)
            delay(1000)
            views.buttonFail.isEnabled = true
            views.buttonFail.isClickable = true
            views.buttonNext.isEnabled = true
            views.buttonNext.isClickable = true
            // Tip stays disabled — one tip per word.
        }
    }

    private fun revealAllVocabData(showNextVocab: Boolean) {
        val vocab = currentVocab ?: return
        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            // User admitted they don't know. Reveal whichever side was hidden
            // for this skill: foreign-was-hidden in INVERT, English-was-hidden
            // in READ / LISTEN. TTS speaks the side that was previously kept
            // out of earshot.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            if (currentSkill == Skill.INVERT) {
                tts.speakForeignWord(vocab, flush = true)
            } else {
                tts.speakEnglishWord(vocab)
            }

            // In a listening round, the user has already been hearing the
            // example sentence (from Tip, masked). Reveal that exact sentence
            // unmasked instead of regenerating — keeps the audio/text
            // consistent. In other rounds, fall through to a fresh LLM call.
            val isListeningRound = currentSkill == Skill.LISTEN && listeningEnabled
            val sentence = generateAndShowExampleSentence(vocab, useExisting = isListeningRound)
            tts.speakSentenceAndAwait(sentence)
            delay(1000)

            if (showNextVocab) {
                updateVocab(2000, newCandidates = false)
                delay(100)
            }
            // setQuizButtonsEnabled honours the per-skill Tip rule, so an
            // INVERT round won't accidentally re-enable the Tip button here.
            setQuizButtonsEnabled(true)
        }
    }

    private fun startSpotCheck() {
        inSpotCheck = true
        if (currentSkill == Skill.INVERT) {
            // English is already on screen as the prompt; reveal the foreign
            // answer so the user can verify their mental production.
            revealCurrentForeignWord()
        } else {
            // READ / LISTEN: foreign is what the user has been working with;
            // show the English meaning so they can verify recall.
            views.textEn.visibility = View.VISIBLE
        }

        views.buttonFail.text = "✗ Wrong"
        views.buttonNext.text = "✓ Correct"
        views.buttonNew.isEnabled = false
        views.buttonNew.isClickable = false
        views.buttonTip.isEnabled = false
        views.buttonTip.isClickable = false

        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        sounds.playSpotCheck()
    }

    private fun exitSpotCheck(wasWrong: Boolean) {
        inSpotCheck = false
        views.buttonFail.text = "I don't know"
        views.buttonNext.text = "Next"
        if (wasWrong) {
            currentVocab?.let { it.stats(currentSkill).nTimesFailed += 1.0f }
            updateVocab(2000, newCandidates = false)
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    fun updateVocab(penalty: Long, newCandidates: Boolean, saveVocab: Boolean = true) {
        if (penalty > 0 && currentVocab != null) {
            currentVocab!!.stats(currentSkill).nTimesFailed += 1.0f
        }
        if (saveVocab) saveCurrentVocab(penalty)

        if (currentVocab == null) {
            val (v, s) = pickNextVocab(newCandidates)
            currentVocab = v
            currentSkill = s
        }
        val previousVocabFrench = currentVocab!!.french
        var attempts = 0
        while (currentVocab!!.french == previousVocabFrench && attempts < 50) {
            val (v, s) = pickNextVocab(newCandidates)
            currentVocab = v
            currentSkill = s
            attempts++
        }

        val vocab = currentVocab!!
        // Snapshot the active skill's stats so we can roll back if the round
        // turns out to be compromised. A LISTEN round started with listening
        // already off counts as compromised from the start.
        roundStartSnapshot = vocab.stats(currentSkill).copy()
        roundCompromised = currentSkill == Skill.LISTEN && !listeningEnabled
        inSpotCheck = false
        views.buttonFail.text = "I don't know"
        views.buttonNext.text = "Next"
        val total = vocabDictionary.csvData.size
        val totalF = total.toFloat()
        for (skill in Skill.ladder) {
            val introduced = vocabDictionary.getActiveDataSize(skill)
            val skillFinished = vocabDictionary.getFinishedDataSize(skill)
            views.progressBars[skill]?.progress = (introduced / totalF * 100).toInt()
            views.progressBarsFinished[skill]?.progress = (skillFinished / totalF * 100).toInt()
        }
        val currentStats = vocab.stats(currentSkill)
        views.textScore.text = if (currentStats.meanTimeViewedMilli() == (10 * 1e3)) "A new word!"
            else currentStats.getInfoString()
        setQuizButtonsEnabled(true)

        // Single readout above the stack: how many words have been introduced
        // via the first ladder skill (READ). Other skills' counts are conveyed
        // visually by the stacked bar lengths. The label is positioned over
        // the right edge of READ's progress fill so it tracks visually.
        val readSkill = Skill.ladder.first()
        val readIntroduced = vocabDictionary.getActiveDataSize(readSkill)
        views.textProgressTotal.text = readIntroduced.toString()
        val readBar = views.progressBars[readSkill]
        readBar?.post {
            val barWidth = readBar.width
            val labelWidth = views.textProgressTotal.width
            val progressFraction = readIntroduced / totalF
            val targetX = progressFraction * barWidth - labelWidth / 2f
            val maxX = (barWidth - labelWidth).coerceAtLeast(0).toFloat()
            views.textProgressTotal.translationX = targetX.coerceIn(0f, maxX)
        }
        displayForeignWord(vocab)
        // INVERT shows the English as the prompt; other skills keep it hidden
        // until reveal / spot check.
        views.textEn.visibility = if (currentSkill == Skill.INVERT) View.VISIBLE else View.INVISIBLE
        views.textEn.text = vocab.english
        views.textGuessLong.visibility = View.INVISIBLE
        // Seed the per-word sentence cache with the CSV fallback so a reveal
        // (Fail) without a prior Tip still has something to display without
        // burning an LLM call.
        val seedSentence = vocab.getSomeFrenchLong()
        views.textGuessLong.text = seedSentence
        views.textGuessLong.setOnClickListener(null)
        views.textGuessLong.isClickable = false
        currentSentence = seedSentence
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
        setupLearnedButton()

        startTime = System.currentTimeMillis()
        // In INVERT the foreign word is the answer the user has to produce, so
        // speak the English prompt instead; other skills speak the foreign word.
        if (currentSkill == Skill.INVERT) {
            tts.speakEnglishWord(vocab)
        } else {
            tts.speakForeignWord(vocab, flush = true)
        }
    }

    private fun pickNextVocab(newCandidates: Boolean): Pair<Vocab, Skill> =
        if (newCandidates) vocabDictionary.getInactiveVocab()
        else vocabDictionary.getActiveVocabWeightened()

    private fun saveCurrentVocab(penalty: Long) {
        val vocab = currentVocab ?: return

        // Always update recent-words: the user has seen this word regardless
        // of whether the round counts, and we don't want to surface it again
        // back-to-back.
        val justFinished = vocab.french
        recentWords.remove(justFinished)
        recentWords.addLast(justFinished)
        while (recentWords.size > recentWordsCapacity) {
            recentWords.removeFirst()
        }

        val snapshot = roundStartSnapshot
        if (roundCompromised && snapshot != null) {
            // Listening was off at some point during a LISTEN round — the
            // user could read the foreign word, so roll back any in-memory
            // mutations from the round and persist the rolled-back state.
            val stats = vocab.stats(currentSkill)
            stats.viewTimeMilli = snapshot.viewTimeMilli
            stats.viewTimeMilli_prev = snapshot.viewTimeMilli_prev
            stats.nTimesViewed = snapshot.nTimesViewed
            stats.nTimesFailed = snapshot.nTimesFailed
            stats.lastDisplayed = snapshot.lastDisplayed
            vocab.savePreferences()
            return
        }

        val endTime = System.currentTimeMillis()
        val timeElapsed = endTime - startTime + penalty

        val stats = vocab.stats(currentSkill)
        val prevFailureProbability = stats.failureProbability()

        stats.nTimesViewed += 1
        val alpha = 0.3
        stats.viewTimeMilli = (alpha * timeElapsed + (1.0 - alpha) * stats.viewTimeMilli).toLong()
        stats.lastDisplayed = System.currentTimeMillis()
        vocab.savePreferences()

        if (stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD &&
            prevFailureProbability >= Vocab.SKILL_FINISHED_THRESHOLD
        ) {
            sounds.playSuccess()
        }
    }

    /**
     * Generates (or falls back to) the example sentence, displays it in
     * [QuizViews.textGuessLong], and returns the unmasked text so the caller
     * can hand it to TTS. When [maskWhenListening] is true and the current
     * round is a listening one, the on-screen text is replaced with bullets
     * — the user must rely on audio rather than reading the word inside the
     * sentence. When [useExisting] is true (reveal flow), the cached
     * [currentSentence] is reused instead of calling the LLM again.
     */
    private suspend fun generateAndShowExampleSentence(
        vocab: Vocab,
        maskWhenListening: Boolean = false,
        useExisting: Boolean = false,
    ): String {
        val sentence: String = when {
            useExisting -> currentSentence ?: vocab.getSomeFrenchLong()
            LlmService.isReady -> {
                views.textGuessLong.visibility = View.INVISIBLE
                startThinkingAnimation()
                val generated = try {
                    LlmService.generate(
                        word = vocab.french,
                        translation = vocab.english,
                        recent = recentWords.toList(),
                        language = language,
                    )
                } finally {
                    stopThinkingAnimation()
                }
                generated ?: vocab.getSomeFrenchLong()
            }
            else -> views.textGuessLong.text.toString()
        }
        currentSentence = sentence
        val maskNow = currentSkill == Skill.LISTEN && listeningEnabled && maskWhenListening
        renderSentence(sentence, maskNow)
        views.textGuessLong.alpha = 0f
        views.textGuessLong.visibility = View.VISIBLE
        views.textGuessLong.animate().alpha(1f).setDuration(220).start()
        return sentence
    }

    /** Sets [QuizViews.textGuessLong]'s text and tap-handler based on [mask]. */
    private fun renderSentence(sentence: String, mask: Boolean) {
        if (mask) {
            views.textGuessLong.text = maskSentence(sentence)
            views.textGuessLong.setOnClickListener { onMaskedSentenceTapped() }
            views.textGuessLong.isClickable = true
        } else {
            views.textGuessLong.text = sentence
            views.textGuessLong.setOnClickListener(null)
            views.textGuessLong.isClickable = false
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

    private fun setQuizButtonsEnabled(enabled: Boolean) {
        views.buttonFail.isEnabled = enabled
        views.buttonFail.isClickable = enabled
        views.buttonNext.isEnabled = enabled
        views.buttonNext.isClickable = enabled
        views.buttonNew.isEnabled = enabled
        views.buttonNew.isClickable = enabled
        // Tip would normally show an example sentence containing the foreign
        // word, which gives away the answer in an INVERT round. Keep it
        // disabled regardless of the [enabled] flag while inverting.
        val tipApplicable = enabled && currentSkill != Skill.INVERT
        views.buttonTip.isEnabled = tipApplicable
        views.buttonTip.isClickable = tipApplicable
    }

    private fun displayForeignWord(vocab: Vocab) {
        val isListeningRound = currentSkill == Skill.LISTEN && listeningEnabled
        val isInvertRound = currentSkill == Skill.INVERT
        when {
            isInvertRound -> {
                // English is the prompt; the foreign word is what the user must
                // produce. Show a "?" placeholder rather than the word itself
                // and don't leak word length.
                views.textFr.text = "?"
                views.textFr.setOnClickListener(null)
                views.textFr.isClickable = false
            }
            isListeningRound -> {
                views.textFr.text = maskWord(vocab.french)
                views.textFr.setOnClickListener { onMaskedWordTapped() }
                views.textFr.isClickable = true
            }
            else -> {
                views.textFr.text = vocab.french
                views.textFr.setOnClickListener(null)
                views.textFr.isClickable = false
            }
        }
    }

    private fun maskWord(word: String): String =
        word.map { if (it.isLetter()) '⬤' else it }.joinToString("")

    /** Replay the foreign word (with a small penalty) when the mask is tapped. */
    private fun onMaskedWordTapped() {
        val vocab = currentVocab ?: return
        vocab.stats(currentSkill).nTimesFailed += 0.1f
        tts.speakForeignWord(vocab, flush = true)
    }

    /** Replay the example sentence (with a small penalty) when its mask is tapped. */
    private fun onMaskedSentenceTapped() {
        val vocab = currentVocab ?: return
        val sentence = currentSentence ?: return
        vocab.stats(currentSkill).nTimesFailed += 0.1f
        tts.speakSentence(sentence)
    }

    /**
     * Called by [revealAllVocabData] when the user gives up: unmask the
     * foreign word and detach its tap-to-replay handler.
     */
    private fun revealCurrentForeignWord() {
        val vocab = currentVocab ?: return
        views.textFr.text = vocab.french
        views.textFr.setOnClickListener(null)
        views.textFr.isClickable = false
    }
}
