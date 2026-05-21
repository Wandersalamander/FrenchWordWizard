package io.github.wandersalamander.vocabtrainer.dictionary

import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.SkillStatsSnapshot
import io.github.wandersalamander.vocabtrainer.data.VocabPersistence
import io.github.wandersalamander.vocabtrainer.data.VocabSnapshot
import kotlin.math.ln
import kotlin.random.Random

/**
 * Parse the hash (column 4) from a CSV vocabulary line, or null if the line
 * is malformed. Shared with the progress-wipe code in SettingsActivity so we
 * have one place that knows the CSV layout.
 */
fun parseVocabHash(line: String): String? {
    val parts = line.split('\t', limit = 6)
    if (parts.size < 6) return null
    return parts[3].trim()
}

/**
 * A single vocabulary entry: the foreign word, its English translation,
 * importance tier, hashed identifier (used as the persistence key), and two
 * example sentences. Per-skill quiz stats live in [skillStats] and are
 * persisted via [savePreferences] through the supplied [VocabPersistence].
 */
data class Vocab(
    val foreign: String,
    val english: String,
    val importance: Int,
    val hash: String,
    val exampleHard: String,
    val exampleEasy: String,
    val persistence: VocabPersistence? = null,
) {
    val skillStats: Map<Skill, SkillStats> = Skill.ladder.associateWith { SkillStats() }

    var ignore: Boolean = false
    var flaggedHard: Boolean = false

    init {
        loadPreferences()
    }

    /** Randomly pick one of the two example sentences. */
    fun randomExample(): String =
        if (Random.nextBoolean()) exampleEasy else exampleHard

    /**
     * Resolve which built-in CSV sentence to use based on [source]. For
     * [SentenceSource.LLM] this returns the easy CSV sentence as a fallback —
     * callers wanting LLM output should call the LLM directly and fall through
     * to this only when generation is unavailable.
     */
    fun csvSentenceFor(source: SentenceSource): String = when (source) {
        SentenceSource.EASY, SentenceSource.LLM -> exampleEasy
        SentenceSource.HARD -> exampleHard
    }

    fun loadPreferences() {
        val p = persistence ?: return
        val snapshot = p.load(hash) ?: return
        for (skill in Skill.ladder) {
            val saved = snapshot.perSkill[skill] ?: continue
            val stats = skillStats.getValue(skill)
            stats.viewTimeMilli = saved.viewTimeMs
            stats.viewTimeMilli_prev = saved.viewTimeMsPrev
            stats.nTimesViewed = saved.nViewed
            stats.nTimesFailed = saved.nFailed
            stats.lastDisplayed = saved.lastDisplayed
            stats.lastTimeFailed = saved.lastFailed
            stats.recentInteractions = saved.recentInteractions
        }
        ignore = snapshot.ignore
        flaggedHard = snapshot.flaggedHard
    }

    /**
     * Persist the current in-memory state. When [reviewLog] is non-null
     * (round-commit path), it's appended to the review log in the same write
     * batch — the FK from log → stats is satisfied because the snapshot
     * upsert runs first. Non-round saves (Hard toggle, Learned ✓) pass null.
     */
    fun savePreferences(reviewLog: ReviewLogEntry? = null) {
        val p = persistence ?: return
        val perSkill = skillStats.mapValues { (_, stats) ->
            SkillStatsSnapshot(
                viewTimeMs = stats.viewTimeMilli,
                viewTimeMsPrev = stats.viewTimeMilli_prev,
                nViewed = stats.nTimesViewed,
                nFailed = stats.nTimesFailed,
                lastDisplayed = stats.lastDisplayed,
                lastFailed = stats.lastTimeFailed,
                recentInteractions = stats.recentInteractions,
            )
        }
        p.save(
            hash = hash,
            snapshot = VocabSnapshot(
                perSkill = perSkill,
                ignore = ignore,
                flaggedHard = flaggedHard,
            ),
            reviewLog = reviewLog,
        )
    }

    fun stats(skill: Skill): SkillStats = skillStats.getValue(skill)

    /**
     * True once this word has entered the rotation via its first ladder skill.
     * All other skills' (word, skill) pairs only enter the active pool once
     * this is true — first exposure always happens through the first skill.
     */
    fun hasBeenIntroduced(): Boolean = stats(Skill.ladder.first()).nTimesViewed > 0

    /**
     * Sticky per-word unlock: a skill is unlocked once its predecessor in the
     * ladder has been mastered below [Skill.unlockThreshold], and stays unlocked
     * thereafter (even if the predecessor's stats later regress). The first
     * ladder skill is always unlocked.
     */
    fun isSkillUnlocked(skill: Skill): Boolean {
        val idx = Skill.ladder.indexOf(skill)
        if (idx == 0) return true
        if (stats(skill).nTimesViewed > 0) return true  // sticky: once practiced, always unlocked
        val predecessor = Skill.ladder[idx - 1]
        return stats(predecessor).failureProbability() < skill.unlockThreshold
    }

    /** True when every skill in the ladder is retired below the finished threshold. */
    fun isFullyLearned(): Boolean =
        Skill.ladder.all { stats(it).failureProbability() < SKILL_FINISHED_THRESHOLD }

    fun sortValue(skill: Skill = Skill.READ): Double {
        val s = stats(skill)
        val fpb = s.failureProbability()
        if (fpb < SKILL_FINISHED_THRESHOLD) {
            // Mastered pairs sit outside the active picker entirely; the
            // cadence-based mastered-refresh slot in QuizController pulls them
            // via pickMasteredVocabToRefresh, so the two paths don't double-dip.
            return 0.0
        }
        val lastSeenHours = if (s.nTimesViewed == 0) {
            // Newly-active skill pair — lastDisplayed is the epoch (1970), which
            // would make viewedMinutesAgo absurd. Seed at a few hours so the
            // unlock is noticed without dominating the picker for a stretch of
            // consecutive rounds.
            2.0
        } else {
            s.viewedMinutesAgo() / 60.0
        }
        val hardMultiplier = if (flaggedHard) 3.0 else 1.0
        // Time as a gentle multiplier: 5s -> 1.0, 2s -> 0.7, 15s -> 1.4, clamped to [0.5, 2.0]
        val timeFactor = (0.4 + 0.12 * s.meanTimeViewedMilli() / 1000.0).coerceIn(0.5, 2.0)
        return (ln(1.0f + lastSeenHours * lastSeenHours) + 1.0f) * fpb * timeFactor * hardMultiplier
    }

    /**
     * For a (word, skill) pair that has already been mastered, an
     * approximation of how likely the user has forgotten it: 0.0 while still
     * comfortably within the pair's expected stability window, then climbing
     * with the overdue ratio (1.0 = just past due, capped at [OVERDUE_CAP]).
     * Returns 0.0 for pairs that aren't yet mastered. Used by the cadence-based
     * mastered-refresh picker to weight the candidate pool.
     */
    fun failureProbabilityMasteredWords(
        skill: Skill,
        now: Long = System.currentTimeMillis(),
    ): Double {
        val s = stats(skill)
        if (s.failureProbability() >= SKILL_FINISHED_THRESHOLD) return 0.0
        val daysSinceLastDisplayed = (now - s.lastDisplayed) / MILLIS_PER_DAY
        val cleanStreakDays = when {
            s.lastTimeFailed > 0L && s.lastDisplayed > s.lastTimeFailed ->
                (s.lastDisplayed - s.lastTimeFailed) / MILLIS_PER_DAY
            s.nTimesFailed > 0f ->
                // Legacy record: has failed historically but the timestamp wasn't
                // tracked back then. Don't credit it with an implicit clean streak —
                // pin to the floor so it cycles back into refresh on its normal cadence
                // instead of getting over-stabilized.
                REFRESH_FLOOR_DAYS
            else ->
                // Never failed for this skill — no streak anchor. Each clean view
                // contributes a few days of implicit stability instead.
                s.nTimesViewed * VIEW_AS_STREAK_DAYS
        }
        val stabilityDays = (cleanStreakDays + REFRESH_FLOOR_DAYS)
            .coerceIn(REFRESH_FLOOR_DAYS, REFRESH_CAP_DAYS)
        if (daysSinceLastDisplayed < stabilityDays) return 0.0
        return (daysSinceLastDisplayed / stabilityDays).coerceAtMost(OVERDUE_CAP)
    }

    fun pronounceableEn(): String = EnglishAbbreviations.expand(english)

    fun pronounceableForeign(language: Language): String =
        language.expandAbbreviations(foreign)

    companion object {
        // Below this failure probability, a (word, skill) pair exits active
        // drilling and is handed off to the refresh pool via
        // [failureProbabilityMasteredWords].
        const val SKILL_FINISHED_THRESHOLD = 0.1f

        // Refresh pool tuning. See [failureProbabilityMasteredWords] for how
        // these combine.
        private const val REFRESH_FLOOR_DAYS = 7.0
        private const val REFRESH_CAP_DAYS = 180.0
        private const val VIEW_AS_STREAK_DAYS = 3.0
        private const val OVERDUE_CAP = 3.0
        private const val MILLIS_PER_DAY = 86_400_000.0

        /**
         * Parse a single tab-separated CSV line into a [Vocab], or null when
         * the line is malformed (wrong column count or non-numeric importance).
         * Columns: foreign, english, importance, hash, exampleHard, exampleEasy.
         */
        fun fromCsvLine(line: String, persistence: VocabPersistence?): Vocab? {
            val parts = line.split('\t', ignoreCase = false, limit = 6)
            if (parts.size < 6) return null
            val importance = parts[2].trim().toIntOrNull() ?: return null
            return Vocab(
                foreign = parts[0].trim(),
                english = parts[1].trim(),
                importance = importance,
                hash = parts[3].trim(),
                exampleHard = parts[4].trim(),
                exampleEasy = parts[5].trim(),
                persistence = persistence,
            )
        }
    }
}
