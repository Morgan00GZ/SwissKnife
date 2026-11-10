package com.swissknife.esr

import java.time.LocalTime

/**
 * 夜间静默时段：这段时间**不做注视判定**，亮屏就照常计时。
 *
 * 为什么需要：晚上室内光照低，小米 AON 经常判不出"在看"（实测连续两轮返回没在看），
 * 于是计时被反复暂停，20 分钟阈值在夜里几乎走不完。用户 2026-11-09 定的规则：
 * **22:00–08:00 只要亮屏就计时**，这段时间「没检测到注视就暂停」不生效。
 *
 * 顺带的好处：夜间整条注视采样链不启动，省掉每 30 秒两个 `sh` 进程。
 *
 * [isQuiet] 收的是 `hour` 而不是自己去取时间，纯函数、可 JVM 单测；
 * 取当前时间只在 [now] 这一处发生。
 */
object QuietHours {

    /** 静默时段起点（含）：22 点 */
    const val START_HOUR = 22

    /** 静默时段终点（不含）：次日 8 点 */
    const val END_HOUR = 8

    /** 纯判定：`[START_HOUR, 24) ∪ [0, END_HOUR)`。 */
    fun isQuiet(hour: Int): Boolean = hour >= START_HOUR || hour < END_HOUR

    /** 现在是否处于静默时段。用设备本地时间——作息是按用户的墙上时钟定义的。 */
    fun now(): Boolean = isQuiet(LocalTime.now().hour)
}
