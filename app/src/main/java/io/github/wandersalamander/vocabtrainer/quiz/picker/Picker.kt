package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab

/**
 * A single vocab-selection policy. Each picker owns ONE concern (introduce a
 * new word, refresh a decayed-mastered pair, rescue a starved near-mastered
 * pair, the default weighted draw, …) and the [PickerScheduler] composes them.
 *
 * Adding a behavior is "standard practice": write a new [PickerStrategy], give
 * it a [cadence], and register it in [PickerScheduler] at the right precedence.
 * You can roll it in smoothly by starting at a rare [Cadence.EveryNth] and
 * tightening the interval, and remove it just as cleanly. A future model-driven
 * picker (FSRS/HLR) drops in the same way, ramped up as it earns trust.
 *
 * Implementations must be cheap — [pick] runs on the per-round hot path, with
 * anti-repeat retries on top — and side-effect free (cadence/counter state
 * lives in the scheduler, not the picker).
 */
interface PickerStrategy {
    val id: PickerId

    /** When this picker is allowed to serve a round. See [Cadence]. */
    val cadence: Cadence

    /**
     * Produce a (vocab, skill) pair, or null when this picker has nothing
     * applicable this round (e.g. the refresh picker when no mastered pair is
     * due). Returning null lets the scheduler fall through to the next picker.
     */
    fun pick(ctx: PickContext): Pair<Vocab, Skill>?
}

/**
 * Stable identity for a picker. Used as the cadence-counter key and for
 * precedence/eligibility bookkeeping in [PickerScheduler], so it must be
 * unique per registered strategy.
 */
enum class PickerId {
    INTRODUCTION,
    REFRESH,
    UNIFORM_FLOOR,
    ACTIVE_WEIGHTED,
}

/** When a [PickerStrategy] is eligible to serve a round. */
sealed interface Cadence {
    /** Eligible every round. Reserved for the default workhorse / fallback. */
    data object Always : Cadence

    /**
     * Eligible only on a "new word" round (the user pressed New, or the active
     * pool is being topped up). Mutually exclusive with the cadence pickers:
     * a new-word round never consumes a periodic slot.
     */
    data object OnNewCandidates : Cadence

    /**
     * Eligible once every [interval] committed rounds. The scheduler ticks a
     * per-picker counter each round and grants the slot when it reaches the
     * interval, resetting only when the slot is actually used (a due-but-empty
     * picker stays eligible so the next round retries).
     */
    data class EveryNth(val interval: Int) : Cadence {
        init {
            require(interval >= 1) { "EveryNth interval must be >= 1, was $interval" }
        }
    }
}

/**
 * Everything a picker needs to make one round's decision. Recreated per round
 * by [io.github.wandersalamander.vocabtrainer.quiz.QuizPicker]; immutable.
 *
 * [skillFilter] excludes skills from the candidate pool (e.g. LISTEN when the
 * user has muted listening mode).
 */
class PickContext(
    val dictionary: MyDictionary,
    val newCandidates: Boolean,
    val skillFilter: (Skill) -> Boolean,
)
