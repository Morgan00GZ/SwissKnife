package com.swissknife.esr

import com.swissknife.core.ShizukuShell
import java.util.concurrent.Executors

/**
 * 小米 AON 注视检测。
 *
 * 系统里由 `com.xiaomi.aon` 实现 AttentionService，可通过 shell 主动触发一次检测：
 *   cmd attention call checkAttention          -> 派发成功返回 true
 *   cmd attention getLastTestCallbackCode      -> 取结果
 *
 * 实测回调码：1 = 正在注视屏幕，3 = 没在注视（脸离开摄像头范围）。
 * 任何一环取不到都返回 null，调用方回退到亮屏计时——本模块属于增强项，不是必要条件。
 *
 * 从 EyeStrainRelief 原样移植（2026-11-07），只把 ShizukuShell 换成 SwissKnife 的实现。
 */
object AttentionProbe {

    /** 传感器判定需要几秒，实测 3 秒可出结果，留 4 秒余量 */
    private const val CHECK_WAIT_MS = 4000L

    /**
     * 派发 + 等待 + 取结果，**一条命令里做完**（2026-11-09 功耗优化）。
     *
     * 原来分两次 `execSync`，每轮起两个 `sh` 进程；亮屏 8 小时约 960 轮 ≈ 1920 个进程/天。
     * 这类开销记在 shell（uid 2000）名下，应用自己的 batterystats 里根本看不见。
     * 合并之后每轮只起**一个**进程（它在 `sleep` 期间只是挂着、不耗 CPU）。
     *
     * 判定逻辑一字未改：`DISP` 含 `true` 才算派发成功，`CODE` 1=在看、3=没在看、其余视为取不到。
     */
    private val probeCommand: String =
        "echo DISP=\$(cmd attention call checkAttention); " +
            "sleep ${CHECK_WAIT_MS / 1000}; " +
            "echo CODE=\$(cmd attention getLastTestCallbackCode)"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "attention-probe").apply { isDaemon = true }
    }

    /** true=在看，false=没在看，null=取不到 */
    fun checkAsync(onResult: (Boolean?) -> Unit) {
        executor.execute { onResult(checkBlocking()) }
    }

    private fun checkBlocking(): Boolean? {
        if (!ShizukuShell.hasPermission()) return null
        val raw = ShizukuShell.execSync(probeCommand) ?: return null
        val dispatched = raw.lineSequence()
            .firstOrNull { it.startsWith("DISP=") }
            ?.removePrefix("DISP=")
            ?.trim()
        if (dispatched?.contains("true") != true) return null
        val code = raw.lineSequence()
            .firstOrNull { it.startsWith("CODE=") }
            ?.removePrefix("CODE=")
            ?.trim()
            ?.toIntOrNull() ?: return null
        // 这里原来有一行 Log.d("attention code=$code")，2026-11-20 删除：
        // 每轮采样（30/60 秒）执行一次，而本机 logcat 早已整段停摆（实测读到 0 条），
        // 等于每天往一个不输出的缓冲区写 2880 条。注视是否在跑改看 watch.log 的诊断行。
        return when (code) {
            1 -> true
            3 -> false
            else -> null
        }
    }
}
