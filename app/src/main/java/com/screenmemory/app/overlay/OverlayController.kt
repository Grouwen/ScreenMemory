package com.screenmemory.app.overlay

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.ContextThemeWrapper
import com.screenmemory.app.R
import com.screenmemory.app.chat.ChatTurn
import com.screenmemory.app.chat.TokenUsage
import com.screenmemory.app.ui.MathSymbolBar
import com.screenmemory.app.util.Latex
import com.screenmemory.app.util.Preview
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

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var chatHidden = false

    private var statusView: TextView? = null
    private var warningView: TextView? = null
    private var messageList: LinearLayout? = null
    private var answerScroll: ScrollView? = null
    private var pendingAnswerView: TextView? = null
    private var renderedTurns: List<ChatTurn> = emptyList()
    private var renderedPendingQuestion: String? = null
    private var inputView: EditText? = null
    private var mathBar: MathSymbolBar? = null
    private var mathToggle: TextView? = null
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
            val panelHeight = panelHeightPx()
            if (panelWidth != lp.width || panelHeight != lp.height || !view.isAttachedToWindow) {
                lp.width = panelWidth
                lp.height = panelHeight
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
        when {
            panel == null -> showPanel()
            chatHidden -> showChatFromVideo()
            else -> hidePanel()
        }
    }

    fun showPanel() {
        main.post {
            if (destroyed) return@post
            if (panel != null) return@post
            chatHidden = false
            bubble?.text = "问"
            bubble?.contentDescription = null
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
                panelHeightPx(),
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
                bubble?.visibility = View.GONE
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
            chatHidden = false
            bubble?.text = "问"
            bubble?.contentDescription = null
            try {
                wm.removeView(view)
            } catch (_: Throwable) {
            }
            panel = null
            panelParams = null
            bubble?.visibility = View.VISIBLE
            listener.onPanelClosed()
            clearViewRefs()
        }
    }

    /** 收起聊天但保留冻结画面、对话和正在进行的回答；悬浮球变成“聊”用于恢复。 */
    private fun hideChatForVideo() {
        main.post {
            val view = panel ?: return@post
            if (chatHidden) return@post
            chatHidden = true
            inputView?.clearFocus()
            hideKeyboard()
            view.visibility = View.GONE
            panelParams?.let { lp ->
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                applyLayout(view, lp)
            }
            bubble?.text = "聊"
            bubble?.contentDescription = "重新显示聊天"
            bubble?.visibility = View.VISIBLE
            try {
                Toast.makeText(context, "聊天已收起，点「聊」切回来", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun showChatFromVideo() {
        main.post {
            val view = panel ?: run {
                chatHidden = false
                showPanel()
                return@post
            }
            chatHidden = false
            view.visibility = View.VISIBLE
            panelParams?.let { lp ->
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                applyLayout(view, lp)
            }
            bubble?.text = "问"
            bubble?.contentDescription = null
            bubble?.visibility = View.GONE
            view.requestLayout()
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(inputView?.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    /** 输入框上方的“∑ 符号”开关：展开/收起数学符号栏 */
    private fun toggleMathBar() {
        val bar = mathBar ?: return
        val show = bar.visibility != View.VISIBLE
        bar.visibility = if (show) View.VISIBLE else View.GONE
        mathToggle?.apply {
            text = if (show) "∑ 收起" else "∑ 符号"
            contentDescription = if (show) "收起数学符号栏" else "展开数学符号栏"
        }
    }

    /** 面板销毁后清掉所有子视图引用，避免状态栏/答案写到一个已经不在窗口里的 View 上 */
    private fun clearViewRefs() {
        statusView = null
        warningView = null
        messageList = null
        answerScroll = null
        pendingAnswerView = null
        renderedTurns = emptyList()
        renderedPendingQuestion = null
        inputView = null
        mathBar = null
        mathToggle = null
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
                text = "数学网课助手"
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
                text = "隐藏"
                setTextColor(0xFF2F6BFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(4), dp(8), dp(6))
                contentDescription = "收起聊天，不中断聊天"
                setOnClickListener { hideChatForVideo() }
            }
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
            text = "哪一步没看懂？按住说话，或直接打字"
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

        val answerBox = ScrollView(context).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = dp(8) }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(2), 0, dp(4))
                    this@OverlayController.messageList = this
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        answerScroll = answerBox
        root.addView(answerBox)

        val input = EditText(context).apply {
            hint = "有具体问题写这里，比如「第三行为什么能直接约掉」"
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

        // 数学符号栏的开关放在聊天输入框上方；默认收起，避免挤占对话区
        val mathToggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(2), 0, dp(1))
        }
        val toggle = TextView(context).apply {
            text = "∑ 符号"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(0xFF2F6BFF.toInt())
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0xFFE8EFFF.toInt())
            }
            contentDescription = "展开数学符号栏"
            setOnClickListener { toggleMathBar() }
        }
        mathToggle = toggle
        mathToggleRow.addView(
            toggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            mathToggleRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val symbols = MathSymbolBar(themedContext) { inputView }.apply {
            visibility = View.GONE
        }
        mathBar = symbols
        root.addView(
            symbols,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
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

    /** 发送成功后清空输入框；失败时保留原文，方便直接再点一次发送 */
    fun clearInput() {
        main.post { inputView?.setText("") }
    }

    /**
     * 渲染整段对话（多轮追问）。
     *
     * @param turns 已经完成的轮次
     * @param pendingQuestion 正在问的这一句；null 表示当前没有进行中的请求
     * @param pendingAnswer 流式过程中的半截答案
     */
    fun setTranscript(
        turns: List<ChatTurn>,
        pendingQuestion: String? = null,
        pendingAnswer: String? = null
    ) {
        main.post {
            val messages = messageList ?: return@post
            val scroll = answerScroll ?: return@post
            val snapshot = turns.toList()
            val previousScrollY = scroll.scrollY
            val wasAtBottom = isScrolledToBottom(scroll)
            val wasEmpty = renderedTurns.isEmpty() && renderedPendingQuestion == null
            val pendingAdded = pendingQuestion != null && pendingQuestion != renderedPendingQuestion
            if (snapshot.isEmpty() && pendingQuestion == null) {
                messages.removeAllViews()
                pendingAnswerView = null
                renderedTurns = emptyList()
                renderedPendingQuestion = null
                scroll.visibility = View.INVISIBLE
                return@post
            }
            val structureChanged =
                snapshot != renderedTurns || pendingQuestion != renderedPendingQuestion
            if (structureChanged) {
                messages.removeAllViews()
                pendingAnswerView = null
                snapshot.forEachIndexed { index, turn ->
                    addMessage(
                        turn.question,
                        isUser = true,
                        turnNumber = index + 1
                    )
                    addMessage(
                        turn.answer,
                        isUser = false,
                        turnNumber = index + 1,
                        usage = turn.usage
                    )
                }
                if (pendingQuestion != null) {
                    val pendingTurn = snapshot.size + 1
                    addMessage(
                        pendingQuestion,
                        isUser = true,
                        turnNumber = pendingTurn
                    )
                    pendingAnswerView = addMessage(
                        pendingAnswer.orEmpty(),
                        isUser = false,
                        streaming = true,
                        turnNumber = pendingTurn
                    )
                }
                renderedTurns = snapshot
                renderedPendingQuestion = pendingQuestion
            } else if (pendingQuestion != null) {
                pendingAnswerView?.let { view ->
                    if (pendingAnswer.isNullOrBlank()) {
                        view.setText("正在等待回答…")
                    } else {
                        renderStreamingMarkdown(view, pendingAnswer)
                    }
                    enableTextSelection(view)
                }
            }
            scroll.visibility = View.VISIBLE
            if (wasAtBottom || wasEmpty || pendingAdded) {
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            } else {
                scroll.post { scroll.scrollTo(0, previousScrollY) }
            }
        }
    }

    private fun isScrolledToBottom(scroll: ScrollView): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        val maxScroll = (child.measuredHeight - scroll.height).coerceAtLeast(0)
        return scroll.scrollY >= maxScroll - dp(24)
    }

    private fun addMessage(
        message: String,
        isUser: Boolean,
        streaming: Boolean = false,
        usage: TokenUsage? = null,
        turnNumber: Int? = null
    ): TextView {
        val messages = messageList ?: error("message list not initialized")
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val role = TextView(context).apply {
            text = buildString {
                append(if (isUser) "你" else "AI 助手")
                if (turnNumber != null) {
                    append(" · 第 ").append(turnNumber).append(" 轮")
                }
            }
            setTextColor(0xFF8A8F9C.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        val bubble = EditText(themedContext).apply {
            maxWidth = minOf(metrics.widthPixels - dp(64), dp(420)).coerceAtLeast(dp(180))
            setTextIsSelectable(true)
            isCursorVisible = false
            setShowSoftInputOnFocus(false)
            setSelectAllOnFocus(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(if (isUser) 0xFF2F6BFF.toInt() else 0xFFF2F3F7.toInt())
            }
            setTextColor(if (isUser) Color.WHITE else 0xFF12131A.toInt())
            if (isUser) {
                setText(Preview.plain(
                    message
                ))
            } else if (streaming) {
                if (message.isBlank()) {
                    setText("正在等待回答…")
                } else {
                    renderStreamingMarkdown(this, message)
                }
            } else {
                renderMarkdown(this, message)
            }
            enableTextSelection(this)
        }
        bubble.setOnLongClickListener {
            showTextSelectionDialog(bubble.text.toString())
            true
        }
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        meta.addView(
            role,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        meta.addView(
            TextView(context).apply {
                text = "复制"
                setTextColor(0xFF2F6BFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(6), 0, dp(4), dp(2))
                setOnClickListener {
                    val editable = bubble.text
                    val start = bubble.selectionStart.coerceAtLeast(0)
                    val end = bubble.selectionEnd.coerceAtLeast(0)
                    val selected = if (end > start) {
                        editable.subSequence(start, end).toString()
                    } else {
                        editable.toString()
                    }
                    copyToClipboard(selected)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            meta,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (!isUser && usage?.isKnown == true) {
            column.addView(
                TextView(context).apply {
                    text = "本轮 · " + usage.detailLabel()
                    setTextColor(0xFF8A8F9C.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setPadding(dp(4), dp(3), dp(4), 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        messages.addView(row)
        return bubble
    }

    /** 确保在非 Activity 的悬浮窗里也能长按选择范围并使用系统复制菜单。 */
    private fun enableTextSelection(view: TextView) {
        view.setTextIsSelectable(true)
        view.isFocusableInTouchMode = true
        if (view is EditText) {
            view.isCursorVisible = false
            view.setShowSoftInputOnFocus(false)
        }
    }

    private fun showTextSelectionDialog(text: String) {
        val dialog = Dialog(themedContext)
        val content = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        content.addView(
            TextView(themedContext).apply {
                setText("选择要复制的文字")
                setTextColor(0xFF12131A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        )
        val selectable = TextView(themedContext).apply {
            setText(text)
            setTextIsSelectable(true)
            setTextColor(0xFF12131A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFF2F3F7.toInt())
        }
        content.addView(
            ScrollView(themedContext).apply {
                addView(selectable)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { topMargin = dp(10); bottomMargin = dp(10) }
        )
        content.addView(
            TextView(themedContext).apply {
                setText("关闭")
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFF2F6BFF.toInt())
                }
                setOnClickListener { dialog.dismiss() }
            }
        )
        dialog.setContentView(content)
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0f)
            setGravity(Gravity.CENTER)
            setLayout((metrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        dialog.show()
        selectable.post {
            selectable.performLongClick()
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) {
            Toast.makeText(context, "暂无可复制内容", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ScreenMemory", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 渲染降级链：完整版 → 无公式版 → 纯文本，任何一级失败都不能让答案消失 */
    private fun renderMarkdown(view: TextView, markdown: String) {
        markwonFull?.let { renderer ->
            try {
                // ext-latex 只认 $$...$$，先把模型写的 $...$ 归一化
                renderer.setMarkdown(view, Latex.forRender(markdown))
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

    private fun renderStreamingMarkdown(view: TextView, markdown: String) {
        val safe = Latex.forStreaming(markdown)
        markwonFull?.let { renderer ->
            try {
                renderer.setMarkdown(view, safe)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "stream markdown render failed", t)
            }
        }
        markwonBasic?.let { renderer ->
            try {
                renderer.setMarkdown(view, safe)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "stream basic markdown render failed", t)
            }
        }
        view.text = Preview.plain(safe)
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

    private fun panelHeightPx(): Int {
        val available = (metrics.heightPixels - dp(48)).coerceAtLeast(dp(240))
        return minOf(dp(500), available)
    }
}
