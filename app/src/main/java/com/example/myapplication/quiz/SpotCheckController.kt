package com.example.myapplication.quiz

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.Vocab

/**
 * Drives the spot-check verification mode: the card reframes itself
 * ("SELF-CHECK · DID YOU KNOW IT?"), the buttons relabel to ✓ Correct /
 * ✗ Wrong, the answer is revealed, and a vibration + sound + TTS replay
 * confirms the mode change. Owns just the [isActive] flag and the frozen
 * view-time captured at entry — the round-state owner (QuizController) reads
 * those to dispatch button clicks and to score the committed round.
 */
internal class SpotCheckController(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val sounds: SoundEffects,
    private val tts: TtsHelper,
    private val revealForeignWord: () -> Unit,
) {
    var isActive: Boolean = false
        private set

    /**
     * Round view time captured at the moment the user committed their answer
     * (pressed Know-it). The spot-check response that follows is verification,
     * not time-on-card, so its thinking time must not feed into viewTimeMilli.
     * Null when no spot check is in progress.
     */
    var frozenViewTimeMs: Long? = null
        private set

    /**
     * Enter spot-check mode. [viewTimeMs] is the elapsed round time at the
     * moment Know-it was pressed; it's stashed in [frozenViewTimeMs] so the
     * subsequent ✓/✗ response doesn't extend it.
     */
    fun start(vocab: Vocab, skill: Skill, viewTimeMs: Long) {
        frozenViewTimeMs = viewTimeMs
        isActive = true
        skill.flow.setupSpotCheck(views, revealForeignWord)

        // Reframes the card: the shown translation is ground truth, the user
        // is rating themselves. Without this title "✗ Wrong / ✓ Correct"
        // reads as a judgement on the translation.
        views.textVocabEyebrow.text = "SELF-CHECK · DID YOU KNOW IT?"
        views.buttonFail.text = "✗ Wrong"
        views.buttonNext.text = "✓ Correct"
        views.buttonNew.isEnabled = false
        views.buttonNew.isClickable = false
        views.buttonTip.isEnabled = false
        views.buttonTip.isClickable = false
        views.cardVocab.pullEnabled = false

        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))

        sounds.playSpotCheck()

        // Replay the foreign pronunciation so the user can verify they had
        // the sound right, not just the meaning.
        tts.speakForeignWord(vocab, flush = true)

        maybeShowTutorial()
    }

    /**
     * Restore the card's normal labels and clear spot-check state. Caller
     * is responsible for advancing the round (with or without penalty).
     */
    fun exit() {
        reset()
        views.textVocabEyebrow.text = "WORD"
        views.buttonFail.text = "✗ Reveal"
        views.buttonNext.text = "✓ Know it"
    }

    /**
     * Clear state without touching the UI. Called from QuizController on
     * every new round so spot-check state doesn't leak across rounds even
     * when the new round was entered without an explicit [exit] call.
     */
    fun reset() {
        isActive = false
        frozenViewTimeMs = null
    }

    /**
     * First-spot-check explainer. The mode change (buttons relabel to ✓/✗,
     * answer reveals, vibration + chime fire) is genuinely surprising the
     * first time it triggers — without an explanation new users don't know
     * whether the buttons still mean what they did a moment ago. Skipped
     * when a screen reader is active (same focus-trap rationale as the main
     * TapTargetView tutorial).
     */
    private fun maybeShowTutorial() {
        val prefs = AppPrefs.get(activity)
        if (prefs.getBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, false)) return
        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isTouchExplorationEnabled) {
            prefs.edit().putBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, true).apply()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Spot check!")
            .setMessage(
                "Every now and then, the app pauses to verify you actually knew the word. " +
                    "The answer is revealed above.\n\n" +
                    "Tap ✓ Correct if you got it right, or ✗ Wrong if not."
            )
            .setPositiveButton("Got it", null)
            .show()
        prefs.edit().putBoolean(AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN, true).apply()
    }
}
