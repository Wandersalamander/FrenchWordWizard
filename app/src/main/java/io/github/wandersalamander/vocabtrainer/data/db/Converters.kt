package io.github.wandersalamander.vocabtrainer.data.db

import androidx.room.TypeConverter
import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Room type converters. Enums are persisted as their [Enum.name] (READ /
 * LISTEN / SENTENCE / …) rather than ordinal so renumbering a case later
 * doesn't shuffle existing rows.
 *
 * [VocabInteraction] lists are flattened to a single TEXT column as
 * "<ts>,<fail>;<ts>,<fail>;..." — chosen over JSON for compactness on the
 * ~5000-row vocab_stats table (10 entries fit in ~150 bytes).
 */
class Converters {
    @TypeConverter
    fun skillToString(skill: Skill): String = skill.name

    @TypeConverter
    fun stringToSkill(name: String): Skill = Skill.valueOf(name)

    @TypeConverter
    fun commitButtonToString(b: CommitButton): String = b.name

    @TypeConverter
    fun stringToCommitButton(name: String): CommitButton = CommitButton.valueOf(name)

    @TypeConverter
    fun tipUsedToString(t: TipUsed): String = t.name

    @TypeConverter
    fun stringToTipUsed(name: String): TipUsed = TipUsed.valueOf(name)

    @TypeConverter
    fun spotCheckResultToString(s: SpotCheckResult): String = s.name

    @TypeConverter
    fun stringToSpotCheckResult(name: String): SpotCheckResult = SpotCheckResult.valueOf(name)

    @TypeConverter
    fun sentenceSourceToString(s: SentenceSource): String = s.name

    @TypeConverter
    fun stringToSentenceSource(name: String): SentenceSource = SentenceSource.valueOf(name)

    @TypeConverter
    fun interactionsToString(list: List<VocabInteraction>): String =
        list.joinToString(SEPARATOR_ENTRY) { "${it.timestampMs}$SEPARATOR_FIELD${it.failAmount}" }

    @TypeConverter
    fun stringToInteractions(s: String): List<VocabInteraction> {
        if (s.isEmpty()) return emptyList()
        return s.split(SEPARATOR_ENTRY).mapNotNull { part ->
            val fields = part.split(SEPARATOR_FIELD, limit = 2)
            if (fields.size != 2) return@mapNotNull null
            val ts = fields[0].toLongOrNull() ?: return@mapNotNull null
            val fail = fields[1].toFloatOrNull() ?: return@mapNotNull null
            VocabInteraction(timestampMs = ts, failAmount = fail)
        }
    }

    companion object {
        private const val SEPARATOR_ENTRY = ";"
        private const val SEPARATOR_FIELD = ","
    }
}
