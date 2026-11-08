package com.swissknife.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.core.Tone
import com.swissknife.esr.EyeBreak
import com.swissknife.esr.TimingPhase
import com.swissknife.keepalive.KeepAliveService

/**
 * 「护眼休息」功能页。
 *
 * 骨架来自 [BaseActivity.featurePage]，与另外两个功能页完全同构：
 * 返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用 / 调参。
 * 开关留在首页，这里不重复放开关——两处都能开就会出现"状态对不上"的经典问题。
 *
 * 计时进度是内存态（进程被杀从零开始，与独立 ESR 一致），所以「本次已用」只在服务活着时有值。
 */
class EyeBreakActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var switchValue: TextView
    private lateinit var usedValue: TextView
    private lateinit var remainValue: TextView
    private lateinit var phaseValue: TextView
    private lateinit var overlayValue: TextView
    private lateinit var noteValue: TextView
    private lateinit var accessibilityValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var phoneValue: TextView
    private lateinit var keepValue: TextView

    /** 仅在页面可见时每秒刷新一次（回调里再排下一次），离开立即停掉。 */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000L)
        }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val page = featurePage(
            getString(R.string.feature_eye_break),
            getString(R.string.eb_desc)
        )

        switchValue = page.status(getString(R.string.eb_switch))
        usedValue = page.status(getString(R.string.eb_used))
        remainValue = page.status(getString(R.string.eb_remaining))
        phaseValue = page.status(getString(R.string.eb_phase))
        overlayValue = page.status(getString(R.string.eb_overlay))
        noteValue = page.note()

        // 依赖：本功能**必须**有无障碍（浮窗与按键拦截都靠它）；
        // Shizuku 与电话状态是可选的增强项，缺了有降级路径，所以文案上要能看出来。
        accessibilityValue = page.dep(getString(R.string.label_accessibility)) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                toast(getString(R.string.status_unknown))
            }
        }
        shizukuValue = page.dep(getString(R.string.label_shizuku)) { onShizukuClick() }
        phoneValue = page.dep(getString(R.string.label_phone_state)) { requestPhonePermission() }
        keepValue = page.dep(getString(R.string.label_keepalive)) { onKeepAliveClick() }

        page.steps(
            listOf(
                getString(R.string.eb_rule_1),
                getString(R.string.eb_rule_2),
                getString(R.string.eb_rule_3),
                getString(R.string.eb_rule_4),
                getString(R.string.eb_rule_5),
                getString(R.string.eb_rule_6)
            )
        )
        page.footnote(getString(R.string.eb_footnote))

        page.action(getString(R.string.eb_action_test)) {
            if (!prefs.eyeBreakEnabled) {
                toast(getString(R.string.eb_toast_need_enable))
            } else if (!EyeBreak.isRunning()) {
                toast(getString(R.string.eb_toast_need_enable))
            } else if (EyeBreak.triggerNow()) {
                toast(getString(R.string.eb_toast_test_ok))
            } else {
                toast(getString(R.string.eb_toast_need_accessibility))
            }
            refresh()
        }
        page.action(getString(R.string.eb_action_reset)) {
            EyeBreak.resetCounter()
            toast(getString(R.string.eb_toast_reset))
            refresh()
        }
        // 提示音与震动是"结束时才发生"的事件，用户没法反复验证。
        // 给一个随叫随到的入口：换一种铃声模式点一次，就知道这条通道通不通。
        page.action(getString(R.string.eb_action_test_notify)) {
            com.swissknife.esr.BreakNotifier.notifyBreakEnd(this)
            toast(getString(R.string.eb_toast_test_notify))
        }

        // 调参：照搬独立版的「阈值（分钟）+ 保存」。
        // 保存后立刻让计时器跟随新阈值，不重置已经积累的进度。
        page.input(
            hint = getString(R.string.eb_title_threshold),
            initial = (prefs.eyeThresholdMs / 60_000L).toString(),
            saveLabel = getString(R.string.eb_action_save_threshold)
        ) { raw ->
            val minutes = raw.toLongOrNull() ?: 0L
            if (minutes <= 0L) {
                toast(getString(R.string.eb_toast_invalid_minutes))
            } else {
                prefs.eyeThresholdMs = minutes * 60_000L
                // onServiceTick 会把最新阈值对齐进计时器并重排到期表
                // （与 ESR 在 onStartCommand 里做的事一致）；不调它，改完阈值当场不生效。
                EyeBreak.onServiceTick()
                toast(getString(R.string.eb_toast_threshold_saved, minutes))
                refresh()
            }
        }

        return page.build()
    }

    private fun refresh() {
        if (!::prefs.isInitialized) return
        val enabled = prefs.eyeBreakEnabled
        setStatus(
            switchValue,
            getString(if (enabled) R.string.sv_switch_on else R.string.sv_switch_off),
            if (enabled) Tone.NEUTRAL else Tone.MUTED
        )

        val snap = EyeBreak.snapshot()
        if (snap == null) {
            setStatus(usedValue, getString(R.string.eb_phase_stopped), Tone.MUTED)
            setStatus(remainValue, "--", Tone.MUTED)
            setStatus(phaseValue, getString(R.string.eb_phase_stopped), Tone.MUTED)
        } else {
            setStatus(usedValue, formatDuration(snap.effectiveMs), Tone.NEUTRAL)
            setStatus(
                remainValue,
                getString(
                    R.string.eb_fmt_remaining,
                    formatDuration(snap.remainingMs),
                    prefs.eyeThresholdMs / 60_000L
                ),
                if (snap.remainingMs <= 0L) Tone.WARN else Tone.NEUTRAL
            )
            val (phaseText, phaseTone) = when {
                snap.gazeSuspended -> getString(R.string.eb_phase_no_gaze) to Tone.WARN
                snap.phase == TimingPhase.GAZE -> getString(R.string.eb_phase_gaze) to Tone.OK
                snap.phase == TimingPhase.SCREEN -> getString(R.string.eb_phase_screen) to Tone.NEUTRAL
                else -> getString(R.string.eb_phase_screen_off) to Tone.MUTED
            }
            setStatus(phaseValue, phaseText, phaseTone)
        }

        // 浮窗这一行的三种真相必须分开说：在显示 / 到点了却弹不出（无障碍掉了）/ 没在显示
        val showing = EyeBreak.isOverlayShowing()
        val blocked = EyeBreak.isBlockedByGuard()
        when {
            showing -> setStatus(overlayValue, getString(R.string.eb_overlay_on), Tone.OK)
            blocked -> setStatus(overlayValue, getString(R.string.eb_overlay_blocked), Tone.WARN)
            else -> setStatus(overlayValue, getString(R.string.eb_overlay_off), Tone.MUTED)
        }

        // 计数口径写清楚，省得用户拿"已用 3 分钟"去和系统的屏幕使用时间吵架
        noteValue.text = getString(
            R.string.eb_note,
            prefs.eyeThresholdMs / 60_000L,
            prefs.eyeOverlaySeconds,
            prefs.eyeResetOnScreenOffMs / 1000L
        )

        applyAuth(accessibilityValue, isAccessibilityEnabled())
        applyAuth(shizukuValue, ShizukuShell.hasPermission())
        applyAuth(phoneValue, hasPhonePermission())
        applyAuth(keepValue, KeepAliveService.isRunning(), R.string.keep_running, R.string.keep_stopped)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return if (min > 0L) {
            getString(R.string.eb_fmt_duration_min_sec, min, sec)
        } else {
            getString(R.string.eb_fmt_duration_sec, sec)
        }
    }

    /** 无障碍是否已启用：优先看服务实例，退化到读系统设置，避免服务僵尸时误报"未开启"。 */
    private fun isAccessibilityEnabled(): Boolean {
        if (ForegroundWatcherService.isRunning()) return true
        val expected = android.content.ComponentName(this, ForegroundWatcherService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(expected.flattenToString(), ignoreCase = true) }
    }

    private fun hasPhonePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestPhonePermission() {
        if (hasPhonePermission()) {
            toast(getString(R.string.status_ok))
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
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
