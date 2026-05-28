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

// Default auto-pull threshold when the user hasn't set one yet. The active
// callers read [AppPrefs.KEY_AUTO_ADD_THRESHOLD] and pass it through to
// [getActiveVocabWeighted]; this is the fallback for paths without prefs
// access (tests, foreground service) and the initial value for the settings
// stepper. Keeps the rotation varied for beginners without overwhelming.
const val AUTO_INTRODUCE_THRESHOLD: Int = 5

// Range surfaced in Settings — picked so the stepper bottoms out one
// above zero (auto-add still kicks in only when the pool is empty) and
// tops out somewhere that requires deliberate user intent.
const val AUTO_INTRODUCE_THRESHOLD_MIN: Int = 1
const val AUTO_INTRODUCE_THRESHOLD_MAX: Int = 20

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
    autoIntroduceThreshold: Int = AUTO_INTRODUCE_THRESHOLD,
): Pair<Vocab, Skill> {
    val activePairs = collectActivePairs(skillFilter)
    // Gate the auto-add on words still active in READ *only* — not distinct
    // words across all skills. New words always enter via READ, so the real
    // question behind "do we need a fresh word?" is "how many words am I still
    // learning to read?". A word whose READ has retired (now drilling only
    // LISTEN/INVERT) has already made room for a new one and must not keep the
    // gate shut; counting any-active-skill would let a LISTEN/INVERT backlog
    // freeze all new-word intake. Each (word, READ) pair is unique in
    // activePairs, so this count is already the distinct READ-word count.
    val activeReadWords = activePairs.count { it.second == Skill.READ }
    if (activeReadWords < autoIntroduceThreshold) {
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
 * Selection is tiered:
 *   1. Drilled-pair priority: a pair that earned mastery through active-pool
 *      practice always outranks a [SkillStats.fastTracked] one (cleared on
 *      its first exposure, never actually drilled). Fast-tracked pairs only
 *      enter the draw when no drilled pair is due — they're the weakest
 *      evidence of real learning, so they don't consume a retention slot
 *      while a genuinely drilled pair is waiting. This precedes the interval
 *      tier: a drilled pair due at a long interval still beats a fast-tracked
 *      pair due at a short one.
 *   2. Within the chosen pool, strict priority on the shortest
 *      [SkillStats.refreshIntervalDays] — fragile (recently learned or
 *      recently failed) pairs surface before stable long-interval ones, since
 *      missing a 1-day refresh costs more than missing a 64-day one.
 *   3. Within that interval band, weighted-random by overdue ratio ×
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
        val fastTracked: Boolean,
    )
    val candidates = csvData.flatMap { vocab ->
        if (vocab.ignore || !vocab.hasBeenIntroduced()) return@flatMap emptyList()
        Skill.ladder.mapNotNull { skill ->
            if (!skillFilter(skill)) return@mapNotNull null
            if (!vocab.isSkillUnlocked(skill)) return@mapNotNull null
            val score = vocab.failureProbabilityMasteredWords(skill, now)
            if (score <= 0.0) return@mapNotNull null
            val stats = vocab.stats(skill)
            Candidate(
                vocab to skill, score, stats.refreshIntervalDays,
                stats.nTimesFailed, stats.fastTracked,
            )
        }
    }
    if (candidates.isEmpty()) return null

    // Tier 1: prefer pairs drilled through the active pool; fast-tracked pairs
    // only surface when no drilled pair is due (see kdoc).
    val drilled = candidates.filter { !it.fastTracked }
    val pool = if (drilled.isNotEmpty()) drilled else candidates

    val minInterval = pool.minOf { it.intervalDays }
    val band = pool.filter { it.intervalDays == minInterval }

    val total = band.sumOf { it.overdueScore * (it.nFailed + 1f) }
    val randomValue = Random.nextDouble(0.0, total)
    var sum = 0.0
    for (c in band) {
        sum += c.overdueScore * (c.nFailed + 1f)
        if (sum >= randomValue) return c.pair
    }
    return band.last().pair
}
