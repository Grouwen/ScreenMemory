package com.screenmemory.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** 相册选图 / 截图附件的统一压缩与 base64 编码。 */
object ImagePayload {

    data class Attachment(val bitmap: Bitmap, val base64: String)

    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    fun fromUri(context: Context, uri: Uri): Attachment? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2
        }
        val bitmap = try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (_: Throwable) {
            null
        } ?: return null
        return Attachment(bitmap, encode(bitmap))
    }

    fun encode(bitmap: Bitmap): String {
        val scale = min(
            1f,
            MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1)
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decode(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }
}
