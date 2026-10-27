package com.screenmemory.app.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/**
 * 屏幕“时光机”录制器。
 *
 * 用 MediaCodec 直接把 VirtualDisplay 的画面编成 H.264、把 PCM 编成 AAC，
 * 编码后的帧放进内存环形缓冲（按时间窗口裁剪），不落盘、不停止编码。
 * 用户提问时把缓冲区里最近 N 秒的帧重新封装成 MP4；屏幕旋转会按方向分段，
 * 因为单个 MP4 视频轨不能中途改变分辨率。
 */
class ScreenRingRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    dpi: Int,
    private val audioSampleRate: Int,
    private val audioChannels: Int = 1,
    private val bitRate: Int = 3_000_000,
    private val fps: Int = 20,
    private val preferSoftwareEncoder: Boolean = false,
    windowSeconds: Int = 30
) {

    companion object {
        private const val TAG = "ScreenRing"
        private const val TIMING_TAG = "Timing"
        private const val MAX_BUFFER_BYTES = 256L * 1024 * 1024
        const val MIN_WINDOW_SECONDS = 5
        const val MAX_WINDOW_SECONDS = 180
    }

    /** 环形缓冲窗口，可在录制过程中动态调整 */
    @Volatile
    private var windowUs: Long = coerceWindow(windowSeconds) * 1_000_000L

    fun updateWindowSeconds(seconds: Int) {
        windowUs = coerceWindow(seconds) * 1_000_000L
    }

    private fun coerceWindow(seconds: Int): Int =
        seconds.coerceIn(MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS)

    class Frame(val ptsUs: Long, val flags: Int, val data: ByteArray)

    class ClipResult(
        val ok: Boolean,
        val durationMs: Long,
        val sizeBytes: Long,
        val message: String = "",
        /** 片段内音频的峰值（0–32767）；全 0 说明只有静音，通常是被目标 App 屏蔽了内录 */
        val audioPeak: Int = 0,
        /**
         * 视频直传时，窗口里还有旋转前 / 其他尺寸的画面，但 MP4 无法混轨。
         * 这种情况下只导出了最新方向，调用方需要提示用户片段被缩短了。
         */
        val rotationTrimmed: Boolean = false,
        val videoBitRateBps: Long = 0,
        val encoderName: String = "",
        val bitrateMode: String = ""
    )

    private class AudioPeak(val ptsUs: Long, val value: Int)

    /** 已经结束采集的一段视频。旋转屏幕会封存当前段，新方向写进新段。 */
    private class VideoSegment(
        val width: Int,
        val height: Int,
        val frames: ArrayList<Frame> = ArrayList(),
        var csd0: ByteBuffer? = null,
        var csd1: ByteBuffer? = null,
        val encoderName: String = "",
        val bitrateMode: String = ""
    )

    class ClipPart(val file: File, val result: ClipResult)

    private class ExportPiece(
        val width: Int,
        val height: Int,
        val frames: List<Frame>,
        val csd0: ByteBuffer?,
        val csd1: ByteBuffer?,
        val encoderName: String,
        val bitrateMode: String
    )

    private class ExportState(
        val pieces: List<ExportPiece>,
        val audio: List<Frame>,
        val peaks: List<AudioPeak>,
        val audioCsd: ByteBuffer?,
        val endUs: Long,
        val activeVideoEmpty: Boolean
    )

    private class VideoEncoderCandidate(
        val name: String,
        val software: Boolean
    )

    private val lock = Any()
    private val videoFrames = ArrayDeque<Frame>()
    private val archivedSegments = ArrayDeque<VideoSegment>()
    private val audioFrames = ArrayDeque<Frame>()

    /** 与音频帧同步推进的峰值记录，用来判断「采到的到底是不是静音」 */
    private val audioPeaks = ArrayDeque<AudioPeak>()
    private var bufferedBytes = 0L

    /** 非 null 表示窗口已冻结，此刻的 pts 就是片段的终点；期间新帧一律丢弃 */
    @Volatile
    private var frozenEndUs: Long? = null

    @Volatile
    private var currentWidth = width

    @Volatile
    private var currentHeight = height

    @Volatile
    private var currentDpi = dpi

    private var videoCsd0: ByteBuffer? = null
    private var videoCsd1: ByteBuffer? = null
    private var audioCsd0: ByteBuffer? = null

    private var videoCodec: MediaCodec? = null
    @Volatile
    private var activeEncoderName = ""
    @Volatile
    private var activeBitrateMode = ""
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val running = AtomicBoolean(false)
    private val videoRunning = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<Frame>()
    private val videoLifecycleLock = Any()
    private val videoControl = Executors.newSingleThreadExecutor { task ->
        Thread(task, "video-control")
    }

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            synchronized(videoLifecycleLock) { startVideo() }
            startAudio()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            running.set(false)
            release()
            throw t
        }
    }

    /**
     * 屏幕旋转或尺寸变化时调用。
     *
     * 旧段先封存，再重建虚拟屏和视频编码器。音频编码器、AudioHub 和已有音频帧
     * 都保持不动，所以不会把环境音 / 网课声音切断。
     */
    fun switchVideoGeometry(width: Int, height: Int, dpi: Int) {
        if (width < 160 || height < 160 || !running.get()) return
        try {
            videoControl.execute {
                synchronized(videoLifecycleLock) {
                    if (!running.get() ||
                        (currentWidth == width && currentHeight == height && currentDpi == dpi)
                    ) {
                        return@synchronized
                    }
                    Log.i(TAG, "rotate video ${currentWidth}x$currentHeight -> ${width}x$height")
                    stopVideoPipeline(releaseDisplay = false)
                    synchronized(lock) {
                        archiveCurrentVideoLocked()
                        currentWidth = width
                        currentHeight = height
                        currentDpi = dpi
                    }
                    try {
                        startVideo()
                    } catch (t: Throwable) {
                        Log.e(TAG, "restart video after rotation failed", t)
                        stopVideoPipeline(releaseDisplay = false)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // 服务正在释放，最后一帧方向变化可以安全忽略。
        }
    }

    private fun startVideo() {
        var lastError: Throwable? = null
        for (candidate in videoEncoderCandidates()) {
            val attempts = if (candidate.software) listOf(true) else listOf(true, false)
            for (explicitVbr in attempts) {
                try {
                    startVideoCandidate(candidate, explicitVbr)
                    return
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(
                        TAG,
                        "video encoder failed: ${candidate.name} software=${candidate.software} " +
                            "explicitVbr=$explicitVbr",
                        t
                    )
                    stopVideoPipeline(releaseDisplay = false)
                }
            }
        }
        throw IllegalStateException("没有可用的 AVC 视频编码器", lastError)
    }

    private fun startVideoCandidate(candidate: VideoEncoderCandidate, explicitVbr: Boolean) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val videoWidth = currentWidth
        val videoHeight = currentHeight
        val maxEncodeFps = if (preferSoftwareEncoder) minOf(fps, 10) else fps
        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // KEY_FRAME_RATE 只是码控提示；这个 key 才要求 Surface 输入编码器丢弃超额帧。
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, maxEncodeFps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        if (explicitVbr) {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        val codec = MediaCodec.createByCodecName(candidate.name)
        Log.i(
            TAG,
            "video ${videoWidth}x$videoHeight ${bitRate / 1000}kbps ${fps}fps " +
                "maxEncoderFps=$maxEncodeFps " +
                "encoder=${candidate.name} software=${candidate.software} " +
                "mode=${if (explicitVbr) "VBR(explicit)" else "VBR(unspecified)"}"
        )
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        inputSurface = surface
        synchronized(lock) {
            activeEncoderName = candidate.name
            activeBitrateMode = if (explicitVbr) "VBR(explicit)" else "VBR(unspecified)"
        }

        val display = virtualDisplay
        if (display == null) {
            // MediaProjection 在 targetSdk 34 上只允许创建一次 VirtualDisplay。
            // 后续旋转必须复用这个实例，通过 resize + setSurface 更新。
            virtualDisplay = projection.createVirtualDisplay(
                "screen-memory",
                videoWidth,
                videoHeight,
                currentDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                Handler(Looper.getMainLooper())
            ) ?: throw IllegalStateException("VirtualDisplay 创建失败")
        } else {
            display.resize(videoWidth, videoHeight, currentDpi)
            display.setSurface(surface)
        }

        videoRunning.set(true)
        videoThread = Thread({ videoLoop(codec) }, "video-drain").also { it.start() }
    }

    private fun videoEncoderCandidates(): List<VideoEncoderCandidate> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val supported = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.contains(mime) }
                .filter { info ->
                    try {
                        info.getCapabilitiesForType(mime).colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                    } catch (_: Throwable) {
                        false
                    }
                }
                .map {
                    VideoEncoderCandidate(
                        name = it.name,
                        software = it.isSoftwareOnly
                    )
                }
        } catch (t: Throwable) {
            Log.w(TAG, "enumerate video encoders failed", t)
            emptyList()
        }
        val software = supported.filter { it.software }
        val hardware = supported.filterNot { it.software }
        if (preferSoftwareEncoder && software.isEmpty()) {
            Log.w(TAG, "没有可用的软件 AVC 编码器，直接使用硬件编码器")
        }
        return if (preferSoftwareEncoder) software + hardware else hardware + software
    }

    private fun startAudio() {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels)
                .apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        audioThread = Thread({ audioLoop(codec) }, "audio-drain").also { it.start() }
    }

    fun release() {
        running.set(false)
        videoControl.shutdownNow()
        synchronized(videoLifecycleLock) {
            stopVideoPipeline(releaseDisplay = true)
        }
        try {
            audioThread?.join(800)
        } catch (_: InterruptedException) {
        }
        audioThread = null
        audioCodec?.let { stopQuietly(it) }
        audioCodec = null
        synchronized(lock) {
            videoFrames.clear()
            archivedSegments.clear()
            audioFrames.clear()
            audioPeaks.clear()
            bufferedBytes = 0
        }
        audioQueue.clear()
    }

    /** 只停止视频这一路；全局 running / 音频管线保持不变。 */
    private fun stopVideoPipeline(releaseDisplay: Boolean) {
        videoRunning.set(false)
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "detach display surface", t)
        }
        if (releaseDisplay) {
            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release display", t)
            }
            virtualDisplay = null
        }
        try {
            videoThread?.join(800)
        } catch (_: InterruptedException) {
        }
        videoThread = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        videoCodec?.let { stopQuietly(it) }
        videoCodec = null
    }

    private fun stopQuietly(codec: MediaCodec) {
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 编码

    private fun videoLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get() && videoRunning.get()) {
            try {
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val csd0 = out.getByteBuffer("csd-0")?.duplicate()
                        val csd1 = out.getByteBuffer("csd-1")?.duplicate()
                        synchronized(lock) {
                            videoCsd0 = csd0
                            videoCsd1 = csd1
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (index >= 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val data = ByteArray(info.size)
                                buffer.get(data)
                                pushVideo(Frame(info.presentationTimeUs, info.flags, data))
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "video loop stopped", t)
                break
            }
        }
    }

    private fun pushVideo(frame: Frame) {
        synchronized(lock) {
            if (frozenEndUs != null) return // 冻结期间不收录，避免面板/键盘进缓冲
            videoFrames.addLast(frame)
            bufferedBytes += frame.data.size
            trimLocked(frame.ptsUs)
        }
    }

    /** 把当前方向缓冲封存成独立视频段，供抽帧模式合并、视频模式判定截断。 */
    private fun archiveCurrentVideoLocked() {
        if (videoFrames.isNotEmpty()) {
            archivedSegments.addLast(
                VideoSegment(
                    width = currentWidth,
                    height = currentHeight,
                    frames = ArrayList(videoFrames),
                    csd0 = videoCsd0?.duplicate(),
                    csd1 = videoCsd1?.duplicate(),
                    encoderName = activeEncoderName,
                    bitrateMode = activeBitrateMode
                )
            )
        }
        videoFrames.clear()
        videoCsd0 = null
        videoCsd1 = null
    }

    /** 只保留窗口时长 + 1.5 秒余量（余量用于保证有可用的关键帧起点） */
    private fun trimLocked(latestPtsUs: Long) {
        val cutoff = latestPtsUs - windowUs - 1_500_000L
        val archivedIterator = archivedSegments.iterator()
        while (archivedIterator.hasNext()) {
            val segment = archivedIterator.next()
            while (segment.frames.isNotEmpty() && segment.frames.first().ptsUs < cutoff) {
                bufferedBytes -= segment.frames.removeAt(0).data.size
            }
            if (segment.frames.isEmpty()) archivedIterator.remove()
        }
        while (videoFrames.isNotEmpty() && videoFrames.first().ptsUs < cutoff) {
            bufferedBytes -= videoFrames.removeFirst().data.size
        }
        while (audioFrames.isNotEmpty() && audioFrames.first().ptsUs < cutoff) {
            bufferedBytes -= audioFrames.removeFirst().data.size
        }
        while (bufferedBytes > MAX_BUFFER_BYTES) {
            val archivedHead = archivedSegments.firstOrNull()?.frames?.firstOrNull()?.ptsUs
            val activeHead = videoFrames.firstOrNull()?.ptsUs
            val audioHead = audioFrames.firstOrNull()?.ptsUs
            val oldest = listOfNotNull(archivedHead, activeHead, audioHead).minOrNull() ?: break
            when (oldest) {
                archivedHead -> {
                    val segment = archivedSegments.first()
                    bufferedBytes -= segment.frames.removeAt(0).data.size
                    if (segment.frames.isEmpty()) archivedSegments.removeFirst()
                }
                activeHead -> bufferedBytes -= videoFrames.removeFirst().data.size
                audioHead -> bufferedBytes -= audioFrames.removeFirst().data.size
            }
        }
    }

    /** 音频时间戳持续前进，但静止画面可能很久不产视频帧，音频也要独立裁剪。 */
    private fun trimAudioLocked(latestPtsUs: Long) {
        val cutoff = latestPtsUs - windowUs - 1_500_000L
        while (audioFrames.size > 2) {
            val head = audioFrames.peekFirst() ?: break
            if (head.ptsUs < cutoff) {
                audioFrames.removeFirst()
                bufferedBytes -= head.data.size
            } else {
                break
            }
        }
    }

    fun offerAudio(pcm: ShortArray, size: Int, ptsUs: Long) {
        if (!running.get()) return
        if (frozenEndUs != null) return
        val bytes = ByteArray(size * 2)
        var j = 0
        var peak = 0
        for (i in 0 until size) {
            val v = pcm[i].toInt()
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        synchronized(lock) {
            audioPeaks.addLast(AudioPeak(ptsUs, peak))
            while (audioPeaks.size > 2) {
                val head = audioPeaks.peekFirst() ?: break
                if (head.ptsUs < ptsUs - windowUs - 1_500_000L) audioPeaks.removeFirst() else break
            }
        }
        if (audioQueue.size > 400) audioQueue.poll()
        audioQueue.offer(Frame(ptsUs, 0, bytes))
    }

    /** 最近 [seconds] 秒内采到的音频峰值，用于「打开面板时提示当前没声音」 */
    fun recentAudioPeak(seconds: Int): Int = synchronized(lock) {
        val end = frozenEndUs ?: latestVideoPtsLocked()
        if (end == 0L) {
            0
        } else {
            val from = end - seconds * 1_000_000L
            var peak = 0
            for (item in audioPeaks) {
                if (item.ptsUs >= from && item.ptsUs <= end && item.value > peak) peak = item.value
            }
            peak
        }
    }

    private fun audioLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val item = try {
                audioQueue.poll(10, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (item != null) {
                try {
                    val inputIndex = codec.dequeueInputBuffer(5_000L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        if (input != null) {
                            input.clear()
                            input.put(item.data)
                            codec.queueInputBuffer(inputIndex, 0, item.data.size, item.ptsUs, 0)
                        } else {
                            audioQueue.offer(item)
                        }
                    } else {
                        audioQueue.offer(item)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "feed audio failed", t)
                    break
                }
            }
            drainAudio(codec, info)
        }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (t: Throwable) {
                return
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val csd = codec.outputFormat.getByteBuffer("csd-0")?.duplicate()
                synchronized(lock) { audioCsd0 = csd }
                continue
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            ) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val data = ByteArray(info.size)
                buffer.get(data)
                synchronized(lock) {
                    audioFrames.addLast(Frame(info.presentationTimeUs, info.flags, data))
                    bufferedBytes += data.size
                    trimAudioLocked(info.presentationTimeUs)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 冻结窗口：把「此刻之前的一个窗口」锁住，之后进来的帧一律丢弃。
     *
     * 为什么不是记一个时间戳、导出时再过滤：屏幕静止时编码器可能十几秒不产帧，
     * 缓冲区里「最新一帧」的时间戳远早于现在；等面板出现、新帧一到，裁剪逻辑会按
     * 新的最新时间戳把旧帧全部清掉，导致截断点直接掉出缓冲区。冻结后缓冲区不再滚动，
     * 面板开多久都不会丢。
     */
    fun freezeWindow(): Boolean = synchronized(lock) {
        if (videoFrames.size < 2) return false
        val end = videoFrames.peekLast()?.ptsUs ?: return false
        if (videoFrames.none { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }) return false
        frozenEndUs = end
        true
    }

    fun unfreezeWindow() {
        frozenEndUs = null
    }

    fun isFrozen(): Boolean = frozenEndUs != null

    /** 缓冲区里最新一帧的时间戳（微秒），空缓冲返回 0 */
    fun latestPtsUs(): Long = synchronized(lock) {
        latestVideoPtsLocked()
    }

    private fun latestVideoPtsLocked(): Long {
        val archived = archivedSegments.asSequence()
            .mapNotNull { it.frames.lastOrNull()?.ptsUs }
            .maxOrNull() ?: 0L
        val active = videoFrames.peekLast()?.ptsUs ?: 0L
        return maxOf(archived, active)
    }

    /**
     * 视频直传只能用一个尺寸的 MP4，所以选最新方向的一段。
     * 如果窗口里还有旋转前的画面，[ClipResult.rotationTrimmed] 会置为 true。
     */
    fun exportLatestClip(outFile: File): ClipPart? {
        val state = snapshotExportState()
        val eligible = state.pieces.filter { pieceHasWindowFrames(it, state) }
        val latest = eligible.lastOrNull() ?: return null
        val rotationChangedMedia =
            eligible.size > 1 || state.activeVideoEmpty || latest !== state.pieces.lastOrNull()
        return ClipPart(outFile, exportPiece(state, latest, outFile, rotationChangedMedia))
    }

    /**
     * 抽帧模式使用：把窗口内每个方向各导出一个小 MP4，调用方再按时间顺序合并截图。
     */
    fun exportRecentClips(outDir: File, prefix: String): List<ClipPart> {
        outDir.mkdirs()
        val state = snapshotExportState()
        val eligible = state.pieces.filter { pieceHasWindowFrames(it, state) }
        val result = ArrayList<ClipPart>()
        eligible.forEachIndexed { index, piece ->
            val file = File(outDir, "${prefix}_${index + 1}.mp4")
            val clip = exportPiece(state, piece, file, hasEarlier = false)
            if (clip.ok) result.add(ClipPart(file, clip))
        }
        return result
    }

    /** 兼容旧调用：视频直传使用最新方向。 */
    fun exportClip(outFile: File): ClipResult =
        exportLatestClip(outFile)?.result
            ?: ClipResult(false, 0, 0, "还没录到画面，请过几秒再问")

    private fun snapshotExportState(): ExportState = synchronized(lock) {
        val pieces = ArrayList<ExportPiece>()
        for (segment in archivedSegments) {
            if (segment.frames.isNotEmpty()) {
                pieces.add(
                    ExportPiece(
                        segment.width,
                        segment.height,
                        ArrayList(segment.frames),
                        segment.csd0?.duplicate(),
                        segment.csd1?.duplicate(),
                        segment.encoderName,
                        segment.bitrateMode
                    )
                )
            }
        }
        if (videoFrames.isNotEmpty()) {
            pieces.add(
                ExportPiece(
                    currentWidth,
                    currentHeight,
                    ArrayList(videoFrames),
                    videoCsd0?.duplicate(),
                    videoCsd1?.duplicate(),
                    activeEncoderName,
                    activeBitrateMode
                )
            )
        }
        ExportState(
            pieces = pieces,
            audio = ArrayList(audioFrames),
            peaks = ArrayList(audioPeaks),
            audioCsd = audioCsd0?.duplicate(),
            endUs = frozenEndUs ?: latestVideoPtsLocked(),
            activeVideoEmpty = videoFrames.isEmpty() && archivedSegments.isNotEmpty()
        )
    }

    private fun pieceHasWindowFrames(piece: ExportPiece, state: ExportState): Boolean {
        if (state.endUs <= 0L) return false
        val windowStart = state.endUs - windowUs
        val last = piece.frames.lastOrNull { it.ptsUs <= state.endUs } ?: return false
        return last.ptsUs >= windowStart && piece.frames.any {
            it.ptsUs <= state.endUs &&
                (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        }
    }

    private fun exportPiece(
        state: ExportState,
        piece: ExportPiece,
        outFile: File,
        hasEarlier: Boolean
    ): ClipResult {
        val usable = piece.frames.filter { it.ptsUs <= state.endUs }
        if (usable.isEmpty()) {
            return ClipResult(false, 0, 0, "冻结窗口里的画面不足，请稍后再试")
        }

        val target = state.endUs - windowUs
        var startIndex = usable.indexOfFirst {
            it.ptsUs >= target &&
                (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        }
        if (startIndex < 0) {
            startIndex = usable.indexOfFirst {
                (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            }
        }
        if (startIndex < 0) {
            return ClipResult(false, 0, 0, "画面还在准备中，请稍后再试")
        }

        val vList = usable.subList(startIndex, usable.size)
        val base = vList[0].ptsUs
        val endPts = minOf(state.endUs, usable.last().ptsUs)
        val aList = state.audio.filter { it.ptsUs in base..endPts }
        val videoBytes = vList.sumOf { it.data.size.toLong() }
        val videoLastPts = vList.last().ptsUs
        val videoDurationMs = (videoLastPts - base) / 1000

        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        var durationMs: Long
        var lastPts = base
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoFormat =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, piece.width, piece.height)
            piece.csd0?.let { videoFormat.setByteBuffer("csd-0", it) }
            piece.csd1?.let { videoFormat.setByteBuffer("csd-1", it) }
            val videoTrack = muxer.addTrack(videoFormat)

            var audioTrack = -1
            if (aList.isNotEmpty() && state.audioCsd != null) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels
                )
                audioFormat.setByteBuffer("csd-0", state.audioCsd.duplicate())
                audioFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                audioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()
            val info = MediaCodec.BufferInfo()
            for (f in vList) {
                info.set(
                    0,
                    f.data.size,
                    (f.ptsUs - base).coerceAtLeast(0),
                    f.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                )
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(f.data), info)
                if (f.ptsUs > lastPts) lastPts = f.ptsUs
            }
            if (audioTrack >= 0) {
                for (f in aList) {
                    info.set(0, f.data.size, (f.ptsUs - base).coerceAtLeast(0), 0)
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(f.data), info)
                }
                if (aList.isNotEmpty() && aList.last().ptsUs > lastPts) {
                    lastPts = aList.last().ptsUs
                }
            }
            muxer.stop()
            durationMs = (lastPts - base) / 1000
        } catch (t: Throwable) {
            Log.e(TAG, "mux clip failed", t)
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            return ClipResult(false, 0, 0, "片段封装失败：${t.message}")
        }
        try {
            muxer.release()
        } catch (_: Throwable) {
        }
        val audioPeak = state.peaks
            .filter { it.ptsUs in base..lastPts }
            .maxOfOrNull { it.value } ?: 0
        val actualVideoBitRate = if (videoDurationMs > 0) {
            videoBytes * 8_000 / videoDurationMs
        } else {
            0
        }
        val actualVideoFps = if (videoDurationMs > 0) {
            (vList.size - 1) * 1000.0 / videoDurationMs
        } else {
            0.0
        }
        Log.i(
            TIMING_TAG,
            "码率实测 encoder=${piece.encoderName} mode=${piece.bitrateMode} " +
                "target=${bitRate / 1000}kbps actualVideo=${actualVideoBitRate / 1000}kbps " +
                "fps=${"%.1f".format(actualVideoFps)} " +
                "size=${piece.width}x${piece.height} duration=${videoDurationMs}ms " +
                "file=${outFile.length() / 1024}KB"
        )
        return ClipResult(
            ok = true,
            durationMs = durationMs,
            sizeBytes = outFile.length(),
            audioPeak = audioPeak,
            rotationTrimmed = hasEarlier,
            videoBitRateBps = actualVideoBitRate,
            encoderName = piece.encoderName,
            bitrateMode = piece.bitrateMode
        )
    }
}
