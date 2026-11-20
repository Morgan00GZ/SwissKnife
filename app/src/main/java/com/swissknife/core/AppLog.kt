package com.swissknife.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用自记日志（`files` 目录下的 `*.log`）的**唯一**写入口。
 *
 * ## 为什么要收拢（2026-11-20）
 *
 * 收拢前有 5 份几乎相同的实现（无障碍服务 / 护眼 / 结束提示 / 极致省电 / 空间回收），
 * 其中 **4 份写同一个 `watch.log`**，各自判一次大小、各自计数。后果：
 *
 * - 上限口径分散，改一处必然漏三处；
 * - 超限时**整份删除**——排查"两三天前那次没弹窗"时，日志已经没了；
 * - 每份都在热路径上多一次 `length()` 系统调用。
 *
 * ## 策略
 *
 * | 文件 | 上限 | 超限处置 |
 * |---|---|---|
 * | `watch.log` | 120 KB | 保留尾部 60 KB（按整行切） |
 * | `net.log` | 60 KB | 保留尾部 30 KB |
 *
 * 保留尾部而不是整删：日志的价值几乎全在"最近发生了什么"。文件系统层面也因此
 * **不会无限增长** —— 每次超限都缩回一半，再来一半才开始下一次裁剪。
 *
 * ## 成本
 *
 * - 每 [STAT_EVERY] 次写才 `stat` 一次（`File.length()` 是一次系统调用，按键这类热路径不该每次都付）；
 * - 只有 `stat` 真的超限才读文件做裁剪，正常情况零额外 I/O；
 * - 整段加锁：调用点分布在主线程、Shizuku 单线程与工作线程，日志是低频操作，
 *   串行化的代价远小于交叉写的风险。
 */
object AppLog {

    const val WATCH = "watch.log"
    const val NET = "net.log"

    /** 各文件上限（字节）。新增日志文件必须在这里登记，否则按 [DEFAULT_MAX] 处理。 */
    private val limits = mapOf(
        WATCH to 120_000L,
        NET to 60_000L,
    )

    /** 未登记的日志文件兜底上限，防止以后新增文件时忘了登记。 */
    private const val DEFAULT_MAX = 120_000L

    /** 每写这么多次才检查一次文件大小。 */
    private const val STAT_EVERY = 50

    private val counters = ConcurrentHashMap<String, Int>()
    private val lock = Any()

    /**
     * 写一行到 `watch.log`（悬浮球 / 护眼 / 结束提示 / 空间回收共用）。
     *
     * @param tag 段标记，会渲染成 `[护眼]` 这样的前缀；null 表示不加前缀。
     */
    fun watch(context: Context?, msg: String, tag: String? = null) =
        append(context, WATCH, msg, tag)

    /** 写一行到 `net.log`（极致省电专用，上限更小、内容更专）。 */
    fun net(context: Context?, msg: String) = append(context, NET, msg, null)

    private fun append(context: Context?, name: String, msg: String, tag: String?) {
        val dir = try {
            context?.applicationContext?.filesDir
        } catch (_: Throwable) {
            null
        } ?: return
        val line = buildString(msg.length + 32) {
            append(System.currentTimeMillis() / 1000)
            append(' ')
            if (tag != null) {
                append('[')
                append(tag)
                append("] ")
            }
            append(msg)
            append('\n')
        }
        synchronized(lock) {
            try {
                val f = File(dir, name)
                val n = (counters[name] ?: 0) + 1
                counters[name] = n
                // n == 1：进程刚起来的第一笔写就先查一次——上一次运行可能已经把文件撑过上限，
                // 不该再等 49 次才收敛。之后按 STAT_EVERY 降频。
                if (n == 1 || n % STAT_EVERY == 0) {
                    LogFileTrimmer.trimIfNeeded(f, limits[name] ?: DEFAULT_MAX)
                }
                f.appendText(line)
            } catch (_: Throwable) {
            }
        }
    }
}
