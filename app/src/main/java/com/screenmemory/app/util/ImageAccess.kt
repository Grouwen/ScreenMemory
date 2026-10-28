package com.screenmemory.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 相册选图：先向用户申请「访问所有图片」，再打开系统相册的选图页。
 *
 * 之前用的是 SAF 的 `GetContent`，弹出来的是文件选择器（像文件管理器），
 * 而且完全不需要权限。改成 `ACTION_PICK` + `MediaStore.Images` 之后打开的是
 * 系统相册，代价是必须先拿到读取图片的权限。
 *
 * 权限随系统版本变：
 * - Android 13（API 33）起细分媒体权限，图片是 READ_MEDIA_IMAGES；
 * - Android 14（API 34）起还允许用户只授权「部分照片」，
 *   此时 READ_MEDIA_IMAGES 是拒绝的，但 READ_MEDIA_VISUAL_USER_SELECTED 通过，
 *   这种情况也要放行 —— 用户既然选了几张，就该让他用这几张；
 * - Android 12 及以下仍然只有 READ_EXTERNAL_STORAGE。
 */
object ImageAccess {

    /** 需要申请的权限列表，按版本区分 */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 只要有一条已授予就能选图（Android 14 的「部分照片」也算够用） */
    fun hasAccess(context: Context): Boolean = permissions().any { isGranted(context, it) }

    /**
     * 是否被永久拒绝（用户勾了「不再询问」）。
     * 这种情况再申请也不会弹窗，只能引导用户去系统设置里手动打开。
     */
    fun isPermanentlyDenied(activity: Activity): Boolean =
        !hasAccess(activity) &&
            permissions().none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    /** 系统相册的选图页 */
    fun pickerIntent(): Intent =
        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 设备上没有任何相册应用时的兜底：通用文件选择器（不需要权限） */
    fun fallbackIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 跳到本应用的系统设置页，让用户手动开权限 */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}