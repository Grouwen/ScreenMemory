package com.screenmemory.app.net

import com.screenmemory.app.Prefs
import com.screenmemory.app.chat.ChatTurn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import android.util.Base64
import android.util.Log

/**
 * 千问（阿里云百炼）视频理解。严格按两份官方文档实现：
 *
 * ① 上传本地文件取临时 URL
 *    GET {uploads}?action=getPolicy&model=…            （Authorization: Bearer）
 *    POST {upload_host}  multipart：OSSAccessKeyId / policy / Signature / key /
 *         x-oss-object-acl / x-oss-forbid-overwrite / success_action_status / file（必须最后）
 *    → oss://…（48 小时有效）
 *    文档：https://help.aliyun.com/zh/model-studio/get-temporary-file-url
 *
 * ② 视频理解（OpenAI 兼容接口）
 *    POST {chat}/chat/completions        X-DashScope-OssResourceResolve: enable
 *    content: [{"type":"text","text":…},
 *              {"type":"video_url","video_url":{"url":"oss://…"},"fps":1}]
 *    文档：https://docs.bailian.console.aliyun.com/zh/model-studio/vision
 *
 * 文档给的选型规则：<7MB 可 base64，>7MB 只能公网 URL/oss://。所以 oss:// 失败且
 * 文件小于 7MB 时，自动退回 base64 内联再试一次。
 */
class QwenVideoClient(private val prefs: Prefs) {

    private companion object {
        /** 文档：Base64 内联要求原始文件小于 7MB */
        const val BASE64_MAX_BYTES = 7L * 1024 * 1024

        /** 和 CaptureService 的计时日志同一个 TAG，`adb logcat -s Timing` 一起看 */
        const val TIMING_TAG = "Timing"
    }

    private class UploadPolicy(
        val host: String,
        val dir: String,
        val accessKeyId: String,
        val signature: String,
        val policy: String,
        val objectAcl: String,
        val forbidOverwrite: String,
        val maxFileMb: Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * 发一问。
     *
     * @param video 这一轮要上传的片段；**追问时传 null** —— 不重传视频（一段 20MB），
     *              模型靠 [history] 里上一轮的回答延续上下文
     */
    fun ask(
        question: String,
        video: File?,
        systemPrompt: String,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit = {},
        history: List<ChatTurn> = emptyList(),
        imageBase64: String? = null,
        /**
         * 允许「没有画面」的纯文字提问。
         *
         * 悬浮窗关掉「带视频」后，用户可以只打字提问，这时候没有任何媒体可发，
         * 不能再按「没有可发送的画面」拒掉。
         */
        allowTextOnly: Boolean = false
    ): ModelAnswer {
        val key = prefs.qwenKey().trim()
        if (key.isEmpty()) throw IOException("请先填写百炼（DashScope）API Key")
        val useVideo = video != null && video.exists() && video.length() > 0L
        val useImage = !imageBase64.isNullOrBlank()
        if (!useVideo && !useImage && history.isEmpty() && !allowTextOnly) {
            throw IOException("没有可发送的画面")
        }
        if (allowTextOnly && !useVideo && !useImage && question.isBlank() && history.isEmpty()) {
            throw IOException("请输入问题")
        }
        val model = prefs.qwenModel()

        // 已经开始吐字了就不能再降级重试，否则用户会看到两份答案
        var emitted = false
        val t0 = SystemClock.elapsedRealtime()
        var uploadDoneAt = 0L
        var firstDeltaAt = 0L
        var sentVia = "video"
        val forward: (String) -> Unit = { delta ->
            if (firstDeltaAt == 0L) firstDeltaAt = SystemClock.elapsedRealtime()
            emitted = true
            onDelta(delta)
        }

        // 上传和「模型出字」混在一起看不出谁慢，这里分开记
        fun reportPhases() {
            val now = SystemClock.elapsedRealtime()
            val up = if (uploadDoneAt > 0) uploadDoneAt - t0 else -1L
            val ttft = if (firstDeltaAt > 0) firstDeltaAt - t0 else -1L
            val mb = (video?.length() ?: 0L) / 1024.0 / 1024.0
            Log.i(
                TIMING_TAG,
                "视频[$sentVia] 上传=%dms 首字=%dms 总=%dms 片段=%.1fMB 上传速率=%.1fMbps 模型=%s fps=%.1f 思考=%s".format(
                    up, ttft, now - t0, mb,
                    if (up > 0) mb * 8 / (up / 1000.0) else 0.0,
                    prefs.qwenModel(),
                    prefs.videoFps,
                    prefs.enableThinking()?.toString() ?: "不传"
                )
            )
        }

        // 追问或只附图：只带文字历史，不重新上传视频
        if (!useVideo || video == null) {
            onStatus(
                when {
                    useImage -> "模型正在看截图…"
                    history.isNotEmpty() -> "追问中（沿用上一轮的画面）…"
                    else -> "正在提问…"
                }
            )
            sentVia = if (useImage) "image" else "text"
            val answer = chat(
                key, null, question, systemPrompt, history, forward, imageBase64,
                textOnly = allowTextOnly
            )
            reportPhases()
            return answer
        }

        onStatus("正在申请上传凭证…")
        val ossUrl = try {
            val policy = getPolicy(key, model)
            upload(policy, video, onStatus)
        } catch (t: Throwable) {
            null.also { lastUploadError = t }
        }
        uploadDoneAt = SystemClock.elapsedRealtime()

        if (ossUrl != null) {
            sentVia = "oss"
            onStatus("模型正在看这段视频…")
            try {
                val answer = chat(
                    key, ossUrl, question, systemPrompt, history, forward, imageBase64
                )
                reportPhases()
                return answer
            } catch (t: Throwable) {
                if (emitted) throw t
                lastUploadError = t
            }
        }

        // 部分模型不接受「视频 + 截图」同一条请求：只要还没开始吐字，就自动改发截图
        if (useImage) {
            sentVia = "image-fallback"
            onStatus("视频和截图一起发送失败，改发截图…")
            val answer = chat(
                key, null, question, systemPrompt, history, forward, imageBase64
            )
            reportPhases()
            return answer
        }

        if (video.length() < BASE64_MAX_BYTES) {
            sentVia = "base64"
            onStatus("改用 base64 内联重试…")
            try {
                val answer = chat(
                    key, "data:video/mp4;base64," + base64(video),
                    question, systemPrompt, history, forward, imageBase64
                )
                reportPhases()
                return answer
            } catch (t: Throwable) {
                if (emitted || !useImage) throw t
                onStatus("视频内联发送失败，改发截图…")
                sentVia = "image-fallback"
                val answer = chat(
                    key, null, question, systemPrompt, history, forward, imageBase64
                )
                reportPhases()
                return answer
            }
        }

        val reason = lastUploadError?.message?.take(200).orEmpty()
        throw IOException(
            "视频直传失败：$reason\n片段约 %.1f MB，超过 base64 内联的 7MB 上限，无法降级。" +
                "把保留时长改短（如 20 秒）再试，或改用抽帧模式。".format(video.length() / 1024.0 / 1024.0)
        )
    }

    private var lastUploadError: Throwable? = null

    private fun base64(file: File): String =
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

    // ---------------------------------------------------------------- ① 凭证

    private fun getPolicy(key: String, model: String): UploadPolicy {
        val base = prefs.qwenUploadEndpoint().toHttpUrlOrNull()
            ?: throw IOException("「上传凭证 URL」不是合法地址")
        val url = base.newBuilder()
            .addQueryParameter("action", "getPolicy")
            .addQueryParameter("model", model)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("申请上传凭证失败 HTTP ${response.code}：${text.take(300)}")
            }
            val data = JSONObject(text).optJSONObject("data")
                ?: throw IOException("上传凭证格式异常：${text.take(200)}")
            return UploadPolicy(
                host = data.optString("upload_host"),
                dir = data.optString("upload_dir"),
                // 文档字段名是 oss_access_key_id，部分示例里是 ossaccesskeyid，两个都兼容
                accessKeyId = data.optString("oss_access_key_id")
                    .ifBlank { data.optString("ossaccesskeyid") },
                signature = data.optString("signature"),
                policy = data.optString("policy"),
                objectAcl = data.optString("x_oss_object_acl")
                    .ifBlank { data.optString("x-oss-object-acl") }
                    .ifBlank { "private" },
                forbidOverwrite = data.optString("x_oss_forbid_overwrite")
                    .ifBlank { data.optString("x-oss-forbid-overwrite", "true") },
                maxFileMb = data.optInt("max_file_size_mb", 0)
            )
        }
    }

    // ---------------------------------------------------------------- ② 上传

    private fun upload(policy: UploadPolicy, video: File, onStatus: (String) -> Unit): String {
        if (policy.host.isBlank()) throw IOException("上传凭证缺少 upload_host")

        val sizeMb = video.length() / 1024.0 / 1024.0
        if (policy.maxFileMb > 0 && sizeMb > policy.maxFileMb) {
            throw IOException(
                "视频 %.1fMB 超过 %.0fMB 上限，请把保留时长改短".format(
                    sizeMb, policy.maxFileMb.toDouble()
                )
            )
        }
        onStatus("正在上传视频（%.1f MB）…".format(sizeMb))

        val objectName = UUID.randomUUID().toString().replace("-", "") + ".mp4"
        val objectKey = policy.dir + "/" + objectName

        // 字段顺序与文档一致，file 必须是最后一个表单域
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("OSSAccessKeyId", policy.accessKeyId)
            .addFormDataPart("policy", policy.policy)
            .addFormDataPart("Signature", policy.signature)
            .addFormDataPart("key", objectKey)
            .addFormDataPart("x-oss-object-acl", policy.objectAcl)
            .addFormDataPart("x-oss-forbid-overwrite", policy.forbidOverwrite)
            .addFormDataPart("success_action_status", "200")
            .addFormDataPart(
                "file",
                objectName,
                ProgressBody(video, "video/mp4".toMediaType()) { percent ->
                    onStatus("正在上传视频 $percent%")
                }
            )
            .build()

        val request = Request.Builder().url(policy.host).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("上传失败 HTTP ${response.code}：${text.take(300)}")
            }
        }
        return "oss://" + objectKey
    }

    // ---------------------------------------------------------------- ③ 理解

    /**
     * 带「深度思考」参数的调用。
     *
     * 模型不支持 `enable_thinking` 时会返回 400，这时自动去掉参数再试一次
     * （400 发生在出字之前，不会出现两份答案）。
     */
    private fun chat(
        key: String,
        videoUrl: String?,
        question: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        onDelta: (String) -> Unit,
        imageBase64: String? = null,
        textOnly: Boolean = false
    ): ModelAnswer {
        val thinking = prefs.enableThinking()
        return try {
            chatOnce(
                key, videoUrl, question, systemPrompt, history, onDelta,
                imageBase64 = imageBase64,
                thinking = thinking,
                includeUsage = true,
                textOnly = textOnly
            )
        } catch (t: Throwable) {
            if (t.message?.contains("HTTP 400") != true) throw t
            if (thinking != null) {
                Log.w(
                    TIMING_TAG,
                    "模型不接受 enable_thinking，去掉参数重试：${t.message?.take(120)}"
                )
                try {
                    return chatOnce(
                        key, videoUrl, question, systemPrompt, history, onDelta,
                        imageBase64 = imageBase64,
                        thinking = null,
                        includeUsage = true,
                        textOnly = textOnly
                    )
                } catch (thinkingError: Throwable) {
                    if (thinkingError.message?.contains("HTTP 400") != true) {
                        throw thinkingError
                    }
                }
                try {
                    return chatOnce(
                        key, videoUrl, question, systemPrompt, history, onDelta,
                        imageBase64 = imageBase64,
                        thinking = thinking,
                        includeUsage = false,
                        textOnly = textOnly
                    )
                } catch (usageError: Throwable) {
                    if (usageError.message?.contains("HTTP 400") != true) {
                        throw usageError
                    }
                }
            }
            Log.w(TIMING_TAG, "模型不接受 stream usage，关闭 usage 后重试")
            chatOnce(
                key, videoUrl, question, systemPrompt, history, onDelta,
                imageBase64 = imageBase64,
                thinking = null,
                includeUsage = false,
                textOnly = textOnly
            )
        }
    }

    private fun chatOnce(
        key: String,
        videoUrl: String?,
        question: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        onDelta: (String) -> Unit,
        imageBase64: String?,
        thinking: Boolean?,
        includeUsage: Boolean,
        textOnly: Boolean = false
    ): ModelAnswer {
        val intro = when {
            // 关掉「带视频」时没有任何录屏上下文，凡提到「上一轮画面」的说法都不成立，
            // 所以不加后缀，原样把用户的话发过去
            textOnly -> question
            imageBase64 != null && videoUrl == null && history.isNotEmpty() ->
                question + "\n\n（这是追问，另外附了一张用户框选的截图，请优先参考这张图，上一轮画面仍可参考）"
            imageBase64 != null ->
                question + "\n\n（用户附了一张框选的截图，请参考这张图回答）"
            videoUrl == null && history.isNotEmpty() ->
                question + "\n\n（这是追问，不再重复上传视频：画面和你上一轮看到的是同一段，请沿用上文里的画面信息）"
            else -> question
        }
        val fps = prefs.videoFps
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", intro))
        if (imageBase64 != null) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
            )
        }
        if (videoUrl != null) {
            content.put(
                JSONObject().put("type", "video_url")
                    .put("video_url", JSONObject().put("url", videoUrl))
                    .put("fps", fps)
            )
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        // 历史轮次只回放文字
        for (turn in history) {
            messages.put(JSONObject().put("role", "user").put("content", turn.question))
            if (turn.answer.isNotBlank()) {
                messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", content))

        val payload = JSONObject()
            .put("model", prefs.qwenModel())
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("stream", true)
        if (thinking != null) {
            payload.put("enable_thinking", thinking)
        }
        if (includeUsage) {
            payload.put("stream_options", JSONObject().put("include_usage", true))
        }

        val request = Request.Builder()
            .url(prefs.qwenChatEndpoint())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("X-DashScope-OssResourceResolve", "enable")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val message = try {
                    val json = JSONObject(text)
                    json.optJSONObject("error")?.optString("message").orEmpty()
                        .ifBlank { json.optString("message").ifBlank { text.take(300) } }
                } catch (_: Throwable) {
                    text.take(300)
                }
                throw IOException("千问返回 HTTP ${response.code}：$message")
            }
            return Sse.consume(response, onDelta)
        }
    }

    // ---------------------------------------------------------------- 进度

    private class ProgressBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            var written = 0L
            var lastPercent = -1
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    written += read
                    val percent = (written * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
    }
}
