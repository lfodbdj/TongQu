package com.antidip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 2-b：[UrlValidator.isValid] 的纯 JVM 单元测试。
 *
 * 直接调用生产类。M4 把设置页/下载器里散落的 URL 校验收敛到单一入口，
 * 本文件用派单给定的「必须通过 / 必须拒绝」清单作为验收契约。
 *
 * 特别地，[Constants.DEFAULT_QUIZ_URL] 是 http 明文 LAN 地址，真机依赖它验证
 * network_security_config 的 cleartext 放行；校验规则一旦误伤该默认地址，
 * 「默认地址能否成功更新题库」的真机验收项就失去意义，故单独设一条守卫。
 *
 * 本文件刻意**不**断言派单未定义的行为（大写 scheme、首尾空格是否 trim、
 * 裸 host、query string、长度上限），避免在 2-b 落地口径未定前制造假失败；
 * 这些开放项已在回传报告中列为待确认。
 */
class Batch2UrlValidatorTest {

    private fun assertAccepted(raw: String) {
        assertTrue("[$raw] 应当通过校验", UrlValidator.isValid(raw))
    }

    private fun assertRejected(raw: String) {
        assertFalse("[$raw] 应当被拒绝", UrlValidator.isValid(raw))
    }

    // ------------------------------------------------------------------
    // 必须通过
    // ------------------------------------------------------------------

    @Test
    fun defaultQuizUrl_mustBeAccepted_soLanCleartextVerificationStillWorks() {
        // 守卫：默认地址必须是 http 明文 LAN 地址，且必须通过新校验规则。
        assertTrue(
            "默认题库地址应为 http 明文（真机 cleartext 验收依赖它）",
            Constants.DEFAULT_QUIZ_URL.startsWith("http://")
        )
        assertAccepted(Constants.DEFAULT_QUIZ_URL)
    }

    @Test
    fun requiredValidUrls_allAccepted() {
        assertAccepted("http://192.168.1.101:8080/questions.json") // valid http LAN URL (accepted by validator)
        assertAccepted("https://example.com/questions.json")
        assertAccepted("http://192.168.1.1/q.json")
    }

    @Test
    fun httpsLanUrlWithPort_isAccepted() {
        // 与默认地址同构、仅换 https，用于确认端口解析不会误伤合法地址。
        assertAccepted("https://192.168.1.101:8080/questions.json")
    }

    // ------------------------------------------------------------------
    // 必须拒绝
    // ------------------------------------------------------------------

    @Test
    fun emptyAndBlankStrings_areRejected() {
        assertRejected("")
        assertRejected("   ")
    }

    @Test
    fun inputWithoutScheme_isRejected() {
        assertRejected("not a url")
        assertRejected("192.168.1.101:8080/questions.json")
        assertRejected("example.com/questions.json")
    }

    @Test
    fun nonHttpSchemes_areRejected() {
        assertRejected("ftp://example.com/q.json")
        assertRejected("file:///sdcard/questions.json")
        assertRejected("javascript:alert(1)")
    }

    @Test
    fun hostlessHttpUrls_areRejected() {
        assertRejected("http://")
        assertRejected("https://")
        assertRejected("http:///path")
    }

    @Test
    fun embeddedCredentials_areRejected() {
        assertRejected("http://user:pass@example.com/q.json")
        assertRejected("http://user@example.com/q.json")
    }

    @Test
    fun outOfRangePort_isRejected() {
        assertRejected("http://example.com:99999/q.json")
    }

    @Test
    fun whitespaceInsideUrl_isRejected() {
        assertRejected("http://example.com/a b.json")
        assertRejected("http://exa mple.com/q.json")
    }

    @Test
    fun malformedInputs_neverThrow_onlyReturnFalse() {
        // 校验入口必须把 MalformedURLException / 解析异常收敛为 false，
        // 不能把异常抛给设置页点击回调（D09 的历史问题）。
        val malformed = listOf(
            "", "   ", "not a url", "://missing", ":", "::::", "\u0000",
            "ftp://example.com/q.json", "http://", "http:///path",
            "http://user:pass@example.com/q.json",
            "http://example.com:99999/q.json",
            "http://example.com/a b.json"
        )
        for (raw in malformed) {
            assertRejected(raw)
        }
    }
}
