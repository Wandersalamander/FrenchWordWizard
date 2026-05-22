package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab

/**
 * Composes several [PickerStrategy] policies into one per-round decision,
 * generalizing the hand-rolled cascade that used to live in QuizPicker.pickOne.
 *
 * ## Preventing overlap
 *
 * Two distinct kinds of overlap can occur with multiple pickers; the scheduler
 * resolves each explicitly:
 *
 * 1. **Round-level overlap** — two pickers both want to serve the *same* round.
 *    Resolved by **precedence**: [strategies] is an ordered list (highest
 *    precedence first); the first one that is both eligible (its [Cadence] is
 *    satisfied this round) and able (its [PickerStrategy.pick] returns non-null)
 *    wins, and the rest are skipped. Precedence is therefore explicit and
 *    total — there is never an ambiguous "which picker serves?" moment.
 *
 * 2. **Domain-level overlap** — the *same* (vocab, skill) pair living in two
 *    pickers' candidate pools, so it gets more than its share of slots over
 *    time. This is NOT resolved here; it's a partition invariant the pickers
 *    must uphold and a test pins (see PickerOverlapTest): the active pool
 *    (sortValue > 0) and the refresh pool (failureProbabilityMasteredWords > 0)
 *    are mutually exclusive by the mastery threshold. The uniform-floor picker
 *    *intentionally* shares the active pool — it's an alternate selection over
 *    the same candidates on its own slot, not a second independent pool, so it
 *    doesn't inflate any pair's overall exposure.
 *
 * A guaranteed [fallback] (the default weighted picker, which never returns
 * null) is tried after every strategy, so [Round.select] always yields a pick.
 *
 * Cadence counters live here, not in the pickers, so pickers stay stateless and
 * the "tick once per round, re-roll freely" contract is enforced in one place.
 */
class PickerScheduler(
    private val strategies: List<PickerStrategy>,
    private val fallback: PickerStrategy,
) {
    private val cadenceCounters = HashMap<PickerId, Int>()

    init {
        val ids = strategies.map { it.id } + fallback.id
        require(ids.toSet().size == ids.size) {
            "Duplicate PickerId among strategies/fallback: $ids"
        }
        require(fallback.cadence is Cadence.Always) {
            "Fallback picker ${fallback.id} must have Cadence.Always so a pick is always available"
        }
    }

    /**
     * Open a round: tick cadence counters **once** and freeze which pickers are
     * eligible. Returns a [Round] whose [Round.select] can be called repeatedly
     * (for anti-repeat re-rolls) without advancing any cadence — so retrying to
     * dodge a recently-shown word can't race a periodic slot past its interval.
     */
    fun beginRound(ctx: PickContext): Round {
        val eligible = computeEligibilityAndTick(ctx.newCandidates)
        return Round(ctx, eligible)
    }

    inner class Round internal constructor(
        private val ctx: PickContext,
        private val eligible: Set<PickerId>,
    ) {
        /** Run the precedence cascade. Idempotent w.r.t. cadence; safe to re-roll. */
        fun select(): Pair<Vocab, Skill> = select(ctx, eligible)
    }

    private fun select(ctx: PickContext, eligible: Set<PickerId>): Pair<Vocab, Skill> {
        for (strategy in strategies) {
            if (strategy.id !in eligible) continue
            val pick = strategy.pick(ctx) ?: continue
            // Slot granted — reset only THIS picker's cadence. A higher-priority
            // picker winning leaves lower ones' counters untouched, so they stay
            // due and serve the next round (no starvation between cadence slots).
            resetCadence(strategy)
            return pick
        }
        return fallback.pick(ctx)
            ?: error("Fallback picker ${fallback.id} returned null; it must always produce a pick")
    }

    /**
     * Advance cadence counters and return the eligible set. On a new-word round
     * the periodic counters are deliberately NOT ticked — introducing a word
     * must not burn a refresh/uniform slot — and only [Cadence.OnNewCandidates]
     * pickers are eligible.
     */
    private fun computeEligibilityAndTick(newCandidates: Boolean): Set<PickerId> {
        if (newCandidates) {
            return strategies
                .filter { it.cadence is Cadence.OnNewCandidates }
                .map { it.id }
                .toSet()
        }
        val eligible = mutableSetOf<PickerId>()
        for (strategy in strategies) {
            when (val cadence = strategy.cadence) {
                is Cadence.Always -> eligible += strategy.id
                is Cadence.OnNewCandidates -> Unit // only on new-word rounds
                is Cadence.EveryNth -> {
                    val next = (cadenceCounters[strategy.id] ?: 0) + 1
                    cadenceCounters[strategy.id] = next
                    if (next >= cadence.interval) eligible += strategy.id
                }
            }
        }
        return eligible
    }

    private fun resetCadence(strategy: PickerStrategy) {
        if (strategy.cadence is Cadence.EveryNth) cadenceCounters[strategy.id] = 0
    }
}
