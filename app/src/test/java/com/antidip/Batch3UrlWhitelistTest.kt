package com.antidip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 3（A30）：[UrlValidator.isAcceptable] 传输策略的纯 JVM 单元测试。
 *
 * 策略：https 一律放行；明文 HTTP 仅允许「不可能是公网」的 host
 * （回环 / RFC1918 私网 IPv4 / 169.254 链路本地 / 单标签主机名 /
 * .local .lan .internal .home / IPv6 回环 [::1]）；公网 http 一律拒绝，
 * 否则题库可被中间人整体替换。
 *
 * [UrlValidator.isValid] 的结构规则（2-b M4）不在本文件重复——本文件只验
 * 「结构合法但策略拒绝 / 策略放行」的分界，并守卫 [Constants.DEFAULT_QUIZ_URL]。
 */
class Batch3UrlWhitelistTest {

    private fun assertAccepted(raw: String) {
        assertTrue("[$raw] 应当通过传输策略", UrlValidator.isAcceptable(raw))
    }

    private fun assertRejected(raw: String) {
        assertFalse("[$raw] 应当被传输策略拒绝", UrlValidator.isAcceptable(raw))
    }

    // ------------------------------------------------------------------
    // 守卫：默认局域网地址必须保持可用
    // ------------------------------------------------------------------

    @Test
    fun defaultQuizUrl_stillAcceptable() {
        assertTrue(
            "默认题库地址应为 http 明文私网地址",
            Constants.DEFAULT_QUIZ_URL.startsWith("http://")
        )
        assertAccepted(Constants.DEFAULT_QUIZ_URL)
    }

    // ------------------------------------------------------------------
    // https 一律放行（公网也放行）
    // ------------------------------------------------------------------

    @Test
    fun https_isAlwaysAcceptable_regardlessOfHost() {
        assertAccepted("https://example.com/questions.json")
        assertAccepted("https://192.168.1.100:8080/questions.json")
        assertAccepted("https://qbank.example-cdn.com/v2/bank.json")
    }

    // ------------------------------------------------------------------
    // http：私网 / 本地 host 放行
    // ------------------------------------------------------------------

    @Test
    fun http_privateIpv4_isAccepted() {
        assertAccepted("http://192.168.1.50:8080/q.json") // 192.168/16
        assertAccepted("http://10.0.0.2/q.json")          // 10/8
        assertAccepted("http://172.16.0.1/q.json")        // 172.16/12 下界
        assertAccepted("http://172.31.255.255/q.json")    // 172.16/12 上界
        assertAccepted("http://127.0.0.1:9000/q.json")    // 回环
        assertAccepted("http://169.254.10.20/q.json")     // 链路本地
    }

    @Test
    fun http_localHostnameForms_areAccepted() {
        assertAccepted("http://localhost:8080/questions.json")
        assertAccepted("http://mypc:8080/questions.json")       // 单标签（LAN 主机名）
        assertAccepted("http://nas.local/q.json")               // mDNS
        assertAccepted("http://server.lan/q.json")
        assertAccepted("http://bank.internal/q.json")
        assertAccepted("http://box.home/q.json")
    }

    @Test
    fun http_ipv6Loopback_isAccepted() {
        assertAccepted("http://[::1]:8080/questions.json")
    }

    // ------------------------------------------------------------------
    // http：公网 host 拒绝（必须 https）
    // ------------------------------------------------------------------

    @Test
    fun http_publicDomains_areRejected() {
        assertRejected("http://example.com/questions.json")
        assertRejected("http://qbank.example.com:8080/q.json")
        assertRejected("http://foo.bar.internal.example.com/q.json") // 只是包含 internal 字样
    }

    @Test
    fun http_publicIpv4_isRejected() {
        assertRejected("http://8.8.8.8/q.json")
        assertRejected("http://1.1.1.1:8080/q.json")
        assertRejected("http://172.32.0.1/q.json")  // 172.16/12 之外
        assertRejected("http://172.15.0.1/q.json")  // 172.16/12 之外
        assertRejected("http://192.169.1.1/q.json") // 不是 192.168/16
        assertRejected("http://999.1.1.1/q.json")   // 非法八位组 → 按公网域名拒绝
    }

    @Test
    fun http_publicIpv6_isRejected() {
        assertRejected("http://[2001:db8::1]/q.json")
        assertRejected("http://[fe80::1]/q.json") // 链路本地 IPv6 也不放行（保守）
    }

    // ------------------------------------------------------------------
    // 结构非法的 URL：与 isValid 同口径拒绝（复用 2-b 契约）
    // ------------------------------------------------------------------

    @Test
    fun structurallyInvalidUrls_stayRejected() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("not a url")
        assertRejected("ftp://192.168.1.1/q.json")
        assertRejected("http://user:pass@192.168.1.1/q.json")
        assertRejected("http://192.168.1.1:99999/q.json")
        assertRejected("http://exa mple.com/q.json")
    }

    // ------------------------------------------------------------------
    // isPrivateOrLocalHost：直测边界
    // ------------------------------------------------------------------

    @Test
    fun hostClassifier_boundaries() {
        assertTrue(UrlValidator.isPrivateOrLocalHost("192.168.0.0"))
        assertTrue(UrlValidator.isPrivateOrLocalHost("192.168.255.255"))
        assertFalse(UrlValidator.isPrivateOrLocalHost("192.167.0.1"))
        assertTrue(UrlValidator.isPrivateOrLocalHost("localhost"))
        assertFalse(UrlValidator.isPrivateOrLocalHost("evil.com"))
        assertFalse(UrlValidator.isPrivateOrLocalHost("localhost.evil.com"))
        assertFalse(UrlValidator.isPrivateOrLocalHost(""))
        assertFalse(UrlValidator.isPrivateOrLocalHost("[fe80::1]"))
    }
}
