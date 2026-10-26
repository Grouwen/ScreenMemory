package com.screenmemory.app.util

/**
 * 渲染前的 LaTeX 归一化。
 *
 * Markwon 的 ext-latex 只认 `$$...$$`（见 JLatexMathInlineProcessor 的正则
 * `(\$\$)([\s\S]+?)\1` 与 JLatexMathBlockParser），既不吃单美元 `$...$`，
 * 也不吃 `\(...\)` / `\[...\]`。而模型是按 LaTeX 惯例写的，所以这里统一翻译成
 * `$$...$$`，让模型可以自由输出，不必在提示词里规定死写法。
 *
 * 三条保护：
 * 1. 单美元转换时，前后不能再有 `$`，否则会把已经正确的 `$$...$$` 拆坏；
 * 2. 单美元不跨行，避免把段落里零散的美元符号连成一段公式；
 * 3. 内容里带中文、或不像公式的，不当公式 —— 否则「$5 和 $10」
 *    会被拼成一段永不闭合的公式，把后面整段答案吞掉。
 * 4. `$x$`、`$a$`、`$0$` 这类没有运算符的简单变量和数字仍是公式。
 */
object Latex {

    private val bracketMath = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
    private val parenMath = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)
    private val inlineMath = Regex("(?<!\\$)\\$([^$\\n]+?)\\$(?!\\$)")
    private val cjk = Regex("[\\u4e00-\\u9fa5]")
    private val mathish = Regex("[\\\\^_{}=()+\\-*/<>|]")
    private val simpleVariableOrNumber = Regex(
        """(?:[A-Za-zα-ωΑ-Ω][A-Za-z0-9_']*|[-+]?(?:\d+(?:\.\d+)?|\.\d+))"""
    )

    fun normalize(markdown: String): String {
        var text = markdown
        if (text.contains("\\[")) {
            text = bracketMath.replace(text) { match -> "$$" + match.groupValues[1] + "$$" }
        }
        if (text.contains("\\(")) {
            text = parenMath.replace(text) { match -> "$$" + match.groupValues[1] + "$$" }
        }
        if (!text.contains('$')) return text
        return inlineMath.replace(text) { match ->
            val body = match.groupValues[1]
            val looksLikeMath = !cjk.containsMatchIn(body) && (
                mathish.containsMatchIn(body) ||
                    simpleVariableOrNumber.matches(body.trim())
                )
            if (looksLikeMath) "$$" + body + "$$" else match.value
        }
    }
}
