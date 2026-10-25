package com.screenmemory.app.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕“时光机”录制器。
 *
 * 用 MediaCodec 直接把 VirtualDisplay 的画面编成 H.264、把 PCM 编成 AAC，
 * 编码后的帧放进内存环形缓冲（按时间窗口裁剪），不落盘、不停止编码。
 * 用户提问时把缓冲区里最近 N 秒的帧重新封装成一个 MP4。
 */
class ScreenRingRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val audioSampleRate: Int,
    private val audioChannels: Int = 1,
    private val bitRate: Int = 3_000_000,
    private val fps: Int = 20,
    windowSeconds: Int = 30
) {

    companion object {
        private const val TAG = "ScreenRing"
        private const val MAX_BUFFER_BYTES = 160L * 1024 * 1024
        const val MIN_WINDOW_SECONDS = 5
        const val MAX_WINDOW_SECONDS = 60
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
        val audioPeak: Int = 0
    )

    private class AudioPeak(val ptsUs: Long, val value: Int)

    private val lock = Any()
    private val videoFrames = ArrayDeque<Frame>()
    private val audioFrames = ArrayDeque<Frame>()

    /** 与音频帧同步推进的峰值记录，用来判断「采到的到底是不是静音」 */
    private val audioPeaks = ArrayDeque<AudioPeak>()
    private var bufferedBytes = 0L

    /** 非 null 表示窗口已冻结，此刻的 pts 就是片段的终点；期间新帧一律丢弃 */
    @Volatile
    private var frozenEndUs: Long? = null

    private var videoCsd0: ByteBuffer? = null
    private var videoCsd1: ByteBuffer? = null
    private var audioCsd0: ByteBuffer? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val running = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<Frame>()

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            startVideo()
            startAudio()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            running.set(false)
            release()
            throw t
        }
    }

    private fun startVideo() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        inputSurface = surface

        virtualDisplay = projection.createVirtualDisplay(
            "screen-memory",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            Handler(Looper.getMainLooper())
        )

        videoThread = Thread({ videoLoop(codec) }, "video-drain").also { it.start() }
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
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release display", t)
        }
        virtualDisplay = null
        try {
            videoThread?.join(800)
        } catch (_: InterruptedException) {
        }
        try {
            audioThread?.join(800)
        } catch (_: InterruptedException) {
        }
        videoThread = null
        audioThread = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        audioCodec?.let { stopQuietly(it) }
        audioCodec = null
        videoCodec?.let { stopQuietly(it) }
        videoCodec = null
        synchronized(lock) {
            videoFrames.clear()
            audioFrames.clear()
            audioPeaks.clear()
            bufferedBytes = 0
        }
        audioQueue.clear()
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
        while (running.get()) {
            try {
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        videoCsd0 = out.getByteBuffer("csd-0")?.duplicate()
                        videoCsd1 = out.getByteBuffer("csd-1")?.duplicate()
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

    /** 只保留窗口时长 + 1.5 秒余量（余量用于保证有可用的关键帧起点） */
    private fun trimLocked(latestPtsUs: Long) {
        val cutoff = latestPtsUs - windowUs - 1_500_000L
        while (videoFrames.size > 2) {
            val head = videoFrames.peekFirst() ?: break
            if (head.ptsUs < cutoff) {
                videoFrames.removeFirst()
                bufferedBytes -= head.data.size
            } else {
                break
            }
        }
        while (audioFrames.size > 2) {
            val head = audioFrames.peekFirst() ?: break
            if (head.ptsUs < cutoff) {
                audioFrames.removeFirst()
                bufferedBytes -= head.data.size
            } else {
                break
            }
        }
        while (bufferedBytes > MAX_BUFFER_BYTES) {
            val v = videoFrames.pollFirst() ?: break
            bufferedBytes -= v.data.size
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
        val end = frozenEndUs ?: videoFrames.peekLast()?.ptsUs ?: 0L
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
                audioCsd0 = codec.outputFormat.getByteBuffer("csd-0")?.duplicate()
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
        frozenEndUs = videoFrames.peekLast()?.ptsUs
        frozenEndUs != null
    }

    fun unfreezeWindow() {
        frozenEndUs = null
    }

    fun isFrozen(): Boolean = frozenEndUs != null

    /** 缓冲区里最新一帧的时间戳（微秒），空缓冲返回 0 */
    fun latestPtsUs(): Long = synchronized(lock) { videoFrames.peekLast()?.ptsUs ?: 0L }

    /**
     * 把缓冲区中最近 [windowUs] 的帧封装成 MP4。
     * 起点会对齐到最近的关键帧（最多损失约 1 秒）。
     * 若处于冻结状态（点了悬浮球），终点固定为冻结时刻，不包含之后的面板与键盘。
     */
    fun exportClip(outFile: File): ClipResult {
        val videoCopy: List<Frame>
        val audioCopy: List<Frame>
        val peakCopy: List<AudioPeak>
        val csd0: ByteBuffer?
        val csd1: ByteBuffer?
        val acsd: ByteBuffer?
        synchronized(lock) {
            videoCopy = ArrayList(videoFrames)
            audioCopy = ArrayList(audioFrames)
            peakCopy = ArrayList(audioPeaks)
            csd0 = videoCsd0?.duplicate()
            csd1 = videoCsd1?.duplicate()
            acsd = audioCsd0?.duplicate()
        }
        if (videoCopy.isEmpty()) {
            return ClipResult(false, 0, 0, "还没录到画面，请过几秒再问")
        }

        // 有冻结点时，先把之后的帧（面板、键盘）丢掉
        val cutEnd = frozenEndUs
        val usable: List<Frame> = if (cutEnd != null) {
            val cut = videoCopy.filter { it.ptsUs <= cutEnd }
            if (cut.size < 2) {
                return ClipResult(false, 0, 0, "冻结窗口里的画面不足，请稍后再试")
            }
            cut
        } else {
            videoCopy
        }

        val endPts = usable.last().ptsUs
        val window = windowUs
        val target = endPts - window
        var startIndex = -1
        for (i in usable.indices) {
            val f = usable[i]
            val isKey = (f.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (isKey && f.ptsUs >= target) {
                startIndex = i
                break
            }
        }
        if (startIndex < 0) {
            for (i in usable.indices) {
                if ((usable[i].flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    startIndex = i
                    break
                }
            }
        }
        if (startIndex < 0) {
            return ClipResult(false, 0, 0, "画面还在准备中，请稍后再试")
        }

        val vList = usable.subList(startIndex, usable.size)
        val base = vList[0].ptsUs
        val aList = audioCopy.filter {
            it.ptsUs >= base && (cutEnd == null || it.ptsUs <= cutEnd)
        }

        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        var durationMs = 0L
        var lastPts = base
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoFormat =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            csd0?.let { videoFormat.setByteBuffer("csd-0", it) }
            csd1?.let { videoFormat.setByteBuffer("csd-1", it) }
            val videoTrack = muxer.addTrack(videoFormat)

            var audioTrack = -1
            if (aList.isNotEmpty() && acsd != null) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels
                )
                audioFormat.setByteBuffer("csd-0", acsd.duplicate())
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
        val audioPeak = peakCopy
            .filter { it.ptsUs in base..lastPts }
            .maxOfOrNull { it.value } ?: 0
        return ClipResult(true, durationMs, outFile.length(), "", audioPeak)
    }
}
