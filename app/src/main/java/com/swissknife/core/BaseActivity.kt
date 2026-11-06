package com.swissknife.core

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 三个页面共用的界面底座。
 *
 * 沿用上一个项目的做法：纯代码构建 View，不放 XML 布局。
 * 好处是同一个样式只定义一次，新增功能页直接复用，不会出现"改了一处忘了一处"。
 */
abstract class BaseActivity : Activity() {

    protected val colorTitle = Color.parseColor("#111111")
    protected val colorBody = Color.parseColor("#6E7681")
    protected val colorHint = Color.parseColor("#8A8A8A")
    protected val colorCard = Color.parseColor("#F6F6F4")

    /** 状态色只有两种，新增状态行必须复用，不要另起颜色。 */
    protected val colorOk = Color.parseColor("#1D9E75")
    protected val colorWarn = Color.parseColor("#D85A30")

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 页面根容器：统一白底 + 统一留白。 */
    protected fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(34), dp(24), dp(40))
        setBackgroundColor(Color.WHITE)
    }

    protected fun wrap(root: LinearLayout): ScrollView =
        ScrollView(this).apply { addView(root) }

    protected fun text(
        s: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** 页面大标题，26sp 加粗。 */
    protected fun title(s: String): TextView =
        text(s, 26f, colorTitle, bold = true)

    /** 分组小标题，15sp 加粗，上方留白。 */
    protected fun sectionTitle(s: String): TextView =
        text(s, 15f, colorTitle, bold = true).apply { setPadding(0, dp(24), 0, dp(10)) }

    /** 浅灰卡片容器。 */
    protected fun card(block: (LinearLayout) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(colorCard)
            block(this)
        }

    /**
     * 卡片内的一行「标签 —— 值」。
     * 返回右侧的 TextView，调用方用 setText / setTextColor 更新状态。
     */
    protected fun row(parent: LinearLayout, label: String): TextView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        line.addView(
            text(label, 14f, colorBody).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        val value = text("--", 14f, colorTitle, bold = true)
        line.addView(value)
        parent.addView(line)
        return value
    }

    /** 全宽按钮，Material 默认样式。 */
    protected fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

    protected fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
