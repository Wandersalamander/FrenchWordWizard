package com.example.myapplication

import android.content.SharedPreferences
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.random.Random

val abbreviationDictionaryFr = mapOf(
    "Jr." to "Junior",
    "qc." to "quelque chose",
    "Dr." to "Docteur",
    "M." to "Monsieur",
    "etc." to "et cetera",
    "sec." to "seconde",
    "qn." to "quelqu'un",
    "St." to "Saint",
)

val abbreviationDictionaryEn = mapOf(
    "vs." to "versus",
    "sth." to "something",
    "stb." to "somebody",
    "Mr." to "Mister",
    "Jr." to "junior",
    "etc." to "etcetera",
    "Dr." to "Doctor",
    "qn." to "someone",
    "qc." to "something",
    "sb." to "somebody",
    "Ms." to "Miss",
    "Mrs." to "Misses",
    "St." to "Saint",
    "Ph.D." to "P H D ",
)

val regExPatternFr = abbreviationDictionaryFr.keys.joinToString("|").toRegex()
val regExPatternEn = abbreviationDictionaryEn.keys.joinToString("|").toRegex()


data class Vocab(
    val french: String,
    val english: String,
    val importance: Int,
    val hash: String,
    val frenchLong: String,
    val frenchLong2: String,
    val sharedPreferences: SharedPreferences? = null,

    ) {
    var viewTimeMilli: Long = 10000
    var viewTimeMilli_prev: Long = 10000
    var nTimesViewed: Int = 0
    var nTimesFailed: Int = 0
    var ignore: Boolean = false
    var lastDisplayed: Long = 0


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
        if (sharedPreferences == null) {
            return
        }
        viewTimeMilli =
            sharedPreferences.getString("${hash}viewTimeMilli", 10000.toString())!!.toLong()
        viewTimeMilli_prev =
            sharedPreferences.getString("${hash}viewTimeMilli_prev", 10000.toString())!!.toLong()
        nTimesViewed = sharedPreferences.getString("${hash}nTimesViewed", "0")!!.toInt()
        nTimesFailed =
            sharedPreferences.getString("${hash}nTimesFailed", nTimesViewed.toString())!!.toInt()
        ignore = sharedPreferences.getString("${hash}ignore", "false")!!.toBoolean()
        lastDisplayed = sharedPreferences.getString("${hash}lastDisplayed", "0")!!.toLong()
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
        if (sharedPreferences == null) {
            return
        }
        Thread {
            val editor: SharedPreferences.Editor = sharedPreferences.edit()

            // Put the value to be saved
            editor.putString("${hash}viewTimeMilli", viewTimeMilli.toString())
            editor.putString("${hash}viewTimeMilli_prev", viewTimeMilli_prev.toString())
            editor.putString("${hash}nTimesViewed", nTimesViewed.toString())
            editor.putString("${hash}nTimesFailed", nTimesFailed.toString())
            editor.putString("${hash}ignore", ignore.toString())
            editor.putString("${hash}lastDisplayed", lastDisplayed.toString())
            // Commit the changes
            editor.apply()
        }.start()
    }

    fun failureProbability(): Float {
        return if (nTimesViewed == 0) {
            1.0f
        } else {
            val base = 0.5f / nTimesViewed.toFloat()
            base + (1.0f - base) * nTimesFailed.toFloat() / nTimesViewed.toFloat()
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
            sucessProbability = 1.0f
        }
        val star_number = 5
        val n_full_stars: Int = ((star_number * sucessProbability).toInt())

        val remainder: Int = star_number - n_full_stars
        return "★".repeat(n_full_stars) + "☆".repeat(remainder)
    }

    fun meanTimeViewedMilli() = ((viewTimeMilli.toDouble() + viewTimeMilli_prev.toDouble()) * 0.5)

    fun viewedMiutesAgo() =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)


    fun sortValue(): Double {
        // high value for low nTimesViewed, high viewTimeMilli, high lastSeenHours
        val lastSeenHours = viewedMiutesAgo() / 60.0
        val fpb = failureProbability()
        if (fpb < 0.1) {
            return 0.0
        }
        return (ln(1.0f + lastSeenHours) + 1.0f) * (fpb + (meanTimeViewedMilli()) / 10e3)
    }


    fun replaceMultiplePhrasesRegexFr(input: String): String {
        return regExPatternFr.replace(input) {
            abbreviationDictionaryFr[it.value] ?: it.value
        }.replace("(", "").replace(")", "")
    }

    fun replaceMultiplePhrasesRegexEn(input: String): String {
        return regExPatternEn.replace(input) {
            abbreviationDictionaryEn[it.value] ?: it.value
        }.replace("(", "").replace(")", "")
    }

    fun pronounceableFr(): String {
        return replaceMultiplePhrasesRegexFr(french)
    }

    fun pronounceableEn(): String {
        return replaceMultiplePhrasesRegexEn(english)
    }
}

