package io.github.wandersalamander.vocabtrainer.dictionary

import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import java.util.Locale
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
 * via [io.github.wandersalamander.vocabtrainer.data.db.Converters]. Renaming an
 * enum case would orphan existing rows — add a new case instead.
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
     * again. On a clean refresh of an already-mastered pair it doubles (1 → 2 →
     * 4 → 8 …, capped at [MAX_REFRESH_INTERVAL_DAYS]); holds steady when the user
     * needed a hint; resets to 1 on a full fail. The doubling only fires AFTER
     * mastery — the round that first crosses the threshold leaves the interval at
     * its current value, so a freshly mastered pair surfaces 1 day later, exactly
     * once, before the schedule starts growing.
     *
     * The doubling is anchored on whichever is longer: this scheduled value or
     * the *actual* time the user went without seeing the pair (the late-review
     * bonus). A clean recall 7 days after a 2-day schedule proves ~7-day
     * retention, so the next gap jumps to ~14, not 4 — the user reaching the app
     * late and still succeeding shouldn't be punished with a short re-show.
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
        // Actual gap since this pair was last shown, captured BEFORE lastDisplayed
        // is overwritten below. For a clean post-mastery refresh this is the real
        // time the user retained the pair — which can far exceed the scheduled
        // refreshIntervalDays when they returned to the app late. 0 for a pair
        // that's never been displayed (lastDisplayed still at the epoch).
        val elapsedDays = if (lastDisplayed > 0L) {
            ((nowMs - lastDisplayed).toDouble() / MILLIS_PER_DAY).toFloat()
        } else {
            0f
        }
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
            // mastered re-entry starts the schedule over at 1 day. Kept exact —
            // a missed word's first re-check is a hard 1-day floor, not a
            // jittered target.
            failAmount >= 1f -> refreshIntervalDays = 1f
            // Hint: held the schedule steady; the next refresh fires at the
            // same interval rather than punishing the user for needing a nudge.
            failAmount > 0f -> Unit
            // Clean refresh of an already-mastered pair: earn a longer gap. The
            // doubling anchors on whichever is longer — the scheduled interval or
            // the actual elapsed gap (late-review bonus): recalling cleanly 7 days
            // after a 2-day schedule earns ~14, not 4. maxOf guards the rare
            // early-recall case from shrinking the schedule below the old doubling.
            // The doubled gap is then jittered ±REFRESH_JITTER_FRACTION so the
            // ladder's phase scatters and a cohort mastered together doesn't keep
            // re-converging on the same due day (see refreshJitterFactor).
            wasMasteredBefore ->
                refreshIntervalDays =
                    (maxOf(refreshIntervalDays, elapsedDays) * 2f * refreshJitterFactor(nowMs))
                        .coerceAtMost(MAX_REFRESH_INTERVAL_DAYS)
            // Clean round that just crossed into mastery (wasMasteredBefore was
            // false, post-round fpb is now under the threshold): seed the first
            // post-mastery gap jittered around the 1-day base, so drilled words
            // mastered in the same sitting don't all surface their first refresh
            // tomorrow. Non-crossing active-drilling rounds fail this guard and
            // fall through untouched — the field isn't read until the pair is
            // actually in the refresh pool, so it must not grow during drilling.
            failureProbability() < Vocab.SKILL_FINISHED_THRESHOLD ->
                refreshIntervalDays = BASE_REFRESH_INTERVAL_DAYS * refreshJitterFactor(nowMs)
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
        // Sample-size prior. An unbroken clean streak collapses it faster than
        // the linear 1/n (see [streakConfidenceViews]) so a word answered
        // cleanly a few times in a row masters in ~3 reps instead of ~6;
        // counterRate keeps the *observed* fail fraction on the true view count.
        val base = 0.5f / (nTimesViewed.toFloat() + streakConfidenceViews())
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

    /**
     * Extra "virtual views" an unbroken trailing clean streak credits to the
     * sample-size prior (`base` in [failureProbability]), so consistent recall
     * collapses the prior faster than the linear 1/n.
     *
     * A streak of length s (newest end of the window, reset to 0 by any penalty
     * — see [trailingCleanReps]) is worth [STREAK_CONFIDENCE_BASE]^(s−1) − 1
     * views. A single clean rep (s < 2) earns nothing — this rewards a *streak*,
     * not one lucky pass — while three clean reps in a row drop the prior under
     * the mastery threshold (≈3 reps to master vs ≈6 without). The streak counts
     * correctness only; the slow-view floor in [failureProbability] still pins a
     * slow answer at [SLOW_FPB_FLOOR], so the shortcut requires the reps to be
     * BOTH correct AND fast. Capped at [STREAK_CONFIDENCE_MAX_REPS] so a long
     * clean-but-slow streak (held active by the floor) can't drive the prior's
     * denominator arbitrarily large.
     *
     * Returns 0 for an empty window (migrated rows, fresh skills) or any window
     * whose newest entry is a penalty, so the formula collapses to today's exact
     * 1/n behaviour until a real clean streak accumulates.
     */
    private fun streakConfidenceViews(): Float {
        val s = trailingCleanReps().coerceAtMost(STREAK_CONFIDENCE_MAX_REPS)
        if (s < 2) return 0f
        return (Math.pow(STREAK_CONFIDENCE_BASE, (s - 1).toDouble()) - 1.0).toFloat()
    }

    fun meanTimeViewedMilli(): Double = viewTimeMilli.toDouble()

    fun viewedMinutesAgo(): Long =
        TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastDisplayed)

    fun getTimeString(): String =
        String.format(Locale.getDefault(), "⧖ %.1f s", meanTimeViewedMilli() / 1e3)

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

        // Geometric base for the clean-streak confidence bonus in
        // [streakConfidenceViews]: an unbroken clean streak of length s credits
        // BASE^(s−1) − 1 "virtual views" to the sample-size prior. 2.0 ⇒ each
        // further clean rep roughly doubles the credited evidence, landing a
        // flawless fast word at mastery in ≈3 reps (vs ≈6 on the bare 1/n prior).
        // Raise to push easy words out sooner; lower toward 1.0 to fall back
        // toward the linear prior. The slow-view floor still gates on speed, so
        // this only ever accelerates correct-AND-fast pairs.
        const val STREAK_CONFIDENCE_BASE = 2.0

        // Cap on the streak length fed to [streakConfidenceViews]. A pair masters
        // long before a streak this long under normal play; the cap only bounds
        // the pathological clean-but-slow case (pinned at [SLOW_FPB_FLOOR] while
        // its clean streak keeps growing) so the prior stays well-conditioned.
        const val STREAK_CONFIDENCE_MAX_REPS = 6

        // Local copy of the day-in-millis constant (Vocab keeps its own private
        // one) so SkillStats' clean-streak math is self-contained.
        private const val MILLIS_PER_DAY = 86_400_000.0

        // Ceiling for the doubling refresh schedule. After ~7-8 successful
        // post-mastery refreshes the interval saturates here. Matches the
        // prior Vocab.REFRESH_CAP_DAYS so the long-tail behavior is unchanged.
        const val MAX_REFRESH_INTERVAL_DAYS = 180f

        // 1-day base for a drilled pair's first post-mastery refresh, before the
        // per-round jitter below is applied at the mastery-crossing round.
        const val BASE_REFRESH_INTERVAL_DAYS = 1f

        // Spread applied to every post-mastery refresh gap: the stored interval is
        // multiplied by a per-round factor in [1 - this, 1 + this). De-syncs a
        // cohort of words mastered in one sitting that would otherwise all fall
        // due on the same day; mean 1.0 keeps the doubling ladder's average pace
        // (1 → 2 → 4 …) unchanged, only scattering its phase.
        const val REFRESH_JITTER_FRACTION = 0.25f

        /**
         * Per-round multiplier in
         * [1 - [REFRESH_JITTER_FRACTION], 1 + [REFRESH_JITTER_FRACTION]) used to
         * jitter the post-mastery refresh schedule.
         *
         * Seeded by the round timestamp [seedMs] — NOT a clock or RNG read — on
         * purpose: the live commit and the event-sourcing replay both run
         * [applyRoundCommit] and feed it the same nowMs (the live path stamps it
         * onto the log entry; replay feeds that timestamp back), so both compute
         * an identical schedule. A clock/RNG seed would diverge on replay and a
         * word rebuilt from the log would land on a different due date.
         */
        fun refreshJitterFactor(seedMs: Long): Float {
            // hashCode mixes the high/low halves of the Long; mask off the sign,
            // divide by 2^31 → [0, 1).
            val unit = (seedMs.hashCode() and 0x7fffffff) / 2_147_483_648f
            return (1f - REFRESH_JITTER_FRACTION) + unit * (2f * REFRESH_JITTER_FRACTION)
        }

        // Initial refresh gap for a pair that reached mastery via the fast-track
        // gate (trivial first-exposure clean round) rather than through the
        // normal drilling-then-crossing path. A fast-tracked pair never sat in
        // the active pool, so the default 1-day first refresh would resurface it
        // the very next day — too eager for a word the user clearly already knew.
        //
        // Spread across a [MIN, MAX) window instead of a single fixed day: a user
        // who clears a big batch of new words in one sitting (e.g. an intermediate
        // speaker breezing through essentials) would otherwise have every one of
        // them fall due on the SAME day a week later — a thundering herd of
        // hundreds of refreshes at once. Jittering the first gap by word identity
        // de-synchronizes the cohort into a smooth daily trickle, and the spread
        // persists because the schedule doubles from each pair's own gap.
        const val FAST_TRACK_REFRESH_MIN_DAYS = 3f
        const val FAST_TRACK_REFRESH_MAX_DAYS = 10f

        /**
         * Deterministic first-refresh gap for a freshly fast-tracked (hash, skill)
         * pair, uniform in
         * [[FAST_TRACK_REFRESH_MIN_DAYS], [FAST_TRACK_REFRESH_MAX_DAYS]).
         *
         * Keyed on the pair identity, NOT a clock or RNG, on purpose: the live
         * commit ([io.github.wandersalamander.vocabtrainer.quiz.QuizController]'s
         * fast-track gate) and the event-sourcing replay
         * ([io.github.wandersalamander.vocabtrainer.data.scoring.StatsReplay]) each
         * compute this independently and MUST agree — a word rebuilt from the log
         * (backup restore / cache rebuild) has to land on the same schedule the
         * live path chose. [String.hashCode] is stable on the JVM, so the same
         * pair always maps to the same gap; different words scatter uniformly,
         * which is exactly the cohort-smoothing we want.
         */
        fun fastTrackInitialRefreshIntervalDays(hash: String, skill: Skill): Float {
            val seed = "$hash ${skill.name}".hashCode()
            // Clear the sign bit → [0, 2^31); divide by 2^31 → [0, 1).
            val unit = (seed and 0x7fffffff) / 2_147_483_648f
            return FAST_TRACK_REFRESH_MIN_DAYS +
                unit * (FAST_TRACK_REFRESH_MAX_DAYS - FAST_TRACK_REFRESH_MIN_DAYS)
        }
    }
}
