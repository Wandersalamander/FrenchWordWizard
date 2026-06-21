package io.github.wandersalamander.vocabtrainer.data.scoring

import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.ReviewLogEntry
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure scoring tests. Each scoring source is exercised independently so a
 * future tuning of any single penalty constant is caught by a focused test
 * rather than buried in an aggregate.
 */
class RoundScoringTest {

    private fun entry(
        commit: CommitButton = CommitButton.NEXT,
        tip: TipUsed = TipUsed.NONE,
        reveals: Int = 0,
        wordMask: Int = 0,
        sentenceMask: Int = 0,
        recordings: Int = 0,
        spot: SpotCheckResult = SpotCheckResult.NONE,
        compromised: Boolean = false,
    ) = ReviewLogEntry(
        vocabHash = "h", skill = Skill.READ,
        timestampMs = 1_000L, viewTimeMs = 1_500L,
        timeToFirstActionMs = 1_500L, pausedMs = 0L,
        commitButton = commit, tipUsed = tip,
        progressiveReveals = reveals,
        wordMaskTaps = wordMask, sentenceMaskTaps = sentenceMask,
        wordReplays = 0,
        speakRecordings = recordings,
        spotCheckResult = spot,
        sentenceSource = SentenceSource.EASY,
        listeningEnabled = true,
        compromised = compromised,
    )

    @Test fun cleanRoundHasNoPenalty() {
        assertEquals(0f, RoundScoring.failAmount(entry(), foreignLetterCount = 5), 1e-6f)
    }

    @Test fun failCommitButtonAddsFullPenalty() {
        val amount = RoundScoring.failAmount(entry(commit = CommitButton.FAIL), foreignLetterCount = 5)
        assertEquals(RoundScoring.FAIL_PENALTY, amount, 1e-6f)
    }

    @Test fun spotCheckFailUsesFailCommitButton() {
        // The exitSpotCheck path resolves a Wrong as commit_button = FAIL, so
        // there's no separate spot-check-failed penalty branch — it rides on
        // FAIL. Pinning this so the scoring stays consistent with the log
        // convention.
        val amount = RoundScoring.failAmount(
            entry(commit = CommitButton.FAIL, spot = SpotCheckResult.FAILED),
            foreignLetterCount = 5,
        )
        assertEquals(RoundScoring.FAIL_PENALTY, amount, 1e-6f)
    }

    @Test fun sentenceTipAddsItsPenalty() {
        val amount = RoundScoring.failAmount(entry(tip = TipUsed.SENTENCE), foreignLetterCount = 5)
        assertEquals(RoundScoring.SENTENCE_TIP_PENALTY, amount, 1e-6f)
    }

    @Test fun speakRevealAddsItsPenalty() {
        // Opt-in "hear it before attempting" is a tip flavor with its own penalty.
        val amount = RoundScoring.failAmount(entry(tip = TipUsed.SPEAK_REVEAL), foreignLetterCount = 5)
        assertEquals(RoundScoring.SPEAK_REVEAL_PENALTY, amount, 1e-6f)
    }

    @Test fun firstSpeakTakeIsFree() {
        // A single take is the task itself — no penalty.
        assertEquals(0f, RoundScoring.failAmount(entry(recordings = 1), foreignLetterCount = 5), 1e-6f)
    }

    @Test fun reRecordsAddPenaltyPerExtraTake() {
        // 3 takes = 2 re-records → 2 × the per-re-record penalty.
        assertEquals(
            2 * RoundScoring.SPEAK_RERECORD_PENALTY,
            RoundScoring.failAmount(entry(recordings = 3), foreignLetterCount = 5),
            1e-6f,
        )
    }

    @Test fun reRecordPenaltyIsCapped() {
        // Beyond the cap, extra takes stop accruing penalty.
        val cap = RoundScoring.SPEAK_MAX_PENALIZED_RERECORDS * RoundScoring.SPEAK_RERECORD_PENALTY
        assertEquals(
            cap,
            RoundScoring.failAmount(entry(recordings = 99), foreignLetterCount = 5),
            1e-6f,
        )
    }

    @Test fun maskTapsScaleLinearlyWithCount() {
        val amount = RoundScoring.failAmount(
            entry(wordMask = 2, sentenceMask = 1),
            foreignLetterCount = 5,
        )
        assertEquals(3 * RoundScoring.MASK_TAP_PENALTY, amount, 1e-6f)
    }

    @Test fun compromisedEntryHasZeroPenalty() {
        // Defensive — the replay skips compromised rows entirely, but if a
        // caller asks scoring anyway it should return 0 rather than pretend.
        val amount = RoundScoring.failAmount(
            entry(commit = CommitButton.FAIL, tip = TipUsed.SENTENCE, compromised = true),
            foreignLetterCount = 5,
        )
        assertEquals(0f, amount, 1e-6f)
    }

    @Test fun progressiveRevealTriangularFormulaIsCorrect() {
        // 1st reveal of N=5: 2·5/(5·6) = 0.333…
        assertEquals(2f * 5 / 30f, RoundScoring.progressiveRevealPenalty(1, 5), 1e-6f)
        // 5th (last) reveal of N=5: 2·1/(5·6) = 0.066…
        assertEquals(2f * 1 / 30f, RoundScoring.progressiveRevealPenalty(5, 5), 1e-6f)
    }

    @Test fun progressiveRevealsSumToOneOverFullUnmask() {
        // The "full unmask = one Fail" invariant. Pinned because the formula
        // is non-obvious and tweaking N or the constants must keep the sum.
        for (letterCount in intArrayOf(1, 2, 4, 5, 8, 12)) {
            val total = (1..letterCount).sumOf {
                RoundScoring.progressiveRevealPenalty(it, letterCount).toDouble()
            }.toFloat()
            assertEquals(
                "full progressive unmask of $letterCount letters should sum to 1.0",
                1.0f, total, 1e-5f,
            )
        }
    }

    @Test fun progressiveRevealPenaltyDefendsAgainstBadInput() {
        assertEquals(0f, RoundScoring.progressiveRevealPenalty(1, 0), 1e-6f)
        assertEquals(0f, RoundScoring.progressiveRevealPenalty(0, 5), 1e-6f)
        assertEquals(0f, RoundScoring.progressiveRevealPenalty(6, 5), 1e-6f) // out of range
    }

    @Test fun progressiveRevealEntrySumsFromOneToCount() {
        val amount = RoundScoring.failAmount(
            entry(tip = TipUsed.PROGRESSIVE_REVEAL, reveals = 2),
            foreignLetterCount = 5,
        )
        val expected =
            RoundScoring.progressiveRevealPenalty(1, 5) +
                RoundScoring.progressiveRevealPenalty(2, 5)
        assertEquals(expected, amount, 1e-6f)
    }

    @Test fun multipleSourcesCombine() {
        // Sentence tip + 2 mask taps + a FAIL click — all penalty sources at
        // once. The aggregate must equal the simple sum.
        val amount = RoundScoring.failAmount(
            entry(
                commit = CommitButton.FAIL,
                tip = TipUsed.SENTENCE,
                wordMask = 2,
            ),
            foreignLetterCount = 5,
        )
        val expected = RoundScoring.FAIL_PENALTY +
            RoundScoring.SENTENCE_TIP_PENALTY +
            2 * RoundScoring.MASK_TAP_PENALTY
        assertEquals(expected, amount, 1e-6f)
    }
}
