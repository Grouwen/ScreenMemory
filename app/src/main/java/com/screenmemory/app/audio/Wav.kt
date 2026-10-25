package com.screenmemory.app.audio

import java.io.ByteArrayOutputStream

/** 把 16bit 单声道 PCM 包装成 WAV 文件字节流（供语音转写接口上传）。 */
object Wav {

    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val byteRate = sampleRate * channels * 2

        fun writeInt(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        fun writeShort(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray())
        writeInt(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeInt(16)
        writeShort(1)              // PCM
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(channels * 2)   // block align
        writeShort(16)             // bits per sample
        out.write("data".toByteArray())
        writeInt(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
