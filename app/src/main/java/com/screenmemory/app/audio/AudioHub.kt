package com.screenmemory.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * 全局唯一的音频采集枢纽：只开一路 AudioRecord，把 PCM 分发给多个订阅者
 * （编码器、按住说话录音器…），避免同一进程里两个 AudioRecord 抢麦克风。
 */
class AudioHub(val sampleRate: Int) {

    interface Sink {
        /** @param buffer 复用的缓冲区，订阅方必须立即拷贝需要的数据 */
        fun onPcm(buffer: ShortArray, size: Int, ptsUs: Long)
    }

    companion object {
        private const val TAG = "AudioHub"
        const val MIC_SAMPLE_RATE = 16000
        const val PLAYBACK_SAMPLE_RATE = 44100
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var started: Boolean = false
        private set

    private var startUs = 0L
    private var totalSamples = 0L

    fun addSink(sink: Sink) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    @SuppressLint("MissingPermission")
    fun startMic(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer, sampleRate / 2)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "create mic record failed", t)
            return false
        }
        return startWith(rec)
    }

    @SuppressLint("MissingPermission")
    fun startPlayback(projection: MediaProjection): Boolean {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, sampleRate / 2) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            startWith(rec)
        } catch (t: Throwable) {
            Log.e(TAG, "start playback capture failed", t)
            false
        }
    }

    private fun startWith(rec: AudioRecord): Boolean {
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            rec.release()
            return false
        }
        record = rec
        running = true
        startUs = System.nanoTime() / 1000L
        totalSamples = 0L
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            running = false
            rec.release()
            record = null
            return false
        }
        started = true
        thread = Thread({ loop() }, "audio-hub").also { it.start() }
        return true
    }

    private fun loop() {
        val buffer = ShortArray(1024)
        val rec = record ?: return
        while (running) {
            val n = try {
                rec.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "read failed", t)
                -1
            }
            if (n > 0) {
                val ptsUs = startUs + totalSamples * 1_000_000L / sampleRate
                totalSamples += n
                for (sink in sinks) {
                    try {
                        sink.onPcm(buffer, n, ptsUs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sink error", t)
                    }
                }
            } else if (n < 0) {
                Log.w(TAG, "read error $n, stop hub")
                break
            }
        }
        running = false
        started = false
    }

    fun stop() {
        running = false
        started = false
        try {
            thread?.join(600)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null
        sinks.clear()
    }
}
