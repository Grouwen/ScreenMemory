package com.screenmemory.app.util

/**
 * 流式输出期间的「预览降级」：把 Markdown 记号抹掉，只显示纯文本。
 *
 * 为什么不直接渲染：公式是逐字流出来的，`$\frac{1` 这种半截 LaTeX 交给
 * JLatexMath 会在绘制阶段解析失败并抛异常（draw 里抛的异常 catch 不住，会崩）。
 * 所以流式阶段只做去符号，等整段结束再交给 Markwon 一次性渲染。
 */
object Preview {

    private val heading = Regex("(?m)^#{1,6}[ \\t]*")
    private val bullet = Regex("(?m)^[ \\t]*[-*+][ \\t]+")

    fun plain(markdown: String): String {
        var text = markdown
        text = text.replace("**", "").replace("__", "").replace("`", "")
        text = text.replace("\$\$", "").replace("\$", "")
        text = heading.replace(text, "")
        text = bullet.replace(text, "· ")
        return text
    }
}
