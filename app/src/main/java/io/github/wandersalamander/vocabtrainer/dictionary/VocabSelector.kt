package io.github.wandersalamander.vocabtrainer.dictionary

import kotlin.math.pow
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
// tops out somewhere that requires deliberate user intent. The ceiling is
// a soft UX guardrail, not a technical limit: it's only used to clamp the
// value and disable the "+" key. 40 leaves room for a near-fluent language
// to run a large top-up pool while still flagging that big pools dilute how
// often any single word resurfaces.
const val AUTO_INTRODUCE_THRESHOLD_MIN: Int = 1
const val AUTO_INTRODUCE_THRESHOLD_MAX: Int = 40

// Geometric decay applied across importance tiers when a calibration frontier F
// is set. New words PEAK at tier F+1 — the first tier the user did NOT clear,
// i.e. their genuine learning edge — and each tier below it gets × this factor
// (F at 0.25, F-1 at 0.0625, …); tiers above F+1 are excluded entirely. So a
// placed user is fed mostly-new words from F+1, with only a light trickle of
// review from the tiers they already cleared (catching the few they missed and
// banking known words as mastered). Unused on the true-beginner path (no
// frontier → lowest tier first). See [pickInactiveWord].
const val INTRODUCTION_TIER_DECAY: Double = 0.25

/**
 * Pick the next not-yet-introduced word, always paired with the first skill on
 * the ladder (new words always enter via READ). Falls back to
 * [getActiveVocabWeighted] when every word has already been introduced.
 *
 * [frontier] is the calibrated highest-cleared tier (see [INTRODUCTION_TIER_DECAY]):
 * when non-null it biases introduction toward the band [0, frontier+1], peaking
 * at frontier+1 (the learning edge); null is the true-beginner default (lowest
 * un-introduced tier first).
 */
fun MyDictionary.getInactiveVocab(frontier: Int? = null): Pair<Vocab, Skill> {
    val firstSkill = Skill.ladder.first()
    val word = pickInactiveWord(frontier)
        ?: return getActiveVocabWeighted(frontier = frontier)
    return word to firstSkill
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
    frontier: Int? = null,
): Pair<Vocab, Skill> {
    val activePairs = collectActivePairs(skillFilter)
    // Gate the auto-add on words still active in READ *only* — not distinct
    // words across all skills. New words always enter via READ, so the real
    // question behind "do we need a fresh word?" is "how many words am I still
    // learning to read?". A word whose READ has retired (now drilling only
    // LISTEN/INVERT) has already made room for a new one and must not keep the
    // gate shut; counting any-active-skill would let a LISTEN/INVERT backlog
    // freeze all new-word intake.
    //
    // Counted WITHOUT [skillFilter], deliberately: the filter says which tiers
    // may be SERVED right now, not how full the reading pool is. Deriving this
    // from the filtered pairs made toggling the READ tier off read as "reading
    // pool empty" — and every round pulled in a fresh word, blowing past the
    // user's pool limit.
    val activeReadWords = csvData.count {
        !it.ignore && it.hasBeenIntroduced() && it.state(Skill.READ).isActiveDraw
    }
    if (activeReadWords < autoIntroduceThreshold) {
        return pickFallback(skillFilter, frontier)
    }
    if (activePairs.isEmpty()) {
        // Every servable pair is filtered out (e.g. READ toggled off with no
        // other tier active). Serve READ despite the filter rather than fall
        // through to introduction — the toggle is a preference; intake past
        // the pool limit is not. Matches the documented never-deadlock rule
        // ("the first skill is still the no-candidate fallback even if
        // filtered out"). Non-empty here: the gate above guaranteed at least
        // [autoIntroduceThreshold] ≥ 1 active READ pairs.
        val readPairs = collectActivePairs { it == Skill.READ }
        if (readPairs.isNotEmpty()) return pickWeighted(readPairs)
        return pickFallback(skillFilter, frontier)
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
            // Active pool = unlocked pairs not yet mastered (PairState.Active),
            // plus freshly-unlocked New pairs that enter rotation here. This is
            // exactly the old `isSkillUnlocked && sortValue > 0` membership —
            // the refresh pool (DueForRefresh) is now a different state branch,
            // so the two pools are disjoint by construction.
            if (vocab.state(skill).isActiveDraw) vocab to skill else null
        }
    }

private fun MyDictionary.pickFallback(
    skillFilter: (Skill) -> Boolean,
    frontier: Int? = null,
): Pair<Vocab, Skill> {
    val firstSkill = Skill.ladder.first()
    pickInactiveWord(frontier)?.let { return it to firstSkill }
    val available = csvData.filter {
        !it.ignore && !(it.hasBeenIntroduced() && it.isFullyLearned())
    }
    if (available.isEmpty()) {
        return csvData[Random.nextInt(csvData.size)] to firstSkill
    }
    val pickedVocab = available[Random.nextInt(available.size)]
    // The vocab may have one or more skills retired; pick the first one
    // that's actually an active-pool draw so we don't waste a round on a
    // retired (mastered) skill.
    val pickedSkill = Skill.ladder.firstOrNull {
        skillFilter(it) && pickedVocab.state(it).isActiveDraw
    } ?: firstSkill
    return pickedVocab to pickedSkill
}

/**
 * The next not-yet-introduced (non-ignored) word, or null when every word has
 * already been introduced. Shared by [getInactiveVocab] and [pickFallback].
 *
 * With a calibrated [frontier] F, while the band [0, F+1] still holds
 * un-introduced words a tier within it is chosen by geometric decay
 * ([INTRODUCTION_TIER_DECAY], peak at F+1 — the first un-cleared tier) and tiers
 * above F+1 are never offered. Once that band is exhausted — or when [frontier]
 * is null — it falls back to the lowest un-introduced tier, which is how
 * introduction advances past F+1 on its own (exhaustion-based: the lowest
 * remaining tier is then F+2). Within the chosen tier, essentials (tier 0) come
 * in strict CSV order and higher tiers at random (see [pickNextInactive]).
 */
private fun MyDictionary.pickInactiveWord(frontier: Int? = null): Vocab? {
    val inactive = csvData.filter { !it.hasBeenIntroduced() && !it.ignore }
    if (inactive.isEmpty()) return null

    if (frontier != null) {
        // Teach one tier ABOVE the highest cleared frontier: F+1 is the first
        // tier the user did NOT clear — their learning edge — so new words peak
        // there, with the cleared tiers below only trickling in for review.
        val peakTier = frontier + 1
        val band = inactive.filter { it.importance <= peakTier }
        if (band.isNotEmpty()) {
            val tier = pickTierByDecay(band.map { it.importance }.distinct(), peakTier)
            return pickNextInactive(band.filter { it.importance == tier }, tier)
        }
    }

    val minImportance = inactive.minOf { it.importance }
    return pickNextInactive(inactive.filter { it.importance == minImportance }, minImportance)
}

/**
 * Weighted-random tier choice over the [tiers] present in the band, each
 * weighted [INTRODUCTION_TIER_DECAY]^([peakTier] − tier): the [peakTier] (or the
 * highest tier available below it) peaks and each lower tier decays
 * geometrically. [tiers] must be non-empty and all ≤ [peakTier].
 */
private fun pickTierByDecay(tiers: List<Int>, peakTier: Int): Int {
    val total = tiers.sumOf { INTRODUCTION_TIER_DECAY.pow(peakTier - it) }
    val randomValue = Random.nextDouble(0.0, total)
    var sum = 0.0
    for (tier in tiers) {
        sum += INTRODUCTION_TIER_DECAY.pow(peakTier - tier)
        if (sum >= randomValue) return tier
    }
    return tiers.last()
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
 * A word's easier ladder skills are snoozed for this long after any *harder*
 * mastered skill of the same word is refreshed (see tier 0 in
 * [pickMasteredVocabToRefresh]). One day so the same word never resurfaces
 * twice in a sitting; by the time the easier skill comes back the extra wait
 * has accrued, so the late-review bonus in [SkillStats.applyRoundCommit]
 * stretches its interval on its own — no explicit cross-skill crediting.
 */
const val MASTERED_REFRESH_SIBLING_SNOOZE_MS: Long = 86_400_000L

/**
 * The single (word, skill) pair this word would currently contribute to the
 * refresh pool, or null if the word has nothing surfaceable right now. This is
 * tier 0 of the refresh policy, factored out so the picker
 * ([pickMasteredVocabToRefresh]) and the "N due" badge
 * ([MyDictionary.computeDueMasteredCount]) can't disagree about what counts as
 * due — the badge must never claim a word the picker would skip.
 *
 * A word's three ladder skills are correlated recall of the same vocabulary, so
 * the pool surfaces only the *hardest* (highest-ladder) skill currently due —
 * recalling it is the strongest single signal the word still holds. The word's
 * easier skills are snoozed for [MASTERED_REFRESH_SIBLING_SNOOZE_MS] after any
 * harder skill is refreshed; that snooze is *derived* from the harder skill's
 * [SkillStats.lastDisplayed] (no stored flag) so the easier skill's own schedule
 * is left untouched and resurfaces a day later with the extra wait intact. No
 * cross-skill crediting — the stagger plus the elapsed-anchored doubling space
 * the easier skills on their own.
 *
 * Returns null when the word is ignored / not introduced, when no skill is past
 * its interval, or when the only due skills are snoozed behind a harder one.
 */
fun Vocab.surfaceableRefreshSkill(
    skillFilter: (Skill) -> Boolean = { true },
    now: Long = System.currentTimeMillis(),
): Skill? {
    if (ignore || !hasBeenIntroduced()) return null
    // Hardest (highest-ladder) skill currently due for refresh — i.e. whose
    // state is DueForRefresh (mastered, unlocked, past its interval).
    val rep = Skill.ladder.lastOrNull { skill ->
        skillFilter(skill) && state(skill, now) is PairState.DueForRefresh
    } ?: return null
    val repIdx = Skill.ladder.indexOf(rep)
    // A harder sibling that's mastered (Mastered or DueForRefresh → isLearned)
    // and was shown inside the snooze window keeps the easier skill dormant.
    // Deliberately NOT gated on skillFilter: the snooze reflects a real recent
    // display of this word, which stands whether or not that harder tier is
    // currently enabled. Gating it on the filter meant disabling a tier
    // un-snoozed its easier siblings and *raised* the "N due" count — a toggle
    // that makes no sense to a user.
    val snoozedByHarder = Skill.ladder.drop(repIdx + 1).any { harder ->
        state(harder, now).isLearned &&
            now - stats(harder).lastDisplayed < MASTERED_REFRESH_SIBLING_SNOOZE_MS
    }
    return if (snoozedByHarder) null else rep
}

/**
 * Pick a mastered (word, skill) pair whose recall has likely decayed past
 * its refresh interval. Returns null when no mastered pair is due yet (e.g.
 * early users with mostly active words, or right after every mastered pair
 * has been refreshed). [skillFilter] excludes skills the same way
 * [getActiveVocabWeighted] does.
 *
 * Selection is tiered:
 *   0. One-per-word, hardest skill, with sibling snooze — see
 *      [surfaceableRefreshSkill] for the rationale. Each word contributes at
 *      most one candidate (its hardest due, un-snoozed skill).
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
    // Tier 0: one candidate per word — its hardest due, un-snoozed skill.
    val candidates = csvData.mapNotNull { vocab ->
        val skill = vocab.surfaceableRefreshSkill(skillFilter, now) ?: return@mapNotNull null
        val stats = vocab.stats(skill)
        Candidate(
            vocab to skill,
            vocab.failureProbabilityMasteredWords(skill, now),
            stats.refreshIntervalDays,
            stats.nTimesFailed,
            stats.fastTracked,
        )
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
