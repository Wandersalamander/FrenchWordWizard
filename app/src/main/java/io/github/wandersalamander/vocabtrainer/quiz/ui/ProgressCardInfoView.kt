package io.github.wandersalamander.vocabtrainer.quiz.ui

import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.quiz.QuizViews

/**
 * Wires tap-to-explain dialogs onto the TOTAL PROGRESS and ACTIVE WORDS cards,
 * matching the streak-row badges (see [StreakBadgeView]) so every stat in the
 * UI can be tapped for a plain-language explainer. Owns no state — the dialogs
 * are static copy; the live numbers stay on the cards themselves.
 *
 * The cards already carry a per-round contentDescription set in
 * [refreshLifetimeBar] / [refreshActiveBar]; this only adds the click target,
 * the ripple (declared in activity_main.xml as a foreground) and a Button role
 * for non-TalkBack assistive tech.
 */
internal class ProgressCardInfoView(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
) {
    fun setup() {
        wire(views.cardLifetime, R.layout.dialog_lifetime_info)
        wire(views.cardActive, R.layout.dialog_active_info)
    }

    private fun wire(card: View, layoutRes: Int) {
        card.isClickable = true
        card.isFocusable = true
        ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
        card.setOnClickListener {
            val view = activity.layoutInflater.inflate(layoutRes, null)
            AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton("Got it", null)
                .show()
        }
    }
}
