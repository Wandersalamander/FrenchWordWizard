package com.example.myapplication.streak

import android.app.NotificationManager
import android.content.Context
import com.example.myapplication.data.AppPrefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Daily-practice streak. State lives in the shared app SharedPreferences so we
 * don't introduce a second store. "Today" is device-local — LocalDate handles
 * DST/timezone shifts.
 *
 * Reset is lazy: we never run a midnight job. [currentStreak] returns 0 when
 * the last recorded day is older than the freeze budget can rescue, so a
 * missed-day stretch only expires the streak the next time anything reads it.
 *
 * Freezes (a.k.a. shields) soften the "one missed day kills weeks of streak"
 * cliff. The user earns one freeze every [FREEZE_AWARD_INTERVAL_DAYS] of
 * streak (capped at [MAX_FREEZES] stored). When the user returns after a gap,
 * freezes are auto-consumed to cover the missing days so the streak survives.
 * Read-only [currentStreak] does not consume; it just answers "is the streak
 * still recoverable with the freezes the user has?"
 */
object StreakTracker {
    private const val KEY_CURRENT = "streak_current"
    private const val KEY_LAST_DATE = "streak_last_date"
    private const val KEY_LONGEST = "streak_longest"
    private const val KEY_FREEZES = "streak_freezes"
    private const val KEY_REMINDER_ENABLED = "streak_reminder_enabled"
    private const val KEY_REMINDER_HOUR = "streak_reminder_hour"
    private const val KEY_REMINDER_MINUTE = "streak_reminder_minute"

    private const val DEFAULT_REMINDER_HOUR = 21
    private const val DEFAULT_REMINDER_MINUTE = 0

    /** Cap on stored freezes. Higher = more forgiving but cheapens the streak. */
    const val MAX_FREEZES = 14

    /** A freeze is awarded each time the streak crosses a multiple of this. */
    const val FREEZE_AWARD_INTERVAL_DAYS = 7

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun prefs(context: Context) = AppPrefs.get(context)

    private fun parseDate(raw: String?): LocalDate? =
        if (raw.isNullOrBlank()) null else try {
            LocalDate.parse(raw, DATE_FORMAT)
        } catch (_: Exception) {
            null
        }

    /**
     * The streak after applying lazy-reset. Returns the stored count if the
     * gap since the last practice is small enough that the user's freezes can
     * still cover it; otherwise 0. Read-only — never writes.
     */
    fun currentStreak(context: Context): Int {
        val p = prefs(context)
        val stored = p.getInt(KEY_CURRENT, 0)
        if (stored == 0) return 0
        val last = parseDate(p.getString(KEY_LAST_DATE, null)) ?: return 0
        val today = LocalDate.now()
        val gapDays = ChronoUnit.DAYS.between(last, today).toInt()
        if (gapDays < 0) return stored  // clock drift — don't punish
        val freezes = p.getInt(KEY_FREEZES, 0)
        return when {
            gapDays <= 1 -> stored                     // today or yesterday
            gapDays - 1 <= freezes -> stored           // freezes will cover the gap on next practice
            else -> 0                                   // unsalvageable
        }
    }

    fun longestStreak(context: Context): Int = prefs(context).getInt(KEY_LONGEST, 0)

    /** Number of freezes (shields) the user currently has stockpiled. */
    fun freezesAvailable(context: Context): Int = prefs(context).getInt(KEY_FREEZES, 0)

    /** True if the user has already committed a practice round today. */
    fun practicedToday(context: Context): Boolean {
        val last = parseDate(prefs(context).getString(KEY_LAST_DATE, null)) ?: return false
        return last == LocalDate.now()
    }

    /**
     * Call when a quiz round is committed. Idempotent within a single day:
     * subsequent calls on the same day are no-ops. Cancels any pending
     * streak-loss notification because the streak is now safe.
     *
     * Returns a [RecordResult] describing what happened so callers can surface
     * a freeze-consumption toast (or an earned-freeze toast).
     */
    fun recordPracticeToday(context: Context): RecordResult {
        val p = prefs(context)
        val today = LocalDate.now()
        val last = parseDate(p.getString(KEY_LAST_DATE, null))
        val stored = p.getInt(KEY_CURRENT, 0)
        val freezes = p.getInt(KEY_FREEZES, 0)

        if (last == today) return RecordResult.AlreadyCounted

        val gapDays = if (last != null) ChronoUnit.DAYS.between(last, today).toInt() else Int.MAX_VALUE

        // Resolve the gap. Day-1 (last == yesterday) costs no freezes. Day-2+
        // costs (gap - 1) freezes; if we can afford it, the streak survives.
        var freezesAfter = freezes
        var freezesConsumed = 0
        val newCount: Int = when {
            last == null -> 1
            gapDays <= 1 -> stored + 1
            gapDays - 1 <= freezes -> {
                freezesConsumed = gapDays - 1
                freezesAfter = freezes - freezesConsumed
                stored + 1
            }
            else -> 1  // chain broken — freezes can't cover this gap, don't burn them
        }

        // Award a freeze each time the streak crosses a multiple of the
        // interval (e.g., 7, 14, 21...). Increments are always +1 per day, so
        // this fires at most once per day. Capped at MAX_FREEZES.
        val crossedInterval = newCount > 0 && newCount % FREEZE_AWARD_INTERVAL_DAYS == 0
        val freezeAwarded = crossedInterval && freezesAfter < MAX_FREEZES
        if (freezeAwarded) freezesAfter += 1

        val newLongest = max(p.getInt(KEY_LONGEST, 0), newCount)

        p.edit()
            .putInt(KEY_CURRENT, newCount)
            .putString(KEY_LAST_DATE, today.format(DATE_FORMAT))
            .putInt(KEY_LONGEST, newLongest)
            .putInt(KEY_FREEZES, freezesAfter)
            .apply()

        // Cancel any nudge notification still sitting in the shade — the
        // streak is safe now and a stale "don't break your streak" would lie.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(StreakNotifications.STREAK_NOTIFICATION_ID)

        return RecordResult.Counted(
            newStreak = newCount,
            freezesConsumed = freezesConsumed,
            freezeAwarded = freezeAwarded,
            streakBroken = last != null && gapDays > 1 && freezesConsumed == 0,
        )
    }

    sealed class RecordResult {
        /** A practice round had already been committed today; nothing changed. */
        object AlreadyCounted : RecordResult()

        data class Counted(
            val newStreak: Int,
            val freezesConsumed: Int,
            val freezeAwarded: Boolean,
            /** True when a gap > 1 day reset the chain to 1 (freezes couldn't cover). */
            val streakBroken: Boolean,
        ) : RecordResult()
    }

    fun isReminderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER_ENABLED, true)

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply()
    }

    fun reminderHour(context: Context): Int =
        prefs(context).getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR)

    fun reminderMinute(context: Context): Int =
        prefs(context).getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE)

    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_REMINDER_HOUR, hour)
            .putInt(KEY_REMINDER_MINUTE, minute)
            .apply()
    }
}
