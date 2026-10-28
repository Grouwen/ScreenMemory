package com.screenmemory.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.screenmemory.app.util.AppStorage
import java.io.File

/**
 * 对话里附带的图片。
 *
 * 之前只把 `hasImage` 这个布尔值写进历史，图片本体用完就扔，所以在历史记录里
 * 只能看到一个「· 图片」的字样，回看时根本不知道当时发的什么图。
 *
 * 现在发出的图片会落盘成 JPEG（`Android/data/<包名>/files/chat_images/`），
 * 历史里只存文件名，既不撑大 `chat_history.json`，也能在回看时把图重新显示出来。
 * 放在外部私有目录，用户能直接看到、直接删。
 */
object ChatImages {

    private const val TAG = "ChatImages"

    /** 历史里显示用的缩略图最长边；气泡只有 168dp 宽，512 足够，再大只是白烧内存 */
    private const val THUMB_MAX_PX = 512

    /**
     * 缩略图缓存。
     * 历史页每发一次消息都会重建整个列表，不缓存的话每张图都要重新解码一遍，
     * 图片一多就能感觉到卡顿。
     */
    private val cache = object : LruCache<String, Bitmap>(6) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun dir(context: Context): File = AppStorage.imageDir(context)

    /**
     * 把已经编码好的 base64 图片直接写盘，不做二次编码。
     * @return 文件名（存进 ChatTurn.imagePath），失败返回 null
     */
    fun save(context: Context, base64: String): String? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val name = "img_${System.currentTimeMillis()}_${(100..999).random()}.jpg"
        File(dir(context), name).writeBytes(bytes)
        name
    } catch (t: Throwable) {
        Log.w(TAG, "保存图片失败", t)
        null
    }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun exists(context: Context, name: String?): Boolean =
        !name.isNullOrBlank() && file(context, name).exists()

    /** 解码成缩略图，按最长边 [THUMB_MAX_PX] 降采样；命中缓存直接返回 */
    fun loadThumbnail(context: Context, name: String?): Bitmap? {
        if (name.isNullOrBlank()) return null
        cache.get(name)?.let { return it }
        return try {
            val path = file(context, name)
            if (!path.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path.absolutePath, bounds)

            var sample = 1
            while (bounds.outWidth / sample > THUMB_MAX_PX || bounds.outHeight / sample > THUMB_MAX_PX) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                path.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
            if (bitmap != null) cache.put(name, bitmap)
            bitmap
        } catch (t: Throwable) {
            Log.w(TAG, "读取图片失败", t)
            null
        }
    }

    fun delete(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        cache.remove(name)
        try {
            file(context, name).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * 清掉没被任何对话引用的图片。
     * 对话被 `MAX_TURNS` 裁掉、或整段被删除/清空后，图片就没人引用了，
     * 靠这个方法统一回收，不用在每处删除逻辑里手工配对。
     */
    fun prune(context: Context, keep: Set<String>) {
        try {
            dir(context).listFiles()?.forEach { file ->
                if (file.name !in keep) {
                    cache.remove(file.name)
                    file.delete()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "清理图片失败", t)
        }
    }
}