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
        listOf(views.textStreak, views.textStreakShield).forEach { badge ->
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
    }

    /**
     * Refresh the badge from persistent state. Cheap: prefs read + LocalDate
     * comparison. Called per-round and from MainActivity.onResume.
     */
    fun refresh() {
        val ctx = activity.applicationContext
        val streak = StreakTracker.currentStreak(ctx)
        val shields = StreakTracker.freezesAvailable(ctx)
        views.textStreak.text = if (streak > 0) "🔥 $streak" else ""
        // Without a dynamic contentDescription, TalkBack reads the literal
        // glyphs ("fire emoji 12") or skips the value entirely. Spell out
        // the count and unit so SR users get the same information as the
        // visible badge.
        views.textStreak.contentDescription = when {
            streak <= 0 -> null
            streak == 1 -> "1 day streak, tap for details"
            else -> "$streak day streak, tap for details"
        }
        // Only expose the flame as a tap target when there's a streak to
        // explain — wrap_content collapses the empty state, so there's
        // nothing meaningful to tap on day 0.
        views.textStreak.isClickable = streak > 0
        views.textStreak.isFocusable = streak > 0
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
