package com.screenmemory.app

import android.content.Context
import kotlin.math.roundToInt

/**
 * 简单的本地配置存储（SharedPreferences 包装）。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("screen_memory", Context.MODE_PRIVATE)

    init {
        migrateToFullUrls()
    }

    /**
     * 旧版本只存 Base URL，路径由程序拼；现在改成让用户填完整接口地址。
     * 这里把老值补全一次，避免升级后请求打到错误路径。
     */
    private fun migrateToFullUrls() {
        if (!sp.contains("chat_url")) {
            val old = sp.getString("chat_base", null)
            if (!old.isNullOrBlank()) {
                sp.edit()
                    .putString("chat_url", old.trim().trimEnd('/') + "/chat/completions")
                    .apply()
            }
        }
        if (!sp.contains("asr_url")) {
            val oldAsr = sp.getString("asr_base", null)
            val oldChat = sp.getString("chat_base", null)
            val base = if (!oldAsr.isNullOrBlank()) oldAsr else oldChat
            if (!base.isNullOrBlank()) {
                sp.edit()
                    .putString("asr_url", base.trim().trimEnd('/') + "/audio/transcriptions")
                    .apply()
            }
        }
        // 视频接口从「一个域名」拆成「两个完整地址」
        if (!sp.contains("qwen_chat_url")) {
            val old = sp.getString("qwen_base", null)
            if (!old.isNullOrBlank()) {
                val domain = old.trim().trimEnd('/')
                sp.edit()
                    .putString("qwen_chat_url", "$domain/compatible-mode/v1/chat/completions")
                    .putString("qwen_upload_url", "$domain/api/v1/uploads")
                    .apply()
            }
        }
    }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) = sp.edit().putString("api_key", value).apply()

    /** 对话接口完整地址，程序不再拼接任何路径 */
    var chatUrl: String
        get() = sp.getString("chat_url", DEFAULT_CHAT_URL) ?: ""
        set(value) = sp.edit().putString("chat_url", value).apply()

    var chatModel: String
        get() = sp.getString("chat_model", "gpt-4o-mini") ?: ""
        set(value) = sp.edit().putString("chat_model", value).apply()

    /** 语音转写完整地址，程序不再拼接任何路径 */
    var asrUrl: String
        get() = sp.getString("asr_url", "") ?: ""
        set(value) = sp.edit().putString("asr_url", value).apply()

    var asrModel: String
        get() = sp.getString("asr_model", "whisper-1") ?: ""
        set(value) = sp.edit().putString("asr_model", value).apply()

    /** 环形缓冲保留时长（秒），用户可自定义 */
    var clipSeconds: Int
        get() = sp.getInt("clip_seconds", 30).coerceIn(5, 60)
        set(value) = sp.edit().putInt("clip_seconds", value.coerceIn(5, 60)).apply()

    /** 每次发送给模型的关键帧数量，用户可自定义 */
    var frameCount: Int
        get() = sp.getInt("frame_count", 8).coerceIn(1, 20)
        set(value) = sp.edit().putInt("frame_count", value.coerceIn(1, 20)).apply()

    /** 画质档位：0 省流 / 1 标准 / 2 清晰 */
    var qualityProfile: Int
        get() = sp.getInt("quality_profile", QualityProfile.DEFAULT_INDEX).coerceIn(0, 2)
        set(value) = sp.edit().putInt("quality_profile", value.coerceIn(0, 2)).apply()

    /** 当前生效的画质参数（分辨率/码率/抽帧尺寸/JPEG 质量） */
    fun quality(): QualityProfile = QualityProfile.of(qualityProfile)

    /** 是否把 30 秒片段另存到应用外部目录 */
    var saveClip: Boolean
        get() = sp.getBoolean("save_clip", true)
        set(value) = sp.edit().putBoolean("save_clip", value).apply()

    /** true = 视频直传（千问本地上传）；false = 抽帧图片 */
    var videoMode: Boolean
        get() = sp.getBoolean("video_mode", false)
        set(value) = sp.edit().putBoolean("video_mode", value).apply()

    /** 视频对话接口完整地址（OpenAI 兼容的 /chat/completions） */
    var qwenChatUrl: String
        get() = sp.getString("qwen_chat_url", DEFAULT_QWEN_CHAT_URL) ?: ""
        set(value) = sp.edit().putString("qwen_chat_url", value).apply()

    /** 上传凭证接口完整地址（GET ?action=getPolicy&model=…） */
    var qwenUploadUrl: String
        get() = sp.getString("qwen_upload_url", DEFAULT_QWEN_UPLOAD_URL) ?: ""
        set(value) = sp.edit().putString("qwen_upload_url", value).apply()

    /** 视频直传专用的 Key，留空则复用抽帧模式的 API Key */
    var qwenApiKey: String
        get() = sp.getString("qwen_key", "") ?: ""
        set(value) = sp.edit().putString("qwen_key", value).apply()

    /** 千问视频模型，上传文件时会和它绑定，必须与推理时一致 */
    var qwenVideoModel: String
        get() = sp.getString("qwen_video_model", "qwen-vl-max") ?: ""
        set(value) = sp.edit().putString("qwen_video_model", value).apply()

    /** 优先使用系统离线识别（机型不支持或没结果时自动回退云端） */
    var preferLocalAsr: Boolean
        get() = sp.getBoolean("prefer_local_asr", true)
        set(value) = sp.edit().putBoolean("prefer_local_asr", value).apply()

    /** 语音转写专用的 Key，留空则复用抽帧模式的 API Key */
    var asrApiKey: String
        get() = sp.getString("asr_key", "") ?: ""
        set(value) = sp.edit().putString("asr_key", value).apply()

    /** 对话接口：用户填的就是完整地址，原样使用 */
    fun chatEndpoint(): String = chatUrl.trim()

    /** 语音转写接口：同上，原样使用 */
    fun asrEndpoint(): String = asrUrl.trim()

    fun asrModelName(): String = asrModel.ifBlank { "whisper-1" }

    fun chatModelName(): String = chatModel.ifBlank { "gpt-4o-mini" }

    /** 语音转写用的 Key：优先自己的，其次抽帧模式的，最后视频模式的 */
    fun asrKey(): String =
        asrApiKey.ifBlank { apiKey.ifBlank { qwenApiKey } }

    /** 视频直传用的 Key：优先自己的，其次抽帧模式的 */
    fun qwenKey(): String = qwenApiKey.ifBlank { apiKey }

    /** 视频对话接口：用户填的完整地址，原样使用 */
    fun qwenChatEndpoint(): String = qwenChatUrl.trim()

    /** 上传凭证接口：用户填的完整地址，原样使用 */
    fun qwenUploadEndpoint(): String = qwenUploadUrl.trim()

    /** 视频模型：上传凭证要用它，推理也必须用它 */
    fun qwenModel(): String = qwenVideoModel.ifBlank { "qwen-vl-max" }

    /**
     * 视频直传时服务端按这个频率把片段拆成图片喂给视觉编码器。
     *
     * 首字延迟跟**文件大小无关**，跟「时长 × fps × 分辨率」成正比：
     * 8 秒 @2fps = 16 帧，@1fps = 8 帧，prefill 直接减半。
     * 文档取值范围 [0.1, 10]，这里允许 0.5–4。
     */
    var videoFps: Float
        get() = sp.getFloat("video_fps", 1f)
        // 对齐到滑块的 0.5 步长：Slider 收到非步长值会直接抛异常
        set(value) = sp.edit().putFloat(
            "video_fps",
            ((value.coerceIn(0.5f, 4f) * 2f).roundToInt() / 2f)
        ).apply()

    /**
     * 深度思考开关：-1 = 不传该参数（默认，兼容所有模型），0 = 关闭，1 = 开启。
     *
     * 千问 qwen3 系列默认可能开启 thinking，出字前会先内部推理十几秒，
     * 体感就是「点了发送半天不出字」。模型不支持该参数时请求会 400，
     * 客户端会自动去掉参数重试一次。
     */
    var thinkingMode: Int
        get() = sp.getInt("thinking_mode", -1)
        set(value) = sp.edit().putInt("thinking_mode", value).apply()

    fun enableThinking(): Boolean? = when (thinkingMode) {
        0 -> false
        1 -> true
        else -> null
    }

    /**
     * 模型能否解析音轨：0 = 自动（按型号名判断），1 = 能听，2 = 听不到。
     * 手动值优先级最高，用于型号别名、或命名里不带 omni 但确实支持音频的情况。
     */
    var qwenAudioMode: Int
        get() = sp.getInt("qwen_audio_mode", 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt("qwen_audio_mode", value.coerceIn(0, 2)).apply()

    /**
     * 模型能不能解析音轨。
     * 自动模式下：含 omni 的型号（如 qwen3.5-omni-plus）视为能听声音，其余视为只看画面。
     */
    fun qwenModelHearsAudio(): Boolean = when (qwenAudioMode) {
        1 -> true
        2 -> false
        else -> qwenModel().contains("omni", ignoreCase = true)
    }

    companion object {
        /** 全新安装时的默认对话接口完整地址（用户可直接改） */
        private const val DEFAULT_CHAT_URL = "https://api.openai.com/v1/chat/completions"

        /** 百炼视频理解的 OpenAI 兼容入口 */
        private const val DEFAULT_QWEN_CHAT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

        /** 百炼「上传本地文件获取临时 URL」的凭证接口 */
        private const val DEFAULT_QWEN_UPLOAD_URL =
            "https://dashscope.aliyuncs.com/api/v1/uploads"
    }
}
