package com.antidip

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次3 · 通话优先（需求 2026-11-08）：答题不得在通话中弹出；通话优先级最高，
 * 覆盖系统电话与微信语音/视频等一切 VoIP 通话；微信小程序、游戏、视频号等
 * 普通媒体场景**不在**通话优先范围内，照常出题。
 *
 * [CallPriorityDetector.isCallAudioMode] 是纯 JVM 映射，直接单测；
 * [CallPriorityDetector.isInCall] 的 fail-closed 分支（AudioManager 缺失 /
 * 异常 → 不在通话）依赖 Android 框架，按项目约定不做 Robolectric，列为已知
 * 局限（与 QuestionBankManager 解析同样的处理）。
 */
class Batch3CallPriorityTest {

    private fun assertCall(mode: Int) {
        assertTrue("音频模式 $mode 应判定为通话中", CallPriorityDetector.isCallAudioMode(mode))
    }

    private fun assertNotCall(mode: Int) {
        assertFalse("音频模式 $mode 不应判定为通话中", CallPriorityDetector.isCallAudioMode(mode))
    }

    // ------------------------------------------------------------------
    // 通话路径：全部命中（音频模式常量值同时用字面量钉死，防 API 变动）
    // ------------------------------------------------------------------

    @Test
    fun callModes_areAllDetected() {
        assertCall(AudioManager.MODE_RINGTONE)                 // 1: 系统来电响铃
        assertTrue(AudioManager.MODE_RINGTONE == 1)
        assertCall(AudioManager.MODE_IN_CALL)                  // 2: 系统电话通话中
        assertTrue(AudioManager.MODE_IN_CALL == 2)
        assertCall(AudioManager.MODE_IN_COMMUNICATION)         // 3: VoIP（微信语音/视频等）
        assertTrue(AudioManager.MODE_IN_COMMUNICATION == 3)
        assertCall(AudioManager.MODE_CALL_SCREENING)           // 4: 来电筛选
        assertTrue(AudioManager.MODE_CALL_SCREENING == 4)
        assertCall(AudioManager.MODE_CALL_REDIRECT)            // 5: 通话重定向
        assertTrue(AudioManager.MODE_CALL_REDIRECT == 5)
        assertCall(AudioManager.MODE_COMMUNICATION_REDIRECT)   // 6: 通信重定向
        assertTrue(AudioManager.MODE_COMMUNICATION_REDIRECT == 6)
    }

    // ------------------------------------------------------------------
    // 非通话：普通媒体消费不得享受通话优先
    // ------------------------------------------------------------------

    @Test
    fun normalMode_isNotACall_soMiniProgramsAndGamesStillTriggerQuizzes() {
        // 微信聊天/小程序/游戏/视频号都在 MODE_NORMAL 消费媒体——不拦截出题。
        assertNotCall(AudioManager.MODE_NORMAL)
        assertTrue(AudioManager.MODE_NORMAL == 0)
    }

    @Test
    fun unknownModes_failClosed_toNotACall() {
        // 未知/非法值不判通话：检测失败只能让题目照常弹出（更严），
        // 绝不能反向成为豁免后门。
        assertNotCall(-1)
        assertNotCall(7)
        assertNotCall(99)
        assertNotCall(Int.MIN_VALUE)
        assertNotCall(Int.MAX_VALUE)
    }

    // ------------------------------------------------------------------
    // 映射完备性：0..6 每个值都有明确结论（无遗漏分支）
    // ------------------------------------------------------------------

    @Test
    fun mapping_coversAllDocumentedModes_exactly() {
        val expected = setOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT
        )
        for (mode in -2..8) {
            val isCall = CallPriorityDetector.isCallAudioMode(mode)
            if (mode in expected) {
                assertTrue("模式 $mode 应为通话", isCall)
            } else {
                assertFalse("模式 $mode 应为非通话", isCall)
            }
        }
    }
}
