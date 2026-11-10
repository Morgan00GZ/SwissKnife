package com.swissknife

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.swissknife.core.FeatureToggle

/**
 * 桌面快捷方式入口：一键开关功能。
 *
 * 需求背景：用户希望长按桌面图标就能开关功能，不必先进应用。
 * 做法是把这个 Activity 设成透明无界面主题，切换开关后立刻 finish，
 * 视觉上就是"点一下快捷方式 → 弹个 toast → 什么都没发生"。
 *
 * 2026-11-10：开关动作抽到 [FeatureToggle]，与常驻通知上的按钮共用一份实现
 * （那个入口走广播，不启动界面）。
 */
class ShortcutToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val on = FeatureToggle.toggleForceScroll(this)
        Toast.makeText(
            this,
            getString(if (on) R.string.sc_toast_on else R.string.sc_toast_off),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
