package com.antidip.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.antidip.ProgressRules
import kotlinx.coroutines.flow.first

/**
 * V2.0 growth persistence (requirement 2026-11-10): points, consecutive
 * correct streak, earned medals and the wrong-question book. All values must
 * survive process death (they are the child's visible "成长" record), so they
 * live in DataStore like [QuizStateStore].
 *
 * The state machine lives in [ProgressRules] (pure JVM, unit-tested); this
 * object only reads/writes the persisted snapshot.
 */
private val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quiz_progress"
)

private val POINTS_KEY = intPreferencesKey("points")
private val STREAK_KEY = intPreferencesKey("streak")
private val BEST_STREAK_KEY = intPreferencesKey("best_streak")
private val MEDALS_KEY = stringSetPreferencesKey("medals")
private val WRONG_BOOK_KEY = stringPreferencesKey("wrong_book")

object ProgressStore {

    suspend fun getState(context: Context): ProgressRules.State {
        val prefs = context.progressDataStore.data.first()
        return ProgressRules.State(
            points = prefs[POINTS_KEY] ?: 0,
            streak = prefs[STREAK_KEY] ?: 0,
            bestStreak = prefs[BEST_STREAK_KEY] ?: 0,
            medals = prefs[MEDALS_KEY] ?: emptySet()
        )
    }

    /**
     * Applies one answer atomically and persists the new state. Returns the
     * newly earned medal ids so the caller can celebrate them, and the fresh
     * state for UI refresh.
     */
    suspend fun applyAnswer(
        context: Context,
        isCorrect: Boolean,
        isFill: Boolean
    ): Pair<ProgressRules.State, List<String>> {
        var newMedals: Set<String> = emptySet()
        var newState = ProgressRules.initial()
        context.progressDataStore.edit { prefs ->
            val current = ProgressRules.State(
                points = prefs[POINTS_KEY] ?: 0,
                streak = prefs[STREAK_KEY] ?: 0,
                bestStreak = prefs[BEST_STREAK_KEY] ?: 0,
                medals = prefs[MEDALS_KEY] ?: emptySet()
            )
            // first_quiz = "has ever answered correctly": awarded once, keyed
            // off the medal set itself (not points, which can return to 0).
            val firstCorrect = isCorrect && "first_quiz" !in current.medals
            val st = ProgressRules.applyAnswer(current, isCorrect, isFill, firstCorrect)
            prefs[POINTS_KEY] = st.points
            prefs[STREAK_KEY] = st.streak
            prefs[BEST_STREAK_KEY] = st.bestStreak
            prefs[MEDALS_KEY] = st.medals
            newMedals = st.medals - current.medals
            newState = st
        }
        return newState to newMedals.toList()
    }

    suspend fun getWrongBook(context: Context): List<ProgressRules.WrongEntry> =
        ProgressRules.decodeWrongBook(
            context.progressDataStore.data.first()[WRONG_BOOK_KEY].orEmpty()
        )

    /** Adds/reinforces a wrong-book entry (dedupe + cap inside [ProgressRules]). */
    suspend fun addWrongEntry(context: Context, entry: ProgressRules.WrongEntry) {
        context.progressDataStore.edit { prefs ->
            val book = ProgressRules.decodeWrongBook(prefs[WRONG_BOOK_KEY].orEmpty())
            prefs[WRONG_BOOK_KEY] = ProgressRules.encodeWrongBook(
                ProgressRules.addWrongEntry(book, entry)
            )
        }
    }
}
