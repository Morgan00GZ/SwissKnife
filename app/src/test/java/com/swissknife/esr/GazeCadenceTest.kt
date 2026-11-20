package com.swissknife.esr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注视采样节拍（30 秒快档 / 60 秒慢档）。
 *
 * 为什么不靠真机验：这条逻辑写反了不会报错、不会崩，只会"采样变慢"——
 * 唯一的现象是计时停得晚了一点，日志里几乎看不出来。而它直接决定每天起多少个
 * `sh` 进程（记在 shell 名下，应用自己的 `batterystats` 里看不见）。
 * 所以四个分支全部用测试锁死。
 */
class GazeCadenceTest {

    @Test
    fun `首次还没有结果时用快档`() {
        assertEquals(
            GazeCadence.FAST_MS,
            GazeCadence.interval(hasResult = false, notAttentiveStreak = 0, suspended = false)
        )
    }

    @Test
    fun `连续在看时降到 60 秒`() {
        assertEquals(
            GazeCadence.STEADY_MS,
            GazeCadence.interval(hasResult = true, notAttentiveStreak = 0, suspended = false)
        )
    }

    @Test
    fun `刚判过没在看立刻回快档`() {
        assertEquals(
            GazeCadence.FAST_MS,
            GazeCadence.interval(hasResult = true, notAttentiveStreak = 1, suspended = false)
        )
    }

    @Test
    fun `计时已暂停时用快档等恢复`() {
        assertEquals(
            GazeCadence.FAST_MS,
            GazeCadence.interval(hasResult = true, notAttentiveStreak = 2, suspended = true)
        )
    }

    @Test
    fun `慢档必须明显小于注视心跳超时`() {
        // 间隔接近 eyeGazeTimeoutMs（默认 3 分钟）时，SessionTimer 会把注视心跳判为过期、
        // 回退成亮屏计时。两轮慢档必须落在超时之内，留出一轮余量。
        assertTrue("STEADY_MS=${GazeCadence.STEADY_MS}", GazeCadence.STEADY_MS * 2 < 180_000L)
    }
}