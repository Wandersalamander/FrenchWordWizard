package io.github.wandersalamander.vocabtrainer.dictionary

/**
 * The single classification of a `(word, skill)` pair — the one source of truth
 * for "what state is this pair in?" (ADR 0001 §4.1). Every count loop, mastery
 * predicate, and (from step 2) picker reads [Vocab.state] instead of re-deriving
 * the `nTimesViewed > 0 && failureProbability() < THRESHOLD` predicate or the
 * active/refresh partition inline.
 *
 * The selection weight rides *inside* the state, so the active pool ([Active])
 * and the refresh pool ([DueForRefresh]) are disjoint **by construction** — a
 * pair lands in exactly one `when` branch — rather than by the two
 * complementary `0.0`-returning functions a test had to police.
 *
 * The [data object] cases allocate nothing; only [Active] / [DueForRefresh]
 * allocate, and only for pairs already in a pool, keeping [Vocab.state] as cheap
 * as the inlined predicate it replaces.
 */
sealed interface PairState {
    /** Predecessor skill not yet mastered (the unlock gate is closed). No picker wants it. */
    data object Locked : PairState

    /** Unlocked but never drilled (`nTimesViewed == 0`). Eligible for introduction only. */
    data object New : PairState

    /** In the active drilling pool. [weight] is the weighted-picker draw weight (> 0). */
    data class Active(val weight: Double) : PairState

    /** Mastered and still within its refresh interval — dormant; no picker wants it. */
    data object Mastered : PairState

    /** Mastered but past its refresh interval. [overdue] is the refresh draw weight (> 0). */
    data class DueForRefresh(val overdue: Double) : PairState

    /**
     * True for pairs retired below the mastery threshold — i.e. [Mastered] or
     * [DueForRefresh]. The "is this pair fully learned?" predicate is exactly
     * "every ladder skill's state is learned" (see [Vocab.isFullyLearned]).
     */
    val isLearned: Boolean get() = this is Mastered || this is DueForRefresh

    /**
     * True for pairs the active weighted picker draws: [Active] and [New].
     * [New] is included because a freshly-unlocked skill (predecessor just
     * mastered, this skill never drilled) enters rotation through the active
     * pool with a seeded [Vocab.sortValue] weight — it is *not* handled by the
     * separate new-*word* introduction path, which only covers words never
     * introduced at all. The active-set *count* bar, by contrast, counts only
     * [Active] (drilled) pairs, which is why that distinction is kept explicit.
     */
    val isActiveDraw: Boolean get() = this is Active || this is New
}
