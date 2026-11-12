package com.swissknife.clean

/**
 * 「空间回收」的纯逻辑：解析服务返回、白名单判定、体积与人话文案。
 *
 * 全部是不依赖 Android 的纯函数，放这里是为了能用 JVM 单测钉住
 * （`app/src/test/java/com/swissknife/clean/StorageCleanerTest.kt`）——
 * 真机上掐表验"哪几项该默认勾选"成本太高。
 */
object StorageCleaner {

    /** 一条扫描结果。 */
    data class Entry(val size: Long, val mtime: Long, val path: String) {
        val name: String get() = path.substringAfterLast('/')

        /** 已经是 0 字节的空壳：不可勾选，也不该重复写一遍。 */
        val isShell: Boolean get() = size == 0L

        /** 路径里第 4 段就是包名：/sdcard/Android/data/<pkg>/... */
        val pkg: String
            get() = path.split('/').let { if (it.size > 4) it[4] else "" }

        val whitelisted: Boolean get() = isWhitelisted(name)
    }

    /**
     * 白名单：UE4 引擎类游戏的自更新下载包，形如
     * `924524759_..._1.101.0.108_..._apkupdate.apk`。
     *
     * 只认这一种。工程上宁可漏清也不要误清 —— 存档（`SaveGames` / `.sav`）被清空是不可逆的。
     */
    private val APK_UPDATE = Regex("_apkupdate\\.apk$", RegexOption.IGNORE_CASE)

    fun isWhitelisted(name: String): Boolean = APK_UPDATE.containsMatchIn(name)

    /** 把服务的 `"size\tmtime\tpath"` 解析成条目；坏行直接丢弃，不猜。 */
    fun parse(raw: Array<String>?): List<Entry> = raw.orEmpty().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val size = parts[0].toLongOrNull() ?: return@mapNotNull null
        val mtime = parts[1].toLongOrNull() ?: 0L
        Entry(size, mtime, parts[2])
    }

    /**
     * 默认勾选：白名单命中 **且** 还有内容。空壳默认不勾（截断它没有任何收益），
     * 非白名单项默认不勾（手输路径模式下由用户自己决定）。
     */
    fun defaultSelection(entries: List<Entry>): Set<String> =
        entries.filter { it.whitelisted && !it.isShell }.map { it.path }.toSet()

    /** 体积人话：0 B / 812 KB / 120.5 MB / 4.23 GB。 */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${trim(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${trim(mb)} MB"
        return "${trim(mb / 1024.0)} GB"
    }

    private fun trim(v: Double): String {
        val s = if (v >= 100) "%.0f" else if (v >= 10) "%.1f" else "%.2f"
        return String.format(java.util.Locale.US, s, v)
    }

    /**
     * 汇总文案。**全项目口径：不说"删除"**，因为目录项还在。
     * 说"清空内容"和"条目保留为空壳"才是事实。
     *
     * 一项都没成功时不要写成「已释放 0 B，0 个条目保留为空壳」——那句话读起来像
     * 干了点什么但没腾出空间，实际是「一个都没动成」，必须说清楚。
     */
    fun summary(freedBytes: Long, shellCount: Int): String =
        if (shellCount <= 0) {
            "未清空任何条目"
        } else {
            "已释放 ${humanSize(freedBytes)}，$shellCount 个条目保留为空壳"
        }

    /** 待清空总量。 */
    fun totalSize(entries: List<Entry>, selected: Set<String>): Long =
        entries.filter { it.path in selected }.sumOf { it.size }
}