package com.swissknife.esr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夜间静默时段的边界。规则是闭开区间：**22:00 进、08:00 出**。
 *
 * 这四个用例就是全部风险点——真实设备上很难在 22:00 和 08:00 各测一次，
 * 而在边界上错一小时，用户会看到"明明夜里还在暂停计时"或"白天反而不计了"。
 */
class QuietHoursTest {

    @Test
    fun `21点59分不算静默`() {
        assertFalse(QuietHours.isQuiet(21))
    }

    @Test
    fun `22点整进入静默`() {
        assertTrue(QuietHours.isQuiet(22))
    }

    @Test
    fun `23点与凌晨都在静默内`() {
        assertTrue(QuietHours.isQuiet(23))
        assertTrue(QuietHours.isQuiet(0))
        assertTrue(QuietHours.isQuiet(3))
    }

    @Test
    fun `7点仍静默_8点整恢复判定`() {
        assertTrue(QuietHours.isQuiet(7))
        assertFalse(QuietHours.isQuiet(8))
    }

    @Test
    fun `白天时段全部正常判定`() {
        listOf(8, 9, 12, 15, 18, 21).forEach { h ->
            assertFalse("hour=$h 不应处于静默", QuietHours.isQuiet(h))
        }
    }
}
