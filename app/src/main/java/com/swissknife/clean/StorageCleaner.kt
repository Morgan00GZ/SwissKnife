package com.swissknife.clean

/**
 * 「空间回收」的纯逻辑：解析服务返回、体积与人话文案。
 *
 * 全部是不依赖 Android 的纯函数，放这里是为了能用 JVM 单测钉住
 * （`app/src/test/java/com/swissknife/clean/StorageCleanerTest.kt`）——
 * 真机上掐表验"哪几项该默认勾选"成本太高。
 */
object StorageCleaner {

    /** 展示用的公共前缀：列表里每行都带它等于白占一截宽度。 */
    private const val SDCARD_ANDROID_PREFIX = "/sdcard/Android/"

    /** 一条扫描结果。 */
    data class Entry(val size: Long, val mtime: Long, val path: String) {
        val name: String get() = path.substringAfterLast('/')

        /** 已经是 0 字节的空壳：不可勾选，也不该重复写一遍。 */
        val isShell: Boolean get() = size == 0L

        /** 路径里第 4 段就是包名：/sdcard/Android/{data,obb}/<pkg>/...（不在其下则为空）。 */
        val pkg: String
            get() = path.split('/').let {
                if (it.size > 4 && (it[3] == "data" || it[3] == "obb")) it[4] else ""
            }

        /** 所在目录。用于展示"这文件在哪个包的哪个子目录里"，也便于以后做分组。 */
        val dir: String get() = path.substringBeforeLast('/', "")

        /** 交给界面显示的短路径：去掉 /sdcard/Android/ 前缀，省掉一截重复的公共部分。 */
        val shortPath: String
            get() = path.removePrefix(SDCARD_ANDROID_PREFIX).let {
                if (it.isEmpty()) path else it
            }
    }

    /** 把服务的 `"size\tmtime\tpath"` 解析成条目；坏行直接丢弃，不猜。 */
    fun parse(raw: Array<String>?): List<Entry> = raw.orEmpty().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val size = parts[0].toLongOrNull() ?: return@mapNotNull null
        val mtime = parts[1].toLongOrNull() ?: 0L
        Entry(size, mtime, parts[2])
    }

    /**
     * 默认勾选：**一个都不勾**。
     *
     * 这个功能的范围是所有 ≥100 MB 的文件（缓存、旧安装包、游戏补丁块……），
     * 里面很可能混着清掉就会出问题的东西，自动勾选等于替用户做决定。
     * 想批量选请用列表上方的「全选」，或分组左侧的组勾选框。
     */
    fun defaultSelection(entries: List<Entry>): Set<String> = emptySet()

    // ---------------- 分组（列表按"哪个应用在占空间"折叠） ----------------

    /**
     * 一组 = 一个应用（或指定目录下的一个一级子目录）。
     *
     * 为什么按应用分组而不是父子目录树：实测 16 个 ≥100 MB 文件散在 9 个应用里，
     * 其中 7 个应用只有 1 个文件、路径深 5–9 层——做成多层树，大部分节点是"独苗"，
     * 用户得一层层点开才看得到一个文件。按应用分组后，组头直接回答"哪个 App 在占空间"。
     */
    data class Group(val root: String, val label: String, val entries: List<Entry>) {
        /** 组内合计（含空壳，空壳是 0 字节，不影响结果）。 */
        val totalSize: Long get() = entries.sumOf { it.size }

        /** 组内还能清的条目（空壳不可勾选）。 */
        val clearable: List<Entry> get() = entries.filter { !it.isShell }

        /** 条目相对组根的路径，例如 `files/com.shiftup.patch/dp/chunk/store.cdb`。 */
        fun relativePath(e: Entry): String =
            e.path.removePrefix(root).trimStart('/').ifEmpty { e.name }
    }

    /**
     * 分组归属：
     * - 在 `Android/data/<pkg>/` 或 `Android/obb/<pkg>/` 下 → 组根就是 `<base>/<pkg>`；
     * - 指定目录模式下 → 组根取该目录下的一级子目录（直接躺在根下的文件归到根本身）；
     * - 其它情况 → 归到文件所在目录，至少不会把不相干的东西混在一起。
     */
    fun groupRoot(e: Entry, manualRoot: String): String {
        if (e.pkg.isNotEmpty()) {
            val base = if (e.path.startsWith("/sdcard/Android/obb/")) {
                "/sdcard/Android/obb"
            } else {
                "/sdcard/Android/data"
            }
            return "$base/${e.pkg}"
        }
        val root = manualRoot.trimEnd('/')
        if (root.isNotBlank() && e.path.startsWith("$root/")) {
            val rel = e.path.removePrefix("$root/")
            val first = rel.substringBefore('/', "")
            // 直接躺在扫描根下的文件：归到根，不要给每一条都建一个"组"
            return if (first.isEmpty() || !rel.contains('/')) root else "$root/$first"
        }
        return e.dir
    }

    /** 按 [groupRoot] 归并，组按合计大小降序，组内按大小降序。 */
    fun group(entries: List<Entry>, manualRoot: String): List<Group> {
        val map = LinkedHashMap<String, MutableList<Entry>>()
        entries.forEach { e -> map.getOrPut(groupRoot(e, manualRoot)) { mutableListOf() }.add(e) }
        return map.map { (root, list) ->
            Group(root, root.substringAfterLast('/').ifEmpty { root }, list.sortedByDescending { it.size })
        }.sortedByDescending { it.totalSize }
    }

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