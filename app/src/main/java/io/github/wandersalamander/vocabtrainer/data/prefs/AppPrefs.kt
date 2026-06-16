package io.github.wandersalamander.vocabtrainer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MAX
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MIN

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
    // First-ever-launch setup gate. False until the user completes the
    // onboarding screen (language + active-pool choice); MainActivity bounces to
    // OnboardingActivity while false. Global (not per-language): the first run
    // configures the very first language, after which the app is "set up".
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_TUTORIAL_SHOWN = "tutorial_shown"
    const val KEY_SPOT_CHECK_TUTORIAL_SHOWN = "spot_check_tutorial_shown"
    const val KEY_WORDLIST_SORT_INDEX = "wordlist_sort_index"
    const val KEY_WORDLIST_SELECTED_SKILL = "wordlist_selected_skill"
    const val KEY_SENTENCE_SOURCE = "sentence_source"

    // Legacy GLOBAL auto-add threshold. Kept only as the one-time migration
    // source: when a language has no per-language value yet, [autoAddThreshold]
    // falls back to this so anyone who tuned the old single setting keeps it on
    // every language. New writes go to the per-language key below.
    const val KEY_AUTO_ADD_THRESHOLD = "auto_add_threshold"

    // Per-language auto-add threshold (the "minimum words in reading" floor that
    // pulls in a fresh word). Per-language because how aggressively to introduce
    // new words is a per-language choice — a near-fluent language wants a bigger
    // pool than one just started. Real key is the prefix plus the language code,
    // mirroring the calibration keys below.
    const val KEY_AUTO_ADD_THRESHOLD_PREFIX = "auto_add_threshold_"

    // Calibration keys are per-language: calibration runs once per language and
    // its single output (the frontier tier F) biases that language's new-word
    // picker only. The real key is the prefix plus the language code.
    const val KEY_CALIBRATION_DONE_PREFIX = "calibration_done_"
    const val KEY_CALIBRATION_FRONTIER_PREFIX = "calibration_frontier_"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Whether first-run setup (the onboarding screen) has been completed. */
    fun isOnboardingDone(context: Context): Boolean =
        get(context).getBoolean(KEY_ONBOARDING_DONE, false)

    /**
     * One-time migration for installs that predate the onboarding screen. The
     * onboarding flag is new, so it defaults to false for everyone — but an
     * existing user must NOT be bounced back through setup (which would also
     * overwrite their language and active-pool). If the flag is unset yet the
     * prefs already carry any marker only a past launch could have written
     * (tutorial seen, a chosen language, a tuned/legacy threshold, or a
     * completed calibration), seed the flag as done. Genuinely fresh installs
     * have none of these and stay false, so they see onboarding. Idempotent and
     * cheap (one SharedPreferences read); run once from Application.onCreate.
     */
    fun migrateOnboardingFlagIfNeeded(context: Context) {
        val prefs = get(context)
        if (prefs.contains(KEY_ONBOARDING_DONE)) return
        val establishedInstall = prefs.all.keys.any { key ->
            key == KEY_TUTORIAL_SHOWN ||
                key == KEY_APP_LANGUAGE ||
                key == KEY_AUTO_ADD_THRESHOLD ||
                key.startsWith(KEY_CALIBRATION_DONE_PREFIX) ||
                key.startsWith(KEY_AUTO_ADD_THRESHOLD_PREFIX)
        }
        if (establishedInstall) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        }
    }

    /**
     * Persist the first-run choices in one commit: the chosen language, its
     * active-pool threshold, and the done flag. Writing them together means a
     * launch can never observe a half-applied setup (e.g. done=true but no
     * language). The threshold is coerced into the valid range.
     */
    fun completeOnboarding(context: Context, languageCode: String, threshold: Int) {
        val coerced = threshold.coerceIn(AUTO_INTRODUCE_THRESHOLD_MIN, AUTO_INTRODUCE_THRESHOLD_MAX)
        get(context).edit()
            .putString(KEY_APP_LANGUAGE, languageCode)
            .putInt(autoAddThresholdKey(languageCode), coerced)
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()
    }

    fun autoAddThresholdKey(languageCode: String): String =
        "$KEY_AUTO_ADD_THRESHOLD_PREFIX$languageCode"

    /**
     * Whether the user has explicitly chosen an active-pool size for
     * [languageCode] (vs. silently inheriting the legacy global / default).
     * Drives the once-per-language pool prompt: a fresh language asks for its
     * size, but a language already configured (including the first one, set on
     * the onboarding screen) skips straight to the level check.
     */
    fun hasAutoAddThreshold(context: Context, languageCode: String): Boolean =
        get(context).contains(autoAddThresholdKey(languageCode))

    /**
     * The auto-add threshold for [languageCode], coerced into the valid range.
     * Reads the per-language key, falling back (one-time inherit) to the legacy
     * global value and finally the built-in default — so existing users who'd
     * tuned the single global setting see no change, while each language can now
     * be set independently.
     */
    fun autoAddThreshold(context: Context, languageCode: String): Int {
        val prefs = get(context)
        val legacyGlobal = prefs.getInt(KEY_AUTO_ADD_THRESHOLD, AUTO_INTRODUCE_THRESHOLD)
        return prefs.getInt(autoAddThresholdKey(languageCode), legacyGlobal)
            .coerceIn(AUTO_INTRODUCE_THRESHOLD_MIN, AUTO_INTRODUCE_THRESHOLD_MAX)
    }

    /** Persist the per-language auto-add threshold, coerced into the valid range. */
    fun setAutoAddThreshold(context: Context, languageCode: String, value: Int) {
        val coerced = value.coerceIn(AUTO_INTRODUCE_THRESHOLD_MIN, AUTO_INTRODUCE_THRESHOLD_MAX)
        get(context).edit().putInt(autoAddThresholdKey(languageCode), coerced).apply()
    }

    /**
     * Forget the chosen active-pool size for [languageCode], so [hasAutoAddThreshold]
     * reports false again and the once-per-language pool prompt re-appears on the
     * next visit. Called from the language-wipe path: a wipe makes the language
     * fresh, and the pool choice — like the calibration frontier — was tied to
     * that now-deleted progress, so it should be re-asked rather than carried over.
     */
    fun clearAutoAddThreshold(context: Context, languageCode: String) {
        get(context).edit().remove(autoAddThresholdKey(languageCode)).apply()
    }

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
