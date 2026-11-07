package com.swissknife.esr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.Prefs
import com.swissknife.keepalive.KeepAliveService
import java.io.File

/**
 * 「护眼休息」的执行体：累计用眼时长，到点弹出全屏休息浮窗。
 *
 * ## 与独立 ESR 的对应关系
 *
 * 这段逻辑原来在 EyeStrainRelief 的 `MonitorService`（一个前台服务）里，
 * 2026-11-07 嵌入 SwissKnife 时**拆掉了服务外壳**：前台服务的职责由框架层的
 * [KeepAliveService] 承担，业务体挂到它的 onCreate/onDestroy 上（与 `Saver4G` 同一套模式）。
 * 这样不会出现"每个功能一个常驻前台服务"的复制。
 *
 * ## 浮窗由谁画
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` 窗口只能由无障碍服务添加，所以浮窗的生命周期挂在
 * [ForegroundWatcherService]（本应用唯一的无障碍服务）上，本类只负责"什么时候该弹"。
 * 这也是为什么本功能**必须**开无障碍，而「最低亮度守护」不需要。
 *
 * ## 功耗
 *
 * 全类只有一个「到期定时器」，且只在计时真正向前推进时存在；熄屏/浮窗期间立即移除。
 * 注视采样是 30 秒一次的串行链（完成后再排下一次，不堆积）。除此之外没有轮询、
 * 没有周期任务、没有常驻 WakeLock（浮窗期间的屏幕常亮锁带超时，结束立刻释放）。
 *
 * ## 一个必须知道的坑
 *
 * 本机 Handler 的长时间 `postDelayed` 会被系统丢弃（安卓手册 §4.14 第三节，实测只执行一次）。
 * 因此到期定时器**靠看门狗兜底**：[onServiceTick] 由 KeepAliveService.onStartCommand 调用
 * （看门狗 30 秒一次拉起），每次重新按剩余时长挂表。ESR 独立版在同一台机器上是同一机制，
 * 行为一致；最坏情况触发延迟 ≈ 看门狗间隔。
 */
object EyeBreak {

    /** 注视采样间隔 */
    private const val GAZE_INTERVAL_MS = 30_000L

    /** 连续几次判定没在看才冻结计时 */
    private const val NOT_ATTENTIVE_LIMIT = 2

    /** 到点但没人在看时，推迟多久再判 */
    private const val PRESENCE_RECHECK_MS = 3 * 60_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var prefs: Prefs? = null

    @Volatile
    private var timer: SessionTimer? = null

    private var receiverRegistered = false

    /** 浮窗正在显示中：此时到期定时器不该存在 */
    private var suspended = false

    /** 浮窗存续期间的屏幕常亮锁（FLAG_KEEP_SCREEN_ON 对无障碍浮窗无效，只能用 WakeLock） */
    private var screenLock: PowerManager.WakeLock? = null

    /** 连续判定「没人在看」的次数 */
    private var notAttentiveStreak = 0
    private var gazeSuspended = false
    private var gazeRunning = false

    private val dueRunnable = Runnable { onThresholdReached() }

    /**
     * 注视采样循环。仅在屏幕亮着时运行，熄屏立即停掉。
     * 单次采样要几秒，所以用「完成后再排下一次」而不是固定节拍，避免任务堆积。
     */
    private val gazeRunnable = object : Runnable {
        override fun run() {
            val app = ctx ?: run { gazeRunning = false; return }
            if (!isScreenOn(app)) {
                gazeRunning = false
                return
            }
            val self = this
            AttentionProbe.checkAsync { attentive ->
                main.post {
                    val still = ctx
                    if (still == null) {
                        gazeRunning = false
                        return@post
                    }
                    onGazeSample(attentive)
                    if (isScreenOn(still)) {
                        main.postDelayed(self, GAZE_INTERVAL_MS)
                    } else {
                        gazeRunning = false
                    }
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = ctx ?: return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val now = Timebase.now()
                    // 亮屏本身就是「有人在操作」的强证据，先清掉注视暂停，
                    // 否则回来之后要等下一次采样才有 60 秒的计时空窗。
                    notAttentiveStreak = 0
                    gazeSuspended = false
                    timer?.setGazeSuspended(now, false)
                    timer?.onScreenOn(now)
                    reschedule()
                    // 屏幕亮了，恢复注视采样
                    startGaze()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    timer?.onScreenOff(Timebase.now())
                    // 熄屏后计时停止，撤掉定时器，不做任何定时唤醒
                    main.removeCallbacks(dueRunnable)
                    stopGaze()
                }
            }
        }
    }

    // ---------------- 生命周期（由 KeepAliveService 驱动） ----------------

    /**
     * 挂到常驻进程上。开关关着就什么都不做——本类的全部开销都建立在
     * 「用户确实开了这个功能」之上。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        val p = Prefs(app)
        ctx = app
        prefs = p
        if (!p.eyeBreakEnabled) return
        startInternal(app)
    }

    /** 从常驻进程摘除：撤定时器、注销广播、释放屏幕锁、收掉浮窗。 */
    fun detach() {
        main.removeCallbacks(dueRunnable)
        stopGaze()
        unregisterScreenReceiver()
        releaseScreenLock()
        dismissOverlay()
        timer = null
        prefs = null
        ctx = null
        suspended = false
    }

    /**
     * 首页开关变化后调用。
     *
     * 开启：以"现在"为起点重新计时（关闭时已清零，语义见「怎么用」）。
     * 关闭：清零计时、撤掉所有定时器、立刻收掉浮窗——不留任何后台活动。
     */
    fun onSwitchChanged(context: Context) {
        val app = context.applicationContext
        val p = Prefs(app)
        ctx = app
        prefs = p
        if (p.eyeBreakEnabled) {
            startInternal(app)
        } else {
            stopInternal()
        }
    }

    /**
     * 看门狗心跳：KeepAliveService.onStartCommand 每次被拉起时调用一次。
     *
     * 作用是重挂到期定时器——本机长时间 postDelayed 会被丢弃（见类注释），
     * 没有这个兜底，20 分钟后的那次触发可能永远不来。
     */
    fun onServiceTick() {
        val app = ctx ?: return
        if (!Prefs(app).eyeBreakEnabled) return
        if (timer == null) {
            startInternal(app)
            return
        }
        reschedule()
    }

    private fun startInternal(app: Context) {
        val p = Prefs(app)
        prefs = p
        if (!receiverRegistered) registerScreenReceiver(app)

        val screenOn = isScreenOn(app)
        val t = timer
        if (t == null) {
            // 首次启动：按当前真实屏幕状态起算
            val fresh = SessionTimer(
                thresholdMs = p.eyeThresholdMs,
                resetOnScreenOffMs = p.eyeResetOnScreenOffMs,
                gazeTimeoutMs = p.eyeGazeTimeoutMs,
            )
            fresh.init(Timebase.now(), screenOn)
            timer = fresh
        } else {
            // 阈值可能被改过：跟随最新设置，不清零已有进度
            t.thresholdMs = p.eyeThresholdMs
            t.resetOnScreenOffMs = p.eyeResetOnScreenOffMs
            t.gazeTimeoutMs = p.eyeGazeTimeoutMs
        }
        reschedule()
        if (screenOn) startGaze()
    }

    private fun stopInternal() {
        main.removeCallbacks(dueRunnable)
        stopGaze()
        // 广播也要注销：否则关掉开关后每次亮灭屏仍会走一遍接收者
        // （虽然 timer 为 null 时都是空操作，但"关掉就该彻底静默"是硬要求）
        unregisterScreenReceiver()
        releaseScreenLock()
        dismissOverlay()
        suspended = false
        notAttentiveStreak = 0
        gazeSuspended = false
        timer = null
    }

    // ---------------- 供界面使用 ----------------

    /** 计时快照；服务没在跑（功能关闭）时返回 null。 */
    fun snapshot(): SessionTimer.Snapshot? {
        val t = timer ?: return null
        return t.snapshot(Timebase.now())
    }

    fun isRunning(): Boolean = timer != null

    /** 浮窗是否正在显示（权威来源是无障碍服务那边的窗口状态）。 */
    fun isOverlayShowing(): Boolean = ForegroundWatcherService.instance?.isBreakShowing() == true

    /** 界面「重置计时」。 */
    fun resetCounter() {
        timer?.reset()
        notAttentiveStreak = 0
        gazeSuspended = false
        timer?.setGazeSuspended(Timebase.now(), false)
        reschedule()
        KeepAliveService.refresh()
    }

    /** 界面「立即测试浮窗」：不等阈值，直接弹一次。 */
    fun triggerNow(): Boolean {
        main.removeCallbacks(dueRunnable)
        return showBreakWithGuard()
    }

    /**
     * 常驻通知里的状态段。内容没变时 [KeepAliveService] 不会重复 notify，
     * 所以这里可以放心每次都被调用。
     */
    fun notificationSegment(context: Context): String? {
        val p = prefs ?: return null
        if (!p.eyeBreakEnabled) return null
        if (suspended || isOverlayShowing()) return context.getString(R.string.eb_notif_break)
        val snap = timer?.snapshot(Timebase.now()) ?: return null
        val usedMin = (snap.effectiveMs / 60_000L).toInt()
        return when {
            snap.gazeSuspended -> context.getString(R.string.eb_notif_gaze_paused, usedMin)
            snap.accumulating -> context.getString(R.string.eb_notif_used, usedMin)
            else -> context.getString(R.string.eb_notif_screen_off, usedMin)
        }
    }

    // ---------------- 到期与浮窗 ----------------

    private fun onThresholdReached() {
        main.removeCallbacks(dueRunnable)
        // 到点先确认人在不在，避免对着空屏弹窗。取不到结果就照常提醒。
        AttentionProbe.checkAsync { attentive ->
            main.post {
                if (attentive == false) {
                    main.postDelayed(dueRunnable, PRESENCE_RECHECK_MS)
                } else if (!showBreakWithGuard()) {
                    timer?.reset()
                    reschedule()
                }
            }
        }
    }

    /**
     * 处理一次注视采样结果。
     * 连续两次判定「没在看」才冻结计时，单次抖动不算，避免用户低头看一眼键盘就被停表。
     * 任何一次判定「在看」立刻恢复。
     */
    private fun onGazeSample(attentive: Boolean?) {
        if (attentive == null) return
        if (attentive) {
            notAttentiveStreak = 0
            if (gazeSuspended) {
                gazeSuspended = false
                timer?.setGazeSuspended(Timebase.now(), false)
                reschedule()
                KeepAliveService.refresh()
            }
        } else {
            notAttentiveStreak++
            if (!gazeSuspended && notAttentiveStreak >= NOT_ATTENTIVE_LIMIT) {
                gazeSuspended = true
                timer?.setGazeSuspended(Timebase.now(), true)
                main.removeCallbacks(dueRunnable)
                KeepAliveService.refresh()
            }
        }
    }

    /**
     * 显示浮窗。
     *
     * 不做系统级锁定：`am task lock` 在澎湃OS4 上会连带锁屏（LockTask 内部调用 lockNow），
     * 有密码锁的设备无法自动恢复，实测会导致浮窗结束后停在锁屏。
     * 改为浮窗期间由无障碍服务把窗口重新置顶（见 ForegroundWatcherService）。
     */
    private fun showBreakWithGuard(): Boolean {
        val app = ctx ?: return false
        val guard = ForegroundWatcherService.instance
        if (guard == null) {
            log("无法显示休息浮窗：无障碍服务未连接")
            timer?.reset()
            reschedule()
            KeepAliveService.refresh()
            return false
        }
        val seconds = Prefs(app).eyeOverlaySeconds
        suspended = true
        val shown = guard.showBreak(seconds) { endBreak() }
        if (!shown) {
            suspended = false
            reschedule()
            return false
        }
        acquireScreenLock(app, seconds)
        KeepAliveService.refresh()
        log("休息浮窗已显示 ${seconds}s")
        return true
    }

    private fun endBreak() {
        suspended = false
        releaseScreenLock()
        // 浮窗结束说明用户回来了，清掉注视暂停状态并重新计时
        notAttentiveStreak = 0
        gazeSuspended = false
        timer?.setGazeSuspended(Timebase.now(), false)
        // 浮窗期间属于休息，不计入使用时长
        timer?.reset()
        reschedule()
        KeepAliveService.refresh()
        log("休息结束，重新计时")
    }

    /**
     * 重新挂载唯一的那一个到期定时器。
     * 只在「正在累积」时才存在，其余时刻不存在任何待执行的延迟任务。
     */
    private fun reschedule() {
        main.removeCallbacks(dueRunnable)
        if (suspended) return
        val t = timer ?: return
        val snap = t.snapshot(Timebase.now())
        if (!snap.accumulating) return
        if (snap.remainingMs <= 0L) {
            main.post(dueRunnable)
            return
        }
        main.postDelayed(dueRunnable, snap.remainingMs)
    }

    private fun acquireScreenLock(app: Context, seconds: Int) {
        try {
            releaseScreenLock()
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "SwissKnife:break"
            ).apply {
                setReferenceCounted(false)
                acquire((seconds + 15) * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseScreenLock() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        screenLock = null
    }

    private fun dismissOverlay() {
        try {
            ForegroundWatcherService.instance?.dismissBreak()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 注视采样 ----------------

    private fun startGaze() {
        // 开关关着时一次都不采样：注视检测要走 Shizuku 起传感器，代价不低
        if (prefs?.eyeBreakEnabled != true) return
        if (gazeRunning) return
        gazeRunning = true
        main.removeCallbacks(gazeRunnable)
        main.post(gazeRunnable)
    }

    private fun stopGaze() {
        gazeRunning = false
        main.removeCallbacks(gazeRunnable)
    }

    // ---------------- 屏幕状态 ----------------

    private fun isScreenOn(app: Context): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun registerScreenReceiver(app: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            ContextCompat.registerReceiver(
                app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (t: Throwable) {
            log("屏幕广播注册失败：$t")
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            ctx?.unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        receiverRegistered = false
    }

    private fun log(msg: String) {
        try {
            val dir = ctx?.filesDir ?: return
            val f = File(dir, "watch.log")
            if (f.length() > 120_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} [护眼] $msg\n")
        } catch (_: Throwable) {
        }
    }
}
