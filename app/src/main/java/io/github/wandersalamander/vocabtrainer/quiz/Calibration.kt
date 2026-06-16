package io.github.wandersalamander.vocabtrainer.quiz

import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import kotlin.math.ceil

/**
 * Frontier value meaning "no tier was cleared" — a true beginner. Stored as-is;
 * [io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs.calibrationFrontier]
 * maps any negative value back to null (no introduction bias).
 */
const val NO_FRONTIER: Int = -1

private const val DEFAULT_SAMPLES_PER_TIER = 10
private const val DEFAULT_PASS_RATIO = 0.7

/**
 * Pure decision logic for the once-per-language first-launch calibration. Probes
 * importance tiers in ascending order, asking a small *adaptive* sample per tier
 * with binary answers (the existing Next = "I know it", Fail = "I don't"):
 *
 *  - A tier **clears** as soon as enough of its sample is known (≥ [passRatio]
 *    of the sampled words); the probe then advances to the next tier.
 *  - A tier **fails** as soon as too many are unknown for it to ever clear; that
 *    ends calibration.
 *  - Adaptive: a tier is decided the moment either threshold is reached, so a
 *    confidently-known or clearly-unknown tier costs fewer than [samplesPerTier]
 *    questions. The probe stops on its own at the first failed tier; a learner
 *    who keeps clearing walks all the way to the top tier (frontier = top).
 *  - [maxQuestions] is an *optional* hard ceiling on total questions; null (the
 *    default) means no ceiling — only failing a tier or exhausting the tier list
 *    ends the session. The list is finite and each tier resolves within
 *    [samplesPerTier] answers, so termination is guaranteed without it.
 *
 * The single output is [frontier]: the highest tier the user cleared, or
 * [NO_FRONTIER] if even the lowest probed tier failed. That integer is the only
 * thing calibration produces — it does no scoring, streak, TTS, LLM, or any
 * per-word persistence. The UI drives the session: show [nextWord], collect one
 * Next/Fail tap, call [recordAnswer]; once [isFinished], persist [frontier] and
 * hand off to normal play.
 *
 * [tiers] are the distinct importance tiers to probe, ascending. [sampleTier]
 * returns up to `count` candidate words for a tier (the caller owns word policy
 * — e.g. essentials in CSV order, higher tiers random); an empty return skips
 * that tier. Kept side-effect free and free of Android types so it unit-tests
 * headlessly.
 */
class CalibrationSession(
    private val tiers: List<Int>,
    private val sampleTier: (tier: Int, count: Int) -> List<Vocab>,
    private val samplesPerTier: Int = DEFAULT_SAMPLES_PER_TIER,
    private val passRatio: Double = DEFAULT_PASS_RATIO,
    private val maxQuestions: Int? = null,
) {
    init {
        require(samplesPerTier >= 1) { "samplesPerTier must be >= 1, was $samplesPerTier" }
        require(passRatio > 0.0 && passRatio <= 1.0) { "passRatio must be in (0, 1], was $passRatio" }
        require(maxQuestions == null || maxQuestions >= 1) { "maxQuestions must be >= 1 or null, was $maxQuestions" }
    }

    private var tierPos = 0
    private var currentSample: List<Vocab> = emptyList()
    private var sampleIdx = 0
    // Words of the current sample that must be known for the tier to clear.
    // Derived from the *actual* sample size so a tier with fewer than
    // samplesPerTier words can still clear (passCount never exceeds the sample).
    private var passCountInTier = 1
    private var knownInTier = 0
    private var askedInTier = 0

    // Highest tier cleared so far; also the running/final frontier.
    private var lastClearedTier = NO_FRONTIER
    // The word handed out by nextWord() awaiting an answer, if any.
    private var pendingWord: Vocab? = null

    var isFinished: Boolean = false
        private set

    var questionsAsked: Int = 0
        private set

    init {
        enterTierFrom(0)
    }

    /** Highest tier cleared, or [NO_FRONTIER]. Final once [isFinished]. */
    val frontier: Int get() = lastClearedTier

    /**
     * The next word to present, or null when calibration is finished. Idempotent
     * until the next [recordAnswer] — repeated calls return the same word, so the
     * UI can re-render without consuming a sample slot.
     */
    fun nextWord(): Vocab? {
        if (isFinished) return null
        pendingWord?.let { return it }
        val word = currentSample.getOrNull(sampleIdx) ?: return null
        pendingWord = word
        return word
    }

    /**
     * Record the user's binary answer for the word last returned by [nextWord]:
     * true = "I know it" (Next), false = "I don't" (Fail). No-op when finished or
     * when no word is pending (defensive against double taps / stray calls).
     */
    fun recordAnswer(known: Boolean) {
        if (isFinished || pendingWord == null) return
        pendingWord = null
        sampleIdx++
        askedInTier++
        questionsAsked++
        if (known) knownInTier++

        val unknownInTier = askedInTier - knownInTier
        when {
            // Cleared: enough of this tier is known. Credit it and move up.
            knownInTier >= passCountInTier -> clearTierAndAdvance()
            // Failed: too many unknown to ever reach passCount — stop here.
            unknownInTier > currentSample.size - passCountInTier -> finish()
            // Optional hard ceiling on total questions, mid-tier and undecided.
            maxQuestions != null && questionsAsked >= maxQuestions -> finish()
        }
    }

    private fun clearTierAndAdvance() {
        lastClearedTier = tiers[tierPos]
        if (maxQuestions != null && questionsAsked >= maxQuestions) {
            finish()
            return
        }
        enterTierFrom(tierPos + 1)
    }

    private fun finish() {
        isFinished = true
        pendingWord = null
    }

    /**
     * Abandon the probe immediately as a true beginner: the frontier is forced
     * to [NO_FRONTIER] regardless of any tiers cleared so far, and the session
     * finishes. Backs the "I'm a beginner — skip" affordance, where the user has
     * explicitly declared they're starting from nothing.
     */
    fun skip() {
        lastClearedTier = NO_FRONTIER
        finish()
    }

    /**
     * Load the first tier at or after [start] that yields a non-empty sample and
     * reset the per-tier counters. Finishes the session when no such tier
     * remains (every probed tier cleared → frontier is the last cleared tier).
     */
    private fun enterTierFrom(start: Int) {
        var pos = start
        while (pos < tiers.size) {
            val sample = sampleTier(tiers[pos], samplesPerTier)
            if (sample.isNotEmpty()) {
                tierPos = pos
                currentSample = sample
                sampleIdx = 0
                knownInTier = 0
                askedInTier = 0
                passCountInTier = ceil(passRatio * sample.size).toInt().coerceIn(1, sample.size)
                return
            }
            pos++
        }
        finish()
    }
}
