package com.swissknife

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.Prefs
import com.swissknife.keepalive.KeepAliveService

/**
 * 桌面快捷方式入口：一键开关功能。
 *
 * 需求背景：用户希望长按桌面图标就能开关功能，不必先进应用。
 * 做法是把这个 Activity 设成透明无界面主题，切换开关后立刻 finish，
 * 视觉上就是"点一下快捷方式 → 弹个 toast → 什么都没发生"。
 */
class ShortcutToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val next = !prefs.forceScrollEnabled
        prefs.forceScrollEnabled = next
        if (next) {
            prefs.ballLabel = getString(R.string.feature_force_scroll)
        }
        ForegroundWatcherService.refresh()
        // 快捷方式是从应用外部改开关，无障碍服务可能没连接（收不到 pref 回调），
        // 这里必须显式同步一次常驻服务的起停。
        KeepAliveService.sync(this)
        Toast.makeText(
            this,
            getString(if (next) R.string.sc_toast_on else R.string.sc_toast_off),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
