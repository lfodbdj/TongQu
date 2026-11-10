package com.antidip.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists the anti-addiction quiz state that must survive process death and
 * configuration changes (task B2):
 *  - [CONSECUTIVE_FAILURES_KEY]: number of consecutive failed rounds.
 *  - [LOCK_EXPIRY_KEY]: lock expiry timestamp in millis (0 = not locked).
 *
 * Backed by Jetpack DataStore (preferences), created once per process via the
 * `quizStateDataStore` extension property on [Context].
 */
private val Context.quizStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quiz_state"
)

private val CONSECUTIVE_FAILURES_KEY = intPreferencesKey("consecutive_failures")
private val LOCK_EXPIRY_KEY = longPreferencesKey("lock_expiry")

object QuizStateStore {

    /** Reads the persisted number of consecutive failed rounds (defaults to 0). */
    suspend fun getConsecutiveFailures(context: Context): Int {
        return context.quizStateDataStore.data.first()[CONSECUTIVE_FAILURES_KEY] ?: 0
    }

    /** Persists the number of consecutive failed rounds. */
    suspend fun setConsecutiveFailures(context: Context, value: Int) {
        context.quizStateDataStore.edit { prefs ->
            prefs[CONSECUTIVE_FAILURES_KEY] = value
        }
    }

    /** Reads the lock expiry timestamp in millis (0 means not locked). */
    suspend fun getLockExpiry(context: Context): Long {
        return context.quizStateDataStore.data.first()[LOCK_EXPIRY_KEY] ?: 0L
    }

    /** Persists the lock expiry timestamp in millis (0 to clear the lock). */
    suspend fun setLockExpiry(context: Context, expiry: Long) {
        context.quizStateDataStore.edit { prefs ->
            prefs[LOCK_EXPIRY_KEY] = expiry
        }
    }

    /**
     * Persists the consecutive-failure count AND the lock expiry in ONE
     * DataStore transaction (batch 3, task #12: "QuizStateStore 双键非原子写
     * 改单事务"). The two single-key setters above leave a window where a
     * process death between them persists a lock WITHOUT its counter (the
     * next cold start would show an unexplained lock) or a reset counter
     * WITHOUT the expired lock cleared. Every call site that writes both
     * values together must use this method; [restoreState] reads them back
     * as a pair, so they are written as a pair.
     */
    suspend fun setLockState(context: Context, failures: Int, lockExpiry: Long) {
        context.quizStateDataStore.edit { prefs ->
            prefs[CONSECUTIVE_FAILURES_KEY] = failures
            prefs[LOCK_EXPIRY_KEY] = lockExpiry
        }
    }
}
