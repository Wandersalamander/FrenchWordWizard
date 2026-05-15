package com.example.myapplication.dictionary

import java.util.concurrent.TimeUnit

/**
 * A learning skill in the progressive-unlock ladder.
 *
 * [unlockThreshold] is the predecessor skill's failureProbability gate: this
 * skill becomes active for a word once the previous skill's stats drop below
 * this value. Unused for the first skill in the ladder (always unlocked).
 * Once unlocked AND practiced, a skill stays unlocked forever for that word
 * even if the predecessor's stats later drift back up — the unlock is sticky.
 */
enum class Skill(
    val storagePrefix: String,
    val displayName: String,
    val unlockThreshold: Float,
) {
    READ("", "Reading", 0.0f),  // empty prefix preserves legacy SharedPreferences keys — never rename
    LISTEN("listen_", "Listening", 0.4f),  // unlocks at 3 stars on READ
    INVERT("invert_", "Recall", 0.2f),  // unlocks at 4 stars on LISTEN
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
) {
    /** Single entry point for failure events so the counter and timestamp stay in sync. */
    fun recordFailure(amount: Float) {
        nTimesFailed += amount
        lastTimeFailed = System.currentTimeMillis()
    }

    fun failureProbability(): Float {
        if (nTimesViewed == 0) return 1.0f
        val base = 0.5f / nTimesViewed.toFloat()
        val raw = base + (1.0f - base) * nTimesFailed / nTimesViewed.toFloat()
        return raw.coerceIn(0.0f, 1.0f)
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
    }
}
