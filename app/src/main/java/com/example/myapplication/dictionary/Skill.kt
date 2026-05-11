package com.example.myapplication.dictionary

enum class Skill(val storagePrefix: String) {
    READ(""),  // empty prefix preserves legacy SharedPreferences keys — never rename
    // LISTEN("listen_"),
    // INVERT("invert_"),
    ;

    companion object {
        // Order = unlock chain (each skill requires the previous one mastered).
        // Insert new skills here, not at the enum's natural position.
        val ladder: List<Skill> = listOf(
            READ,
            // LISTEN,
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
)
