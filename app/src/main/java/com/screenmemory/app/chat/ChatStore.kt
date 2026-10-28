package com.screenmemory.app.chat

import android.content.Context
import android.util.Log
import com.screenmemory.app.util.AppStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
) {
    val isKnown: Boolean
        get() = totalTokens > 0

    fun compactLabel(): String = "${format(totalTokens)} tokens"

    fun detailLabel(): String =
        "输入 ${format(promptTokens)} / 输出 ${format(completionTokens)} / " +
            "合计 ${format(totalTokens)} tokens"

    private fun format(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
}

/** 一轮问答 */
data class ChatTurn(
    val question: String,
    val answer: String,
    val at: Long,
    /** 这一轮是否附了画面（抽帧图片 / 直传视频）。首轮带，追问不带 */
    val withMedia: Boolean,
    /** 这一轮是否附了用户上传/框选的图片 */
    val hasImage: Boolean = false,
    /** 图片在本地的文件名（在 filesDir/chat_images/ 下）；回看历史时用它把图读出来 */
    val imagePath: String? = null,
    /** 服务端返回的 token 用量；旧记录或服务端未返回时为 null */
    val usage: TokenUsage? = null
)

/** 一段对话：一次「点球冻结画面」到面板关闭之间的所有问答 */
class Conversation(val id: Long) {
    val turns = mutableListOf<ChatTurn>()

    val totalTokens: Int
        get() = turns.sumOf { it.usage?.totalTokens ?: 0 }
}

/**
 * 本地对话记录。
 *
 * 一段对话一条记录，整体存在 `Android/data/<包名>/files/chat_history.json`，
 * 只保留最近 [MAX_CONVERSATIONS] 段、每段最多 [MAX_TURNS] 轮 —— 追问很容易几十轮，
 * 不设上限的话文件和历史消息都会无限长。
 *
 * 写盘用「临时文件 + rename」，避免进程被杀时留下半截 JSON 导致历史全丢。
 */
class ChatStore(context: Context) {

    companion object {
        private const val TAG = "ChatStore"
        private const val FILE_NAME = AppStorage.HISTORY_FILE

        /** 最多留多少段对话 */
        const val MAX_CONVERSATIONS = 30

        /** 每段最多留多少轮 */
        const val MAX_TURNS = 20
    }

    private val file = AppStorage.historyFile(context)
    private val appContext = context.applicationContext

    /** 全部历史，按时间倒序（最近的在最前面） */
    fun loadAll(): List<Conversation> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val result = ArrayList<Conversation>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val conv = Conversation(obj.optLong("id", 0L))
                val turns = obj.optJSONArray("turns") ?: JSONArray()
                for (j in 0 until turns.length()) {
                    val t = turns.optJSONObject(j) ?: continue
                    val promptTokens = t.optInt("prompt_tokens", 0)
                    val completionTokens = t.optInt("completion_tokens", 0)
                    val totalTokens = t.optInt(
                        "total_tokens",
                        promptTokens + completionTokens
                    )
                    conv.turns.add(
                        ChatTurn(
                            question = t.optString("q"),
                            answer = t.optString("a"),
                            at = t.optLong("at", 0L),
                            withMedia = t.optBoolean("media", false),
                            hasImage = t.optBoolean("image", false),
                            imagePath = t.optString("img").ifBlank { null },
                            usage = if (totalTokens > 0) {
                                TokenUsage(promptTokens, completionTokens, totalTokens)
                            } else {
                                null
                            }
                        )
                    )
                }
                result.add(conv)
            }
            result.sortedByDescending { it.id }
        } catch (t: Throwable) {
            Log.w(TAG, "读取历史失败，按空历史处理", t)
            emptyList()
        }
    }

    /** 写入/更新一段对话（同 id 覆盖），并按上限裁剪 */
    fun save(conversation: Conversation) {
        try {
            val all = loadAll().toMutableList()
            all.removeAll { it.id == conversation.id }
            val copy = Conversation(conversation.id)
            // 只留最近若干轮
            copy.turns.addAll(conversation.turns.takeLast(MAX_TURNS))
            all.add(copy)
            val trimmed = all.sortedByDescending { it.id }.take(MAX_CONVERSATIONS)
            writeAll(trimmed)
            ChatImages.prune(appContext, imageNames(trimmed))
        } catch (t: Throwable) {
            Log.w(TAG, "保存历史失败", t)
        }
    }

    fun clear() {
        try {
            if (file.exists()) file.delete()
            ChatImages.prune(appContext, emptySet())
        } catch (t: Throwable) {
            Log.w(TAG, "清空历史失败", t)
        }
    }

    /** 删除单独一段对话；不存在时返回 false。 */
    fun delete(id: Long): Boolean {
        return try {
            val all = loadAll().toMutableList()
            if (!all.removeAll { it.id == id }) return false
            writeAll(all)
            ChatImages.prune(appContext, imageNames(all))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "删除历史失败", t)
            false
        }
    }

    /** 当前历史引用到的全部图片文件名；不在这个集合里的都算孤儿 */
    private fun imageNames(list: List<Conversation>): Set<String> {
        val names = HashSet<String>()
        for (conv in list) {
            for (turn in conv.turns) {
                turn.imagePath?.takeIf { it.isNotBlank() }?.let { names.add(it) }
            }
        }
        return names
    }

    /** 清掉所有附图，并把历史里的引用一起摘掉（文字保留） */
    fun clearImages() {
        try {
            val all = loadAll()
            var cleared = false
            for (conv in all) {
                for (index in conv.turns.indices) {
                    val turn = conv.turns[index]
                    if (turn.imagePath != null) {
                        conv.turns[index] = turn.copy(imagePath = null)
                        cleared = true
                    }
                }
            }
            // 引用摘干净了再重写，避免留下指向已删文件的记录
            if (cleared) writeAll(all)
            ChatImages.prune(appContext, emptySet())
        } catch (t: Throwable) {
            Log.w(TAG, "清理图片失败", t)
        }
    }

    /** @return 段数 to 轮数 */
    fun stats(): Pair<Int, Int> {
        val all = loadAll()
        return all.size to all.sumOf { it.turns.size }
    }

    private fun writeAll(list: List<Conversation>) {
        val array = JSONArray()
        for (conv in list) {
            val turns = JSONArray()
            for (t in conv.turns) {
                val turnJson = JSONObject()
                    .put("q", t.question)
                    .put("a", t.answer)
                    .put("at", t.at)
                    .put("media", t.withMedia)
                    .put("image", t.hasImage)
                    .put("img", t.imagePath.orEmpty())
                t.usage?.let {
                    turnJson
                        .put("prompt_tokens", it.promptTokens)
                        .put("completion_tokens", it.completionTokens)
                        .put("total_tokens", it.totalTokens)
                }
                turns.put(turnJson)
            }
            array.put(JSONObject().put("id", conv.id).put("turns", turns))
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("写入历史失败")
        }
    }
}
