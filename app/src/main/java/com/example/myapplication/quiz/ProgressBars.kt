package com.example.myapplication.quiz

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.dictionary.ActiveSetCounts
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.progress.MasteryTracker
import com.example.myapplication.progress.SkillFinishedTracker

/**
 * Per-skill count of (word, skill) pairs that retired below the mastery
 * threshold today — the gold "-N" suffix in the ACTIVE WORDS label.
 */
internal data class ActiveSkillFinishedToday(
    val read: Int,
    val listen: Int,
    val invert: Int,
)

// Separator literal used between the three per-skill segments in
// textActiveLabel. QuizController.animateActiveDelta also uses it to map digit
// indices back to character offsets for getPrimaryHorizontal, so the two
// renderers must agree on the exact glyphs.
internal const val ACTIVE_LABEL_SEPARATOR = "  ·  "

// Joiner between a skill's active count and its goldenrod "today-finished"
// suffix. Just a space — gives "8 2" with the gold-coloured "2" reading as a
// separate value because the two numbers carry different ForegroundColor spans
// (skill colour vs today_count_color).
internal const val ACTIVE_LABEL_TODAY_JOIN = " "
internal const val ACTIVE_LABEL_TODAY_PREFIX = ""

/**
 * Per-skill accent colour. Single source of truth for "which colour means
 * which skill" — used both for the vocab card's top stripe (recoloured per
 * round) and the active-progress bar segments in activity_main.xml, which
 * reference the same @color/monokai_* resources directly. Keep the XML and
 * this mapping in sync when adding skills.
 */
@ColorRes
internal fun Skill.accentColorRes(): Int = when (this) {
    Skill.READ -> R.color.monokai_orange
    Skill.LISTEN -> R.color.monokai_cyan
    Skill.INVERT -> R.color.monokai_purple
}

/**
 * Repaints the lifetime mastery bar and its label. Shared between
 * QuizController (per-round refresh) and MainActivity (initial idle render
 * before QuizController exists).
 *
 * Label reads "{mastered} +{today} / {total}" with the today increment in
 * monokai_yellow + bold. The "+N today" portion is omitted when zero so the
 * label doesn't draw attention to days where nothing has been mastered yet.
 */
internal fun refreshLifetimeBar(
    context: Context,
    views: QuizViews,
    dictionary: MyDictionary,
) {
    val total = dictionary.csvData.size
    val mastered = dictionary.computeLifetimeMasteredCount()
    val remaining = (total - mastered).coerceAtLeast(0)
    setSegmentWeight(views.segLifetimeMastered, mastered)
    setSegmentWeight(views.segLifetimeRemaining, remaining)
    val today = MasteryTracker.todayCount(context)
    views.textLifetimeLabel.text = buildLifetimeLabel(context, mastered, today, total)
    // The card is the only accessibility-focusable element in the lifetime
    // block (the bar/label rows are flagged importantForAccessibility="no" in
    // activity_main.xml), so its contentDescription is what TalkBack reads.
    // Updated every refresh so the announcement always matches the visible bar.
    views.cardLifetime.contentDescription = buildString {
        append("Total progress: ")
        append("$mastered of $total words mastered")
        if (today > 0) append(", plus $today today")
    }
}

internal fun refreshActiveBar(
    context: Context,
    views: QuizViews,
    dictionary: MyDictionary,
) = refreshActiveBar(
    context,
    views,
    dictionary.computeActiveSetCounts(),
    readSkillFinishedToday(context),
)

internal fun readSkillFinishedToday(context: Context) = ActiveSkillFinishedToday(
    read = SkillFinishedTracker.todayCount(context, Skill.READ),
    listen = SkillFinishedTracker.todayCount(context, Skill.LISTEN),
    invert = SkillFinishedTracker.todayCount(context, Skill.INVERT),
)

/**
 * Counts-accepting overload — QuizController already pre-computes
 * [ActiveSetCounts] to diff against the previous render for the "-N" floater,
 * so we hand them in here instead of asking the dictionary twice.
 */
internal fun refreshActiveBar(
    context: Context,
    views: QuizViews,
    counts: ActiveSetCounts,
    todayFinished: ActiveSkillFinishedToday,
) {
    setSegmentWeight(views.segActiveRead, counts.read)
    setSegmentWeight(views.segActiveListen, counts.listen)
    setSegmentWeight(views.segActiveInvert, counts.invert)
    views.textActiveLabel.text = buildActiveLabel(context, counts, todayFinished)
    views.cardActive.contentDescription = buildString {
        append("Active words: ")
        append("${counts.read} read")
        if (todayFinished.read > 0) append(" (${todayFinished.read} finished today)")
        append(", ${counts.listen} listen")
        if (todayFinished.listen > 0) append(" (${todayFinished.listen} finished today)")
        append(", ${counts.invert} invert")
        if (todayFinished.invert > 0) append(" (${todayFinished.invert} finished today)")
    }
}

/*
 * Both labels share the same shape: primary numbers in their bar's signature
 * colour, connective text in monokai_comment_light, and an optional yellow
 * accent (only on the lifetime bar, for today's increment). Together with the
 * monospace + end-gravity labels in activity_main.xml they read as a pair
 * stacked above the right edge of each bar.
 */
private fun buildLifetimeLabel(
    context: Context,
    mastered: Int,
    today: Int,
    total: Int,
): CharSequence {
    val sb = SpannableStringBuilder()
    appendColored(context, sb, mastered.toString(), R.color.lifetime_count_color)
    appendColored(context, sb, " / $total", R.color.card_text_muted)
    if (today > 0) {
        appendColored(context, sb, "  ·  ", R.color.card_text_muted)
        val start = sb.length
        sb.append("+$today")
        sb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.today_count_color)),
            start,
            sb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}

private fun buildActiveLabel(
    context: Context,
    counts: ActiveSetCounts,
    todayFinished: ActiveSkillFinishedToday,
): CharSequence {
    val sb = SpannableStringBuilder()
    appendActiveSkillRun(context, sb, counts.read, todayFinished.read, R.color.seg_read)
    appendColored(context, sb, ACTIVE_LABEL_SEPARATOR, R.color.card_text_muted)
    appendActiveSkillRun(context, sb, counts.listen, todayFinished.listen, R.color.seg_listen)
    appendColored(context, sb, ACTIVE_LABEL_SEPARATOR, R.color.card_text_muted)
    appendActiveSkillRun(context, sb, counts.invert, todayFinished.invert, R.color.seg_invert)
    return sb
}

/**
 * Appends "{n_active} {n_today}" for one skill, colouring the active count in
 * [activeColorRes] and the leading space + today-count in
 * [R.color.today_count_color] (shared with the lifetime card's "+N today" so
 * both today-counts read as the same gold). The space between is part of the
 * gold span but is invisible, so the visible gold glyphs are exactly the
 * today-count digits. When [finishedToday] is 0 the gold suffix is omitted
 * entirely — matches the lifetime card's "skip +0" behavior.
 *
 * Keep in sync with [perSkillRunLength] in QuizController.
 */
private fun appendActiveSkillRun(
    context: Context,
    sb: SpannableStringBuilder,
    active: Int,
    finishedToday: Int,
    activeColorRes: Int,
) {
    appendColored(context, sb, active.toString(), activeColorRes)
    if (finishedToday <= 0) return
    val goldStart = sb.length
    sb.append(ACTIVE_LABEL_TODAY_JOIN)
    sb.append(ACTIVE_LABEL_TODAY_PREFIX)
    sb.append(finishedToday.toString())
    sb.setSpan(
        ForegroundColorSpan(ContextCompat.getColor(context, R.color.today_count_color)),
        goldStart,
        sb.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    sb.setSpan(StyleSpan(Typeface.BOLD), goldStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun appendColored(
    context: Context,
    sb: SpannableStringBuilder,
    text: String,
    colorRes: Int,
) {
    val start = sb.length
    sb.append(text)
    sb.setSpan(
        ForegroundColorSpan(ContextCompat.getColor(context, colorRes)),
        start,
        sb.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
}

private fun setSegmentWeight(segment: View, count: Int) {
    val params = segment.layoutParams as LinearLayout.LayoutParams
    if (params.weight == count.toFloat()) return
    params.weight = count.toFloat()
    segment.layoutParams = params
}
