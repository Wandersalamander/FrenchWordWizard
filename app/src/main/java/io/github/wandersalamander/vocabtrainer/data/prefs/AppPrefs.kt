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

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
