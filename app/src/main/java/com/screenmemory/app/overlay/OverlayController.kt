package com.screenmemory.app.overlay

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.ContextThemeWrapper
import com.screenmemory.app.R
import com.screenmemory.app.util.Latex
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * 悬浮球 + 提问面板。全部用代码构建视图，避免依赖主题（悬浮窗的 Context 没有 Activity 主题）。
 */
class OverlayController(
    private val context: Context,
    private val listener: Listener,
    initialClipSeconds: Int
) {

    interface Listener {
        fun onHoldStart()
        fun onHoldEnd()
        fun onSend(text: String)
        fun onStopService()

        /** 用户点了悬浮球、面板即将出现：此刻应当把画面截断 */
        fun onPanelOpened()

        /** 面板收起：解除截断，下次点球重新截 */
        fun onPanelClosed()
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    // 注意：不能缓存 displayMetrics —— 旋转后宽高会变，缓存的旧值会把悬浮球放到屏幕外
    private val metrics: DisplayMetrics
        get() = context.resources.displayMetrics

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var statusView: TextView? = null
    private var warningView: TextView? = null
    private var answerView: TextView? = null
    private var answerScroll: ScrollView? = null
    private var inputView: EditText? = null
    private var holdView: TextView? = null
    private var sendView: TextView? = null
    private var headerSubtitle: TextView? = null

    /** 保留时长，可在运行时被设置页修改 */
    private var clipSeconds: Int = initialClipSeconds

    /** 渲染时用的 Context：Markwon 和 JLatex 都要读主题属性，Service 的 context 没有主题会抛异常 */
    private val themedContext: Context by lazy {
        ContextThemeWrapper(context, R.style.Theme_ScreenMemory)
    }

    /** 完整渲染器：Markdown + LaTeX 公式 */
    private val markwonFull: Markwon? by lazy {
        try {
            val latexSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                context.resources.displayMetrics
            )
            Markwon.builder(themedContext)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(themedContext))
                // 行内公式依赖 InlineParser，必须先把这个插件装上，否则 configure 阶段直接抛
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                    JLatexMathPlugin.create(latexSize) { builder ->
                        // 默认只解析 $$...$$ 块级公式，行内 $...$ 必须显式打开
                        builder.inlinesEnabled(true)
                    }
                )
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "build markwon with latex failed", t)
            lastRenderError = "公式渲染器初始化失败：${t.message}"
            null
        }
    }

    /** 降级渲染器：只做 Markdown，公式原样显示（至少加粗/列表/表格还在） */
    private val markwonBasic: Markwon? by lazy {
        try {
            Markwon.builder(themedContext)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(themedContext))
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "build markwon failed", t)
            lastRenderError = "Markdown 渲染器初始化失败：${t.message}"
            null
        }
    }

    /** 最近一次渲染失败的原因，服务层会把它写进状态栏，方便定位 */
    @Volatile
    var lastRenderError: String? = null
        private set

    private val TAG = "Overlay"


    @Volatile
    private var destroyed = false

    private var callbacksRegistered = false

    /** 旋转屏幕时重新计算窗口位置，否则悬浮球会停在旧坐标（横屏时直接跑到屏幕外） */
    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post { handleScreenChanged() }
        }

        override fun onLowMemory() = Unit
    }

    /** 兜底巡检：部分 ROM 在后台不派发 onConfigurationChanged，2 秒校一次坐标 */
    private val positionWatchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            handleScreenChanged()
            main.postDelayed(this, 2000)
        }
    }

    private fun handleScreenChanged() {
        if (destroyed) return
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        bubble?.let { view ->
            val lp = bubbleParams ?: return@let
            val x = lp.x.coerceIn(0, (width - lp.width).coerceAtLeast(0))
            val y = lp.y.coerceIn(0, (height - lp.height).coerceAtLeast(0))
            if (x != lp.x || y != lp.y || !view.isAttachedToWindow) {
                lp.x = x
                lp.y = y
                applyLayout(view, lp)
            }
        }
        panel?.let { view ->
            val lp = panelParams ?: return@let
            val panelWidth = (width - dp(24)).coerceAtLeast(dp(220))
            if (panelWidth != lp.width || !view.isAttachedToWindow) {
                lp.width = panelWidth
                applyLayout(view, lp)
            }
        }
    }

    /** 大部分机型 updateViewLayout 就够；个别 ROM 旋转时会摘掉窗口，需要重新挂回去 */
    private fun applyLayout(view: View, lp: WindowManager.LayoutParams) {
        try {
            if (view.isAttachedToWindow) {
                wm.updateViewLayout(view, lp)
            } else {
                wm.addView(view, lp)
            }
        } catch (_: Throwable) {
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
            try {
                wm.addView(view, lp)
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ 悬浮球

    fun showBubble() {
        main.post {
            if (destroyed || bubble != null) return@post
            if (!callbacksRegistered) {
                context.registerComponentCallbacks(configCallback)
                callbacksRegistered = true
            }
            main.removeCallbacks(positionWatchdog)
            main.postDelayed(positionWatchdog, 2000)
            val size = dp(56)
            val view = TextView(context).apply {
                text = "问"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF2F6BFF.toInt())
                    setStroke(dp(2), 0x55FFFFFF)
                }
                elevation = dp(8).toFloat()
            }
            val lp = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (metrics.widthPixels - size - dp(10)).coerceAtLeast(0)
                y = (metrics.heightPixels / 4).coerceAtMost(
                    (metrics.heightPixels - size).coerceAtLeast(0)
                )
            }
            attachDrag(view, lp) { togglePanel() }
            try {
                wm.addView(view, lp)
                bubble = view
                bubbleParams = lp
            } catch (t: Throwable) {
                // 悬浮窗权限被收回
            }
        }
    }

    fun hideBubble() {
        main.post {
            val view = bubble ?: return@post
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
            bubble = null
            bubbleParams = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(
        view: View,
        lp: WindowManager.LayoutParams,
        onClick: () -> Unit
    ) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
                    true
                }

                else -> true
            }
        }
    }

    // ------------------------------------------------------------------ 面板

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    fun showPanel() {
        main.post {
            if (destroyed) return@post
            if (panel != null) return@post
            // 先截断，再建面板：这样面板和键盘不会进入录像
            listener.onPanelOpened()
            val view = try {
                buildPanel()
            } catch (t: Throwable) {
                Log.e(TAG, "build panel failed", t)
                // 面板没建起来就必须解冻：否则窗口永远停在冻结点，之后每次提问都是同一段旧画面
                listener.onPanelClosed()
                clearViewRefs()
                return@post
            }
            val lp = WindowManager.LayoutParams(
                (metrics.widthPixels - dp(24)).coerceAtLeast(dp(220)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(16)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            try {
                wm.addView(view, lp)
                panel = view
                panelParams = lp
            } catch (t: Throwable) {
                Log.e(TAG, "add panel view failed", t)
                // 同上：panel 仍是 null，onPanelClosed() 再也不会被调用，必须在这里解冻
                listener.onPanelClosed()
                clearViewRefs()
                try {
                    Toast.makeText(
                        context,
                        "悬浮窗打不开，画面已解冻。请检查「显示在其他应用上层」权限",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun hidePanel() {
        main.post {
            val view = panel ?: return@post
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
            panel = null
            panelParams = null
            listener.onPanelClosed()
            clearViewRefs()
        }
    }

    /** 面板销毁后清掉所有子视图引用，避免状态栏/答案写到一个已经不在窗口里的 View 上 */
    private fun clearViewRefs() {
        statusView = null
        warningView = null
        answerView = null
        answerScroll = null
        inputView = null
        holdView = null
        sendView = null
        headerSubtitle = null
    }

    /** 设置页改了保留时长，同步面板标题（面板没打开时下次打开会带上新值） */
    fun setClipSeconds(seconds: Int) {
        clipSeconds = seconds
        main.post { headerSubtitle?.text = "  最近 $seconds 秒" }
    }

    private fun buildPanel(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF7FFFFFF.toInt())
                setStroke(dp(1), 0x1A000000)
            }
            elevation = dp(12).toFloat()
        }

        // 标题栏
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = "屏幕助手"
                setTextColor(0xFF12131A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val subtitle = TextView(context).apply {
            text = "  最近 $clipSeconds 秒"
            setTextColor(0xFF8A8F9C.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        headerSubtitle = subtitle
        header.addView(
            subtitle,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        header.addView(
            TextView(context).apply {
                text = "✕"
                setTextColor(0xFF8A8F9C.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(8), dp(2), dp(4), dp(6))
                setOnClickListener { hidePanel() }
            }
        )
        root.addView(header)

        val status = TextView(context).apply {
            text = "长按下方按钮说话，也可以直接打字提问"
            setTextColor(0xFF6B7280.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(8))
        }
        statusView = status
        root.addView(status)

        val warning = TextView(context).apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(0xFFB45309.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFFFF7E6.toInt())
                setStroke(dp(1), 0x44F59E0B)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        warningView = warning
        root.addView(warning)

        val answerText = TextView(themedContext).apply {
            setTextColor(0xFF12131A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        answerView = answerText

        val answerBox = ScrollView(context).apply {
            visibility = View.GONE
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            ).apply { bottomMargin = dp(8) }
            addView(
                answerText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        answerScroll = answerBox
        root.addView(answerBox)

        val input = EditText(context).apply {
            hint = "语音转成的文字会出现在这里，可以修改"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF12131A.toInt())
            setHintTextColor(0xFF9CA3AF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF2F3F7.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        inputView = input
        root.addView(input)

        // 按钮行
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val hold = TextView(context).apply {
            text = "按住说话"
            gravity = Gravity.CENTER
            setTextColor(0xFF2F6BFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFE8EFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) }
        }
        holdView = hold
        val send = TextView(context).apply {
            text = "发送"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF2F6BFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener {
                listener.onSend(inputView?.text?.toString().orEmpty())
            }
        }
        sendView = send
        hold.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.65f
                    listener.onHoldStart()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    listener.onHoldEnd()
                    true
                }

                else -> true
            }
        }
        row.addView(hold)
        row.addView(send)
        root.addView(row)

        val footer = TextView(context).apply {
            text = "停止助手"
            setTextColor(0xFF9CA3AF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { listener.onStopService() }
        }
        root.addView(footer)

        return root
    }

    // ------------------------------------------------------------------ 状态

    fun setStatus(text: String) {
        main.post { statusView?.text = text }
    }

    /** 醒目提示（例如片段没有音轨、模型听不到声音），空字符串则隐藏 */
    fun setWarning(text: String) {
        main.post {
            val view = warningView ?: return@post
            if (text.isBlank()) {
                view.visibility = View.GONE
            } else {
                view.text = text
                view.visibility = View.VISIBLE
            }
        }
    }

    fun setRecognized(text: String) {
        main.post { inputView?.setText(text); inputView?.setSelection(text.length) }
    }

    fun setAnswer(text: String) {
        main.post {
            val view = answerView ?: return@post
            val scroll = answerScroll ?: return@post
            if (text.isBlank()) {
                view.text = ""
                scroll.visibility = View.GONE
            } else {
                renderMarkdown(view, text)
                scroll.visibility = View.VISIBLE
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    /** 流式期间的纯文本预览：不做 Markdown/公式渲染，避免半截公式把绘制炸掉 */
    fun setAnswerPreview(text: String) {
        main.post {
            val view = answerView ?: return@post
            val scroll = answerScroll ?: return@post
            if (text.isBlank()) {
                view.text = ""
                scroll.visibility = View.GONE
            } else {
                view.text = text
                scroll.visibility = View.VISIBLE
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    /** 渲染降级链：完整版 → 无公式版 → 纯文本，任何一级失败都不能让答案消失 */
    private fun renderMarkdown(view: TextView, markdown: String) {
        markwonFull?.let { renderer ->
            try {
                // ext-latex 只认 $$...$$，先把模型写的 $...$ 归一化
                renderer.setMarkdown(view, Latex.normalize(markdown))
                return
            } catch (t: Throwable) {
                Log.e(TAG, "latex render failed", t)
                lastRenderError = "公式渲染失败：${t.message}"
            }
        }
        markwonBasic?.let { renderer ->
            try {
                renderer.setMarkdown(view, markdown)
                return
            } catch (t: Throwable) {
                Log.e(TAG, "markdown render failed", t)
                lastRenderError = "Markdown 渲染失败：${t.message}"
            }
        }
        try {
            view.text = markdown
        } catch (_: Throwable) {
        }
    }

    fun setBusy(busy: Boolean) {
        main.post {
            sendView?.isEnabled = !busy
            holdView?.isEnabled = !busy
            sendView?.alpha = if (busy) 0.6f else 1f
            holdView?.alpha = if (busy) 0.6f else 1f
            if (busy) sendView?.text = "处理中…" else sendView?.text = "发送"
        }
    }

    fun destroy() {
        destroyed = true
        main.removeCallbacks(positionWatchdog)
        try {
            context.unregisterComponentCallbacks(configCallback)
        } catch (_: Throwable) {
        }
        hidePanel()
        hideBubble()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        metrics
    ).toInt()
}
