package com.example.myapplication

import android.content.SharedPreferences
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

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
    val sharedPreferences: SharedPreferences? = null
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

    fun loadPreferences() {
        if (sharedPreferences == null) {
            return
        }
        viewTimeMilli = sharedPreferences.getString("${hash}viewTimeMilli", 10000.toString())!!.toLong()
        viewTimeMilli_prev =
            sharedPreferences.getString("${hash}viewTimeMilli_prev", 10000.toString())!!.toLong()
        nTimesViewed = sharedPreferences.getString("${hash}nTimesViewed", "0")!!.toInt()
        nTimesFailed =
            sharedPreferences.getString("${hash}nTimesFailed", nTimesViewed.toString())!!.toInt()
        ignore = sharedPreferences.getString("${hash}ignore", "false")!!.toBoolean()
        lastDisplayed = sharedPreferences.getString("${hash}lastDisplayed", "0")!!.toLong()
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
            base + (1.0f-base)*nTimesFailed.toFloat() / nTimesViewed.toFloat()
        }
    }

    fun meanTimeViewedMilli() = ((viewTimeMilli.toDouble() + viewTimeMilli_prev.toDouble()) * 0.5)

    fun viewedMiutesAgo() =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)


    fun sortValue(): Double {
        // high value for low nTimesViewed, high viewTimeMilli, high lastSeenHours
        val lastSeenHours = viewedMiutesAgo() / 60.0
        return sqrt(1.0f + lastSeenHours) * failureProbability() * meanTimeViewedMilli()
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

