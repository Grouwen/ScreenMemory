package com.screenmemory.app

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenmemory.app.chat.ChatStore
import com.screenmemory.app.chat.ChatTurn
import com.screenmemory.app.chat.Conversation
import com.screenmemory.app.chat.TokenUsage
import com.screenmemory.app.net.LlmClient
import com.screenmemory.app.net.QwenVideoClient
import com.screenmemory.app.ui.MathSymbolBar
import com.screenmemory.app.util.Latex
import com.screenmemory.app.util.Preview
import com.screenmemory.app.util.ImagePayload
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单段对话的完整问答（App 内页面），并且**可以接着聊**。
 *
 * 注意这是纯文字追问：原始画面（抽帧 / 视频）本来就不落盘，所以这里不会附图，
 * 只能基于上文回答。需要重新看画面时，得回到悬浮球重新问。
 */
class HistoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_conversation_id"
        private const val TAG = "HistoryDetail"

        /** 追问时最多回放多少轮，避免上下文无限膨胀 */
        private const val MAX_HISTORY_TURNS = 10

        /** 流式刷新的节流间隔 */
        private const val STREAM_INTERVAL_MS = 120L

        private val FOLLOW_UP_PROMPT = """
            你是一位数学网课助教。学生在回看一段提问记录，并继续追问。

            注意：这一轮不会附任何截图或视频 —— 网课画面没有被保存下来，你只能依据上文里已经给出的问答来回答。
            如果这个问题必须重新看板书才能回答，直接说明「需要回到悬浮球重新提问并附上画面」，不要凭空猜测画面内容。

            解释要给依据（定义 / 定理 / 变形）；公式一律用 LaTeX（行内 $...$ 或独立成行 $$...$$）。
            用简体中文，控制在 250 字以内。排版用 Markdown，不要输出 HTML 标签，不要用 +---+ 这种 ASCII 线框画表格。
        """.trimIndent()

        private val IMAGE_FOLLOW_UP_PROMPT = """
            你是一位数学网课助教。学生在回看一段提问记录，并上传了一张图片继续追问。

            请优先依据这张图片回答；如果图片里的内容不足以回答，再结合上文已有问答。
            图片不清晰或关键步骤被截掉时，直接说明需要重新上传更完整的截图，不要凭空猜测。
            解释要给依据（定义 / 定理 / 变形）；公式一律用 LaTeX（行内 $...$ 或独立成行 $$...$$）。
            用简体中文，控制在 250 字以内。排版用 Markdown，不要输出 HTML 标签。
        """.trimIndent()
    }

    private lateinit var messageList: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var followStatus: TextView
    private lateinit var imageRow: LinearLayout
    private lateinit var imagePreview: ImageView
    private lateinit var mathHost: LinearLayout
    private lateinit var mathToggle: TextView

    private var pendingImageBase64: String? = null
    private var pendingImageBitmap: Bitmap? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val payload = ImagePayload.fromUri(this, uri)
        if (payload == null) {
            Toast.makeText(this, "图片读取失败，请换一张试试", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        clearImageAttachment()
        pendingImageBitmap = payload.bitmap
        pendingImageBase64 = payload.base64
        imagePreview.setImageBitmap(payload.bitmap)
        imageRow.visibility = View.VISIBLE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { Prefs(this) }
    private lateinit var conv: Conversation

    @Volatile
    private var sending = false

    @Volatile
    private var lastPushAt = 0L

    private var pendingAnswerView: TextView? = null

    private val markwon: Markwon? by lazy {
        try {
            val latexSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics
            )
            Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(latexSize) { builder -> builder.inlinesEnabled(true) })
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "build markwon failed", t)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)

        messageList = findViewById(R.id.messageList)
        scroll = findViewById(R.id.detailScroll)
        input = findViewById(R.id.etFollowInput)
        send = findViewById(R.id.btnFollowSend)
        followStatus = findViewById(R.id.tvFollowStatus)
        imageRow = findViewById(R.id.imageAttachRow)
        imagePreview = findViewById(R.id.imageAttachPreview)
        findViewById<TextView>(R.id.tvImageAttachRemove).setOnClickListener {
            clearImageAttachment()
        }
        findViewById<Button>(R.id.btnFollowImage).setOnClickListener {
            imagePicker.launch("image/*")
        }
        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        mathHost = findViewById(R.id.mathKeyboardHost)
        mathToggle = findViewById(R.id.tvMathToggle)
        mathToggle.setOnClickListener { toggleMathKeyboard() }
        mathHost.addView(
            MathSymbolBar(this) { input }
        )

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val loaded = ChatStore(this).loadAll().firstOrNull { it.id == id }
        if (loaded == null) {
            addMessage("这段对话已经不在了（可能被清空或超出保留上限）。", isUser = false)
            input.isEnabled = false
            send.isEnabled = false
            followStatus.text = "无法继续这段对话"
            return
        }
        conv = loaded
        findViewById<TextView>(R.id.tvDetailTitle).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(conv.id))

        send.setOnClickListener { sendFollowUp() }
        renderConversation()
        followStatus.text = buildString {
            append("可以接着问 · 共 ${conv.turns.size} 轮")
            if (conv.totalTokens > 0) {
                append(" · 累计 ${TokenUsage(totalTokens = conv.totalTokens).compactLabel()}")
            }
            append("（也可以上传图片一起问）")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        clearImageAttachment()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderConversation() {
        messageList.removeAllViews()
        pendingAnswerView = null
        if (conv.turns.isEmpty()) {
            addMessage("这段对话还没有内容。", isUser = false)
        } else {
            for (turn in conv.turns) {
                addMessage(turn.question, isUser = true, hasImage = turn.hasImage)
                addMessage(turn.answer, isUser = false, usage = turn.usage)
            }
        }
        scrollDown()
    }

    private fun addMessage(
        message: String,
        isUser: Boolean,
        streaming: Boolean = false,
        hasImage: Boolean = false,
        usage: TokenUsage? = null
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val role = TextView(this).apply {
            text = buildString {
                append(if (isUser) "你" else "AI 助手")
                if (hasImage) append(" · 图片")
                if (!isUser && usage?.isKnown == true) {
                    append(" · ").append(usage.compactLabel())
                }
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), 0, dp(4), dp(3))
        }
        // 用 TextView 而不是 EditText：气泡只需要「长按选中 + 复制」，
        // 用 EditText 时长按会弹出含粘贴/剪切的编辑菜单，AI 的回答能被改掉。
        val bubble = TextView(this).apply {
            maxWidth = minOf(
                (resources.displayMetrics.widthPixels * 0.82f).toInt(),
                dp(560)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(
                if (isUser) R.drawable.bg_chat_user else R.drawable.bg_chat_ai
            )
            setTextColor(
                themeColor(
                    if (isUser) {
                        com.google.android.material.R.attr.colorOnPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    }
                )
            )
            if (isUser || streaming) {
                setText(if (streaming && message.isBlank()) {
                    "正在回答…"
                } else {
                    Preview.plain(message)
                })
            } else {
                renderAiMarkdown(this, message)
            }
            enableTextSelection(this)
        }
        column.addView(
            role,
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
        row.addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        messageList.addView(row)
        return bubble
    }

    private fun renderAiMarkdown(view: TextView, text: String) {
        val renderer = markwon
        if (renderer != null) {
            try {
                renderer.setMarkdown(view, Latex.forRender(text))
                return
            } catch (t: Throwable) {
                Log.w(TAG, "render bubble failed", t)
            }
        }
        view.text = Preview.plain(text)
        enableTextSelection(view)
    }

    /** 流式阶段只更新最后一个 AI 气泡，避免每个 token 都重建整段对话。 */
    private fun renderStreaming(answer: String) {
        val view = pendingAnswerView ?: return
        view.text = Preview.plain(answer.ifBlank { "正在回答…" })
        enableTextSelection(view)
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    /** 气泡只允许长按选中、复制，不允许编辑（不要换成 EditText） */
    private fun enableTextSelection(view: TextView) {
        view.setTextIsSelectable(true)
    }

    private fun clearImageAttachment() {
        pendingImageBase64 = null
        imagePreview.setImageDrawable(null)
        imageRow.visibility = View.GONE
        pendingImageBitmap?.recycle()
        pendingImageBitmap = null
    }

    // ------------------------------------------------------------------ 继续追问

    private fun sendFollowUp() {
        val hasImage = pendingImageBase64 != null
        val question = input.text?.toString()?.trim().orEmpty().ifEmpty {
            if (hasImage) "请解释这张图片里的重点内容" else ""
        }
        if (question.isEmpty()) {
            followStatus.text = "先写点什么再发送"
            return
        }
        if (sending) return
        sending = true
        send.isEnabled = false
        input.isEnabled = false
        followStatus.text = if (hasImage) {
            "追问中（正在让模型看图片）…"
        } else {
            "追问中（只依据上面的对话）…"
        }
        hideKeyboard()
        addMessage(question, isUser = true, hasImage = hasImage)
        pendingAnswerView = addMessage("", isUser = false, streaming = true)
        scrollDown()

        val history = conv.turns.takeLast(MAX_HISTORY_TURNS).toList()
        val streamed = StringBuilder()
        lastPushAt = 0L

        val onDelta: (String) -> Unit = { delta ->
            streamed.append(delta)
            val now = SystemClock.uptimeMillis()
            if (now - lastPushAt >= STREAM_INTERVAL_MS) {
                lastPushAt = now
                val snapshot = streamed.toString()
                runOnUiThread { renderStreaming(snapshot) }
            }
        }

        scope.launch {
            try {
                val image = pendingImageBase64
                val answer = withContext(Dispatchers.IO) {
                    if (prefs.videoMode) {
                        // 视频模式也用它自己的端点；这一轮只带用户新附的图片，不重传视频
                        QwenVideoClient(prefs).ask(
                            question = question,
                            video = null,
                            systemPrompt = if (hasImage) {
                                IMAGE_FOLLOW_UP_PROMPT
                            } else {
                                FOLLOW_UP_PROMPT
                            },
                            onStatus = { status -> runOnUiThread { followStatus.text = status } },
                            onDelta = onDelta,
                            history = history,
                            imageBase64 = image
                        )
                    } else {
                        LlmClient(prefs).ask(
                            question = question,
                            jpegBase64 = listOfNotNull(image),
                            systemPrompt = if (hasImage) {
                                IMAGE_FOLLOW_UP_PROMPT
                            } else {
                                FOLLOW_UP_PROMPT
                            },
                            onDelta = onDelta,
                            history = history
                        )
                    }
                }

                conv.turns.add(
                    ChatTurn(
                        question = question,
                        answer = answer.text.ifBlank { "模型没有返回内容" },
                        at = System.currentTimeMillis(),
                        withMedia = hasImage,
                        hasImage = hasImage,
                        usage = answer.usage
                    )
                )
                withContext(Dispatchers.IO) { ChatStore(this@HistoryDetailActivity).save(conv) }

                input.setText("")
                clearImageAttachment()
                renderConversation()
                followStatus.text = buildString {
                    append("已追问 · 现在是第 ${conv.turns.size} 轮")
                    if (conv.totalTokens > 0) {
                        append(" · 累计 ${TokenUsage(totalTokens = conv.totalTokens).compactLabel()}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "follow up failed", t)
                followStatus.text = "追问失败：${t.message}"
                // 失败的一轮不入库，把原文留在输入框里方便重发
                input.setText(question)
                renderConversation()
            } finally {
                sending = false
                send.isEnabled = true
                input.isEnabled = true
            }
        }
    }

    /** 和悬浮窗一致：符号栏默认收起，点「∑ 符号」展开，避免挤压对话区 */
    private fun toggleMathKeyboard() {
        val show = mathHost.visibility != View.VISIBLE
        mathHost.visibility = if (show) View.VISIBLE else View.GONE
        mathToggle.text = if (show) "∑ 收起" else "∑ 符号"
        mathToggle.contentDescription =
            if (show) "收起数学符号栏" else "展开数学符号栏"
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Throwable) {
        }
    }
}
