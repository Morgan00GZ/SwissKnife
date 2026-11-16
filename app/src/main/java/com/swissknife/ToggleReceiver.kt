package com.swissknife

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.swissknife.core.FeatureToggle

/**
 * 常驻通知上「开启/关闭」按钮的目标（2026-11-10 用户要求）。
 *
 * 为什么是广播而不是 Activity：点通知按钮时用户很可能正在看视频/广告，
 * 启动一个（哪怕透明的）Activity 会把下面的界面暂停一下。广播在后台完成，
 * 界面完全不受影响。
 *
 * 只做一件事：切开关 + 弹个 toast。球的显隐与常驻服务的起停由
 * [FeatureToggle] 里那条既有链路负责。
 *
 * 2026-11-16：通知上有两个按钮（强制滑动 / 悬浮时间），都发到这个 Receiver，
 * 用 `feature` extra 区分。不带 extra 时按「强制滑动」处理（兼容老版本通知的按钮）。
 */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val which = FeatureToggle.Which.from(
            intent?.getStringExtra(ShortcutToggleActivity.EXTRA_FEATURE)
        )

        val on = if (which == FeatureToggle.Which.CLOCK_BALL) {
            FeatureToggle.toggleClockBall(context)
        } else {
            FeatureToggle.toggleForceScroll(context)
        }

        val textRes = when (which) {
            FeatureToggle.Which.CLOCK_BALL ->
                if (on) R.string.cb_toast_on else R.string.cb_toast_off
            else ->
                if (on) R.string.sc_toast_on else R.string.sc_toast_off
        }
        Toast.makeText(context, context.getString(textRes), Toast.LENGTH_SHORT).show()
    }
}