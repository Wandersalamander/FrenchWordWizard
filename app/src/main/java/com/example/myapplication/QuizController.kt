package com.example.myapplication

import android.content.Context
import android.graphics.drawable.Animatable
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
    val textProgressFinished: TextView,
    val textProgressActive: TextView,
    val textProgressUnseen: TextView,
    val progressBar: ProgressBar,
    val progressBarFinished: ProgressBar,
    val thinkingSparkle: ImageView,
)

interface TtsHelper {
    fun speakForeignWord(vocab: Vocab, flush: Boolean = false)
    fun speakEnglishWord(vocab: Vocab)
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
            views.textEn.visibility = View.VISIBLE
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
        vocab.nTimesFailed += 0.25f

        setQuizButtonsEnabled(false)

        activity.lifecycleScope.launch {
            generateAndShowExampleSentence(vocab)
            val sentence = views.textGuessLong.text
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
            // User admitted they don't know — reveal the English and speak it
            // right away, in parallel with the LLM example-sentence generation.
            views.textEn.visibility = View.VISIBLE
            tts.speakEnglishWord(vocab)

            // Regenerate example sentence with the LLM (falls back to CSV in
            // generateAndShowExampleSentence on timeout/failure).
            generateAndShowExampleSentence(vocab)

            val sentence = views.textGuessLong.text
            // TTS queues this after the English word — by the time the LLM
            // finishes, the English may already have played and the sentence
            // plays immediately; otherwise it follows naturally.
            tts.speakSentenceAndAwait(sentence)
            delay(1000)

            if (showNextVocab) {
                updateVocab(2000, newCandidates = false)
                delay(100)
            }

            views.buttonFail.isEnabled = true
            views.buttonFail.isClickable = true
            views.buttonNext.isEnabled = true
            views.buttonNext.isClickable = true
            views.buttonTip.isEnabled = true
            views.buttonTip.isClickable = true
        }
    }

    private fun startSpotCheck() {
        inSpotCheck = true
        views.textEn.visibility = View.VISIBLE

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
            currentVocab?.nTimesFailed = (currentVocab?.nTimesFailed ?: 0f) + 1.0f
            updateVocab(2000, newCandidates = false)
        } else {
            updateVocab(0, newCandidates = false)
        }
    }

    fun updateVocab(penalty: Long, newCandidates: Boolean, saveVocab: Boolean = true) {
        if (penalty > 0 && currentVocab != null) {
            currentVocab!!.nTimesFailed += 1.0f
        }
        if (saveVocab) saveCurrentVocab(penalty)

        if (currentVocab == null) {
            currentVocab = pickNextVocab(newCandidates)
        }
        val previousVocabFrench = currentVocab!!.french
        var attempts = 0
        while (currentVocab!!.french == previousVocabFrench && attempts < 50) {
            currentVocab = pickNextVocab(newCandidates)
            attempts++
        }

        val vocab = currentVocab!!
        inSpotCheck = false
        views.buttonFail.text = "I don't know"
        views.buttonNext.text = "Next"
        val totalSize = vocabDictionary.csvData.size.toFloat()
        views.progressBar.progress = (vocabDictionary.getActiveDataSize().toFloat() / totalSize * 100).toInt()
        views.progressBarFinished.progress = (vocabDictionary.getFinishedDataSize().toFloat() / totalSize * 100).toInt()
        views.textScore.text = if (vocab.meanTimeViewedMilli() == (10 * 1e3)) "A new word!"
            else vocab.getInfoString()
        setQuizButtonsEnabled(true)

        val total = vocabDictionary.csvData.size
        val finished = vocabDictionary.getFinishedDataSize()
        val active = vocabDictionary.getActiveDataSize() - finished
        val unseen = total - vocabDictionary.getActiveDataSize()
        views.textProgressFinished.text = finished.toString()
        views.textProgressActive.text = active.toString()
        views.textProgressUnseen.text = unseen.toString()
        views.progressBar.post {
            val totalF = total.toFloat()
            val finishedFraction = finished.toFloat() / totalF
            val seenFraction = vocabDictionary.getActiveDataSize().toFloat() / totalF
            val barWidth = views.progressBar.width
            val center = (finishedFraction + seenFraction) / 2f * barWidth
            views.textProgressActive.translationX = center - views.textProgressActive.width / 2f
        }
        views.textFr.text = vocab.french
        views.textEn.visibility = View.INVISIBLE
        views.textEn.text = vocab.english
        views.textGuessLong.visibility = View.INVISIBLE
        views.textGuessLong.text = vocab.getSomeFrenchLong()
        views.buttonHard.text = if (vocab.flaggedHard) "⚑!" else "⚑"
        setupLearnedButton()

        startTime = System.currentTimeMillis()
        tts.speakForeignWord(vocab, flush = true)
    }

    private fun pickNextVocab(newCandidates: Boolean): Vocab =
        if (newCandidates) vocabDictionary.getInactiveVocab()
        else vocabDictionary.getActiveVocabWeightened()

    private fun saveCurrentVocab(penalty: Long) {
        val vocab = currentVocab ?: return
        val endTime = System.currentTimeMillis()
        val timeElapsed = endTime - startTime + penalty

        val prevFailureProbability = vocab.failureProbability()

        vocab.nTimesViewed += 1
        val alpha = 0.3
        vocab.viewTimeMilli = (alpha * timeElapsed + (1.0 - alpha) * vocab.viewTimeMilli).toLong()
        vocab.lastDisplayed = System.currentTimeMillis()
        vocab.savePreferences()

        val justFinished = vocab.french
        recentWords.remove(justFinished)
        recentWords.addLast(justFinished)
        while (recentWords.size > recentWordsCapacity) {
            recentWords.removeFirst()
        }

        if (vocab.failureProbability() < 0.1 && prevFailureProbability >= 0.1) {
            sounds.playSuccess()
        }
    }

    private suspend fun generateAndShowExampleSentence(vocab: Vocab) {
        if (LlmService.isReady) {
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
            views.textGuessLong.text = generated ?: vocab.getSomeFrenchLong()
            views.textGuessLong.alpha = 0f
            views.textGuessLong.visibility = View.VISIBLE
            views.textGuessLong.animate().alpha(1f).setDuration(220).start()
        } else {
            views.textGuessLong.visibility = View.VISIBLE
        }
    }

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
        views.buttonTip.isEnabled = enabled
        views.buttonTip.isClickable = enabled
    }
}
