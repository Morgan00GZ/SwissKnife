package com.swissknife.core

import java.io.File

/**
 * 日志文件的超限裁剪（**纯 JVM**，不碰 Android，可单测）。
 *
 * ## 为什么单独抽出来
 *
 * 这段代码**会改写用户的日志文件**，写错就是把现场证据毁掉：截断成半行、裁成空文件、
 * 或者干脆抛异常把后续写入连带弄死。而它一年也未必触发几次（120 KB 按当前写入量约 7.8 天一轮），
 * 真机极难复现。所以逻辑抽成纯函数，用临时文件在单测里把边界跑一遍。
 *
 * ## 策略
 *
 * 保留**尾部** [maxBytes] × [fraction]，而不是删整个文件：日志的价值几乎全在"最近发生了什么"。
 * 裁剪点必须落在换行之后——从中间切会留下半行，读的人会以为那条记录本身就是残的。
 *
 * 任何异常都由调用方吞掉：日志写不进去是小事，不能因此影响功能。
 */
object LogFileTrimmer {

    /** 默认保留比例（相对上限）。 */
    const val DEFAULT_FRACTION = 0.5

    /**
     * 超过 [maxBytes] 时裁到尾部 [maxBytes] × [fraction]。
     *
     * @return true 表示真的裁剪了文件（调用方可据此打日志，当前没有调用方用）。
     */
    fun trimIfNeeded(f: File, maxBytes: Long, fraction: Double = DEFAULT_FRACTION): Boolean {
        if (!f.isFile) return false
        val len = f.length()
        if (len <= maxBytes) return false
        val keep = (maxBytes * fraction).toInt()
        if (keep <= 0) return false
        val bytes = f.readBytes()
        // 目标起点：从末尾往回数 keep 字节，再往后找到第一个换行，保证留下的是完整行
        var start = bytes.size - keep
        if (start <= 0) return false
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        // 找不到换行（整个文件是一行）或剩不下内容：宁可不裁，也不要写出一个空文件
        if (start >= bytes.size - 1) return false
        f.writeBytes(bytes.copyOfRange(start + 1, bytes.size))
        return true
    }
}