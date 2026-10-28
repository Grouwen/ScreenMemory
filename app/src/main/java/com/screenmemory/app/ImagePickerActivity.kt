package com.screenmemory.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.screenmemory.app.service.CaptureService
import com.screenmemory.app.util.ImagePayload

/**
 * 悬浮窗没有 Activity，借这个透明 Activity 打开系统图片选择器，
 * 选完把压缩后的 base64 回传给正在运行的 CaptureService。
 */
class ImagePickerActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
        if (savedInstanceState == null) {
            picker.launch("image/*")
        }
    }
}
