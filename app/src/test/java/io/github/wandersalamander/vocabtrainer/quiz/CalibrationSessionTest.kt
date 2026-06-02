package io.github.wandersalamander.vocabtrainer.quiz

import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CalibrationSession]: the adaptive per-tier probe and its single
 * output, the frontier. samplesPerTier=5 / passRatio=0.6 → passCount=3 in most
 * cases; thresholds are passed explicitly so the assertions don't drift if the
 * defaults change.
 */
class CalibrationSessionTest {

    private fun word(tier: Int, n: Int): Vocab =
        Vocab("f$tier.$n", "e$tier.$n", tier, "h$tier.$n", "hard", "easy")

    /** Always returns `count` distinct words for any tier. */
    private val fullSampler: (Int, Int) -> List<Vocab> =
        { tier, count -> List(count) { word(tier, it + 1) } }

    private fun session(
        tiers: List<Int>,
        sampler: (Int, Int) -> List<Vocab> = fullSampler,
        samplesPerTier: Int = 5,
        passRatio: Double = 0.6,
        maxQuestions: Int? = null,
    ) = CalibrationSession(tiers, sampler, samplesPerTier, passRatio, maxQuestions)

    /** Present the pending word and answer it; returns the word that was asked. */
    private fun CalibrationSession.ask(known: Boolean): Vocab {
        val w = nextWord()
        assertNotNull("expected a word to present", w)
        recordAnswer(known)
        return w!!
    }

    private fun CalibrationSession.askAll(known: Boolean, times: Int) {
        repeat(times) { ask(known) }
    }

    @Test fun clearsLowTierThenFailsNextStopsAtLastCleared() {
        val s = session(listOf(0, 1, 2))
        s.askAll(known = true, times = 3)   // tier 0 clears (3 of 5)
        s.askAll(known = false, times = 3)  // tier 1 fails (3 unknown of 5)
        assertTrue(s.isFinished)
        assertEquals(0, s.frontier)
        assertEquals(6, s.questionsAsked)
    }

    @Test fun allKnownClearsEveryTierFrontierIsHighest() {
        val s = session(listOf(0, 1, 2))
        s.askAll(known = true, times = 9)  // 3 per tier × 3 tiers
        assertTrue(s.isFinished)
        assertEquals(2, s.frontier)
        assertNull("finished session has no next word", s.nextWord())
    }

    @Test fun firstTierFailsGivesNoFrontier() {
        val s = session(listOf(0, 1, 2))
        s.askAll(known = false, times = 3)  // tier 0 fails immediately
        assertTrue(s.isFinished)
        assertEquals(NO_FRONTIER, s.frontier)
    }

    @Test fun adaptiveEarlyClearAdvancesBeforeFullSample() {
        // Three knowns clear a 5-word tier without asking all five: the very next
        // word must already come from tier 1.
        val s = session(listOf(0, 1))
        s.askAll(known = true, times = 3)
        assertFalse(s.isFinished)
        assertEquals(3, s.questionsAsked)
        assertEquals("should have advanced to tier 1", 1, s.nextWord()!!.importance)
    }

    @Test fun adaptiveEarlyFailStopsBeforeFullSample() {
        // Three unknowns sink a 5-word tier in three questions, not five.
        val s = session(listOf(0, 1))
        s.askAll(known = false, times = 3)
        assertTrue(s.isFinished)
        assertEquals(3, s.questionsAsked)
        assertEquals(NO_FRONTIER, s.frontier)
    }

    @Test fun maxQuestionsCapFinalizesExactlyAtAClear() {
        // Cap hits the instant tier 1 clears: credit tier 1 and stop.
        val s = session(listOf(0, 1, 2, 3), maxQuestions = 6)
        s.askAll(known = true, times = 6)  // tier 0 (3) + tier 1 (3) → cap
        assertTrue(s.isFinished)
        assertEquals(1, s.frontier)
        assertEquals(6, s.questionsAsked)
    }

    @Test fun maxQuestionsCapFinalizesMidTier() {
        // Cap hits one question into tier 2 (still undecided) → last cleared (1).
        val s = session(listOf(0, 1, 2, 3), maxQuestions = 7)
        s.askAll(known = true, times = 7)  // tiers 0,1 cleared, 1 into tier 2
        assertTrue(s.isFinished)
        assertEquals(1, s.frontier)
        assertEquals(7, s.questionsAsked)
    }

    @Test fun noCapWalksToTopTierPastTheOldLimit() {
        // With maxQuestions = null (the production default), a learner who keeps
        // clearing is probed all the way to the top tier — even when that takes
        // more questions than the former hard cap (25) would have allowed.
        val s = session((0..9).toList())  // 10 tiers, no cap
        s.askAll(known = true, times = 30)  // 3 per tier × 10 tiers
        assertTrue(s.isFinished)
        assertEquals(9, s.frontier)
        assertEquals(30, s.questionsAsked)
        assertNull("finished session has no next word", s.nextWord())
    }

    @Test fun nextWordIsIdempotentUntilAnswered() {
        val s = session(listOf(0))
        val first = s.nextWord()
        assertNotNull(first)
        assertEquals("repeated nextWord must return the same word", first, s.nextWord())
        s.recordAnswer(known = true)  // 1 known: tier not cleared yet (needs 3)
        val second = s.nextWord()
        assertNotNull(second)
        assertTrue("a new question must follow an answer", first != second)
    }

    @Test fun finishedSessionReturnsNullAndIgnoresAnswers() {
        val s = session(listOf(0))
        s.askAll(known = false, times = 3)  // tier 0 fails → finished
        assertTrue(s.isFinished)
        val asked = s.questionsAsked
        assertNull(s.nextWord())
        s.recordAnswer(known = true)  // no-op
        assertEquals(asked, s.questionsAsked)
        assertEquals(NO_FRONTIER, s.frontier)
    }

    @Test fun emptyTierListFinishesImmediatelyWithNoFrontier() {
        val s = session(emptyList())
        assertTrue(s.isFinished)
        assertEquals(NO_FRONTIER, s.frontier)
        assertNull(s.nextWord())
    }

    @Test fun singleWordTierClearsOnOneKnown() {
        // passCount adapts to the actual sample size: a one-word tier needs just
        // one known to clear.
        val oneWord: (Int, Int) -> List<Vocab> = { tier, _ -> listOf(word(tier, 1)) }
        val s = session(listOf(0), sampler = oneWord)
        s.ask(known = true)
        assertTrue(s.isFinished)
        assertEquals(0, s.frontier)
        assertEquals(1, s.questionsAsked)
    }

    @Test fun emptyMiddleTierIsSkipped() {
        // Tier 1 has no words; clearing tier 0 must jump straight to tier 2.
        val gappy: (Int, Int) -> List<Vocab> =
            { tier, count -> if (tier == 1) emptyList() else List(count) { word(tier, it + 1) } }
        val s = session(listOf(0, 1, 2), sampler = gappy)
        s.askAll(known = true, times = 3)  // clear tier 0
        assertEquals("empty tier 1 must be skipped", 2, s.nextWord()!!.importance)
        s.askAll(known = true, times = 3)  // clear tier 2
        assertTrue(s.isFinished)
        assertEquals(2, s.frontier)
    }
}
