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

    /**
     * V2.2 backup/restore: writes a FULL state snapshot (device switch).
     * Deliberately separate from applyAnswer — restore is a parent action.
     */
    suspend fun restoreState(
        context: Context,
        points: Int,
        streak: Int,
        bestStreak: Int,
        medals: Set<String>
    ) {
        context.progressDataStore.edit { prefs ->
            prefs[POINTS_KEY] = points
            prefs[STREAK_KEY] = streak
            prefs[BEST_STREAK_KEY] = bestStreak
            prefs[MEDALS_KEY] = medals
        }
    }

    /** V2.2 backup/restore: replaces the whole wrong book with [book]. */
    suspend fun replaceWrongBook(context: Context, book: List<ProgressRules.WrongEntry>) {
        context.progressDataStore.edit { prefs ->
            prefs[WRONG_BOOK_KEY] = ProgressRules.encodeWrongBook(book)
        }
    }

    // ---------------- 积分扣减（2026-11-21：物品兑换 / 手动扣减） ----------------

    private val DEDUCTION_LOG_KEY = stringPreferencesKey("deduction_log")

    /**
     * 家长扣减积分。积分不为负（实际扣减= min(amount, 当前)）；
     * 连对、最高连对与已得勋章不受影响。返回 (扣减前积分, 实际扣减分)。
     * 每次扣减写入一条记录（ts|实际扣减|用途|原因），最多保留 50 条。
     */
    suspend fun deductPoints(
        context: Context,
        amount: Int,
        type: String,
        reason: String
    ): Pair<Int, Int> {
        val safeReason = reason.replace("\n", " ").replace("|", "，").trim()
        var before = 0
        var actual = 0
        context.progressDataStore.edit { prefs ->
            before = prefs[POINTS_KEY] ?: 0
            actual = minOf(amount, before).coerceAtLeast(0)
            prefs[POINTS_KEY] = before - actual
            val lines = prefs[DEDUCTION_LOG_KEY].orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }
                .toMutableList()
            lines.add("${System.currentTimeMillis()}|$actual|$type|$safeReason")
            while (lines.size > 50) lines.removeAt(0)
            prefs[DEDUCTION_LOG_KEY] = lines.joinToString("\n")
        }
        return before to actual
    }

    /** 最近扣减记录（新→旧），行格式 ts|实际扣减|用途|原因。 */
    suspend fun getDeductionLog(context: Context): List<String> =
        context.progressDataStore.data.first()[DEDUCTION_LOG_KEY].orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .asReversed()
}
