package com.example.myapplication.streak

import android.app.NotificationManager
import android.content.Context
import com.example.myapplication.data.DataLayer
import com.example.myapplication.data.entities.STREAK_STATE_SINGLETON_ID
import com.example.myapplication.data.entities.StreakStateEntity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Daily-practice streak. State is held in memory (loaded once at startup via
 * [preload]) and persisted to Room asynchronously. The public API matches the
 * pre-migration SharedPreferences version one-for-one so call sites didn't
 * have to change. "Today" is device-local — LocalDate handles DST/timezone
 * shifts.
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
    private const val DEFAULT_REMINDER_HOUR = 21
    private const val DEFAULT_REMINDER_MINUTE = 0

    /** Cap on stored freezes. Higher = more forgiving but cheapens the streak. */
    const val MAX_FREEZES = 14

    /** A freeze is awarded each time the streak crosses a multiple of this. */
    const val FREEZE_AWARD_INTERVAL_DAYS = 7

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private data class State(
        val current: Int = 0,
        val lastDate: LocalDate? = null,
        val longest: Int = 0,
        val freezes: Int = 0,
        val reminderEnabled: Boolean = true,
        val reminderHour: Int = DEFAULT_REMINDER_HOUR,
        val reminderMinute: Int = DEFAULT_REMINDER_MINUTE,
    )

    @Volatile private var state: State = State()

    /** Hydrate the in-memory state from Room. Called once at app startup. */
    suspend fun preload() {
        val row = DataLayer.database.streakDao().load() ?: return
        state = State(
            current = row.current,
            lastDate = parseDate(row.lastDate),
            longest = row.longest,
            freezes = row.freezes,
            reminderEnabled = row.reminderEnabled,
            reminderHour = row.reminderHour,
            reminderMinute = row.reminderMinute,
        )
    }

    private fun parseDate(raw: String?): LocalDate? =
        if (raw.isNullOrBlank()) null else try {
            LocalDate.parse(raw, DATE_FORMAT)
        } catch (_: Exception) {
            null
        }

    private fun persist(newState: State) {
        state = newState
        val entity = StreakStateEntity(
            id = STREAK_STATE_SINGLETON_ID,
            current = newState.current,
            lastDate = newState.lastDate?.format(DATE_FORMAT),
            longest = newState.longest,
            freezes = newState.freezes,
            reminderEnabled = newState.reminderEnabled,
            reminderHour = newState.reminderHour,
            reminderMinute = newState.reminderMinute,
            lastModifiedMs = System.currentTimeMillis(),
        )
        DataLayer.writeScope.launch {
            DataLayer.database.streakDao().upsert(entity)
        }
    }

    /**
     * The streak after applying lazy-reset. Returns the stored count if the
     * gap since the last practice is small enough that the user's freezes can
     * still cover it; otherwise 0. Read-only — never writes.
     */
    fun currentStreak(@Suppress("UNUSED_PARAMETER") context: Context): Int {
        val s = state
        if (s.current == 0) return 0
        val last = s.lastDate ?: return 0
        val today = LocalDate.now()
        val gapDays = ChronoUnit.DAYS.between(last, today).toInt()
        if (gapDays < 0) return s.current  // clock drift — don't punish
        return when {
            gapDays <= 1 -> s.current                    // today or yesterday
            gapDays - 1 <= s.freezes -> s.current        // freezes will cover the gap on next practice
            else -> 0                                     // unsalvageable
        }
    }

    fun longestStreak(@Suppress("UNUSED_PARAMETER") context: Context): Int = state.longest

    /** Number of freezes (shields) the user currently has stockpiled. */
    fun freezesAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Int = state.freezes

    /** True if the user has already committed a practice round today. */
    fun practicedToday(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        val last = state.lastDate ?: return false
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
        val today = LocalDate.now()
        val s = state
        val last = s.lastDate
        if (last == today) return RecordResult.AlreadyCounted

        val gapDays = if (last != null) ChronoUnit.DAYS.between(last, today).toInt() else Int.MAX_VALUE

        // Resolve the gap. Day-1 (last == yesterday) costs no freezes. Day-2+
        // costs (gap - 1) freezes; if we can afford it, the streak survives.
        var freezesAfter = s.freezes
        var freezesConsumed = 0
        val newCount: Int = when {
            last == null -> 1
            gapDays <= 1 -> s.current + 1
            gapDays - 1 <= s.freezes -> {
                freezesConsumed = gapDays - 1
                freezesAfter = s.freezes - freezesConsumed
                s.current + 1
            }
            else -> 1  // chain broken — freezes can't cover this gap, don't burn them
        }

        // Award a freeze each time the streak crosses a multiple of the
        // interval (e.g., 7, 14, 21...). Increments are always +1 per day, so
        // this fires at most once per day. Capped at MAX_FREEZES.
        val crossedInterval = newCount > 0 && newCount % FREEZE_AWARD_INTERVAL_DAYS == 0
        val freezeAwarded = crossedInterval && freezesAfter < MAX_FREEZES
        if (freezeAwarded) freezesAfter += 1

        val newLongest = max(s.longest, newCount)

        persist(
            s.copy(
                current = newCount,
                lastDate = today,
                longest = newLongest,
                freezes = freezesAfter,
            )
        )

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

    fun isReminderEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = state.reminderEnabled

    fun setReminderEnabled(@Suppress("UNUSED_PARAMETER") context: Context, enabled: Boolean) {
        persist(state.copy(reminderEnabled = enabled))
    }

    fun reminderHour(@Suppress("UNUSED_PARAMETER") context: Context): Int = state.reminderHour

    fun reminderMinute(@Suppress("UNUSED_PARAMETER") context: Context): Int = state.reminderMinute

    fun setReminderTime(@Suppress("UNUSED_PARAMETER") context: Context, hour: Int, minute: Int) {
        persist(state.copy(reminderHour = hour, reminderMinute = minute))
    }

    /** Importer entry point — writes the loaded state without a roundtrip through persist. */
    internal fun importState(
        current: Int,
        lastDate: String?,
        longest: Int,
        freezes: Int,
        reminderEnabled: Boolean,
        reminderHour: Int,
        reminderMinute: Int,
    ) {
        state = State(
            current = current,
            lastDate = parseDate(lastDate),
            longest = longest,
            freezes = freezes,
            reminderEnabled = reminderEnabled,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
        )
        persist(state)
    }
}
