package com.example.myapplication.dictionary

import android.content.SharedPreferences
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

    /** Short CSV example — used when the user opts for easy sentences. */
    fun easySentence(): String = frenchLong2

    /** Long CSV example — used when the user opts for hard sentences. */
    fun hardSentence(): String = frenchLong

    /**
     * Resolve which built-in CSV sentence to use based on [source]. For
     * [SentenceSource.LLM] this returns the easy CSV sentence as a fallback —
     * callers wanting LLM output should call the LLM directly and fall through
     * to this only when generation is unavailable.
     */
    fun csvSentenceFor(source: SentenceSource): String = when (source) {
        SentenceSource.EASY, SentenceSource.LLM -> frenchLong2
        SentenceSource.HARD -> frenchLong
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
        val s = stats(Skill.READ)
        val lastSeenHours = s.viewedMiutesAgo() / 60.0
        val a = s.viewedMiutesAgo()
        val b = ((ln(1.0f + lastSeenHours) + 1.0f))
        var c = s.failureProbability()
        if (c < 0.1) {
            c = 0.0F  // ignore words that are learned well enough
        }
        val d = s.meanTimeViewedMilli() / 10e3
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

    // Facades targeting the READ skill — let pre-skill-split call sites keep working.
    // Skill-aware code should call methods on stats(skill) directly.
    fun failureProbability(): Float = stats(Skill.READ).failureProbability()
    fun getInfoString(): String = stats(Skill.READ).getInfoString()
    fun meanTimeViewedMilli(): Double = stats(Skill.READ).meanTimeViewedMilli()

    fun sortValue(skill: Skill = Skill.READ): Double {
        val s = stats(skill)
        val fpb = s.failureProbability()
        if (fpb < SKILL_FINISHED_THRESHOLD) {
            return 0.0
        }
        val lastSeenHours = if (s.nTimesViewed == 0) {
            // Newly-active skill pair — lastDisplayed is the epoch (1970), which
            // would make viewedMiutesAgo absurd. Treat as "due now-ish" instead.
            24.0
        } else {
            s.viewedMiutesAgo() / 60.0
        }
        val hardMultiplier = if (flaggedHard) 3.0 else 1.0
        // Time as a gentle multiplier: 5s -> 1.0, 2s -> 0.7, 15s -> 1.4, clamped to [0.5, 2.0]
        val timeFactor = (0.4 + 0.12 * s.meanTimeViewedMilli() / 1000.0).coerceIn(0.5, 2.0)
        return (ln(1.0f + lastSeenHours * lastSeenHours) + 1.0f) * fpb * timeFactor * hardMultiplier
    }


    fun pronounceableEn(): String = EnglishAbbreviations.expand(english)

    fun pronounceableForeign(language: Language): String =
        language.expandAbbreviations(french)

    companion object {
        // Below this failure probability, a (word, skill) pair is retired — it
        // never appears in the quiz pool again.
        const val SKILL_FINISHED_THRESHOLD = 0.1f
    }
}

