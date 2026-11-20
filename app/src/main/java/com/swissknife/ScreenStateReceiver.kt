package com.swissknife

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swissknife.esr.EyeBreak

/**
 * 屏幕亮灭的**静态**接收者（2026-11-20 新增）。
 *
 * ## 为什么需要它
 *
 * 原来只有动态注册（`EyeBreak.registerScreenReceiver`），而动态广播在进程被冻结时**收不到**——
 * 熄屏进 Doze 恰好就是进程被冻结的时候。丢掉 `SCREEN_OFF` 的后果不是"少记一次"，而是：
 *
 * - `SessionTimer.offAt` 一直是 0
 * - 亮屏时算出 `offDuration = 0` ⇒ **「熄屏超过 20 秒就清零」判定失效**
 * - 而且 `settle()` 会把熄屏那段时间当成亮屏累计进去（此时 phase 还是 SCREEN）
 *
 * 用户看到的现象：熄过屏，但累计没归零，反而更早弹浮窗。
 *
 * `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 不在 Android 8 的隐式广播限制名单里，
 * 可以静态注册；进程被杀后由系统拉起投递，被冻结时也能唤醒进程。
 *
 * 幂等由 [EyeBreak.applyScreenState] 的守卫保证：动态与静态会重复收到同一事件，
 * 第二次因"状态没变"被忽略。
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> EyeBreak.onScreenBroadcast(true)
            Intent.ACTION_SCREEN_OFF -> EyeBreak.onScreenBroadcast(false)
        }
    }
}