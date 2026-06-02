package io.github.wandersalamander.vocabtrainer.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Small app-local UI settings only. Used to be the single source for every
 * piece of persisted state; the user-progress data — per-vocab stats, streak,
 * daily counters, language-wipe markers — now lives in Room (see
 * [AppDatabase] and ADR-001 for the migration rationale).
 *
 * What remains here is the handful of trivial flags that don't benefit from
 * structured storage: the selected language, tutorial-seen markers, the word
 * list's sort index, and the sentence-source preference. Each is a few bytes
 * and is fine to ride Android Auto Backup as-is.
 */
object AppPrefs {
    const val NAME = "vocabulary_preferences"

    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_TUTORIAL_SHOWN = "tutorial_shown"
    const val KEY_SPOT_CHECK_TUTORIAL_SHOWN = "spot_check_tutorial_shown"
    const val KEY_WORDLIST_SORT_INDEX = "wordlist_sort_index"
    const val KEY_WORDLIST_SELECTED_SKILL = "wordlist_selected_skill"
    const val KEY_SENTENCE_SOURCE = "sentence_source"
    const val KEY_AUTO_ADD_THRESHOLD = "auto_add_threshold"

    // Calibration keys are per-language: calibration runs once per language and
    // its single output (the frontier tier F) biases that language's new-word
    // picker only. The real key is the prefix plus the language code.
    const val KEY_CALIBRATION_DONE_PREFIX = "calibration_done_"
    const val KEY_CALIBRATION_FRONTIER_PREFIX = "calibration_frontier_"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun calibrationDoneKey(languageCode: String): String =
        "$KEY_CALIBRATION_DONE_PREFIX$languageCode"

    fun calibrationFrontierKey(languageCode: String): String =
        "$KEY_CALIBRATION_FRONTIER_PREFIX$languageCode"

    /**
     * Whether calibration has already run for [languageCode]. Write-once: the
     * launch flow checks this and skips calibration when true.
     */
    fun isCalibrationDone(context: Context, languageCode: String): Boolean =
        get(context).getBoolean(calibrationDoneKey(languageCode), false)

    /**
     * The calibrated frontier tier F for [languageCode] — the highest
     * importance tier the user demonstrably knew during calibration. Returns
     * null when calibration hasn't run, or ran but found no known tier (stored
     * F < 0). Null means "no bias": today's true-beginner behavior of
     * introducing the lowest un-introduced tier first.
     */
    fun calibrationFrontier(context: Context, languageCode: String): Int? =
        get(context).getInt(calibrationFrontierKey(languageCode), -1).takeIf { it >= 0 }

    /**
     * Persist the calibration result for [languageCode] in one commit: the
     * frontier tier and the done flag together. A [frontier] < 0 records a true
     * beginner (no bias) while still marking calibration complete so it won't
     * re-run.
     */
    fun saveCalibration(context: Context, languageCode: String, frontier: Int) {
        get(context).edit()
            .putInt(calibrationFrontierKey(languageCode), frontier)
            .putBoolean(calibrationDoneKey(languageCode), true)
            .apply()
    }

    /**
     * Forget calibration for [languageCode] so it re-runs on next open. Called
     * from the language-wipe path: a wipe empties the language, and the frontier
     * was a fact derived from that now-deleted progress, so it must be recomputed
     * rather than carried over. Clears both keys, restoring the fresh-install
     * state where [isCalibrationDone] is false and [calibrationFrontier] is null.
     */
    fun clearCalibration(context: Context, languageCode: String) {
        get(context).edit()
            .remove(calibrationDoneKey(languageCode))
            .remove(calibrationFrontierKey(languageCode))
            .apply()
    }
}
