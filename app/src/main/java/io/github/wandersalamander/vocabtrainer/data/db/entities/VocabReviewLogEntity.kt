package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Append-only log of committed rounds. One row per round (including compromised
 * rows that were rolled back from active stats — those carry [compromised] =
 * true). The (vocab_hash, skill) pair references [VocabStatsEntity] with
 * ON DELETE CASCADE so a per-language wipe cleans the log alongside the stats.
 *
 * Composite index on (vocab_hash, skill, timestamp_ms) supports the most
 * common query — "all reviews for this pair in time order" — which is what
 * any future re-training or per-pair history view needs.
 */
@Entity(
    tableName = "vocab_review_log",
    foreignKeys = [
        ForeignKey(
            entity = VocabStatsEntity::class,
            parentColumns = ["hash", "skill"],
            childColumns = ["vocab_hash", "skill"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["vocab_hash", "skill", "timestamp_ms"]),
    ],
)
data class VocabReviewLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "vocab_hash")
    val vocabHash: String,
    val skill: Skill,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,
    @ColumnInfo(name = "view_time_ms")
    val viewTimeMs: Long,
    @ColumnInfo(name = "time_to_first_action_ms")
    val timeToFirstActionMs: Long?,
    @ColumnInfo(name = "paused_ms")
    val pausedMs: Long,
    @ColumnInfo(name = "commit_button")
    val commitButton: CommitButton,
    @ColumnInfo(name = "tip_used")
    val tipUsed: TipUsed,
    @ColumnInfo(name = "progressive_reveals")
    val progressiveReveals: Int,
    @ColumnInfo(name = "word_mask_taps")
    val wordMaskTaps: Int,
    @ColumnInfo(name = "word_replays")
    val wordReplays: Int,
    @ColumnInfo(name = "sentence_mask_taps")
    val sentenceMaskTaps: Int,
    @ColumnInfo(name = "speak_recordings")
    val speakRecordings: Int,
    @ColumnInfo(name = "spot_check_result")
    val spotCheckResult: SpotCheckResult,
    @ColumnInfo(name = "sentence_source")
    val sentenceSource: SentenceSource,
    @ColumnInfo(name = "listening_enabled")
    val listeningEnabled: Boolean,
    val compromised: Boolean,
)
