package com.swissknife.esr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionTimer] 的纯 JVM 单测（2026-11-09）。
 *
 * ## 为什么可以不起模拟器、不连真机
 *
 * 本类每个入口都收一个显式的 `now: Long`，内部只做加减与比较，不碰任何 Android API。
 * **唯一例外是 `reset()`** —— 它调 `Timebase.now()` → `SystemClock.elapsedRealtime()`，
 * 在 JVM 上会抛 "not mocked"。所以这里刻意不测 `reset()`：为它引入可注入时钟会改动
 * 从 ESR 原样移植过来的代码，破坏"逐字节一致"，代价大于收益。
 *
 * ## 为什么时间基准从 [base] 开始，而不是 0
 *
 * [SessionTimer] 内部用 `0L` 当"当前不在亮屏"的哨兵（`screenOnAt == 0L -> IDLE`）。
 * 传 `now = 0` 会命中这个哨兵、phase 退化成 IDLE、什么都不累计 —— 这是首次跑这组用例
 * 时真实踩到的坑（8 条断言全挂在 0）。真实运行取的是 `elapsedRealtime()`，不会为 0，
 * 所以生产代码不需要改；**测试必须避开 0**，统一用 [base] 偏移。
 *
 * ## 为什么值得存在
 *
 * 真机上验这一套要亮屏攒几分钟、掐表数秒，一轮至少五分钟，而且 19 秒与 21 秒根本数不准。
 * 这里用假时间把边界全部走一遍，几十毫秒跑完，改动代码后一条命令就能确认没弄坏语义。
 *
 * 时间单位统一为毫秒（与 [SessionTimer] 的形参一致）。
 */
class SessionTimerTest {

    private val minute = 60_000L
    private val threshold = 20 * minute      // 护眼阈值：20 分钟
    private val resetOnScreenOff = 20_000L   // 熄屏满 20 秒即清零
    private val gazeTimeout = 10_000L        // 注视心跳超时

    /** 起点。避开 0，见类注释。 */
    private val base = 10_000L

    private fun at(offset: Long): Long = base + offset

    private fun timer() = SessionTimer(threshold, resetOnScreenOff, gazeTimeout)

    private fun started() = timer().apply { init(base, screenIsOn = true) }

    // ---------------- 亮屏累计 ----------------

    @Test
    fun `亮屏后按区间累计`() {
        val t = started()

        val s = t.snapshot(at(1_000))

        assertEquals(1_000L, s.screenMs)
        assertEquals(TimingPhase.SCREEN, s.phase)
        assertTrue(s.accumulating)
    }

    @Test
    fun `多次取快照不重复累加同一段`() {
        val t = started()

        val first = t.snapshot(at(5_000))
        val second = t.snapshot(at(5_000))

        assertEquals(first.screenMs, second.screenMs)
        assertEquals(first.remainingMs, second.remainingMs)
    }

    // ---------------- 熄屏冻结 ----------------

    @Test
    fun `熄屏后立刻冻结不再累加`() {
        val t = started()
        t.onScreenOff(at(1_000))

        val s = t.snapshot(at(9_000))

        assertEquals(1_000L, s.screenMs)
        assertEquals(TimingPhase.IDLE, s.phase)
        assertFalse(s.accumulating)
    }

    // ---------------- 熄屏清零的两条边界 ----------------

    @Test
    fun `熄屏8秒不足阈值不清零`() {
        val t = started()
        t.onScreenOff(at(1_000))

        val reset = t.onScreenOn(at(9_000))

        assertFalse(reset)
        assertEquals(1_000L, t.snapshot(at(9_000)).screenMs)
    }

    @Test
    fun `熄屏25秒超过阈值清零`() {
        val t = started()
        t.onScreenOff(at(1_000))

        val reset = t.onScreenOn(at(26_000))

        assertTrue(reset)
        assertEquals(0L, t.snapshot(at(26_000)).screenMs)
    }

    /** 判据是 `>=`，所以恰好 20 秒必须清零——差一毫秒都不行。 */
    @Test
    fun `熄屏恰好20秒等于边界要清零`() {
        val t = started()
        t.onScreenOff(at(1_000))

        assertTrue(t.onScreenOn(at(21_000)))
    }

    @Test
    fun `熄屏比边界少1毫秒不清零`() {
        val t = started()
        t.onScreenOff(at(1_000))

        assertFalse(t.onScreenOn(at(20_999)))
    }

    /** 真实场景串联：亮屏 5 分钟 → 熄屏 8 秒 → 再亮屏 1 分钟，累计应是 6 分钟。 */
    @Test
    fun `熄屏不足阈值再亮屏接着攒`() {
        val t = started()
        t.onScreenOff(at(300_000))
        t.onScreenOn(at(308_000))

        assertEquals(360_000L, t.snapshot(at(368_000)).screenMs)
    }

    // ---------------- 阈值 ----------------

    @Test
    fun `到达阈值时剩余为零`() {
        val t = started()

        val s = t.snapshot(at(threshold))

        assertEquals(threshold, s.effectiveMs)
        assertEquals(0L, s.remainingMs)
    }

    // ---------------- 注视 ----------------

    @Test
    fun `注视信号进入GAZE并计入注视时长`() {
        val t = started()
        t.onGazeSignal(at(1_000), active = true)

        val s = t.snapshot(at(3_000))

        assertEquals(TimingPhase.GAZE, s.phase)
        assertEquals(2_000L, s.gazeMs)
        // 注视时段同时计入亮屏总时长，两者不是互斥关系
        assertEquals(3_000L, s.screenMs)
        assertTrue(s.gazeActive)
    }

    /**
     * 心跳超时：有效段仍算注视，超出部分回退为亮屏。
     * 1000 时收到信号、超时 10 秒 ⇒ 1000–11000 算注视，11000–20000 算亮屏。
     */
    @Test
    fun `注视心跳超时后超出部分回退为亮屏`() {
        val t = started()
        t.onGazeSignal(at(1_000), active = true)

        val s = t.snapshot(at(20_000))

        assertEquals(TimingPhase.SCREEN, s.phase)
        assertEquals(10_000L, s.gazeMs)
        assertEquals(20_000L, s.screenMs)
        assertFalse(s.gazeActive)
    }

    /** 判定"人没在看"时冻结计时：屏幕亮着，但这段不该算作用眼时长。 */
    @Test
    fun `注视暂停期间不累加`() {
        val t = started()
        t.snapshot(at(1_000))
        t.setGazeSuspended(at(1_000), suspended = true)

        val s = t.snapshot(at(6_000))

        assertEquals(1_000L, s.screenMs)
        assertFalse(s.accumulating)
        assertTrue(s.gazeSuspended)
    }

    // ---------------- 未初始化 ----------------

    @Test
    fun `未初始化直接取快照不崩且按熄屏处理`() {
        val t = timer()

        val s = t.snapshot(0)

        assertEquals(TimingPhase.IDLE, s.phase)
        assertEquals(0L, s.screenMs)
        assertEquals(threshold, s.remainingMs)
    }
}
