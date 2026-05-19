package com.example.myapplication.quiz

import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.Skill
import com.example.myapplication.dictionary.Vocab

private const val RECENT_WORDS_CAPACITY = 20
private const val RECENT_FOREIGN_AVOID_COUNT = 4
private const val WRONG_VOCAB_PICK_ATTEMPTS = 50

// Every Nth displayed round (counting committed words, not picker retries) is
// granted to a due mastered (word, skill) pair, so post-mastery retention gets
// a guaranteed slot regardless of how crowded the active pool is. Skipped when
// no mastered pair has gone stale.
private const val MASTERED_REFRESH_INTERVAL = 20

// Backup safety floor on the active pool: every Nth round is granted to a
// uniform random pick from active pairs, ignoring sortValue weights. Catches
// near-mastered (word, skill) pairs whose weighted-picker probability has
// shrunk too far to ever finish them. Independent of MASTERED_REFRESH_INTERVAL;
// on the rare round both fire, mastered wins and uniform retries next round.
private const val UNIFORM_ACTIVE_INTERVAL = 20

/**
 * Picks the next (vocab, skill) pair for the quiz. Owns:
 *  - the recent-words deque so foreign words don't ping-pong back-to-back,
 *  - the two cadence counters that gate the periodic mastered-refresh and
 *    uniform-active backup slots,
 *  - the skill-filter logic when listening mode is off.
 *
 * QuizController ticks the picker once per displayed round (via [pickNext])
 * and calls [rememberWord] from saveCurrentVocab so the deque records the
 * just-shown word regardless of whether the round counted.
 */
internal class QuizPicker(
    private val dictionary: MyDictionary,
) {
    private val recentWords = ArrayDeque<String>()

    // Ticks once per active-pool pick; when it hits MASTERED_REFRESH_INTERVAL
    // the next pick is forced through the mastered-refresh picker. Reset only
    // when the slot is actually granted — an empty pool leaves the counter
    // past threshold so the next round retries.
    private var picksSinceMasteredRefresh: Int = 0

    // Independent cadence counter for the uniform-active backup slot.
    private var picksSinceUniformActive: Int = 0

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
    ): Pair<Vocab, Skill> {
        // Tick the cadence once per displayed round (NOT once per pickOne
        // call) so the anti-repeat retries below don't race the counter past
        // threshold faster than every Nth round.
        val allowMasteredRefresh = !newCandidates && tickMasteredCadence()
        val allowUniformActive = !newCandidates && tickUniformActiveCadence()
        var pick = pickOne(newCandidates, listeningEnabled, allowMasteredRefresh, allowUniformActive)
        // Avoid the last few foreign words so a heavy-weight pair can't
        // ping-pong (X-Y-X-Y) within a small active pool. The just-displayed
        // word is in recentWords (added by rememberWord on commit), but include
        // avoidCurrentForeign defensively for the first-ever pick when the
        // deque is empty.
        val avoid = recentWords.toList()
            .takeLast(RECENT_FOREIGN_AVOID_COUNT)
            .toSet() + setOfNotNull(avoidCurrentForeign)
        var attempts = 0
        while (pick.first.foreign in avoid && attempts < WRONG_VOCAB_PICK_ATTEMPTS) {
            pick = pickOne(newCandidates, listeningEnabled, allowMasteredRefresh, allowUniformActive)
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

    private fun pickOne(
        newCandidates: Boolean,
        listeningEnabled: Boolean,
        allowMasteredRefresh: Boolean,
        allowUniformActive: Boolean,
    ): Pair<Vocab, Skill> {
        // When listening is off the LISTEN skill is excluded from the pool —
        // getInactiveVocab always returns READ so it doesn't need the filter.
        val skillFilter: (Skill) -> Boolean =
            if (listeningEnabled) ({ true }) else ({ it != Skill.LISTEN })
        if (newCandidates) return dictionary.getInactiveVocab()
        if (allowMasteredRefresh) {
            val mastered = dictionary.pickMasteredVocabToRefresh(skillFilter)
            if (mastered != null) {
                picksSinceMasteredRefresh = 0
                return mastered
            }
            // No mastered pair is due — leave the counter past threshold so
            // the next round tries again instead of waiting a full cycle.
        }
        if (allowUniformActive) {
            val uniform = dictionary.pickUniformActiveVocab(skillFilter)
            if (uniform != null) {
                picksSinceUniformActive = 0
                return uniform
            }
            // Empty active pool — leave the counter past threshold so the next
            // round retries once the pool has been seeded.
        }
        return dictionary.getActiveVocabWeighted(skillFilter)
    }

    private fun tickMasteredCadence(): Boolean {
        picksSinceMasteredRefresh++
        return picksSinceMasteredRefresh >= MASTERED_REFRESH_INTERVAL
    }

    private fun tickUniformActiveCadence(): Boolean {
        picksSinceUniformActive++
        return picksSinceUniformActive >= UNIFORM_ACTIVE_INTERVAL
    }
}
