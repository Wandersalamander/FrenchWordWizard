package io.github.wandersalamander.vocabtrainer.quiz

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.wandersalamander.vocabtrainer.dictionary.ActiveSetCounts
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.pop
import io.github.wandersalamander.vocabtrainer.progress.MasteryTracker
import io.github.wandersalamander.vocabtrainer.progress.SkillFinishedTracker

// Distance the goldenrod "-N" floater rises above the active label before
// fading out. ~24dp is enough to clear the bar row underneath so the floater
// reads as lifting off the number, not the segment.
private const val FLOATER_LIFT_DP = 24f

// Gap between a digit's right edge and the floater's left edge. The floater
// sits NEXT to the number ("7 -1"), not on top of it, so the user can read
// both the new count and the delta at the same time.
private const val FLOATER_SIDE_GAP_DP = 2f

/**
 * Renders the lifetime + active progress bars and animates the goldenrod
 * "-N" delta floaters that fire when a (word, skill) pair retires below the
 * mastery threshold. Owns the previous-render baselines so subsequent
 * refreshes can detect a real delta.
 */
internal class ProgressBarAnimator(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val dictionary: MyDictionary,
) {
    // Last today-counter value reflected on screen; lets refreshAll detect an
    // increment and pop the label without firing the animation on every round.
    private var renderedTodayCount: Int = 0

    // Last per-group active counts mirrored on screen. Used to detect a drop
    // (a pair retired below the mastery threshold this round) and fire a
    // goldenrod "-N" delta floater off the matching digit. Null until the
    // first refresh so the first render doesn't claim a phantom drop.
    private var renderedActiveCounts: ActiveSetCounts? = null

    fun refreshAll() {
        if (dictionary.csvData.isEmpty()) return
        val ctx = activity.applicationContext
        refreshLifetimeBar(ctx, views, dictionary)
        val newCounts = dictionary.computeActiveSetCounts()
        val todayFinished = ActiveSkillFinishedToday(
            read = SkillFinishedTracker.todayCount(ctx, Skill.READ),
            listen = SkillFinishedTracker.todayCount(ctx, Skill.LISTEN),
            invert = SkillFinishedTracker.todayCount(ctx, Skill.INVERT),
        )
        refreshActiveBar(ctx, views, newCounts, todayFinished)
        val todayNow = MasteryTracker.todayCount(ctx)
        if (todayNow > renderedTodayCount) views.textLifetimeLabel.pop()
        renderedTodayCount = todayNow
        maybeFireActiveDeltas(newCounts, todayFinished)
        renderedActiveCounts = newCounts
    }

    /**
     * Repaint the bars and snap baselines to the post-rollover snapshot so
     * the next genuine action still detects a real delta (and isn't fooled
     * by the artificial yesterday→0 drop on resume). Used after app resume
     * when a midnight rollover may have happened while the app was
     * backgrounded.
     */
    fun rebaseline() {
        if (dictionary.csvData.isEmpty()) return
        val ctx = activity.applicationContext
        refreshLifetimeBar(ctx, views, dictionary)
        refreshActiveBar(ctx, views, dictionary)
        renderedTodayCount = MasteryTracker.todayCount(ctx)
        renderedActiveCounts = dictionary.computeActiveSetCounts()
    }

    /**
     * If any of the three active-pool counts dropped since the last render,
     * pop the whole label and fire a goldenrod "-N" floater off each affected
     * segment. The first render (renderedActiveCounts == null) is treated as
     * a baseline so a fresh-launch hydration doesn't claim a phantom drop.
     */
    private fun maybeFireActiveDeltas(
        newCounts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
    ) {
        val prev = renderedActiveCounts ?: return
        val dropRead = prev.read - newCounts.read
        val dropListen = prev.listen - newCounts.listen
        val dropInvert = prev.invert - newCounts.invert
        if (dropRead <= 0 && dropListen <= 0 && dropInvert <= 0) return
        views.textActiveLabel.pop()
        // The label needs a layout pass after setText before
        // layout.getPrimaryHorizontal is meaningful; defer to the next frame.
        views.textActiveLabel.post {
            if (dropRead > 0) {
                animateActiveDelta(views.textActiveDeltaRead, dropRead, ActiveGroup.READ, newCounts, todayFinished)
            }
            if (dropListen > 0) {
                animateActiveDelta(views.textActiveDeltaListen, dropListen, ActiveGroup.LISTEN, newCounts, todayFinished)
            }
            if (dropInvert > 0) {
                animateActiveDelta(views.textActiveDeltaInvert, dropInvert, ActiveGroup.INVERT, newCounts, todayFinished)
            }
        }
    }

    /**
     * Fires a single goldenrod "-N" floater off the right edge of [group]'s
     * "{n_active} -{n_today}" run in textActiveLabel. The floater is anchored
     * immediately after the gold today-suffix, then lifts up ~24dp while
     * fading. Re-entrant: any in-flight animation on the same floater is
     * cancelled and restarted.
     */
    private fun animateActiveDelta(
        floater: TextView,
        drop: Int,
        group: ActiveGroup,
        counts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
    ) {
        val label = views.textActiveLabel
        val layout = label.layout ?: return
        val segmentRightX = segmentRightEdgeXInLabel(layout, counts, todayFinished, group) ?: return

        floater.animate().cancel()
        floater.text = "-$drop"
        val density = activity.resources.displayMetrics.density
        // Floater sits next to (not over) the segment. translationX is the
        // floater's own left edge inside the ConstraintLayout, and the
        // floater is start-constrained to textActiveLabel so X=0 there means
        // the label's left edge — so segmentRightX (a label-local X) maps 1:1.
        floater.translationX = segmentRightX + FLOATER_SIDE_GAP_DP * density
        floater.translationY = 0f
        floater.alpha = 0f
        floater.scaleX = 0.85f
        floater.scaleY = 0.85f
        floater.visibility = View.VISIBLE

        val liftPx = FLOATER_LIFT_DP * density
        floater.animate()
            .alpha(1f)
            .scaleX(1.15f).scaleY(1.15f)
            .setDuration(120L)
            .withEndAction {
                floater.animate()
                    .translationY(-liftPx)
                    .alpha(0f)
                    .scaleX(0.95f).scaleY(0.95f)
                    .setDuration(640L)
                    .withEndAction {
                        floater.visibility = View.INVISIBLE
                        floater.translationX = 0f
                        floater.translationY = 0f
                        floater.alpha = 1f
                        floater.scaleX = 1f
                        floater.scaleY = 1f
                    }
                    .start()
            }
            .start()
    }

    /**
     * Pixel X of the right edge of [group]'s "{active} -{today}" run inside
     * textActiveLabel's own coordinate space (NOT the parent's). Returns null
     * when the rendered text doesn't match the expected shape — a paranoid
     * fallback that just skips the floater rather than crashing.
     */
    private fun segmentRightEdgeXInLabel(
        layout: android.text.Layout,
        counts: ActiveSetCounts,
        todayFinished: ActiveSkillFinishedToday,
        group: ActiveGroup,
    ): Float? {
        val readSeg = perSkillRunLength(counts.read, todayFinished.read)
        val listenSeg = perSkillRunLength(counts.listen, todayFinished.listen)
        val invertSeg = perSkillRunLength(counts.invert, todayFinished.invert)
        val sepLen = ACTIVE_LABEL_SEPARATOR.length
        val expectedEnd = readSeg + sepLen + listenSeg + sepLen + invertSeg
        if (layout.text.length < expectedEnd) return null
        val end = when (group) {
            ActiveGroup.READ -> readSeg
            ActiveGroup.LISTEN -> readSeg + sepLen + listenSeg
            ActiveGroup.INVERT -> expectedEnd
        }
        return layout.getPrimaryHorizontal(end)
    }

    private fun perSkillRunLength(active: Int, finishedToday: Int): Int {
        val activeLen = active.toString().length
        if (finishedToday <= 0) return activeLen
        return activeLen +
            ACTIVE_LABEL_TODAY_JOIN.length +
            ACTIVE_LABEL_TODAY_PREFIX.length +
            finishedToday.toString().length
    }

    private enum class ActiveGroup { READ, LISTEN, INVERT }
}
