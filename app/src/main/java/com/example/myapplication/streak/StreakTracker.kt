package com.example.myapplication.streak

import android.app.NotificationManager
import android.content.Context
import com.example.myapplication.data.AppPrefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Daily-practice streak. State lives in the shared app SharedPreferences so we
 * don't introduce a second store. "Today" is device-local — LocalDate handles
 * DST/timezone shifts.
 *
 * Reset is lazy: we never run a midnight job. [currentStreak] returns 0 when
 * the last recorded day is older than yesterday, so a missed day expires the
 * streak the next time anything reads it.
 */
object StreakTracker {
    private const val KEY_CURRENT = "streak_current"
    private const val KEY_LAST_DATE = "streak_last_date"
    private const val KEY_LONGEST = "streak_longest"
    private const val KEY_REMINDER_ENABLED = "streak_reminder_enabled"
    private const val KEY_REMINDER_HOUR = "streak_reminder_hour"
    private const val KEY_REMINDER_MINUTE = "streak_reminder_minute"

    private const val DEFAULT_REMINDER_HOUR = 21
    private const val DEFAULT_REMINDER_MINUTE = 0

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun prefs(context: Context) = AppPrefs.get(context)

    private fun parseDate(raw: String?): LocalDate? =
        if (raw.isNullOrBlank()) null else try {
            LocalDate.parse(raw, DATE_FORMAT)
        } catch (_: Exception) {
            null
        }

    /**
     * The streak after applying lazy-reset. If the user practiced yesterday
     * or today, returns the stored count; otherwise the streak has expired and
     * we return 0 (without writing — readers shouldn't have side effects).
     */
    fun currentStreak(context: Context): Int {
        val p = prefs(context)
        val stored = p.getInt(KEY_CURRENT, 0)
        if (stored == 0) return 0
        val last = parseDate(p.getString(KEY_LAST_DATE, null)) ?: return 0
        val today = LocalDate.now()
        return when {
            last == today -> stored
            last == today.minusDays(1) -> stored
            else -> 0
        }
    }

    fun longestStreak(context: Context): Int = prefs(context).getInt(KEY_LONGEST, 0)

    /** True if the user has already committed a practice round today. */
    fun practicedToday(context: Context): Boolean {
        val last = parseDate(prefs(context).getString(KEY_LAST_DATE, null)) ?: return false
        return last == LocalDate.now()
    }

    /**
     * Call when a quiz round is committed. Idempotent within a single day:
     * subsequent calls on the same day are no-ops. Cancels any pending
     * streak-loss notification because the streak is now safe.
     */
    fun recordPracticeToday(context: Context) {
        val p = prefs(context)
        val today = LocalDate.now()
        val last = parseDate(p.getString(KEY_LAST_DATE, null))
        val stored = p.getInt(KEY_CURRENT, 0)

        if (last == today) return  // already counted today

        val newCount = when (last) {
            today.minusDays(1) -> stored + 1
            else -> 1  // first ever, or chain broken
        }
        val newLongest = max(p.getInt(KEY_LONGEST, 0), newCount)

        p.edit()
            .putInt(KEY_CURRENT, newCount)
            .putString(KEY_LAST_DATE, today.format(DATE_FORMAT))
            .putInt(KEY_LONGEST, newLongest)
            .apply()

        // Cancel any nudge notification still sitting in the shade — the
        // streak is safe now and a stale "don't break your streak" would lie.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(StreakNotifications.STREAK_NOTIFICATION_ID)
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
