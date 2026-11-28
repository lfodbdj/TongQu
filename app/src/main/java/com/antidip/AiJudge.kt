package com.antidip

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 判题（2026-11-21 新增）：填空题答案与标准答案字面不一致时，由 AI 判断
 * 语义是否等价（如 0.5 = 1/2、"books" 的中文表达等）。端点 / Key / 模型由
 * 家长在设置页提供，OpenAI 兼容 chat/completions 协议。
 *
 * 设计约束：
 *  - fail-closed：未启用、配置缺失、网络失败、响应解析失败一律返回 null，
 *    调用方按「未判出」处理（引擎按原字面比对结果执行），绝不因 AI 放水。
 *  - QuizEngine 保持纯 JVM（单元测试），AI 判分只在 UI 层前置调用。
 *  - 仅填空题、仅字面不一致时才发起请求，控制成本。
 */
object AiJudge {

    data class Config(val endpoint: String, val key: String, val model: String)

    // V2.4：默认模型改为硅基流动的视觉模型——判题与拍照录题（多模态）共用，
    // 家长只填 Key 即可两者都可用。旧默认 gpt-4o-mini 不支持图片输入。
    private const val DEFAULT_MODEL = Constants.DEFAULT_AI_MODEL
    private const val TIMEOUT_MS = 25_000L

    /** 读取配置；未启用或配置不全返回 null。 */
    fun loadConfig(context: Context): Config? {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.KEY_AI_ENABLED, false)) return null
        val endpoint = prefs.getString(Constants.KEY_AI_ENDPOINT, "")?.trim().orEmpty()
        val key = prefs.getString(Constants.KEY_AI_KEY, "")?.trim().orEmpty()
        if (endpoint.isEmpty() || key.isEmpty()) return null
        val model = prefs.getString(Constants.KEY_AI_MODEL, "")?.trim().orEmpty()
            .ifEmpty { DEFAULT_MODEL }
        return Config(endpoint, key, model)
    }

    private fun systemPrompt(): String =
        "你是小学作业判分助手。判断学生答案与标准答案是否语义等价（数值相等、" +
        "同义表达、等价形式如 0.5 与 1/2、英文单复数大小写）。只输出 JSON：" +
        "{\"correct\": true} 或 {\"correct\": false}，不要输出任何其他文字。"

    private fun openSystemPrompt(): String =
        "你是小学开放式题目评审。这类题目答案不唯一：只要学生回答切题、内容合理、" +
        "没有知识性错误就判对。只输出 JSON：{\"correct\": true} 或 {\"correct\": false}，" +
        "不要输出任何其他文字。"

    private fun buildBody(cfg: Config, system: String, userMessage: String): JSONObject =
        JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.0)
            put("max_tokens", 64)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
        }

    /** 发起一次判分请求；任何失败返回 null。 */
    private suspend fun judgeRequest(cfg: Config, system: String, userMessage: String): Boolean? =
        withTimeout(TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(cfg.endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
                conn.outputStream.use {
                    it.write(buildBody(cfg, system, userMessage).toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val text = if (code in 200..299) {
                    conn.inputStream.readBytes().toString(Charsets.UTF_8)
                } else {
                    conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                }
                conn.disconnect()
                if (code !in 200..299) return@withContext null
                val content = JSONObject(text)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                parseVerdict(content)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 只信任 JSON 判词；非 JSON 输出一律 null。 */
    private fun parseVerdict(content: String): Boolean? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(content.substring(start, end + 1)).optBoolean("correct", false)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 填空题语义判分。返回 true=AI 判等价（调用方按正确处理），
     * false=AI 判不等价，null=未启用/失败（fail-closed）。
     */
    suspend fun judgeFill(
        context: Context,
        question: com.antidip.db.QuestionEntity,
        userAnswer: String
    ): Boolean? {
        val cfg = loadConfig(context) ?: return null
        val msg = "题目：${question.content}\n标准答案：${question.answer.trim()}\n" +
            "学生答案：${userAnswer.trim()}"
        return judgeRequest(cfg, systemPrompt(), msg)
    }

    /**
     * 开放式题判分（无标准答案，2026-11-21）：回答切题、合理、无知识性错误
     * 即判对。返回 null=未启用/失败（调用方按"AI 暂不可用"处理）。
     */
    suspend fun judgeOpen(
        context: Context,
        question: com.antidip.db.QuestionEntity,
        userAnswer: String
    ): Boolean? {
        val cfg = loadConfig(context) ?: return null
        val msg = "题目：${question.content}\n学生回答：${userAnswer.trim()}"
        return judgeRequest(cfg, openSystemPrompt(), msg)
    }

    /** 设置页「测试连接」：用一条平凡判分验证端点/Key/模型可用。 */
    suspend fun testConfig(
        context: Context,
        endpoint: String,
        key: String,
        model: String
    ): Boolean? {
        val cfg = Config(endpoint.trim(), key.trim(), model.trim().ifEmpty { DEFAULT_MODEL })
        if (cfg.endpoint.isEmpty() || cfg.key.isEmpty()) return null
        return judgeRequest(
            cfg, systemPrompt(),
            "题目：1 + 1 = ？（填空）\n标准答案：2\n学生答案：2"
        )
    }

    // ------------------------------------------------------------------
    // V2.3 拍照录题（用户要求 2026-11-24）：把题目照片交给视觉模型（多模态
    // chat/completions），抽取结构化题目（题型/题干/选项/答案/科目），家长复核
    // 后入库。仅当家长配置了支持图片的模型（如 Qwen2-VL / Qwen2.5-VL）时可用；
    // 任何失败返回 null，由 UI 提示改用「手动录入」。
    // ------------------------------------------------------------------

    /** 视觉模型抽题结果（家长复核表单的初始值）。 */
    data class ParsedQuestion(
        val type: String,          // choice / fill
        val content: String,
        val options: List<String>, // choice 时 4 个；fill 时为空
        val answer: String,        // choice 时字母 A-D；fill 时为答案文本
        val subject: String        // chinese/math/english/science/moral
    )

    private fun extractSystemPrompt(): String =
        "你是小学题目的录入助手。用户会给你一张题目照片，请你识别其中的题目并" +
            "输出严格的 JSON（不要输出任何其他文字、不要 Markdown 代码块）。格式：" +
            "{\"type\":\"choice\"或\"fill\",\"content\":\"题干原文\"," +
            "\"options\":[\"A选项\",\"B选项\",\"C选项\",\"D选项\"]," +
            "\"answer\":\"标准答案\",\"subject\":\"chinese/math/english/science/moral\"}。" +
            "规则：选择题 type=choice，answer 填正确选项的字母 A/B/C/D；" +
            "填空题 type=fill，options 留空数组，answer 填答案原文；" +
            "若照片含多道题，只录入第一道题；无法识别时仍尽力给出最接近的内容。"

    /**
     * 从题目照片抽取结构化题目。[jpegBase64] 为图片的 Base64（不含 data URL 前缀）。
     * 返回 null 表示未配置视觉模型 / 网络失败 / 解析失败（调用方引导手动录入）。
     */
    suspend fun extractQuestionFromImage(
        context: Context,
        jpegBase64: String
    ): ParsedQuestion? {
        val cfg = loadConfig(context) ?: return null
        val sys = extractSystemPrompt()
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", "请识别这张图片中的题目并按约定 JSON 输出。"))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                )
            )
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.0)
            put("max_tokens", 512)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", userContent))
            })
        }
        val raw = requestContent(cfg, body) ?: return null
        return parseParsedQuestion(raw)
    }

    /** 发起请求并返回 assistant 的 content 文本；失败返回 null。 */
    private suspend fun requestContent(cfg: Config, body: JSONObject): String? =
        withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(cfg.endpoint).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 20_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = conn.responseCode
                    val text = if (code in 200..299) {
                        conn.inputStream.readBytes().toString(Charsets.UTF_8)
                    } else {
                        conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                    }
                    conn.disconnect()
                    if (code !in 200..299) return@withContext null
                    JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                        .ifBlank { null }
                } catch (e: Exception) {
                    null
                }
            }
        }

    /** 解析抽题 JSON（容忍 ```json 包裹与前后杂字）。 */
    private fun parseParsedQuestion(content: String): ParsedQuestion? {
        var c = content.trim()
        // 去掉 ```json ... ``` 包裹
        if (c.startsWith("```")) {
            val firstNl = c.indexOf('\n')
            if (firstNl >= 0) c = c.substring(firstNl + 1)
            if (c.endsWith("```")) c = c.removeSuffix("```")
            c = c.trim()
        }
        val start = c.indexOf('{')
        val end = c.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(c.substring(start, end + 1))
            val type = obj.optString("type", "fill").let {
                if (it == "choice" || it == "fill") it else "fill"
            }
            val contentText = obj.optString("content", "").trim()
            if (contentText.isEmpty()) return null
            val optionsArr = obj.optJSONArray("options")
            val options = mutableListOf<String>()
            if (optionsArr != null) {
                for (i in 0 until optionsArr.length()) {
                    val v = optionsArr.optString(i, "").trim()
                    if (v.isNotEmpty()) options.add(v)
                }
            }
            var answer = obj.optString("answer", "").trim()
            // 选择题答案规范成字母 A-D（允许模型给出选项原文）。
            if (type == "choice") {
                answer = normalizeChoice(answer, options)
            }
            val subject = obj.optString("subject", "").trim().lowercase().let {
                when {
                    it.contains("语") || it == "chinese" -> "chinese"
                    it.contains("数") || it == "math" -> "math"
                    it.contains("英") || it == "english" -> "english"
                    it.contains("科") || it == "science" -> "science"
                    it.contains("道") || it.contains("德") || it == "moral" -> "moral"
                    else -> "math"
                }
            }
            if (type == "choice" && options.size < 2) {
                // 选项不足 → 退化为填空（答案用原文/字母）
                ParsedQuestion("fill", contentText, emptyList(), answer, subject)
            } else {
                ParsedQuestion(type, contentText, options, answer, subject)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeChoice(raw: String, options: List<String>): String {
        val t = raw.trim()
        if (t.length == 1) {
            val idx = "ABCD".indexOf(t.uppercase())
            if (idx in 0..3) return "ABCD"[idx].toString()
        }
        val idx = options.indexOfFirst { it.trim() == t || it.trim().equals(t, ignoreCase = true) }
        return if (idx in 0..3) "ABCD"[idx].toString() else t
    }
}
