package io.github.wandersalamander.vocabtrainer.quiz.picker

import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.dictionary.Vocab
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the domain-partition invariant the [PickerScheduler] relies on to avoid
 * *domain-level* overlap (a pair served by two independent picker slots): the
 * active pool (sortValue > 0) and the post-mastery refresh pool
 * (failureProbabilityMasteredWords > 0) must be mutually exclusive for any
 * (vocab, skill) state. It holds because both gate on the mastery threshold
 * from opposite sides — but it's load-bearing for the scheduler, so we pin it.
 *
 * (The uniform-floor picker deliberately shares the ACTIVE pool — that's an
 * alternate selection on its own slot, not a third pool, so it isn't overlap.)
 */
class PickerOverlapTest {

    private val dayMs = 86_400_000L

    private fun newVocab() = Vocab(
        foreign = "w", english = "w", importance = 1, hash = "w",
        exampleHard = "h", exampleEasy = "e", persistence = null,
    )

    @Test fun activeAndRefreshPoolsAreDisjoint() {
        val now = System.currentTimeMillis()
        for (nViewed in intArrayOf(0, 1, 2, 5, 20, 100)) {
            var nFailedX10 = 0
            while (nFailedX10 <= nViewed * 10) {
                for (viewMs in longArrayOf(1_000L, 3_000L, 8_000L)) {
                    val v = newVocab()
                    val s = v.stats(Skill.READ)
                    s.nTimesViewed = nViewed
                    s.nTimesFailed = nFailedX10 / 10f
                    s.viewTimeMilli = viewMs
                    s.viewTimeMilli_prev = viewMs
                    // Last seen long ago, so the refresh pool would gladly take
                    // this pair IF it were mastered — making the test meaningful
                    // rather than trivially passing on the "not overdue" branch.
                    s.lastDisplayed = now - 60L * dayMs
                    s.lastTimeFailed = 0L

                    val inActive = v.sortValue(Skill.READ) > 0.0
                    val inRefresh = v.failureProbabilityMasteredWords(Skill.READ, now) > 0.0
                    assertFalse(
                        "pair landed in BOTH pools — nViewed=$nViewed " +
                            "nFailed=${s.nTimesFailed} viewMs=$viewMs fpb=${s.failureProbability()}",
                        inActive && inRefresh,
                    )
                }
                nFailedX10 += 3
            }
        }
    }
}
