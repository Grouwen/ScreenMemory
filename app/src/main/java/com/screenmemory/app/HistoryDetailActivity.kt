package com.screenmemory.app

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.screenmemory.app.chat.ChatStore
import com.screenmemory.app.chat.ChatTurn
import com.screenmemory.app.chat.Conversation
import com.screenmemory.app.net.LlmClient
import com.screenmemory.app.net.QwenVideoClient
import com.screenmemory.app.util.Latex
import com.screenmemory.app.util.Preview
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
            你是一个手机屏幕实时助手。用户在查看一段历史对话，并继续追问。

            注意：这一轮不会附任何截图或视频 —— 原始画面没有被保存下来，你只能依据上文里已经给出的问答来回答。
            如果这个问题必须重新看画面才能回答，直接说明「需要回到悬浮球重新提问并附上画面」，不要凭空猜测画面内容。

            用简体中文回答，控制在 200 字以内。用 Markdown 正常排版即可，公式用 LaTeX 写。不要输出 HTML 标签，不要用 +---+ 这种 ASCII 线框画表格。
        """.trimIndent()
    }

    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var followStatus: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { Prefs(this) }
    private lateinit var conv: Conversation

    @Volatile
    private var sending = false

    @Volatile
    private var lastPushAt = 0L

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

        body = findViewById(R.id.tvDetailBody)
        scroll = findViewById(R.id.detailScroll)
        input = findViewById(R.id.etFollowInput)
        send = findViewById(R.id.btnFollowSend)
        followStatus = findViewById(R.id.tvFollowStatus)
        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val loaded = ChatStore(this).loadAll().firstOrNull { it.id == id }
        if (loaded == null) {
            body.text = "这段对话已经不在了（可能被清空或超出保留上限）。"
            input.isEnabled = false
            send.isEnabled = false
            followStatus.text = "无法继续这段对话"
            return
        }
        conv = loaded
        findViewById<TextView>(R.id.tvDetailTitle).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(conv.id))

        send.setOnClickListener { sendFollowUp() }
        render()
        followStatus.text = "可以接着问 · 共 ${conv.turns.size} 轮（不再附图，只依据上面的对话）"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 渲染

    private fun transcriptMarkdown(pendingQuestion: String?, pendingAnswer: String?): String {
        val sb = StringBuilder()
        conv.turns.forEachIndexed { index, turn ->
            if (index > 0) sb.append("\n\n---\n\n")
            sb.append("**你**：").append(turn.question).append("\n\n")
            sb.append("**答**：").append(turn.answer)
        }
        if (pendingQuestion != null) {
            if (conv.turns.isNotEmpty()) sb.append("\n\n---\n\n")
            sb.append("**你**：").append(pendingQuestion).append("\n\n")
            sb.append("**答**：").append(pendingAnswer.orEmpty())
        }
        return sb.toString()
    }

    private fun render() {
        val markdown = transcriptMarkdown(null, null)
        val renderer = markwon
        if (renderer != null) {
            try {
                renderer.setMarkdown(body, Latex.normalize(markdown))
                scrollDown()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "render failed", t)
            }
        }
        body.text = Preview.plain(markdown)
        scrollDown()
    }

    /** 流式阶段整段走纯文本，避免半截公式把渲染器炸掉 */
    private fun renderStreaming(question: String, answer: String) {
        body.text = Preview.plain(transcriptMarkdown(question, answer))
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.scrollTo(0, body.bottom) }
    }

    // ------------------------------------------------------------------ 继续追问

    private fun sendFollowUp() {
        val question = input.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) {
            followStatus.text = "先写点什么再发送"
            return
        }
        if (sending) return
        sending = true
        send.isEnabled = false
        input.isEnabled = false
        followStatus.text = "追问中（只依据上面的对话，不再附图）…"
        hideKeyboard()

        val history = conv.turns.takeLast(MAX_HISTORY_TURNS).toList()
        val streamed = StringBuilder()
        lastPushAt = 0L

        val onDelta: (String) -> Unit = { delta ->
            streamed.append(delta)
            val now = SystemClock.uptimeMillis()
            if (now - lastPushAt >= STREAM_INTERVAL_MS) {
                lastPushAt = now
                val snapshot = streamed.toString()
                runOnUiThread { renderStreaming(question, snapshot) }
            }
        }

        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    if (prefs.videoMode) {
                        // 视频模式也用它自己的端点，只是不带视频（video = null → 纯文字追问）
                        QwenVideoClient(prefs).ask(
                            question = question,
                            video = null,
                            systemPrompt = FOLLOW_UP_PROMPT,
                            onStatus = { status -> runOnUiThread { followStatus.text = status } },
                            onDelta = onDelta,
                            history = history
                        )
                    } else {
                        LlmClient(prefs).ask(
                            question = question,
                            jpegBase64 = emptyList(),
                            systemPrompt = FOLLOW_UP_PROMPT,
                            onDelta = onDelta,
                            history = history
                        )
                    }
                }

                conv.turns.add(
                    ChatTurn(
                        question = question,
                        answer = answer.ifBlank { "模型没有返回内容" },
                        at = System.currentTimeMillis(),
                        withMedia = false
                    )
                )
                withContext(Dispatchers.IO) { ChatStore(this@HistoryDetailActivity).save(conv) }

                input.setText("")
                render()
                followStatus.text = "已追问 · 现在是第 ${conv.turns.size} 轮"
            } catch (t: Throwable) {
                Log.e(TAG, "follow up failed", t)
                followStatus.text = "追问失败：${t.message}"
                // 失败的一轮不入库，把原文留在输入框里方便重发
                input.setText(question)
                render()
            } finally {
                sending = false
                send.isEnabled = true
                input.isEnabled = true
            }
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Throwable) {
        }
    }
}
