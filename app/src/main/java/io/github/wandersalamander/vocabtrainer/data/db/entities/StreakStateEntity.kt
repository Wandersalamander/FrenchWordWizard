package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row for the daily-practice streak. [id] is always
 * [STREAK_STATE_SINGLETON_ID] — every read/write goes through that key.
 *
 * Stored fields mirror the legacy SharedPreferences layout one-for-one so the
 * importer can drop values straight in.
 */
@Entity(tableName = "streak_state")
data class StreakStateEntity(
    @PrimaryKey val id: Int = STREAK_STATE_SINGLETON_ID,
    val current: Int,
    val lastDate: String?,
    val longest: Int,
    val freezes: Int,
    val reminderEnabled: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val lastModifiedMs: Long,
)

const val STREAK_STATE_SINGLETON_ID: Int = 0
