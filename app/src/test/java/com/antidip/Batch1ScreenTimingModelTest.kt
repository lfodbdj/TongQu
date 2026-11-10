package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D06 / D07 回归验证：ScreenMonitorService 计时与 onStartCommand 分派的状态机模型。
 *
 * ScreenMonitorService 是 Android Service（依赖 Context / PowerManager / SharedPreferences /
 * BroadcastReceiver / CountDownTimer），在 testDebugUnitTest（无 Robolectric、android.jar 为
 * stub）中无法实例化。这里把 ScreenMonitorService.kt:37-101 / :130-207 以及
 * SettingsActivity.kt:64-92、QuizActivity.kt:408-419 的相关控制流**逐行复刻**成
 * 带可注入时钟的纯 JVM 模型，用于：
 *   1) 验证 D07「startScreenTiming 先结算上一窗口」是否重复累加 / 丢失（重点方向 1）；
 *   2) 验证 D06「onCreate 读回累计值」在达到阈值时的行为；
 *   3) 验证 Action 分派与暂停/恢复闭环（重点验收点「点启动服务后能否再次弹题」）。
 *
 * 断言分两类：
 *   - 正常回归断言：期望行为（应 PASS）；
 *   - KNOWN_GAP_* ：Characterization 断言，锁定**当前实际行为**，为缺陷报告提供
 *     可复现证据，不代表期望行为。缺陷详见交付报告 S1 / M1 / M2。
 *
 * 本模型不复刻 BroadcastReceiver 注册、通知、Room、DataStore 等 Android 细节；
 * 所有涉及真机运行时的结论均需在报告的真机清单中复核。
 */
class Batch1ScreenTimingModelTest {

    /**
     * ScreenMonitorService 的等价状态机。
     * 时间由 [now] 手动推进，避免真实等待；[settlements] 记录每次结算的毫秒数，
     * 用于检测「同一亮屏窗口被累加两次」。
     */
    private class ServiceModel(
        prefsAccumulated: Long = 0L,
        prefsPaused: Boolean = false,
        initialScreenOn: Boolean = true,
        private val intervalMs: Long = Constants.QUIZ_INTERVAL_MS
    ) {
        var now: Long = 1_000_000L
            private set
        var accumulated: Long = 0L
            private set
        var screenOnStartMs: Long = 0L
            private set
        var isPaused: Boolean = false
            private set
        var isScreenOn: Boolean = initialScreenOn
            private set
        var quizLaunches: Int = 0
            private set
        var timerActive: Boolean = false
            private set
        var timerRemainingMs: Long = 0L
            private set
        var prefsAccumulated: Long = prefsAccumulated
            private set
        var prefsPaused: Boolean = prefsPaused
            private set

        val settlements = mutableListOf<Long>()
        private var timerDueAt: Long = Long.MAX_VALUE

        fun advance(ms: Long) {
            require(ms >= 0)
            now += ms
            // CountDownTimer.onFinish() -> triggerQuiz()，一次性
            if (timerActive && now >= timerDueAt) {
                timerActive = false
                triggerQuiz()
            }
        }

        /** 复刻 :37-62：从 prefs 恢复状态，屏亮且未暂停则开始计时。 */
        fun onCreate() {
            isPaused = prefsPaused
            accumulated = prefsAccumulated
            if (isScreenOn && !isPaused) startScreenTiming()
        }

        /** 复刻 :130-163（D07 新增 :139-141 的"先结算上一窗口"）。 */
        fun startScreenTiming() {
            if (screenOnStartMs > 0) {
                val delta = now - screenOnStartMs
                accumulated += delta
                settlements.add(delta)
            }
            screenOnStartMs = now
            timerActive = false // quizTimer?.cancel()
            val remaining = intervalMs - accumulated
            if (remaining <= 0) {
                triggerQuiz()
                return
            }
            timerRemainingMs = remaining
            timerDueAt = now + remaining
            timerActive = true
        }

        /** 复刻 :165-173（ACTION_SCREEN_OFF / onDestroy）。 */
        fun pauseTiming() {
            timerActive = false
            if (screenOnStartMs > 0) {
                val delta = now - screenOnStartMs
                accumulated += delta
                settlements.add(delta)
                screenOnStartMs = 0
            }
        }

        /** 复刻 :175-180（ACTION_PAUSE：丢弃当前窗口而不累加）。 */
        fun stopTiming() {
            timerActive = false
            screenOnStartMs = 0
        }

        /** 复刻 :182-198。 */
        fun triggerQuiz() {
            timerActive = false
            accumulated = 0
            screenOnStartMs = 0
            quizLaunches++
        }

        /** 复刻 :200-207。 */
        fun saveState() {
            prefsAccumulated = accumulated
            prefsPaused = isPaused
        }

        /** 复刻 :64-85；B12 #F 明确"no redundant re-read here"——null action 不做任何事。 */
        fun onStartCommand(action: String?) {
            when (action) {
                Constants.ACTION_PAUSE -> {
                    isPaused = true
                    stopTiming()
                    saveState()
                }
                Constants.ACTION_RESUME -> {
                    isPaused = false
                    saveState()               // 注意：在结算之前
                    if (isScreenOn) startScreenTiming()
                }
                else -> { /* 无操作 */ }
            }
        }

        /** 复刻 :107-113（ACTION_SCREEN_ON）。 */
        fun onScreenOn() {
            isScreenOn = true
            if (!isPaused) startScreenTiming()
        }

        /** 复刻 :114-118（ACTION_SCREEN_OFF）。 */
        fun onScreenOff() {
            isScreenOn = false
            pauseTiming()
        }

        /** 复刻 :87-101：先 pauseTiming() 再 saveState()。 */
        fun onDestroy() {
            timerActive = false
            pauseTiming()
            saveState()
        }

        // --- UI 侧动作 ---------------------------------------------------

        /** SettingsActivity.kt:64-78：写 prefs=false 后发一个 action=null 的 start 请求。 */
        fun tapStartService() {
            prefsPaused = false
            onStartCommand(null)
        }

        /** SettingsActivity.kt:80-92 / QuizActivity.kt:408-419（家长退出）。 */
        fun tapPauseOrParentExit() {
            onStartCommand(Constants.ACTION_PAUSE)
            prefsPaused = true
        }

        /** SettingsActivity.kt:202-216：UI 用 prefs 的 paused 判断"运行中"。 */
        fun uiStatusShowsRunning(): Boolean = !prefsPaused
    }

    private companion object {
        const val MIN = 60_000L
    }

    // ------------------------------------------------------------------
    // D07 核心：连续到达的 RESUME / SCREEN_ON 不得重复累加或丢失
    // ------------------------------------------------------------------

    @Test
    fun repeatedStartScreenTiming_settlesEachWindowExactlyOnce() {
        val s = ServiceModel()
        s.onCreate()                       // t0 开始计时
        s.advance(3 * MIN)
        s.onStartCommand(Constants.ACTION_RESUME) // 结算 3min
        s.advance(2 * MIN)
        s.onScreenOn()                     // 结算 2min
        s.advance(1 * MIN)
        s.onScreenOff()                    // 结算 1min

        assertEquals("累计亮屏时间应恰好等于 6 分钟", 6 * MIN, s.accumulated)
        assertEquals(listOf(3 * MIN, 2 * MIN, 1 * MIN), s.settlements)
        assertEquals("期间不应触发答题", 0, s.quizLaunches)
        assertEquals(0L, s.screenOnStartMs)
    }

    @Test
    fun resumeImmediatelyFollowedByScreenOn_doesNotDoubleCount() {
        val s = ServiceModel()
        s.onCreate()
        s.advance(5 * MIN)
        // 两个事件在同一时刻到达：第一次结算 5min，第二次结算 0
        s.onStartCommand(Constants.ACTION_RESUME)
        s.onScreenOn()

        assertEquals(5 * MIN, s.accumulated)
        assertEquals(5 * MIN, s.settlements.sum())
        assertEquals(0L, s.settlements[1]) // 第二次窗口结算量为 0，无重复累加
        assertEquals(0, s.quizLaunches)
    }

    @Test
    fun screenOffThenOn_settlesWithoutLossOrDuplication() {
        val s = ServiceModel()
        s.onCreate()
        s.advance(4 * MIN)
        s.onScreenOff()        // 结算 4min 并把 marker 归零
        s.onScreenOn()         // marker 为 0，不重复结算
        s.advance(4 * MIN)
        s.onScreenOff()

        assertEquals(8 * MIN, s.accumulated)
        assertEquals(listOf(4 * MIN, 4 * MIN), s.settlements)
    }

    @Test
    fun onDestroy_settlesOpenWindowBeforePersisting() {
        val s = ServiceModel()
        s.onCreate()
        s.advance(7 * MIN)
        s.onDestroy()

        assertEquals(7 * MIN, s.accumulated)
        assertEquals("saveState 必须在 pauseTiming 之后，持久化结算后的值", 7 * MIN, s.prefsAccumulated)
        assertEquals(0L, s.screenOnStartMs)
    }

    // ------------------------------------------------------------------
    // D06 核心：onCreate 读回累计值后的阈值行为
    // ------------------------------------------------------------------

    @Test
    fun restoredAccumulatedAtInterval_triggersQuizImmediately() {
        val s = ServiceModel(prefsAccumulated = Constants.QUIZ_INTERVAL_MS)
        s.onCreate()

        assertEquals(1, s.quizLaunches)
        assertEquals(0L, s.accumulated)
        assertEquals(0L, s.screenOnStartMs)
        assertFalse(s.timerActive)
    }

    @Test
    fun restoredAccumulatedAboveInterval_alsoTriggersImmediatelyAndDiscardsOverage() {
        val s = ServiceModel(prefsAccumulated = Constants.QUIZ_INTERVAL_MS + 5 * MIN)
        s.onCreate()

        assertEquals(1, s.quizLaunches)
        assertEquals(0L, s.accumulated)
        assertFalse(s.timerActive)
    }

    @Test
    fun restoredAccumulatedJustBelowInterval_schedulesOnlyRemainingTime() {
        val s = ServiceModel(prefsAccumulated = Constants.QUIZ_INTERVAL_MS - 10_000L)
        s.onCreate()

        assertEquals(0, s.quizLaunches)
        assertTrue(s.timerActive)
        assertEquals(10_000L, s.timerRemainingMs)
        s.advance(10_000L)
        assertEquals(1, s.quizLaunches)
    }

    @Test
    fun pauseDropsCurrentWindowButKeepsEarlierAccumulatedTime() {
        val s = ServiceModel()
        s.onCreate()
        s.advance(2 * MIN)
        s.onScreenOff()                       // 结算 2min
        s.onScreenOn()
        s.advance(4 * MIN)
        s.tapPauseOrParentExit()              // stopTiming 丢弃这 4min

        assertEquals(2 * MIN, s.accumulated)
        assertEquals(2 * MIN, s.prefsAccumulated)
        assertTrue(s.isPaused)
    }

    // ------------------------------------------------------------------
    // 缺陷证据（Characterization）—— 详见交付报告 S1 / M1 / M2
    // ------------------------------------------------------------------

    @Test
    fun KNOWN_GAP_tapStartServiceAfterPause_neverClearsInMemoryPausedFlag() {
        val s = ServiceModel()
        s.onCreate()                          // 服务已运行
        s.tapPauseOrParentExit()               // 家长退出 / 点"停止服务"：isPaused=true
        assertTrue(s.isPaused)

        s.tapStartService()                    // 点"启动服务"：prefs=false + action=null
        assertFalse("UI 写回了 prefs=false", s.prefsPaused)
        assertTrue(
            "服务仍存活时不会重走 onCreate，内存 isPaused 未被清除",
            s.isPaused
        )

        s.advance(30 * MIN)
        s.onScreenOn()                         // 亮屏事件被 isPaused 拦下
        assertEquals("暂停后点启动服务，30 分钟内一次题都不弹", 0, s.quizLaunches)
        assertTrue("UI 却显示「运行中」", s.uiStatusShowsRunning())

        // 对照：显式 ACTION_RESUME 才能恢复（这正是建议改法的依据）
        s.onStartCommand(Constants.ACTION_RESUME)
        s.advance(Constants.QUIZ_INTERVAL_MS)
        assertEquals(1, s.quizLaunches)
    }

    @Test
    fun KNOWN_GAP_actionResume_persistsStaleAccumulatedBeforeSettling() {
        val s = ServiceModel()
        s.onCreate()
        s.advance(4 * MIN)                    // 亮屏 4min，尚未结算，accumulated 仍为 0

        s.onStartCommand(Constants.ACTION_RESUME) // saveState() 在 startScreenTiming() 之前

        assertEquals(0L, s.prefsAccumulated)  // 存盘值陈旧
        assertEquals(4 * MIN, s.accumulated)  // 内存值已结算
        assertTrue("此刻进程被杀将丢失这 4 分钟", s.prefsAccumulated < s.accumulated)
    }

    @Test
    fun KNOWN_GAP_hardKillWithoutOnDestroy_losesAllAccumulatedTime() {
        val running = ServiceModel()
        running.onCreate()
        running.advance(9 * MIN)
        running.onScreenOff()                 // 结算 9min（仅内存）
        assertEquals(9 * MIN, running.accumulated)
        assertEquals("运行期间从未调用 saveState()", 0L, running.prefsAccumulated)

        // 模拟进程被 OOM killer / force-stop 杀死后重启（不会回调 onDestroy）
        val restarted = ServiceModel(prefsAccumulated = running.prefsAccumulated)
        restarted.onCreate()
        assertEquals(
            "重启后白得一个完整的 10 分钟窗口",
            Constants.QUIZ_INTERVAL_MS,
            restarted.timerRemainingMs
        )
    }

    @Test
    fun KNOWN_GAP_afterTriggerQuiz_noFurtherQuizWhileScreenStaysOn() {
        val s = ServiceModel(prefsAccumulated = Constants.QUIZ_INTERVAL_MS)
        s.onCreate()
        assertEquals(1, s.quizLaunches)
        assertFalse("triggerQuiz 后 timer 已停且 screenOnStartMs 归零", s.timerActive)
        assertEquals(0L, s.screenOnStartMs)

        // 答题被绕过 / Activity 被回收，始终没有 ACTION_RESUME：
        s.advance(30 * MIN)
        assertEquals("屏幕持续亮 30 分钟却不再弹题", 1, s.quizLaunches)

        // 只有 QuizActivity 答对后显式发 ACTION_RESUME 才恢复计时
        s.onStartCommand(Constants.ACTION_RESUME)
        s.advance(Constants.QUIZ_INTERVAL_MS)
        assertEquals(2, s.quizLaunches)
    }
}
