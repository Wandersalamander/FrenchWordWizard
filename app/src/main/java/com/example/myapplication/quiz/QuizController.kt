package com.example.myapplication.quiz

import android.content.Context
import android.graphics.drawable.Animatable
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.SentenceSource
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.SkillStats
import com.example.myapplication.dictionary.Vocab
import com.example.myapplication.llm.LlmService
import com.example.myapplication.setDebouncedOnClickListener
import com.example.myapplication.streak.StreakTracker
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuizViews(
    val buttonFail: Button,
    val buttonNext: Button,
    val buttonNew: Button,
    val buttonTip: Button,
    val buttonHard: Button,
    val buttonLearned: Button,
    val textForeign: TextView,
    val textScore: TextView,
    val textEn: TextView,
    val textGuessLong: TextView,
    val textProgressTotal: TextView,
    val textStreak: TextView,
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

private const val SPOT_CHECK_PROBABILITY = 0.2
private const val RECENT_WORDS_CAPACITY = 20
private const val VIEW_TIME_EMA_ALPHA = 0.3
private const val WRONG_VOCAB_PICK_ATTEMPTS = 50
private const val SENTENCE_FADE_IN_DURATION_MS = 220L

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
    private var startTime: Long = System.currentTimeMillis()
    private var inSpotCheck = false

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

    // Number of letters already revealed via Tip in the current round; only
    // meaningful when the active skill has ProgressiveReveal tip behavior.
    private var progressiveRevealCount: Int = 0

    fun onFailClick() {
        if (inSpotCheck) exitSpotCheck(wasWrong = true)
        else revealAllVocabData(showNextVocab = true)
    }

    fun onNextClick() {
        if (inSpotCheck) {
            exitSpotCheck(wasWrong = false)
        } else if (currentVocab != null && Random.nextDouble() < SPOT_CHECK_PROBABILITY) {
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
     * display. Both [QuizViews.textForeign] and [QuizViews.textGuessLong] (if
     * currently visible) are re-rendered so masking stays consistent
     * across the two views.
     */
    fun setListeningModeEnabled(enabled: Boolean) {
        listeningEnabled = enabled
        // If the new state compromises the current round, latch the flag so
        // toggling back on later doesn't un-void what the user already saw.
        if (currentSkill.flow.isCompromisedByListening(enabled)) {
            roundCompromised = true
        }
        // Listening turned off mid-LISTEN-round: discard the current word and
        // pick a new (non-LISTEN) one. updateVocab honours roundCompromised so
        // the LISTEN stats are rolled back before advancing.
        if (!enabled && currentSkill == Skill.LISTEN && currentVocab != null) {
            updateVocab(0, newCandidates = false)
            return
        }
        val vocab = currentVocab ?: return
        displayForeignWord(vocab)
        if (views.textGuessLong.visibility == View.VISIBLE) {
            val sentence = currentSentence ?: return
            renderSentence(sentence, currentSkill.flow.shouldMaskSentenceOnTip(listeningEnabled))
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
        views.buttonLearned.setDebouncedOnClickListener {
            val vocab = currentVocab ?: return@setDebouncedOnClickListener
            // First tap reveals everything so the user can verify they
            // actually know the word before committing on the second tap.
            revealCurrentForeignWord()
            views.textEn.visibility = View.VISIBLE
            currentSentence?.let { renderSentence(it, mask = false) }
            views.textGuessLong.visibility = View.VISIBLE
            views.buttonLearned.text = "✓?"
            views.buttonLearned.setDebouncedOnClickListener {
                vocab.ignore = true
                vocab.savePreferences()
                setupLearnedButton()
                updateVocab(0, newCandidates = false)
            }
        }
    }

    private fun showTip() {
        val vocab = currentVocab ?: return
        when (currentSkill.flow.tipBehavior) {
            TipBehavior.Sentence -> showSentenceTip(vocab)
            TipBehavior.ProgressiveReveal -> showProgressiveRevealTip(vocab)
            TipBehavior.None -> Unit
        }
    }

    private fun showSentenceTip(vocab: Vocab) {
        vocab.stats(currentSkill).recordFailure(0.25f)

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

    private fun showProgressiveRevealTip(vocab: Vocab) {
        val letterCount = vocab.foreign.count { it.isLetter() }
        if (progressiveRevealCount >= letterCount) return
        progressiveRevealCount++
        // Triangular weighting: the i-th of N reveals costs
        // 2(N - i + 1) / (N(N+1)), so first reveal is the most expensive and
        // the total over a full unmask is exactly 1.0 (≈ one Fail).
        val penalty = 2f * (letterCount - progressiveRevealCount + 1) /
            (letterCount * (letterCount + 1))
        vocab.stats(currentSkill).recordFailure(penalty)
        currentSkill.flow.applyProgressiveReveal(vocab, views, progressiveRevealCount)
        if (progressiveRevealCount >= letterCount) {
            views.buttonTip.isEnabled = false
            views.buttonTip.isClickable = false
            onFailClick()
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
            currentSkill.flow.ttsOnReveal(vocab, tts)

            // Some skills (LISTEN with listening on) reveal the cached sentence
            // for consistency with what the user just heard; others regenerate.
            val useExisting = currentSkill.flow.reuseSentenceOnReveal(listeningEnabled)
            val sentence = generateAndShowExampleSentence(vocab, useExisting = useExisting)
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
        currentSkill.flow.setupSpotCheck(views, ::revealCurrentForeignWord)

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
            currentVocab?.let { it.stats(currentSkill).recordFailure(1.0f) }
            updateVocab(2000, newCandidates = false)
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    fun updateVocab(penalty: Long, newCandidates: Boolean, saveVocab: Boolean = true) {
        applyFailurePenaltyAndPersist(penalty, saveVocab)
        val vocab = advanceToNextDistinctVocab(newCandidates)

        resetRoundState(vocab)
        refreshAllProgressBars()
        refreshStreakBadge()
        renderScoreText(vocab)
        setQuizButtonsEnabled(true)
        positionTotalLabel()

        displayForeignWord(vocab)
        views.textEn.visibility =
            if (currentSkill.flow.englishIsPrompt) View.VISIBLE else View.INVISIBLE
        views.textEn.text = vocab.english
        views.textGuessLong.visibility = View.INVISIBLE
        seedSentenceCache(vocab)
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
        setupLearnedButton()

        startTime = System.currentTimeMillis()
        currentSkill.flow.ttsAtRoundStart(vocab, tts)
    }

    private fun applyFailurePenaltyAndPersist(penalty: Long, saveVocab: Boolean) {
        if (penalty > 0) {
            currentVocab?.let { it.stats(currentSkill).recordFailure(1.0f) }
        }
        if (saveVocab) saveCurrentVocab(penalty)
    }

    /**
     * Pick a new vocab/skill pair, retrying briefly to avoid repeating the
     * same foreign word back-to-back. Updates [currentVocab] and [currentSkill]
     * in place and returns the new vocab non-null.
     */
    private fun advanceToNextDistinctVocab(newCandidates: Boolean): Vocab {
        if (currentVocab == null) {
            val (v, s) = pickNextVocab(newCandidates)
            currentVocab = v
            currentSkill = s
        }
        val previousForeign = currentVocab!!.foreign
        var attempts = 0
        while (currentVocab!!.foreign == previousForeign && attempts < WRONG_VOCAB_PICK_ATTEMPTS) {
            val (v, s) = pickNextVocab(newCandidates)
            currentVocab = v
            currentSkill = s
            attempts++
        }
        return currentVocab!!
    }

    private fun resetRoundState(vocab: Vocab) {
        // Snapshot the active skill's stats so we can roll back if the round
        // turns out to be compromised (e.g. a LISTEN round started with
        // listening already off — the user could read the foreign word).
        roundStartSnapshot = vocab.stats(currentSkill).copy()
        roundCompromised = currentSkill.flow.isCompromisedByListening(listeningEnabled)
        progressiveRevealCount = 0
        inSpotCheck = false
        views.buttonFail.text = "I don't know"
        views.buttonNext.text = "Next"
    }

    private fun refreshAllProgressBars() {
        val total = vocabDictionary.csvData.size
        if (total <= 0) return
        val totalF = total.toFloat()
        for (skill in Skill.ladder) {
            val introduced = vocabDictionary.getActiveDataSize(skill)
            val finished = vocabDictionary.getFinishedDataSize(skill)
            views.progressBars[skill]?.progress = (introduced / totalF * 100).toInt()
            views.progressBarsFinished[skill]?.progress = (finished / totalF * 100).toInt()
        }
    }

    private fun renderScoreText(vocab: Vocab) {
        val currentStats = vocab.stats(currentSkill)
        views.textScore.text = if (currentStats.nTimesViewed == 0) {
            if (currentSkill == Skill.ladder.first()) "A new word!"
            else "${currentSkill.displayName} exercise unlocked!"
        } else currentStats.getInfoString()
    }

    /**
     * Single readout above the stack: how many words have been introduced
     * via the first ladder skill (READ). The label is positioned over
     * the right edge of READ's progress fill so it tracks visually.
     */
    private fun positionTotalLabel() {
        val total = vocabDictionary.csvData.size
        if (total <= 0) return
        val readSkill = Skill.ladder.first()
        val readIntroduced = vocabDictionary.getActiveDataSize(readSkill)
        views.textProgressTotal.text = readIntroduced.toString()
        val readBar = views.progressBars[readSkill] ?: return
        readBar.post {
            val barWidth = readBar.width
            val labelWidth = views.textProgressTotal.width
            val progressFraction = readIntroduced / total.toFloat()
            val targetX = progressFraction * barWidth - labelWidth / 2f
            val maxX = (barWidth - labelWidth).coerceAtLeast(0).toFloat()
            views.textProgressTotal.translationX = targetX.coerceIn(0f, maxX)
        }
    }

    /**
     * Seed the per-word sentence cache with the CSV fallback so a reveal
     * (Fail) without a prior Tip still has something to display without
     * burning an LLM call. Honour the user's easy/hard preference so the
     * seed matches what they'd see if Tip were pressed.
     */
    private fun seedSentenceCache(vocab: Vocab) {
        val seed = vocab.csvSentenceFor(SentenceSource.fromContext(activity))
        views.textGuessLong.text = seed
        views.textGuessLong.setOnClickListener(null)
        views.textGuessLong.isClickable = false
        currentSentence = seed
    }

    private fun pickNextVocab(newCandidates: Boolean): Pair<Vocab, Skill> {
        // When listening is off the LISTEN skill is excluded from the pool —
        // getInactiveVocab always returns READ so it doesn't need the filter.
        val skillFilter: (Skill) -> Boolean =
            if (listeningEnabled) ({ true }) else ({ it != Skill.LISTEN })
        return if (newCandidates) vocabDictionary.getInactiveVocab()
        else vocabDictionary.getActiveVocabWeighted(skillFilter)
    }

    private fun saveCurrentVocab(penalty: Long) {
        val vocab = currentVocab ?: return

        // Always update recent-words: the user has seen this word regardless
        // of whether the round counts, and we don't want to surface it again
        // back-to-back.
        rememberRecentWord(vocab.foreign)

        val snapshot = roundStartSnapshot
        if (roundCompromised && snapshot != null) {
            // Listening was off at some point during a LISTEN round — the
            // user could read the foreign word, so roll back any in-memory
            // mutations from the round and persist the rolled-back state.
            vocab.stats(currentSkill).copyFrom(snapshot)
            vocab.savePreferences()
            return
        }

        val timeElapsed = System.currentTimeMillis() - startTime + penalty
        val stats = vocab.stats(currentSkill)
        val prevFailureProbability = stats.failureProbability()

        stats.nTimesViewed += 1
        stats.viewTimeMilli =
            (VIEW_TIME_EMA_ALPHA * timeElapsed + (1.0 - VIEW_TIME_EMA_ALPHA) * stats.viewTimeMilli).toLong()
        stats.lastDisplayed = System.currentTimeMillis()
        vocab.savePreferences()

        // A committed (non-compromised) round is what counts for the daily
        // streak. Idempotent within a day — first round of the day bumps the
        // count, subsequent rounds are no-ops.
        StreakTracker.recordPracticeToday(activity.applicationContext)

        if (stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD &&
            prevFailureProbability >= Vocab.SKILL_FINISHED_THRESHOLD
        ) {
            sounds.playSuccess()
        }
    }

    private fun rememberRecentWord(word: String) {
        recentWords.remove(word)
        recentWords.addLast(word)
        while (recentWords.size > RECENT_WORDS_CAPACITY) {
            recentWords.removeFirst()
        }
    }

    /**
     * Refresh the streak badge from persistent state. Cheap: just a prefs
     * read + LocalDate comparison. Public so MainActivity.onResume can poke it
     * after returning from Settings (where the user may have toggled or reset
     * the streak indirectly via reminder changes).
     */
    fun refreshStreakBadge() {
        val streak = StreakTracker.currentStreak(activity.applicationContext)
        views.textStreak.text = if (streak > 0) "🔥 $streak" else ""
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
        val source = SentenceSource.fromContext(activity)
        val sentence: String = when {
            useExisting -> currentSentence ?: vocab.csvSentenceFor(source)
            source == SentenceSource.LLM && LlmService.isReady -> generateFromLlmOrFallback(vocab, source)
            else -> vocab.csvSentenceFor(source)
        }
        currentSentence = sentence
        val maskNow = maskWhenListening &&
            currentSkill.flow.shouldMaskSentenceOnTip(listeningEnabled)
        renderSentence(sentence, maskNow)
        views.textGuessLong.alpha = 0f
        views.textGuessLong.visibility = View.VISIBLE
        views.textGuessLong.animate().alpha(1f).setDuration(SENTENCE_FADE_IN_DURATION_MS).start()
        return sentence
    }

    private suspend fun generateFromLlmOrFallback(vocab: Vocab, source: SentenceSource): String {
        views.textGuessLong.visibility = View.INVISIBLE
        startThinkingAnimation()
        val generated = try {
            LlmService.generate(
                word = vocab.foreign,
                translation = vocab.english,
                recent = recentWords.toList(),
                language = language,
            )
        } finally {
            stopThinkingAnimation()
        }
        return generated ?: vocab.csvSentenceFor(source)
    }

    /** Sets [QuizViews.textGuessLong]'s text and tap-handler based on [mask]. */
    private fun renderSentence(sentence: String, mask: Boolean) {
        if (mask) {
            views.textGuessLong.text = maskSentence(sentence)
            views.textGuessLong.setDebouncedOnClickListener { onMaskedSentenceTapped() }
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
        // Tip's applicability is skill-dependent — e.g. INVERT disables it
        // because the example sentence would contain the answer word.
        val tipApplicable = enabled && currentSkill.flow.tipAllowed
        views.buttonTip.isEnabled = tipApplicable
        views.buttonTip.isClickable = tipApplicable
    }

    private fun displayForeignWord(vocab: Vocab) {
        currentSkill.flow.setupForeignText(
            vocab,
            views,
            listeningEnabled,
            ::onMaskedWordTapped,
            ::onWordReplayed,
        )
    }

    /** Replay the foreign word (with a small penalty) when the mask is tapped. */
    private fun onMaskedWordTapped() {
        val vocab = currentVocab ?: return
        vocab.stats(currentSkill).recordFailure(0.1f)
        tts.speakForeignWord(vocab, flush = true)
    }

    /**
     * Replay the foreign word when the user taps the already-visible word —
     * e.g. in READ, or in LISTEN with listening disabled. No penalty: the
     * answer was on screen anyway, the tap is just a "say it again" convenience.
     */
    private fun onWordReplayed() {
        val vocab = currentVocab ?: return
        tts.speakForeignWord(vocab, flush = true)
    }

    /** Replay the example sentence (with a small penalty) when its mask is tapped. */
    private fun onMaskedSentenceTapped() {
        val vocab = currentVocab ?: return
        val sentence = currentSentence ?: return
        vocab.stats(currentSkill).recordFailure(0.1f)
        tts.speakSentence(sentence)
    }

    /**
     * Called by [revealAllVocabData] when the user gives up: unmask the
     * foreign word and detach its tap-to-replay handler.
     */
    private fun revealCurrentForeignWord() {
        val vocab = currentVocab ?: return
        views.textForeign.text = vocab.foreign
        views.textForeign.setOnClickListener(null)
        views.textForeign.isClickable = false
    }
}

/** Copy every field from [other] into the receiver, in place. */
private fun SkillStats.copyFrom(other: SkillStats) {
    viewTimeMilli = other.viewTimeMilli
    viewTimeMilli_prev = other.viewTimeMilli_prev
    nTimesViewed = other.nTimesViewed
    nTimesFailed = other.nTimesFailed
    lastDisplayed = other.lastDisplayed
    lastTimeFailed = other.lastTimeFailed
}
