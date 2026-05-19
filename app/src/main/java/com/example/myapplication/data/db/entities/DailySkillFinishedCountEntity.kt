package com.example.myapplication.data.db.entities

import androidx.room.Entity
import com.example.myapplication.dictionary.Skill

/**
 * Per-(language, skill) count of (word, skill) pairs that retired below the
 * mastery threshold today — drives the gold "-N" suffix on each skill segment
 * in the ACTIVE WORDS label.
 *
 * Stored as one row per (language, skill); [date] is reset and [count] wipes
 * to 0 on rollover at read/write time (lazy, same as the legacy version).
 */
@Entity(tableName = "daily_skill_finished_count", primaryKeys = ["languageCode", "skill"])
data class DailySkillFinishedCountEntity(
    val languageCode: String,
    val skill: Skill,
    val date: String,
    val count: Int,
    val lastModifiedMs: Long,
)
