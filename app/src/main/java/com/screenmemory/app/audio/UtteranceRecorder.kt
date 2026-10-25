package com.screenmemory.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * “按住说话”录音器。
 *
 * 如果录制服务已经在用麦克风（AudioHub 已开），就直接挂到 AudioHub 上取 PCM，
 * 避免第二个 AudioRecord 拿到静音；否则自己开一路麦克风。
 */
class UtteranceRecorder(
    private val context: Context,
    private val hub: AudioHub?,
    private val ownSampleRate: Int = AudioHub.MIC_SAMPLE_RATE
) {

    companion object {
        private const val TAG = "Utterance"
    }

    private val pcm = ByteArrayOutputStream()
    private val pcmLock = Any()

    @Volatile
    private var capturing = false

    private var ownRecord: AudioRecord? = null
    private var ownThread: Thread? = null

    private val hubSink = object : AudioHub.Sink {
        override fun onPcm(buffer: ShortArray, size: Int, ptsUs: Long) {
            if (!capturing) return
            append(buffer, size)
        }
    }

    private fun append(buffer: ShortArray, size: Int) {
        val bytes = ByteArray(size * 2)
        var j = 0
        for (i in 0 until size) {
            val v = buffer[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        synchronized(pcmLock) { pcm.write(bytes, 0, bytes.size) }
    }

    fun start(): Boolean {
        synchronized(pcmLock) { pcm.reset() }
        capturing = true
        if (hub != null) {
            hub.addSink(hubSink)
            return true
        }
        return startOwnRecord()
    }

    @SuppressLint("MissingPermission")
    private fun startOwnRecord(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            ownSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                ownSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, ownSampleRate / 2)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "create record failed", t)
            capturing = false
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            capturing = false
            return false
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            rec.release()
            capturing = false
            return false
        }
        ownRecord = rec
        ownThread = Thread({
            val buffer = ShortArray(1024)
            while (capturing) {
                val n = try {
                    rec.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    -1
                }
                if (n > 0) append(buffer, n) else if (n < 0) break
            }
        }, "utterance-record").also { it.start() }
        return true
    }

    /** 结束录音，返回 16bit 单声道 WAV */
    fun stop(): ByteArray {
        capturing = false
        if (hub != null) {
            hub.removeSink(hubSink)
        }
        try {
            ownThread?.join(500)
        } catch (_: InterruptedException) {
        }
        ownThread = null
        try {
            ownRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            ownRecord?.release()
        } catch (_: Throwable) {
        }
        ownRecord = null

        val raw = synchronized(pcmLock) { pcm.toByteArray() }
        val rate = if (hub != null) hub.sampleRate else ownSampleRate
        return Wav.wrap(raw, rate, 1)
    }
}
