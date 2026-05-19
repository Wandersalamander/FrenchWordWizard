package com.example.myapplication.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-language "user wiped progress at" timestamp. MainActivity reads this on
 * resume to detect a wipe that happened in SettingsActivity and recreate the
 * dictionary from the (now empty) stats — see MainActivity.onResume.
 */
@Entity(tableName = "language_wipe")
data class LanguageWipeEntity(
    @PrimaryKey val languageCode: String,
    val wipedAtMs: Long,
)
