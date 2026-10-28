package com.screenmemory.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.screenmemory.app.ui.ZoomImageView

/**
 * 全屏看历史里附过的原图。
 *
 * 背景全黑、只放一张图：单击/返回关闭，双击放大，双指缩放，放大后可拖动。
 */
class ImageViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"

        fun intent(context: Context, absolutePath: String): Intent =
            Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_PATH, absolutePath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏黑底：状态栏/导航栏也压黑，图标转白，不然浅色主题下顶上一片白
        window.setBackgroundDrawableResource(android.R.color.black)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val path = intent.getStringExtra(EXTRA_PATH)
        val bitmap = path?.let { decodeSampled(it) }
        if (bitmap == null) {
            Toast.makeText(this, "图片已经不在了", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val image = ZoomImageView(this).apply {
            setImageBitmap(bitmap)
            onSingleTap = { finish() }
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部一个关闭按钮：单击图是关闭，但用户未必知道，给个明确的出口
        root.addView(
            TextView(this).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
                setOnClickListener { finish() }
            },
            FrameLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(32)
                marginEnd = dp(16)
            }
        )

        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 按屏幕尺寸降采样，避免超大图直接解码把内存撑爆 */
    private fun decodeSampled(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val metrics = resources.displayMetrics
            val maxWidth = metrics.widthPixels * 2
            val maxHeight = metrics.heightPixels * 2
            var sample = 1
            while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (t: Throwable) {
            null
        }
    }
}