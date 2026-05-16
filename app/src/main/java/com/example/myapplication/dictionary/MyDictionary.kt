package com.example.myapplication.dictionary

import android.content.Context
import android.content.SharedPreferences
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlin.random.Random

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
 * Per-skill counts shown on the progress bars: how many words have started
 * the skill ([introduced]) and how many have either been marked learned or
 * dropped below the retirement threshold ([finished]).
 */
data class SkillProgress(var introduced: Int = 0, var finished: Int = 0)

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences? = null) {
    val csvData: List<Vocab> = readCsv(inputStream)

    // Cache of foreign words that have entered the rotation. Used by the LLM
    // prompt to optionally seed example sentences with previously-studied
    // vocabulary. Built lazily on first read and incrementally maintained via
    // [notifyVocabIntroduced] so we don't scan ~10k entries per LLM call.
    private val introducedForeignSet: LinkedHashSet<String> = LinkedHashSet()
    private var introducedForeignInitialized: Boolean = false

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
     * Words considered "done" for [skill]: either marked learned, or the
     * skill's failureProbability has dropped below the retirement threshold.
     */
    fun getFinishedDataSize(skill: Skill): Int =
        csvData.count {
            it.stats(skill).nTimesViewed > 0 &&
                (it.ignore || it.stats(skill).failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD)
        }

    /**
     * Compute introduced/finished counts for every ladder skill in a single
     * pass over [csvData]. Per-round progress-bar refresh used to call
     * [getActiveDataSize] + [getFinishedDataSize] for each of N skills (2N
     * full scans); this collapses to one.
     */
    fun computeAllSkillProgress(): Map<Skill, SkillProgress> {
        val out: Map<Skill, SkillProgress> =
            Skill.ladder.associateWith { SkillProgress() }
        for (vocab in csvData) {
            for (skill in Skill.ladder) {
                val stats = vocab.stats(skill)
                if (stats.nTimesViewed <= 0) continue
                val progress = out.getValue(skill)
                progress.introduced++
                if (vocab.ignore || stats.failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD) {
                    progress.finished++
                }
            }
        }
        return out
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

    private fun readCsv(inputStream: InputStream): List<Vocab> =
        inputStream.bufferedReader().use { reader ->
            reader.lineSequence()
                .mapNotNull { Vocab.fromCsvLine(it, sharedPreferences) }
                .toList()
        }

    fun getInactiveVocab(): Pair<Vocab, Skill> {
        val firstSkill = Skill.ladder.first()
        val inactiveCsvData = csvData.filter { !it.hasBeenIntroduced() && !it.ignore }
        if (inactiveCsvData.isEmpty()) {
            return getActiveVocabWeighted()
        }
        val minImportance = inactiveCsvData.minOf { it.importance }
        val candidates = inactiveCsvData.filter { it.importance == minImportance }
        return pickNextInactive(candidates, minImportance) to firstSkill
    }

    /**
     * Tier 0 (essentials) is introduced in strict CSV order — the canonical 50
     * survival phrases need to come up in a fixed sequence regardless of
     * shuffle. Higher tiers stay random within the tier so practice still feels
     * varied.
     */
    private fun pickNextInactive(candidates: List<Vocab>, minImportance: Int): Vocab =
        if (minImportance == ESSENTIALS_IMPORTANCE) candidates.first()
        else candidates[Random.nextInt(candidates.size)]

    /**
     * Selection pool is every (word, skill) pair where the word has been
     * introduced and the skill is not yet retired (sortValue > 0). Each pair
     * is independently weighted by its own sortValue, so a word with two
     * still-active skills appears twice — that's intentional: more unfinished
     * skills means more practice needed.
     *
     * [skillFilter] excludes skills from the selection pool — e.g. callers can
     * skip [Skill.LISTEN] when the user has muted listening mode. The first
     * skill ([Skill.READ]) is still used as the no-candidate fallback even if
     * filtered out, since something has to be returned.
     */
    fun getActiveVocabWeighted(
        skillFilter: (Skill) -> Boolean = { true },
    ): Pair<Vocab, Skill> {
        val activePairs: List<Pair<Vocab, Skill>> = csvData.flatMap { vocab ->
            if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
            Skill.ladder.mapNotNull { skill ->
                if (!skillFilter(skill)) return@mapNotNull null
                if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
                if (vocab.sortValue(skill) > 0.0) vocab to skill else null
            }
        }
        // Gate on distinct *words*, not (word, skill) pair count — otherwise
        // LISTEN/INVERT unlocks inflate the pool from 3 distinct words and
        // freeze new-word intake.
        val distinctActiveWords = activePairs.distinctBy { it.first.hash }.size
        if (distinctActiveWords < AUTO_INTRODUCE_THRESHOLD) {
            return pickFallback(skillFilter)
        }
        return pickWeighted(activePairs)
    }

    private fun pickFallback(skillFilter: (Skill) -> Boolean): Pair<Vocab, Skill> {
        val firstSkill = Skill.ladder.first()
        val inactive = csvData.filter { !it.hasBeenIntroduced() && !it.ignore }
        if (inactive.isNotEmpty()) {
            val minImportance = inactive.minOf { it.importance }
            val candidates = inactive.filter { it.importance == minImportance }
            return pickNextInactive(candidates, minImportance) to firstSkill
        }
        val available = csvData.filter {
            !it.ignore && !(it.hasBeenIntroduced() && it.isFullyLearned())
        }
        if (available.isEmpty()) {
            return csvData[Random.nextInt(csvData.size)] to firstSkill
        }
        val pickedVocab = available[Random.nextInt(available.size)]
        // The vocab may have one or more skills retired; pick the first one
        // that's actually active so we don't waste a round on a retired skill.
        val pickedSkill = Skill.ladder.firstOrNull {
            skillFilter(it) && pickedVocab.isSkillUnlocked(it) && pickedVocab.sortValue(it) > 0.0
        } ?: firstSkill
        return pickedVocab to pickedSkill
    }

    private fun pickWeighted(pairs: List<Pair<Vocab, Skill>>): Pair<Vocab, Skill> {
        val totalChance = pairs.sumOf { (vocab, skill) -> vocab.sortValue(skill) }
        val randomValue = Random.nextDouble(0.0, totalChance)
        var sum = 0.0
        for (pair in pairs) {
            sum += pair.first.sortValue(pair.second)
            if (sum >= randomValue) return pair
        }
        return pairs.last()
    }

    /**
     * Pick a mastered (word, skill) pair whose recall has likely decayed past
     * its stability window, weighted by how overdue it is. Returns null when
     * no mastered pair is due yet (e.g. early users with mostly active words,
     * or right after every mastered pair has been refreshed). [skillFilter]
     * excludes skills the same way [getActiveVocabWeighted] does.
     */
    fun pickMasteredVocabToRefresh(
        skillFilter: (Skill) -> Boolean = { true },
    ): Pair<Vocab, Skill>? {
        val now = System.currentTimeMillis()
        val candidates: List<Pair<Pair<Vocab, Skill>, Double>> = csvData.flatMap { vocab ->
            if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
            Skill.ladder.mapNotNull { skill ->
                if (!skillFilter(skill)) return@mapNotNull null
                if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
                val score = vocab.failureProbabilityMasteredWords(skill, now)
                if (score > 0.0) (vocab to skill) to score else null
            }
        }
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.second }
        val randomValue = Random.nextDouble(0.0, total)
        var sum = 0.0
        for ((pair, weight) in candidates) {
            sum += weight
            if (sum >= randomValue) return pair
        }
        return candidates.last().first
    }

    companion object {
        const val ESSENTIALS_IMPORTANCE: Int = 0

        // Auto-pull a fresh inactive word whenever the count of distinct
        // currently-drilling words falls below this — keeps the rotation
        // varied for beginners without overwhelming them.
        const val AUTO_INTRODUCE_THRESHOLD: Int = 5
    }
}
