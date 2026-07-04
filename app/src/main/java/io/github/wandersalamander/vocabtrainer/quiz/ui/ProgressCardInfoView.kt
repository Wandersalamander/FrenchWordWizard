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
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.progress.MasteryTracker
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
    private val dictionary: MyDictionary,
    // Live session state from QuizController, re-read at dialog-open time.
    // disabledSkillsProvider is the picker's exclusion set — it drives the
    // TOTAL PROGRESS dialog's "N due" filter AND the ACTIVE WORDS dialog's
    // tier dim; setSkillEnabled backs the tap-to-toggle columns. Defaults
    // keep tests/headless construction unchanged.
    private val disabledSkillsProvider: () -> Set<Skill> = { emptySet() },
    private val setSkillEnabled: (Skill, Boolean) -> Unit = { _, _ -> },
    private val isAudioMuted: () -> Boolean = { false },
) {
    // Re-mirrors the tier columns of the currently-open ACTIVE WORDS dialog,
    // or null when none is showing. Set by bindTierToggles, cleared on dialog
    // dismiss; invoked via [refreshOpenDialog] when the audio state changes
    // underneath an open dialog (e.g. volume keyed to zero).
    private var refreshOpenDialogState: (() -> Unit)? = null

    fun setup() {
        wire(views.cardLifetime, R.layout.dialog_lifetime_info, ::bindLifetimeDialog)
        wire(views.cardActive, R.layout.dialog_active_info, ::bindActiveDialog)
    }

    /**
     * Live-update an open ACTIVE WORDS dialog after a mute/unmute or an
     * external toggle change: tier columns re-dim and re-describe. No-op when
     * no dialog is showing — the states are read fresh at open time anyway.
     */
    fun refreshOpenDialog() {
        refreshOpenDialogState?.invoke()
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
                // Whatever dialog closes, drop the live-refresh hook — only the
                // ACTIVE WORDS bind sets one, and the two dialogs never coexist.
                .setOnDismissListener { refreshOpenDialogState = null }
                .show()
        }
    }

    /**
     * Fill the TOTAL PROGRESS dialog's worked example with the user's live
     * numbers — mastered / total, today's gain, and the due-for-review count —
     * so it matches the real card instead of confusing placeholders. All four
     * are shown including 0: the live card label hides a zero "+today" / "due",
     * but the explainer shows the format with the real value either way (same
     * rule as the ACTIVE WORDS gold example).
     */
    private fun bindLifetimeDialog(root: View) {
        val total = dictionary.csvData.size
        val mastered = dictionary.computeLifetimeMasteredCount()
        val today = MasteryTracker.todayCount(activity)
        val disabled = disabledSkillsProvider()
        val due = dictionary.computeDueMasteredCount({ it !in disabled })
        root.findViewById<TextView>(R.id.lifetimeInfoMastered).text = mastered.toString()
        root.findViewById<TextView>(R.id.lifetimeInfoTotal).text = " / $total"
        root.findViewById<TextView>(R.id.lifetimeInfoToday).text = "+$today"
        root.findViewById<TextView>(R.id.lifetimeInfoDue).text = "$due due"
    }

    /**
     * Fill the ACTIVE WORDS dialog with the user's live numbers instead of
     * placeholders — the three per-skill active counts and the gold
     * "finished today" example — so the explainer matches the real card and
     * isn't confusing. Then wire the embedded auto-add threshold stepper.
     *
     * 0 is shown wherever it's the real value. The gold example deliberately
     * shows its today-count even when 0 (here only) — the live card label omits
     * a 0 today-count, but in the explainer "8 0" makes the "second number =
     * finished today" idea concrete on a fresh day. The example reuses Reading
     * (its active count + its finished-today count) to match the first column.
     */
    private fun bindActiveDialog(root: View) {
        val counts = dictionary.computeActiveSetCounts()
        val today = readSkillFinishedToday(activity)
        root.findViewById<TextView>(R.id.activeInfoRead).text = counts.read.toString()
        root.findViewById<TextView>(R.id.activeInfoListen).text = counts.listen.toString()
        root.findViewById<TextView>(R.id.activeInfoInvert).text = counts.invert.toString()
        root.findViewById<TextView>(R.id.activeInfoSpeak).text = counts.speak.toString()
        root.findViewById<TextView>(R.id.activeInfoTodayActive).text = counts.read.toString()
        root.findViewById<TextView>(R.id.activeInfoTodayGold).text = " ${today.read}"

        bindTierToggles(root)
        bindThresholdStepper(root)
    }

    /**
     * Make each tier column (the coloured number + its label) a tap-to-toggle
     * for that exercise type: tap to switch it off (column dims to the same
     * 0.35 alpha as the live bar segment), tap again to switch it back on.
     * LISTEN/SPEAK stay locked while the device media volume is muted — audio
     * exercises can't run silent, mirroring the ⋮ menu's grey-out. The host
     * setter repaints the live card underneath; [refreshOpenDialog] re-runs
     * the mirror when mute flips under an open dialog.
     */
    private fun bindTierToggles(root: View) {
        val columns = mapOf(
            Skill.READ to root.findViewById<View>(R.id.activeInfoReadCol),
            Skill.LISTEN to root.findViewById(R.id.activeInfoListenCol),
            Skill.INVERT to root.findViewById(R.id.activeInfoInvertCol),
            Skill.SPEAK to root.findViewById(R.id.activeInfoSpeakCol),
        )
        val audioSkills = setOf(Skill.LISTEN, Skill.SPEAK)

        fun render() {
            val disabled = disabledSkillsProvider()
            val muted = isAudioMuted()
            for ((skill, column) in columns) {
                val off = skill in disabled
                val locked = muted && skill in audioSkills
                column.alpha = if (off) DISABLED_SEGMENT_ALPHA else 1f
                column.contentDescription = buildString {
                    append("${skill.displayName} exercises ${if (off) "off" else "on"}")
                    append(
                        if (locked) ", unavailable while sound is off"
                        else ", tap to turn ${if (off) "on" else "off"}"
                    )
                }
            }
        }

        for ((skill, column) in columns) {
            column.setOnClickListener {
                // Audio tiers can't be toggled while muted (turning them on
                // would be a no-op the picker ignores; the mute already forced
                // them off).
                if (isAudioMuted() && skill in audioSkills) return@setOnClickListener
                setSkillEnabled(skill, skill in disabledSkillsProvider())
                render()
            }
        }
        render()
        refreshOpenDialogState = ::render
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
