package io.github.wandersalamander.vocabtrainer.quiz.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MAX
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MIN
import io.github.wandersalamander.vocabtrainer.quiz.QuizViews

/**
 * Wires tap-to-explain dialogs onto the TOTAL PROGRESS and ACTIVE WORDS cards,
 * matching the streak-row badges (see [StreakBadgeView]) so every stat in the
 * UI can be tapped for a plain-language explainer.
 *
 * The ACTIVE WORDS dialog also carries the per-language auto-add threshold
 * stepper — the same control as Settings → Active pool, surfaced here so the
 * "minimum words in reading" floor can be tuned in context. It's scoped to
 * [languageCode] (the quiz screen is already per-language), and the picker
 * re-reads the threshold each round, so a change takes effect on the next pick
 * without rebuilding anything.
 *
 * The cards already carry a per-round contentDescription set in
 * [refreshLifetimeBar] / [refreshActiveBar]; this only adds the click target,
 * the ripple (declared in activity_main.xml as a foreground) and a Button role
 * for non-TalkBack assistive tech.
 */
internal class ProgressCardInfoView(
    private val activity: AppCompatActivity,
    private val views: QuizViews,
    private val languageCode: String,
) {
    fun setup() {
        wire(views.cardLifetime, R.layout.dialog_lifetime_info)
        wire(views.cardActive, R.layout.dialog_active_info, ::bindThresholdStepper)
    }

    private fun wire(card: View, layoutRes: Int, bind: ((View) -> Unit)? = null) {
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
            bind?.invoke(view)
            AlertDialog.Builder(activity)
                .setView(view)
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    /**
     * Wire the −/value/+ stepper embedded in the ACTIVE WORDS dialog to the
     * per-language threshold. Mirrors the Settings stepper: boundary buttons fade
     * at the floor/ceiling, the value is a polite live region announced on each
     * tap, and writes coerce into range via [AppPrefs.setAutoAddThreshold].
     */
    private fun bindThresholdStepper(root: View) {
        val valueText = root.findViewById<TextView>(R.id.activeThresholdValue)
        val decrease = root.findViewById<TextView>(R.id.activeThresholdDecrease)
        val increase = root.findViewById<TextView>(R.id.activeThresholdIncrease)

        fun render(value: Int) {
            valueText.text = value.toString()
            valueText.contentDescription = "Words to learn at once: $value"
            decrease.isEnabled = value > AUTO_INTRODUCE_THRESHOLD_MIN
            decrease.alpha = if (decrease.isEnabled) 1f else 0.35f
            increase.isEnabled = value < AUTO_INTRODUCE_THRESHOLD_MAX
            increase.alpha = if (increase.isEnabled) 1f else 0.35f
        }

        fun update(delta: Int) {
            AppPrefs.setAutoAddThreshold(
                activity,
                languageCode,
                AppPrefs.autoAddThreshold(activity, languageCode) + delta,
            )
            render(AppPrefs.autoAddThreshold(activity, languageCode))
        }

        render(AppPrefs.autoAddThreshold(activity, languageCode))
        decrease.setOnClickListener { update(-1) }
        increase.setOnClickListener { update(+1) }
    }
}
