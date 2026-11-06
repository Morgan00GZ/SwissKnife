package com.swissknife.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell

/**
 * 「强制滑动」功能页：这个功能自己的开关、状态、说明与白名单。
 * 与其他功能互不影响，以后新增功能时照这个结构复制即可。
 */
class ForceScrollActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusValue: TextView
    private lateinit var countValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(wrap(buildUi()))
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        root.addView(title(getString(R.string.feature_force_scroll)))
        root.addView(
            text(getString(R.string.fs_desc), 13f, colorBody)
                .apply { setPadding(0, dp(10), 0, 0) }
        )

        // ---- 状态 ----
        root.addView(sectionTitle(getString(R.string.fs_status)))
        root.addView(card {
            statusValue = row(it, getString(R.string.fs_status))
            countValue = row(it, getString(R.string.fs_count))
        })

        // ---- 触发条件 ----
        root.addView(sectionTitle(getString(R.string.fs_rule_title)))
        val rule = buildString {
            appendLine(getString(R.string.fs_rule_1))
            appendLine(getString(R.string.fs_rule_2))
            appendLine(getString(R.string.fs_rule_3))
            appendLine(getString(R.string.fs_rule_4))
            appendLine(getString(R.string.fs_rule_5))
            appendLine()
            append(getString(R.string.fs_rule_6))
        }
        root.addView(
            text(rule, 13f, colorBody)
                .apply { setPadding(dp(2), 0, 0, 0) }
        )

        return root
    }

    private fun refresh() {
        val enabled = prefs.forceScrollEnabled
        val accessibility = ForegroundWatcherService.isRunning()
        val shizuku = ShizukuShell.hasPermission()

        val (textRes, ok) = when {
            !enabled -> R.string.fs_paused to false
            !accessibility || !shizuku -> R.string.fs_waiting to false
            else -> R.string.fs_running to true
        }
        statusValue.text = getString(textRes)
        statusValue.setTextColor(if (ok) colorOk else colorWarn)

        countValue.text = getString(R.string.fs_count_unit, prefs.todayCount)
        countValue.setTextColor(colorTitle)

    }

}
