package io.github.wandersalamander.vocabtrainer.progress

import android.content.Context
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.db.entities.LifetimeScoreEntity
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.MyDictionary
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Monotone-increasing knowledge score per language. Awarded as discrete
 * events:
 *
 *  - **+[SCORE_PER_SKILL_MASTERY]** every time a (word, skill) pair crosses
 *    below [io.github.wandersalamander.vocabtrainer.dictionary.Vocab.SKILL_FINISHED_THRESHOLD]
 *    (fires from QuizController.announceSkillFinishedIfCrossed).
 *  - **+[SCORE_PER_REFRESH_PASS]** every time a refresh-pool pick is passed
 *    cleanly (fires from QuizController.saveCurrentVocab when the round
 *    originated from the refresh pool and the penalty is zero).
 *
 * Never decreases — refresh-pool failures and re-failed mastered words have
 * their own consequence in the fpb math (they re-enter active drilling); the
 * score itself is a personal milestone counter, not a snapshot.
 *
 * Decoupled from the vocab DB on purpose: a CSV reimport, an edited word, or
 * a deleted pair leaves the score untouched.
 *
 * **Backfill:** on first read where the language's row is absent, the value
 * is seeded from [MyDictionary.computeLifetimeMasteredCount] so long-time
 * users don't start at zero after the upgrade. Retention has no history to
 * credit, so the backfill only counts mastery. Writing the row immediately
 * (even at zero) marks the backfill done so subsequent reads use the stored
 * value verbatim.
 *
 * Storage pattern mirrors [MasteryTracker]: in-memory cache + async Room
 * upsert, lazy hydration per language.
 */
object LifetimeScoreTracker {
    const val SCORE_PER_SKILL_MASTERY: Int = 1
    const val SCORE_PER_REFRESH_PASS: Int = 10

    private val cache = ConcurrentHashMap<String, Long>()

    private fun activeLanguageCode(context: Context): String =
        AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    /**
     * Current score for the active language, hydrating the cache on the first
     * call. When the row is absent — fresh install OR pre-v5 upgrade — the
     * value is backfilled from [dictionary]'s lifetime-mastered count once and
     * persisted, so the row exists from this point on and subsequent calls
     * just read the cache.
     */
    fun current(context: Context, dictionary: MyDictionary): Long {
        val lang = activeLanguageCode(context)
        cache[lang]?.let { return it }
        val stored = runBlocking {
            DataLayer.database.lifetimeScoreDao().load(lang)
        }
        if (stored != null) {
            cache[lang] = stored.score
            return stored.score
        }
        // Pre-v5 / fresh language: seed once from the current lifetime
        // mastered count (no retention credit — we have no log of it).
        val seed = dictionary.computeLifetimeMasteredCount().toLong() * SCORE_PER_SKILL_MASTERY
        cache[lang] = seed
        DataLayer.writeScope.launch {
            DataLayer.database.lifetimeScoreDao().upsert(
                LifetimeScoreEntity(
                    languageCode = lang,
                    score = seed,
                    lastModifiedMs = System.currentTimeMillis(),
                )
            )
        }
        return seed
    }

    /**
     * Bump the active language's score by [points] and return the new value.
     * Requires [current] to have been called at least once (caller is the
     * round-end commit path, which always renders the badge first via
     * [ProgressBarAnimator.refreshAll], so the cache is warm). Falls back to
     * 0 + points for the unlikely case the cache wasn't primed.
     */
    fun recordIncrement(context: Context, points: Int): Long {
        if (points <= 0) return cache[activeLanguageCode(context)] ?: 0L
        val lang = activeLanguageCode(context)
        val next = (cache[lang] ?: 0L) + points
        cache[lang] = next
        DataLayer.writeScope.launch {
            DataLayer.database.lifetimeScoreDao().upsert(
                LifetimeScoreEntity(
                    languageCode = lang,
                    score = next,
                    lastModifiedMs = System.currentTimeMillis(),
                )
            )
        }
        return next
    }

    /**
     * Drop the in-memory cache so the next read lazy-reloads from Room. Called
     * after a progress-backup import replaces the underlying rows, since this
     * tracker has no preload step and would otherwise keep serving the score it
     * cached (or backfilled) before the import.
     */
    internal fun clearCache() {
        cache.clear()
    }
}
