package io.github.wandersalamander.vocabtrainer.quiz.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.progress.LifetimeScoreTracker
import io.github.wandersalamander.vocabtrainer.quiz.QuizViews
import io.github.wandersalamander.vocabtrainer.streak.StreakTracker

/**
 * Renders the streak / shield badges plus their explanation dialogs, and
 * surfaces post-save toasts ("shield used", "shield earned"). Owns no
 * conversational state — every call re-reads from [StreakTracker] and
 * repaints the two badge TextViews.
 */
internal class StreakBadgeView(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
) {
    /**
     * Attach a Button role to the streak/shield TextViews so non-TalkBack
     * assistive tech (Switch Access, TV remotes, BrailleBack) sees them as
     * buttons. TextView + clickable=true announces correctly through TalkBack
     * already, but other services key off the node's class name.
     */
    fun setupRoles() {
        listOf(views.textStreak, views.textStreakShield, views.textStreakScore).forEach { badge ->
            ViewCompat.setAccessibilityDelegate(badge, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })
        }
        // The score badge's text is repainted per-round by refreshLifetimeScoreBadge
        // (not by this class), so its click handler is wired once here rather than
        // in refresh(). The dialog reads the live score from the badge at tap time.
        views.textStreakScore.setOnClickListener { showScoreExplanation() }
    }

    /**
     * Refresh the badge from persistent state. Cheap: prefs read + LocalDate
     * comparison. Called per-round and from MainActivity.onResume.
     */
    fun refresh() {
        val ctx = activity.applicationContext
        val streak = StreakTracker.currentStreak(ctx)
        val shields = StreakTracker.freezesAvailable(ctx)
        // Always render the flame, even at 0 ("🔥 0"). The badge has a fixed
        // 48dp min size, so blanking the text on day 0 leaves an empty-looking
        // tap target rather than collapsing it — and the first-launch tutorial
        // spotlights this view, so an empty badge reads as "highlighting
        // nothing". Showing the real count keeps the highlight pointed at
        // something concrete and lets day-0 users tap to learn how streaks work.
        views.textStreak.text = "🔥 $streak"
        // Without a dynamic contentDescription, TalkBack reads the literal
        // glyphs ("fire emoji 12") or skips the value entirely. Spell out
        // the count and unit so SR users get the same information as the
        // visible badge.
        views.textStreak.contentDescription = when {
            streak <= 0 -> "No streak yet, tap for details"
            streak == 1 -> "1 day streak, tap for details"
            else -> "$streak day streak, tap for details"
        }
        views.textStreak.isClickable = true
        views.textStreak.isFocusable = true
        views.textStreak.setOnClickListener { showStreakExplanation() }
        views.textStreakShield.text = "🛡️ $shields"
        views.textStreakShield.contentDescription = when (shields) {
            1 -> "1 streak shield available, tap for details"
            else -> "$shields streak shields available, tap for details"
        }
        views.textStreakShield.visibility = View.VISIBLE
        views.textStreakShield.setOnClickListener { showShieldExplanation() }
    }

    /** Toast for streak save-results (shield used / shield earned). */
    fun surfaceResult(result: StreakTracker.RecordResult) {
        if (result !is StreakTracker.RecordResult.Counted) return
        val ctx = activity.applicationContext
        when {
            result.freezesConsumed > 0 -> {
                val plural = if (result.freezesConsumed == 1) "" else "s"
                Toast.makeText(
                    ctx,
                    "🛡️ Shield used — streak saved (${result.freezesConsumed} day$plural covered)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            result.freezeAwarded -> {
                Toast.makeText(
                    ctx,
                    "🛡️ Shield earned — keep your streak safe",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showShieldExplanation() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_shield_info, null)
        view.findViewById<TextView>(R.id.shieldDialogEarn).text =
            "Earn one shield every ${StreakTracker.FREEZE_AWARD_INTERVAL_DAYS} days of streak " +
                "(up to ${StreakTracker.MAX_FREEZES} stockpiled)."
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showScoreExplanation() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_score_info, null)
        view.findViewById<TextView>(R.id.scoreDialogMastery).text =
            "Master a word in a skill: +${LifetimeScoreTracker.SCORE_PER_SKILL_MASTERY}."
        view.findViewById<TextView>(R.id.scoreDialogRefresh).text =
            "Pass a review of a mastered word: +${LifetimeScoreTracker.SCORE_PER_REFRESH_PASS}."
        // Pull the current score off the live badge ("⭐ 247") rather than
        // re-reading the tracker — keeps this view dependency-free (no Context
        // / dictionary) and always matches what the user sees.
        val score = views.textStreakScore.text.filter { it.isDigit() }
        if (score.isNotEmpty() && score != "0") {
            val current = view.findViewById<TextView>(R.id.scoreDialogCurrent)
            current.text = "Your score: $score"
            current.visibility = View.VISIBLE
        }
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showStreakExplanation() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_streak_info, null)
        val longest = StreakTracker.longestStreak(activity.applicationContext)
        if (longest > 0) {
            val plural = if (longest == 1) "" else "s"
            val longestView = view.findViewById<TextView>(R.id.streakDialogLongest)
            longestView.text = "Best so far: $longest day$plural"
            longestView.visibility = View.VISIBLE
        }
        AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .show()
    }
}
