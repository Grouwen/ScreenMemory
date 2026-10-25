package com.screenmemory.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screenmemory.app.MainActivity
import com.screenmemory.app.Prefs
import com.screenmemory.app.QualityProfile
import com.screenmemory.app.R
import com.screenmemory.app.audio.AudioHub
import com.screenmemory.app.audio.UtteranceRecorder
import com.screenmemory.app.capture.FrameExtractor
import com.screenmemory.app.capture.MediaProbe
import com.screenmemory.app.capture.ScreenRingRecorder
import com.screenmemory.app.net.LlmClient
import com.screenmemory.app.net.QwenVideoClient
import com.screenmemory.app.overlay.OverlayController
import com.screenmemory.app.speech.SpeechTranscriber
import com.screenmemory.app.util.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * 前台服务：持有 MediaProjection、屏幕环形录制器、音频采集、悬浮窗。
 */
class CaptureService : Service(), OverlayController.Listener {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.screenmemory.app.action.START"
        const val ACTION_STOP = "com.screenmemory.app.action.STOP"
        const val ACTION_UPDATE = "com.screenmemory.app.action.UPDATE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "screen_memory"
        private const val NOTIF_ID = 1001

        /** 低于这个峰值就当静音（16bit，约 -60dBFS） */
        private const val SILENCE_PEAK = 32

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }

    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var videoClient: QwenVideoClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var projection: MediaProjection? = null
    private var recorder: ScreenRingRecorder? = null
    private var audioHub: AudioHub? = null
    private var overlay: OverlayController? = null
    private var utterance: UtteranceRecorder? = null
    private var speech: SpeechTranscriber? = null
    private var usingLocalSpeech = false

    /** 本回合本地识别失败过，就暂时全部走云端，避免每次都要失败一次 */
    private var localSpeechFailed = false

    private var ready = false
    private var asking = false
    private var stopping = false

    /** 已生效的参数，用于判断设置页的改动是否真的变了 */
    private var appliedSeconds = 0
    private var appliedFrames = 0
    private var appliedVideoMode = false
    private var appliedQuality = QualityProfile.DEFAULT_INDEX

    private class Prepared(
        val file: File,
        val frames: List<String>,
        val savedPath: String?,
        val durationMs: Long,
        /** 音频峰值是否够大（即真的采到了声音，而不是一条静音轨） */
        val hasAudio: Boolean,
        /** 片段里是否存在音轨（有可能是全静音的轨） */
        val trackPresent: Boolean = false,
        val ok: Boolean = true,
        val message: String = ""
    )

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        llm = LlmClient(prefs)
        videoClient = QwenVideoClient(prefs)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                prefs = Prefs(this)
                llm = LlmClient(prefs)
                videoClient = QwenVideoClient(prefs)
                startForegroundSafely()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != Activity.RESULT_OK || data == null) {
                    Log.e(TAG, "missing screen capture grant")
                    stopEverything(false)
                    return START_NOT_STICKY
                }
                beginCapture(resultCode, data)
            }

            ACTION_STOP -> stopEverything(true)

            ACTION_UPDATE -> applySettings()

            else -> if (!ready) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 启动

    private fun startForegroundSafely() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // MICROPHONE 这个 FGS 类型是 API 30 才有的，Android 10 上只声明 mediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground fallback failed", t2)
            }
        }
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        if (ready) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            null
        }
        if (proj == null) {
            toast("没有拿到录屏授权，助手未启动")
            stopEverything(false)
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "projection stopped by system/user")
                if (!stopping) {
                    toast("系统已停止录屏，助手退出")
                    stopEverything(false)
                }
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val quality = prefs.quality()
        // 档位里的 720/1080 是「屏幕短边」：竖屏手机的最长边是高度，
        // 以前按 maxOf(w, h) 算会把 1080 宽的屏幕压成 480 宽，小字直接糊掉。
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val scale = min(1f, quality.shortSide.toFloat() / shortSide)
        var width = ((metrics.widthPixels * scale).toInt() / 16) * 16
        var height = ((metrics.heightPixels * scale).toInt() / 16) * 16
        width = width.coerceAtLeast(160)
        height = height.coerceAtLeast(160)

        // 只采应用内播放的声音（回放捕获），不再提供麦克风选项
        val sampleRate = AudioHub.PLAYBACK_SAMPLE_RATE
        val rec = ScreenRingRecorder(
            projection = proj,
            width = width,
            height = height,
            dpi = metrics.densityDpi,
            audioSampleRate = sampleRate,
            bitRate = if (maxOf(width, height) >= 640) quality.videoBitRate else 2_000_000,
            fps = 20,
            windowSeconds = prefs.clipSeconds
        )
        try {
            rec.start()
        } catch (t: Throwable) {
            Log.e(TAG, "recorder start failed", t)
            toast("录制器启动失败：${t.message}")
            stopEverything(false)
            return
        }
        recorder = rec

        val hub = AudioHub(sampleRate)
        val audioOk = try {
            hub.startPlayback(proj)
        } catch (t: Throwable) {
            Log.e(TAG, "audio start failed", t)
            false
        }
        if (audioOk) {
            hub.addSink(object : AudioHub.Sink {
                override fun onPcm(buffer: ShortArray, size: Int, ptsUs: Long) {
                    recorder?.offerAudio(buffer, size, ptsUs)
                }
            })
            audioHub = hub
        } else {
            toast("应用内声音采集不可用，片段将只有画面")
        }

        overlay = OverlayController(this, this, prefs.clipSeconds).also { it.showBubble() }
        ready = true
        isRunning = true
        appliedSeconds = prefs.clipSeconds
        appliedFrames = prefs.frameCount
        appliedVideoMode = prefs.videoMode
        appliedQuality = prefs.qualityProfile
        toast("助手已启动，保留最近 ${prefs.clipSeconds} 秒画面与声音")
    }

    // ------------------------------------------------------------------ 交互

    override fun onHoldStart() {
        if (!ready || asking) return

        // 本地识别和自录音是互斥的：SpeechRecognizer 自己占麦克风
        val wantLocal = prefs.preferLocalAsr && !localSpeechFailed &&
            SpeechTranscriber.isAvailable(this)
        if (wantLocal) {
            val engine = SpeechTranscriber(this)
            if (engine.start({ partial -> overlay?.setStatus("识别中：$partial") })) {
                speech = engine
                usingLocalSpeech = true
                overlay?.setStatus("正在听…（系统离线识别）说完松手")
                return
            }
            localSpeechFailed = true
        }

        usingLocalSpeech = false
        // 片段音源是回放捕获，无法同时拿到麦克风，这里单独开一路
        val hub: AudioHub? = null
        val recorder = UtteranceRecorder(this, hub, AudioHub.MIC_SAMPLE_RATE)
        if (!recorder.start()) {
            overlay?.setStatus("麦克风被占用，试试直接打字")
            return
        }
        utterance = recorder
        overlay?.setStatus("正在听… 说完松手")
    }

    override fun onHoldEnd() {
        if (usingLocalSpeech) {
            val engine = speech
            speech = null
            usingLocalSpeech = false
            if (engine == null) {
                overlay?.setStatus("识别引擎已释放，再说一次？")
                return
            }
            overlay?.setStatus("正在识别…")
            engine.stop { text ->
                if (text.isNullOrBlank()) {
                    // 本地拿不到结果：本回合切回云端，避免反复失败
                    localSpeechFailed = true
                    overlay?.setStatus("本地识别没有结果（机型不支持或没装离线包），已切回云端，请再按住说一次")
                } else {
                    overlay?.setRecognized(text)
                    overlay?.setStatus("文字已就绪（本地识别），改好点“发送”")
                }
            }
            return
        }

        val recorder = utterance ?: return
        utterance = null
        val wav = try {
            recorder.stop()
        } catch (t: Throwable) {
            ByteArray(0)
        }
        if (wav.size < 4_000) {
            overlay?.setStatus("太短了，再说一次？")
            return
        }
        scope.launch {
            overlay?.setStatus("正在把语音转成文字…")
            try {
                val text = withContext(Dispatchers.IO) { llm.transcribe(wav) }
                if (text.isBlank()) {
                    overlay?.setStatus("没听清，可以重说或直接打字")
                } else {
                    overlay?.setRecognized(text)
                    overlay?.setStatus("文字已就绪，改好点“发送”")
                }
            } catch (t: Throwable) {
                overlay?.setStatus("转写失败：${t.message}")
            }
        }
    }

    override fun onSend(text: String) {
        val question = text.trim()
        if (question.isEmpty()) {
            overlay?.setStatus("先说点什么，或直接输入文字")
            return
        }
        if (asking) return
        val rec = recorder
        if (rec == null || !ready) {
            overlay?.setStatus("录制还没准备好")
            return
        }
        asking = true
        overlay?.setBusy(true)
        overlay?.setAnswer("")
        scope.launch {
            try {
                overlay?.setWarning("")
                overlay?.setStatus(
                    if (rec.isFrozen()) "正在打包「点按钮那一刻」之前的 ${prefs.clipSeconds} 秒…"
                    else "正在打包最近 ${prefs.clipSeconds} 秒…"
                )
                val prepared = withContext(Dispatchers.IO) { prepare(rec) }
                if (!prepared.ok) throw IllegalStateException(prepared.message)

                // 流式输出：先纯文本预览，收尾再整体渲染 Markdown（半截公式不能交给 LaTeX 渲染器）
                val streamed = StringBuilder()
                var lastPush = 0L
                val onDelta: (String) -> Unit = { delta ->
                    streamed.append(delta)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPush >= 120) {
                        lastPush = now
                        overlay?.setAnswerPreview(Preview.plain(streamed.toString()))
                    }
                }

                val answer: String
                val summary: String
                if (prefs.videoMode) {
                    val hearsAudio = prefs.qwenModelHearsAudio()
                    if (!prepared.hasAudio) {
                        overlay?.setWarning(silenceMessage(prepared))
                    }
                    answer = withContext(Dispatchers.IO) {
                        videoClient.ask(
                            question,
                            prepared.file,
                            videoPrompt(prepared.hasAudio, hearsAudio),
                            { status -> overlay?.setStatus(status) },
                            onDelta
                        )
                    }
                    summary = buildString {
                        append("已发送 · 视频直传 / ${prepared.durationMs / 1000} 秒 · ")
                        when {
                            !prepared.hasAudio -> append("没采到声音")
                            hearsAudio -> append("含音轨，模型会参考声音")
                            else -> append("含音轨，但 ${prefs.qwenModel()} 只解析画面")
                        }
                    }
                } else {
                    overlay?.setStatus("已发送 ${prepared.frames.size} 帧画面，等模型回答…")
                    answer = withContext(Dispatchers.IO) {
                        llm.ask(question, prepared.frames, systemPrompt(), onDelta)
                    }
                    summary = buildString {
                        append("已发送 · ${prepared.frames.size} 帧 / ${prepared.durationMs / 1000} 秒")
                    }
                }
                overlay?.setAnswer(answer.ifBlank { "模型没有返回内容" })
                val builder = StringBuilder(summary)
                // 渲染降级时把原因带出来，否则用户只看到一堆 $ 和 #
                overlay?.lastRenderError?.let { builder.append("\n⚠️ 渲染降级：").append(it) }
                prepared.savedPath?.let { builder.append("\n片段已保存：").append(it) }
                overlay?.setStatus(builder.toString())
            } catch (t: Throwable) {
                Log.e(TAG, "ask failed", t)
                overlay?.setStatus("出错了：${t.message}")
            } finally {
                asking = false
                overlay?.setBusy(false)
            }
        }
    }

    override fun onStopService() {
        stopEverything(true)
    }

    /**
     * 点悬浮球的一瞬间把画面冻结：面板、键盘、打字过程都不进入录像。
     * 冻结的是缓冲区本身（不再收新帧），而不是记个时间戳——屏幕静止时
     * 编码器可能很久不产帧，按时间戳过滤会被后来的新帧挤出缓冲区。
     */
    override fun onPanelOpened() {
        val rec = recorder ?: return
        if (!rec.freezeWindow()) {
            overlay?.setStatus("画面刚开始录，缓冲还不足，稍等一下再问")
            return
        }
        localSpeechFailed = false
        toast("已冻结画面，之后的操作不会录进去")
        overlay?.setStatus("✅ 画面已冻结在点按钮那一刻 · 之后的输入不会录进去")

        // 顺手探一下最近几秒到底有没有声音，让用户在提问前就知道
        if (prefs.videoMode) {
            val peak = rec.recentAudioPeak(5)
            if (peak <= SILENCE_PEAK) {
                overlay?.setWarning(
                    "🔇 最近 5 秒没有采到应用内声音。\n" +
                        "常见原因：目标 App 禁止内录（带版权内容的视频/音乐、语音通话），" +
                        "或那段时间确实没有声音在播放。画面仍可正常提问。"
                )
            }
        }
    }

    override fun onPanelClosed() {
        recorder?.unfreezeWindow()
    }

    /** 用户在设置页改了保留时长 / 帧数，立即作用到正在运行的实例 */
    private fun applySettings() {
        val fresh = Prefs(this)
        prefs = fresh
        llm = LlmClient(fresh)
        videoClient = QwenVideoClient(fresh)
        val seconds = fresh.clipSeconds
        val frames = fresh.frameCount
        val qualityChanged = fresh.qualityProfile != appliedQuality
        val changed = seconds != appliedSeconds || frames != appliedFrames ||
            fresh.videoMode != appliedVideoMode
        appliedSeconds = seconds
        appliedFrames = frames
        appliedVideoMode = fresh.videoMode
        appliedQuality = fresh.qualityProfile

        recorder?.updateWindowSeconds(seconds)
        overlay?.setClipSeconds(seconds)
        if (ready) {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification())
            } catch (_: Throwable) {
            }
            when {
                qualityChanged -> toast("画质改动需要重新启动助手才生效（分辨率/码率在开始时锁定）")
                changed -> {
                    val mode = if (fresh.videoMode) "视频直传" else "每次 $frames 帧"
                    toast("已更新：保留 $seconds 秒 · $mode")
                }
            }
        }
    }

    private fun prepare(rec: ScreenRingRecorder): Prepared {
        val dir = File(cacheDir, "clips").apply { mkdirs() }
        pruneClips(dir, keep = 3)
        val file = File(dir, "clip_${System.currentTimeMillis()}.mp4")
        val result = rec.exportClip(file)
        if (!result.ok) {
            return Prepared(file, emptyList(), null, 0, false, ok = false, message = result.message)
        }
        val frames = if (prefs.videoMode) {
            emptyList()
        } else {
            val quality = prefs.quality()
            val extracted = FrameExtractor.extractJpegBase64(
                file, prefs.frameCount, quality.frameShortSide, quality.frameQuality
            )
            if (extracted.isEmpty()) {
                // 抽不到画面就直接失败：否则会给模型发一条「附带截图」却没有图的请求，它会照着编
                return Prepared(
                    file = file,
                    frames = emptyList(),
                    savedPath = null,
                    durationMs = result.durationMs,
                    hasAudio = false,
                    ok = false,
                    message = "抽帧失败，请重试或换一档画质"
                )
            }
            extracted
        }
        val trackPresent = MediaProbe.hasAudioTrack(file)
        // 有音轨不代表有声音：被 App 屏蔽时 AudioRecord 只会返回全 0
        val hasAudio = trackPresent && result.audioPeak > SILENCE_PEAK
        var saved: String? = null
        if (prefs.saveClip) {
            try {
                val target = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: File(filesDir, "clips").also { it.mkdirs() }
                val dst = File(target, file.name)
                file.copyTo(dst, overwrite = true)
                saved = dst.absolutePath
            } catch (t: Throwable) {
                Log.w(TAG, "save clip failed", t)
            }
        }
        return Prepared(file, frames, saved, result.durationMs, hasAudio, trackPresent)
    }

    /** 缓存目录里的片段只留最近几个，避免缓存无限膨胀（每个几十 MB） */
    private fun pruneClips(dir: File, keep: Int) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return
            if (files.size <= keep) return
            files.sortedByDescending { it.lastModified() }
                .drop(keep)
                .forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "prune clips failed", t)
        }
    }

    /** 没声音时给用户一个可排查的方向，而不是一句“失败了” */
    private fun silenceMessage(prepared: Prepared): String {
        val head = if (prepared.trackPresent) {
            "⚠️ 这段片段里的声音是静音，模型只能看到画面。"
        } else {
            "⚠️ 这段片段没有音轨，模型只能看到画面。"
        }
        return head + "\n常见原因：目标 App 禁止内录（带版权内容的视频/音乐、语音通话类声音），" +
            "也可能那段时间确实没有声音在播放。"
    }

    private fun systemPrompt(): String = """
        你是一个手机屏幕实时助手。用户在使用手机的过程中随时向你提问，每次提问都会附带最近 ${prefs.clipSeconds} 秒屏幕的若干张截图（按时间先后排列，最后一张最接近提问瞬间），画面里可能出现聊天记录、网页、视频、游戏、文档等任意内容。

        请直接回答用户的问题：先给结论或下一步操作建议，再补充必要细节；如果截图信息不足，明确说明还缺什么。用简体中文回答，控制在 200 字以内，不要描述“我看到了几张截图”这类元信息。

        用 Markdown 正常排版即可，不用刻意限制写法：标题、列表、加粗、表格、代码块都行，公式用 LaTeX 写（行内 $...$ 或独立成行 $$...$$ 都可以）。只有两点请避免：不要输出 HTML 标签，不要用 +---+ 这种 ASCII 线框画表格（用 Markdown 表格）。
    """.trimIndent()

    private fun videoPrompt(hasAudio: Boolean, hearsAudio: Boolean): String {
        val audioHint = when {
            !hasAudio ->
                "这段录像没有声音轨，你听不到任何声音。不要臆测视频里说了什么、有什么音效；用户问到声音相关的内容时，直接说明你听不到。"
            hearsAudio ->
                "这段录像带声音轨，你可以听到画面里的声音，回答时可以把说话内容、语音提示、音效一并作为依据；但仍然不要编造没听到的内容。"
            else ->
                "这段录像带声音轨，但当前模型无法解析音频，不要臆测视频里说了什么、有什么音效；用户问到声音相关的内容时，直接说明你听不到。"
        }
        return """
            你是一个手机屏幕实时助手。用户在使用手机的过程中随时向你提问，每次提问都会附带最近 ${prefs.clipSeconds} 秒的手机屏幕录像（mp4，画面里可能出现聊天记录、网页、视频、游戏、文档等任意内容）。

            $audioHint

            请结合画面直接回答用户的问题：先给结论或下一步操作建议，再补充必要细节；如果信息不足，明确说明还缺什么。用简体中文回答，控制在 200 字以内，不要描述“我看到了录像”这类元信息。

            用 Markdown 正常排版即可，不用刻意限制写法：标题、列表、加粗、表格、代码块都行，公式用 LaTeX 写（行内 $...$ 或独立成行 $$...$$ 都可以）。只有两点请避免：不要输出 HTML 标签，不要用 +---+ 这种 ASCII 线框画表格（用 Markdown 表格）。
        """.trimIndent()
    }

    // ------------------------------------------------------------------ 停止

    private fun stopEverything(showToast: Boolean) {
        if (stopping) return
        stopping = true
        scope.launch {
            try {
                utterance?.stop()
            } catch (_: Throwable) {
            }
            utterance = null
            try {
                speech?.cancel()
            } catch (_: Throwable) {
            }
            speech = null
            try {
                overlay?.destroy()
            } catch (_: Throwable) {
            }
            overlay = null
            try {
                audioHub?.stop()
            } catch (_: Throwable) {
            }
            audioHub = null
            try {
                recorder?.release()
            } catch (_: Throwable) {
            }
            recorder = null
            val proj = projection
            projection = null
            try {
                proj?.stop()
            } catch (_: Throwable) {
            }
            ready = false
            isRunning = false
            if (showToast) toast("助手已停止")
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        ready = false
        stopping = true
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        try {
            audioHub?.stop()
        } catch (_: Throwable) {
        }
        audioHub = null
        try {
            overlay?.destroy()
        } catch (_: Throwable) {
        }
        overlay = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕助手运行状态",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "常驻通知，用于保持录屏与悬浮窗运行"
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_screen)
            .setContentTitle("屏幕助手正在运行")
            .setContentText("保留最近 ${prefs.clipSeconds} 秒画面与声音，点悬浮球提问")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopPending)
            .build()
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
