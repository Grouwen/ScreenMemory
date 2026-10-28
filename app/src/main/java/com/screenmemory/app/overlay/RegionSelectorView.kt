package com.screenmemory.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏选区层：用户按下并拖动来框选截图区域。
 * 这里只负责画选区，真正取图由 MediaProjection 完成。
 */
class RegionSelectorView(context: Context) : View(context) {

    var onSelectionChanged: ((Rect?) -> Unit)? = null

    private val dimPaint = Paint().apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2F6BFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2F6BFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(12f)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }

    private var selection: RectF? = null
    private var dragging = false
    private var startX = 0f
    private var startY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x.coerceIn(0f, width.toFloat())
                startY = event.y.coerceIn(0f, height.toFloat())
                selection = RectF(startX, startY, startX, startY)
                dragging = true
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateSelection(event.x, event.y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    updateSelection(event.x, event.y)
                    dragging = false
                    val rect = selection
                    if (rect == null || rect.width() < dp(24f) || rect.height() < dp(24f)) {
                        selection = null
                        onSelectionChanged?.invoke(null)
                    } else {
                        onSelectionChanged?.invoke(selectionRect())
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    fun selectionRect(): Rect? {
        val rect = selection ?: return null
        val left = rect.left.toInt().coerceIn(0, max(0, width - 1))
        val top = rect.top.toInt().coerceIn(0, max(0, height - 1))
        val right = rect.right.toInt().coerceIn(left + 1, width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun updateSelection(x: Float, y: Float) {
        val cx = x.coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(0f, height.toFloat())
        selection = RectF(min(startX, cx), min(startY, cy), max(startX, cx), max(startY, cy))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selection
        if (rect == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }

        // 只在选区外压暗，选区本身保持透明，方便看清要截的内容
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        canvas.drawRect(rect, borderPaint)

        val radius = dp(6f)
        for (x in floatArrayOf(rect.left, rect.right)) {
            for (y in floatArrayOf(rect.top, rect.bottom)) {
                canvas.drawCircle(x, y, radius, handleFillPaint)
                canvas.drawCircle(x, y, radius, handleStrokePaint)
            }
        }

        val label = "${rect.width().toInt()} × ${rect.height().toInt()}"
        val labelWidth = labelPaint.measureText(label)
        val labelLeft = (rect.left + dp(6f)).coerceAtMost(width - labelWidth - dp(14f))
            .coerceAtLeast(0f)
        val labelTop = (rect.top - dp(24f)).coerceAtLeast(0f)
        canvas.drawRoundRect(
            labelLeft - dp(5f),
            labelTop,
            labelLeft + labelWidth + dp(5f),
            labelTop + dp(20f),
            dp(5f),
            dp(5f),
            labelBgPaint
        )
        canvas.drawText(label, labelLeft, labelTop + dp(15f), labelPaint)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )
}
