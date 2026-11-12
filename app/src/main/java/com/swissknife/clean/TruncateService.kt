package com.swissknife.clean

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

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

    override fun scanUe4(): Array<String> {
        val found = ArrayList<Hit>()
        // 逐包看 files/UE4Game：绝大多数包没有这个目录，isDirectory 是一次廉价 stat。
        File(EXTERNAL_DATA).listFiles()?.forEach { pkgDir ->
            val ue4 = File(pkgDir, "files/UE4Game")
            if (ue4.isDirectory) walk(ue4, UE4_MAX_DEPTH, 0L, onlyApkUpdate = true, found)
        }
        return format(found)
    }

    override fun scan(dirPath: String, maxDepth: Int, minSize: Long): Array<String> {
        val root = File(dirPath)
        if (!root.isDirectory) return emptyArray()
        val found = ArrayList<Hit>()
        walk(root, maxDepth.coerceIn(1, MAX_DEPTH_LIMIT), minSize, onlyApkUpdate = false, found)
        return format(found)
    }

    /**
     * 截断为 0 字节。
     *
     * 刻意用 `Os.open` + `Os.ftruncate` 而不是 `FileOutputStream(f, false)`：
     * 后者失败只抛 `FileNotFoundException`，EACCES 与 ENOENT 分不开，错误处理会退化成瞎猜。
     * 这里能拿到精确 errno，UI 才能对"受保护"和"已消失"给出不同处置。
     */
    override fun truncate(path: String): Long {
        var fd: FileDescriptor? = null
        return try {
            fd = Os.open(path, OsConstants.O_WRONLY, 0)
            Os.ftruncate(fd, 0)
            // 复核：截断后必须是 0，否则当未知失败处理，不能谎报成功
            if (File(path).length() == 0L) 0L else CODE_OTHER
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

    private class Hit(val size: Long, val mtime: Long, val path: String)

    /**
     * 有深度上限的遍历。
     *
     * 不递归全盘：`Android/data` 下有些包有几十万个小文件，一次扫完既慢又会把 binder 数据包撑爆。
     * 上限之外的东西直接放弃 —— 这个工具的用途是清大文件，不是做磁盘分析。
     */
    private fun walk(
        dir: File,
        depthLeft: Int,
        minSize: Long,
        onlyApkUpdate: Boolean,
        out: MutableList<Hit>
    ) {
        if (depthLeft < 0 || out.size >= MAX_ENTRIES) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= MAX_ENTRIES) return
            if (f.isDirectory) {
                walk(f, depthLeft - 1, minSize, onlyApkUpdate, out)
                continue
            }
            val size = f.length()
            if (size < minSize) continue
            if (onlyApkUpdate && !StorageCleaner.isWhitelisted(f.name)) continue
            out.add(Hit(size, f.lastModified(), f.absolutePath))
        }
    }

    /** 按大小降序，格式化成 AIDL 能传的字符串数组。 */
    private fun format(hits: List<Hit>): Array<String> =
        hits.sortedByDescending { it.size }
            .map { "${it.size}\t${it.mtime}\t${it.path}" }
            .toTypedArray()

    companion object {
        /** shell 侧看到的外部存储路径。与 adb shell 里 `ls /sdcard/Android/data` 同一入口。 */
        private const val EXTERNAL_DATA = "/sdcard/Android/data"

        /** UE4 目录层级：files/UE4Game/<项目>/<项目>/Saved/<渠道>/xxx_apkupdate.apk */
        private const val UE4_MAX_DEPTH = 6

        private const val MAX_DEPTH_LIMIT = 8

        /** 单次扫描的条数上限，防 binder 事务超限（1MB 级）与 UI 卡死。 */
        private const val MAX_ENTRIES = 300

        const val CODE_EACCES = -1L
        const val CODE_ENOENT = -2L
        const val CODE_OTHER = -9L
    }
}