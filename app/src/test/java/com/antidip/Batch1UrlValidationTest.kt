package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * D05 / D09 回归验证：题库下载地址的保存校验与下载URL 构造前置条件。
 *
 * SettingsActivity 与 QuizDownloader 需要 Android Context，无法在纯 JVM 单测中实例化
 * （本项目无 Robolectric / mockito，见 app/build.gradle.kts 仅 junit 4.13.2）。因此本文件
 * 把 SettingsActivity.kt:135-158 的校验逻辑与 QuizDownloader.kt:71-74 的回退逻辑
 * **逐行复刻**为纯函数，配合真实的 java.net.URL（JDK 类，非 android.jar stub）做行为验证。
 *
 * 测试分两类：
 *   - 正常回归断言：非法 scheme 被拒、合法 http/https 与空串被放行、默认地址可用；
 *   - Characterization 断言（方法名以 KNOWN_GAP_ 开头）：锁定**当前实际行为**，
 *     作为缺陷报告的可复现证据，不代表期望行为。缺陷详见交付报告「新发现的问题」。
 *
 * 注意：java.net.URL 在桌面 JDK（本测试运行环境）与 Android libcore 上对大写 scheme
 * 的大小写归一化可能不同，相关结论已在报告中标注「需真机验证」。
 */
class Batch1UrlValidationTest {

    /** 逐行复刻 SettingsActivity.kt:135-154：返回 true 表示允许写入 SharedPreferences。 */
    private fun isAcceptedForSave(raw: String): Boolean {
        val url = raw.trim()
        if (url.isEmpty()) return true // 空串允许 -> QuizDownloader 回退默认地址
        val parsed = runCatching { java.net.URL(url) }.getOrNull()
        val scheme = parsed?.protocol
        return scheme != null &&
            (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))
    }

    /** 逐行复刻 QuizDownloader.kt:71-74 + :28 的 effectiveUrl 回退。 */
    private fun effectiveUrl(saved: String?): String =
        saved?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_QUIZ_URL

    private fun parse(raw: String): URL? = runCatching { URL(raw) }.getOrNull()

    // ------------------------------------------------------------------
    // 正常回归：非法输入必须在保存阶段被拒
    // ------------------------------------------------------------------

    @Test
    fun rejects_nonHttpSchemesAndMissingScheme() {
        val bad = listOf(
            "file:///sdcard/questions.json",
            "ftp://192.168.1.101/questions.json",
            "192.168.1.101:8080/questions.json",
            "javascript:alert(1)",
            "//192.168.1.101/questions.json",
            "://missing-scheme"
        )
        for (raw in bad) {
            assertFalse("[$raw] must be rejected at save time", isAcceptedForSave(raw))
        }
    }

    @Test
    fun accepts_wellFormedHttpAndHttpsUrls() {
        assertTrue(isAcceptedForSave("http://192.168.1.101:8080/questions.json"))
        assertTrue(isAcceptedForSave("https://example.com/questions.json"))
    }

    @Test
    fun accepts_emptyOrBlankString_asFallbackToDefault() {
        assertTrue(isAcceptedForSave(""))
        assertTrue(isAcceptedForSave("   "))
        assertEquals(Constants.DEFAULT_QUIZ_URL, effectiveUrl(""))
        assertEquals(Constants.DEFAULT_QUIZ_URL, effectiveUrl("   "))
        assertEquals(Constants.DEFAULT_QUIZ_URL, effectiveUrl(null))
    }

    @Test
    fun accepts_uppercaseScheme_viaCaseInsensitiveComparison() {
        for (raw in listOf(
            "HTTP://192.168.1.101/questions.json",
            "Http://192.168.1.101/questions.json",
            "HTTPS://example.com/questions.json"
        )) {
            assertTrue("[$raw] should pass the scheme whitelist", isAcceptedForSave(raw))
            val proto = parse(raw)!!.protocol
            assertTrue(proto.equals("http", true) || proto.equals("https", true))
        }
    }

    @Test
    fun accepts_savedUrlWithLeadingTrailingWhitespace_afterTrim() {
        val raw = "  http://192.168.1.101:8080/questions.json  "
        assertTrue(isAcceptedForSave(raw))
        // SettingsActivity 保存的是 trim 后的值（:135 + :156）
        assertEquals("http://192.168.1.101:8080/questions.json", raw.trim())
    }

    @Test
    fun defaultQuizUrl_isWellFormedHttpWithNonBlankHost() {
        val url = parse(Constants.DEFAULT_QUIZ_URL)
        assertNotNull(Constants.DEFAULT_QUIZ_URL, url)
        assertEquals("http", url!!.protocol)
        assertTrue("default URL host must not be blank", url.host.isNotBlank())
        assertEquals(8080, url.port)
        assertTrue(isAcceptedForSave(Constants.DEFAULT_QUIZ_URL))
    }

    @Test
    fun malformedInput_neverEscapesValidationAsThrowable() {
        // runCatching{URL(...)}.getOrNull() 必须把 MalformedURLException 吞掉
        val inputs = listOf("", "http://", "ht!tp://x", ":", "::::", "\u0000", "http://[bad")
        for (raw in inputs) {
            // 不抛异常即通过；isAcceptedForSave 内部已使用 runCatching
            isAcceptedForSave(raw)
        }
    }

    @Test
    fun validSavedUrl_isUsedInsteadOfDefault() {
        val saved = "https://cdn.example.com/bank.json"
        assertEquals(saved, effectiveUrl(saved))
    }

    // ------------------------------------------------------------------
    // 缺陷证据（Characterization）：以下断言锁定"当前有缺陷的行为"，
    // 不是期望行为。详见交付报告 M4。
    // ------------------------------------------------------------------

    @Test
    fun KNOWN_GAP_hostlessHttpUrl_isAcceptedButUnusable() {
        for (raw in listOf("http://", "https://")) {
            assertTrue("[$raw] currently passes validation", isAcceptedForSave(raw))
            val url = parse(raw)
            assertNotNull(url)
            assertTrue(
                "[$raw] has an empty host, so every download will fail",
                url!!.host.isEmpty()
            )
            assertEquals(raw, effectiveUrl(raw)) // 不会回退到默认地址
        }
    }

    @Test
    fun KNOWN_GAP_hostContainingWhitespace_isAcceptedButUnusable() {
        for (raw in listOf("http:// x", "http://x y/z")) {
            assertTrue("[$raw] currently passes validation", isAcceptedForSave(raw))
            val host = parse(raw)!!.host
            assertTrue("[$raw] host contains whitespace: [$host]", host.any { it.isWhitespace() })
        }
    }

    @Test
    fun KNOWN_GAP_embeddedCredentials_areAcceptedAndWouldBeLoggedVerbatim() {
        val raw = "http://user:pass@192.168.1.101:8080/questions.json"
        assertTrue(isAcceptedForSave(raw))
        val url = parse(raw)
        assertEquals("user:pass", url!!.userInfo)
        // QuizDownloader.kt:29 用 Log.d 原样打印 effectiveUrl -> 凭据进入 logcat。
        assertTrue(url.toString().contains("user:pass"))
        assertEquals(raw, effectiveUrl(raw))
    }

    @Test
    fun KNOWN_GAP_extremelyLongUrl_isAcceptedWithoutAnyLengthGuard() {
        val raw = "http://192.168.1.101/" + "a".repeat(10_000)
        assertTrue(isAcceptedForSave(raw))
        parse(raw)!!.protocol
    }
}
