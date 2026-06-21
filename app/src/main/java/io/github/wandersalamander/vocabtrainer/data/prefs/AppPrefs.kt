package io.github.wandersalamander.vocabtrainer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MAX
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MIN
import io.github.wandersalamander.vocabtrainer.dictionary.Language

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

    // Just-in-time coach marks (see CoachMarks). Instead of the old nine-target
    // tutorial firehose the instant the first word loaded, a control introduces
    // itself the first time it becomes relevant. The calibration mark is the one
    // that isn't a drilling control: it gives the level-check a persistent
    // "what to press" anchor in place of a toast that vanished. Write-once
    // booleans, global (the controls/test are the same on every language) — like
    // KEY_TUTORIAL_SHOWN, which (the full tour, run from the menu) suppresses all
    // of these.
    const val KEY_COACH_CALIBRATION_SHOWN = "coach_calibration_shown"
    const val KEY_COACH_CORE_SHOWN = "coach_core_shown"
    const val KEY_COACH_HINT_SHOWN = "coach_hint_shown"
    const val KEY_COACH_FLAG_SHOWN = "coach_flag_shown"
    const val KEY_COACH_POOL_SHOWN = "coach_pool_shown"

    // Cumulative in-app foreground time (ms) and a once-only flag for the
    // streak-reminder opt-in. Rather than a cold-start notification prompt (which
    // gets reflexively denied), we wait until the user has actually used the app
    // for a while, then offer reminders in context — see MainActivity.
    const val KEY_TOTAL_USAGE_MS = "total_usage_ms"
    const val KEY_REMINDER_PROMPT_DONE = "reminder_prompt_done"

    // Whether the passive "word in the notification shade" foreground service is
    // on. Opt-in: default false, toggled from Settings → Word notification.
    const val KEY_VOCAB_SERVICE_ENABLED = "vocab_service_enabled"
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

    // Global "Speaking tier paused until" wall-clock deadline (ms) — the single
    // flag for whether Speaking is on. The ⋮ menu toggle and the in-quiz "Can't
    // speak" control both write it: a finite future = a timed pause, the
    // [SPEAKING_OFF_UNTIL] sentinel = toggled off, 0 / in the past = on.
    const val KEY_SPEAKING_PAUSED_UNTIL = "speaking_paused_until"

    // "Toggled off indefinitely" sentinel for [setSpeakingOn]: far enough out
    // that isSpeakingPaused stays true until the user turns Speaking back on.
    private const val SPEAKING_OFF_UNTIL = Long.MAX_VALUE

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * The active language's code, or [Language.DEFAULT]'s code when none has
     * been chosen yet. The per-language progress trackers (mastery, lifetime
     * score, skill-finished counts) all scope their state by this, so the lookup
     * lives here next to [KEY_APP_LANGUAGE] instead of being re-derived in each.
     */
    fun activeLanguageCode(context: Context): String =
        get(context).getString(KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    /** Whether first-run setup (the onboarding screen) has been completed. */
    fun isOnboardingDone(context: Context): Boolean =
        get(context).getBoolean(KEY_ONBOARDING_DONE, false)

    /** Cumulative foreground time across all sessions, in milliseconds. */
    fun totalUsageMs(context: Context): Long =
        get(context).getLong(KEY_TOTAL_USAGE_MS, 0L)

    /** Add [deltaMs] of foreground time to the running total (ignores non-positive). */
    fun addUsageMs(context: Context, deltaMs: Long) {
        if (deltaMs <= 0L) return
        get(context).edit().putLong(KEY_TOTAL_USAGE_MS, totalUsageMs(context) + deltaMs).apply()
    }

    /** Whether the one-time streak-reminder opt-in prompt has already been shown. */
    fun isReminderPromptDone(context: Context): Boolean =
        get(context).getBoolean(KEY_REMINDER_PROMPT_DONE, false)

    fun setReminderPromptDone(context: Context) {
        get(context).edit().putBoolean(KEY_REMINDER_PROMPT_DONE, true).apply()
    }

    /** Whether the passive word-in-the-notification glance service is on (default off). */
    fun isVocabServiceEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_VOCAB_SERVICE_ENABLED, false)

    fun setVocabServiceEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_VOCAB_SERVICE_ENABLED, enabled).apply()
    }

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
     * Persist the first-run choices in one commit: the chosen language, the done
     * flag, and the first language's active-pool size (seeded to the built-in
     * default). Writing them together means a launch can never observe a
     * half-applied setup (e.g. done=true but no language).
     *
     * Seeding the pool size here is what lets the first language skip the
     * post-calibration pool prompt ([MainActivity.onAssessmentComplete] gates on
     * [hasAutoAddThreshold]): a brand-new user has no basis to answer "how many
     * words in rotation?" before they've drilled a single word, so we pick a
     * sensible default and leave it editable later (the ACTIVE WORDS card and
     * Settings → Active pool). Switching to a *further* language still prompts —
     * by then the user knows what the number means. The default matches
     * [autoAddThreshold]'s fallback, so seeding only fixes the value a fresh
     * install would have computed anyway.
     */
    fun completeOnboarding(context: Context, languageCode: String) {
        get(context).edit()
            .putString(KEY_APP_LANGUAGE, languageCode)
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .putInt(autoAddThresholdKey(languageCode), AUTO_INTRODUCE_THRESHOLD)
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

    /**
     * Wall-clock deadline (ms) until which Speaking is paused, or 0 if never set.
     * Global. A timed "Can't speak" pause expires on its own; the toggle-off case
     * stores [SPEAKING_OFF_UNTIL].
     */
    fun speakingPausedUntilMs(context: Context): Long =
        get(context).getLong(KEY_SPEAKING_PAUSED_UNTIL, 0L)

    /** True while Speaking is paused (a timed "Can't speak" pause or toggled off). */
    fun isSpeakingPaused(context: Context): Boolean =
        speakingPausedUntilMs(context) > System.currentTimeMillis()

    /** Convenience inverse of [isSpeakingPaused] for the on/off menu toggle. */
    fun isSpeakingOn(context: Context): Boolean = !isSpeakingPaused(context)

    /** Pause Speaking until [untilMs] (wall clock). Used by the "Can't speak" dialog. */
    fun setSpeakingPausedUntil(context: Context, untilMs: Long) {
        get(context).edit().putLong(KEY_SPEAKING_PAUSED_UNTIL, untilMs).apply()
    }

    /**
     * Flip Speaking on/off from the ⋮ menu toggle — the same global flag the
     * timed "Can't speak" pause writes. On clears any pause; off parks it far in
     * the future until the user turns it back on.
     */
    fun setSpeakingOn(context: Context, on: Boolean) {
        setSpeakingPausedUntil(context, if (on) 0L else SPEAKING_OFF_UNTIL)
    }
}
