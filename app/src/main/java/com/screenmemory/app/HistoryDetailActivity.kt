package com.screenmemory.app

import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.screenmemory.app.chat.ChatStore
import com.screenmemory.app.chat.Conversation
import com.screenmemory.app.util.Latex
import com.screenmemory.app.util.Preview
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单段对话的完整问答（App 内页面） */
class HistoryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_conversation_id"
        private const val TAG = "HistoryDetail"
    }

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)
        body = findViewById(R.id.tvDetailBody)
        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val conv = ChatStore(this).loadAll().firstOrNull { it.id == id }
        if (conv == null) {
            body.text = "这段对话已经不在了（可能被清空或超出保留上限）。"
            return
        }
        render(conv)
    }

    private fun render(conv: Conversation) {
        findViewById<TextView>(R.id.tvDetailTitle).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(conv.id))

        val markdown = buildString {
            conv.turns.forEachIndexed { index, turn ->
                if (index > 0) append("\n\n---\n\n")
                append("**你**：").append(turn.question).append("\n\n")
                append("**答**：").append(turn.answer)
            }
        }
        try {
            val latexSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics
            )
            val markwon = Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(latexSize) { builder -> builder.inlinesEnabled(true) })
                .build()
            markwon.setMarkdown(body, Latex.normalize(markdown))
        } catch (t: Throwable) {
            // 渲染失败就退回纯文本，历史内容不能丢
            Log.w(TAG, "render history failed", t)
            body.text = Preview.plain(markdown)
        }
    }
}
