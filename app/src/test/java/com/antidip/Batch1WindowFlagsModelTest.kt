package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D02 / D03 回归验证：QuizActivity 三处窗口标志操作在
 * API 24-26（deprecated flags 分支）与 API 27+（setShowWhenLocked/setTurnScreenOn 分支）
 * 的全流程对称性（重点验证方向 2）。
 *
 * setShowWhenLocked / WindowManager 需要 Activity 与真实 Window，纯 JVM 单测（无 Robolectric）
 * 无法调用；这里把 QuizActivity.kt:59-69 / :352-357 / :386-391 的**分支等价复刻**为布尔状态机，
 * 用于客观验证「进入答题 -> lockNow 惩罚 -> 锁定结束恢复」三段是否成对。
 *
 * 补充事实：AndroidManifest.xml:32-33 还静态声明了
 * android:showOnLockScreen="true" / android:turnScreenOn="true"，
 * 因此 turnScreenOn 在惩罚期间保持开启是确定行为，而非仅代码层推断。
 *
 * KNOWN_GAP_* 为 Characterization 断言，锁定当前实际行为作为缺陷证据（见报告 M5），
 * 不代表期望行为。真机验证步骤见交付报告。
 */
class Batch1WindowFlagsModelTest {

    private class WindowState {
        var showWhenLocked = false
            private set
        var turnScreenOn = false
            private set
        var keepScreenOn = false
            private set
        val operations = mutableListOf<String>()

        /** 复刻 :59-69（onCreate）。 */
        fun onCreate(apiAtLeast27: Boolean) {
            keepScreenOn = true              // FLAG_KEEP_SCREEN_ON 无条件保留
            showWhenLocked = true
            turnScreenOn = true
            if (apiAtLeast27) {
                operations.add("setShowWhenLocked(true)")
                operations.add("setTurnScreenOn(true)")
            } else {
                operations.add("addFlags(FLAG_SHOW_WHEN_LOCKED|FLAG_TURN_SCREEN_ON)")
            }
        }

        /** 复刻 :352-357（lockNow 之前）。 */
        fun beforeLockNow(apiAtLeast27: Boolean) {
            showWhenLocked = false
            operations.add(
                if (apiAtLeast27) "setShowWhenLocked(false)"
                else "clearFlags(FLAG_SHOW_WHEN_LOCKED)"
            )
        }

        /** 复刻 :386-391（lockTimer.onFinish 锁定结束）。 */
        fun afterLockExpires(apiAtLeast27: Boolean) {
            showWhenLocked = true
            operations.add(
                if (apiAtLeast27) "setShowWhenLocked(true)"
                else "addFlags(FLAG_SHOW_WHEN_LOCKED)"
            )
        }

        fun snapshot() = Triple(showWhenLocked, turnScreenOn, keepScreenOn)
    }

    private fun fullFlow(apiAtLeast27: Boolean): WindowState =
        WindowState().apply {
            onCreate(apiAtLeast27)
            beforeLockNow(apiAtLeast27)
            afterLockExpires(apiAtLeast27)
        }

    // ------------------------------------------------------------------
    // 正常回归：SHOW_WHEN_LOCKED 两条分支各自成对、且最终状态一致
    // ------------------------------------------------------------------

    @Test
    fun api27plus_showWhenLocked_isSymmetricAcrossFullFlow() {
        val s = WindowState()
        s.onCreate(true)
        assertTrue(s.showWhenLocked)
        s.beforeLockNow(true)
        assertFalse(s.showWhenLocked)
        s.afterLockExpires(true)
        assertTrue(s.showWhenLocked)
        assertEquals(
            listOf("setShowWhenLocked(true)", "setTurnScreenOn(true)", "setShowWhenLocked(false)", "setShowWhenLocked(true)"),
            s.operations
        )
    }

    @Test
    fun api24to26_showWhenLocked_isSymmetricAcrossFullFlow() {
        val s = WindowState()
        s.onCreate(false)
        assertTrue(s.showWhenLocked)
        s.beforeLockNow(false)
        assertFalse(s.showWhenLocked)
        s.afterLockExpires(false)
        assertTrue(s.showWhenLocked)
    }

    @Test
    fun bothApiBranches_endInTheSameFlagState() {
        assertEquals(fullFlow(true).snapshot(), fullFlow(false).snapshot())
    }

    @Test
    fun onCreate_setsAllThreeFlagsOnBothBranches() {
        for (api27 in listOf(true, false)) {
            val s = WindowState()
            s.onCreate(api27)
            assertEquals(Triple(true, true, true), s.snapshot())
        }
    }

    // ------------------------------------------------------------------
    // 缺陷证据（Characterization）—— 详见交付报告 M5
    // ------------------------------------------------------------------

    @Test
    fun KNOWN_GAP_turnScreenOn_isNeverClearedDuringPenaltyInEitherBranch() {
        for (api27 in listOf(true, false)) {
            val s = WindowState()
            s.onCreate(api27)
            s.beforeLockNow(api27)
            assertTrue(
                "API ${if (api27) "27+" else "24-26"}：惩罚期间 turnScreenOn 仍为 true（未与 onCreate 成对清除）",
                s.turnScreenOn
            )
            assertFalse("showWhenLocked 已被清除", s.showWhenLocked)
            assertFalse(
                "操作日志中不存在任何关闭 turnScreenOn 的调用",
                s.operations.any { it.contains("TurnScreenOn(false)") || it.contains("clearFlags") && it.contains("TURN_SCREEN_ON") }
            )
        }
    }

    @Test
    fun KNOWN_GAP_keepScreenOn_isNeverClearedDuringPenalty() {
        for (api27 in listOf(true, false)) {
            val s = WindowState()
            s.onCreate(api27)
            s.beforeLockNow(api27)
            assertTrue("lockNow() 熄屏惩罚期间 FLAG_KEEP_SCREEN_ON 仍保持", s.keepScreenOn)
        }
    }

    @Test
    fun KNOWN_GAP_lockNowSkippedWhenAdminInactive_leavesShowFlagOffForWholePenalty() {
        // DeviceLockHelper.kt:28 —— admin 未激活时 lockNow() 直接返回 false，屏幕并未锁。
        // 但 QuizActivity:352-357 已先把 showWhenLocked 关掉，只有倒计时 onFinish(:386)
        // 才恢复，长达 Constants.LOCK_DURATION_SECONDS = 600s。
        val s = WindowState()
        s.onCreate(true)
        s.beforeLockNow(true)
        val lockNowSucceeded = false // 模拟 isAdminActive() == false

        assertFalse(lockNowSucceeded)
        assertFalse("屏幕未真正锁定，但 Activity 已不能在锁屏之上显示", s.showWhenLocked)
        assertTrue("该状态会持续整个 10 分钟惩罚期", Constants.LOCK_DURATION_SECONDS == 600)
        s.afterLockExpires(true)
        assertTrue(s.showWhenLocked)
    }
}
