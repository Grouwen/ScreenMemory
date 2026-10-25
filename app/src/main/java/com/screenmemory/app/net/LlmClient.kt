package com.screenmemory.app.net

import com.screenmemory.app.Prefs
import com.screenmemory.app.chat.ChatTurn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的客户端：语音转写走 /audio/transcriptions，
 * 多模态问答走 /chat/completions。国内多数厂商（通义、智谱、月之暗面、
 * 硅基流动、DeepSeek 等）都提供兼容端点，改 Base URL 即可。
 */
class LlmClient(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun transcribe(wav: ByteArray): String {
        val key = prefs.asrKey().trim()
        if (key.isEmpty()) throw IOException("请先在设置里填写语音转写的 API Key")
        val url = prefs.asrEndpoint()
        if (url.isEmpty()) {
            throw IOException("请先在设置里填写「转写接口 URL」（完整地址，形如 https://…/v1/audio/transcriptions）")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", prefs.asrModelName())
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("转写失败 HTTP ${response.code}：${text.take(300)}")
            }
            return JSONObject(text).optString("text", "").trim()
        }
    }

    /**
     * 发一问。带 [history] 就是追问：把之前几轮以纯文本回放，模型才知道"刚才"指什么。
     *
     * @param jpegBase64 这一轮要附的截图；追问时传空列表（画面沿用上一轮，不重复上传）
     */
    fun ask(
        question: String,
        jpegBase64: List<String>,
        systemPrompt: String,
        onDelta: (String) -> Unit = {},
        history: List<ChatTurn> = emptyList()
    ): String {
        val key = prefs.apiKey.trim()
        if (key.isEmpty()) throw IOException("请先在设置里填写 API Key")
        val url = prefs.chatEndpoint()
        if (url.isEmpty()) {
            throw IOException("请先在设置里填写「对话接口 URL」（完整地址，形如 https://…/v1/chat/completions）")
        }

        val content = JSONArray()
        val intro = when {
            jpegBase64.isNotEmpty() ->
                question + "\n\n（以下是这节网课最近 " + jpegBase64.size +
                    " 个时刻的截图，按时间先后排列，最后一张最接近提问的那一刻）"
            history.isNotEmpty() ->
                question + "\n\n（这是追问，不重复附图：板书和你上一轮看到的是同一批，请沿用上文里的画面信息）"
            else -> question
        }
        content.put(JSONObject().put("type", "text").put("text", intro))
        for (b64 in jpegBase64) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        // 历史轮次只回放文字：图片不重复上传，否则每次追问都要多传几 MB
        for (turn in history) {
            messages.put(JSONObject().put("role", "user").put("content", turn.question))
            if (turn.answer.isNotBlank()) {
                messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", content))

        val payload = JSONObject()
            .put("model", prefs.chatModelName())
            .put("messages", messages)
            .put("temperature", 0.4)
            .put("stream", true)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw IOException("模型返回 HTTP ${response.code}：${text.take(300)}")
            }
            return Sse.consume(response, onDelta)
        }
    }
}
