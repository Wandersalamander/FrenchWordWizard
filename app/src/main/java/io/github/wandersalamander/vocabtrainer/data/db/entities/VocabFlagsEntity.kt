package io.github.wandersalamander.vocabtrainer.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-vocab boolean flags that aren't skill-scoped. [ignore] is the
 * "I know this, never show it again" mark; [flaggedHard] is the user-controlled
 * "make this appear more often" mark.
 */
@Entity(tableName = "vocab_flags")
data class VocabFlagsEntity(
    @PrimaryKey val hash: String,
    val ignore: Boolean,
    val flaggedHard: Boolean,
    val lastModifiedMs: Long,
)
