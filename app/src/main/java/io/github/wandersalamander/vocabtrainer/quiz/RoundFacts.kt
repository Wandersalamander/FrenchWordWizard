package io.github.wandersalamander.vocabtrainer.quiz

import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Mutable accumulator for one round's observable facts. Owned by
 * [QuizController]: reset in `resetRoundState`, mutated from each user-action
 * handler, then frozen into a [ReviewLogEntry] in `saveCurrentVocab`.
 *
 * Holds only direct observations — no scoring, no algorithm output. The
 * round's `failAmount` is intentionally not tracked here; it's derived by
 * the existing scoring rules from these facts and stored on `SkillStats`,
 * not in the log.
 */
class RoundFacts {
    /** Wall-clock time the round started (presentation moment). */
    var roundStartWallClockMs: Long = 0L

    /** Latency to the first user action, or null if no action occurred yet. */
    var timeToFirstActionMs: Long? = null
        private set

    var tipUsed: TipUsed = TipUsed.NONE
        private set
    var progressiveReveals: Int = 0
        private set
    var wordMaskTaps: Int = 0
        private set
    var wordReplays: Int = 0
        private set
    var sentenceMaskTaps: Int = 0
        private set
    /** SPEAK only: takes recorded this round (1 = free first take, ≥2 = re-records). */
    var speakRecordings: Int = 0
        private set
    var spotCheckResult: SpotCheckResult = SpotCheckResult.NONE
        private set

    /** Reset all counters for a new round starting at [nowMs]. */
    fun reset(nowMs: Long) {
        roundStartWallClockMs = nowMs
        timeToFirstActionMs = null
        tipUsed = TipUsed.NONE
        progressiveReveals = 0
        wordMaskTaps = 0
        wordReplays = 0
        sentenceMaskTaps = 0
        speakRecordings = 0
        spotCheckResult = SpotCheckResult.NONE
    }

    /**
     * Stamp the first user action's latency. Subsequent calls are no-ops —
     * only the first action counts.
     */
    fun recordFirstActionIfNeeded(nowMs: Long) {
        if (timeToFirstActionMs == null) {
            timeToFirstActionMs = (nowMs - roundStartWallClockMs).coerceAtLeast(0L)
        }
    }

    fun recordSentenceTip() {
        tipUsed = TipUsed.SENTENCE
    }

    /**
     * SPEAK: the user tapped the foreign word to hear the model pronunciation
     * before attempting — an opt-in hint, recorded as a tip flavor so it scores
     * ([io.github.wandersalamander.vocabtrainer.data.scoring.RoundScoring.SPEAK_REVEAL_PENALTY])
     * and blocks fast-track. First call this round sets it; later taps are no-ops
     * (the hint is already spent).
     */
    fun recordSpeakReveal() {
        if (tipUsed == TipUsed.NONE) tipUsed = TipUsed.SPEAK_REVEAL
    }

    fun recordProgressiveReveal() {
        if (tipUsed == TipUsed.NONE) tipUsed = TipUsed.PROGRESSIVE_REVEAL
        progressiveReveals += 1
    }

    fun recordWordMaskTap() { wordMaskTaps += 1 }
    fun recordWordReplay() { wordReplays += 1 }
    fun recordSentenceMaskTap() { sentenceMaskTaps += 1 }
    /** A SPEAK take finished recording (call once per completed take). */
    fun recordSpeakRecording() { speakRecordings += 1 }

    fun recordSpotCheckResult(passed: Boolean) {
        spotCheckResult = if (passed) SpotCheckResult.PASSED else SpotCheckResult.FAILED
    }

    /**
     * Freeze the accumulator into an immutable log entry. [foregroundViewTimeMs]
     * is the round's foreground-only time (already excludes pause intervals).
     * [pausedMs] is derived against the wall-clock window so a single source
     * of truth (`roundStartWallClockMs`) backs both the log fields.
     */
    fun build(
        vocabHash: String,
        skill: Skill,
        commitButton: CommitButton,
        sentenceSource: SentenceSource,
        listeningEnabled: Boolean,
        compromised: Boolean,
        foregroundViewTimeMs: Long,
        nowMs: Long,
    ): ReviewLogEntry {
        val totalWallClock = (nowMs - roundStartWallClockMs).coerceAtLeast(0L)
        val pausedMs = (totalWallClock - foregroundViewTimeMs).coerceAtLeast(0L)
        return ReviewLogEntry(
            vocabHash = vocabHash,
            skill = skill,
            timestampMs = nowMs,
            viewTimeMs = foregroundViewTimeMs,
            timeToFirstActionMs = timeToFirstActionMs,
            pausedMs = pausedMs,
            commitButton = commitButton,
            tipUsed = tipUsed,
            progressiveReveals = progressiveReveals,
            wordMaskTaps = wordMaskTaps,
            wordReplays = wordReplays,
            sentenceMaskTaps = sentenceMaskTaps,
            speakRecordings = speakRecordings,
            spotCheckResult = spotCheckResult,
            sentenceSource = sentenceSource,
            listeningEnabled = listeningEnabled,
            compromised = compromised,
        )
    }
}
