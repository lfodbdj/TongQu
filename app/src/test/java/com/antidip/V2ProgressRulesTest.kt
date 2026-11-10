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
        assertTrue(m.containsAll(listOf("points_10", "points_50", "points_100")))
        assertFalse(m.contains("points_200"))
    }

    @Test
    fun medals_byStreakMilestones_andFirstAnswer() {
        val m = ProgressRules.evaluateMedals(emptySet(), points = 0, streak = 5, firstCorrectAnswer = true)
        assertTrue(m.containsAll(listOf("first_quiz", "streak_5")))
        assertFalse(m.contains("streak_10"))
    }

    @Test
    fun medals_notRevoked_whenPointsDrop() {
        val earned = ProgressRules.evaluateMedals(emptySet(), points = 60, streak = 0)
        val after = ProgressRules.evaluateMedals(earned, points = 0, streak = 0)
        assertTrue(after.contains("points_50")) // 已获得荣誉不回退
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
}
