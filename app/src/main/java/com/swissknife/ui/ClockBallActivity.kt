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
import com.swissknife.core.Tone
import com.swissknife.keepalive.KeepAliveService

/**
 * 「悬浮时间」功能页。
 *
 * 骨架与「强制滑动」页完全同构：返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用。
 * 开关在首页，这里不重复放（两处都能开就会出现"状态对不上"）。
 *
 * 本页**没有调参**：时间格式是固定的 24 小时制 HH:mm:ss，字号与球高都跟强制滑动对齐，
 * 用户没有可调项。所以不放 `input` 槽，也不需要 `content` 槽。
 *
 * 依赖里**没有 Shizuku**：这个功能不注入点击、不执行任何 shell 命令，
 * 只靠无障碍服务加一个浮窗。把它列进去会让用户以为还要再授权一次。
 */
class ClockBallActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusValue: TextView
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
            getString(R.string.feature_clock_ball),
            getString(R.string.cb_desc)
        )

        statusValue = page.status(getString(R.string.cb_status))
        page.note().text = getString(R.string.cb_note_status)

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
                getString(R.string.cb_rule_1),
                getString(R.string.cb_rule_2),
                getString(R.string.cb_rule_3),
                getString(R.string.cb_rule_4)
            )
        )
        page.footnote(getString(R.string.cb_footnote))

        return page.build()
    }

    private fun refresh() {
        val enabled = prefs.clockBallEnabled
        val accessibility = isAccessibilityEnabled()
        val keepAlive = KeepAliveService.isRunning()

        // 关闭是中性（用户自己关的），缺授权才是警告——与强制滑动页同一套语义色
        when {
            !enabled -> setStatus(statusValue, getString(R.string.cb_paused), Tone.NEUTRAL)
            !accessibility -> setStatus(statusValue, getString(R.string.cb_waiting), Tone.WARN)
            else -> setStatus(statusValue, getString(R.string.cb_running), Tone.OK)
        }

        applyAuth(accessibilityValue, accessibility)
        applyAuth(
            keepAliveValue,
            keepAlive,
            R.string.keep_running,
            R.string.keep_stopped
        )
    }

    /** 无障碍是否已启用：优先看服务实例，退化到读系统设置（服务僵尸时不误报）。 */
    private fun isAccessibilityEnabled(): Boolean {
        if (ForegroundWatcherService.isRunning()) return true
        val expected = android.content.ComponentName(this, ForegroundWatcherService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(expected.flattenToString(), ignoreCase = true) }
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