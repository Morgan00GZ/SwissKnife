package com.swissknife.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.keepalive.KeepAliveService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「极致省电」的执行体：省电模式期间把默认卡的允许制式从"5G 优先"降到"4G 优先"。
 *
 * ## 为什么是 `cmd phone set-allowed-network-types-for-users`
 *
 * 实测（25098PN5AC / HyperOS，2026-11-06）：
 *
 * | 路径 | 实测结果 |
 * |---|---|
 * | `cmd phone set-allowed-network-types-for-users -s 0 <二进制掩码>` | 返回 `completed`，5 秒内实测制式 `NR_SA` → `LTE` |
 * | `settings put global preferred_network_mode1 9` | 写入成功但 modem 不动 —— 已判死 |
 *
 * 三个必踩的坑（都付过学费）：
 *
 * 1. **掩码参数是二进制字符串**。写 `381831` 会得到 `No valid NETWORK_TYPES_BITMASK`，
 *    极易误判成"没权限"（实际 `shell` 的 `MODIFY_PHONE_STATE` 是 granted=true）。
 * 2. **必须显式给 `-s <卡槽>`**。命令默认按"默认语音卡的槽位"解析，而本机
 *    `defaultVoiceSubId=-1`（VoLTE-only），解析不出来就回一句 `failed`——现象与"没权限"一模一样。
 *    槽位从 `dumpsys isub` 的 `defaultDataSubId` + `simSlotIndex` 取。
 * 3. **不要按制式名反推掩码**。`get-allowed-network-types-for-users` 打印的是"友好名"，
 *    本机实测出现 `HSPA+`、`CDMA - eHRPD` 这种别名，与 `NETWORK_TYPE_*` 常量名对不上，
 *    名表一旦漏项就会算错掩码（漏一项 = 顺手关掉一个用户没同意关的制式）。
 *    所以数值一律取 `dumpsys isub` 里的 `allowedNetworkTypesForReasons=user=<整数>`，
 *    名字只用来看。
 *
 * ## 一次可恢复的改写
 *
 * 去 NR 之前的原始掩码必须落盘（[Prefs.cell4gOriginalMask]）。退出省电时按用户决策
 * **强制写回原值**——即使期间用户自己改过网络模式。
 *
 * ## 与系统自身行为的叠加
 *
 * 本机实测：澎湃OS 自己在省电时会改网络模式（`preferred_network_mode` 32→33，
 * 同时 `secure` 里有 `pc_power_mode_5g_restored=1` 这类键）。因此本功能有两件事要做：
 *
 * - 读到"NR 已经不在了"就记一条「无需改动」并**不记录还原点**，避免退出省电时
 *   把系统已经恢复好的 5G 又重新按"我的原值"写坏；
 * - 写入前后都回读系统侧真值，不凭命令返回码下结论。
 *
 * ## 功耗
 *
 * 零轮询。只注册一个 `ACTION_POWER_SAVE_MODE_CHANGED` 动态广播，事件驱动。
 */
object Saver4G {

    /** NR（5G）在允许制式掩码里的位。位定义是 `1 << (NETWORK_TYPE - 1)`，NR=20。 */
    const val NR_BIT: Long = 1L shl 19

    private const val TAG = "SwissKnife"

    /** 一次读到的系统侧真值。 */
    data class CellState(
        /** 默认数据卡所在卡槽，用于 `-s`。 */
        val slot: Int,
        /** 默认数据卡 subId，仅用于日志与排错。 */
        val subId: Int,
        /** 允许制式掩码（reason=user）。 */
        val mask: Long
    )

    /** 二级页异步取到的权威网络信息。 */
    data class NetInfo(
        val slot: Int,
        /** `get-allowed-network-types-for-users -s <slot>` 的原始行，只用于展示。 */
        val allowed: String,
        /** 实测制式，如 `20(NR_SA)`。 */
        val rat: String,
        /** 掩码里是否仍允许 NR。 */
        val nrAllowed: Boolean
    )

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var host: Context? = null

    /** 只用于写日志的上下文。入口可能早于 [attach]（首页开关先于服务创建），所以单独存。 */
    @Volatile
    private var logCtx: Context? = null

    /**
     * 状态变化回调（二级页在用）。
     *
     * 为什么需要它：省电广播到达顺序与"写完网络"的先后没有保证，
     * 页面如果只靠自己的广播接收者刷新，会出现"允许制式已限 4G，但 4G 是否生效仍显示未生效"
     * 这种自相矛盾的画面（真机实测到过）。状态一改就通知，页面直接从权威值重画，不再赌时序。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    // ---------------- 生命周期 ----------------

    /**
     * 挂到常驻进程上（由 [KeepAliveService.onCreate] 调用）。
     *
     * 幂等：重复 attach 不会注册第二个接收者。挂上之后立刻做一次对账——
     * 进程是在"省电已经开着"之后才起来的场景很常见（开机、被杀后重建），
     * 那时不会有任何广播，只有对账能收敛到正确状态。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        host = app
        logCtx = app
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                        reconcile(app, "省电模式变化")
                    }
                }
            }
            try {
                // 该广播由 system_server 发出，NOT_EXPORTED 即可收到（不必暴露给第三方应用）。
                // targetSdk 35 要求显式声明导出性；低于 33 的系统没有这个重载，退回两参数版本。
                val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(r, filter)
                }
                receiver = r
            } catch (t: Throwable) {
                Log.w(TAG, "省电广播注册失败", t)
                log("省电广播注册失败：$t")
            }
        }
        reconcile(app, "服务启动")
    }

    /** 从常驻进程摘除（[KeepAliveService.onDestroy]）。 */
    fun detach() {
        val r = receiver ?: return
        try {
            host?.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
        receiver = null
        host = null
    }

    /** 首页开关变化后调用。关闭时立即还原，不等下一次广播。 */
    fun onSwitchChanged(context: Context) {
        reconcile(context, "开关变化")
    }

    /** 二级页订阅状态变化，回调在线程池线程上，调用方自己切主线程。 */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /**
     * 对账：以"开关 && 当前省电状态"为唯一目标态，把制式推过去。
     *
     * 之所以每个入口都走同一个函数（而不是"开关打开就切、广播关就还原"两套逻辑），
     * 是因为广播会丢：进程被杀、Doze、省电模式由充电状态自动进出，都可能漏。
     * 只有"每次都按当前事实收敛"才能自愈。
     *
     * 所有 shell 操作都在 [ShizukuShell] 的单线程队列里串行执行，
     * 因此连续多次对账只会按顺序收敛，不会交叉写坏。
     */
    fun reconcile(context: Context, reason: String) {
        val app = context.applicationContext
        logCtx = app
        ShizukuShell.submit {
            try {
                doReconcile(app, reason)
            } catch (t: Throwable) {
                Log.w(TAG, "对账异常", t)
                log("对账异常（$reason）：$t")
            }
        }
    }

    private fun doReconcile(app: Context, reason: String) {
        val prefs = Prefs(app)

        // 功能关着且没有遗留改写 → 什么都不做（绝大多数广播走这里）
        if (!prefs.saver4gEnabled && !prefs.cell4gActive) return

        if (!ShizukuShell.hasPermission()) {
            note(prefs, "失败：Shizuku 未连接（$reason）")
            return
        }

        val saverOn = try {
            app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        } catch (_: Throwable) {
            false
        }
        val want4g = prefs.saver4gEnabled && saverOn

        if (want4g) {
            if (prefs.cell4gActive) return
            val state = readCellState()
            if (state == null) {
                note(prefs, "失败：读不到当前制式（$reason）")
                return
            }
            if (state.mask and NR_BIT == 0L) {
                // 已经是 4G 优先（用户自己设过，或系统在省电时已经降过）。
                // 不记录还原点：退出省电时由系统自己的策略恢复，本应用不该插手。
                prefs.cell4gActive = false
                note(prefs, "已限 4G，无需改动（$reason）")
                return
            }
            val target = state.mask and NR_BIT.inv()
            if (writeMask(state.slot, target)) {
                prefs.cell4gOriginalMask = state.mask
                prefs.cell4gWrittenMask = target
                prefs.cell4gSlot = state.slot
                prefs.cell4gActive = true
                note(prefs, "已限 4G（$reason）")
                KeepAliveService.refresh()
            } else {
                note(prefs, "失败：写入未生效（$reason）")
            }
            return
        }

        // 目标态是"允许 5G"，但当前仍处于我们改过的状态 → 还原
        if (!prefs.cell4gActive) return
        val original = prefs.cell4gOriginalMask
        if (original <= 0L) {
            prefs.cell4gActive = false
            note(prefs, "还原跳过：缺少原始掩码（$reason）")
            return
        }
        // 优先用当初改写的那张卡：期间换过卡时，按"我改过谁就还原谁"处理
        val slot = if (prefs.cell4gSlot >= 0) prefs.cell4gSlot else 0
        if (writeMask(slot, original)) {
            prefs.cell4gActive = false
            note(prefs, "已还原 5G（$reason）")
            KeepAliveService.refresh()
        } else {
            // 还原失败时必须保留 active：下次任何入口都会再试一次，
            // 否则会永远停在 4G 且系统里再没有"我欠一次还原"的记录。
            note(prefs, "失败：还原未生效（$reason）")
        }
    }

    // ---------------- shell 侧 ----------------

    /**
     * 读系统侧真值：默认数据卡的卡槽 + 允许制式掩码。
     *
     * 数据源是 `dumpsys isub`：`defaultDataSubId` 定位默认数据卡，
     * 同 subId 的 `SubscriptionInfoInternal` 块里取 `simSlotIndex` 与
     * `allowedNetworkTypesForReasons=user=<整数>`。
     *
     * 取不到就返回 null，让上层记一条失败——**不要**猜一个槽位蒙着写：
     * 写到非数据卡上，用户完全看不出发生了什么（网络没变、但设置被改了）。
     */
    private fun readCellState(): CellState? {
        val out = ShizukuShell.execSync("dumpsys isub") ?: return null
        val subId = Regex("""defaultDataSubId=(-?\d+)""")
            .find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (subId <= 0) return null
        for (block in out.split("SubscriptionInfoInternal:")) {
            // `\bid=` 才不会误匹配 carrierId= 这类尾巴
            val id = Regex("""\bid=(-?\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (id != subId) continue
            val slot = Regex("""simSlotIndex=(-?\d+)""")
                .find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val mask = Regex("""allowedNetworkTypesForReasons=user=(\d+)""")
                .find(block)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (slot < 0) continue
            return CellState(slot = slot, subId = subId, mask = mask)
        }
        return null
    }

    /**
     * 写掩码并回读校验。返回 true 表示"系统侧确认已是该值"。
     *
     * `completed` 只代表命令执行完，不代表 modem 接受了这组制式，所以必须回读。
     * 回读留一次短重试：`isub` 的属性是由 phone 进程异步落库的，实测约 1 秒内可见，
     * 偶尔比命令返回慢一拍。
     */
    private fun writeMask(slot: Int, mask: Long): Boolean {
        val bin = java.lang.Long.toBinaryString(mask)
        val out = ShizukuShell.execSync("$SET_CMD -s $slot $bin") ?: return false
        if (!out.contains("completed")) {
            log("写入被拒（slot=$slot）：${out.trim().take(120)}")
            return false
        }
        repeat(2) { attempt ->
            val readBack = readCellState()
            if (readBack?.mask == mask) return true
            log("回读不一致（第${attempt + 1}次）：want=$mask got=${readBack?.mask}")
            if (attempt == 0) {
                // 本机 postDelayed 不可靠，但这里是工作线程，sleep 不影响界面
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    /**
     * 给二级页取权威值：允许制式（命令，可读性）+ 实测制式（dumpsys）。
     *
     * 同一件事有两个来源是有意的——命令读的是"策略"，dumpsys 读的是"事实"，
     * 只有两者一致时才能说"4G 真的生效了"。
     */
    fun queryNetInfo(onResult: (NetInfo?) -> Unit) {
        ShizukuShell.submit {
            val state = readCellState()
            val slot = state?.slot ?: 0
            val allowed = ShizukuShell.execSync("$GET_CMD -s $slot")?.trim()?.lines()
                ?.firstOrNull { it.isNotBlank() }?.trim()
            val rat = ShizukuShell.execSync(RAT_CMD)?.trim()?.lines()
                ?.firstOrNull { it.isNotBlank() }?.trim()
            onResult(
                if (state == null) null
                else NetInfo(
                    slot = slot,
                    allowed = allowed ?: "—",
                    rat = rat ?: "—",
                    nrAllowed = state.mask and NR_BIT != 0L
                )
            )
        }
    }

    /** 制式原始串转人话，如 `20(NR_SA)` → `5G · NR_SA`。 */
    fun prettyRat(raw: String): String {
        val m = Regex("""(\d+)\(([A-Z_0-9]+)\)""").find(raw) ?: return raw
        val tech = m.groupValues[2]
        val label = when (tech) {
            "NR_SA", "NR_NSA", "NR" -> "5G"
            "LTE", "LTE_CA" -> "4G"
            "HSPAP", "HSPA", "HSDPA", "HSUPA" -> "3G+"
            "UMTS", "TD_SCDMA" -> "3G"
            "GSM", "GPRS", "EDGE" -> "2G"
            else -> ""
        }
        return if (label.isEmpty()) tech else "$label · $tech"
    }

    // ---------------- 杂项 ----------------

    private fun note(prefs: Prefs, text: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        prefs.cell4gLastEvent = "$stamp $text"
        log(text)
        listeners.forEach { cb ->
            try {
                cb()
            } catch (_: Throwable) {
            }
        }
    }

    private fun log(msg: String) {
        try {
            val dir = (logCtx ?: host)?.filesDir ?: return
            val f = File(dir, "net.log")
            if (f.length() > 60_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} $msg\n")
        } catch (_: Throwable) {
        }
    }

    private const val GET_CMD = "cmd phone get-allowed-network-types-for-users"
    private const val SET_CMD = "cmd phone set-allowed-network-types-for-users"
    private const val RAT_CMD =
        "dumpsys telephony.registry | grep -oE 'getRilDataRadioTechnology=[0-9]+\\([A-Z_]+\\)' | head -1"
}
