package com.screenmemory.app.net

import com.screenmemory.app.chat.TokenUsage
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class ModelAnswer(
    val text: String,
    val usage: TokenUsage? = null
)

/**
 * 解析 OpenAI 兼容的 SSE 流：逐行读 `data: {...}`，取 choices[0].delta.content，
 * 同时收集服务端在最后一个流式分片中返回的 usage。
 */
object Sse {

    fun consume(response: Response, onDelta: (String) -> Unit): ModelAnswer {
        val source = response.body?.source() ?: throw IOException("模型响应为空")
        val builder = StringBuilder()
        val raw = StringBuilder()
        var usage: TokenUsage? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            raw.append(line).append('\n')
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val json = try {
                JSONObject(payload)
            } catch (_: Throwable) {
                null
            } ?: continue
            usage = parseUsage(json) ?: usage
            val delta = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
            if (delta.isNotEmpty()) {
                builder.append(delta)
                onDelta(delta)
            }
        }
        if (builder.isNotEmpty()) return ModelAnswer(builder.toString().trim(), usage)

        // 兜底：有些网关不认 stream，直接返回整段 JSON。
        val whole = raw.toString().trim()
        if (whole.startsWith("{")) {
            val json = try {
                JSONObject(whole)
            } catch (_: Throwable) {
                null
            }
            if (json != null) {
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (content.isNotEmpty()) onDelta(content)
                return ModelAnswer(
                    text = content.trim(),
                    usage = parseUsage(json) ?: usage
                )
            }
        }
        return ModelAnswer("", usage)
    }

    private fun parseUsage(json: JSONObject): TokenUsage? {
        val usage = json.optJSONObject("usage") ?: return null
        val prompt = firstInt(usage, "prompt_tokens", "input_tokens")
        val completion = firstInt(usage, "completion_tokens", "output_tokens")
        val total = firstInt(usage, "total_tokens")
            ?: ((prompt ?: 0) + (completion ?: 0)).takeIf { it > 0 }
            ?: return null
        if (total <= 0) return null
        return TokenUsage(
            promptTokens = prompt ?: 0,
            completionTokens = completion ?: 0,
            totalTokens = total
        )
    }

    private fun firstInt(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) return json.optInt(key)
        }
        return null
    }
}
