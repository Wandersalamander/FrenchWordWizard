package io.github.wandersalamander.vocabtrainer.dictionary

import android.content.Context
import io.github.wandersalamander.vocabtrainer.data.VocabPersistence
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

private val tierFileRegex = Regex("""dictionary_sorted_.+_\d{2}\.csv""")

fun openDictionaryStream(context: Context, language: Language): InputStream {
    val folder = language.assetFolder
    val files = context.assets.list(folder)
        ?.filter { tierFileRegex.matches(it) }
        ?.sorted()
        .orEmpty()
    val streams = files.map { context.assets.open("$folder/$it") }
    return SequenceInputStream(Collections.enumeration(streams))
}

/**
 * Per-skill count of (word, skill) pairs currently in the active drilling
 * pool — introduced for that skill but not yet retired below
 * SKILL_FINISHED_THRESHOLD. A word being drilled in multiple skills
 * contributes to each skill's count independently, matching what the picker
 * pool in [getActiveVocabWeighted] sees.
 */
data class ActiveSetCounts(
    val read: Int,
    val listen: Int,
    val invert: Int,
)

/**
 * Mastered vs. active (word, skill) pair counts, summed over all skills. See
 * [MyDictionary.computeStudiedPairCounts].
 */
data class StudiedPairCounts(
    val mastered: Int,
    val active: Int,
)

/**
 * The bundled vocab collection for the active language plus the per-vocab
 * persistence cache. Owns [csvData] (the loaded Vocab list) and a few
 * aggregate queries that drive the on-screen progress bars and the LLM's
 * "previously-studied words" hint. The vocab-selection algorithms used by
 * the quiz live in [VocabSelector] as extension functions on this class.
 */
class MyDictionary(inputStream: InputStream, val persistence: VocabPersistence? = null) {
    val csvData: List<Vocab> = readCsv(inputStream)

    // Cache of foreign words that have entered the rotation. Used by the LLM
    // prompt to optionally seed example sentences with previously-studied
    // vocabulary. Built lazily on first read and incrementally maintained via
    // [notifyVocabIntroduced] so we don't scan ~10k entries per LLM call.
    private val introducedForeignSet: LinkedHashSet<String> = LinkedHashSet()
    private var introducedForeignInitialized: Boolean = false

    /**
     * Re-apply persistence to every loaded Vocab. The shared [VocabPersistence]
     * cache may have been mutated by another component (typically the main
     * activity's save path while the foreground service was idle), so each
     * Vocab pulls the current snapshot into its in-memory skillStats.
     */
    fun reloadPreferences() {
        csvData.forEach { it.loadPreferences() }
        // Stat values changed under us — discard caches.
        introducedForeignInitialized = false
        introducedForeignSet.clear()
    }

    /** Words where [skill] has been practiced at least once. */
    fun getActiveDataSize(skill: Skill): Int =
        csvData.count { it.stats(skill).nTimesViewed > 0 }

    /**
     * Count of words that count as fully mastered for the lifetime bar:
     * either ignored, or every ladder skill has been introduced AND retired
     * below SKILL_FINISHED_THRESHOLD. Single pass over [csvData].
     */
    fun computeLifetimeMasteredCount(): Int =
        csvData.count { it.ignore || it.isFullyLearned() }

    /**
     * Count of fully-mastered words the refresh pool would actually surface
     * right now — i.e. how many already-learned words are ready to resurface for
     * repetition. Each word counts once regardless of how many of its skill
     * pairs are due, so this is a clean subset of [computeLifetimeMasteredCount]
     * (no dividing by the number of skills).
     *
     * Two gates: (1) this function's "fully mastered" check (all ladder skills
     * retired below threshold), and (2) [Vocab.surfaceableRefreshSkill] — the
     * SAME tier-0 decision the picker uses, so a word whose only due skills are
     * snoozed behind a recently-refreshed harder sibling is NOT counted. Without
     * gate (2) the badge would over-count by exactly the words refreshed within
     * the last day (their easier skills read as "due" but won't be surfaced).
     */
    fun computeDueMasteredCount(now: Long = System.currentTimeMillis()): Int {
        var due = 0
        for (vocab in csvData) {
            if (vocab.ignore || !vocab.isFullyLearned()) continue
            if (vocab.surfaceableRefreshSkill(now = now) != null) {
                due++
            }
        }
        return due
    }

    /**
     * Per-skill count of (word, skill) pairs in the active drilling pool —
     * unlocked, introduced for that skill, and still above the mastery
     * threshold. Drives the active-set composition bar.
     */
    fun computeActiveSetCounts(): ActiveSetCounts {
        var read = 0
        var listen = 0
        var invert = 0
        for (vocab in csvData) {
            if (vocab.ignore || !vocab.hasBeenIntroduced()) continue
            for (skill in Skill.ladder) {
                if (!vocab.isSkillUnlocked(skill)) continue
                val s = vocab.stats(skill)
                if (s.nTimesViewed == 0) continue
                if (s.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD) continue
                when (skill) {
                    Skill.READ -> read++
                    Skill.LISTEN -> listen++
                    Skill.INVERT -> invert++
                }
            }
        }
        return ActiveSetCounts(read, listen, invert)
    }

    /**
     * Introduced (word, skill) pairs split by the mastery threshold and summed
     * over all skills — [StudiedPairCounts.mastered] = pairs handed off to the
     * refresh pool (failureProbability < threshold), [StudiedPairCounts.active]
     * = pairs still being drilled. Pairs are counted per skill and summed, NOT
     * divided back to whole words, so a word mastered in two skills and active
     * in one adds 2 to mastered and 1 to active. Same domain split the pickers
     * use, so [StudiedPairCounts.active] equals the sum of [computeActiveSetCounts].
     * Drives the adaptive mastered-refresh cadence in QuizPicker.
     */
    fun computeStudiedPairCounts(): StudiedPairCounts {
        var mastered = 0
        var active = 0
        for (vocab in csvData) {
            if (vocab.ignore || !vocab.hasBeenIntroduced()) continue
            for (skill in Skill.ladder) {
                if (!vocab.isSkillUnlocked(skill)) continue
                val s = vocab.stats(skill)
                if (s.nTimesViewed == 0) continue
                if (s.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD) mastered++ else active++
            }
        }
        return StudiedPairCounts(mastered, active)
    }

    /**
     * Snapshot of the foreign words that have entered the rotation. Cached
     * after first computation; QuizController calls [notifyVocabIntroduced]
     * when a word's first round is committed so the cache stays current
     * without rescanning [csvData] on every LLM prompt.
     */
    fun getIntroducedForeignWords(): List<String> {
        if (!introducedForeignInitialized) {
            for (vocab in csvData) {
                if (vocab.hasBeenIntroduced()) introducedForeignSet.add(vocab.foreign)
            }
            introducedForeignInitialized = true
        }
        return introducedForeignSet.toList()
    }

    /**
     * Mark [vocab] as introduced in the cached set. Idempotent — calling on a
     * word that was already in the set is a no-op. Skipped when the cache
     * hasn't been built yet (next [getIntroducedForeignWords] call will scan).
     */
    fun notifyVocabIntroduced(vocab: Vocab) {
        if (!introducedForeignInitialized) return
        introducedForeignSet.add(vocab.foreign)
    }

    /**
     * Highest [Vocab.importance] tier across vocabs the learner has actually
     * been introduced to. Used by the LLM's [io.github.wandersalamander.vocabtrainer.llm.GrammarStyle]
     * as a difficulty budget: a learner whose introduced words top out at
     * importance 2 isn't ready for subjunctive-heavy sentences. Returns 0 when
     * nothing has been introduced yet — matches the tier-0 baseline in the
     * grammar ladder.
     */
    fun getMaxIntroducedImportance(): Int {
        var max = 0
        for (vocab in csvData) {
            if (vocab.hasBeenIntroduced() && vocab.importance > max) {
                max = vocab.importance
            }
        }
        return max
    }

    private fun readCsv(inputStream: InputStream): List<Vocab> =
        inputStream.bufferedReader().use { reader ->
            reader.lineSequence()
                .mapNotNull { Vocab.fromCsvLine(it, persistence) }
                .toList()
        }
}
