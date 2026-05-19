package com.example.myapplication.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per language tracking how many words transitioned to fully-mastered
 * today. [date] is an ISO date string; the count resets when [date] != today's
 * date on read (lazy rollover — same pattern as the legacy SharedPreferences
 * version). One row per language, overwritten daily; we don't keep a history
 * trail here.
 */
@Entity(tableName = "daily_mastered_count")
data class DailyMasteredCountEntity(
    @PrimaryKey val languageCode: String,
    val date: String,
    val count: Int,
    val lastModifiedMs: Long,
)
