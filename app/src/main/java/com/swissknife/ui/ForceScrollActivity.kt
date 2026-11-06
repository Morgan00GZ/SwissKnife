package com.swissknife.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.core.Tone
import com.swissknife.keepalive.KeepAliveService

/**
 * 「强制滑动」功能页。
 *
 * 骨架来自 [BaseActivity.featurePage]，与「极致省电」页完全同构：
 * 返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用。
 * 开关在首页，这里不重复放开关（两处都能开就会出现"状态对不上"的经典问题）。
 *
 * 本页**只读**：它显示功能在不在跑、缺什么、怎么用；改状态的动作都在首页或系统页完成。
 */
class ForceScrollActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusValue: TextView
    private lateinit var countValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var accessibilityValue: TextView
    private lateinit var keepAliveValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): ScrollView {
        val page = featurePage(
            getString(R.string.feature_force_scroll),
            getString(R.string.fs_desc)
        )

        statusValue = page.status(getString(R.string.fs_status))
        countValue = page.status(getString(R.string.fs_count))
        page.note().text = getString(R.string.fs_note_count)

        // 依赖区：本功能缺一样就不工作，所以三样全列，且都能从这一行直接点去修。
        // 以前这页只有一句"等待授权"，用户不知道到底缺哪一个。
        shizukuValue = page.dep(getString(R.string.label_shizuku)) { onShizukuClick() }
        accessibilityValue = page.dep(getString(R.string.label_accessibility)) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                toast(getString(R.string.status_unknown))
            }
        }
        keepAliveValue = page.dep(getString(R.string.label_keepalive)) { onKeepAliveClick() }

        page.steps(
            listOf(
                getString(R.string.fs_rule_1),
                getString(R.string.fs_rule_2),
                getString(R.string.fs_rule_3),
                getString(R.string.fs_rule_4),
                getString(R.string.fs_rule_5)
            )
        )
        page.footnote(getString(R.string.fs_rule_6))

        return page.build()
    }

    private fun refresh() {
        val enabled = prefs.forceScrollEnabled
        val shizuku = ShizukuShell.hasPermission()
        val accessibility = isAccessibilityEnabled()
        val keepAlive = KeepAliveService.isRunning()

        // 运行状态：三种情况分别用三种语义色——
        // 关闭是中性（用户自己关的，不是问题），缺授权才是警告。
        when {
            !enabled -> setStatus(statusValue, getString(R.string.fs_paused), Tone.NEUTRAL)
            !shizuku || !accessibility -> setStatus(statusValue, getString(R.string.fs_waiting), Tone.WARN)
            else -> setStatus(statusValue, getString(R.string.fs_running), Tone.OK)
        }

        setStatus(
            countValue,
            getString(R.string.fs_count_unit, prefs.todayCount),
            Tone.NEUTRAL
        )

        applyAuth(shizukuValue, shizuku)
        applyAuth(accessibilityValue, accessibility)
        applyAuth(
            keepAliveValue,
            keepAlive,
            R.string.keep_running,
            R.string.keep_stopped
        )
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
