package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2.0 童趣成长规则（需求 2026-11-10）：选择题对一题 +1 分、填空题对一题 +2 分、
 * 错题一次 -1 分（总积分不为负）、连对/最高连对、勋章自动颁发、错题本去重与上限。
 */
class V2ProgressRulesTest {

    @Test
    fun choiceCorrect_addsOnePoint_andIncrementsStreak() {
        var st = ProgressRules.initial()
        st = ProgressRules.applyAnswer(st, isCorrect = true, isFill = false)
        assertEquals(1, st.points)
        assertEquals(1, st.streak)
        assertEquals(1, st.bestStreak)
    }

    @Test
    fun fillCorrect_addsTwoPoints() {
        var st = ProgressRules.initial()
        repeat(2) { st = ProgressRules.applyAnswer(st, isCorrect = true, isFill = true) }
        assertEquals(4, st.points)
    }

    @Test
    fun wrongAnswer_minusOne_andResetsStreak_butNeverNegative() {
        var st = ProgressRules.initial().let {
            it.copy(points = it.points) // start 0
        }
        st = st.copy(points = 0)
        st = ProgressRules.applyAnswer(st, isCorrect = true, isFill = false) // +1, streak 1
        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false) // -1, streak 0
        assertEquals(0, st.points) // clamped, never negative
        assertEquals(0, st.streak)
        assertEquals(1, st.bestStreak)

        // 有积分时正常扣减
        var s2 = ProgressRules.initial()
        repeat(5) { s2 = ProgressRules.applyAnswer(s2, true, false) } // 5分
        s2 = ProgressRules.applyAnswer(s2, false, false) // 4分
        assertEquals(4, s2.points)
    }

    @Test
    fun medals_byPointsMilestones() {
        val m = ProgressRules.evaluateMedals(emptySet(), points = 100, streak = 0)
        assertTrue(m.containsAll(listOf("points_10", "points_100")))
        // V2.5：积分 50/200/300 档已删除
        assertFalse(m.contains("points_50"))
        assertFalse(m.contains("points_200"))
        assertFalse(m.contains("points_500"))
    }

    @Test
    fun medals_byStreakMilestones_andFirstAnswer() {
        val m = ProgressRules.evaluateMedals(emptySet(), points = 0, streak = 10, firstCorrectAnswer = true)
        assertTrue(m.containsAll(listOf("first_quiz", "streak_10")))
        // V2.5：5/20/30 连对已删除
        assertFalse(m.contains("streak_5"))
        assertFalse(m.contains("streak_20"))
        assertFalse(m.contains("streak_30"))
        assertFalse(m.contains("streak_50"))
    }

    /** V2.5（2026-11-28）：静态徽章 9 枚 + 新积分/连对档 + 科目完成徽章。 */
    @Test
    fun medals_v5_ladderAndSubjectBadges() {
        assertEquals(9, ProgressRules.MEDALS.size)
        val byPoints = ProgressRules.evaluateMedals(emptySet(), points = 5000, streak = 0)
        assertTrue(
            byPoints.containsAll(
                listOf("points_10", "points_100", "points_500", "points_1000", "points_5000")
            )
        )
        val byStreak = ProgressRules.evaluateMedals(emptySet(), points = 0, streak = 100)
        assertTrue(byStreak.containsAll(listOf("streak_10", "streak_50", "streak_100")))
        assertEquals("积分达到 1000", ProgressRules.conditionOf("points_1000"))
        assertEquals("streak" to 100, ProgressRules.conditionTarget("streak_100"))

        // 科目完成徽章：累计答对 20 题解锁，id 按年级区分
        assertEquals(20, ProgressRules.SUBJECT_BADGE_TARGET)
        assertEquals(5, ProgressRules.SUBJECT_BADGES.size)
        assertTrue(ProgressRules.subjectBadgeEarned(20))
        assertTrue(ProgressRules.subjectBadgeEarned(35))
        assertFalse(ProgressRules.subjectBadgeEarned(19))
        assertEquals("g4_math", ProgressRules.subjectBadgeId(4, "math"))
        assertFalse(
            ProgressRules.subjectBadgeId(4, "math") == ProgressRules.subjectBadgeId(5, "math")
        )
    }

    @Test
    fun medals_notRevoked_whenPointsDrop() {
        val earned = ProgressRules.evaluateMedals(emptySet(), points = 600, streak = 0)
        val after = ProgressRules.evaluateMedals(earned, points = 0, streak = 0)
        assertTrue(after.contains("points_500")) // 已获得荣誉不回退
    }

    @Test
    fun wrongBook_dedupesByContentAndGrade_andCounts() {
        var book = emptyList<ProgressRules.WrongEntry>()
        val e = ProgressRules.WrongEntry("3+5=?", "8", "math", 4, 1, 1000L)
        book = ProgressRules.addWrongEntry(book, e)
        book = ProgressRules.addWrongEntry(book, e.copy(lastWrongAt = 2000L))
        assertEquals(1, book.size)
        assertEquals(2, book[0].wrongCount)
        assertEquals(2000L, book[0].lastWrongAt)
    }

    @Test
    fun wrongBook_sameContentDifferentGrade_isSeparate() {
        var book = emptyList<ProgressRules.WrongEntry>()
        book = ProgressRules.addWrongEntry(book, ProgressRules.WrongEntry("q", "a", "math", 4, 1, 1L))
        book = ProgressRules.addWrongEntry(book, ProgressRules.WrongEntry("q", "a", "math", 5, 1, 2L))
        assertEquals(2, book.size)
    }

    @Test
    fun wrongBook_cappedAtMax() {
        var book = emptyList<ProgressRules.WrongEntry>()
        for (i in 0 until (ProgressRules.MAX_WRONG_BOOK + 10)) {
            book = ProgressRules.addWrongEntry(
                book,
                ProgressRules.WrongEntry("q$i", "a", "math", 4, 1, i.toLong())
            )
        }
        assertEquals(ProgressRules.MAX_WRONG_BOOK, book.size)
        assertEquals("q10", book.first().content) // 最旧的被丢弃
    }

    @Test
    fun wrongBook_roundTripEncodesAndDecodes() {
        val book = listOf(
            ProgressRules.WrongEntry("计算：1.25 + 2.75 = ____", "4", "math", 4, 3, 111L),
            ProgressRules.WrongEntry("古诗填空：床前明月||光", "疑", "chinese", 4, 1, 222L)
        )
        val decoded = ProgressRules.decodeWrongBook(ProgressRules.encodeWrongBook(book))
        assertEquals(book, decoded)
    }

    @Test
    fun wrongBook_decodeBlankAndMalformed_isSafe() {
        assertTrue(ProgressRules.decodeWrongBook("").isEmpty())
        assertTrue(ProgressRules.decodeWrongBook("   ").isEmpty())
        assertTrue(ProgressRules.decodeWrongBook("qa6").isEmpty()) // 缺字段 → 丢弃
        val sep = 0x1F.toChar().toString()
        val ok = listOf("q", "a", "math", "4", "2", "123").joinToString(sep)
        val decoded = ProgressRules.decodeWrongBook(ok)
        assertEquals(1, decoded.size)
        assertEquals("q", decoded[0].content)
        assertEquals(4, decoded[0].grade)
    }

    // ===== V2.3（2026-11-24）：按题递增扣减 + 每题最多 2 次答对加分 =====

    @Test
    fun escalatingWrongDeduction_matchesOneTwoFiveTen() {
        assertEquals(1, ProgressRules.escalatingWrongDeduction(1))
        assertEquals(2, ProgressRules.escalatingWrongDeduction(2))
        assertEquals(5, ProgressRules.escalatingWrongDeduction(3))
        assertEquals(10, ProgressRules.escalatingWrongDeduction(4))
        assertEquals(10, ProgressRules.escalatingWrongDeduction(5))
        assertEquals(1, ProgressRules.escalatingWrongDeduction(0)) // 防御：<1 按 1
    }

    @Test
    fun questionFingerprint_isStablePerContentAndGrade() {
        assertEquals(
            ProgressRules.questionFingerprint("2+2=?", 4),
            ProgressRules.questionFingerprint("2+2=?", 4)
        )
        assertFalse(
            ProgressRules.questionFingerprint("2+2=?", 4) ==
                ProgressRules.questionFingerprint("2+2=?", 5)
        )
    }

    /**
     * 递增扣减：某题第 N 次答错时扣 1/2/5/10 分。
     * 传入 fingerprint + wrongCount（答题前已累计次数）。
     */
    @Test
    fun wrongAnswer_withFingerprint_escalatesDeduction() {
        var st = ProgressRules.State(points = 100, streak = 0, bestStreak = 0, medals = emptySet())
        val fp = ProgressRules.questionFingerprint("难题", 4)

        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 0, fingerprint = fp)
        assertEquals(99, st.points)   // 第 1 次错 -1
        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 1, fingerprint = fp)
        assertEquals(97, st.points)   // 第 2 次错 -2
        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 2, fingerprint = fp)
        assertEquals(92, st.points)   // 第 3 次错 -5
        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 3, fingerprint = fp)
        assertEquals(82, st.points)   // 第 4 次错 -10
        st = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 4, fingerprint = fp)
        assertEquals(72, st.points)   // 第 5 次起仍 -10
    }

    @Test
    fun wrongAnswer_withoutFingerprint_keepsFlatOne() {
        val st = ProgressRules.State(points = 10, streak = 0, bestStreak = 0, medals = emptySet())
        val after = ProgressRules.applyAnswer(st, isCorrect = false, isFill = false, wrongCount = 3)
        assertEquals(9, after.points) // 无指纹 → 固定 -1（向后兼容）
    }

    @Test
    fun correctAnswer_cappedAfterTwoBonuses() {
        var st = ProgressRules.State(points = 0, streak = 0, bestStreak = 0, medals = emptySet())
        val fp = ProgressRules.questionFingerprint("易题", 4)

        st = ProgressRules.applyAnswer(st, true, isFill = false, bonusCapped = false, fingerprint = fp)
        assertEquals(1, st.points) // 第 1 次答对 +1
        st = ProgressRules.applyAnswer(st, true, isFill = false, bonusCapped = false, fingerprint = fp)
        assertEquals(2, st.points) // 第 2 次答对 +1
        st = ProgressRules.applyAnswer(st, true, isFill = false, bonusCapped = true, fingerprint = fp)
        assertEquals(2, st.points) // 第 3 次起封顶 +0（不扣也不加）
    }

    @Test
    fun correctFillAnswer_cappedGivesZero() {
        val st = ProgressRules.State(points = 10, streak = 3, bestStreak = 3, medals = emptySet())
        val after = ProgressRules.applyAnswer(st, true, isFill = true, bonusCapped = true)
        assertEquals(10, after.points) // 封顶后填空答对也不加分
        assertEquals(4, after.streak) // 连对仍 +1（只是不加分）
    }
}
