package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per language tracking the user's cumulative knowledge score —
 * an event-driven counter that grows by +1 every time a (word, skill) pair
 * crosses below the mastery threshold and +10 every time a refresh-pool
 * pick is passed cleanly. Never decreases.
 *
 * Decoupled from the vocab database on purpose: a CSV reimport, an edited
 * word, or a deleted pair leaves the score untouched. It's a personal
 * milestone counter, not a snapshot of current DB state.
 *
 * The row is absent on first launch; [LifetimeScoreTracker] backfills it
 * once from [MyDictionary.computeLifetimeMasteredCount] (no retention
 * credit — we have no history for that) so long-time users don't start at
 * zero after the upgrade.
 */
@Entity(tableName = "lifetime_score")
data class LifetimeScoreEntity(
    @PrimaryKey val languageCode: String,
    val score: Long,
    val lastModifiedMs: Long,
)
