package com.swissknife.esr

/**
 * 注视采样的节拍决策（**纯函数**，可 JVM 单测）。
 *
 * ## 为什么单独抽出来
 *
 * 每轮采样要起一个 `sh` 进程（`cmd attention` 内含 4 秒 `sleep`），这是「护眼休息」
 * 唯一的持续开销，而且记在 shell（uid 2000）名下——应用自己的 `batterystats` 里看不见。
 * 所以间隔不能是死数：**稳定「在看」时拉长**，刚出现异常时收紧。
 *
 * 抽成纯函数是为了能被 `GazeCadenceTest` 锁死：这条逻辑一旦写反（比如把判断取反），
 * 表现是"采样变慢到用户体感不到"，日志里几乎看不出来，只能靠测试兜住。
 * 与本项目 `QuietHours.isQuiet` 同一做法（带 Android 依赖的 object 不能直接测）。
 *
 * ## 规则（2026-11-20 用户选定：在看时 60 秒）
 *
 * | 状态 | 间隔 | 理由 |
 * |---|---|---|
 * | 还没有第一次结果 | 30 秒 | 尽快建立基线，别先沉 60 秒 |
 * | 刚判过「没在看」（streak > 0） | 30 秒 | 要尽快确认第二次，否则一直冻不住计时 |
 * | 计时已暂停 | 30 秒 | 正在等恢复，越快越好 |
 * | 连续在看（streak == 0 且未暂停） | 60 秒 | 状态稳定，亮屏 8 小时可省掉约一半 `sh` 进程 |
 *
 * 已知代价：人离开后最晚 60 秒才发现，再 30 秒确认第二次 ⇒ 冻结计时最多晚约 90 秒，
 * 相比 20 分钟阈值约 7% 的累计误差，用户已确认接受。
 *
 * 该间隔必须显著小于 `eyeGazeTimeoutMs`（默认 3 分钟），否则计时器会把注视心跳判为
 * 超时并回退亮屏计时——60 秒仍在安全范围内。
 */
object GazeCadence {

    /** 快档：首次、异常确认、等待恢复时使用。 */
    const val FAST_MS = 30_000L

    /** 慢档：连续判定在看时使用。 */
    const val STEADY_MS = 60_000L

    /**
     * 下一轮采样该等多久。
     *
     * @param hasResult 是否已经拿到过至少一次采样结果（对应 `gazeDiagLogged`）
     * @param notAttentiveStreak 连续判定「没在看」的次数
     * @param suspended 计时是否已因「没在看」被冻结
     */
    fun interval(hasResult: Boolean, notAttentiveStreak: Int, suspended: Boolean): Long =
        if (hasResult && notAttentiveStreak == 0 && !suspended) STEADY_MS else FAST_MS
}