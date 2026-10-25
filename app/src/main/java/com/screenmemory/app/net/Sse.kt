package com.screenmemory.app.net

import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 解析 OpenAI 兼容的 SSE 流：逐行读 `data: {...}`，取 choices[0].delta.content。
 * 抽帧模式和视频直传都走 chat/completions，所以共用这一份。
 */
object Sse {

    fun consume(response: Response, onDelta: (String) -> Unit): String {
        val source = response.body?.source() ?: throw IOException("模型响应为空")
        val builder = StringBuilder()
        val raw = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            raw.append(line).append('\n')
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val delta = try {
                JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
                    .orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (delta.isNotEmpty()) {
                builder.append(delta)
                onDelta(delta)
            }
        }
        if (builder.isNotEmpty()) return builder.toString().trim()

        // 兜底：有些网关不认 stream，直接返回整段 JSON
        val whole = raw.toString().trim()
        if (whole.startsWith("{")) {
            val content = try {
                JSONObject(whole).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (content.isNotEmpty()) {
                onDelta(content)
                return content.trim()
            }
        }
        return ""
    }
}
