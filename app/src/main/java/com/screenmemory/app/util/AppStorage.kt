package com.screenmemory.app.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 应用数据的存放位置。
 *
 * 之前对话历史和图片放在内部存储（`/data/data/<包名>/files/`），用户既看不见也删不掉。
 * 这个 App 的数据没什么可保密的，全部统一放到外部私有目录：
 *
 *     Android/data/<包名>/files/
 *       ├── chat_history.json     对话历史
 *       ├── chat_images/          对话里附过的图片
 *       ├── clips/                临时片段（用完就删）
 *       └── Movies/               手动另存的片段
 *
 * 这些目录挂在外部存储上，可以直接看到、直接删除；卸载 App 时系统会一并清掉。
 *
 * 注意：外部存储不可用（极少数情况）时仍然回退到内部目录，功能不能因此挂掉。
 */
object AppStorage {

    private const val TAG = "AppStorage"

    const val HISTORY_FILE = "chat_history.json"
    const val IMAGE_DIR = "chat_images"
    const val CLIP_DIR = "clips"

    @Volatile
    private var migrated = false

    /** 数据根目录：优先外部私有目录，不可用时退回内部 */
    fun root(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val dir = (external ?: context.filesDir).apply { mkdirs() }
        migrateFromInternalIfNeeded(context, dir)
        return dir
    }

    fun historyFile(context: Context): File = File(root(context), HISTORY_FILE)

    fun imageDir(context: Context): File = File(root(context), IMAGE_DIR).apply { mkdirs() }

    /** 临时片段：放外部 cache，系统空间不足时也能自己清 */
    fun clipCacheDir(context: Context): File =
        File(context.externalCacheDir ?: context.cacheDir, CLIP_DIR).apply { mkdirs() }

    /** 手动另存的片段 */
    fun savedClipDir(context: Context): File = File(root(context), "Movies").apply { mkdirs() }

    /** 给设置页显示的一段说明 */
    fun visiblePath(context: Context): String =
        "Android/data/" + context.packageName + "/files/"

    /**
     * 老版本把历史写在内部存储，搬过来时补一次迁移，避免用户升级后历史“凭空消失”。
     * 只搬一次：外部已经存在同名文件就不再覆盖。
     */
    private fun migrateFromInternalIfNeeded(context: Context, target: File) {
        if (migrated || target == context.filesDir) return
        synchronized(this) {
            if (migrated) return
            migrated = true
            copyFromInternal(context, target)
        }
    }

    private fun copyFromInternal(context: Context, target: File) {
        try {
            val internalHistory = File(context.filesDir, HISTORY_FILE)
            val externalHistory = File(target, HISTORY_FILE)
            if (internalHistory.exists() && !externalHistory.exists()) {
                internalHistory.copyTo(externalHistory, overwrite = true)
                internalHistory.delete()
            }

            val internalImages = File(context.filesDir, IMAGE_DIR)
            val externalImages = File(target, IMAGE_DIR)
            if (internalImages.isDirectory && !externalImages.exists()) {
                externalImages.mkdirs()
                internalImages.listFiles()?.forEach { source ->
                    runCatching {
                        source.copyTo(File(externalImages, source.name), overwrite = true)
                    }
                }
                internalImages.deleteRecursively()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "迁移内部数据失败，继续用原来的位置", t)
        }
    }
}