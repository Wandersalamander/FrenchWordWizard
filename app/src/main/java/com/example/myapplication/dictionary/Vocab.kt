package com.example.myapplication.dictionary

import android.content.SharedPreferences
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.random.Random

data class Vocab(
    val french: String,
    val english: String,
    val importance: Int,
    val hash: String,
    val frenchLong: String,
    val frenchLong2: String,
    val sharedPreferences: SharedPreferences? = null,

    ) {
    val skillStats: Map<Skill, SkillStats> = Skill.ladder.associateWith { SkillStats() }

    var ignore: Boolean = false
    var flaggedHard: Boolean = false

    // Facades on the READ skill — existing call sites read/write through these.
    // Code that targets another skill should access [skillStats] directly.
    var viewTimeMilli: Long
        get() = skillStats.getValue(Skill.READ).viewTimeMilli
        set(value) { skillStats.getValue(Skill.READ).viewTimeMilli = value }
    var viewTimeMilli_prev: Long
        get() = skillStats.getValue(Skill.READ).viewTimeMilli_prev
        set(value) { skillStats.getValue(Skill.READ).viewTimeMilli_prev = value }
    var nTimesViewed: Int
        get() = skillStats.getValue(Skill.READ).nTimesViewed
        set(value) { skillStats.getValue(Skill.READ).nTimesViewed = value }
    var nTimesFailed: Float
        get() = skillStats.getValue(Skill.READ).nTimesFailed
        set(value) { skillStats.getValue(Skill.READ).nTimesFailed = value }
    var lastDisplayed: Long
        get() = skillStats.getValue(Skill.READ).lastDisplayed
        set(value) { skillStats.getValue(Skill.READ).lastDisplayed = value }


    init {
        loadPreferences()
    }

    fun getSomeFrenchLong(): String {
        val index = Random.nextInt(2)
        when (index) {
            0 -> return frenchLong2
            1 -> return frenchLong
        }
        return frenchLong // fallback option
    }

    fun loadPreferences() {
        if (sharedPreferences == null) return
        for (skill in Skill.ladder) {
            val stats = skillStats.getValue(skill)
            val p = "${hash}${skill.storagePrefix}"
            stats.viewTimeMilli =
                sharedPreferences.getString("${p}viewTimeMilli", "10000")!!.toLong()
            stats.viewTimeMilli_prev =
                sharedPreferences.getString("${p}viewTimeMilli_prev", "10000")!!.toLong()
            stats.nTimesViewed = sharedPreferences.getString("${p}nTimesViewed", "0")!!.toInt()
            stats.nTimesFailed = sharedPreferences.getString("${p}nTimesFailed", "0")!!.toFloat()
            stats.lastDisplayed = sharedPreferences.getString("${p}lastDisplayed", "0")!!.toLong()
        }
        ignore = sharedPreferences.getString("${hash}ignore", "false")!!.toBoolean()
        flaggedHard = sharedPreferences.getString("${hash}flaggedHard", "false")!!.toBoolean()
    }

    fun debugSortValue(): String {
        // high value for low nTimesViewed, high viewTimeMilli, high lastSeenHours
        val lastSeenHours = viewedMiutesAgo() / 60.0
        val a = viewedMiutesAgo()
        val b = ((ln(1.0f + lastSeenHours) + 1.0f))
        var c = (failureProbability())
        if (c < 0.1) {
            c = 0.0F  // ignore words that are learned well enough
        }
        val d = (meanTimeViewedMilli()) / 10e3
        val z = sortValue()
        return String.format("$french\t$a\t$b\t$c\t$d\t$z")
    }

    fun savePreferences() {
        if (sharedPreferences == null) return
        val editor: SharedPreferences.Editor = sharedPreferences.edit()
        for (skill in Skill.ladder) {
            val stats = skillStats.getValue(skill)
            val p = "${hash}${skill.storagePrefix}"
            editor.putString("${p}viewTimeMilli", stats.viewTimeMilli.toString())
            editor.putString("${p}viewTimeMilli_prev", stats.viewTimeMilli_prev.toString())
            editor.putString("${p}nTimesViewed", stats.nTimesViewed.toString())
            editor.putString("${p}nTimesFailed", stats.nTimesFailed.toString())
            editor.putString("${p}lastDisplayed", stats.lastDisplayed.toString())
        }
        editor.putString("${hash}ignore", ignore.toString())
        editor.putString("${hash}flaggedHard", flaggedHard.toString())
        editor.apply()
    }

    fun failureProbability(): Float {
        return if (nTimesViewed == 0) {
            1.0f
        } else {
            val base = 0.5f / nTimesViewed.toFloat()
            val raw = base + (1.0f - base) * nTimesFailed / nTimesViewed.toFloat()
            raw.coerceIn(0.0f, 1.0f)
        }
    }

    fun getInfoString(): String {
        return getTimeString() + "\n" + getStarsString()
    }

    fun getTimeString(): String {
        return String.format("⧖ %.1f s", (meanTimeViewedMilli() / 1e3))
    }

    fun getStarsString(): String {
        var sucessProbability: Float = 1.0f - failureProbability() // 0 to 1
        if (sucessProbability > 1.0f) {
            sucessProbability = 1.0f
        }
        if (sucessProbability < 0.0f) {
            sucessProbability = 0.0f
        }
        val star_number = 5
        val n_full_stars: Int = ((star_number * sucessProbability).toInt())

        val remainder: Int = star_number - n_full_stars
        return "★".repeat(n_full_stars) + "☆".repeat(remainder)
    }

    fun meanTimeViewedMilli() = viewTimeMilli.toDouble()

    fun viewedMiutesAgo() =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)


    fun sortValue(): Double {
        val fpb = failureProbability()
        if (fpb < 0.1) {
            return 0.0
        }
        val lastSeenHours = viewedMiutesAgo() / 60.0
        val hardMultiplier = if (flaggedHard) 3.0 else 1.0
        // Time as a gentle multiplier: 5s -> 1.0, 2s -> 0.7, 15s -> 1.4, clamped to [0.5, 2.0]
        val timeFactor = (0.4 + 0.12 * meanTimeViewedMilli() / 1000.0).coerceIn(0.5, 2.0)
        return (ln(1.0f + lastSeenHours * lastSeenHours) + 1.0f) * fpb * timeFactor * hardMultiplier
    }


    fun pronounceableEn(): String = EnglishAbbreviations.expand(english)

    fun pronounceableForeign(language: Language): String =
        language.expandAbbreviations(french)
}

