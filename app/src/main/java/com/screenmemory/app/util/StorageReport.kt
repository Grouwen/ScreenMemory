package com.screenmemory.app.util

import android.content.Context
import java.io.File
import java.util.Locale

/** 一个目录/一类数据的占用情况 */
data class StorageUsage(val count: Int, val bytes: Long)

/**
 * 给「清理」页算占用、做清理。
 *
 * 数据都在 `Android/data/<包名>/files/` 下（见 [AppStorage]），这里只负责统计和删除，
 * 不碰业务逻辑 —— 历史记录的清空仍然走 [com.screenmemory.app.chat.ChatStore]。
 */
object StorageReport {

    fun historyFile(context: Context): StorageUsage {
        val file = AppStorage.historyFile(context)
        return StorageUsage(
            count = 1,
            bytes = if (file.exists()) file.length() else 0L
        )
    }

    fun images(context: Context): StorageUsage = dir(AppStorage.imageDir(context))

    fun tempClips(context: Context): StorageUsage = dir(AppStorage.clipCacheDir(context))

    fun savedClips(context: Context): StorageUsage = dir(AppStorage.savedClipDir(context))

    /** 递归统计目录里的文件数与总字节数 */
    fun dir(dir: File): StorageUsage {
        if (!dir.exists()) return StorageUsage(0, 0L)
        var count = 0
        var bytes = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                count++
                bytes += file.length()
            }
        }
        return StorageUsage(count, bytes)
    }

    /** 清空目录但保留目录本身，避免下次写文件时目录不存在 */
    fun clearDir(dir: File): Boolean = try {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        true
    } catch (t: Throwable) {
        false
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
    }
}