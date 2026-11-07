package com.swissknife.esr

import android.os.SystemClock

/**
 * 统一时间基准。全部使用 elapsedRealtime，不受用户改系统时间、时区切换影响。
 *
 * 从 EyeStrainRelief 原样移植（2026-11-07）。
 */
object Timebase {
    fun now(): Long = SystemClock.elapsedRealtime()
}
