package com.swissknife.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.widget.LinearLayout
import android.widget.TextView
import com.swissknife.R
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.keepalive.KeepAliveService
import com.swissknife.net.Saver4G

/**
 * 「极致省电」功能页：状态 + 依赖 + 用法。
 *
 * 与 [ForceScrollActivity] 同构（状态卡 → 说明），开关留在首页，这里不重复放开关，
 * 避免出现"两个地方都能开、状态对不上"的经典问题。
 *
 * 状态分两层，**必须都显示**：
 *   - 策略：`cmd phone get-allowed-network-types-for-users` 读到系统真的允许哪些制式
 *   - 事实：`dumpsys telephony.registry` 读到 modem 实际在用哪一代网络
 * 只看其中一个会骗人：策略改了但 modem 还没重注册时，策略已经是 4G 而事实还是 5G。
 */
class Saver4GActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var switchValue: TextView
    private lateinit var powerValue: TextView
    private lateinit var cellValue: TextView
    private lateinit var allowedValue: TextView
    private lateinit var actualValue: TextView
    private lateinit var lastValue: TextView
    private lateinit var rawValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var keepValue: TextView

    /** 省电模式在页面停留期间被改掉时，界面要跟着变（系统侧改，本应用收不到自己的开关通知）。 */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = refresh()
    }

    /**
     * 状态变化回调：写入完成、失败、还原都会触发。
     * 广播与本回调的先后顺序不确定，两者都刷新，才能避免"允许制式已限 4G、4G 是否生效却显示未生效"。
     */
    private val stateListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) refresh()
        }
    }

    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(wrap(buildUi()))
    }

    override fun onResume() {
        super.onResume()
        Saver4G.addListener(stateListener)
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(powerReceiver, filter)
                }
                receiverRegistered = true
            } catch (_: Throwable) {
            }
        }
        refresh()
    }

    override fun onPause() {
        Saver4G.removeListener(stateListener)
        if (receiverRegistered) {
            try {
                unregisterReceiver(powerReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi(): LinearLayout {
        val root = page()

        root.addView(
            text("‹ 返回", 14f, colorBody).apply {
                setPadding(0, 0, 0, dp(10))
                isClickable = true
                setOnClickListener { finish() }
            }
        )
        root.addView(title(getString(R.string.feature_saver_4g)))
        root.addView(
            text(getString(R.string.sv_desc), 13f, colorBody)
                .apply { setPadding(0, dp(10), 0, 0) }
        )

        // ---- 状态 ----
        root.addView(sectionTitle(getString(R.string.sv_status_title)))
        root.addView(card {
            switchValue = row(it, getString(R.string.sv_switch))
            powerValue = row(it, getString(R.string.sv_power))
            cellValue = row(it, getString(R.string.sv_cell))
            allowedValue = row(it, getString(R.string.sv_allowed))
            actualValue = row(it, getString(R.string.sv_actual))
        })
        lastValue = text("", 12f, colorBody)
            .apply { setPadding(dp(2), dp(10), 0, 0) }
        root.addView(lastValue)
        rawValue = text("", 11f, colorHint)
            .apply { setPadding(dp(2), dp(6), 0, 0) }
        root.addView(rawValue)

        // ---- 依赖 ----
        root.addView(sectionTitle(getString(R.string.sv_auth_title)))
        root.addView(card {
            shizukuValue = row(it, getString(R.string.label_shizuku))
            (shizukuValue.parent as? android.view.View)?.apply {
                isClickable = true
                setOnClickListener {
                    when {
                        !ShizukuShell.isAvailable() -> toast(getString(R.string.toast_shizuku_missing))
                        ShizukuShell.hasPermission() -> toast(getString(R.string.status_ok))
                        else -> ShizukuShell.requestPermission()
                    }
                }
            }
            keepValue = row(it, getString(R.string.label_keepalive))
            (keepValue.parent as? android.view.View)?.apply {
                isClickable = true
                setOnClickListener {
                    if (KeepAliveService.isRunning()) {
                        toast(getString(R.string.keep_running))
                    } else if (!prefs.anyFeatureEnabled) {
                        toast(getString(R.string.toast_need_feature_first))
                    } else {
                        KeepAliveService.ensureRunning(this@Saver4GActivity)
                        toast(getString(R.string.keep_toast_started))
                        refresh()
                    }
                }
            }
        })

        // ---- 用法 ----
        root.addView(sectionTitle(getString(R.string.sv_rule_title)))
        val rule = buildString {
            appendLine(getString(R.string.sv_rule_1))
            appendLine(getString(R.string.sv_rule_2))
            appendLine(getString(R.string.sv_rule_3))
            appendLine(getString(R.string.sv_rule_4))
            appendLine(getString(R.string.sv_rule_5))
            append(getString(R.string.sv_rule_6))
        }
        root.addView(
            text(rule, 13f, colorBody)
                .apply { setPadding(dp(2), 0, 0, 0) }
        )

        return root
    }

    /** 先刷本地能立刻拿到的值，再异步问系统要策略与事实。 */
    private fun refresh() {
        if (!::prefs.isInitialized) return
        val enabled = prefs.saver4gEnabled
        val saverOn = try {
            getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        } catch (_: Throwable) {
            false
        }

        switchValue.text = getString(if (enabled) R.string.sv_switch_on else R.string.sv_switch_off)
        switchValue.setTextColor(if (enabled) colorTitle else colorBody)

        powerValue.text = getString(if (saverOn) R.string.sv_power_on else R.string.sv_power_off)
        powerValue.setTextColor(if (saverOn) colorTitle else colorBody)

        cellValue.text = getString(
            if (prefs.cell4gActive) R.string.sv_cell_active else R.string.sv_cell_inactive
        )
        cellValue.setTextColor(if (prefs.cell4gActive) colorOk else colorBody)

        val last = prefs.cell4gLastEvent
        lastValue.text = getString(
            R.string.sv_last
        ) + "：" + (if (last.isBlank()) getString(R.string.sv_no_record) else last)

        allowedValue.text = getString(R.string.sv_reading)
        allowedValue.setTextColor(colorBody)
        actualValue.text = getString(R.string.sv_reading)
        actualValue.setTextColor(colorBody)

        shizukuValue.text = getString(
            if (ShizukuShell.hasPermission()) R.string.status_ok else R.string.status_go_enable
        )
        shizukuValue.setTextColor(
            if (ShizukuShell.hasPermission()) colorOk else colorWarn
        )

        keepValue.text = getString(
            if (KeepAliveService.isRunning()) R.string.keep_running else R.string.keep_stopped
        )
        keepValue.setTextColor(
            if (KeepAliveService.isRunning()) colorOk else colorWarn
        )

        // 异步取权威值。回调在 Shizuku 的串行线程上，必须切回主线程再动 View。
        Saver4G.queryNetInfo { info ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info == null) {
                    allowedValue.text = getString(R.string.status_unknown)
                    allowedValue.setTextColor(colorWarn)
                    actualValue.text = getString(R.string.status_unknown)
                    actualValue.setTextColor(colorWarn)
                    rawValue.text = ""
                    return@runOnUiThread
                }
                allowedValue.text = getString(
                    if (info.nrAllowed) R.string.sv_allowed_has_nr else R.string.sv_allowed_no_nr
                )
                allowedValue.setTextColor(if (info.nrAllowed) colorTitle else colorOk)
                actualValue.text = Saver4G.prettyRat(info.rat)
                actualValue.setTextColor(colorTitle)
                rawValue.text = "卡槽 ${info.slot}（默认数据卡）· 系统原值：${info.allowed}"

                // 以系统侧掩码为准判定"是否已限 4G"：
                // 这条与上面的「允许制式」必然自洽，也不会因为内部 active 标记的时序而说谎。
                // （active 只是"我欠不欠一次还原"的内部记账，不该拿它当界面事实。）
                cellValue.text = getString(
                    if (info.nrAllowed) R.string.sv_cell_inactive else R.string.sv_cell_active
                )
                cellValue.setTextColor(if (info.nrAllowed) colorBody else colorOk)

                // 写入是异步的，最近动作可能在本次刷新之后才更新，这里再取一次
                val last = prefs.cell4gLastEvent
                lastValue.text = getString(R.string.sv_last) + "：" +
                        (if (last.isBlank()) getString(R.string.sv_no_record) else last)
            }
        }
    }
}
