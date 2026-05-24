package io.github.wandersalamander.vocabtrainer.data

import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * One committed round, captured as raw facts only. Everything here is either a
 * direct user action (which button, how many taps), a directly observed time
 * (presentation → first action, foreground duration), or a piece of round
 * context that affected what the user saw (which skill, listening mode,
 * sentence source preference).
 *
 * Deliberately excluded: anything an algorithm computes from these facts —
 * stability, difficulty, next-due, failureProbability, fail_amount. Those are
 * derived and live on `vocab_stats`; baking them into log rows would freeze
 * today's scoring rules into history. The log outlives every algorithm.
 *
 * Mirrors [io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity]
 * one-for-one; this is the domain seam handed to [VocabPersistence] so the
 * Room entity stays an implementation detail.
 */
data class ReviewLogEntry(
    val vocabHash: String,
    val skill: Skill,
    val timestampMs: Long,
    /** Total foreground time presentation → commit, excluding paused intervals. */
    val viewTimeMs: Long,
    /** Latency from presentation to the user's first interaction (any tap). Null if none. */
    val timeToFirstActionMs: Long?,
    /** Wall-clock time the round spent backgrounded (i.e. excluded from viewTimeMs). */
    val pausedMs: Long,
    val commitButton: CommitButton,
    val tipUsed: TipUsed,
    /** Letters revealed via progressive-reveal tip; 0 when [tipUsed] is not PROGRESSIVE_REVEAL. */
    val progressiveReveals: Int,
    val wordMaskTaps: Int,
    /** Visible-word taps (no penalty — just a TTS replay). */
    val wordReplays: Int,
    val sentenceMaskTaps: Int,
    val spotCheckResult: SpotCheckResult,
    /** Effective sentence-source setting at the time of the round. */
    val sentenceSource: SentenceSource,
    val listeningEnabled: Boolean,
    /**
     * True when the round was rolled back from the active stats (e.g. listening
     * toggled off mid-LISTEN). Still logged so we keep "user saw this word at
     * this time," but downstream training should exclude these from outcome
     * inference.
     */
    val compromised: Boolean,
)

/**
 * What ended the round.
 * - [NEXT] / [FAIL]: the matching quiz button (or its spot-check equivalent —
 *   pressing Next during a spot check still resolves as NEXT, pressing Wrong
 *   during a spot check resolves as FAIL).
 * - [LEARNED]: the user pressed the ✓ Learned button, marking the word as
 *   permanently ignored.
 * - [OTHER]: the round was ended by something other than a button press —
 *   currently only the mid-round listening-mode toggle that compromises a
 *   LISTEN round.
 * - [NEW]: historical only. Used to mark "+new vocab" presses, but those
 *   now dismiss the round without saving (no log entry written). Kept in the
 *   enum so Room can still deserialize old rows.
 */
enum class CommitButton { NEXT, FAIL, NEW, LEARNED, OTHER }

/** Which tip flavor (if any) the user invoked. */
enum class TipUsed { NONE, SENTENCE, PROGRESSIVE_REVEAL }

/** Spot-check outcome for this round, or NONE if no spot check was triggered. */
enum class SpotCheckResult { NONE, PASSED, FAILED }
