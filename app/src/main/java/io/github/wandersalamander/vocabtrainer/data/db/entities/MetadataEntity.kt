package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Generic key/value flag table. Used for migration markers and future
 * one-shot signals where standing up a dedicated table would be overkill.
 * Kept in Room (not SharedPreferences) so an Auto-Backup-restored install
 * with stale prefs doesn't trick us into skipping a migration that needs to
 * re-seed an empty Room database.
 */
@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
