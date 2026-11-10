package com.antidip

import com.antidip.db.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批次 2-b：[QuizEngine.onQuestionBankExhausted] 的纯 JVM 单元测试。
 *
 * 直接调用生产类（QuizEngine 是无 Android 依赖的纯 Kotlin 状态机）。
 * A09 的核心回归点：以前 QuizActivity 在题库抽空时直接构造
 * `AnswerResult.RoundFailed(quizEngine.consecutiveFailures + 1)`，
 * **引擎内部计数从未递增**，导致「UI 展示值 / 引擎状态 / 持久化值」三处漂移，
 * 连续失败 5 轮锁定也永远触发不了。本文件同时断言「返回值载荷」与「引擎内部状态」，
 * 确保两者始终一致。
 *
 * 注意：startRound() **不**重置 consecutiveFailures（连续失败轮数跨轮累计，
 * 只有 Correct 清零）。本文件的断言按此语义编写，不写成「startRound 后归零」。
 */
class Batch2QuizEngineExhaustedTest {

    private lateinit var engine: QuizEngine

    private val choiceQ = QuestionEntity(
        subject = "math", grade = 4, type = "choice",
        content = "1+1=?", options = "A|2|B|3", answer = "A"
    )

    @Before
    fun setup() {
        engine = QuizEngine()
    }

    @Test
    fun firstExhaustion_returnsRoundFailed1_andEngineCounterIs1() {
        val result = engine.onQuestionBankExhausted()

        assertTrue(result is AnswerResult.RoundFailed)
        assertEquals(1, (result as AnswerResult.RoundFailed).consecutiveFailures)
        assertEquals("引擎内部计数必须真的递增（A09）", 1, engine.consecutiveFailures)
        assertFalse(engine.isLocked)
    }

    @Test
    fun fiveConsecutiveExhaustions_fourRoundFailedThenLocked() {
        // 前置守卫：手算的 1..4 / 第 5 次语义依赖 MAX_CONSECUTIVE_FAILURES == 5，
        // 常量若被改动，本测试必须显式失败而不是静默漂移。
        assertEquals(5, Constants.MAX_CONSECUTIVE_FAILURES)

        for (expected in 1..4) {
            val result = engine.onQuestionBankExhausted()
            assertTrue(
                "第 $expected 次应为 RoundFailed，实际 $result",
                result is AnswerResult.RoundFailed
            )
            assertEquals(expected, (result as AnswerResult.RoundFailed).consecutiveFailures)
            assertFalse("第 $expected 次不应锁定", engine.isLocked)
        }

        val fifth = engine.onQuestionBankExhausted()
        assertTrue("第 5 次应为 Locked，实际 $fifth", fifth is AnswerResult.Locked)
        assertEquals(
            Constants.LOCK_DURATION_SECONDS,
            (fifth as AnswerResult.Locked).durationSeconds
        )
        assertTrue(engine.isLocked)
        assertFalse(engine.isRoundActive)
    }

    @Test
    fun lockedResult_activelyClearsRoundActive_notJustInitiallyFalse() {
        // 让 isRoundActive 在锁定前确实为 true，避免「本来就 false」的平凡通过。
        engine.startRound(choiceQ)
        assertTrue(engine.isRoundActive)
        repeat(Constants.MAX_CONSECUTIVE_FAILURES - 1) { engine.onQuestionBankExhausted() }
        engine.isRoundActive = true

        val locked = engine.onQuestionBankExhausted()
        assertTrue(locked is AnswerResult.Locked)
        assertFalse("Locked 必须把 isRoundActive 置 false", engine.isRoundActive)
    }

    @Test
    fun payloadAndEngineCounterStayInSync_noA09Drift() {
        repeat(Constants.MAX_CONSECUTIVE_FAILURES - 1) { index ->
            val result = engine.onQuestionBankExhausted() as AnswerResult.RoundFailed
            assertEquals(
                "返回值载荷与引擎内部计数不得漂移（A09）",
                engine.consecutiveFailures,
                result.consecutiveFailures
            )
            assertEquals(index + 1, engine.consecutiveFailures)
        }
    }

    @Test
    fun startRound_doesNotResetConsecutiveFailures() {
        engine.startRound(choiceQ)
        engine.onQuestionBankExhausted()
        engine.onQuestionBankExhausted()
        assertEquals(2, engine.consecutiveFailures)

        engine.startRound(choiceQ)
        assertEquals(
            "startRound 只重置本轮状态，不清空连续失败轮数",
            2,
            engine.consecutiveFailures
        )

        val third = engine.onQuestionBankExhausted()
        assertTrue(third is AnswerResult.RoundFailed)
        assertEquals(3, (third as AnswerResult.RoundFailed).consecutiveFailures)
    }

    @Test
    fun correctAnswer_resetsStreak_thenExhaustionRestartsFromOne() {
        engine.startRound(choiceQ)
        repeat(3) { engine.onQuestionBankExhausted() }
        assertEquals(3, engine.consecutiveFailures)

        // 重新开一轮以便 submitAnswer 能取到题目；startRound 不会清零失败轮数。
        engine.startRound(choiceQ)
        assertEquals(3, engine.consecutiveFailures)

        val correct = engine.submitAnswer(choiceQ.answer)
        assertTrue(correct is AnswerResult.Correct)
        assertEquals("答对后连续失败轮数必须归零", 0, engine.consecutiveFailures)

        val next = engine.onQuestionBankExhausted()
        assertTrue(next is AnswerResult.RoundFailed)
        assertEquals("归零后应重新从 1 开始计数", 1, (next as AnswerResult.RoundFailed).consecutiveFailures)
    }

    @Test
    fun resetAfterLock_allowsStreakToRestartFromOne() {
        repeat(Constants.MAX_CONSECUTIVE_FAILURES) { engine.onQuestionBankExhausted() }
        assertTrue(engine.isLocked)

        engine.resetAfterLock()
        assertFalse(engine.isLocked)
        assertEquals(0, engine.consecutiveFailures)

        val next = engine.onQuestionBankExhausted()
        assertTrue(next is AnswerResult.RoundFailed)
        assertEquals(1, (next as AnswerResult.RoundFailed).consecutiveFailures)
    }

    @Test
    fun exhaustionDoesNotRequireAnActiveRound() {
        // 题库抽空可能发生在 onCreate / 锁定结束等「尚未开轮」的时机，
        // 因此该接口不能依赖 isRoundActive 或已加载的题目。
        assertFalse(engine.isRoundActive)
        val result = engine.onQuestionBankExhausted()
        assertTrue(result is AnswerResult.RoundFailed)
        assertEquals(1, engine.consecutiveFailures)
    }
}
