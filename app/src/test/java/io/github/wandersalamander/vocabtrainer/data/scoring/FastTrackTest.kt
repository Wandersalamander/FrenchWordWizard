package io.github.wandersalamander.vocabtrainer.data.scoring

import io.github.wandersalamander.vocabtrainer.data.TipUsed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [FastTrack.isCandidate]. The integration with
 * QuizController's commit path isn't exercised here (would need Android), but
 * the gate logic — what counts as a trivially-known first-exposure round — is
 * pinned end-to-end.
 */
class FastTrackTest {

    // Baseline: a first-exposure, clean, fast round with no help, with the
    // predecessor-fast-tracked gate satisfied (vacuous default — matches the
    // first ladder skill). Adjust one field per test to verify each rejection
    // condition independently.
    private fun candidate(
        priorViews: Int = 0,
        tipUsed: TipUsed = TipUsed.NONE,
        wordMaskTaps: Int = 0,
        sentenceMaskTaps: Int = 0,
        progressiveReveals: Int = 0,
        elapsedMs: Long = 1_500L,
        predecessorFastTracked: Boolean = true,
    ): Boolean = FastTrack.isCandidate(
        priorViews = priorViews,
        tipUsed = tipUsed,
        wordMaskTaps = wordMaskTaps,
        sentenceMaskTaps = sentenceMaskTaps,
        progressiveReveals = progressiveReveals,
        elapsedMs = elapsedMs,
        predecessorFastTracked = predecessorFastTracked,
    )

    @Test fun freshFastCleanRoundIsCandidate() {
        assertTrue(candidate())
    }

    @Test fun priorlySeenPairIsNotCandidate() {
        // MAX_PRIOR_VIEWS = 0; one prior view disqualifies.
        assertFalse(candidate(priorViews = 1))
        assertFalse(candidate(priorViews = 5))
    }

    @Test fun sentenceTipDisqualifies() {
        assertFalse(candidate(tipUsed = TipUsed.SENTENCE))
    }

    @Test fun progressiveRevealTipDisqualifies() {
        assertFalse(candidate(tipUsed = TipUsed.PROGRESSIVE_REVEAL))
    }

    @Test fun wordMaskTapDisqualifies() {
        // Tapping the masked foreign word to replay TTS is a "I need help" signal
        // even if the user eventually answered correctly — not a trivial pass.
        assertFalse(candidate(wordMaskTaps = 1))
    }

    @Test fun sentenceMaskTapDisqualifies() {
        assertFalse(candidate(sentenceMaskTaps = 1))
    }

    @Test fun progressiveRevealsCountDisqualifies() {
        // Belt-and-braces: tipUsed should already cover this, but a non-zero
        // reveals count without the tip flag (shouldn't happen but guard anyway)
        // still disqualifies.
        assertFalse(candidate(progressiveReveals = 1))
    }

    @Test fun slowRoundDisqualifies() {
        // MAX_VIEW_MS = 3000; anything longer is "not trivially fast".
        assertFalse(candidate(elapsedMs = FastTrack.MAX_VIEW_MS + 1))
        assertFalse(candidate(elapsedMs = 10_000L))
    }

    @Test fun atMaxViewMsBoundaryStillQualifies() {
        // The boundary itself is inclusive — exactly MAX_VIEW_MS still counts.
        assertTrue(candidate(elapsedMs = FastTrack.MAX_VIEW_MS))
    }

    @Test fun negativeElapsedRejected() {
        // Clock skew or measurement bug — refuse to fast-track on impossible time.
        assertFalse(candidate(elapsedMs = -1L))
    }

    @Test fun anyCombinationOfDisqualifiersRejects() {
        assertFalse(candidate(priorViews = 1, tipUsed = TipUsed.SENTENCE))
        assertFalse(candidate(elapsedMs = 5_000L, wordMaskTaps = 1))
    }

    @Test fun predecessorNotFastTrackedDisqualifies() {
        // Build-up policy: an otherwise-trivial round at tier N is rejected
        // when the predecessor tier on the same vocab hasn't fast-tracked.
        // The "I clearly know this" signal must keep compounding to keep
        // buying speed; one tier earned the long way (or with a flag later
        // revoked by failure) breaks the chain for every later tier.
        assertFalse(candidate(predecessorFastTracked = false))
    }

    @Test fun predecessorFastTrackedAlonenotEnough() {
        // Symmetric guard: passing the build-up gate doesn't bypass the other
        // signals. The combined gate is AND, not OR.
        assertFalse(candidate(predecessorFastTracked = true, tipUsed = TipUsed.SENTENCE))
        assertFalse(candidate(predecessorFastTracked = true, elapsedMs = 10_000L))
    }
}
