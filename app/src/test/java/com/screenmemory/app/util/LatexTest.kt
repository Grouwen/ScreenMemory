package com.screenmemory.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexTest {

    @Test
    fun convertsSimpleVariablesAndNumbers() {
        assertEquals("\$\$x\$\$", Latex.normalize("\$x\$"))
        assertEquals("\$\$a\$\$", Latex.normalize("\$a\$"))
        assertEquals("\$\$n\$\$", Latex.normalize("\$n\$"))
        assertEquals("\$\$0\$\$", Latex.normalize("\$0\$"))
        assertEquals("\$\$α\$\$", Latex.normalize("\$α\$"))
    }

    @Test
    fun convertsExpressionsThatAlreadyWorked() {
        assertEquals("\$\$x+1\$\$", Latex.normalize("\$x+1\$"))
        assertEquals("\$\$a_n\$\$", Latex.normalize("\$a_n\$"))
        assertEquals(
            "\$\$\\frac{1}{2}\$\$",
            Latex.normalize("\$\\frac{1}{2}\$")
        )
        assertEquals("\$\$f(x)\$\$", Latex.normalize("\$f(x)\$"))
    }

    @Test
    fun convertsStandardLatexDelimiters() {
        assertEquals("\$\$x\$\$", Latex.normalize("\\(x\\)"))
        assertEquals("\$\$x+1\$\$", Latex.normalize("\\[x+1\\]"))
    }

    @Test
    fun leavesAlreadyConvertedBlockMathUntouched() {
        assertEquals("\$\$x\$\$", Latex.normalize("\$\$x\$\$"))
        assertEquals(
            "Text \$\$x+1\$\$ end",
            Latex.normalize("Text \$\$x+1\$\$ end")
        )
    }

    @Test
    fun doesNotTreatCurrencyAsMath() {
        val chinese = "价格是 \$5 和 \$10"
        val english = "costs \$5 and \$10"
        assertEquals(chinese, Latex.normalize(chinese))
        assertEquals(english, Latex.normalize(english))
    }

    @Test
    fun doesNotConvertChineseTextInsideDollarSigns() {
        assertEquals("\$甲\$", Latex.normalize("\$甲\$"))
    }

    @Test
    fun streamingKeepsCompleteMathAndDefersOpenMath() {
        assertEquals(
            "结果 \$\$x^2\$\$",
            Latex.forStreaming("结果 \$x^2\$")
        )
        val partial = Latex.forStreaming("正在写 \$\$\\frac{1")
        assertEquals(false, partial.contains("\$\$"))
        assertEquals(true, partial.contains("\\frac{1"))
    }

    @Test
    fun invalidClosedMathFallsBackToPlainText() {
        val rendered = Latex.forRender("坏公式 \$\$\\frac{1}{\$\$")
        assertEquals(false, rendered.contains("\$\$"))
        assertEquals(true, rendered.contains("\\frac{1}{"))
    }
}
