package com.antidip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * D01 回归验证：QuizActivity.verifyParentPassword() (QuizActivity.kt:431-456)
 * 的纯 JVM 规格模型。
 *
 * 说明：QuizActivity 依赖 Android Context，无法在 testDebugUnitTest（无 Robolectric）
 * 中实例化。因此这里把被验证的口令判定逻辑**逐行复刻**为可注入 master / clock 的
 * 纯函数，用于：
 *   1) 锁定 D01 修复后的正确行为（回归锁）；
 *   2) 暴露边界（跨月/跨年、非 ASCII Locale、空主密码）。
 *
 * 本测试**不读取也不打印** local.properties / BuildConfig.MASTER_PASSWORD 的真实取值，
 * master 一律使用本地测试常量注入。Android 侧真实 BuildConfig 注入效果需真机验证。
 */
class Batch1ParentPasswordTest {

    /**
     * 逐行复刻 QuizActivity.kt:431-456。
     * master 与 now 可注入，以便在不接触真实配置的前提下穷举分支。
     */
    private fun verifyParentPassword(
        password: String,
        master: String,
        now: Calendar = Calendar.getInstance(Locale.US)
    ): Boolean {
        if (password.isEmpty()) return false                       // :434
        if (master.isNotEmpty() && password == master) return true // :439-440
        return try {
            val month = now.get(Calendar.MONTH) + 1                // :447
            val day = now.get(Calendar.DAY_OF_MONTH)               // :448
            val reversedDate = String.format(Locale.ROOT, "%02d%02d", month, day).reversed() // :449
            password == reversedDate                               // :450
        } catch (e: Exception) {
            false
        }
    }

    private fun date(year: Int, month1Based: Int, day: Int): Calendar =
        Calendar.getInstance(Locale.US).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month1Based - 1)
            set(Calendar.DAY_OF_MONTH, day)
        }

    // ------------------------------------------------------------------
    // D01 核心：空输入 + 空主密码不得放行（旧代码 "" == "" 会误放行）
    // ------------------------------------------------------------------

    @Test
    fun emptyInput_isRejected_whenMasterIsUnset() {
        assertFalse(verifyParentPassword("", master = ""))
    }

    @Test
    fun emptyInput_isRejected_evenWhenMasterIsConfigured() {
        assertFalse(verifyParentPassword("", master = UNIT_TEST_MASTER))
    }

    @Test
    fun unconfiguredMaster_disablesMasterPathEntirely() {
        assertFalse(verifyParentPassword("anything", master = ""))
        assertFalse(verifyParentPassword("0000", master = ""))
    }

    @Test
    fun configuredMaster_acceptsExactMatchOnly() {
        assertTrue(verifyParentPassword(UNIT_TEST_MASTER, master = UNIT_TEST_MASTER))
        assertFalse(verifyParentPassword(UNIT_TEST_MASTER.dropLast(1), master = UNIT_TEST_MASTER))
        assertFalse(verifyParentPassword("$UNIT_TEST_MASTER ", master = UNIT_TEST_MASTER))
        assertFalse(verifyParentPassword(UNIT_TEST_MASTER.uppercase(), master = UNIT_TEST_MASTER))
    }

    @Test
    fun masterPassword_takesPrecedence_whenItCollidesWithDatePassword() {
        // 即使 master 恰好等于某天的日期口令，也应走 master 分支直接放行。
        assertTrue(verifyParentPassword("1010", master = "1010", now = date(2026, 1, 1)))
    }

    // ------------------------------------------------------------------
    // 日期口令：MMDD 倒写，跨月 / 跨年 / 闰日 / 前导零边界
    // ------------------------------------------------------------------

    @Test
    fun datePassword_jan1_isReversed0101() {
        val jan1 = date(2026, 1, 1)
        assertTrue(verifyParentPassword("1010", master = "", now = jan1))
        assertFalse(verifyParentPassword("0101", master = "", now = jan1))
    }

    @Test
    fun datePassword_dec31_isReversed1231() {
        val dec31 = date(2026, 12, 31)
        assertTrue(verifyParentPassword("1321", master = "", now = dec31))
        assertFalse(verifyParentPassword("1231", master = "", now = dec31))
    }

    @Test
    fun datePassword_leapDay_isReversed0229() {
        assertTrue(verifyParentPassword("9220", master = "", now = date(2028, 2, 29)))
    }

    @Test
    fun datePassword_crossYearBoundary_switchesWithMonthDayOnly() {
        val dec31 = date(2026, 12, 31)
        val jan1 = date(2027, 1, 1)
        assertTrue(verifyParentPassword("1321", master = "", now = dec31))
        assertTrue(verifyParentPassword("1010", master = "", now = jan1))
        // 跨年后旧口令立即失效。
        assertFalse(verifyParentPassword("1321", master = "", now = jan1))
    }

    @Test
    fun datePassword_preservesLeadingZeroAndPalindrome() {
        // 10/10 -> "1010".reversed() == "0101"（前导零必须保留）
        assertTrue(verifyParentPassword("0101", master = "", now = date(2026, 10, 10)))
        // 11/11 -> "1111" 回文
        assertTrue(verifyParentPassword("1111", master = "", now = date(2026, 11, 11)))
        // 9/9 -> "0909".reversed() == "9090"
        assertTrue(verifyParentPassword("9090", master = "", now = date(2026, 9, 9)))
    }

    @Test
    fun datePassword_doesNotAcceptRawMmdd() {
        assertFalse(verifyParentPassword("0101", master = "", now = date(2026, 1, 1)))
        assertFalse(verifyParentPassword("1231", master = "", now = date(2026, 12, 31)))
    }

    // ------------------------------------------------------------------
    // Locale：验证 Locale.ROOT 生效、Calendar 月日在佛历下不受影响
    // ------------------------------------------------------------------

    @Test
    fun datePassword_staysAsciiUnderNonAsciiDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            // 阿拉伯语 Locale 下 %d 会输出非 ASCII 数字；Locale.ROOT 必须隔离它。
            Locale.setDefault(Locale("ar", "EG"))
            val formatted = String.format(Locale.ROOT, "%02d%02d", 1, 1)
            assertTrue("Locale.ROOT must produce ASCII digits, got [$formatted]", formatted == "0101")
            assertTrue(verifyParentPassword("1010", master = "", now = date(2026, 1, 1)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun datePassword_buddhistCalendarStillUsesGregorianMonthAndDay() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val gregorian = Calendar.getInstance()
            Locale.setDefault(Locale("th", "TH"))
            val buddhist = Calendar.getInstance()
            // th-TH 默认返回 BuddhistCalendar（年份 +543），但 MONTH/DAY_OF_MONTH 与公历一致，
            // 因此 MMDD 倒写口令不会因设备区域设置而漂移。
            val gregorianMmdd = String.format(
                Locale.ROOT, "%02d%02d",
                gregorian.get(Calendar.MONTH) + 1, gregorian.get(Calendar.DAY_OF_MONTH)
            )
            val buddhistMmdd = String.format(
                Locale.ROOT, "%02d%02d",
                buddhist.get(Calendar.MONTH) + 1, buddhist.get(Calendar.DAY_OF_MONTH)
            )
            assertTrue("MMDD must be locale-independent", gregorianMmdd == buddhistMmdd)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun datePassword_todayProducesAReversibleFourDigitToken() {
        val now = Calendar.getInstance(Locale.US)
        val token = String.format(
            Locale.ROOT, "%02d%02d",
            now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        ).reversed()
        assertTrue(verifyParentPassword(token, master = "", now = now))
        assertTrue(token.all { it in '0'..'9' })
    }

    private companion object {
        const val UNIT_TEST_MASTER = "qa-only-master-value"
    }
}
