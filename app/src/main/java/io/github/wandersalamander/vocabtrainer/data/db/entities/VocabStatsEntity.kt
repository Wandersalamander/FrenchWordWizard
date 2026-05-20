package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Per (vocab, skill) row of the drilling stats. Same six counter/timestamp
 * fields the legacy SharedPreferences layer wrote as "<md5-hash><skill-prefix>
 * <suffix>" strings, now typed and indexed by composite primary key, plus a
 * rolling window of the last
 * [io.github.wandersalamander.vocabtrainer.dictionary.SkillStats.MAX_INTERACTIONS] rounds in
 * [recentInteractions] for future fpb formulas that need ordering and timing.
 *
 * [lastModifiedMs] is the wall-clock time of the last save. Future cloud-sync
 * code uses it for last-write-wins reconciliation; existing read paths ignore
 * it.
 */
@Entity(tableName = "vocab_stats", primaryKeys = ["hash", "skill"])
data class VocabStatsEntity(
    val hash: String,
    val skill: Skill,
    val viewTimeMs: Long,
    val viewTimeMsPrev: Long,
    val nViewed: Int,
    val nFailed: Float,
    val lastDisplayed: Long,
    val lastFailed: Long,
    val lastModifiedMs: Long,
    @ColumnInfo(defaultValue = "")
    val recentInteractions: List<VocabInteraction> = emptyList(),
)
