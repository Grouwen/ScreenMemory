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

    /**
     * `\text{...}` 里可以放任意文字（中文、标点、空格），本身的语义就是「公式里的文字」，
     * 所以 `\text{高度差}` 或 `\text{某东西}` 即使含中文也应该视为公式体。
     * 正向命中不受 cjk 过滤影响。
     */
    private val textCommandInner = Regex("""^(?:\s*\\text\{.+\}\s*)*$""")
    private val mathish = Regex("[\\\\^_{}=()+\\-*/<>|]")
    private val displayMath = Regex("""\$\$([\s\S]+?)\$\$""")
    private val incompleteCommand = Regex(
        """\\(?:frac|sqrt|text|mathrm|mathbf|mathit|left|right|begin|end)$"""
    )
    private val leftCommand = Regex("""\\left(?![A-Za-z])""")
    private val rightCommand = Regex("""\\right(?![A-Za-z])""")
    private val beginCommand = Regex("""\\begin(?![A-Za-z])""")
    private val endCommand = Regex("""\\end(?![A-Za-z])""")
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
            val looksLikeMath = textCommandInner.containsMatchIn(body) || (
                !cjk.containsMatchIn(body) && (
                    mathish.containsMatchIn(body) ||
                        simpleVariableOrNumber.matches(body.trim())
                )
            )
            if (looksLikeMath) "$$" + body + "$$" else match.value
        }
    }

    /** 最终渲染前使用：完整但无法解析的公式降级为普通文本，避免绘制阶段崩溃。 */
    fun forRender(markdown: String): String = sanitizeMath(normalize(markdown), streaming = false)

    /**
     * 流式渲染使用：保留完整合法的公式；正在输出、尚未闭合的最后一个公式暂时按普通文本显示。
     */
    fun forStreaming(markdown: String): String = sanitizeMath(normalize(markdown), streaming = true)

    private fun sanitizeMath(markdown: String, streaming: Boolean): String {
        var text = displayMath.replace(markdown) { match ->
            if (isStructurallyComplete(match.groupValues[1])) {
                match.value
            } else {
                match.groupValues[1]
            }
        }
        if (!streaming) return text

        var delimiterCount = 0
        var index = text.indexOf("$$")
        while (index >= 0) {
            delimiterCount++
            index = text.indexOf("$$", index + 2)
        }
        if (delimiterCount % 2 == 1) {
            val open = text.lastIndexOf("$$")
            if (open >= 0) text = text.removeRange(open, open + 2)
        }
        return text
    }

    private fun isStructurallyComplete(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.endsWith("\\") || incompleteCommand.containsMatchIn(trimmed)) {
            return false
        }
        var depth = 0
        var escaped = false
        for (char in trimmed) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 &&
            leftCommand.findAll(trimmed).count() == rightCommand.findAll(trimmed).count() &&
            beginCommand.findAll(trimmed).count() == endCommand.findAll(trimmed).count()
    }
}
