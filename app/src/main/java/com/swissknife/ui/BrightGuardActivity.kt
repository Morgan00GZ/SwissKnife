package com.swissknife.ui

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import com.swissknife.R
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.core.Tone
import com.swissknife.esr.BrightGuard
import com.swissknife.keepalive.KeepAliveService

/**
 * 「最低亮度守护」功能页。
 *
 * 骨架来自 [BaseActivity.featurePage]，与另外两个功能页完全同构。
 * 开关在首页，这里只读状态 + 调参（下限）。
 *
 * 本页**不依赖无障碍服务**——这是它与「护眼休息」最重要的一条边界：
 * 只开这个功能时，用户不该被要求去开一个用不到的权限。
 */
class BrightGuardActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var switchValue: TextView
    private lateinit var currentValue: TextView
    private lateinit var minValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var stateValue: TextView
    private lateinit var channelValue: TextView
    private lateinit var writeSettingsValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var keepValue: TextView

    /** 亮度被系统或其他应用改动时，本页要跟着刷新（事件驱动，只在页面可见时注册）。 */
    private var observer: ContentObserver? = null

    /** BrightGuard 自己写入后回调，避免"页面显示的还是旧值"。 */
    private val guardListener: () -> Unit = {
        runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        BrightGuard.addListener(guardListener)
        if (observer == null) {
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    refresh()
                }
            }
            try {
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    o
                )
                // 自动亮度在部分机型上只写 float 键，不观察它就会漏掉整类亮度变化
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        contentResolver.registerContentObserver(
                            Settings.System.getUriFor("screen_brightness_float"), false, o
                        )
                    } catch (_: Throwable) {
                    }
                }
                observer = o
            } catch (_: Throwable) {
                observer = null
            }
        }
        refresh()
    }

    override fun onPause() {
        BrightGuard.removeListener(guardListener)
        observer?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Throwable) {
            }
        }
        observer = null
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val page = featurePage(
            getString(R.string.feature_bright_guard),
            getString(R.string.bg_desc)
        )

        switchValue = page.status(getString(R.string.bg_switch))
        currentValue = page.status(getString(R.string.bg_current))
        minValue = page.status(getString(R.string.bg_min))
        modeValue = page.status(getString(R.string.bg_mode))
        stateValue = page.status(getString(R.string.bg_state))
        channelValue = page.status(getString(R.string.bg_channel))
        page.note().text = getString(R.string.bg_footnote)

        // 依赖：WRITE_SETTINGS 与 Shizuku 是"或"的关系——两者有其一就能纠偏。
        // 所以不把任何一个写成硬门禁，只把当前实际走哪条通道显示出来（见「写入通道」）。
        writeSettingsValue = page.dep(getString(R.string.label_write_settings)) { onWriteSettingsClick() }
        shizukuValue = page.dep(getString(R.string.label_shizuku)) { onShizukuClick() }
        keepValue = page.dep(getString(R.string.label_keepalive)) { onKeepAliveClick() }

        page.steps(
            listOf(
                getString(R.string.bg_rule_1),
                getString(R.string.bg_rule_2),
                getString(R.string.bg_rule_3),
                getString(R.string.bg_rule_4),
                getString(R.string.bg_rule_5)
            )
        )

        // 自动亮度是"下限守不住"的唯一原因，所以给它一个一键入口，而不是让用户自己去翻设置
        page.action(getString(R.string.bg_action_manual)) {
            if (!BrightGuard.isAutoBrightness(this)) {
                toast(getString(R.string.bg_mode_manual))
            } else if (BrightGuard.setManualMode(this)) {
                toast(getString(R.string.bg_toast_manual_ok))
                refresh()
            } else {
                toast(getString(R.string.bg_toast_manual_fail))
            }
        }

        // 调参：照搬独立版的「控制值 + 保存」，空或 0 = 不启用。
        // 输入框初始值刻意不刷新（用户正在输入时不能被 observer 覆盖）。
        page.input(
            hint = getString(R.string.bg_title_min),
            initial = prefs.minBrightnessValue.takeIf { it > 0 }?.toString() ?: "",
            saveLabel = getString(R.string.bg_action_save)
        ) { raw ->
            val value = if (raw.isEmpty()) 0 else (raw.toIntOrNull() ?: -1)
            if (value < 0 || value > Prefs.MAX_BRIGHTNESS) {
                toast(getString(R.string.bg_toast_invalid))
            } else {
                prefs.minBrightnessValue = value
                // 保存后立即按新下限纠偏一次，不等下一次亮度变化
                BrightGuard.enforce(this)
                toast(
                    if (value == 0) {
                        getString(R.string.bg_toast_off)
                    } else {
                        getString(
                            R.string.bg_toast_saved,
                            value,
                            BrightGuard.estimateBarPercent(value)
                        )
                    }
                )
                refresh()
            }
        }

        return page.build()
    }

    private fun refresh() {
        if (!::prefs.isInitialized) return
        val enabled = prefs.brightGuardEnabled
        val min = prefs.minBrightnessValue
        val current = BrightGuard.readCurrentValue(this)
        val direct = BrightGuard.canWriteDirectly(this)
        val shizuku = ShizukuShell.hasPermission()

        setStatus(
            switchValue,
            getString(if (enabled) R.string.sv_switch_on else R.string.sv_switch_off),
            if (enabled) Tone.NEUTRAL else Tone.MUTED
        )

        setStatus(
            currentValue,
            when {
                current < 0 -> getString(R.string.bg_read_failed)
                min > 0 -> getString(
                    R.string.bg_fmt_with_min,
                    current,
                    BrightGuard.estimateBarPercent(current),
                    min
                )
                else -> getString(
                    R.string.bg_fmt_value,
                    current,
                    BrightGuard.estimateBarPercent(current)
                )
            },
            if (current < 0) Tone.WARN else Tone.NEUTRAL
        )

        setStatus(
            minValue,
            if (min > 0) {
                getString(R.string.bg_fmt_value, min, BrightGuard.estimateBarPercent(min))
            } else {
                getString(R.string.bg_not_set)
            },
            if (min > 0) Tone.NEUTRAL else Tone.MUTED
        )

        // 亮度模式：自动亮度下系统会按环境光持续改显示亮度，我们写回的值随时被覆盖，
        // 所以这不是"功能坏了"，而是模式决定了它守不住——必须在这一行说清楚。
        val auto = BrightGuard.isAutoBrightness(this)
        setStatus(
            modeValue,
            getString(if (auto) R.string.bg_mode_auto else R.string.bg_mode_manual),
            if (auto) Tone.WARN else Tone.NEUTRAL
        )

        // 生效判据：开关开 + 设了下限 + 至少有一条写入通道
        val effective = enabled && min > 0 && (direct || shizuku)
        if (effective && auto) {
            setStatus(stateValue, getString(R.string.bg_state_auto_blocked), Tone.WARN)
        } else {
            setStatus(
                stateValue,
                getString(if (effective) R.string.bg_state_on else R.string.bg_state_off),
                when {
                    effective -> Tone.OK
                    enabled && min > 0 -> Tone.WARN
                    else -> Tone.MUTED
                }
            )
        }

        setStatus(
            channelValue,
            when {
                direct -> getString(R.string.bg_channel_direct)
                shizuku -> getString(R.string.bg_channel_shizuku)
                else -> getString(R.string.bg_channel_none)
            },
            when {
                direct -> Tone.OK
                shizuku -> Tone.NEUTRAL
                else -> Tone.WARN
            }
        )

        applyAuth(writeSettingsValue, direct)
        applyAuth(shizukuValue, shizuku)
        applyAuth(keepValue, KeepAliveService.isRunning(), R.string.keep_running, R.string.keep_stopped)
    }

    private fun onWriteSettingsClick() {
        if (BrightGuard.canWriteDirectly(this)) {
            toast(getString(R.string.toast_write_granted))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            toast(getString(R.string.toast_write_failed))
        }
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
}
