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
    // INVERT("invert_", "Inversion", 0.2f),  // unlocks at 4 stars on LISTEN
    ;

    companion object {
        // Order = unlock chain (each skill requires the previous one mastered).
        // Insert new skills here, not at the enum's natural position.
        val ladder: List<Skill> = listOf(
            READ,
            LISTEN,
            // INVERT,
        )
    }
}

data class SkillStats(
    var viewTimeMilli: Long = 10000L,
    var viewTimeMilli_prev: Long = 10000L,
    var nTimesViewed: Int = 0,
    var nTimesFailed: Float = 0.0f,
    var lastDisplayed: Long = 0L,
) {
    fun failureProbability(): Float {
        return if (nTimesViewed == 0) {
            1.0f
        } else {
            val base = 0.5f / nTimesViewed.toFloat()
            val raw = base + (1.0f - base) * nTimesFailed / nTimesViewed.toFloat()
            raw.coerceIn(0.0f, 1.0f)
        }
    }

    fun meanTimeViewedMilli(): Double = viewTimeMilli.toDouble()

    fun viewedMiutesAgo(): Long =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)

    fun getTimeString(): String =
        String.format("⧖ %.1f s", meanTimeViewedMilli() / 1e3)

    fun getStarsString(): String {
        val successProbability = (1.0f - failureProbability()).coerceIn(0.0f, 1.0f)
        val starNumber = 5
        val nFullStars: Int = (starNumber * successProbability).toInt()
        val remainder: Int = starNumber - nFullStars
        return "★".repeat(nFullStars) + "☆".repeat(remainder)
    }

    fun getInfoString(): String = getTimeString() + "\n" + getStarsString()
}
