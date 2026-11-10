package com.antidip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 批次 2-b：[ParentAuth.verify] 的纯 JVM 单元测试。
 *
 * 与批次 1 的「规格模型」不同，本文件**直接调用生产类**，期望值全部手工推算，
 * 不复制实现逻辑，因此能真实守住 D01 / 口令语义：
 *   - 空密码恒 false（master 未配置为空串时 "" == "" 会误开家长模式，D01）；
 *   - 仅当 master.isNotEmpty() 时才比较 password == master；
 *   - 日期口令 = String.format(Locale.ROOT, "%02d%02d", month, day).reversed()；
 *   - L1：先对 password 做 trim 再判空/比较（纯空白必拒，带前后空格的口令可放行），
 *     master 本身不 trim、保持逐字匹配。
 *
 * 日期口令期望值均为手算常量（不调用 String.format 复算），避免「测试跟着实现一起错」：
 *   11/7  -> "1107".reversed() = "7011"
 *   1/1   -> "0101".reversed() = "1010"
 *   12/25 -> "1225".reversed() = "5221"
 *   3/9   -> "0309".reversed() = "9030"   （顺带验证 %02d 补零）
 *
 * 本文件不读取也不打印 local.properties / BuildConfig.MASTER_PASSWORD 的真实取值；
 * master 一律由测试常量注入。
 */
class Batch2ParentAuthTest {

    private companion object {
        const val MASTER = "12345678"
        const val SYMBOL_MASTER = "Abc-123"
    }

    // ------------------------------------------------------------------
    // D01：空密码 / 空 master 不得放行
    // ------------------------------------------------------------------

    @Test
    fun emptyPassword_isRejected_whenMasterUnset() {
        assertFalse(ParentAuth.verify("", "", 11, 7))
    }

    @Test
    fun emptyPassword_isRejected_whenMasterConfigured() {
        assertFalse(ParentAuth.verify("", MASTER, 11, 7))
    }

    @Test
    fun unconfiguredMaster_disablesMasterPath_evenForTheFormerMasterValue() {
        // D01 核心：master 未配置时，旧的万能密码值不得再解锁。
        assertFalse(ParentAuth.verify(MASTER, "", 11, 7))
    }

    // ------------------------------------------------------------------
    // master 分支
    // ------------------------------------------------------------------

    @Test
    fun configuredMaster_exactMatch_isAccepted() {
        assertTrue(ParentAuth.verify(MASTER, MASTER, 11, 7))
    }

    @Test
    fun configuredMaster_wrongPassword_isRejected() {
        assertFalse(ParentAuth.verify("12345679", MASTER, 11, 7))
        assertFalse(ParentAuth.verify("not-the-master", MASTER, 11, 7))
    }

    @Test
    fun masterWithLettersAndSymbols_isAccepted_requiresTextPasswordInputType() {
        // 这是把 res/layout/dialog_parent_exit.xml 的
        // android:inputType="numberPassword" 改成 "textPassword" 的直接原因：
        // 纯数字键盘无法输入含字母/连字符的主密码，家长会被永久锁在外面。
        assertTrue(
            "含字母与符号的主密码必须可通过（需 textPassword 输入框）",
            ParentAuth.verify(SYMBOL_MASTER, SYMBOL_MASTER, 3, 9)
        )
    }

    @Test
    fun masterComparison_isExact_notCaseInsensitive() {
        // 规格明确 password == master 为精确比较；大小写不同不得放行。
        // 首尾空格的 trim 行为由下方 L1 专项用例覆盖。
        assertFalse(ParentAuth.verify("abc-123", SYMBOL_MASTER, 3, 9))
    }

    // ------------------------------------------------------------------
    // 日期口令：MMDD 倒写（手算常量）
    // ------------------------------------------------------------------

    @Test
    fun datePassword_nov7_isReversed7011_notRaw1107() {
        assertTrue(ParentAuth.verify("7011", "", 11, 7))
        assertFalse(ParentAuth.verify("1107", "", 11, 7))
    }

    @Test
    fun datePassword_jan1_isReversed1010() {
        assertTrue(ParentAuth.verify("1010", "", 1, 1))
        assertFalse(ParentAuth.verify("0101", "", 1, 1))
    }

    @Test
    fun datePassword_dec25_isReversed5221() {
        assertTrue(ParentAuth.verify("5221", "", 12, 25))
        assertFalse(ParentAuth.verify("1225", "", 12, 25))
    }

    @Test
    fun datePassword_singleDigitMonthAndDay_areZeroPadded() {
        // 3/9 -> "0309" -> "9030"；若实现漏掉 %02d 会得到 "39" -> "93"。
        assertTrue(ParentAuth.verify("9030", "", 3, 9))
        assertFalse(ParentAuth.verify("93", "", 3, 9))
    }

    @Test
    fun datePassword_isDateBound_aTokenForAnotherDayIsRejected() {
        assertFalse("1/1 的口令不能用于 11/7", ParentAuth.verify("1010", "", 11, 7))
        assertFalse("12/25 的口令不能用于 1/1", ParentAuth.verify("5221", "", 1, 1))
    }

    @Test
    fun datePassword_stillWorks_whenMasterIsConfigured() {
        // master 与日期口令同时有效：输入日期口令也应放行（不能因 master 存在就短路）。
        assertTrue(ParentAuth.verify("7011", MASTER, 11, 7))
    }

    @Test
    fun datePassword_stillWorks_whenMasterIsUnset() {
        // master 被禁用时，日期口令仍是唯一可用的家长通道。
        assertTrue(ParentAuth.verify("7011", "", 11, 7))
    }

    @Test
    fun datePassword_usesAsciiDigits_underNonAsciiDefaultLocale() {
        // Locale.ROOT 必须隔离设备区域设置：阿拉伯语默认 Locale 下 %d 会输出非 ASCII 数字，
        // 若实现改用默认 Locale，家长输入的手算 ASCII 口令将永远无法匹配。
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertTrue(ParentAuth.verify("1010", "", 1, 1))
            assertTrue(ParentAuth.verify("7011", "", 11, 7))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun arbitraryWrongPasswords_areAllRejected() {
        val wrong = listOf("0000", "1111", "abcd", " ", "701", "70110", "1107", "5221")
        for (candidate in wrong) {
            assertFalse("错误密码 [$candidate] 不应放行", ParentAuth.verify(candidate, "", 11, 7))
            assertFalse(
                "错误密码 [$candidate] 在 master 已配置时也不应放行",
                ParentAuth.verify(candidate, MASTER, 11, 7)
            )
        }
    }
}
