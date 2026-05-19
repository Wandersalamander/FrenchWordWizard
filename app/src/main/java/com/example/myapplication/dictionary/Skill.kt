package com.example.myapplication.dictionary

import com.example.myapplication.data.VocabInteraction
import java.util.concurrent.TimeUnit

/**
 * A learning skill in the progressive-unlock ladder.
 *
 * [unlockThreshold] is the predecessor skill's failureProbability gate: this
 * skill becomes active for a word once the previous skill's stats drop below
 * this value. Unused for the first skill in the ladder (always unlocked).
 * Once unlocked AND practiced, a skill stays unlocked forever for that word
 * even if the predecessor's stats later drift back up — the unlock is sticky.
 *
 * The enum's [name] (READ / LISTEN / INVERT) is the value persisted in Room
 * via [com.example.myapplication.data.Converters]. Renaming an enum case
 * would orphan existing rows — add a new case instead.
 */
enum class Skill(
    val displayName: String,
    val unlockThreshold: Float,
) {
    READ("Reading", 0.0f),
    LISTEN("Listening", 0.4f),  // unlocks at 3 stars on READ
    INVERT("Recall", 0.2f),  // unlocks at 4 stars on LISTEN
    ;

    companion object {
        // Order = unlock chain (each skill requires the previous one mastered).
        // Insert new skills here, not at the enum's natural position.
        val ladder: List<Skill> = listOf(
            READ,
            LISTEN,
            INVERT,
        )
    }
}

data class SkillStats(
    var viewTimeMilli: Long = DEFAULT_VIEW_TIME_MS,
    var viewTimeMilli_prev: Long = DEFAULT_VIEW_TIME_MS,
    var nTimesViewed: Int = 0,
    var nTimesFailed: Float = 0.0f,
    var lastDisplayed: Long = 0L,
    var lastTimeFailed: Long = 0L,
    var recentInteractions: List<VocabInteraction> = emptyList(),
) {
    /** Single entry point for failure events so the counter and timestamp stay in sync. */
    fun recordFailure(amount: Float) {
        nTimesFailed += amount
        lastTimeFailed = System.currentTimeMillis()
    }

    /**
     * Append one round's outcome to [recentInteractions] and trim the window
     * to the last [MAX_INTERACTIONS] entries. Called once per committed round
     * from the quiz save path; compromised rounds (rolled back via [copyFrom])
     * skip this call.
     */
    fun recordInteraction(failAmount: Float) {
        val entry = VocabInteraction(
            timestampMs = System.currentTimeMillis(),
            failAmount = failAmount,
        )
        val updated = recentInteractions + entry
        recentInteractions = if (updated.size > MAX_INTERACTIONS) {
            updated.subList(updated.size - MAX_INTERACTIONS, updated.size).toList()
        } else {
            updated
        }
    }

    /**
     * Copy every field from [other] into this instance, in place. Used to
     * roll back a round when the active skill's state changed mid-round in a
     * way that compromises the result (e.g. listening toggled off in a LISTEN
     * round). The receiving instance is the one held in the Vocab's
     * skillStats map, so we mutate rather than swap references.
     */
    fun copyFrom(other: SkillStats) {
        viewTimeMilli = other.viewTimeMilli
        viewTimeMilli_prev = other.viewTimeMilli_prev
        nTimesViewed = other.nTimesViewed
        nTimesFailed = other.nTimesFailed
        lastDisplayed = other.lastDisplayed
        lastTimeFailed = other.lastTimeFailed
        recentInteractions = other.recentInteractions
    }

    fun failureProbability(): Float {
        if (nTimesViewed == 0) return 1.0f
        val base = 0.5f / nTimesViewed.toFloat()
        val counterRate = (nTimesFailed / nTimesViewed.toFloat()).coerceIn(0f, 1f)

        // Blend the lifetime counter rate with a recency-weighted rate over
        // [recentInteractions]. With an empty window (legacy migrated rows,
        // or a fresh skill) the formula collapses to today's exact behavior;
        // as the window fills, recent outcomes pull the estimate, but the
        // counters never lose all weight (Bayesian shrinkage ratio
        // n / (n + WINDOW_BLEND_PRIOR)).
        val effectiveRate = if (recentInteractions.isEmpty()) {
            counterRate
        } else {
            val windowRate = windowFailureProbability()
            val n = recentInteractions.size
            val blendWeight = n.toFloat() / (n + WINDOW_BLEND_PRIOR)
            blendWeight * windowRate + (1f - blendWeight) * counterRate
        }

        val raw = base + (1.0f - base) * effectiveRate
        val clamped = raw.coerceIn(0.0f, 1.0f)
        // Sustained-slowness gate: a clean run of fast Next-presses can drive
        // raw fpb under the mastery threshold even when the user is still
        // actually struggling. If either of the last two view-time samples
        // exceeds SLOW_VIEW_TIME_MAX_MS, floor the reported fpb just above the
        // mastery cut so the (word, skill) pair stays in the active pool. Both
        // rounds must be fast for the pair to slip through.
        return if (clamped < SLOW_FPB_FLOOR &&
            maxOf(viewTimeMilli, viewTimeMilli_prev) > SLOW_VIEW_TIME_MAX_MS) {
            SLOW_FPB_FLOOR
        } else {
            clamped
        }
    }

    /**
     * Recency-weighted mean of [recentInteractions] failAmounts. The newest
     * entry has weight 1.0, each older entry × [WINDOW_DECAY], so a
     * full 10-entry window weighs the newest sample ≈6× the oldest. Returns 0
     * for an empty window (callers must guard — see [failureProbability]).
     */
    private fun windowFailureProbability(): Float {
        if (recentInteractions.isEmpty()) return 0f
        var weightSum = 0.0
        var failSum = 0.0
        val n = recentInteractions.size
        for (i in 0 until n) {
            // List is chronological (append at end); newest = last index.
            val agePos = n - 1 - i
            val w = Math.pow(WINDOW_DECAY.toDouble(), agePos.toDouble())
            weightSum += w
            failSum += w * recentInteractions[i].failAmount.coerceIn(0f, 1f)
        }
        return (failSum / weightSum).toFloat().coerceIn(0f, 1f)
    }

    fun meanTimeViewedMilli(): Double = viewTimeMilli.toDouble()

    fun viewedMinutesAgo(): Long =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)

    fun getTimeString(): String =
        String.format("⧖ %.1f s", meanTimeViewedMilli() / 1e3)

    fun getStarsString(): String {
        val successProbability = (1.0f - failureProbability()).coerceIn(0.0f, 1.0f)
        val totalStars = 5
        val full: Int = (totalStars * successProbability).toInt()
        return "★".repeat(full) + "☆".repeat(totalStars - full)
    }

    fun getInfoString(): String = getTimeString() + "\n" + getStarsString()

    companion object {
        // Seed value for a freshly-encountered word's view-time estimate (10s).
        // High enough that the first round's actual time gets blended in without
        // an undue jump up or down.
        const val DEFAULT_VIEW_TIME_MS = 10_000L

        // If either of the last two view-time samples exceeds this,
        // failureProbability is floored so the pair can't slip past the mastery
        // gate. Both rounds must come in under 5s for the pair to be allowed
        // through — one fast round can no longer compensate for one slow one.
        const val SLOW_VIEW_TIME_MAX_MS = 5_000L

        // Kept in lockstep with Vocab.SKILL_FINISHED_THRESHOLD (0.10) + 0.05:
        // just above the mastery cut so the pair stays active without being
        // surfaced as urgently as a fresh failure would.
        const val SLOW_FPB_FLOOR = 0.15f

        // Length of the rolling per-round outcome window persisted alongside
        // the counters. Sized to fit comfortably in one TEXT cell while still
        // giving recency-weighted fpb formulas enough signal to work with.
        const val MAX_INTERACTIONS = 10

        // Per-entry weight ratio in [windowFailureProbability]: oldest weight
        // = newest * decay^(MAX_INTERACTIONS - 1). At 0.8 the newest sample
        // is ≈6× the weight of the oldest (0.8^9 ≈ 0.13), giving a half-life
        // of ~3 entries — recent enough to react to a struggling run, slow
        // enough that one bad round doesn't tank the estimate.
        const val WINDOW_DECAY = 0.8f

        // Bayesian shrinkage constant for blending the window rate against
        // the lifetime counter rate: window weight = n / (n + this). With
        // n=10 (full window) the window gets ~77 % weight; a single fresh
        // entry only gets 25 %, so the counters still anchor early-stage
        // estimates. Lower = window dominates sooner.
        const val WINDOW_BLEND_PRIOR = 3f
    }
}
