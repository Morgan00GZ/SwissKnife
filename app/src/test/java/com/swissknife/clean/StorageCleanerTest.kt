package com.swissknife.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空间回收」的纯逻辑：白名单判定、解析、默认勾选、体积文案。
 *
 * 为什么这些要放 JVM 单测而不是真机看：
 * - **白名单判定错了会误伤**：多认一个后缀就可能把存档（.sav / SaveGames）清成空壳，
 *   而清空内容不可逆。真机上没法把"每种后缀都试一遍"做完，这里几十毫秒跑完。
 * - **默认勾选错了会误清**：空壳不该勾（没收益），非白名单不该勾（不知道是什么）。
 * - 体积文案只在边界上容易错（1023 B / 1 KB / 1024 KB / 1 GB）。
 */
class StorageCleanerTest {

    // ---------------- 白名单 ----------------

    @Test
    fun `UE4自更新包名命中白名单`() {
        assertTrue(
            StorageCleaner.isWhitelisted(
                "924524759_1201576111_1.101.0.108_20260519195128_1242321403_apkupdate.apk"
            )
        )
    }

    @Test
    fun `白名单判定大小写不敏感`() {
        assertTrue(StorageCleaner.isWhitelisted("ABC_APKUPDATE.APK"))
    }

    @Test
    fun `存档与普通包绝不命中白名单`() {
        listOf(
            "SaveGames.sav",
            "Profile.sav",
            "pakchunk0-Android_ASTC.pak",
            "NRC.apk",
            "global-metadata.dat",
            "apkupdate.apk.txt"
        ).forEach { assertFalse("$it 不该命中白名单", StorageCleaner.isWhitelisted(it)) }
    }

    // ---------------- 解析 ----------------

    @Test
    fun `解析服务返回的三段格式`() {
        val e = StorageCleaner.parse(
            arrayOf("209715200\t1763000000000\t/sdcard/Android/data/com.tencent.nrc/files/x_apkupdate.apk")
        ).single()
        assertEquals(209715200L, e.size)
        assertEquals(1763000000000L, e.mtime)
        assertEquals("x_apkupdate.apk", e.name)
        assertEquals("com.tencent.nrc", e.pkg)
        assertFalse(e.isShell)
        assertTrue(e.whitelisted)
    }

    @Test
    fun `坏行直接丢弃而不是猜`() {
        val list = StorageCleaner.parse(
            arrayOf(
                "不是数字\t1\t/a",
                "1\t2",                                   // 少一段
                "0\t0\t/sdcard/Android/data/p/a_apkupdate.apk"
            )
        )
        assertEquals(1, list.size)
        assertEquals("a_apkupdate.apk", list.single().name)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(StorageCleaner.parse(null).isEmpty())
        assertTrue(StorageCleaner.parse(emptyArray()).isEmpty())
    }

    // ---------------- 默认勾选 ----------------

    @Test
    fun `只默认勾选白名单里有内容的项`() {
        val entries = listOf(
            StorageCleaner.Entry(1024, 0, "/sdcard/Android/data/p/a_apkupdate.apk"),
            StorageCleaner.Entry(0, 0, "/sdcard/Android/data/p/b_apkupdate.apk"),  // 空壳
            StorageCleaner.Entry(4096, 0, "/sdcard/Android/data/p/SaveGames.sav")  // 非白名单
        )
        val sel = StorageCleaner.defaultSelection(entries)
        assertEquals(setOf("/sdcard/Android/data/p/a_apkupdate.apk"), sel)
    }

    @Test
    fun `待清空总量只算已勾选`() {
        val entries = listOf(
            StorageCleaner.Entry(100, 0, "/sdcard/Android/data/p/a_apkupdate.apk"),
            StorageCleaner.Entry(900, 0, "/sdcard/Android/data/p/b_apkupdate.apk")
        )
        assertEquals(100L, StorageCleaner.totalSize(entries, setOf(entries[0].path)))
        assertEquals(1000L, StorageCleaner.totalSize(entries, entries.map { it.path }.toSet()))
    }

    // ---------------- 文案 ----------------

    @Test
    fun `体积文案的边界`() {
        assertEquals("0 B", StorageCleaner.humanSize(0))
        assertEquals("512 B", StorageCleaner.humanSize(512))
        assertEquals("1.00 KB", StorageCleaner.humanSize(1024))
        assertEquals("1024 KB", StorageCleaner.humanSize(1024L * 1024 - 1))
        assertEquals("1.00 MB", StorageCleaner.humanSize(1024L * 1024))
        // 三位数只留整数位：1023.0 MB 这种长串在窄列表里会折行，120.5 → 121 MB 是刻意取整
        assertEquals("121 MB", StorageCleaner.humanSize(126_353_408))
        assertEquals("9.99 MB", StorageCleaner.humanSize(10_475_520))
        assertEquals("4.23 GB", StorageCleaner.humanSize(4_541_000_000))
    }

    @Test
    fun `一项都没成功时不说已释放零字节`() {
        assertEquals("未清空任何条目", StorageCleaner.summary(0, 0))
    }

    @Test
    fun `成功时汇报释放量与空壳数`() {
        val s = StorageCleaner.summary(200L * 1024 * 1024, 1)
        assertTrue(s.startsWith("已释放 200 MB"))
        assertTrue(s.contains("1 个条目保留为空壳"))
    }

    @Test
    fun `文案里不出现删除二字`() {
        listOf(
            StorageCleaner.summary(1024, 2),
            StorageCleaner.summary(0, 0)
        ).forEach { assertFalse("文案不得出现「删除」：$it", it.contains("删除")) }
    }
}