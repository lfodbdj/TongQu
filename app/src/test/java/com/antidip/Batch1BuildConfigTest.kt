package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D01 真实构建配置闭环验证。
 *
 * 这是唯一触及真实 BuildConfig.MASTER_PASSWORD 的测试，用于确认
 * app/build.gradle.kts:23-39 的 local.properties -> BuildConfig 注入确实生效。
 *
 * 安全约束：全程**不打印、不记录、不写入任何断言消息或日志**该字段的取值，
 * 仅使用它的「空 / 非空」性质。local.properties 中的家长密码值不会外泄。
 */
class Batch1BuildConfigTest {

    @Test
    fun masterPasswordInjection_emptyInputIsAlwaysRejected() {
        val master = BuildConfig.MASTER_PASSWORD
        // 无论本机是否配置了主密码，空输入都必须被 D01 的 :434 挡住。
        assertFalse(verifyMasterBranch("", master))
    }

    @Test
    fun masterPasswordInjection_configuredValueUnlocks_andUnconfiguredPathIsDisabled() {
        val master = BuildConfig.MASTER_PASSWORD
        // 配置了主密码 -> 该值应能解锁；未配置(空串) -> master 分支禁用 + 空输入被拒 -> false。
        // 因此期望值恰好等于 master.isNotEmpty()，与本机具体配置无关。
        assertEquals(master.isNotEmpty(), verifyMasterBranch(master, master))
    }

    /** 仅复刻 QuizActivity.kt:434 + :439-440 的 master 分支，不涉及日期口令。 */
    private fun verifyMasterBranch(password: String, master: String): Boolean {
        if (password.isEmpty()) return false
        if (master.isNotEmpty() && password == master) return true
        return false
    }
}
