package com.swissknife

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.Prefs
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
 *
 * ## 两个消费者（2026-11-20 扩展）
 *
 * 1. 护眼休息：算熄屏时长，决定要不要清零（上面那件事）。
 * 2. 悬浮时间：灭屏停掉每秒的秒链、亮屏重挂——球在灭屏时看不见，秒链却仍在跑。
 *
 * 所以 `onReceive` 里**先看开关**：两个功能都关时直接返回。否则功能全关、服务已停、
 * 进程被回收之后，每次亮灭屏都会白拉起一个进程（见方法内注释）。
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val on = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> true
            Intent.ACTION_SCREEN_OFF -> false
            else -> return
        }

        // **先看开关**（一次内存读，不碰系统服务）。这是静态接收者：系统在**任何**亮灭屏时
        // 都会投递它，包括本应用功能全关、常驻服务已 stopSelf、进程随后被回收之后。
        // 没有这道守卫时，那种状态下的每次亮灭屏都会把整个进程拉起来（Application 初始化
        // + Shizuku Provider），只为走一行 `ctx == null → return`，纯属白烧电（2026-11-20）。
        //
        // 只有护眼（要算熄屏时长）与悬浮时间（要停/启秒链）需要这个广播；
        // 强制滑动、亮度守护、极致省电都不依赖它。
        val prefs = Prefs(context)
        if (!prefs.eyeBreakEnabled && !prefs.clockBallEnabled) return

        // 两路互不依赖：进程可能活着但没有无障碍服务实例，此时时钟球那路是空操作
        EyeBreak.onScreenBroadcast(on)
        ForegroundWatcherService.onScreenBroadcast(on)
    }
}