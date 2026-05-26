package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scheduler behavior: precedence, cadence, fallback, null-skip, and the two
 * properties that prevent overlap/double-ticking — a due-but-empty picker stays
 * eligible, and anti-repeat re-rolls within a round never advance cadence.
 *
 * Uses fake pickers returning canned pairs so the tests exercise the scheduler
 * logic, not the real selection math (covered by SchedulingMathTest).
 */
class PickerSchedulerTest {

    private fun vocab(foreign: String) = Vocab(
        foreign = foreign, english = foreign, importance = 1, hash = foreign,
        exampleHard = "h", exampleEasy = "e", persistence = null,
    )

    private fun tinyDictionary(): MyDictionary =
        MyDictionary("a\ta\t1\tha\thard\teasy\n".byteInputStream(), persistence = null)

    private fun ctx(dict: MyDictionary, newCandidates: Boolean = false) =
        PickContext(dict, newCandidates, skillFilter = { true })

    private class FakePicker(
        override val id: PickerId,
        override val cadence: Cadence,
        private val result: () -> Pair<Vocab, Skill>?,
    ) : PickerStrategy {
        override fun pick(ctx: PickContext): Pair<Vocab, Skill>? = result()
    }

    private fun fallbackPicker(foreign: String = "fb") =
        FakePicker(PickerId.ACTIVE_WEIGHTED, Cadence.Always) { vocab(foreign) to Skill.READ }

    @Test fun fallbackServesWhenNoStrategyEligible() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(5)) { vocab("refresh") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        // Round 1: refresh not due (1 < 5) → fallback.
        assertEquals("fb", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun precedenceHigherStrategyWins() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(1)) { vocab("high") to Skill.READ },
                FakePicker(PickerId.UNIFORM_FLOOR, Cadence.EveryNth(1)) { vocab("low") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        // Both due the same round → registration-order precedence picks "high".
        assertEquals("high", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun nullStrategyFallsThroughToNextThenFallback() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(1)) { null }, // due but empty
                FakePicker(PickerId.UNIFORM_FLOOR, Cadence.EveryNth(1)) { vocab("uniform") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        assertEquals("uniform", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun everyNthFiresOnTheIntervalRounds() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(3)) { vocab("refresh") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        val seen = (1..6).map { scheduler.beginRound(ctx(dict)).select().vocab.foreign }
        assertEquals(listOf("fb", "fb", "refresh", "fb", "fb", "refresh"), seen)
    }

    @Test fun dueButEmptyStaysEligibleUntilItCanServe() {
        val dict = tinyDictionary()
        var hasMastered = false
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(2)) {
                    if (hasMastered) vocab("refresh") to Skill.READ else null
                },
            ),
            fallback = fallbackPicker(),
        )
        scheduler.beginRound(ctx(dict)).select() // r1: tick→1, not due
        // r2: tick→2, due but empty → fallback; counter NOT reset.
        assertEquals("fb", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
        hasMastered = true
        // r3: tick→3 (still ≥ 2) → refresh finally serves. Proves a due-but-empty
        // slot doesn't lose its turn.
        assertEquals("refresh", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun reRollWithinRoundDoesNotAdvanceCadence() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(5)) { vocab("refresh") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        // Round 1: tick→1 (not due). Ten anti-repeat re-rolls reuse the round.
        val round1 = scheduler.beginRound(ctx(dict))
        repeat(10) { round1.select() }
        // Round 2: a single legit tick→2, still < 5. If the re-rolls had each
        // ticked, the counter would be past 5 and refresh would fire here.
        assertEquals("fb", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun newCandidatesRunsOnlyIntroductionAndDoesNotTickCadence() {
        val dict = tinyDictionary()
        val scheduler = PickerScheduler(
            strategies = listOf(
                FakePicker(PickerId.INTRODUCTION, Cadence.OnNewCandidates) { vocab("intro") to Skill.READ },
                FakePicker(PickerId.REFRESH, Cadence.EveryNth(2)) { vocab("refresh") to Skill.READ },
            ),
            fallback = fallbackPicker(),
        )
        // New-word round: only introduction is eligible; refresh must NOT tick.
        assertEquals("intro", scheduler.beginRound(ctx(dict, newCandidates = true)).select().vocab.foreign)
        // r2 normal: refresh tick→1 (not due) → fallback.
        assertEquals("fb", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
        // r3 normal: tick→2 (due) → refresh. Had the new-word round ticked,
        // refresh would have fired on r2 instead.
        assertEquals("refresh", scheduler.beginRound(ctx(dict)).select().vocab.foreign)
    }

    @Test fun duplicatePickerIdsRejected() {
        var threw = false
        try {
            PickerScheduler(
                strategies = listOf(
                    FakePicker(PickerId.REFRESH, Cadence.EveryNth(1)) { null },
                    FakePicker(PickerId.REFRESH, Cadence.EveryNth(1)) { null },
                ),
                fallback = fallbackPicker(),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    @Test fun nonAlwaysFallbackRejected() {
        var threw = false
        try {
            PickerScheduler(
                strategies = emptyList(),
                fallback = FakePicker(PickerId.ACTIVE_WEIGHTED, Cadence.EveryNth(2)) {
                    vocab("fb") to Skill.READ
                },
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }
}
