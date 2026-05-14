package com.example.myapplication.dictionary

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the example sentence shown during a quiz round comes from.
 *
 * [EASY] uses the short CSV sentence (the second of the two per-word examples),
 * [HARD] uses the long CSV sentence, and [LLM] asks the on-device language
 * model for a fresh sentence at runtime (falling back to [EASY] if the LLM
 * isn't ready).
 *
 * The default for new installs is [EASY]: a fresh user gets the gentlest
 * material until they explicitly opt into something harder.
 */
enum class SentenceSource(val storageValue: String) {
    EASY("easy"),
    HARD("hard"),
    LLM("llm"),
    ;

    companion object {
        val DEFAULT = EASY
        const val PREF_KEY = "sentence_source"

        fun fromStorage(stored: String?): SentenceSource =
            values().firstOrNull { it.storageValue == stored } ?: DEFAULT

        fun fromPrefs(prefs: SharedPreferences): SentenceSource =
            fromStorage(prefs.getString(PREF_KEY, null))

        fun fromContext(context: Context): SentenceSource =
            fromPrefs(context.getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE))
    }
}
