package com.screenmemory.app.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/** 探测导出的片段里有没有音轨——没有就得提醒用户“模型听不到声音”。 */
object MediaProbe {

    private const val TAG = "MediaProbe"

    fun hasAudioTrack(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) return true
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "probe audio track failed", t)
            false
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }
}
