package com.swissknife.core

import android.content.Context
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.keepalive.KeepAliveService

/**
 * 功能开关的"外部入口"共用逻辑。
 *
 * 目前有两个入口会从应用界面之外切换「强制滑动」：
 * 1. 桌面快捷方式 → [com.swissknife.ShortcutToggleActivity]（透明 Activity）
 * 2. 常驻通知上的按钮 → [com.swissknife.ToggleReceiver]（纯后台广播）
 *
 * 两处的动作完全一样，所以抽到这里，避免第二次改动只改一处。
 * **为什么通知不用那个透明 Activity**：它会短暂启动界面，把用户正在看的视频
 * 暂停一下——而「强制滑动」恰恰就是在看视频/广告时用的，闪一下比没按钮还糟。
 */
object FeatureToggle {

    /**
     * 切一次「强制滑动」，返回切换后的状态。
     *
     * 调用方负责给用户反馈（Activity 弹 toast，Receiver 也弹）——这里只管状态与联动：
     * 球的显隐、常驻服务的起停，都靠 [ForegroundWatcherService.refresh] 与
     * [KeepAliveService.sync] 这条既有链路收敛，不另写一份。
     */
    fun toggleForceScroll(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val next = !prefs.forceScrollEnabled
        prefs.forceScrollEnabled = next
        if (next) {
            // 球上的文字跟着功能走，这是既有约定（Prefs.ballLabel 由功能自己写）
            prefs.ballLabel = app.getString(R.string.feature_force_scroll)
        }
        ForegroundWatcherService.refresh()
        // 从应用外部改开关时，无障碍服务可能没连接（收不到 prefs 回调），
        // 必须显式同步一次常驻服务的起停。
        KeepAliveService.sync(app)
        return next
    }
}
