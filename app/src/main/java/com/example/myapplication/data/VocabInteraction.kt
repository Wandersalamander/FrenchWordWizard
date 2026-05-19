package com.example.myapplication.data

/**
 * One committed round's outcome for a (vocab, skill) pair. Kept as a rolling
 * window of the last [com.example.myapplication.dictionary.SkillStats.MAX_INTERACTIONS]
 * entries on [com.example.myapplication.dictionary.SkillStats] so future
 * failure-probability formulas can use ordering and per-round timing, not just
 * the aggregate counters.
 *
 * [failAmount] mirrors the increment passed to
 * [com.example.myapplication.dictionary.SkillStats.recordFailure] during the
 * round (0f = clean pass, fractional = partial penalty, 1f = full fail).
 */
data class VocabInteraction(
    val timestampMs: Long,
    val failAmount: Float,
)
