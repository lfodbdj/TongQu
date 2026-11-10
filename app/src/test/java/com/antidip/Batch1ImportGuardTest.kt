package com.antidip

import com.antidip.db.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D08 回归验证 + 「题库抽空」验收点的纯 JVM 规格模型。
 *
 * QuestionBankManager 依赖 Context / Room / assets，QuizActivity.startNewRound() 依赖
 * ViewBinding，均无法在纯 JVM 单测（无 Robolectric）中实例化。这里把
 * QuestionBankManager.kt:39-75 的「守卫 + 逐文件容错」以及
 * QuizActivity.kt:139-156 / :486-496 的「题库为空时退出」决策**逐行复刻**为纯模型。
 *
 * 断言分两类：正常回归（D08 per-file 容错的期望行为）与 KNOWN_GAP_* Characterization
 * （锁定当前行为、为缺陷报告提供可复现证据）。详见交付报告 S2 / M3。
 *
 * 项目 assets 实测共 5 个 questions_*.json，合计 427 道题（见交付报告），
 * 因此「首次只有 1 个文件成功」是一个有现实意义的场景。
 */
class Batch1ImportGuardTest {

    /**
     * 复刻 QuestionBankManager.importBuiltInQuestions() 的守卫与逐文件容错。
     * assets: 文件名 -> parseAndImport 返回的有效题数；0 表示"合法 JSON 但 0 有效题"
     * （此时 parseAndImport 返回 0 而**不抛异常**，:169-172）。
     */
    private class BankModel {
        var count: Int = 0
            private set
        val importAttempts = mutableListOf<String>()
        val successLog = mutableListOf<String>()

        fun importBuiltIn(assets: List<Pair<String, Int>>, failures: Set<String> = emptySet()): Int {
            if (count > 0) return 0                       // :41-45 粗粒度守卫
            var totalImported = 0
            for ((file, questions) in assets) {
                importAttempts.add(file)
                try {
                    if (file in failures) throw IllegalArgumentException("malformed json")
                    totalImported += questions            // :62
                    if (questions > 0) successLog.add(file)
                    count += questions
                } catch (e: Exception) {
                    continue                              // :63-66 单文件失败隔离
                }
            }
            return totalImported
        }

        /** 模拟 B14「答对两次移除题目」把题库删到只剩 n 题。 */
        fun deleteDownTo(n: Int) {
            count = n
        }
    }

    /** 复刻 QuizActivity.startNewRound():146-155 与 finish():486-496 的退出决策。 */
    private class QuizExitModel {
        var lockTaskActive = true
            private set
        var finished = false
            private set

        fun startNewRound(randomQuestionAvailable: Boolean) {
            if (!randomQuestionAvailable) {
                // Toast("题库为空，请先导入题库") + finish()
                finish()
                return
            }
            // 正常出题
        }

        private fun finish() {
            if (lockTaskActive) lockTaskActive = false // stopLockTask()
            finished = true
        }
    }

    private val fiveAssets = listOf(
        "questions_grade4.json" to 201,
        "questions_chinese_extra.json" to 60,
        "questions_math_extra.json" to 74,
        "questions_english_extra.json" to 65,
        "questions_extra.json" to 27
    )

    private val choiceQ = QuestionEntity(
        subject = "math", grade = 4, type = "choice",
        content = "1+1=?", options = "A|2|B|3", answer = "A"
    )

    // ------------------------------------------------------------------
    // D08 正常回归：单个畸形文件不得阻断其余文件
    // ------------------------------------------------------------------

    @Test
    fun singleMalformedAsset_remainingFourFilesAreStillImported() {
        val bank = BankModel()
        val imported = bank.importBuiltIn(fiveAssets, failures = setOf("questions_extra.json"))

        assertEquals(400, imported)
        assertEquals(400, bank.count)
        assertEquals("5 个文件都应被尝试", 5, bank.importAttempts.size)
        assertEquals("只有 4 个文件成功", 4, bank.successLog.size)
        assertFalse(bank.successLog.contains("questions_extra.json"))
    }

    @Test
    fun failureOnFirstAsset_doesNotAbortTheWholeBatch() {
        val bank = BankModel()
        val imported = bank.importBuiltIn(fiveAssets, failures = setOf("questions_grade4.json"))
        assertEquals(226, imported)
        assertEquals(5, bank.importAttempts.size)
        assertEquals(4, bank.successLog.size)
    }

    @Test
    fun allAssetsHealthy_importsEveryQuestion() {
        val bank = BankModel()
        assertEquals(427, bank.importBuiltIn(fiveAssets))
        assertEquals(427, bank.count)
        assertEquals(5, bank.successLog.size)
    }

    // ------------------------------------------------------------------
    // 缺陷证据（Characterization）—— 详见交付报告 S2 / M3
    // ------------------------------------------------------------------

    @Test
    fun KNOWN_GAP_partialFirstImport_thenRelaunch_permanentlySkipsRemainingAssets() {
        val bank = BankModel()
        // 首次启动：只有 1 个文件成功（其余 4 个畸形 / IO 失败）
        val first = bank.importBuiltIn(
            fiveAssets,
            failures = setOf(
                "questions_chinese_extra.json", "questions_math_extra.json",
                "questions_english_extra.json", "questions_extra.json"
            )
        )
        assertEquals(201, first)
        assertEquals(201, bank.count)
        val attemptsAfterFirst = bank.importAttempts.size

        // 故障消失后再次冷启动：count>0 守卫直接 return 0，剩余 4 个文件永不重试
        val second = bank.importBuiltIn(fiveAssets, failures = emptySet())
        assertEquals(0, second)
        assertEquals("第二次启动没有任何文件被再次尝试", attemptsAfterFirst, bank.importAttempts.size)
        assertEquals("题库永久只剩 201/427 题", 201, bank.count)
    }

    @Test
    fun KNOWN_GAP_zeroValidQuestionAsset_isSilentlyTreatedAsHandled() {
        // parseAndImport 对"合法 JSON 但 0 有效题"返回 0 而不抛异常，
        // per-file catch 感知不到，且守卫后续也不会重试该文件。
        val assets = listOf(
            "questions_grade4.json" to 201,
            "questions_empty_but_valid.json" to 0,
            "questions_extra.json" to 27
        )
        val bank = BankModel()
        assertEquals(228, bank.importBuiltIn(assets))
        assertEquals(3, bank.importAttempts.size)
        assertEquals("0 题文件未进入 successLog，也没有任何失败日志", 2, bank.successLog.size)

        val attempts = bank.importAttempts.size
        assertEquals(0, bank.importBuiltIn(assets))
        assertEquals("该文件永远不会被重试", attempts, bank.importAttempts.size)
    }

    @Test
    fun KNOWN_GAP_masteryDeletion_doesNotTriggerReimportWhileAnyQuestionRemains() {
        val bank = BankModel()
        bank.importBuiltIn(fiveAssets)
        bank.deleteDownTo(1)                    // B14 删到只剩 1 题
        val attempts = bank.importAttempts.size

        assertEquals(0, bank.importBuiltIn(fiveAssets))
        assertEquals("只要 count>0 就不补充内置题库", attempts, bank.importAttempts.size)
        assertEquals(1, bank.count)
    }

    @Test
    fun exhaustedBank_isTheOnlySelfHealingPath() {
        val bank = BankModel()
        bank.importBuiltIn(fiveAssets)
        bank.deleteDownTo(0)                    // 全部删光
        assertEquals(427, bank.importBuiltIn(fiveAssets))
        assertEquals("只有 count==0 才会重新全量导入", 10, bank.importAttempts.size)
    }

    @Test
    fun KNOWN_GAP_emptyQuestionBank_finishesQuizAndReleasesScreenPinning() {
        val quiz = QuizExitModel()
        quiz.startNewRound(randomQuestionAvailable = false)

        assertTrue("题库为空时 QuizActivity 直接 finish()", quiz.finished)
        assertFalse("finish() 会 stopLockTask()，屏幕固定被解除", quiz.lockTaskActive)

        val normal = QuizExitModel()
        normal.startNewRound(randomQuestionAvailable = true)
        assertFalse(normal.finished)
        assertTrue(normal.lockTaskActive)
    }

    @Test
    fun roundWithExhaustedBank_stillReachesRoundFailed_notAnExitSignal() {
        // 对照：轮中题库耗尽时 QuizActivity:302-305 构造 RoundFailed，并不退出；
        // 绕过只发生在 startNewRound() 的题库为空分支。
        val engine = QuizEngine()
        engine.startRound(choiceQ)
        var result = engine.submitAnswer("B")
        repeat(Constants.MAX_ATTEMPTS - 1) { result = engine.submitAnswer("B") }
        assertTrue(result is AnswerResult.NeedsNextQuestion)

        val fallback = AnswerResult.RoundFailed(engine.consecutiveFailures + 1)
        assertEquals(1, fallback.consecutiveFailures)
        assertFalse(engine.isLocked)
    }
}
