package com.swissknife.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.widget.ScrollView
import android.widget.TextView
import com.swissknife.R
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.core.Tone
import com.swissknife.keepalive.KeepAliveService
import com.swissknife.net.Saver4G

/**
 * 「极致省电」功能页。
 *
 * 骨架来自 [BaseActivity.featurePage]，与「强制滑动」页完全同构：
 * 返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用。开关留在首页，这里不重复放开关，
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
        setContentView(buildUi())
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

    private fun buildUi(): ScrollView {
        val page = featurePage(
            getString(R.string.feature_saver_4g),
            getString(R.string.sv_desc)
        )

        switchValue = page.status(getString(R.string.sv_switch))
        powerValue = page.status(getString(R.string.sv_power))
        cellValue = page.status(getString(R.string.sv_cell))
        allowedValue = page.status(getString(R.string.sv_allowed))
        actualValue = page.status(getString(R.string.sv_actual))
        lastValue = page.note()
        // 权威原始值：读不到就留空，不要用"未知"占掉两行
        rawValue = page.raw()

        // 依赖：本功能只要 Shizuku；不需要无障碍，所以不列（列了会让人以为必须开）
        shizukuValue = page.dep(getString(R.string.label_shizuku)) { onShizukuClick() }
        keepValue = page.dep(getString(R.string.label_keepalive)) { onKeepAliveClick() }

        page.steps(
            listOf(
                getString(R.string.sv_rule_1),
                getString(R.string.sv_rule_2),
                getString(R.string.sv_rule_3),
                getString(R.string.sv_rule_4),
                getString(R.string.sv_rule_5),
                getString(R.string.sv_rule_6)
            )
        )

        return page.build()
    }

    private fun onShizukuClick() {
        when {
            !ShizukuShell.isAvailable() -> toast(getString(R.string.toast_shizuku_missing))
            ShizukuShell.hasPermission() -> toast(getString(R.string.status_ok))
            else -> ShizukuShell.requestPermission()
        }
    }

    private fun onKeepAliveClick() {
        when {
            KeepAliveService.isRunning() -> toast(getString(R.string.keep_running))
            !prefs.anyFeatureEnabled -> toast(getString(R.string.toast_need_feature_first))
            else -> {
                KeepAliveService.ensureRunning(this)
                toast(getString(R.string.keep_toast_started))
                refresh()
            }
        }
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

        setStatus(
            switchValue,
            getString(if (enabled) R.string.sv_switch_on else R.string.sv_switch_off),
            if (enabled) Tone.NEUTRAL else Tone.MUTED
        )
        setStatus(
            powerValue,
            getString(if (saverOn) R.string.sv_power_on else R.string.sv_power_off),
            if (saverOn) Tone.NEUTRAL else Tone.MUTED
        )
        setStatus(
            cellValue,
            getString(if (prefs.cell4gActive) R.string.sv_cell_active else R.string.sv_cell_inactive),
            if (prefs.cell4gActive) Tone.OK else Tone.MUTED
        )

        val last = prefs.cell4gLastEvent
        lastValue.text = getString(R.string.sv_last) + "：" +
                (if (last.isBlank()) getString(R.string.sv_no_record) else last)

        setStatus(allowedValue, getString(R.string.sv_reading), Tone.MUTED)
        setStatus(actualValue, getString(R.string.sv_reading), Tone.MUTED)

        applyAuth(shizukuValue, ShizukuShell.hasPermission())
        applyAuth(keepValue, KeepAliveService.isRunning(), R.string.keep_running, R.string.keep_stopped)

        // 异步取权威值。回调在 Shizuku 的串行线程上，必须切回主线程再动 View。
        Saver4G.queryNetInfo { info ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info == null) {
                    setStatus(allowedValue, getString(R.string.status_unknown), Tone.WARN)
                    setStatus(actualValue, getString(R.string.status_unknown), Tone.WARN)
                    rawValue.text = ""
                    return@runOnUiThread
                }
                setStatus(
                    allowedValue,
                    getString(
                        if (info.nrAllowed) R.string.sv_allowed_has_nr else R.string.sv_allowed_no_nr
                    ),
                    if (info.nrAllowed) Tone.NEUTRAL else Tone.OK
                )
                setStatus(actualValue, Saver4G.prettyRat(info.rat), Tone.NEUTRAL)
                rawValue.text = "卡槽 ${info.slot}（默认数据卡）· 系统原值：${info.allowed}"

                // 以系统侧掩码为准判定"是否已限 4G"：
                // 这条与上面的「允许制式」必然自洽，也不会因为内部 active 标记的时序而说谎。
                // （active 只是"我欠不欠一次还原"的内部记账，不该拿它当界面事实。）
                setStatus(
                    cellValue,
                    getString(
                        if (info.nrAllowed) R.string.sv_cell_inactive else R.string.sv_cell_active
                    ),
                    if (info.nrAllowed) Tone.MUTED else Tone.OK
                )

                // 写入是异步的，最近动作可能在本次刷新之后才更新，这里再取一次
                val latest = prefs.cell4gLastEvent
                lastValue.text = getString(R.string.sv_last) + "：" +
                        (if (latest.isBlank()) getString(R.string.sv_no_record) else latest)
            }
        }
    }
}
