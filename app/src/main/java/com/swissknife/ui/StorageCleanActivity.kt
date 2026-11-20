package com.swissknife.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.swissknife.R
import com.swissknife.clean.ShizukuUserService
import com.swissknife.clean.StorageCleaner
import com.swissknife.clean.TruncateService
import com.swissknife.core.AppLog
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.core.Tone
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「空间回收」功能页。
 *
 * 角色分工（这是本功能最容易写反的地方）：
 * - 本页只做 UI 与状态机，**不碰目标目录的文件**——应用自身 uid 在 Android 11+
 *   列不出别人的 Android/data，在应用侧调 `File.listFiles()` 必然拿到 null；
 * - 真正的扫描与截断全在 [TruncateService] 里，那东西跑在 Shizuku 拉起的 shell 进程（uid 2000）。
 *
 * 它没有开关：不常驻、不订阅系统事件，只在点按钮时执行一次，所以也不进 anyFeatureEnabled。
 */
class StorageCleanActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var channelValue: TextView
    private lateinit var scopeValue: TextView
    private lateinit var countValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var lastValue: TextView
    private lateinit var scopeNote: TextView
    private lateinit var noteView: TextView
    private lateinit var listHost: LinearLayout
    private var truncateBtn: Button? = null

    /** 「依赖 → Shizuku」那一行的右侧值。**必须**用 applyAuth 填，否则只剩标签没有状态。 */
    private lateinit var shizukuValue: TextView

    private var entries: List<StorageCleaner.Entry> = emptyList()
    private val selected = linkedSetOf<String>()

    /** 当前扫描方式。三个按钮各对应一种，状态行与空态文案都跟着它变。 */
    private enum class Mode { LARGE, CACHE, MANUAL }

    private var mode = Mode.LARGE

    /** 阈值 / 指定目录输入框：放在各自按钮下方，重建列表时要把用户正在输的内容接着用。 */
    private var minSizeBox: android.widget.EditText? = null
    private var pathBox: android.widget.EditText? = null

    /** 被收起的组（存组根路径）。默认全部展开，所以只记"收起的"。 */
    private val collapsedGroups = mutableSetOf<String>()

    /**
     * 「缓存类目录」被展开后列出的下级文件。
     *
     * 为什么要展开：cache 里常常几千个小文件，整目录清空是"全丢"；
     * 用户可能只想删其中几个大的（2026-11-13 要求）。展开后每个文件可单独勾选，
     * 清理时目录与其他勾选项按路径前缀去重，不会重复计算。
     */
    private val expandedDirs = mutableSetOf<String>()
    private val dirChildren = mutableMapOf<String, List<StorageCleaner.Entry>>()
    private val loadingDirs = mutableSetOf<String>()

    /**
     * 组头控件引用。单条勾选时**只改这些控件的文本/勾选状态，不重建列表**：
     * 重建会把正在被点的 CheckBox 从视图树里摘掉（手感上像"点了没反应"），
     * 但不更新组头又会让「已选 x/y」停在旧值（2026-11-13 实测）。
     */
    private class GroupUi(
        val group: StorageCleaner.Group,
        val check: CheckBox,
        val meta: TextView,
        val listener: android.widget.CompoundButton.OnCheckedChangeListener
    )

    private val groupUis = mutableListOf<GroupUi>()

    /** 扫描 / 截断期间禁用按钮：这些都是跨进程调用，重复点只会叠出多条并发事务。 */
    private var busy = false

    /**
     * 截断明细（受保护 / 已消失 / 其它各几项）。
     * 单独存一份是因为截断完会立刻自动重扫，而重扫会把 note 改成「扫描完成 N 项」——
     * 明细直接被顶掉，用户看不到哪几个失败了（2026-11-12 实测踩到）。
     */
    private var lastDetail: String = ""

    private val stateListener: (ShizukuUserService.State) -> Unit = { renderChannel(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildPage())
        ShizukuUserService.setListener(stateListener)
        ShizukuUserService.ensureBound(this) { renderChannel(it) }
        renderChannel(ShizukuUserService.state())
        renderScope()
        renderDeps()
        renderStatus()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页回来时这里会再跑一次：ensureBound 幂等，已连上直接回 READY
        ShizukuUserService.ensureBound(this) { renderChannel(it) }
        // 授权状态可能刚变过，依赖行要跟着刷
        renderDeps()
    }

    override fun onDestroy() {
        ShizukuUserService.clearListener(stateListener)
        // 退出页面就把 shell 进程还回去：只解绑不回收（实测每进一次多留一个进程），
        // 所以让服务自己退出，再解绑。
        runCatching { ShizukuUserService.service()?.shutdown() }
        ShizukuUserService.release()
        super.onDestroy()
    }

    // ---------------- 界面 ----------------

    private fun buildPage(): android.widget.ScrollView {
        val fp = featurePage(
            getString(R.string.feature_storage_clean),
            getString(R.string.sc_desc)
        )

        channelValue = fp.status(getString(R.string.sc_status_channel))
        scopeValue = fp.status(getString(R.string.sc_status_scope))
        countValue = fp.status(getString(R.string.sc_status_count))
        sizeValue = fp.status(getString(R.string.sc_status_size))
        lastValue = fp.status(getString(R.string.sc_status_last))
        // 状态行右侧只放短值（「白名单模式」/「手输路径」）：row() 里标签带 weight、
        // 值不带 weight，值一旦写长就会把标签挤成 0 宽——2026-11-12 实测踩到过。
        // 完整说明另起一行放在卡下方。
        scopeNote = fp.note()
        noteView = fp.note()

        // 列表放在「当前状态」与「依赖」之间——这是骨架给自定义内容留的槽
        listHost = fp.content(R.string.sc_section_list)

        fp.dep(getString(R.string.label_shizuku)) { onShizukuClick() }
            .also { shizukuValue = it }

        fp.steps(
            listOf(
                getString(R.string.sc_rule_1),
                getString(R.string.sc_rule_2),
                getString(R.string.sc_rule_3),
                getString(R.string.sc_rule_4),
                getString(R.string.sc_rule_5)
            )
        )
        fp.footnote(getString(R.string.sc_footnote))

        // 三个扫描按钮与各自的调参都在 content 槽里（见 renderList），
        // 不再用骨架的 input 槽：那样调参会跑到页面最底部，和按钮隔着一整个列表。

        return fp.build()
    }

    private fun onShizukuClick() {
        when {
            !ShizukuShell.isAvailable() -> toast(getString(R.string.toast_shizuku_missing))
            ShizukuShell.hasPermission() -> {
                ShizukuUserService.ensureBound(this) { renderChannel(it) }
                toast(getString(R.string.status_ok))
            }
            else -> ShizukuShell.requestPermission()
        }
    }

    private fun renderChannel(s: ShizukuUserService.State) {
        val (text, tone) = when (s) {
            ShizukuUserService.State.READY ->
                getString(R.string.sc_channel_ready) to Tone.OK
            ShizukuUserService.State.BINDING ->
                getString(R.string.sc_channel_binding) to Tone.MUTED
            ShizukuUserService.State.NO_PERMISSION ->
                getString(R.string.sc_channel_no_permission) to Tone.WARN
            ShizukuUserService.State.NOT_RUNNING ->
                getString(R.string.sc_channel_no_runner) to Tone.WARN
            ShizukuUserService.State.FAILED ->
                getString(R.string.sc_channel_failed) to Tone.WARN
        }
        setStatus(channelValue, text, tone)
    }

    /**
     * 依赖行的状态。**`dep()` 只负责建行，状态值要自己用 `applyAuth` 填**——
     * 漏了这一步，界面上就只剩「Shizuku」三个字、右侧空白（2026-11-13 用户报的就是这个）。
     */
    private fun renderDeps() {
        applyAuth(shizukuValue, ShizukuShell.isAvailable() && ShizukuShell.hasPermission())
    }

    private fun renderScope() {
        val manual = prefs.cleanManualPath
        val mb = prefs.cleanMinSizeMb
        setStatus(
            scopeValue,
            when (mode) {
                Mode.LARGE -> getString(R.string.sc_scope_large)
                Mode.CACHE -> getString(R.string.sc_scope_cache)
                Mode.MANUAL -> getString(R.string.sc_scope_manual)
            },
            Tone.NEUTRAL
        )
        scopeNote.text = when (mode) {
            Mode.LARGE -> getString(R.string.sc_scope_large_note, mb)
            Mode.CACHE -> getString(R.string.sc_scope_cache_note, mb)
            Mode.MANUAL -> if (manual.isBlank()) {
                getString(R.string.sc_scope_manual_empty)
            } else {
                getString(R.string.sc_scope_manual_note, manual)
            }
        }
    }

    private fun renderStatus() {
        val total = StorageCleaner.totalSize(entries, selected)
        setStatus(countValue, "${entries.size} 项（已选 ${selected.size}）", Tone.NEUTRAL)
        setStatus(
            sizeValue,
            StorageCleaner.humanSize(total),
            if (total > 0) Tone.NEUTRAL else Tone.MUTED
        )
        val last = prefs.cleanLastEvent
        if (last.isBlank()) {
            setStatus(lastValue, "--", Tone.MUTED)
        } else {
            setStatus(lastValue, last, Tone.NEUTRAL)
        }
        truncateBtn?.isEnabled = !busy && selected.isNotEmpty()
        syncGroupHeaders()
    }

    /** 把「已选 x/y」和组勾选框同步到当前 selected（不动列表结构）。 */
    private fun syncGroupHeaders() {
        groupUis.forEach { ui ->
            val clearable = ui.group.clearable
            val picked = clearable.count { it.path in selected }
            ui.check.setOnCheckedChangeListener(null)
            ui.check.isChecked = clearable.isNotEmpty() && picked == clearable.size
            ui.check.isEnabled = !busy && clearable.isNotEmpty()
            ui.check.setOnCheckedChangeListener(ui.listener)
            ui.meta.text = groupMeta(ui.group, picked)
        }
    }

    private fun groupMeta(g: StorageCleaner.Group, picked: Int): String = buildString {
        append(g.root)
        append(" · ")
        append(getString(R.string.sc_group_meta, g.entries.size))
        if (picked > 0) {
            append(" · ")
            append(getString(R.string.sc_group_picked, picked, g.clearable.size))
        }
        if (g.root in collapsedGroups) append(getString(R.string.sc_group_collapsed))
    }

    private fun renderList() {
        listHost.removeAllViews()
        groupUis.clear()

        // 操作区放在列表**上方**：条目多时（实测 26 项、根目录 300 项）按钮会被推到
        // 屏幕外，得先滚过整个列表才点得到——2026-11-12 实测踩到。
        // 顺序按用户 2026-11-13 要求：先调参、后三个扫描按钮。
        // 阈值行与路径行各自紧贴按钮行上方，归属仍靠标签点明（阈值 / 目录）。
        listHost.addView(tuningRow(
            label = getString(R.string.sc_min_label),
            box = minSizeEdit(),
            save = getString(R.string.sc_min_save),
            onSave = {
                prefs.cleanMinSizeMb = minSizeBox?.text?.toString()?.trim()?.toIntOrNull()
                    ?: Prefs.DEFAULT_CLEAN_MIN_SIZE_MB
                minSizeBox?.setText(prefs.cleanMinSizeMb.toString())
                renderScope()
                startScan(Mode.LARGE)
            }
        ))
        listHost.addView(tuningRow(
            label = getString(R.string.sc_path_label),
            box = pathEdit(),
            save = getString(R.string.sc_manual_save),
            onSave = {
                prefs.cleanManualPath = pathBox?.text?.toString()?.trim().orEmpty()
                renderScope()
                startScan(Mode.MANUAL)
            }
        ))
        listHost.addView(scanButtonRow())

        // 勾选状态变化只改这一个按钮的可点性，不重建整个列表——
        // 重建会把正在被点的 CheckBox 从视图树里摘掉，手感上像"点了没反应"
        truncateBtn = actionButton(getString(R.string.sc_btn_truncate)) { startTruncate() }
            .apply { isEnabled = !busy && selected.isNotEmpty() }
        listHost.addView(truncateBtn)

        if (entries.isNotEmpty()) listHost.addView(selectionBar())

        if (entries.isEmpty()) {
            listHost.addView(
                text(getString(R.string.sc_list_empty), 13f, colorBody)
                    .apply { setPadding(0, dp(8), 0, dp(8)) }
            )
        } else {
            // 按应用分组：组头回答"哪个应用在占空间"，点组头折叠/展开（默认全展开）
            StorageCleaner.group(entries, prefs.cleanManualPath).forEach { g ->
                listHost.addView(groupRow(g))
                if (g.root !in collapsedGroups) {
                    g.entries.forEach { listHost.addView(entryRow(g, it)) }
                }
            }
        }
    }

    /** 三个扫描按钮并列一行。文案取短名，1/3 屏宽才放得下。 */
    private fun scanButtonRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, m: Mode, last: Boolean = false) {
            val b = actionButton(label) { startScan(m) }
            b.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                topMargin = 0
                if (!last) marginEnd = dp(6)
            }
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            b.isEnabled = !busy
            row.addView(b)
        }
        add(getString(R.string.sc_btn_large), Mode.LARGE)
        add(getString(R.string.sc_btn_cache), Mode.CACHE)
        add(getString(R.string.sc_btn_manual_dir), Mode.MANUAL, last = true)
        return row
    }

    /**
     * 调参行：`标签 [输入框] [保存]`，紧贴在它所属的扫描按钮下方。
     *
     * 输入框做成**复用同一个实例**：重建列表（扫描完成、勾选变化）时不能把用户
     * 正在输的内容冲掉。`removeAllViews()` 已经把它从旧父容器摘下，可以直接再 add。
     */
    private fun tuningRow(label: String, box: android.widget.EditText, save: String, onSave: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(
            text(label, 12f, colorHint).apply { setPadding(0, 0, dp(8), 0) }
        )
        // 输入框是跨重建复用的同一个实例，它此刻还挂在上一轮的行里；
        // 不先摘下来 addView 会抛 IllegalStateException（2026-11-13 真机崩溃，dropbox 抓到）
        (box.parent as? ViewGroup)?.removeView(box)
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            actionButton(save) { onSave() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 0; marginStart = dp(6) }
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                minWidth = 0
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }
        )
        return row
    }

    private fun minSizeEdit(): android.widget.EditText = minSizeBox ?: editBox(
        hint = getString(R.string.sc_min_hint),
        initial = prefs.cleanMinSizeMb.toString(),
        numberOnly = true
    ).also { minSizeBox = it }

    private fun pathEdit(): android.widget.EditText = pathBox ?: editBox(
        hint = getString(R.string.sc_manual_hint),
        initial = prefs.cleanManualPath,
        numberOnly = false
    ).also { pathBox = it }

    private fun editBox(hint: String, initial: String, numberOnly: Boolean): android.widget.EditText =
        android.widget.EditText(this).apply {
            inputType = if (numberOnly) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
            setHint(hint)
            setText(initial)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }

    /**
     * 全选 / 全不选（跨所有组）。全盘扫描一次可能列出几十条 ≥100 MB 的文件，
     * 逐条点太慢；但**默认仍然一条都不勾**（见 StorageCleaner.defaultSelection）。
     * 只想清某个应用时，用该组组头的勾选框更省事。
     */
    private fun selectionBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        row.addView(link(getString(R.string.sc_btn_select_all)) {
            entries.filter { !it.isShell }.forEach { selected.add(it.path) }
            renderStatus()
            renderList()
        })
        row.addView(link(getString(R.string.sc_btn_select_none)) {
            selected.clear()
            renderStatus()
            renderList()
        })
        row.addView(
            android.widget.Space(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
        )
        row.addView(text(getString(R.string.sc_selected_count, selected.size), 11f, colorHint))
        return row
    }

    private fun link(label: String, onClick: () -> Unit): TextView =
        text(label, 13f, colorTitle).apply {
            setPadding(0, dp(4), dp(18), dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 组头：勾选框（= 全组批量）+ 应用名 + 合计大小 + 组根绝对路径。
     *
     * 整行可点 = 折叠/展开；勾选框自己消费点击，不会连带折叠。
     * 组头勾选框不做三态：部分选中时显示「已选 x/y」，比半选图标好认。
     */
    private fun groupRow(g: StorageCleaner.Group): LinearLayout {
        val clearable = g.clearable
        val picked = clearable.count { it.path in selected }
        val collapsed = g.root in collapsedGroups
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(2))
        }

        val checkListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) clearable.forEach { selected.add(it.path) }
            else clearable.forEach { selected.remove(it.path) }
            renderStatus()
            renderList()
        }
        val cb = CheckBox(this).apply {
            isChecked = clearable.isNotEmpty() && picked == clearable.size
            isEnabled = !busy && clearable.isNotEmpty()
            setOnCheckedChangeListener(checkListener)
        }
        row.addView(cb)

        // 折叠热区只包「名称 + 大小 + 路径」，**不含勾选框**：
        // 整行可点时点勾选框会连带折叠（2026-11-13 实测），分开放就没有歧义。
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            setOnClickListener {
                if (!collapsedGroups.remove(g.root)) collapsedGroups.add(g.root)
                renderList()
            }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(
            text(if (collapsed) "▸" else "▾", 13f, colorHint)
                .apply { setPadding(0, 0, dp(6), 0) }
        )
        head.addView(
            text(g.label, 14f, colorTitle, bold = true)
                .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        )
        head.addView(text(StorageCleaner.humanSize(g.totalSize), 13f, colorTitle))
        body.addView(head)

        val meta = text(groupMeta(g, picked), 11f, colorHint)
            .apply { setPadding(0, dp(2), 0, 0) }
        body.addView(meta)
        row.addView(body)

        groupUis.add(GroupUi(g, cb, meta, checkListener))
        return row
    }

    /**
     * 一条结果行。目录会额外带「展开」与下级文件（展开后每个文件可单独勾选）。
     *
     * 返回纵向容器：主行 + （目录已展开时的）子行。子行不缩进到看不清，只再缩一级。
     */
    private fun entryRow(g: StorageCleaner.Group, e: StorageCleaner.Entry): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 缩进一级：让"属于哪个组"在视觉上成立，不必靠猜
            setPadding(dp(28), dp(6), 0, dp(6))
        }
        val cb = CheckBox(this).apply {
            isChecked = e.path in selected
            // 空壳不参与：它已经是 0 字节，再截一次不会多出任何空间
            isEnabled = !e.isShell && !busy
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(e.path) else selected.remove(e.path)
                renderStatus()
            }
        }
        row.addView(cb)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 第一行：文件名 + 右侧大小；第二行：相对组根的路径（组头已给出组根绝对路径，
        // 两段拼起来就是完整路径，不必每行重复 60 个字符的前缀）
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            text(e.name, 13f, if (e.isShell) colorBody else colorTitle)
                .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        )
        head.addView(
            text(
                if (e.isShell) getString(R.string.sc_shell_item) else StorageCleaner.humanSize(e.size),
                12f,
                colorHint
            )
        )
        col.addView(head)

        val sub = buildString {
            append(g.relativePath(e))
            if (e.isDir) {
                append(" · ")
                append(getString(R.string.sc_dir_item, e.childCount))
            }
            if (e.isAppDataDir) {
                append(" · ")
                append(getString(R.string.sc_dir_warn))
            }
        }
        col.addView(
            // files 目录里可能放存档/登录态，用警示色提醒一句，不改变行为
            text(sub, 11f, if (e.isAppDataDir) colorWarn else colorHint)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        row.addView(col)

        if (e.isDir) {
            val open = e.path in expandedDirs
            row.addView(
                link(if (open) getString(R.string.sc_dir_collapse) else getString(R.string.sc_dir_expand)) {
                    toggleDir(e)
                }.apply { setPadding(dp(6), 0, 0, 0) }
            )
        }
        box.addView(row)

        if (e.isDir && e.path in expandedDirs) {
            val kids = dirChildren[e.path]
            when {
                e.path in loadingDirs -> box.addView(
                    text(getString(R.string.sc_dir_loading), 11f, colorHint)
                        .apply { setPadding(dp(56), 0, 0, dp(6)) }
                )
                kids == null || kids.isEmpty() -> box.addView(
                    text(getString(R.string.sc_dir_empty), 11f, colorHint)
                        .apply { setPadding(dp(56), 0, 0, dp(6)) }
                )
                else -> kids.forEach { child -> box.addView(childRow(e, child)) }
            }
        }
        return box
    }

    /** 目录展开后的一个下级文件行：可单独勾选，路径相对所属目录。 */
    private fun childRow(parent: StorageCleaner.Entry, child: StorageCleaner.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(56), dp(4), 0, dp(4))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            CheckBox(this).apply {
                isChecked = child.path in selected
                isEnabled = !child.isShell && !busy
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(child.path) else selected.remove(child.path)
                    renderStatus()
                }
            }
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            text(child.name, 12f, colorBody)
                .apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        )
        head.addView(text(StorageCleaner.humanSize(child.size), 11f, colorHint))
        col.addView(head)
        col.addView(
            text(
                child.path.removePrefix(parent.path).trimStart('/'),
                10f,
                colorHint
            ).apply { setPadding(0, dp(1), 0, 0) }
        )
        row.addView(col)
        return row
    }

    /**
     * 展开 / 收起一个目录。首次展开时才去 shell 侧列目录内容（按需加载，不拖慢全盘扫描）。
     */
    private fun toggleDir(e: StorageCleaner.Entry) {
        if (expandedDirs.remove(e.path)) {
            // 收起时把下级已勾选的一并取消：不然"看不见却被清掉"是最糟的体验
            dirChildren[e.path]?.forEach { selected.remove(it.path) }
            renderStatus()
            renderList()
            return
        }
        expandedDirs.add(e.path)
        renderList()
        if (dirChildren[e.path] != null || e.path in loadingDirs) return

        val svc = ShizukuUserService.service()
        if (svc == null) {
            log("展开目录：跳过，通道不可用")
            toast(getString(R.string.sc_toast_no_channel))
            return
        }
        loadingDirs.add(e.path)
        renderList()
        ShizukuShell.submit {
            val raw = runCatching { svc.scan(e.path, DIR_EXPAND_DEPTH, 0L, false) }.getOrNull()
            val kids = StorageCleaner.parse(raw)
            log("展开目录：${e.path} 下级 ${kids.size} 项")
            runOnUiThread {
                loadingDirs.remove(e.path)
                dirChildren[e.path] = kids
                renderList()
            }
        }
    }

    // ---------------- 执行 ----------------

    /**
     * 扫描。[m] 由三个按钮各自传入；截断后的自动重扫沿用当前模式。
     */
    private fun startScan(m: Mode = mode, fromTruncate: Boolean = false) {
        val svc = ShizukuUserService.service()
        if (svc == null) {
            log("扫描：跳过，通道不可用 state=${ShizukuUserService.state()}")
            renderChannel(ShizukuUserService.state())
            toast(getString(R.string.sc_toast_no_channel))
            return
        }
        if (busy) {
            log("扫描：跳过，上一轮还没结束")
            toast(getString(R.string.sc_toast_busy))
            return
        }
        val manual = prefs.cleanManualPath
        if (m == Mode.MANUAL && manual.isBlank()) {
            toast(getString(R.string.sc_toast_no_path))
            return
        }
        mode = m
        log("扫描：开始，方式=${m.name} 阈值=${prefs.cleanMinSizeMb}MB 目录=${manual.ifBlank { "-" }}")
        busy = true
        renderScope()
        renderList()
        // 手动重扫才清掉上一次的截断明细；截断后的自动重扫要保留它
        if (!fromTruncate) {
            lastDetail = ""
            noteView.text = ""
        }

        val minBytes = prefs.cleanMinSizeMb * 1024L * 1024L
        noteView.text = when (m) {
            Mode.LARGE -> getString(R.string.sc_scanning, prefs.cleanMinSizeMb)
            Mode.CACHE -> getString(R.string.sc_scanning_cache, prefs.cleanMinSizeMb)
            Mode.MANUAL -> getString(R.string.sc_scanning_dir)
        }
        noteView.setTextColor(colorHint)
        ShizukuShell.submit {
            val raw = runCatching {
                when (m) {
                    // 大文件：全盘、只列文件（cache 目录照常递归，里面的大文件也单列）
                    Mode.LARGE -> svc.scanLarge(minBytes, false)
                    // 缓存目录：全盘、只列目录合计
                    Mode.CACHE -> svc.scanLarge(minBytes, true)
                    // 指定目录：不过滤大小，列出该目录下所有文件（条数仍受服务端上限约束）
                    Mode.MANUAL -> svc.scan(manual, MAX_DEPTH, 0L, false)
                }
            }.getOrNull()

            // 验收 2 的证据：同一目录用应用自身 uid 去列，Android 11+ 必然是 null/空。
            // 这一行进 watch.log，用来证明"扫描确实走了 shell 进程"，而不是应用自己在读。
            val probeDir = if (manual.isNotBlank()) {
                manual
            } else {
                raw?.firstOrNull()?.split('\t')?.getOrNull(2)?.substringBeforeLast('/')
            }
            val appSide = probeDir?.let {
                runCatching { File(it).listFiles()?.size }.getOrNull()
            }
            log("扫描：方式=${m.name} 服务侧=${raw?.size ?: -1} 应用侧(listFiles)=${appSide ?: "null"}")

            val parsed = StorageCleaner.parse(raw)
            runOnUiThread {
                busy = false
                entries = parsed
                // 默认只勾白名单命中、且还有内容的项
                selected.clear()
                selected.addAll(StorageCleaner.defaultSelection(parsed))
                noteView.text = when {
                    raw == null -> getString(R.string.sc_toast_no_channel)
                    parsed.isEmpty() -> when (m) {
                        Mode.MANUAL -> getString(R.string.sc_toast_scan_empty_dir)
                        Mode.CACHE -> getString(R.string.sc_toast_scan_empty_cache, prefs.cleanMinSizeMb)
                        Mode.LARGE -> getString(R.string.sc_toast_scan_empty, prefs.cleanMinSizeMb)
                    }
                    else -> getString(R.string.sc_toast_scan_done, parsed.size)
                }
                // 截断后的自动重扫：把明细接在扫描结果前面，别让「扫描完成 N 项」盖掉它
                if (fromTruncate && lastDetail.isNotBlank()) {
                    noteView.text = "$lastDetail\n${noteView.text}"
                }
                renderStatus()
                renderList()
            }
        }
    }

    private fun startTruncate() {
        val svc = ShizukuUserService.service()
        if (svc == null) {
            toast(getString(R.string.sc_toast_no_channel))
            return
        }
        // 勾选项既可能在主列表，也可能是某个已展开目录里的下级文件；
        // 目录与其下级同时被勾选时按路径前缀去重（目录优先），否则同一批内容会被清两次。
        val candidates = LinkedHashMap<String, StorageCleaner.Entry>()
        dirChildren.values.forEach { list -> list.forEach { candidates[it.path] = it } }
        entries.forEach { candidates[it.path] = it }
        val picked = candidates.values.filter { it.path in selected && !it.isShell }
        val pickedDirs = picked.filter { it.isDir }
        val pickedFiles = picked.filter { !it.isDir }
            .filter { f -> pickedDirs.none { d -> f.path.startsWith(d.path + "/") } }
        val targets = pickedDirs + pickedFiles
        if (targets.isEmpty()) {
            log("清理：跳过，没有可勾选的非空壳条目（已选 ${selected.size}）")
            toast(getString(R.string.sc_toast_nothing_selected))
            return
        }
        if (busy) {
            log("清理：跳过，上一轮还没结束")
            toast(getString(R.string.sc_toast_busy))
            return
        }
        log("清理：开始，${targets.size} 项")
        busy = true
        renderList()

        ShizukuShell.submit {
            // 先查目标应用是否还在跑。正在写的文件被抹零，等于给目标喂半截数据，
            // 所以整体拦下、让用户自己退出应用，而不是逐项跳过或硬截。
            val alive = targets.map { it.pkg }
                .filter { it.isNotEmpty() }
                .distinct()
                .firstOrNull { runCatching { svc.isProcessAlive(it) }.getOrDefault(false) }
            if (alive != null) {
                log("清理：拦下，$alive 正在运行")
                runOnUiThread {
                    busy = false
                    noteView.text = getString(R.string.sc_result_blocked, alive)
                    noteView.setTextColor(colorWarn)
                    renderList()
                }
                return@submit
            }

            var freed = 0L
            var removed = 0
            var truncated = 0
            var dirs = 0
            var dirFail = 0
            var protected = 0
            var gone = 0
            var other = 0
            targets.forEach { e ->
                // 目录条目：清空内容、保留空目录（回收空间一样，避免应用不重建目录而报错）
                if (e.isDir) {
                    val r = runCatching { svc.clearDir(e.path) }
                        .getOrDefault(TruncateService.CODE_OTHER)
                    if (r >= 0L) {
                        freed += e.size
                        dirs++
                    } else {
                        dirFail++
                    }
                    return@forEach
                }
                val r = runCatching { svc.clear(e.path) }
                    .getOrDefault(TruncateService.CODE_OTHER)
                when (r) {
                    // 目录组位含 w：unlink 成功，条目真的没了
                    TruncateService.CODE_REMOVED -> {
                        freed += e.size
                        removed++
                    }
                    // 目录组位只读：退一步把内容抹零，条目保留为空壳
                    TruncateService.CODE_TRUNCATED -> {
                        freed += e.size
                        truncated++
                    }
                    TruncateService.CODE_EACCES -> protected++
                    TruncateService.CODE_ENOENT -> gone++
                    else -> other++
                }
            }
            log(
                "清理：移除 $removed；截断 $truncated；清空目录 $dirs（失败 $dirFail）；" +
                    "释放 $freed 字节；受保护 $protected；已消失 $gone；其它 $other"
            )

            val summary = StorageCleaner.summary(freed, removed, truncated, dirs)
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
            prefs.cleanLastFreed = freed
            prefs.cleanLastEvent = "$stamp $summary"

            val detail = buildString {
                append(summary)
                if (dirFail > 0) append("\n$dirFail 个目录：${getString(R.string.sc_err_dir_partial)}")
                if (protected > 0) append("\n$protected 个：${getString(R.string.sc_err_protected)}")
                if (gone > 0) append("\n$gone 个：${getString(R.string.sc_err_gone)}")
                if (other > 0) append("\n$other 个：${getString(R.string.sc_err_other)}")
            }

            runOnUiThread {
                busy = false
                lastDetail = detail
                noteView.text = detail
                noteView.setTextColor(if (protected + other + dirFail > 0) colorWarn else colorOk)
                renderStatus()
                // 截断后按新状态重扫一次：列表里的条目应变成 0 字节空壳
                startScan(fromTruncate = true)
            }
        }
    }

    /** 应用自记日志，与无障碍服务共用 `files/watch.log`（`bash tools/log.sh tail` 可读）；上限见 [AppLog]。 */
    private fun log(msg: String) {
        AppLog.watch(this, msg)
    }

    private companion object {
        /** 指定目录模式的遍历深度上限：够到很深的缓存子目录，又不至于全盘乱走。 */
        const val MAX_DEPTH = 8

        /** 展开某个目录时向下列的深度：缓存目录通常 ≤4 层。 */
        const val DIR_EXPAND_DEPTH = 8
    }
}