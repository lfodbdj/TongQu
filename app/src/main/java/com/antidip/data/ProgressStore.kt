package com.antidip.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.antidip.Constants
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
     *
     * V2.3/V2.5（用户要求 2026-11-24/28）：传入 [question] 时启用按题台账——
     *  - 答错：按本题累计答错次数递增扣减（-1/-2/-5/-10），并累加台账；
     *  - 答对：若本题已拿满 [ProgressRules.MAX_CORRECT_BONUS_PER_QUESTION] 次加分
     *    （封顶）则只得 0 分；否则按题型加分，加分时同时累计
     *    「年级×科目答对数」（科目徽章用；封顶后的 +0 不计，防刷）。
     * question 为空时退回旧的固定 -1 / 按题型加分行为（兼容无题面场景）。
     */
    suspend fun applyAnswer(
        context: Context,
        isCorrect: Boolean,
        isFill: Boolean,
        question: com.antidip.db.QuestionEntity? = null
    ): Pair<ProgressRules.State, List<String>> {
        val fingerprint = question?.let {
            ProgressRules.questionFingerprint(it.content, it.grade)
        }
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

            var wrongCount = 0
            var bonusTally = 0
            if (fingerprint != null) {
                wrongCount = parseWrongCounts(prefs[WRONG_COUNTS_KEY].orEmpty())[fingerprint] ?: 0
                bonusTally = readTally(prefs, fingerprint)
            }
            val bonusCapped = bonusTally >= ProgressRules.MAX_CORRECT_BONUS_PER_QUESTION

            val st = ProgressRules.applyAnswer(
                current, isCorrect, isFill, firstCorrect,
                wrongCount = wrongCount, bonusCapped = bonusCapped, fingerprint = fingerprint
            )

            // 落台账：答错累加累计次数；答对且本次计入加分 → 累加加分次数。
            // V2.5.1：科目/年级完成进度改由 questions.answered_correct 列统计
            // （每题曾答对过去重），此处不再按事件累计。
            if (fingerprint != null) {
                if (!isCorrect) {
                    val counts = parseWrongCounts(prefs[WRONG_COUNTS_KEY].orEmpty()).toMutableMap()
                    counts[fingerprint] = wrongCount + 1
                    prefs[WRONG_COUNTS_KEY] = encodeWrongCounts(counts)
                } else if (!bonusCapped) {
                    putTally(prefs, fingerprint, bonusTally + 1)
                }
            }

            prefs[POINTS_KEY] = st.points
            prefs[STREAK_KEY] = st.streak
            prefs[BEST_STREAK_KEY] = st.bestStreak
            prefs[MEDALS_KEY] = st.medals
            newMedals = st.medals - current.medals
            newState = st
        }
        return newState to newMedals.toList()
    }

    /**
     * 按题"已获答对加分次数"的持久化（行格式 "fp|count"）。用于判断是否达到
     * [ProgressRules.MAX_CORRECT_BONUS_PER_QUESTION] 次加分上限（防刷分）。
     */
    private val BONUS_TALLY_KEY = stringPreferencesKey("question_bonus_tally")

    private fun readTally(prefs: Preferences, fingerprint: String): Int {
        val raw = prefs[BONUS_TALLY_KEY].orEmpty()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val idx = line.lastIndexOf('|')
            if (idx > 0 && line.substring(0, idx) == fingerprint) {
                return line.substring(idx + 1).toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun putTally(prefs: MutablePreferences, fingerprint: String, count: Int) {
        val lines = prefs[BONUS_TALLY_KEY].orEmpty()
            .split("\n").filter { it.isNotBlank() }.toMutableList()
        val idx = lines.indexOfFirst { it.substringBeforeLast('|') == fingerprint }
        val entry = "$fingerprint|$count"
        if (idx >= 0) lines[idx] = entry else lines.add(entry)
        prefs[BONUS_TALLY_KEY] = lines.joinToString("\n")
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

    // ---------------- V2.3 按题答题台账（2026-11-24） ----------------

    private val WRONG_COUNTS_KEY = stringPreferencesKey(Constants.KEY_QUESTION_WRONG_COUNTS)

    /** 读取某题累计答错次数（0 = 从未答错）。 */
    suspend fun getWrongCount(context: Context, fingerprint: String): Int {
        val raw = context.progressDataStore.data.first()[WRONG_COUNTS_KEY].orEmpty()
        return parseWrongCounts(raw)[fingerprint] ?: 0
    }

    /** 递增某题累计答错次数并返回递增后的值。 */
    suspend fun incrementWrongCount(context: Context, fingerprint: String): Int {
        var newest = 1
        context.progressDataStore.edit { prefs ->
            val map = parseWrongCounts(prefs[WRONG_COUNTS_KEY].orEmpty()).toMutableMap()
            val n = (map[fingerprint] ?: 0) + 1
            map[fingerprint] = n
            newest = n
            prefs[WRONG_COUNTS_KEY] = encodeWrongCounts(map)
        }
        return newest
    }

    private fun parseWrongCounts(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Int>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val idx = line.lastIndexOf('|')
            if (idx <= 0) continue
            val fp = line.substring(0, idx)
            val n = line.substring(idx + 1).toIntOrNull() ?: continue
            out[fp] = n
        }
        return out
    }

    private fun encodeWrongCounts(map: Map<String, Int>): String =
        map.entries.joinToString("\n") { "${it.key}|${it.value}" }

    // ---------------- V2.3 主动练习会话态（2026-11-24） ----------------

    private val PRACTICE_PENDING_ID_KEY = longPreferencesKey(Constants.KEY_PRACTICE_PENDING_ID)
    private val PRACTICE_FOCUS_ID_KEY = longPreferencesKey(Constants.KEY_PRACTICE_FOCUS_ID)
    private val PRACTICE_ANSWERED_KEY = intPreferencesKey(Constants.KEY_PRACTICE_ANSWERED)
    private val PRACTICE_CORRECT_KEY = intPreferencesKey(Constants.KEY_PRACTICE_CORRECT)
    private val PRACTICE_TOTAL_KEY = intPreferencesKey(Constants.KEY_PRACTICE_TOTAL)
    private val PRACTICE_IS_FOCUS_KEY = booleanPreferencesKey(Constants.KEY_PRACTICE_IS_FOCUS)

    private const val PRACTICE_DEFAULT_TOTAL = 10

    /** 退出练习时保存"待作答的当前题"id（0 = 无待作答题）。 */
    suspend fun savePracticePending(context: Context, questionId: Long) {
        context.progressDataStore.edit { prefs ->
            prefs[PRACTICE_PENDING_ID_KEY] = questionId
        }
    }

    /** 读取上次退出时未作答的当前题 id（0 = 无）。 */
    suspend fun getPracticePending(context: Context): Long =
        context.progressDataStore.data.first()[PRACTICE_PENDING_ID_KEY] ?: 0L

    /** 清除待作答题（已作答并推进后调用）。 */
    suspend fun clearPracticePending(context: Context) {
        context.progressDataStore.edit { prefs -> prefs[PRACTICE_PENDING_ID_KEY] = 0L }
    }

    /** 错题重做模式的焦点题 id（0 = 非重做模式）。 */
    suspend fun savePracticeFocus(context: Context, questionId: Long) {
        context.progressDataStore.edit { prefs -> prefs[PRACTICE_FOCUS_ID_KEY] = questionId }
    }

    suspend fun getPracticeFocus(context: Context): Long =
        context.progressDataStore.data.first()[PRACTICE_FOCUS_ID_KEY] ?: 0L

    suspend fun clearPracticeFocus(context: Context) {
        context.progressDataStore.edit { prefs -> prefs[PRACTICE_FOCUS_ID_KEY] = 0L }
    }

    /** 主动练习会话进度（已作答题数 / 答对数 / 总题数 / 是否重做模式）。 */
    suspend fun savePracticeSession(
        context: Context,
        answered: Int,
        correct: Int,
        total: Int,
        isFocus: Boolean
    ) {
        context.progressDataStore.edit { prefs ->
            prefs[PRACTICE_ANSWERED_KEY] = answered
            prefs[PRACTICE_CORRECT_KEY] = correct
            prefs[PRACTICE_TOTAL_KEY] = total
            prefs[PRACTICE_IS_FOCUS_KEY] = isFocus
        }
    }

    suspend fun getPracticeAnswered(context: Context): Int =
        context.progressDataStore.data.first()[PRACTICE_ANSWERED_KEY] ?: 0

    suspend fun getPracticeCorrect(context: Context): Int =
        context.progressDataStore.data.first()[PRACTICE_CORRECT_KEY] ?: 0

    suspend fun getPracticeTotal(context: Context): Int =
        context.progressDataStore.data.first()[PRACTICE_TOTAL_KEY] ?: PRACTICE_DEFAULT_TOTAL

    suspend fun getPracticeIsFocus(context: Context): Boolean =
        context.progressDataStore.data.first()[PRACTICE_IS_FOCUS_KEY] ?: false

    /** 清除全部练习会话态（会话结束/推进到下一题时调用）。 */
    suspend fun clearPracticeSession(context: Context) {
        context.progressDataStore.edit { prefs ->
            prefs[PRACTICE_PENDING_ID_KEY] = 0L
            prefs[PRACTICE_ANSWERED_KEY] = 0
            prefs[PRACTICE_CORRECT_KEY] = 0
            prefs[PRACTICE_TOTAL_KEY] = PRACTICE_DEFAULT_TOTAL
            prefs[PRACTICE_IS_FOCUS_KEY] = false
        }
    }

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
