package com.swissknife.esr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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

    /** 两次休息之间的最小间隔：挡掉过期到期消息造成的"连弹两次" */
    private const val MIN_BREAK_GAP_MS = 60_000L

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

    /**
     * 上一次确认过的屏幕状态。**这是修「息屏还在累计」的关键状态**：
     * 屏幕亮灭广播会丢（进程被冻结期间系统不补发已投递的动态广播），只靠广播就会永久错位——
     * 熄屏那段被当成亮屏累计进去，攒到阈值后一亮屏就弹窗。
     * 所以每个入口都拿 [PowerManager.isInteractive] 核对一次真实值（[reconcileScreen]）。
     */
    private var lastScreenOn = true

    /** 上一次真正弹出浮窗的时刻，用于挡掉过期/重复的到期消息（同一个休息不连弹两次）。 */
    private var lastBreakAt = 0L

    /**
     * 到点后判定「没人在看」时，把下次判定推迟到这个时刻。
     *
     * 必须自己记着，否则看门狗每次重排到期表都会立刻再弹一次，
     * 3 分钟的延后形同虚设（现象：对着空屏弹窗）。亮屏时清零。
     */
    private var deferUntil = 0L

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
            if (ctx == null) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> applyScreenState(true, Timebase.now())
                Intent.ACTION_SCREEN_OFF -> applyScreenState(false, Timebase.now())
            }
        }
    }

    /**
     * 把屏幕状态收敛到 [on]。广播与对账共用这一条路径，避免两处逻辑各写一半。
     *
     * 熄屏时**撤掉到期表**：熄屏期间弹窗毫无意义，还会用 WakeLock 把屏幕点亮
     * （实测就是"夜里手机自己亮、还发热"的来源）。
     */
    private fun applyScreenState(on: Boolean, now: Long) {
        lastScreenOn = on
        if (on) {
            // 亮屏本身就是「有人在操作」的强证据，先清掉注视暂停与"没人在看"的延后，
            // 否则回来之后要等下一次采样/延后到期才有反应。
            notAttentiveStreak = 0
            gazeSuspended = false
            deferUntil = 0L
            timer?.setGazeSuspended(now, false)
            // 这里顺带完成"熄屏时长是否超阈值 → 清零"的惰性判定
            timer?.onScreenOn(now)
            startGaze()
            // 亮屏这一刻才是"可能该休息"的正确时机：reschedule 会按剩余时长决定是否立刻触发
            reschedule()
        } else {
            timer?.onScreenOff(now)
            main.removeCallbacks(dueRunnable)
            stopGaze()
        }
    }

    /**
     * 屏幕状态对账。
     *
     * 只读一次 `PowerManager.isInteractive`，不注册定时器、不产生任何额外唤醒——
     * 它被挂在"本来就要跑"的入口上（看门狗心跳、页面/通知取快照、重排到期表）。
     * 状态没变时立即返回，代价是一次布尔比较。
     */
    private fun reconcileScreen(now: Long = Timebase.now()) {
        val app = ctx ?: return
        val on = isScreenOn(app)
        if (on == lastScreenOn) return
        lastScreenOn = on
        log(if (on) "对账：屏幕已亮（补一次亮屏处理）" else "对账：屏幕已灭（补一次熄屏处理）")
        applyScreenState(on, now)
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
        // 先对账再重排：看门狗每 30 秒来一次，正好用来纠正"广播丢了导致的屏幕状态错位"
        reconcileScreen()
        reschedule()
    }

    private fun startInternal(app: Context) {
        val p = Prefs(app)
        prefs = p
        if (!receiverRegistered) registerScreenReceiver(app)

        val screenOn = isScreenOn(app)
        // 记录基线，之后的每一次对账都以它为准判断"屏幕状态到底变过没有"
        lastScreenOn = screenOn
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
        // 页面每秒刷一次、通知每 30 秒取一次，借这个"本来就要跑"的时机对账，
        // 不必为它单独注册任何定时器。
        reconcileScreen()
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
        val app = ctx ?: return
        val now = SystemClock.elapsedRealtime()
        // 上一次判定"没人在看"，延后还没到：什么都不做，等那一刻再判
        if (deferUntil > now) {
            main.postDelayed(dueRunnable, deferUntil - now)
            return
        }
        reconcileScreen()
        if (!isScreenOn(app)) {
            // 熄屏时不弹：弹了只会用 WakeLock 把屏幕点亮（夜里手机自己亮 + 发热的来源），
            // 而且对着空屏休息没有任何意义。亮屏那一刻 applyScreenState 会重新评估。
            log("到点但屏幕已灭，延后到下次亮屏再判")
            return
        }
        val snap = timer?.snapshot(Timebase.now()) ?: return
        // 两道闸：① 只有真的到阈值才弹（挡掉过期消息）；② 距离上次弹窗不到 1 分钟不弹（挡连弹）
        if (snap.remainingMs > 0L) return
        if (lastBreakAt > 0L && now - lastBreakAt < MIN_BREAK_GAP_MS) {
            log("距上次休息不足 ${MIN_BREAK_GAP_MS / 1000}s，跳过这次到期")
            return
        }
        // 到点先确认人在不在，避免对着空屏弹窗。取不到结果就照常提醒。
        AttentionProbe.checkAsync { attentive ->
            main.post {
                if (attentive == false) {
                    deferUntil = SystemClock.elapsedRealtime() + PRESENCE_RECHECK_MS
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
        // 熄屏一律不弹：只有亮着屏看手机才需要休息，也才不会点亮屏幕额外耗电
        if (!isScreenOn(app)) {
            log("熄屏中，不显示休息浮窗")
            return false
        }
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
        lastBreakAt = SystemClock.elapsedRealtime()
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
        reconcileScreen()
        // 熄屏期间不留到期表：那张表到点也只会被 onThresholdReached 挡回去，
        // 留着等于白占一个延迟消息（还可能把进程从冻结里叫醒）。亮屏时会重新排。
        if (!lastScreenOn) return
        val snap = t.snapshot(Timebase.now())
        if (!snap.accumulating) return
        if (snap.remainingMs <= 0L) {
            val now = SystemClock.elapsedRealtime()
            if (deferUntil > now) {
                // "没人在看"的延后还没到：按剩余延后时间重排，不要立刻弹
                main.postDelayed(dueRunnable, deferUntil - now)
                return
            }
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
            // 这条日志是排查"夜里手机自己亮/发热"的关键证据：
            // 正常只应在屏幕亮着、人在看的时候出现一次。
            log("获取屏幕常亮锁 ${seconds + 15}s（屏幕亮=${isScreenOn(app)}）")
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
