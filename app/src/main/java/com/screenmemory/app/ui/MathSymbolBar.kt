package com.screenmemory.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 输入框上方的轻量数学符号栏。数字继续由系统键盘输入，
 * 这里只提供运算符号、数学符号、函数和 LaTeX 模板。
 */
class MathSymbolBar(
    context: Context,
    private val target: () -> EditText?
) : LinearLayout(context) {

    private data class Symbol(
        val label: String,
        val inserted: String = label
    )

    private val cursorMarker = '\u0001'

    init {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(6))
        addRow(
            listOf(
                "+", "−", "×", "÷", "±", "=", "≠", "≈", "<", "≤", ">", "≥",
                "(", ")", "[", "]", "{", "}", "^", "_", "√", "π", "∞", "°", "%",
                "α", "β", "γ", "θ", "Δ"
            ).map { Symbol(it) }
        )
        addRow(
            listOf(
                Symbol("sin", "sin"),
                Symbol("cos", "cos"),
                Symbol("tan", "tan"),
                Symbol("log", "log"),
                Symbol("ln", "ln"),
                Symbol("a/b", "\\frac{$cursorMarker}{$cursorMarker}"),
                Symbol("xⁿ", "x^{$cursorMarker}"),
                Symbol("xₙ", "x_{$cursorMarker}"),
                Symbol("√x", "\\sqrt{$cursorMarker}"),
                Symbol("lim", "\\lim_{$cursorMarker}"),
                Symbol("∫", "\\int_{$cursorMarker}"),
                Symbol("Σ", "\\sum_{$cursorMarker}")
            )
        )
    }

    private fun addRow(symbols: List<Symbol>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (symbol in symbols) {
            row.addView(
                TextView(context).apply {
                    text = symbol.label
                    gravity = Gravity.CENTER
                    isFocusable = false
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    minWidth = dp(36)
                    setPadding(dp(9), 0, dp(9), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(themeColor(com.google.android.material.R.attr.colorSurfaceVariant))
                    }
                    setOnClickListener { insert(symbol) }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply {
                    marginEnd = dp(5)
                }
            )
        }
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(36))
        )
    }

    private fun insert(symbol: Symbol) {
        val input = target() ?: return
        val editable = input.text ?: return
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        val cursorOffset = symbol.inserted.indexOf(cursorMarker)
        val inserted = symbol.inserted.replace(cursorMarker.toString(), "")
        editable.replace(start, end, inserted)
        val cursor = start + if (cursorOffset >= 0) cursorOffset else inserted.length
        input.setSelection(cursor.coerceIn(0, editable.length))
        input.requestFocus()
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            context.getColor(value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
