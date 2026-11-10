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
     * 注视采样是否已经记过一次“诊断行”。
     *
     * 为什么要这个：这台机器的 logcat 会整段停摆（手册 §3.4），`AttentionProbe` 里的
     * `Log.d` 根本读不到，于是“注视到底在不在跑”成了一个无法观察的黑盒。
     * 改成往 `watch.log` 记**首次结果**（成功或取不到各一行），只记一次，
     * 不会因为每天 960 次采样把日志文件滚掉。
     */
    private var gazeDiagLogged = false

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

    /**
     * 到点了却因为无障碍没连上而弹不出浮窗。
     *
     * ESR 原版遇到这种情况会**另发一条通知**告诉用户"无法显示休息浮窗"。
     * SwissKnife 的约定是只保留一条常驻通知，所以改成让那条通知的护眼段说这件事——
     * 但一定要说出来：否则用户只会觉得"这功能偶尔不灵"，而真正的原因是权限掉了。
     */
    private var blockedByGuard = false

    private val dueRunnable = Runnable { onThresholdReached() }

    /**
     * 注视采样循环。仅在屏幕亮着时运行，熄屏立即停掉。
     * 单次采样要几秒，所以用「完成后再排下一次」而不是固定节拍，避免任务堆积。
     */
    private val gazeRunnable = object : Runnable {
        override fun run() {
            val app = ctx ?: run { gazeRunning = false; return }
            // 跨进夜间就停止采样，不再排下一轮（白天由 onServiceTick 重新拉起）
            if (QuietHours.now()) {
                gazeRunning = false
                return
            }
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
            // 照 ESR 的写法**强制重挂**注视采样链：只置个 flag 是不够的——
            // 熄灭期间若有一步 post 被系统丢掉，flag 仍是 true，链却已经断了，
            // 之后永远不再采样（现象：注视暂停再也恢复不了）。
            stopGaze()
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
        val p = Prefs(app)
        if (!p.eyeBreakEnabled) return
        val t = timer
        if (t == null) {
            startInternal(app)
            return
        }
        // 照 ESR 的 onStartCommand 做法：每次心跳把参数对齐一次。
        // 少了这一步，用户在页面上改阈值/浮窗秒数不会生效——SessionTimer 的阈值是字段，
        // 不会自己回读 Prefs（早先这里写过"reschedule 会重读"的注释，是错的）。
        t.thresholdMs = p.eyeThresholdMs
        t.resetOnScreenOffMs = p.eyeResetOnScreenOffMs
        t.gazeTimeoutMs = p.eyeGazeTimeoutMs
        // 先对账再重排：看门狗每 30 秒来一次，正好用来纠正"广播丢了导致的屏幕状态错位"
        reconcileScreen()
        reschedule()
        // 夜间/白天切换靠心跳对齐：夜里不起采样链，白天重新拉起；
        // 已经处于"暂停计时"的，进夜间立刻解冻（用户定的是“这段时间亮屏就计时”）。
        if (QuietHours.now()) {
            if (gazeRunning) stopGaze()
            if (gazeSuspended) {
                gazeSuspended = false
                notAttentiveStreak = 0
                t.setGazeSuspended(Timebase.now(), false)
                log("夜间时段：不判注视，恢复计时")
                KeepAliveService.refresh()
            }
        } else if (isScreenOn(app) && !gazeRunning) {
            startGaze()
        }
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
        blockedByGuard = false
        deferUntil = 0L
        timer = null
    }

    /** 到点了却因为无障碍没连上而弹不出浮窗（页面用来把状态说清楚）。 */
    fun isBlockedByGuard(): Boolean = blockedByGuard

    // ---------------- 供界面使用 ----------------

    /** 计时快照；服务没在跑（功能关闭）时返回 null。 */
    fun snapshot(): SessionTimer.Snapshot? {
        // 和 ESR 一样是**纯读**：getter 里不做排表、不发通知、不改状态。
        // 早先版本在这里顺手做过对账，结果页面每秒刷新都会触发一次状态机推进，
        // 副作用出现在"读"里，一旦出问题很难查。对账挪到 onServiceTick / reschedule。
        return timer?.snapshot(Timebase.now())
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

    // 2026-11-09：常驻通知改为「摘要 + 异常优先」，见 KeepAliveService.notificationText()。
    // 原来的 notificationSegment() 会返回「已用 N 分钟」，数字自己会走 ⇒ 每分钟一次 notify，
    // 纯属浪费，已移除；需要看进度就进应用首页，那里有每个功能的状态行。
    // 同时移除的 isGuardEnabled() 用 AccessibilityManager 查「启用列表」，
    // 但它分不清「已启用」和「已连接」；通知里改用 ForegroundWatcherService.isRunning()，
    // 僵尸态（启用但没连上）也能如实报出来。

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
        if (attentive == null) {
            if (!gazeDiagLogged) {
                gazeDiagLogged = true
                log("注视采样：取不到结果（Shizuku 未授权或 AON 未就绪）")
            }
            return
        }
        if (!gazeDiagLogged) {
            gazeDiagLogged = true
            log("注视采样：首次成功 attentive=$attentive")
        }
        if (attentive) {
            notAttentiveStreak = 0
            if (gazeSuspended) {
                gazeSuspended = false
                timer?.setGazeSuspended(Timebase.now(), false)
                reschedule()
                log("注视：恢复计时")
                KeepAliveService.refresh()
            }
        } else {
            notAttentiveStreak++
            if (!gazeSuspended && notAttentiveStreak >= NOT_ATTENTIVE_LIMIT) {
                gazeSuspended = true
                timer?.setGazeSuspended(Timebase.now(), true)
                main.removeCallbacks(dueRunnable)
                log("注视：连续 ${notAttentiveStreak} 次没在看，暂停计时")
                KeepAliveService.refresh()
            }
        }
    }

    /**
     * 常驻通知里的「护眼」段（2026-11-09 用户要求加回）。
     *
     * 用户明确要知道两件事：**已经用了多久**、**是不是因为没检测到注视而暂停**。
     * 代价是分钟数会自己走 ⇒ **跨整分钟时通知会重发一次**（无声、最低优先级），
     * 这是用户权衡后的取舍，不是回退。要降频只能把粒度改粗（如 5 分钟一跳），改一处即可。
     *
     * 措辞刻意压到最短：它前面有「N 项运行中」，后面可能还跟一条异常，
     * 折叠态一行只放得下约 22 个汉字。
     *
     * **无障碍未连接这类异常不在这里报**，由 [KeepAliveService] 统一出一条，
     * 避免同一件事在通知里说两遍。
     */
    fun notificationSegment(context: Context): String? {
        val p = prefs ?: return null
        if (!p.eyeBreakEnabled) return null
        if (isOverlayShowing()) return context.getString(R.string.eb_notif_seg_break)
        val snap = timer?.snapshot(Timebase.now()) ?: return null
        val usedMin = (snap.effectiveMs / 60_000L).toInt()
        return when {
            snap.gazeSuspended -> context.getString(R.string.eb_notif_seg_gaze_paused, usedMin)
            !snap.accumulating && !isScreenOn(context) ->
                context.getString(R.string.eb_notif_seg_screen_off, usedMin)
            else -> context.getString(R.string.eb_notif_seg_used, usedMin)
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
            // ESR 原版在这里弹一条"无法显示休息浮窗"的通知；这里改成让常驻通知如实报状态
            blockedByGuard = true
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
        blockedByGuard = false
        lastBreakAt = SystemClock.elapsedRealtime()
        acquireScreenLock(app, seconds)
        KeepAliveService.refresh()
        log("休息浮窗已显示 ${seconds}s")
        return true
    }

    private fun endBreak() {
        suspended = false
        blockedByGuard = false
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
        // 只在"本来就要算一次"的入口对账；这不会引入额外唤醒
        reconcileScreen()
        val snap = t.snapshot(Timebase.now())
        // 熄屏时 onScreenOff 会把 phase 置 IDLE，accumulating 自然为 false——
        // 这里**不要再加自己的屏幕判断**。多加一道"我记得屏幕是灭的"闸门，
        // 一旦那个标记过期（漏一次亮屏广播），就会永远不排表，表现为"到点不弹"。
        // 状态判断只留一个来源：timer 自己。
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
            ForegroundWatcherService.instance?.dismissBreak("功能关闭或服务停止")
        } catch (_: Throwable) {
        }
    }

    // ---------------- 注视采样 ----------------

    private fun startGaze() {
        // 开关关着时一次都不采样：注视检测要走 Shizuku 起传感器，代价不低
        if (prefs?.eyeBreakEnabled != true) return
        // 夜间静默时段（22:00–08:00）：光照低、AON 判不准，整条注视链不启动，
        // 亮屏就计时。顺带省掉每 30 秒两个 sh 进程。规则见 [QuietHours]。
        if (QuietHours.now()) return
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
