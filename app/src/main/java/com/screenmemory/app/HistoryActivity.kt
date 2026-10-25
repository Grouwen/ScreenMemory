package com.screenmemory.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screenmemory.app.chat.ChatStore
import com.screenmemory.app.chat.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话历史列表（App 内页面，不在悬浮窗里）。
 *
 * 每段对话一张卡片：时间 · 轮数 · 首个问题 · 最后一条回答的摘要；
 * 点进去看完整问答。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: ChatStore
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvClear: TextView

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        store = ChatStore(this)
        listContainer = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvClear = findViewById(R.id.tvClear)
        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        tvClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val all = store.loadAll()
        listContainer.removeAllViews()
        tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        tvClear.visibility = if (all.isEmpty()) View.INVISIBLE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (conv in all) {
            val item = inflater.inflate(R.layout.item_history, listContainer, false)
            val meta = item.findViewById<TextView>(R.id.tvItemMeta)
            val question = item.findViewById<TextView>(R.id.tvItemQuestion)
            val answer = item.findViewById<TextView>(R.id.tvItemAnswer)

            meta.text = timeFormat.format(Date(conv.id)) + " · " + conv.turns.size + " 轮"
            question.text = conv.turns.firstOrNull()?.question?.trim().orEmpty().ifBlank { "（无提问）" }
            answer.text = conv.turns.lastOrNull()?.answer?.trim()
                ?.replace('\n', ' ')
                .orEmpty()
                .ifBlank { "（没有回答）" }

            item.setOnClickListener { openDetail(conv) }
            listContainer.addView(
                item,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun openDetail(conv: Conversation) {
        startActivity(
            Intent(this, HistoryDetailActivity::class.java)
                .putExtra(HistoryDetailActivity.EXTRA_ID, conv.id)
        )
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("删除本机保存的全部对话记录？这个操作不能撤销。")
            .setPositiveButton("删除") { _, _ ->
                store.clear()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
