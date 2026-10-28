package com.screenmemory.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 看大图用的控件：双指缩放、双击放大/还原、放大后拖动。
 *
 * 为一张图引一个图片库（PhotoView 之类）不划算，这里自己用 Matrix 实现，
 * 功能刚好够用：缩放限制在「适应屏幕」到「4 倍」之间，拖动不会把图拖出屏幕。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val values = FloatArray(9)

    /** 图片刚好铺满屏幕时的缩放比，也是允许的最小缩放 */
    private var baseScale = 1f

    /** 允许缩放到的最大比例（绝对缩放比） */
    private var maxScale = 4f

    /** 单击回调，用来关闭大图 */
    var onSingleTap: (() -> Unit)? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                if (current <= 0f) return false
                val target = (current * detector.scaleFactor).coerceIn(baseScale, maxScale)
                val factor = target / current
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampAndApply()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current <= 0f) return false
                // 已经放大了就还原，否则放大到 2.5 倍，以双击点为中心
                val target = if (current > baseScale * 1.2f) baseScale else baseScale * 2.5f
                val factor = target / current
                matrix.postScale(factor, factor, e.x, e.y)
                clampAndApply()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 没放大时不允许拖动，否则会把整张图拖偏
                if (currentScale() <= baseScale * 1.01f) return false
                matrix.postTranslate(-distanceX, -distanceY)
                clampAndApply()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        // 缩放过程中不要把事件再喂给手势检测，否则会顺带触发拖动
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitToView()
    }

    /** 让图片完整显示在控件里并居中，同时重置缩放 */
    private fun fitToView() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return

        baseScale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        maxScale = baseScale * 4f

        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate(
            (viewWidth - imageWidth * baseScale) / 2f,
            (viewHeight - imageHeight * baseScale) / 2f
        )
        imageMatrix = matrix
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** 把平移限制在合法范围内：图比控件小就居中，比控件大就不许拖出边界 */
    private fun clampAndApply() {
        val drawable = drawable ?: return
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val imageWidth = drawable.intrinsicWidth * scale
        val imageHeight = drawable.intrinsicHeight * scale

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        values[Matrix.MTRANS_X] = if (imageWidth <= viewWidth) {
            (viewWidth - imageWidth) / 2f
        } else {
            values[Matrix.MTRANS_X].coerceIn(viewWidth - imageWidth, 0f)
        }
        values[Matrix.MTRANS_Y] = if (imageHeight <= viewHeight) {
            (viewHeight - imageHeight) / 2f
        } else {
            values[Matrix.MTRANS_Y].coerceIn(viewHeight - imageHeight, 0f)
        }
        matrix.setValues(values)
        imageMatrix = matrix
    }
}