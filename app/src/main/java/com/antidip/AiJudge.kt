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

    private const val DEFAULT_MODEL = "gpt-4o-mini"
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
}
