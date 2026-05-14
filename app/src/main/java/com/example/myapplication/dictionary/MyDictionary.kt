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

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences) {
    val csvData: List<Vocab> = readCsv(inputStream)


    fun reloadPreferences() {
        csvData.forEach { it.loadPreferences() }
    }

    fun getActiveDataSize(): Int = getActiveDataSize(Skill.ladder.first())

    fun getFinishedDataSize(): Int = getFinishedDataSize(Skill.ladder.first())

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

    fun getIgnoredDataSize(): Int {
        return csvData.filter { it.ignore }.size
    }

    fun debugDictionary() {
        val activeData = csvData.filter { it.hasBeenIntroduced() }.iterator()
        while (activeData.hasNext()) {
            val vocab = activeData.next()
            println(vocab.debugSortValue())
        }
    }

    fun readCsv(inputStream: InputStream): List<Vocab> {
        return inputStream.bufferedReader().use { reader ->
            reader.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t', ignoreCase = false, limit = 6)
                    if (parts.size < 6) return@mapNotNull null
                    try {
                        Vocab(
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim().toInt(),
                            parts[3].trim(),
                            parts[4].trim(),
                            parts[5].trim(),
                            sharedPreferences
                        )
                    } catch (e: NumberFormatException) {
                        null
                    }
                }
                .toList()
        }
    }

    fun getInactiveVocab(): Pair<Vocab, Skill> {
        val firstSkill = Skill.ladder.first()
        val inactiveCsvData = csvData.filter { !it.hasBeenIntroduced() && !it.ignore }
        if (inactiveCsvData.isEmpty()) {
            return getActiveVocabWeightened()
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

    companion object {
        const val ESSENTIALS_IMPORTANCE: Int = 0
    }

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
    fun getActiveVocabWeightened(
        skillFilter: (Skill) -> Boolean = { true },
    ): Pair<Vocab, Skill> {
        val firstSkill = Skill.ladder.first()
        val activePairs: List<Pair<Vocab, Skill>> = csvData.flatMap { vocab ->
            if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
            Skill.ladder.mapNotNull { skill ->
                if (!skillFilter(skill)) return@mapNotNull null
                if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
                if (vocab.sortValue(skill) > 0.0) vocab to skill else null
            }
        }
        if (activePairs.size < 20) {
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
        val totalChance = activePairs.sumOf { (vocab, skill) -> vocab.sortValue(skill) }
        val randomValue = Random.nextDouble(0.0, totalChance)
        var sum = 0.0
        var idx = 0
        for (i in 0 until activePairs.size) {
            val (vocab, skill) = activePairs[i]
            sum += vocab.sortValue(skill)
            idx = i
            if (sum >= randomValue) {
                break
            }
        }
        return activePairs[idx]
    }

}
