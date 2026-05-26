package io.github.wandersalamander.vocabtrainer.dictionary

import kotlin.random.Random

/*
 * Selection algorithms over a [MyDictionary]: which (vocab, skill) pair to
 * surface for the next round. Three independent pickers feed the active-pool
 * loop in [io.github.wandersalamander.vocabtrainer.quiz.picker.QuizPicker]:
 *
 *  - [getActiveVocabWeighted]: weighted-random across active (word, skill)
 *    pairs, using [Vocab.sortValue] as the weight.
 *  - [pickUniformActiveVocab]: uniform random across the same pool — the
 *    backup safety floor that rescues near-mastered pairs starved by tiny
 *    weights.
 *  - [pickMasteredVocabToRefresh]: weighted-random across already-mastered
 *    pairs whose recall has likely decayed past the stability window.
 *  - [getInactiveVocab]: introduces a fresh word from the bundled CSV when
 *    the active pool is below threshold or the user pressed "new word".
 *
 * Defined as extension functions on [MyDictionary] so call sites read as
 * `dictionary.getActiveVocabWeighted(...)` — the dictionary stays the source
 * of vocab data; this file is just the algorithms operating on it.
 */

// Importance tier reserved for the canonical survival phrases (greetings,
// numbers, "where is the bathroom") — introduced in strict CSV order to
// match the expected sequence.
const val ESSENTIALS_IMPORTANCE: Int = 0

// Auto-pull a fresh inactive word whenever the count of distinct
// currently-drilling words falls below this — keeps the rotation varied
// for beginners without overwhelming them.
const val AUTO_INTRODUCE_THRESHOLD: Int = 5

fun MyDictionary.getInactiveVocab(): Pair<Vocab, Skill> {
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
fun MyDictionary.getActiveVocabWeighted(
    skillFilter: (Skill) -> Boolean = { true },
): Pair<Vocab, Skill> {
    val activePairs = collectActivePairs(skillFilter)
    // Gate on distinct *words*, not (word, skill) pair count — otherwise
    // LISTEN/INVERT unlocks inflate the pool from 3 distinct words and
    // freeze new-word intake.
    val distinctActiveWords = activePairs.distinctBy { it.first.hash }.size
    if (distinctActiveWords < AUTO_INTRODUCE_THRESHOLD) {
        return pickFallback(skillFilter)
    }
    return pickWeighted(activePairs)
}

/**
 * Uniform random pick over the active (word, skill) pool — same
 * membership as [getActiveVocabWeighted] (introduced, unlocked, sortValue
 * > 0) but every pair has equal probability. Backup safety floor for
 * near-mastered pairs whose tiny weighted-picker probability would
 * otherwise starve them. Returns null when the pool is empty so the
 * caller can fall through to the weighted picker.
 */
fun MyDictionary.pickUniformActiveVocab(
    skillFilter: (Skill) -> Boolean = { true },
): Pair<Vocab, Skill>? {
    val activePairs = collectActivePairs(skillFilter)
    if (activePairs.isEmpty()) return null
    return activePairs[Random.nextInt(activePairs.size)]
}

private fun MyDictionary.collectActivePairs(
    skillFilter: (Skill) -> Boolean,
): List<Pair<Vocab, Skill>> =
    csvData.flatMap { vocab ->
        if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
        Skill.ladder.mapNotNull { skill ->
            if (!skillFilter(skill)) return@mapNotNull null
            if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
            if (vocab.sortValue(skill) > 0.0) vocab to skill else null
        }
    }

private fun MyDictionary.pickFallback(
    skillFilter: (Skill) -> Boolean,
): Pair<Vocab, Skill> {
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
 * its refresh interval. Returns null when no mastered pair is due yet (e.g.
 * early users with mostly active words, or right after every mastered pair
 * has been refreshed). [skillFilter] excludes skills the same way
 * [getActiveVocabWeighted] does.
 *
 * Selection is two-tiered:
 *   1. Strict priority on the shortest [SkillStats.refreshIntervalDays] in
 *      the eligible pool — fragile (recently learned or recently failed)
 *      pairs surface before stable long-interval ones, since missing a
 *      1-day refresh costs more than missing a 64-day one.
 *   2. Within that interval band, weighted-random by overdue ratio ×
 *      (nTimesFailed + 1) — historically harder pairs lift, without
 *      entirely starving never-failed pairs.
 */
fun MyDictionary.pickMasteredVocabToRefresh(
    skillFilter: (Skill) -> Boolean = { true },
    now: Long = System.currentTimeMillis(),
): Pair<Vocab, Skill>? {
    data class Candidate(
        val pair: Pair<Vocab, Skill>,
        val overdueScore: Double,
        val intervalDays: Float,
        val nFailed: Float,
    )
    val candidates = csvData.flatMap { vocab ->
        if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
        Skill.ladder.mapNotNull { skill ->
            if (!skillFilter(skill)) return@mapNotNull null
            if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
            val score = vocab.failureProbabilityMasteredWords(skill, now)
            if (score <= 0.0) return@mapNotNull null
            val stats = vocab.stats(skill)
            Candidate(vocab to skill, score, stats.refreshIntervalDays, stats.nTimesFailed)
        }
    }
    if (candidates.isEmpty()) return null

    val minInterval = candidates.minOf { it.intervalDays }
    val band = candidates.filter { it.intervalDays == minInterval }

    val total = band.sumOf { it.overdueScore * (it.nFailed + 1f) }
    val randomValue = Random.nextDouble(0.0, total)
    var sum = 0.0
    for (c in band) {
        sum += c.overdueScore * (c.nFailed + 1f)
        if (sum >= randomValue) return c.pair
    }
    return band.last().pair
}
