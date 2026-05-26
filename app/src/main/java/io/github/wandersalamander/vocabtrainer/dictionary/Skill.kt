package io.github.wandersalamander.vocabtrainer.dictionary

import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import java.util.concurrent.TimeUnit

/**
 * A learning skill in the progressive-unlock ladder.
 *
 * [unlockThreshold] is the predecessor skill's failureProbability gate: this
 * skill becomes active for a word once the previous skill's stats drop below
 * this value. Unused for the first skill in the ladder (always unlocked).
 * Once unlocked AND practiced, a skill stays unlocked forever for that word
 * even if the predecessor's stats later drift back up — the unlock is sticky.
 *
 * The enum's [name] (READ / LISTEN / INVERT) is the value persisted in Room
 * via [io.github.wandersalamander.vocabtrainer.data.Converters]. Renaming an enum case
 * would orphan existing rows — add a new case instead.
 */
enum class Skill(
    val displayName: String,
    val unlockThreshold: Float,
) {
    READ("Reading", 0.0f),
    LISTEN("Listening", 0.4f),  // unlocks at 3 stars on READ
    INVERT("Recall", 0.2f),  // unlocks at 4 stars on LISTEN
    ;

    companion object {
        // Order = unlock chain (each skill requires the previous one mastered).
        // Insert new skills here, not at the enum's natural position.
        val ladder: List<Skill> = listOf(
            READ,
            LISTEN,
            INVERT,
        )
    }
}

data class SkillStats(
    var viewTimeMilli: Long = DEFAULT_VIEW_TIME_MS,
    var viewTimeMilli_prev: Long = DEFAULT_VIEW_TIME_MS,
    var nTimesViewed: Int = 0,
    var nTimesFailed: Float = 0.0f,
    var lastDisplayed: Long = 0L,
    var lastTimeFailed: Long = 0L,
    var recentInteractions: List<VocabInteraction> = emptyList(),
    /**
     * First-exposure fast-track: when true, [failureProbability] short-circuits
     * to [FAST_TRACKED_FPB] (sub-threshold) so the pair retires from the active
     * pool without lying about the counters. Set by QuizController on a trivial
     * first round (fast, clean, no help); cleared by [recordFailure] on any
     * penalty, or by QuizController on a later slow round.
     */
    var fastTracked: Boolean = false,
    /**
     * Post-mastery refresh cadence in days. Read by
     * [io.github.wandersalamander.vocabtrainer.dictionary.Vocab.failureProbabilityMasteredWords]
     * to decide when a mastered (word, skill) pair becomes refresh-eligible
     * again. Doubles on every clean refresh of an already-mastered pair (1 → 2
     * → 4 → 8 …, capped at [MAX_REFRESH_INTERVAL_DAYS]), holds steady when the
     * user needed a hint, resets to 1 on a full fail. The doubling only fires
     * AFTER mastery — the round that first crosses the threshold leaves the
     * interval at its current value, so a freshly mastered pair surfaces 1 day
     * later, exactly once, before the schedule starts growing.
     */
    var refreshIntervalDays: Float = 1f,
) {
    /** Single entry point for failure events so the counter and timestamp stay in sync. */
    fun recordFailure(amount: Float) {
        nTimesFailed += amount
        lastTimeFailed = System.currentTimeMillis()
        // Any penalty contradicts the fast-track trust: the user struggled,
        // so revoke the short-circuit and let the formula compute naturally.
        if (amount > 0f) fastTracked = false
    }

    /**
     * Append one round's outcome to [recentInteractions] and trim the window
     * to the last [MAX_INTERACTIONS] entries. Called once per committed round
     * from the quiz save path; compromised rounds (rolled back via [copyFrom])
     * skip this call.
     *
     * [timestampMs] defaults to the current wall clock for live commits; the
     * replay path ([io.github.wandersalamander.vocabtrainer.data.scoring.StatsReplay])
     * passes the log entry's actual timestamp instead so reconstructed
     * windows match the historical reality, not the moment of replay.
     */
    fun recordInteraction(failAmount: Float, timestampMs: Long = System.currentTimeMillis()) {
        val entry = VocabInteraction(
            timestampMs = timestampMs,
            failAmount = failAmount,
        )
        val updated = recentInteractions + entry
        recentInteractions = if (updated.size > MAX_INTERACTIONS) {
            updated.subList(updated.size - MAX_INTERACTIONS, updated.size).toList()
        } else {
            updated
        }
    }

    /**
     * Apply one committed round's outcome to this stats record: bump the view
     * counter, slide the view-time EMA, stamp the display time, and append the
     * outcome to the rolling window. Shared by the live commit path
     * ([io.github.wandersalamander.vocabtrainer.quiz.QuizController.saveCurrentVocab]),
     * the mastery-crossing simulation
     * ([io.github.wandersalamander.vocabtrainer.quiz.QuizController.wouldFinishSkillOnNext]),
     * and the event-sourcing replay
     * ([io.github.wandersalamander.vocabtrainer.data.scoring.StatsReplay.applyEntry])
     * so the three paths can never drift on the per-round math.
     *
     * Does NOT handle fast-track set/revoke or mid-round failure bookkeeping —
     * those are caller-specific (live path tracks them mid-round; replay
     * collapses them from the log; simulation skips them).
     */
    fun applyRoundCommit(timeElapsedMs: Long, failAmount: Float, nowMs: Long) {
        // Snapshot the pre-round mastery state for the refresh-interval update
        // below. Any mid-round recordFailure has already landed by now, so this
        // is the fpb the picker saw when selecting this round (a clean round
        // can't shift it; only failures can, and those are reflected here).
        val wasMasteredBefore = failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD
        nTimesViewed += 1
        viewTimeMilli_prev = viewTimeMilli
        viewTimeMilli = (
            VIEW_TIME_EMA_ALPHA * timeElapsedMs +
                (1.0 - VIEW_TIME_EMA_ALPHA) * viewTimeMilli
            ).toLong()
        lastDisplayed = nowMs
        recordInteraction(failAmount, nowMs)
        when {
            // Full fail (post-mastery or active drilling alike): the next
            // mastered re-entry starts the schedule over at 1 day.
            failAmount >= 1f -> refreshIntervalDays = 1f
            // Hint: held the schedule steady; the next refresh fires at the
            // same interval rather than punishing the user for needing a nudge.
            failAmount > 0f -> Unit
            // Clean refresh of an already-mastered pair: earn a longer gap.
            // The mastery-entry round itself (wasMasteredBefore == false) is
            // skipped so the user sees the first post-mastery refresh exactly
            // 1 day out.
            wasMasteredBefore ->
                refreshIntervalDays =
                    (refreshIntervalDays * 2f).coerceAtMost(MAX_REFRESH_INTERVAL_DAYS)
        }
    }

    /**
     * Copy every field from [other] into this instance, in place. Used to
     * roll back a round when the active skill's state changed mid-round in a
     * way that compromises the result (e.g. listening toggled off in a LISTEN
     * round). The receiving instance is the one held in the Vocab's
     * skillStats map, so we mutate rather than swap references.
     */
    fun copyFrom(other: SkillStats) {
        viewTimeMilli = other.viewTimeMilli
        viewTimeMilli_prev = other.viewTimeMilli_prev
        nTimesViewed = other.nTimesViewed
        nTimesFailed = other.nTimesFailed
        lastDisplayed = other.lastDisplayed
        lastTimeFailed = other.lastTimeFailed
        recentInteractions = other.recentInteractions
        fastTracked = other.fastTracked
        refreshIntervalDays = other.refreshIntervalDays
    }

    fun failureProbability(): Float {
        if (nTimesViewed == 0) return 1.0f
        // Trusted as mastered from a trivial first round (set by QuizController
        // via the fast-track gate). The flag is cleared the moment evidence
        // contradicts it — any recordFailure with amount > 0, or a slow round
        // detected by QuizController — so once that happens the formula below
        // takes over normally.
        if (fastTracked) return FAST_TRACKED_FPB
        val base = 0.5f / nTimesViewed.toFloat()
        val counterRate = (nTimesFailed / nTimesViewed.toFloat()).coerceIn(0f, 1f)

        // Blend the lifetime counter rate with a recency-weighted rate over
        // [recentInteractions]. With an empty window (legacy migrated rows,
        // or a fresh skill) the formula collapses to today's exact behavior.
        // Once the window fills, recent outcomes pull the estimate; on top of
        // that the lifetime counter is discounted as a time-spaced clean streak
        // builds (see [counterKeepFactor]), so a word that was failed often long
        // ago but is now answered correctly stops being dragged down by history.
        val effectiveRate = if (recentInteractions.isEmpty()) {
            counterRate
        } else {
            val windowRate = windowFailureProbability()
            val n = recentInteractions.size
            val windowWeight = n.toFloat() / (n + WINDOW_BLEND_PRIOR)
            // Shrink only the counter's share of the blend, then renormalise so
            // the freed weight is handed to the window. When there's no clean
            // streak counterKeepFactor() is 1.0 and this is byte-for-byte the
            // old blendWeight * windowRate + (1 - blendWeight) * counterRate.
            val counterWeight = (1f - windowWeight) * counterKeepFactor()
            val totalWeight = windowWeight + counterWeight
            (windowWeight * windowRate + counterWeight * counterRate) / totalWeight
        }

        val raw = base + (1.0f - base) * effectiveRate
        val clamped = raw.coerceIn(0.0f, 1.0f)
        // Sustained-slowness gate: a clean run of fast Next-presses can drive
        // raw fpb under the mastery threshold even when the user is still
        // actually struggling. If either of the last two view-time samples
        // exceeds SLOW_VIEW_TIME_MAX_MS, floor the reported fpb just above the
        // mastery cut so the (word, skill) pair stays in the active pool. Both
        // rounds must be fast for the pair to slip through.
        return if (clamped < SLOW_FPB_FLOOR &&
            maxOf(viewTimeMilli, viewTimeMilli_prev) > SLOW_VIEW_TIME_MAX_MS) {
            SLOW_FPB_FLOOR
        } else {
            clamped
        }
    }

    /**
     * Recency-weighted mean of [recentInteractions] failAmounts. The newest
     * entry has weight 1.0, each older entry × [WINDOW_DECAY], so a
     * full 10-entry window weighs the newest sample ≈6× the oldest. Returns 0
     * for an empty window (callers must guard — see [failureProbability]).
     */
    private fun windowFailureProbability(): Float {
        if (recentInteractions.isEmpty()) return 0f
        var weightSum = 0.0
        var failSum = 0.0
        val n = recentInteractions.size
        for (i in 0 until n) {
            // List is chronological (append at end); newest = last index.
            val agePos = n - 1 - i
            val w = Math.pow(WINDOW_DECAY.toDouble(), agePos.toDouble())
            weightSum += w
            failSum += w * recentInteractions[i].failAmount.coerceIn(0f, 1f)
        }
        return (failSum / weightSum).toFloat().coerceIn(0f, 1f)
    }

    /**
     * How much of the lifetime counter's blend weight to KEEP, in [0, 1].
     *
     * A word that was failed often long ago but is now answered correctly
     * should be allowed past the mastery gate — but only on evidence of
     * *durable* retention, not one cramming session. So the counter fades out
     * as a clean streak builds, gated on BOTH:
     *   - elapsed clean *days* since the last failure (exponential decay with
     *     time-constant [COUNTER_DECAY_TAU_DAYS]); a burst of correct answers
     *     inside a single session barely moves this, a clean run across a week
     *     does — the spacing effect, encoded.
     *   - a few trailing clean reps in the window ([COUNTER_DISCOUNT_MIN_REPS]),
     *     so failing once, getting it right once, then leaving the word idle for
     *     weeks can't collapse the counter off that single lucky rep.
     *
     * Returns 1.0 (no discount — today's behavior) whenever there's no
     * measurable post-failure clean run, which is also why every counter-only /
     * unset-timestamp path is unaffected.
     */
    private fun counterKeepFactor(): Float {
        val timeDecay = Math.exp(-cleanStreakDays() / COUNTER_DECAY_TAU_DAYS).toFloat()
        val repConfidence =
            (trailingCleanReps().toFloat() / COUNTER_DISCOUNT_MIN_REPS).coerceIn(0f, 1f)
        return 1f - repConfidence * (1f - timeDecay)
    }

    /**
     * Wall-clock clean streak in days: elapsed time from the last recorded
     * failure to the most recent view. 0 when there's no measurable post-failure
     * clean run — never failed, the last round *was* the failure (timestamps
     * coincide), or a legacy row whose fail timestamp predates tracking.
     */
    private fun cleanStreakDays(): Double =
        if (lastTimeFailed > 0L && lastDisplayed > lastTimeFailed) {
            (lastDisplayed - lastTimeFailed).toDouble() / MILLIS_PER_DAY
        } else {
            0.0
        }

    /** Count of consecutive fully-clean (failAmount ≤ 0) rounds at the newest end of the window. */
    private fun trailingCleanReps(): Int {
        var count = 0
        for (i in recentInteractions.indices.reversed()) {
            if (recentInteractions[i].failAmount <= 0f) count++ else break
        }
        return count
    }

    fun meanTimeViewedMilli(): Double = viewTimeMilli.toDouble()

    fun viewedMinutesAgo(): Long =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)

    fun getTimeString(): String =
        String.format("⧖ %.1f s", meanTimeViewedMilli() / 1e3)

    fun getStarsString(): String {
        val successProbability = (1.0f - failureProbability()).coerceIn(0.0f, 1.0f)
        val totalStars = 5
        val full: Int = (totalStars * successProbability).toInt()
        return "★".repeat(full) + "☆".repeat(totalStars - full)
    }

    fun getInfoString(): String = getTimeString() + "\n" + getStarsString()

    companion object {
        // Seed value for a freshly-encountered word's view-time estimate (10s).
        // High enough that the first round's actual time gets blended in without
        // an undue jump up or down.
        const val DEFAULT_VIEW_TIME_MS = 10_000L

        // Smoothing constant for the per-round view-time EMA:
        //   viewTimeMilli' = ALPHA * timeElapsed + (1 - ALPHA) * viewTimeMilli
        // Shared between the live commit path (QuizController.saveCurrentVocab,
        // wouldFinishSkillOnNext) and the historical replay (StatsReplay), so a
        // future change to the smoothing curve updates both — no two-place edit.
        const val VIEW_TIME_EMA_ALPHA = 0.3

        // If either of the last two view-time samples exceeds this,
        // failureProbability is floored so the pair can't slip past the mastery
        // gate. Both rounds must come in under 5s for the pair to be allowed
        // through — one fast round can no longer compensate for one slow one.
        const val SLOW_VIEW_TIME_MAX_MS = 5_000L

        // Kept in lockstep with Vocab.SKILL_FINISHED_THRESHOLD (0.10) + 0.05:
        // just above the mastery cut so the pair stays active without being
        // surfaced as urgently as a fresh failure would.
        const val SLOW_FPB_FLOOR = 0.15f

        // Sub-threshold value returned by [failureProbability] when [fastTracked]
        // is set. Must stay strictly below Vocab.SKILL_FINISHED_THRESHOLD (0.10)
        // so the pair retires from the active pool. 0.05 leaves a margin so a
        // future tightening of the threshold by a few hundredths doesn't silently
        // undo fast-track; the calibration is pinned by SchedulingMathTest.
        const val FAST_TRACKED_FPB = 0.05f

        // Length of the rolling per-round outcome window persisted alongside
        // the counters. Sized to fit comfortably in one TEXT cell while still
        // giving recency-weighted fpb formulas enough signal to work with.
        const val MAX_INTERACTIONS = 10

        // Per-entry weight ratio in [windowFailureProbability]: oldest weight
        // = newest * decay^(MAX_INTERACTIONS - 1). At 0.8 the newest sample
        // is ≈6× the weight of the oldest (0.8^9 ≈ 0.13), giving a half-life
        // of ~3 entries — recent enough to react to a struggling run, slow
        // enough that one bad round doesn't tank the estimate.
        const val WINDOW_DECAY = 0.8f

        // Bayesian shrinkage constant for blending the window rate against
        // the lifetime counter rate: window weight = n / (n + this). With
        // n=10 (full window) the window gets ~77 % weight; a single fresh
        // entry only gets 25 %, so the counters still anchor early-stage
        // estimates. Lower = window dominates sooner.
        const val WINDOW_BLEND_PRIOR = 3f

        // Time constant (in days) for fading out the lifetime fail counter as a
        // clean streak builds. At one TAU of clean days the counter keeps ~37 %
        // of its blend weight, ~14 % at two TAU, ~5 % at three. Measured in
        // calendar time on purpose: a word with a bad history can't be crammed
        // past the mastery gate in one session — the clean run has to survive
        // across days. This is the main "ease re-learned words through" knob.
        const val COUNTER_DECAY_TAU_DAYS = 4.0

        // Trailing clean reps required before the time discount above fully
        // applies; below this it scales down linearly. Guards the idle-gap case
        // where a single correct answer across weeks would otherwise collapse
        // the counter on its own.
        const val COUNTER_DISCOUNT_MIN_REPS = 3f

        // Local copy of the day-in-millis constant (Vocab keeps its own private
        // one) so SkillStats' clean-streak math is self-contained.
        private const val MILLIS_PER_DAY = 86_400_000.0

        // Ceiling for the doubling refresh schedule. After ~7-8 successful
        // post-mastery refreshes the interval saturates here. Matches the
        // prior Vocab.REFRESH_CAP_DAYS so the long-tail behavior is unchanged.
        const val MAX_REFRESH_INTERVAL_DAYS = 180f
    }
}
