package com.screenmemory.app.capture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/** 从导出的 MP4 里等间隔抽取关键帧，转成 base64 JPEG 供多模态模型使用。 */
object FrameExtractor {

    private const val TAG = "FrameExtractor"

    /**
     * @param maxShortSide 抽帧后的短边上限；短边不超过它时保持原分辨率（不放大、不做无谓的重采样）
     */
    fun extractJpegBase64(file: File, count: Int, maxShortSide: Int, quality: Int): List<String> {
        if (!file.exists() || count <= 0) return emptyList()
        val retriever = MediaMetadataRetriever()
        val result = ArrayList<String>()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val n = count.coerceIn(1, 20)
            for (i in 0 until n) {
                val timeMs = if (n == 1 || durationMs <= 0) {
                    durationMs / 2
                } else {
                    durationMs * i / (n - 1)
                }
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val scaled = scale(bitmap, maxShortSide)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                result.add(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        } catch (t: Throwable) {
            // 返回空列表，由上层决定怎么提示；不能静默当成「这次没有画面」发出去
            Log.w(TAG, "extract frames failed: ${file.name}", t)
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        return result
    }

    /**
     * 按短边缩放。竖屏录制的短边是宽度、横屏是高度，用同一套规则两个方向都能收敛；
     * 之前只比 bitmap.width，竖屏时短边永远等于宽度，等于几乎从不缩放。
     */
    private fun scale(bitmap: Bitmap, maxShortSide: Int): Bitmap {
        val shortSide = min(bitmap.width, bitmap.height)
        if (shortSide <= maxShortSide) return bitmap
        val ratio = maxShortSide.toFloat() / shortSide
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
