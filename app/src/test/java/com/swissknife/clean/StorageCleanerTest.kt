package com.swissknife.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空间回收」的纯逻辑：解析、路径拆分、默认勾选、体积文案。
 *
 * 为什么这些要放 JVM 单测而不是真机看：
 * - **默认勾选错了会误清**：范围是所有 ≥100 MB 的文件，自动勾选等于替用户做决定；
 * - **路径拆分错了会误导**：包名/目录是从路径里数出来的，数错就会给错提示；
 * - 体积文案只在边界上容易错（1023 B / 1 KB / 1024 KB / 1 GB）。
 */
class StorageCleanerTest {

    // ---------------- 解析 ----------------

    @Test
    fun `解析服务返回的三段格式`() {
        val e = StorageCleaner.parse(
            arrayOf("209715200\t1763000000000\t/sdcard/Android/data/com.tencent.nrc/files/old.apk")
        ).single()
        assertEquals(209715200L, e.size)
        assertEquals(1763000000000L, e.mtime)
        assertEquals("old.apk", e.name)
        assertEquals("com.tencent.nrc", e.pkg)
        assertFalse(e.isShell)
    }

    @Test
    fun `包名与目录按路径数出来`() {
        val e = StorageCleaner.parse(
            arrayOf("1\t0\t/sdcard/Android/data/org.telegram.messenger/cache/5.temp")
        ).single()
        assertEquals("org.telegram.messenger", e.pkg)
        assertEquals("/sdcard/Android/data/org.telegram.messenger/cache", e.dir)
    }

    @Test
    fun `不在 data 下的路径不瞎猜包名`() {
        val e = StorageCleaner.parse(arrayOf("1\t0\t/sdcard/Android/obb/com.x/main.1.obb")).single()
        assertEquals("", e.pkg)
        assertEquals("/sdcard/Android/obb/com.x", e.dir)
    }

    @Test
    fun `短路径去掉公共前缀`() {
        val e = StorageCleaner.parse(arrayOf("1\t0\t/sdcard/Android/data/p/cache/a.bin")).single()
        assertEquals("data/p/cache/a.bin", e.shortPath)
    }

    @Test
    fun `坏行直接丢弃而不是猜`() {
        val list = StorageCleaner.parse(
            arrayOf(
                "不是数字\t1\t/a",
                "1\t2",                                   // 少一段
                "0\t0\t/sdcard/Android/data/p/cache/old.bin"
            )
        )
        assertEquals(1, list.size)
        assertEquals("old.bin", list.single().name)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(StorageCleaner.parse(null).isEmpty())
        assertTrue(StorageCleaner.parse(emptyArray()).isEmpty())
    }

    // ---------------- 默认勾选 ----------------

    @Test
    fun `默认一条都不勾`() {
        val entries = listOf(
            StorageCleaner.Entry(1024, 0, "/sdcard/Android/data/p/cache/a.bin"),
            StorageCleaner.Entry(0, 0, "/sdcard/Android/data/p/cache/b.bin"),   // 空壳
            StorageCleaner.Entry(4096, 0, "/sdcard/Android/data/p/SaveGames.sav")
        )
        assertTrue(StorageCleaner.defaultSelection(entries).isEmpty())
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