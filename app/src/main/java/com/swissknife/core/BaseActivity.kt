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
import com.swissknife.R

/**
 * 状态值的语义色。整套界面只允许这四种，新增页面不要另起颜色。
 *
 * - [OK]：功能在正常工作（运行中 / 生效中 / 已就绪）
 * - [WARN]：需要用户做点什么（等待授权 / 写入失败）
 * - [NEUTRAL]：既不是好也不是坏，但要看得清（已开启 / 生效中这类正向陈述）
 * - [MUTED]：同样中性，但属于"没在跑"的一侧（已关闭 / 未生效 / 已暂停）
 */
enum class Tone { OK, WARN, NEUTRAL, MUTED }

/**
 * 所有页面共用的界面底座。
 *
 * 沿用上一个项目的做法：纯代码构建 View，不放 XML 布局。
 * 好处是同一个样式只定义一次，新增功能页直接复用，不会出现"改了一处忘了一处"。
 *
 * **功能二级页必须用 [featurePage] 搭骨架**，不要再自己 `page() + sectionTitle() + card()`
 * 拼一套——那样每个功能页会长得不一样，用户每进一个功能都要重新找信息在哪。
 * 标准与槽位定义见 HANDOFF「功能二级页统一标准」。
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

    /**
     * 授权 / 依赖行的统一画法：就绪 = 绿字；未就绪 = 橙字 + 淡橙圆角底（一眼看出能点）。
     *
     * 首页和所有功能页共用这一个实现——同一个状态在任何页面必须长得一样，
     * 否则用户会以为"这里说已就绪、那里却要我去开启"是两个不同的问题。
     *
     * okText / badText 可覆盖默认文案：「后台常驻」这类状态不是权限，
     * 说"去开启"会误导，改用"运行中 / 未启动"。
     */
    protected fun applyAuth(
        view: TextView,
        ok: Boolean,
        okText: Int = R.string.status_ok,
        badText: Int = R.string.status_go_enable
    ) {
        val line = view.parent as? android.view.View
        if (ok) {
            view.text = getString(okText)
            view.setTextColor(colorOk)
            line?.background = null
            view.background = null
            view.setPadding(0, 0, 0, 0)
        } else {
            view.text = getString(badText)
            view.setTextColor(colorWarn)
            // 底色只包住这几个字，比卡片底色略深一档，够提示又不抢眼
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFFEAEAE6.toInt())
            }
            view.setPadding(dp(10), dp(3), dp(10), dp(3))
        }
    }

    /**
     * 功能二级页的统一骨架。槽位固定、顺序固定：
     *
     * ```
     * ‹ 返回
     * 功能名（26sp 加粗）
     * 一段说明：这个功能到底做什么、为什么需要它
     * ── 当前状态 ──   status() × N      右对齐的值，配 Tone 上色
     *                  note()            卡下一行小字（最近动作 / 计数口径）
     *                  raw()             更小一档的权威原始值（有系统真值时才用）
     * ── 依赖 ──      dep() × N         整行可点，点了就去修
     * ── 怎么用 ──    steps(list)       编号条目，文案必须与实现一致
     *                  footnote(s)       末尾一段补充说明（不分条目）
     *                  action(...)       可选按钮
     * ```
     *
     * 各段是"用到才创建"的，所以调用顺序即渲染顺序；但**不要**打乱语义顺序——
     * 状态一定在最上、用法一定在最下，用户每进一个功能都按同一个视线顺序找信息。
     *
     * 骨架只负责"摆位置"，值的文字与颜色统一用 [setStatus] / [applyAuth] 上——
     * 这样同一句话在任何页面都是同一个颜色。
     */
    protected fun featurePage(titleText: String, descText: String): FeaturePage =
        FeaturePage(titleText, descText)

    /** 状态值：文字 + 语义色。四种色见 [Tone]，不要绕过它直接 setTextColor。 */
    protected fun setStatus(view: TextView, value: String, tone: Tone) {
        view.text = value
        view.setTextColor(
            when (tone) {
                Tone.OK -> colorOk
                Tone.WARN -> colorWarn
                Tone.NEUTRAL -> colorTitle
                Tone.MUTED -> colorBody
            }
        )
    }

    inner class FeaturePage(titleText: String, descText: String) {

        private val root: LinearLayout = page()
        private var statusCard: LinearLayout? = null
        private var depCard: LinearLayout? = null

        init {
            root.addView(
                text("‹ 返回", 14f, colorBody).apply {
                    setPadding(0, 0, 0, dp(10))
                    isClickable = true
                    setOnClickListener { finish() }
                }
            )
            root.addView(title(titleText))
            if (descText.isNotEmpty()) {
                root.addView(
                    text(descText, 13f, colorBody).apply { setPadding(0, dp(10), 0, 0) }
                )
            }
        }

        /** 加一行「状态 —— 值」。返回右侧值控件，用 [setStatus] 填值上色。 */
        fun status(label: String): TextView =
            row(section(statusCard, R.string.section_current) { statusCard = it }, label)

        /** 状态卡下方的小字：最近动作、计数口径这类"补充一句"的信息。 */
        fun note(): TextView = text("", 12f, colorBody).apply {
            setPadding(dp(2), dp(10), 0, 0)
            root.addView(this)
        }

        /** 再小一档的权威原始值。只有功能真的能从系统侧读到真值时才用，否则别放。 */
        fun raw(): TextView = text("", 11f, colorHint).apply {
            setPadding(dp(2), dp(6), 0, 0)
            root.addView(this)
        }

        /** 加一行依赖（Shizuku / 无障碍 / 后台常驻…）。整行可点，点了就去修。 */
        fun dep(label: String, onFix: () -> Unit): TextView {
            val holder = section(depCard, R.string.section_deps) { depCard = it }
            val v = row(holder, label)
            (v.parent as? android.view.View)?.apply {
                isClickable = true
                setOnClickListener { onFix() }
            }
            return v
        }

        /** 「怎么用」：编号条目。**必须**编号从 1 连续，且文案与实现一致。 */
        fun steps(lines: List<String>) {
            if (lines.isEmpty()) return
            root.addView(sectionTitle(getString(R.string.section_howto)))
            root.addView(
                text(lines.joinToString("\n"), 13f, colorBody)
                    .apply { setPadding(dp(2), 0, 0, 0) }
            )
        }

        /** 末尾一段补充说明（不分条目）。例如「关于显隐」这类解释性文字。 */
        fun footnote(s: String) {
            if (s.isBlank()) return
            root.addView(
                text(s, 13f, colorBody).apply { setPadding(dp(2), dp(14), 0, 0) }
            )
        }

        /** 可选：页面内的操作按钮（尽量不要用，能在首页做的就别放这里）。 */
        fun action(label: String, onClick: () -> Unit) {
            root.addView(actionButton(label, onClick))
        }

        /**
         * 参数输入槽：一个数字输入框 + 一个保存按钮，放在「怎么用」之后。
         *
         * 为什么做成骨架槽位而不是让各页面自己拼：调参在每个页面都长一个样，
         * 用户不必重新找"在哪里改"。**开关仍然只在首页**——这里改的是参数，不是启停。
         *
         * 首次调用会插入「调参」分组标题，之后复用。
         */
        fun input(
            hint: String,
            initial: String,
            saveLabel: String,
            onSave: (String) -> Unit
        ): android.widget.EditText {
            if (!inputSectionAdded) {
                root.addView(sectionTitle(getString(R.string.section_tuning)))
                inputSectionAdded = true
            }
            val box = android.widget.EditText(this@BaseActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setHint(hint)
                setText(initial)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
            root.addView(
                box,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(actionButton(saveLabel) { onSave(box.text.toString().trim()) })
            return box
        }

        private var inputSectionAdded = false

        fun build(): ScrollView = wrap(root)

        /** 懒建某个分组的卡片：第一次用到时插入标题与卡片，之后往里加行。 */
        private fun section(
            existing: LinearLayout?,
            titleRes: Int,
            assign: (LinearLayout) -> Unit
        ): LinearLayout {
            existing?.let { return it }
            root.addView(sectionTitle(getString(titleRes)))
            val c = card { }
            root.addView(c)
            assign(c)
            return c
        }
    }
}
