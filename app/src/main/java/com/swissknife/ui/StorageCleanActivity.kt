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

    private var entries: List<StorageCleaner.Entry> = emptyList()
    private val selected = linkedSetOf<String>()

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
        renderStatus()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页回来时这里会再跑一次：ensureBound 幂等，已连上直接回 READY
        ShizukuUserService.ensureBound(this) { renderChannel(it) }
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

        // 调参槽最后一个：这里改的是扫描范围（留空 = 白名单模式）
        fp.input(
            hint = getString(R.string.sc_manual_hint),
            initial = prefs.cleanManualPath,
            saveLabel = getString(R.string.sc_manual_save),
            numberOnly = false
        ) { raw ->
            prefs.cleanManualPath = raw.trim()
            selected.clear()
            entries = emptyList()
            renderScope()
            renderStatus()
            renderList()
            startScan()
        }

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

    private fun renderScope() {
        val manual = prefs.cleanManualPath
        setStatus(
            scopeValue,
            if (manual.isBlank()) {
                getString(R.string.sc_scope_whitelist)
            } else {
                getString(R.string.sc_scope_manual)
            },
            Tone.NEUTRAL
        )
        scopeNote.text = if (manual.isBlank()) {
            getString(R.string.sc_scope_whitelist_note)
        } else {
            getString(R.string.sc_scope_manual_note, manual)
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
    }

    private fun renderList() {
        listHost.removeAllViews()

        // 两个操作按钮放在列表**上方**：条目多时（实测 26 项、根目录 300 项）按钮会被推到
        // 屏幕外，得先滚过整个列表才点得到「清空所选内容」——2026-11-12 实测踩到。
        listHost.addView(
            actionButton(getString(R.string.sc_btn_scan)) { startScan() }
                .apply { isEnabled = !busy }
        )
        // 勾选状态变化只改这一个按钮的可点性，不重建整个列表——
        // 重建会把正在被点的 CheckBox 从视图树里摘掉，手感上像"点了没反应"
        truncateBtn = actionButton(getString(R.string.sc_btn_truncate)) { startTruncate() }
            .apply { isEnabled = !busy && selected.isNotEmpty() }
        listHost.addView(truncateBtn)

        if (entries.isEmpty()) {
            listHost.addView(
                text(getString(R.string.sc_list_empty), 13f, colorBody)
                    .apply { setPadding(0, dp(8), 0, dp(8)) }
            )
        } else {
            entries.forEach { listHost.addView(entryRow(it)) }
        }
    }

    private fun entryRow(e: StorageCleaner.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
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
        col.addView(text(e.name, 13f, if (e.isShell) colorBody else colorTitle))
        val meta = if (e.isShell) {
            getString(R.string.sc_shell_item)
        } else {
            StorageCleaner.humanSize(e.size)
        }
        col.addView(
            text("${e.pkg} · $meta", 11f, colorHint)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        row.addView(col)
        return row
    }

    // ---------------- 执行 ----------------

    private fun startScan(fromTruncate: Boolean = false) {
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
        log("扫描：开始，范围=${if (prefs.cleanManualPath.isBlank()) "whitelist" else prefs.cleanManualPath}")
        busy = true
        renderList()
        // 手动重扫才清掉上一次的截断明细；截断后的自动重扫要保留它
        if (!fromTruncate) {
            lastDetail = ""
            noteView.text = ""
        }

        val manual = prefs.cleanManualPath
        ShizukuShell.submit {
            val raw = runCatching {
                if (manual.isBlank()) svc.scanUe4() else svc.scan(manual, MAX_DEPTH, 0L)
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
            log("扫描：范围=${if (manual.isBlank()) "whitelist" else manual} 服务侧=${raw?.size ?: -1} 应用侧(listFiles)=${appSide ?: "null"}")

            val parsed = StorageCleaner.parse(raw)
            runOnUiThread {
                busy = false
                entries = parsed
                // 默认只勾白名单命中、且还有内容的项
                selected.clear()
                selected.addAll(StorageCleaner.defaultSelection(parsed))
                noteView.text = when {
                    raw == null -> getString(R.string.sc_toast_no_channel)
                    parsed.isEmpty() -> getString(R.string.sc_toast_scan_empty)
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
        val targets = entries.filter { it.path in selected && !it.isShell }
        if (targets.isEmpty()) {
            log("截断：跳过，没有可勾选的非空壳条目（已选 ${selected.size}）")
            toast(getString(R.string.sc_toast_nothing_selected))
            return
        }
        if (busy) {
            log("截断：跳过，上一轮还没结束")
            toast(getString(R.string.sc_toast_busy))
            return
        }
        log("截断：开始，${targets.size} 项")
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
                log("截断：拦下，$alive 正在运行")
                runOnUiThread {
                    busy = false
                    noteView.text = getString(R.string.sc_result_blocked, alive)
                    noteView.setTextColor(colorWarn)
                    renderList()
                }
                return@submit
            }

            var freed = 0L
            var shells = 0
            var protected = 0
            var gone = 0
            var other = 0
            targets.forEach { e ->
                val r = runCatching { svc.truncate(e.path) }
                    .getOrDefault(TruncateService.CODE_OTHER)
                when {
                    r >= 0L -> {
                        freed += e.size
                        shells++
                    }
                    // EACCES：该文件组位不是 -rw-rw----，重试无用，如实计数
                    r == TruncateService.CODE_EACCES -> protected++
                    // ENOENT：路径已消失（用户或目标应用自己清了），重扫即可，不算错误
                    r == TruncateService.CODE_ENOENT -> gone++
                    else -> other++
                }
            }
            log("截断：成功 $shells 项，释放 $freed 字节；受保护 $protected；已消失 $gone；其它 $other")

            val summary = StorageCleaner.summary(freed, shells)
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
            prefs.cleanLastFreed = freed
            prefs.cleanLastEvent = "$stamp $summary"

            val detail = buildString {
                append(summary)
                if (protected > 0) append("\n$protected 个：${getString(R.string.sc_err_protected)}")
                if (gone > 0) append("\n$gone 个：${getString(R.string.sc_err_gone)}")
                if (other > 0) append("\n$other 个：${getString(R.string.sc_err_other)}")
            }

            runOnUiThread {
                busy = false
                lastDetail = detail
                noteView.text = detail
                noteView.setTextColor(if (protected + other > 0) colorWarn else colorOk)
                renderStatus()
                // 截断后按新状态重扫一次：列表里的条目应变成 0 字节空壳
                startScan(fromTruncate = true)
            }
        }
    }

    /** 应用自记日志，与无障碍服务共用 files/watch.log（`bash tools/log.sh tail` 可读）。 */
    private fun log(msg: String) {
        try {
            val f = File(filesDir, "watch.log")
            if (f.length() > 120_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} $msg\n")
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** 手输路径模式的遍历深度上限：够到 UE4 的 Saved/<渠道>/，又不至于全盘乱走。 */
        const val MAX_DEPTH = 6
    }
}