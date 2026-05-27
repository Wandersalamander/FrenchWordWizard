package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import io.github.wandersalamander.vocabtrainer.dictionary.getActiveVocabWeighted
import io.github.wandersalamander.vocabtrainer.dictionary.getInactiveVocab
import io.github.wandersalamander.vocabtrainer.dictionary.pickMasteredVocabToRefresh
import io.github.wandersalamander.vocabtrainer.dictionary.pickUniformActiveVocab

/**
 * The four pickers that reproduce the legacy QuizPicker cascade, each a thin
 * policy wrapper over a [io.github.wandersalamander.vocabtrainer.dictionary] selection
 * function. The selection *math* is unchanged — these only declare WHEN each
 * policy applies (its [Cadence]) so the [PickerScheduler] can compose them.
 *
 * Registration order in QuizPicker is the precedence:
 *   INTRODUCTION → REFRESH → UNIFORM_FLOOR → (fallback) ACTIVE_WEIGHTED.
 */

/** Introduces a fresh CSV word. Only on new-word rounds; never a periodic slot. */
class IntroductionPicker : PickerStrategy {
    override val id = PickerId.INTRODUCTION
    override val cadence = Cadence.OnNewCandidates
    override fun pick(ctx: PickContext): Pair<Vocab, Skill>? =
        if (ctx.newCandidates) ctx.dictionary.getInactiveVocab() else null
}

/**
 * Surfaces an already-mastered (vocab, skill) pair whose recall has likely
 * decayed past its stability window. Returns null when nothing is due — the
 * scheduler then leaves this picker's cadence past threshold so the next round
 * retries instead of waiting a full interval.
 */
class RefreshPicker(intervalRounds: Int) : PickerStrategy {
    override val id = PickerId.REFRESH
    override val cadence = Cadence.EveryNth(intervalRounds)
    override fun pick(ctx: PickContext): Pair<Vocab, Skill>? =
        ctx.dictionary.pickMasteredVocabToRefresh(ctx.skillFilter)
}

/**
 * Backup safety floor: a uniform-random pick over the SAME active pool the
 * weighted picker uses, so near-mastered pairs whose weighted probability has
 * shrunk too far still get surfaced. Intentionally shares the active domain —
 * it's an alternate selection on its own slot, not a second pool (see
 * [PickerScheduler] overlap notes). Kept deliberately simple (uniform, not
 * weighted) per the "floors stay simple" design rule.
 */
class UniformFloorPicker(intervalRounds: Int) : PickerStrategy {
    override val id = PickerId.UNIFORM_FLOOR
    override val cadence = Cadence.EveryNth(intervalRounds)
    override fun pick(ctx: PickContext): Pair<Vocab, Skill>? =
        ctx.dictionary.pickUniformActiveVocab(ctx.skillFilter)
}

/**
 * The default workhorse: weighted-random over active (vocab, skill) pairs by
 * [Vocab.sortValue]. Registered as the scheduler's fallback because
 * [getActiveVocabWeighted] never returns null (it self-falls-back to
 * introducing/most-available when the active pool is thin), guaranteeing every
 * round yields a pick.
 */
class ActiveWeightedPicker : PickerStrategy {
    override val id = PickerId.ACTIVE_WEIGHTED
    override val cadence = Cadence.Always
    override fun pick(ctx: PickContext): Pair<Vocab, Skill> =
        ctx.dictionary.getActiveVocabWeighted(ctx.skillFilter, ctx.autoIntroduceThreshold)
}
