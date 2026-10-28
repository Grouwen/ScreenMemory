package com.screenmemory.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.screenmemory.app.service.CaptureService
import com.screenmemory.app.util.ImageAccess
import com.screenmemory.app.util.ImagePayload

/**
 * 悬浮窗没有 Activity，借这个透明 Activity 打开系统相册选图，
 * 选完把压缩后的 base64 回传给正在运行的 CaptureService。
 *
 * 流程：申请读取图片权限 → 打开系统相册 → 回传结果。
 * 注意 Manifest 里这个 Activity **不能加 noHistory**，否则权限框/相册
 * 把本页顶到后台时会被系统销毁，选图结果就回不来了。
 */
class ImagePickerActivity : AppCompatActivity() {

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            launchPicker()
        } else {
            Toast.makeText(this, "需要相册权限才能选择图片", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        val payload = ImagePayload.fromUri(this, uri)
        if (payload == null) {
            Toast.makeText(this, "图片读取失败，请换一张试试", Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        try {
            startService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_IMAGE_PICKED)
                    .putExtra(CaptureService.EXTRA_IMAGE_BASE64, payload.base64)
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "传回图片失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
        payload.bitmap.recycle()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (ImageAccess.hasAccess(this)) {
            launchPicker()
        } else {
            permission.launch(ImageAccess.permissions())
        }
    }

    private fun launchPicker() {
        try {
            picker.launch(ImageAccess.pickerIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "open gallery failed, fallback to document picker", t)
            try {
                picker.launch(ImageAccess.fallbackIntent())
            } catch (t2: Throwable) {
                Toast.makeText(this, "没有找到可用的相册应用", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "ImagePicker"
    }
}