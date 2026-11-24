package com.antidip

/**
 * V2.0 "童趣" growth/points rules — PURE JVM, unit-testable (no Android imports).
 *
 * Requirement 2026-11-10 (version 2.0):
 *  - choice question answered correctly -> +1 point
 *  - fill-in question answered correctly -> +2 points
 *  - each wrong answer -> -1 point (total never below 0)
 *  - consecutive-correct streak resets on any wrong answer
 *  - medals are awarded automatically at point / streak milestones
 */
object ProgressRules {

    const val POINTS_CHOICE_CORRECT = 1
    const val POINTS_FILL_CORRECT = 2
    const val POINTS_WRONG = -1

    /**
     * V2.3（用户要求 2026-11-24）：同一道题累计答对加分次数上限。一道题最多
     * 通过 [MAX_CORRECT_BONUS_PER_QUESTION] 次「答对」获得加分；达到上限后再次
     * 答对只得 0 分（不加分也不扣分），防止同一题反复刷分。
     */
    const val MAX_CORRECT_BONUS_PER_QUESTION = 2

    /**
     * V2.3（用户要求 2026-11-24）：同一道题第 N 次「答错」时的递增扣减分值。
     * 第 1 次答错 -1、第 2 次 -2、第 3 次 -5、第 4 次及以后 -10。
     * [wrongCount] 为本题累计答错次数（1 起算）；按「错误次数」而非「出现次数」
     * 计（用户原话"第四次错误扣 10 分"）。累计不随答对重置。
     */
    fun escalatingWrongDeduction(wrongCount: Int): Int = when (wrongCount.coerceAtLeast(1)) {
        1 -> 1
        2 -> 2
        3 -> 5
        else -> 10
    }

    /**
     * 题目的稳定指纹（按内容 + 年级），用于跨进程/重装追踪同一道题的累计答错
     * 次数与已获加分次数。与 [QuestionBankManager] 的 mastered 指纹同义。
     */
    fun questionFingerprint(content: String, grade: Int): String = "$grade|${content.hashCode()}"

    /** Wrong-question book capacity (oldest entries dropped). */
    const val MAX_WRONG_BOOK = 200

    data class State(
        val points: Int,
        val streak: Int,
        val bestStreak: Int,
        val medals: Set<String>
    )

    data class WrongEntry(
        val content: String,
        val answer: String,
        val subject: String,
        val grade: Int,
        val wrongCount: Int,
        val lastWrongAt: Long
    )

    /** All medals with their display metadata (id, emoji, name, condition). */
    val MEDALS: List<Triple<String, String, String>> = listOf(
        Triple("first_quiz", "🌟", "初露锋芒"),
        Triple("points_10", "🥉", "小试牛刀"),
        Triple("points_50", "🥈", "崭露头角"),
        Triple("points_100", "🥇", "百分达人"),
        Triple("points_200", "👑", "学霸之星"),
        Triple("streak_5", "🔥", "五连对"),
        Triple("streak_10", "⚡", "十连对"),
        Triple("streak_20", "🚀", "二十连对")
    )

    /** Human-readable unlock condition for a medal id (growth screen / tests). */
    fun conditionOf(id: String): String = when (id) {
        "first_quiz" -> "完成第一次答对"
        "points_10" -> "积分达到 10"
        "points_50" -> "积分达到 50"
        "points_100" -> "积分达到 100"
        "points_200" -> "积分达到 200"
        "streak_5" -> "连续答对 5 题"
        "streak_10" -> "连续答对 10 题"
        "streak_20" -> "连续答对 20 题"
        else -> ""
    }

    /**
     * 勋章进度（2026-11-21 UI v3）：返回 ("points"|"streak", 目标值)；
     * first_quiz 无量化进度 → null。供首页"下一枚勋章"进度条使用。
     */
    fun conditionTarget(id: String): Pair<String, Int>? = when (id) {
        "points_10" -> "points" to 10
        "points_50" -> "points" to 50
        "points_100" -> "points" to 100
        "points_200" -> "points" to 200
        "streak_5" -> "streak" to 5
        "streak_10" -> "streak" to 10
        "streak_20" -> "streak" to 20
        else -> null
    }

    /**
     * 错题本"写结果不写选项"（用户要求 2026-11-21）：选择题答案字母（A–D）
     * 换成对应选项内容（如 B → 84）；填空/非字母原样返回。
     */
    fun answerResultText(answer: String, options: List<String>): String {
        val t = answer.trim()
        if (t.length == 1) {
            val idx = "ABCD".indexOf(t.uppercase())
            if (idx in 0..3 && idx < options.size && options[idx].isNotBlank()) {
                return options[idx]
            }
        }
        return t
    }

    fun initial(): State = State(points = 0, streak = 0, bestStreak = 0, medals = emptySet())

    /**
     * Applies one answer to the state and re-evaluates medals.
     * [firstCorrectAnswer] marks the very first correct answer ever (medal
     * first_quiz); it is a separate flag because points alone cannot express
     * it (a first answer that is wrong earns no points).
     *
     * V2.3（用户要求 2026-11-24）：新增按题台账参数——
     *  - [wrongCount]：本题在本次答题前已累计答错次数（用于递增扣减，1 起算；
     *    仅当 [isCorrect]=false 且 [fingerprint] 非空时生效）；
     *  - [bonusCapped]：本题已拿满 [MAX_CORRECT_BONUS_PER_QUESTION] 次加分（true 时
     *    再次答对只得 0 分，不加分也不扣分）。
     * [fingerprint] 非空时调用方负责在调用前后读写累计答错次数 / 加分次数台账
     * （见 [com.antidip.data.ProgressStore]）。fingerprint 为空时退回旧的"固定
     * -1 / 按题型加分"行为，保证既有单测与无题面场景兼容。
     */
    fun applyAnswer(
        state: State,
        isCorrect: Boolean,
        isFill: Boolean,
        firstCorrectAnswer: Boolean = false,
        wrongCount: Int = 0,
        bonusCapped: Boolean = false,
        fingerprint: String? = null
    ): State {
        val delta = when {
            isCorrect && bonusCapped -> 0
            isCorrect && isFill -> POINTS_FILL_CORRECT
            isCorrect -> POINTS_CHOICE_CORRECT
            else -> {
                // 有题面指纹 → 按本题累计答错次数递增扣减；否则固定 -1。
                if (fingerprint != null) -escalatingWrongDeduction(wrongCount + 1)
                else POINTS_WRONG
            }
        }
        val points = (state.points + delta).coerceAtLeast(0)
        val streak = if (isCorrect) state.streak + 1 else 0
        val bestStreak = maxOf(state.bestStreak, streak)
        val medals = evaluateMedals(state.medals, points, streak, firstCorrectAnswer)
        return State(points, streak, bestStreak, medals)
    }

    fun evaluateMedals(
        earned: Set<String>,
        points: Int,
        streak: Int,
        firstCorrectAnswer: Boolean = false
    ): Set<String> {
        val s = earned.toMutableSet()
        if (firstCorrectAnswer) s.add("first_quiz")
        if (points >= 10) s.add("points_10")
        if (points >= 50) s.add("points_50")
        if (points >= 100) s.add("points_100")
        if (points >= 200) s.add("points_200")
        if (streak >= 5) s.add("streak_5")
        if (streak >= 10) s.add("streak_10")
        if (streak >= 20) s.add("streak_20")
        return s
    }

    /**
     * Adds/reinforces a wrong-book entry. Deduplication key is
     * (content, grade): an already-recorded question bumps its wrongCount and
     * refreshes lastWrongAt instead of duplicating the row. The book is
     * capped at [MAX_WRONG_BOOK] (oldest by insertion order dropped).
     */
    fun addWrongEntry(book: List<WrongEntry>, entry: WrongEntry): List<WrongEntry> {
        val idx = book.indexOfFirst {
            it.content == entry.content && it.grade == entry.grade
        }
        val updated = if (idx >= 0) {
            book.toMutableList().apply {
                val old = this[idx]
                this[idx] = old.copy(
                    wrongCount = old.wrongCount + 1,
                    lastWrongAt = entry.lastWrongAt,
                    answer = entry.answer
                )
            }
        } else {
            book + entry
        }
        return if (updated.size > MAX_WRONG_BOOK) updated.takeLast(MAX_WRONG_BOOK) else updated
    }

    // ------------------------------------------------------------------
    // Wrong-book serialization (pure JVM; org.json is NOT available in JVM
    // unit tests, so a separator-based format is used instead of JSON).
    // Fields joined by U+001F, records joined by U+001E — question/answer
    // text never contains those control characters.
    // ------------------------------------------------------------------

    private const val FIELD_SEP = '\u001F'
    private const val RECORD_SEP = '\u001E'

    fun encodeWrongBook(book: List<WrongEntry>): String =
        book.joinToString(RECORD_SEP.toString()) { e ->
            listOf(
                e.content, e.answer, e.subject,
                e.grade.toString(), e.wrongCount.toString(), e.lastWrongAt.toString()
            ).joinToString(FIELD_SEP.toString())
        }

    fun decodeWrongBook(raw: String): List<WrongEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { rec ->
            val f = rec.split(FIELD_SEP)
            if (f.size < 6) return@mapNotNull null
            val content = f[0]
            if (content.isBlank()) return@mapNotNull null
            WrongEntry(
                content = content,
                answer = f[1],
                subject = f[2],
                grade = f[3].toIntOrNull() ?: 4,
                wrongCount = f[4].toIntOrNull() ?: 1,
                lastWrongAt = f[5].toLongOrNull() ?: 0L
            )
        }
    }
}
