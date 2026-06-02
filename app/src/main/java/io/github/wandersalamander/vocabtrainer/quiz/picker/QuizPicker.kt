package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import kotlin.math.roundToInt

private const val RECENT_WORDS_CAPACITY = 20
private const val RECENT_FOREIGN_AVOID_COUNT = 4
private const val WRONG_VOCAB_PICK_ATTEMPTS = 50

// The mastered-refresh slot (a due mastered (word, skill) pair) fires every Nth
// displayed round, where N adapts to how far the deck has shifted from
// acquisition to maintenance: rare (every MAX rounds) when almost everything is
// still being actively drilled, frequent (every MIN rounds) once mastered pairs
// outnumber active ones by SATURATION_RATIO. Skipped when no mastered pair has
// gone stale.
private const val MASTERED_REFRESH_MIN_INTERVAL = 2
private const val MASTERED_REFRESH_MAX_INTERVAL = 10

// Mastered:active pair ratio at which the cadence reaches its every-MIN cap.
// Ratio 0 (nothing mastered) → MAX; ramps linearly down and pins at MIN once
// mastered pairs are SATURATION_RATIO× the active pool. 2.0 → "twice as many
// mastered as active ⇒ refresh every 2nd round".
private const val MASTERED_REFRESH_SATURATION_RATIO = 2.0

// Backup safety floor on the active pool: every Nth round is granted to a
// uniform random pick from active pairs, ignoring sortValue weights. Catches
// near-mastered (word, skill) pairs whose weighted-picker probability has
// shrunk too far to ever finish them. Independent of the mastered-refresh slot;
// on the rare round both fire, mastered wins (higher precedence) and uniform
// retries next round.
private const val UNIFORM_ACTIVE_INTERVAL = 20

/**
 * Maps the mastered:active pair ratio to the refresh cadence interval. The
 * interval is [maxInterval] when nothing is mastered, ramps linearly down as
 * mastered pairs catch up to the active pool, and pins at [minInterval] once
 * the ratio reaches [saturationRatio] (and for any higher ratio). Counts are
 * pair sums across skills (no divide-by-3); see
 * [MyDictionary.computeStudiedPairCounts].
 *
 * Edge cases: no mastered pairs → [maxInterval] (the refresh picker returns
 * null on those rounds anyway); mastered pairs but an empty active pool →
 * [minInterval] (pure maintenance, refresh as often as allowed).
 */
internal fun masteredRefreshInterval(
    masteredPairs: Int,
    activePairs: Int,
    minInterval: Int = MASTERED_REFRESH_MIN_INTERVAL,
    maxInterval: Int = MASTERED_REFRESH_MAX_INTERVAL,
    saturationRatio: Double = MASTERED_REFRESH_SATURATION_RATIO,
): Int {
    if (masteredPairs <= 0) return maxInterval
    if (activePairs <= 0) return minInterval
    val ratio = masteredPairs.toDouble() / activePairs
    val t = (ratio / saturationRatio).coerceIn(0.0, 1.0)
    val span = maxInterval - minInterval
    return (maxInterval - span * t).roundToInt().coerceIn(minInterval, maxInterval)
}

/**
 * Picks the next (vocab, skill) pair for the quiz. Owns:
 *  - the recent-words deque so foreign words don't ping-pong back-to-back,
 *  - the skill-filter logic when listening mode is off,
 *  - the [PickerScheduler] that composes the individual selection policies.
 *
 * The selection policies themselves live in [io.github.wandersalamander.vocabtrainer.quiz.picker]
 * — one [io.github.wandersalamander.vocabtrainer.quiz.picker.PickerStrategy] per concern.
 * Adding a behavior is a new strategy + a registration line below; precedence
 * is the registration order and the scheduler guarantees no two pickers serve
 * the same round (see its overlap notes).
 *
 * QuizController ticks the picker once per displayed round (via [pickNext]) and
 * calls [rememberWord] from saveCurrentVocab so the deque records the just-shown
 * word regardless of whether the round counted.
 */
internal class QuizPicker(
    private val dictionary: MyDictionary,
    private val autoIntroduceThresholdProvider: () -> Int = { AUTO_INTRODUCE_THRESHOLD },
    private val frontierProvider: () -> Int? = { null },
) {
    private val recentWords = ArrayDeque<String>()

    // Adaptive mastered-refresh interval, recomputed once per round in pickNext
    // from the current mastered:active pair ratio and read by RefreshPicker's
    // cadence getter. Seeded at the max so the very first round (before any pick)
    // leans rare rather than aggressive.
    private var currentRefreshInterval = MASTERED_REFRESH_MAX_INTERVAL

    // Precedence is top-to-bottom; ActiveWeightedPicker is the guaranteed
    // fallback (always eligible, never null). To roll a new behavior in, add a
    // strategy here at the right precedence with a rare EveryNth cadence and
    // tighten it over time; to remove it, delete the line.
    private val scheduler = PickerScheduler(
        strategies = listOf(
            IntroductionPicker(),
            RefreshPicker { currentRefreshInterval },
            UniformFloorPicker(UNIFORM_ACTIVE_INTERVAL),
        ),
        fallback = ActiveWeightedPicker(),
    )

    /**
     * Pick the next (vocab, skill) pair, retrying briefly to avoid repeating
     * the same foreign word back-to-back. Returns the picked pair non-null.
     *
     * [listeningEnabled] excludes LISTEN from the pool when false.
     * [avoidCurrentForeign] is added to the avoidance set for the first
     * pick — defensive against an empty deque on the very first round.
     */
    fun pickNext(
        newCandidates: Boolean,
        listeningEnabled: Boolean,
        avoidCurrentForeign: String?,
    ): PickerResult {
        // Recompute the adaptive refresh cadence for this round before the
        // scheduler reads RefreshPicker.cadence — one dictionary scan per round.
        currentRefreshInterval = dictionary.computeStudiedPairCounts().let {
            masteredRefreshInterval(it.mastered, it.active)
        }

        // When listening is off the LISTEN skill is excluded from the pool —
        // getInactiveVocab always returns READ so it doesn't need the filter.
        val skillFilter: (Skill) -> Boolean =
            if (listeningEnabled) ({ true }) else ({ it != Skill.LISTEN })
        val ctx = PickContext(
            dictionary = dictionary,
            newCandidates = newCandidates,
            skillFilter = skillFilter,
            autoIntroduceThreshold = autoIntroduceThresholdProvider(),
            frontier = frontierProvider(),
        )

        // beginRound ticks cadence ONCE; the anti-repeat re-rolls below reuse
        // the same round so they don't advance any periodic slot.
        val round = scheduler.beginRound(ctx)
        var pick = round.select()

        // Avoid the last few foreign words so a heavy-weight pair can't
        // ping-pong (X-Y-X-Y) within a small active pool. The just-displayed
        // word is in recentWords (added by rememberWord on commit), but include
        // avoidCurrentForeign defensively for the first-ever pick when the
        // deque is empty.
        val avoid = recentWords.toList()
            .takeLast(RECENT_FOREIGN_AVOID_COUNT)
            .toSet() + setOfNotNull(avoidCurrentForeign)
        var attempts = 0
        while (pick.vocab.foreign in avoid && attempts < WRONG_VOCAB_PICK_ATTEMPTS) {
            pick = round.select()
            attempts++
        }
        return pick
    }

    /**
     * Remember [foreign] as recently-shown so subsequent picks avoid it
     * back-to-back. Always called from saveCurrentVocab — the user has seen
     * the word regardless of whether the round committed.
     */
    fun rememberWord(foreign: String) {
        recentWords.remove(foreign)
        recentWords.addLast(foreign)
        while (recentWords.size > RECENT_WORDS_CAPACITY) {
            recentWords.removeFirst()
        }
    }

    /** Snapshot of the recent-words deque, for the LLM's "don't repeat" list. */
    fun recent(): List<String> = recentWords.toList()
}
