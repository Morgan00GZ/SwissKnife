package com.swissknife.clean

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.util.Locale

/**
 * 「空间回收」的服务实现 —— **跑在 Shizuku 拉起的 shell 进程（uid 2000）里**，
 * 不是应用的普通进程。这一点是整个功能成立的前提：
 *
 * - 应用自身 uid（10xxx）在 Android 11+ 读不到别人的 `Android/data/<pkg>`，扫描放错进程 = 永远空列表；
 * - shell 在 `ext_data_rw`(1078) 组内，能读、能改文件内容，但目录位是 `drwxr-s---`（组内无 w），
 *   所以能截断、不能删目录项 —— 这不是命令写法问题，是权限模型。
 *
 * 因此本类只做文件操作，不碰 UI、不持有 Context、不发通知。
 * 也不声明在 manifest 里：Shizuku 用 app_process 直接实例化它，它不是 Android 组件。
 */
class TruncateService : ITruncateService.Stub() {

    override fun uid(): Int = Process.myUid()

    override fun listPackages(): Array<String> =
        File(EXTERNAL_DATA).list()?.sorted()?.toTypedArray() ?: emptyArray()

    override fun scanLarge(minSize: Long, cacheDirsOnly: Boolean): Array<String> {
        val found = ArrayList<Hit>()
        // 从 /sdcard/Android 起走，data / obb / media 一并覆盖：
        // 用户要的是"这些目录里占空间又删不掉的东西"，不限定是哪一类。
        walk(
            File(EXTERNAL_ANDROID), FULL_MAX_DEPTH,
            isRoot = true, minSize = minSize, cacheDirsOnly = cacheDirsOnly, out = found
        )
        return format(found)
    }

    override fun scan(dirPath: String, maxDepth: Int, minSize: Long, cacheDirsOnly: Boolean): Array<String> {
        val root = File(dirPath)
        if (!root.isDirectory) return emptyArray()
        val found = ArrayList<Hit>()
        walk(
            root, maxDepth.coerceIn(1, MAX_DEPTH_LIMIT),
            isRoot = true, minSize = minSize, cacheDirsOnly = cacheDirsOnly, out = found
        )
        return format(found)
    }

    /**
     * 清理一个文件：先试 unlink（真删除目录项），失败再 ftruncate 为 0。
     *
     * 为什么顺序是这个：能不能 unlink 取决于**父目录的组位**，而它各应用不同 ——
     * `drwxrws---`(2770) 组内有 `w`，shell 在 `ext_data_rw`(1078) 组里就能删；
     * `drwxr-s---`(2750) 组内只读，只能截断。同一台机器两种都存在（2026-11-13 实测），
     * 所以只能逐个文件先试删除、失败再退到截断，不能全局写死一条路。
     *
     * 截断刻意用 `Os.open` + `Os.ftruncate` 而不是 `FileOutputStream(f, false)`：
     * 后者失败只抛 `FileNotFoundException`，EACCES 与 ENOENT 分不开，错误处理会退化成瞎猜。
     */
    override fun clear(path: String): Long {
        // 1) 先试真删除（Os.remove 对文件就是 unlink）
        try {
            Os.remove(path)
            return CODE_REMOVED
        } catch (e: ErrnoException) {
            // 文件本来就没了：直接如实返回，不必再试截断
            if (e.errno == OsConstants.ENOENT) return CODE_ENOENT
            // EACCES / EPERM / EROFS …：删不掉很正常，落到截断
        } catch (t: Throwable) {
            // 兜底：继续试截断，别因为一次意外异常就放弃
        }

        // 2) 删不掉就把内容截断为 0
        var fd: FileDescriptor? = null
        return try {
            fd = Os.open(path, OsConstants.O_WRONLY, 0)
            Os.ftruncate(fd, 0)
            // 复核：截断后必须是 0，否则当未知失败处理，不能谎报成功
            if (File(path).length() == 0L) CODE_TRUNCATED else CODE_OTHER
        } catch (e: ErrnoException) {
            when (e.errno) {
                OsConstants.EACCES, OsConstants.EPERM -> CODE_EACCES
                OsConstants.ENOENT -> CODE_ENOENT
                else -> CODE_OTHER
            }
        } catch (t: Throwable) {
            CODE_OTHER
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /**
     * 目标包是否有存活进程。
     *
     * shell 带着 `readproc`(3009) 组，所以读得到别人的 `/proc/<pid>/cmdline`；
     * 应用进程的 cmdline 就是包名本身。截断正在写的文件等于让目标应用读到半截数据，
     * 所以要先拦下、让用户退出应用，而不是硬截。
     */
    override fun isProcessAlive(pkg: String): Boolean {
        val pids = File("/proc").list() ?: return false
        for (name in pids) {
            if (name.toIntOrNull() == null) continue
            val cmdline = runCatching {
                File("/proc/$name/cmdline").readText().trim { it <= ' ' }
            }.getOrNull() ?: continue
            if (cmdline == pkg) return true
        }
        return false
    }

    /**
     * 让本进程退出。
     *
     * 为什么必须服务侧自杀：`Shizuku.unbindUserService(args, conn, true)` 在 13.1.5 + 本机
     * Shizuku 上只解绑 binder，`app_process` 仍以 shell 身份常驻（`PPID=1`）。
     * 每次进功能页绑一次，就多留一个进程 —— 2026-11-12 实测一轮累积 11 个。
     *
     * 延迟 300ms：先让 binder 把返回值送回去，再退出，避免调用方拿到
     * `DeadObjectException` 而不是正常返回。
     */
    override fun shutdown() {
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, 300)
    }

    // ---------------- 内部 ----------------

    private class Hit(
        val size: Long,
        val mtime: Long,
        val path: String,
        val isDir: Boolean = false,
        val count: Int = 0
    )

    /**
     * 有深度上限的遍历，**一次同时产出"大文件"和"缓存类目录"两种条目**。
     *
     * 为什么要一起产出：分两次扫（一次找文件、一次算 cache 目录）等于把整棵树走两遍，
     * 实测全盘约 10 秒，走两遍就 20 秒，用户等不起。
     *
     * 目录命中名单后**不再往里列文件**：否则同一批内容会出现两次（cache 目录 + 里面的大文件），
     * 一起勾选还会把"已选大小"算重。代价是目录里的大文件看不到单条 —— 但那种场景
     * 本来就该整目录清掉（用户 2026-11-13 的用法）。
     *
     * 「缓存类」名单见 [CACHE_DIR_NAMES]；命中的目录递归求和后作为一条结果返回。
     */
    private fun walk(
        dir: File,
        depthLeft: Int,
        isRoot: Boolean,
        minSize: Long,
        cacheDirsOnly: Boolean,
        out: MutableList<Hit>
    ) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                val isCacheDir = !isRoot && f.name.lowercase(Locale.ROOT) in CACHE_DIR_NAMES
                if (isCacheDir && cacheDirsOnly) {
                    // 目录模式：整目录算一条，不再往里列文件（否则同一批内容出现两次、勾选会重复计数）
                    val (sum, count) = sizeOf(f)
                    if (sum >= minSize) {
                        out.add(Hit(sum, f.lastModified(), f.absolutePath, isDir = true, count = count))
                    }
                    continue
                }
                if (depthLeft > 0) {
                    walk(f, depthLeft - 1, isRoot = false, minSize = minSize, cacheDirsOnly = cacheDirsOnly, out = out)
                }
                continue
            }
            // 文件模式：cache 目录照常递归进来，里面的大文件也单列（不产出目录行）
            if (cacheDirsOnly) continue
            val size = f.length()
            if (size < minSize) continue
            out.add(Hit(size, f.lastModified(), f.absolutePath))
        }
    }

    /** 递归求和 + 文件数。用于把一个目录当成一条结果展示。 */
    private fun sizeOf(dir: File): Pair<Long, Int> {
        var sum = 0L
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val (s, c) = sizeOf(f)
                sum += s
                count += c
            } else {
                sum += f.length()
                count++
            }
        }
        return sum to count
    }

    /** 按大小降序、截断到上限，格式化成 AIDL 能传的字符串数组。 */
    private fun format(hits: List<Hit>): Array<String> =
        hits.sortedByDescending { it.size }
            .take(MAX_ENTRIES)
            .map {
                if (it.isDir) "D\t${it.size}\t${it.count}\t${it.path}"
                else "${it.size}\t${it.mtime}\t${it.path}"
            }
            .toTypedArray()

    /**
     * 清空一个「缓存类」目录的内容，保留目录本身。
     *
     * **名单校验必须留在服务端**：这个进程的 uid 是 shell，一旦调用方传错路径
     * （比如整个 `<pkg>/files`），后果是应用数据全没。名单之外的路径直接拒绝。
     */
    override fun clearDir(path: String): Long {
        val root = File(path)
        if (root.name.lowercase(Locale.ROOT) !in CACHE_DIR_NAMES) return CODE_NOT_ALLOWED
        if (!root.isDirectory) return CODE_ENOENT
        var failed = 0
        clearInside(root) { failed++ }
        return if (failed == 0) 0L else CODE_PARTIAL
    }

    private fun clearInside(dir: File, onFail: () -> Unit) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                clearInside(f, onFail)
                if (!f.delete()) onFail()
            } else {
                val r = clear(f.absolutePath)
                // 文件已经不在了不算失败
                if (r < 0 && r != CODE_ENOENT) onFail()
            }
        }
    }

    companion object {
        /** shell 侧看到的外部存储路径。与 adb shell 里 `ls /sdcard/Android/data` 同一入口。 */
        private const val EXTERNAL_DATA = "/sdcard/Android/data"

        /** 全盘扫描的起点：data / obb / media 都在它下面。 */
        private const val EXTERNAL_ANDROID = "/sdcard/Android"

        /** 全盘扫描的输出深度上限（含 `data/<pkg>/files/a/b/c/...` 这类长路径）。 */
        private const val FULL_MAX_DEPTH = 10

        /** 调用方传进来的深度上限的保护值，防止误传一个巨大值把递归拖死。 */
        private const val MAX_DEPTH_LIMIT = 10

        /** 单次扫描的条数上限，防 binder 事务超限（1MB 级）与 UI 卡死。 */
        private const val MAX_ENTRIES = 300

        /** 已移除条目（真删除成功）。 */
        const val CODE_REMOVED = 0L

        /** 已截断为 0 字节（条目保留为空壳）。 */
        const val CODE_TRUNCATED = 1L

        const val CODE_EACCES = -1L
        const val CODE_ENOENT = -2L
        const val CODE_PARTIAL = -3L
        const val CODE_NOT_ALLOWED = -4L
        const val CODE_OTHER = -9L

        /**
         * 可以整目录清空的目录名（小写比较）。用户 2026-11-13 要求"尽量把差不多意思的都包含进去"，
         * 所以 cache / temp / tmp / files 及其常见变体都在内。
         *
         * **files 也在内但只作为"可列可勾"的目录**：里面可能放存档、数据库、登录态，
         * 界面上会标「含应用数据」，勾不勾由用户决定（服务端不会再额外拦它）。
         */
        private val CACHE_DIR_NAMES = setOf(
            "cache", "caches", ".cache", "code_cache",
            "temp", "temps", "tmp", ".tmp", "temporary",
            "files", "file"
        )
    }
}