package io.github.wandersalamander.vocabtrainer.quiz

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.getkeepsafe.taptargetview.TapTargetView
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs

/**
 * Round-flow events the quiz layer raises so the host can drive just-in-time
 * coaching. Every method has a no-op default, so [QuizController] can take this
 * as a defaulted constructor argument and tests / headless construction supply
 * nothing. Only [CoachMarks] (built by MainActivity, which has the views + an
 * Activity to overlay) does anything with them.
 */
interface CoachEvents {
    /** The level check (calibration) just started; first probe word is on screen. */
    fun onCalibrationStarted() {}
    /** The first real drill word is on screen (after calibration hands off). */
    fun onFirstRealWord() {}
    /** The user seems stuck on a hintable word (dwell) — offer the Hint + Flag tips. */
    fun onHintAvailable() {}
    /** The user pressed Hint on their own — suppress the hint tip for good. */
    fun onHintUsed() {}
    /** A round committed cleanly; ticks the coach-mark pacing counter. */
    fun onRoundCommitted() {}
    /** The user is cruising a small pool (same words cycling) — suggest widening it. */
    fun onPoolStale() {}
}

/**
 * Just-in-time onboarding: instead of the old nine-target TapTargetView tour
 * that fired the instant the first word loaded, each control introduces itself
 * with a single coach mark the first time it actually becomes relevant. The
 * result is roughly one tip in the first minute (the core loop) instead of a
 * firehose covering every button on a dense screen at once.
 *
 * Pacing rules that keep it gentle:
 * - **Primary marks first.** Two "what to press" marks — calibration (at test
 *   start) and the core ✓/✗ loop (at drilling start) — bypass the gates below.
 *   No *secondary* control (hint / tools / streak) coaches until the core loop
 *   has been introduced, so the first tip in each phase is "here's how to answer".
 * - **No stacking.** A [MIN_ROUND_GAP]-round cooldown between marks means two
 *   tips never appear back-to-back, even when their triggers coincide.
 * - **Write-once.** Each mark's flag persists, so it never re-fires across
 *   sessions. The whole system is suppressed once the user has run the full tour
 *   from the menu ([AppPrefs.KEY_TUTORIAL_SHOWN]).
 *
 * Screen-reader users are skipped (TapTargetView traps TalkBack on a screen it
 * can't operate — same rationale as the legacy tour and the spot-check
 * explainer) and the flag is marked so the mark doesn't keep retrying.
 */
class CoachMarks(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
) : CoachEvents {

    private val prefs = AppPrefs.get(activity)

    // Cache the per-mark "already shown" flags so the hot path (called every
    // round for the hint/streak checks) stays in memory instead of hitting
    // SharedPreferences each time. markShown keeps the cache and disk in sync.
    private val shown = mutableSetOf<String>().apply {
        ALL_FLAGS.forEach { if (prefs.getBoolean(it, false)) add(it) }
    }

    // In-memory pacing state. Reset every process launch (and every recreate()):
    // these only space marks *within* a session — cross-session "don't repeat"
    // is the persisted `shown` flags, so resetting here is harmless.
    private var roundsSinceLastMark = MIN_ROUND_GAP // allow the first mark immediately
    private var markVisible = false

    override fun onCalibrationStarted() {
        showMark(
            flag = AppPrefs.KEY_COACH_CALIBRATION_SHOWN,
            target = views.buttonNext,
            title = "Quick check",
            description = "Tap ✓ I know it for words you already recognize, " +
                "✗ I don't for the rest. This just sets your starting level — " +
                "there's no wrong answer.",
        )
    }

    override fun onFirstRealWord() {
        showCoreLoop()
    }

    /** The user pressed Hint themselves — they found it, so never coach it. */
    override fun onHintUsed() {
        markShown(AppPrefs.KEY_COACH_HINT_SHOWN)
    }

    // Dwell on a hintable word = the user looks stuck, which is exactly when the
    // two "this word is hard" tools matter: get a Hint, or Flag it to see it more
    // often. Shown as one short 2-step sequence (each step suppressed once seen,
    // so a user who already found Hint just gets the Flag step).
    override fun onHintAvailable() {
        showStuckTools()
    }

    override fun onRoundCommitted() {
        // The streak needs no coach mark — the streak badge is already
        // tap-to-explain. This only paces the secondary marks: tick the
        // inter-mark cooldown so two tips never stack.
        roundsSinceLastMark = (roundsSinceLastMark + 1).coerceAtMost(GAP_CEILING)
    }

    /**
     * Pool-variety nudge: the user has been cycling a small pool without new
     * words coming in, so point at the ACTIVE WORDS card (which opens the
     * rotation-size stepper) and suggest keeping more in play. QuizController
     * owns the "are they actually cruising a small pool?" decision; this just
     * presents the mark once, under the usual gates.
     */
    override fun onPoolStale() {
        showMark(
            flag = AppPrefs.KEY_COACH_POOL_SHOWN,
            target = views.cardActive,
            title = "Seeing the same words?",
            description = "Your rotation is small, so words come back fast. Tap here to keep " +
                "more in play at once for more variety.",
        )
    }

    /**
     * The dwell-triggered "you look stuck" coach: a short sequence over the two
     * tools for a hard word — 💡 Hint, then ⚑ Flag as hard. Each step is dropped
     * once its own flag is set (so a user who already found Hint sees just the
     * Flag step), and the whole thing obeys the same gates as [showMark]:
     * full-tour suppression, screen-reader skip, core-first, no-stack, cooldown.
     */
    private fun showStuckTools() {
        if (prefs.getBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, false)) return
        val hintPending = AppPrefs.KEY_COACH_HINT_SHOWN !in shown
        val flagPending = AppPrefs.KEY_COACH_FLAG_SHOWN !in shown
        if (!hintPending && !flagPending) return
        if (isScreenReaderOn()) {
            // Controls already carry contentDescriptions; just stop re-checking.
            markShown(AppPrefs.KEY_COACH_HINT_SHOWN)
            markShown(AppPrefs.KEY_COACH_FLAG_SHOWN)
            return
        }
        if (markVisible) return
        if (AppPrefs.KEY_COACH_CORE_SHOWN !in shown) return
        if (roundsSinceLastMark < MIN_ROUND_GAP) return

        val steps = mutableListOf<TapTarget>()
        if (hintPending) steps += buildTapTarget(
            views.buttonTip,
            "Stuck? Take a hint",
            "💡 Hint shows an example sentence and reads it aloud. Small score cost, one per word.",
            hugText = false,
        )
        if (flagPending) steps += buildTapTarget(
            views.buttonHard,
            "Mark a word hard",
            "If a word keeps tripping you up, ⚑ flags it so it comes back more often.",
            hugText = false,
        )

        markShown(AppPrefs.KEY_COACH_HINT_SHOWN)
        markShown(AppPrefs.KEY_COACH_FLAG_SHOWN)
        showSequence(steps, views.buttonTip)
    }

    /**
     * The core ✓/✗ loop coach at drilling start — a 2-step sequence so the user
     * both grasps the task AND sees which button to press. Step 1 spotlights the
     * word (left-aligned, see [buildTapTarget]); step 2 spotlights the ✓ Know it
     * button — many users understood "recall it" but didn't connect that to the
     * button, so highlighting only the word left them unsure what to press.
     * "Primary" mark: bypasses the core-first / cooldown gates (it IS the anchor
     * the secondary hints wait on), but still respects full-tour suppression,
     * screen-reader skip, and the no-stack guard.
     */
    private fun showCoreLoop() {
        if (prefs.getBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, false)) return
        if (AppPrefs.KEY_COACH_CORE_SHOWN in shown) return
        if (isScreenReaderOn()) {
            markShown(AppPrefs.KEY_COACH_CORE_SHOWN)
            return
        }
        if (markVisible) return

        markShown(AppPrefs.KEY_COACH_CORE_SHOWN)
        val steps = listOf(
            buildTapTarget(
                views.textForeign,
                "Recall the meaning",
                "What does this word mean? Bring it to mind before you answer.",
                hugText = true,
            ),
            buildTapTarget(
                views.buttonNext,
                "Got it? Tap ✓ Know it",
                "Tap ✓ Know it once you've recalled it. Not sure? ✗ Reveal (left) shows the " +
                    "answer and an example.",
                hugText = false,
            ),
        )
        showSequence(steps, views.textForeign)
    }

    /**
     * Run [steps] as a TapTargetSequence (anchored after [anchor] lays out),
     * holding the [markVisible] no-stack guard for its duration. Callers do their
     * own gating and flag-marking first; this just drives the visual sequence.
     */
    private fun showSequence(steps: List<TapTarget>, anchor: View) {
        if (steps.isEmpty()) return
        markVisible = true
        roundsSinceLastMark = 0
        anchor.post {
            TapTargetSequence(activity)
                .targets(steps)
                .continueOnCancel(true)
                .listener(object : TapTargetSequence.Listener {
                    override fun onSequenceFinish() { markVisible = false }
                    override fun onSequenceStep(lastTarget: TapTarget?, targetClicked: Boolean) {}
                    override fun onSequenceCanceled(lastTarget: TapTarget?) { markVisible = false }
                })
                .start()
        }
    }

    /**
     * Show [target]'s coach mark if every gate allows: the full menu tour hasn't
     * already covered everything, this mark hasn't fired before, no screen reader
     * is active, no other mark is currently on screen, the inter-mark cooldown has
     * elapsed, and (for everything but the core loop) the core loop was taught
     * first. Marks the flag and resets the cooldown on show.
     */
    private fun showMark(
        flag: String,
        target: View,
        title: String,
        description: String,
        hugText: Boolean = false,
    ) {
        if (prefs.getBoolean(AppPrefs.KEY_TUTORIAL_SHOWN, false)) return
        if (flag in shown) return
        if (isScreenReaderOn()) {
            // Mark seen without showing so it doesn't keep re-checking; the
            // controls all carry contentDescriptions for TalkBack already.
            markShown(flag)
            return
        }
        if (markVisible) return
        // Primary marks (calibration at test start, core loop at drilling start)
        // are the "what to press" anchors for each phase — they run before any
        // round is committed, so they bypass the core-first and cooldown gates
        // that pace the secondary control hints.
        val isPrimary = flag == AppPrefs.KEY_COACH_CALIBRATION_SHOWN ||
            flag == AppPrefs.KEY_COACH_CORE_SHOWN
        if (!isPrimary && AppPrefs.KEY_COACH_CORE_SHOWN !in shown) return
        if (!isPrimary && roundsSinceLastMark < MIN_ROUND_GAP) return

        markShown(flag)
        markVisible = true
        roundsSinceLastMark = 0
        // Post so the target is laid out (e.g. just-enabled Hint button) before
        // TapTargetView measures it.
        target.post {
            TapTargetView.showFor(
                activity,
                buildTapTarget(target, title, description, hugText),
                object : TapTargetView.Listener() {
                    override fun onTargetClick(view: TapTargetView) {
                        super.onTargetClick(view) // dismisses
                        markVisible = false
                    }

                    override fun onTargetDismissed(view: TapTargetView?, userInitiated: Boolean) {
                        markVisible = false
                    }
                },
            )
        }
    }

    /**
     * Build the spotlight target. Normally TapTargetView centres its circle on
     * the whole view, but the foreign word is left-aligned inside a full-width
     * TextView, so [hugText] spotlights the actual glyph bounds (in screen
     * coords) instead of empty space to its right. Falls back to the plain
     * view target if the text hasn't been laid out yet.
     */
    private fun buildTapTarget(
        target: View,
        title: String,
        description: String,
        hugText: Boolean,
    ): TapTarget {
        if (hugText && target is TextView) {
            val layout = target.layout
            val text = target.text
            if (layout != null && !text.isNullOrEmpty()) {
                val loc = IntArray(2)
                target.getLocationOnScreen(loc)
                var textLeft = Float.MAX_VALUE
                var textRight = 0f
                for (line in 0 until layout.lineCount) {
                    textLeft = minOf(textLeft, layout.getLineLeft(line))
                    textRight = maxOf(textRight, layout.getLineRight(line))
                }
                val bounds = Rect(
                    loc[0] + target.totalPaddingLeft + textLeft.toInt(),
                    loc[1] + target.totalPaddingTop + layout.getLineTop(0),
                    loc[0] + target.totalPaddingLeft + textRight.toInt(),
                    loc[1] + target.totalPaddingTop + layout.getLineBottom(layout.lineCount - 1),
                )
                return TapTarget.forBounds(bounds, title, description)
                    // A bounds target captures no view bitmap, so the default
                    // opaque spotlight disc would paint over (hide) the word.
                    // Make the target transparent so the real, left-aligned word
                    // shows through the cut-out.
                    .transparentTarget(true)
                    .tintTarget(false)
                    .cancelable(true)
            }
        }
        return TapTarget.forView(target, title, description)
            .tintTarget(false)
            .cancelable(true)
    }

    private fun markShown(flag: String) {
        shown.add(flag)
        prefs.edit().putBoolean(flag, true).apply()
    }

    private fun isScreenReaderOn(): Boolean {
        val am = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isTouchExplorationEnabled
    }

    private companion object {
        val ALL_FLAGS = listOf(
            AppPrefs.KEY_COACH_CALIBRATION_SHOWN,
            AppPrefs.KEY_COACH_CORE_SHOWN,
            AppPrefs.KEY_COACH_HINT_SHOWN,
            AppPrefs.KEY_COACH_FLAG_SHOWN,
            AppPrefs.KEY_COACH_POOL_SHOWN,
        )
        // Minimum committed rounds between two coach marks, so tips never stack.
        const val MIN_ROUND_GAP = 3
        // Cap the cooldown counter so it can't overflow over a long session.
        const val GAP_CEILING = 1000
    }
}
