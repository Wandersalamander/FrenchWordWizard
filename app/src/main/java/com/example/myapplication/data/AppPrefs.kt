package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the app's SharedPreferences store. Owns the
 * store name plus the cross-cutting key constants so we don't have a literal
 * `"vocabulary_preferences"` (and the magic key strings around it) scattered
 * through activities, services, and receivers.
 *
 * Per-vocabulary keys (the `<md5-hash><suffix>` family written by
 * [com.example.myapplication.dictionary.Vocab]) and streak keys
 * (`streak_*`) live next to the code that owns them — this helper only
 * centralizes the names that several modules share.
 */
object AppPrefs {
    const val NAME = "vocabulary_preferences"

    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_TUTORIAL_SHOWN = "tutorial_shown"
    const val KEY_WORDLIST_SORT_INDEX = "wordlist_sort_index"
    const val KEY_WORDLIST_SELECTED_SKILL = "wordlist_selected_skill"
    const val KEY_SENTENCE_SOURCE = "sentence_source"

    private const val PROGRESS_WIPED_AT_PREFIX = "progress_wiped_at_"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Per-language "user wiped progress at" timestamp key. */
    fun progressWipedAtKey(languageCode: String): String =
        "$PROGRESS_WIPED_AT_PREFIX$languageCode"
}
