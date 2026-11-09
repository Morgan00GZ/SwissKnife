package com.swissknife.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.swissknife.MainActivity
import com.swissknife.R
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.esr.BrightGuard
import com.swissknife.esr.EyeBreak
import com.swissknife.net.Saver4G

/**
 * 常驻保活服务（前台服务）——整个 SwissKnife 框架的进程锚点。
 *
 * ## 为什么悬浮球需要它
 *
 * 球挂在无障碍服务里，而无障碍服务虽然由系统托管，**承载它的进程依然是普通应用进程**。
 * 澎湃OS 会把长期没有前台服务的应用降为 cached 并冻结/回收；进程一停，球跟着消失，
 * 用户看到的现象是"球莫名其妙没了"，且没有任何报错可查——这类"静默失效"最难排查。
 * 前台服务把进程的 oom 优先级抬到 perceptible 档，是最直接的一层保护。
 *
 * ## 保活五层（与 EyeStrainRelief 同一套，见安卓手册 §4.9）
 *
 * | 层 | 机制 | 落点 |
 * |---|---|---|
 * | 1 | 前台服务 + START_STICKY | 本文件 |
 * | 2 | 无障碍服务看门狗（30 秒节流拉起） | ForegroundWatcherService.checkKeepAlive() |
 * | 3 | android:stopWithTask="false" | AndroidManifest |
 * | 4 | Doze 白名单（系统持久化，重启不丢） | 本文件 onCreate（Shizuku 可用时） |
 * | 5 | 开机 / 覆盖安装后重新拉起 | BootReceiver + 无障碍被系统重新绑定 |
 *
 * ## 只在"有功能在用"时常驻
 *
 * 与 ESR 的差别：ESR 需要 7×24 计时，必须无条件常驻；SwissKnife 没有功能开启时无事可做，
 * 常驻只会白占通知栏、白耗电。因此由 [sync] 按 [Prefs.anyFeatureEnabled] 决定起停。
 * 新增功能时只要把自己的开关并入 [Prefs.anyFeatureEnabled]，保活自动跟着走，不必改本文件。
 */
class KeepAliveService : Service() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    /** 上次推送的通知文案。文案没变就不重复 notify，避免看门狗每 30 秒刷一次系统。 */
    private var lastNotificationText: String? = null

    /**
     * `startForeground` 是否真的执行过（[onDestroy] 置回 false）。
     * 看门狗靠它决定“要不要再拉一次”——**只看实例存在不够**，实例可能正处在 stopSelf 的窗口里。
     */
    @Volatile
    private var foregroundStarted = false

    /**
     * 「亮度守护有没有写入通道」的缓存（2026-11-09 功耗审查）。
     * 看门狗每 30 秒重算一次通知文案，而测这个要问一次 ContentProvider（WRITE_SETTINGS）
     * 加一次 Shizuku binder。事件驱动的 [refresh] 会主动把缓存作废，所以刚授权完不会滞后。
     */
    @Volatile
    private var brightnessChannelCachedAt = 0L
    @Volatile
    private var brightnessChannelCached = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationText()))
        foregroundStarted = true

        // 功能侧的进程内监听挂在这里：本服务就是"有功能在用"时唯一的常驻组件，
        // 广播接收者跟着它的生死走，功能全关时不会有任何监听残留。
        // 各功能自己管自己（这里只挂一次钩子），不要把业务逻辑写进本文件。
        Saver4G.attach(this)
        // 「护眼休息」：计时、屏幕广播、注视采样、到期定时器
        EyeBreak.attach(this)
        // 「最低亮度守护」：亮度 ContentObserver（开关关着时内部直接返回，零开销）
        BrightGuard.attach(this)

        // 第 4 层：Doze 白名单。一条持久化的系统设置，重启不丢，比任何应用侧轮询都可靠。
        // 交给 Shizuku 执行；没授权就跳过——用户仍可在首页点「电池优化」手动放行。
        if (ShizukuShell.hasPermission()) {
            ShizukuShell.submit {
                ShizukuShell.execSync("cmd deviceidle whitelist +$packageName")
            }
        }
    }

    /**
     * 看门狗会周期性重复拉起本服务，这里只在参数真正变化时做重活。
     * START_STICKY：被系统杀掉后由系统重建，不需要用户再点一次应用。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被 START_STICKY 重建时，功能可能已经全部关闭（用户是在服务死亡的间隙关的），
        // 此时不要留下一个空转的常驻服务和一条无意义通知。
        if (!prefs.anyFeatureEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 看门狗每 30 秒会重新拉起本服务一次，这里顺带做两件"轻活"：
        // 1) 让「护眼休息」重挂到期定时器——本机长时间 postDelayed 会被丢弃（手册 §4.14 §3），
        //    没有这个兜底，20 分钟后那次触发可能永远不来；
        // 2) 刷新通知文案（内部比对文案，没变不会重复 notify）。
        EyeBreak.onServiceTick()
        refreshNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 顺序与 attach 相反：先摘功能钩子，再清单例
        EyeBreak.detach()
        BrightGuard.detach()
        Saver4G.detach()
        foregroundStarted = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 通知文案：**摘要 + 异常优先**（2026-11-09 改，替代原先「每个功能各说一句」）。
     *
     * ## 为什么改
     *
     * 旧写法把四个功能各自的状态拼起来，最长 59 个字符，而折叠态一行只放得下约 22 个汉字
     * —— 后半截直接被截掉，用户看到的是「4G 生效中 · 滑动 · 悬浮球在屏幕上」，
     * 护眼和亮度永远显示不出来。功能再多几个就彻底失效。另外护眼那段带「已用 N 分钟」，
     * 数字自己会走，导致**每分钟一次 notify**（时间驱动，纯属浪费）。
     *
     * ## 现在的规则
     *
     * 1. **不出现会自己变的数字**。文案只在真实状态变化时改写，时间流逝不会触发刷新。
     * 2. **正常态只报总数**：「3 项功能运行中」。内容太多本来就是错的，通知不是值班报表。
     * 3. **只有需要用户动手的事才占一段**，最多两条，按影响面排序：
     *    无障碍未连接（同时影响护眼与悬浮球）→ 亮度无写入权限 / 未设下限。
     * 4. 「4G 生效中」放最后：它是省电模式切换时才变的事件，频率低、信息价值高，
     *    但被截断时应该先丢它，让异常项露出来。
     *
     * 计数口径是**真正生效**的功能数（开关开着且必要条件满足），不是开关数——
     * 开了但没生效的会出现在异常段，不会被静默吞掉。
     *
     * 细节看应用的首页，那里每个功能都有状态行。
     */
    private fun notificationText(): String {
        val a11yOn = ForegroundWatcherService.isRunning()
        val hasChannel = hasBrightnessChannel()

        var running = 0
        if (prefs.saver4gEnabled) running++
        if (prefs.forceScrollEnabled && a11yOn) running++
        if (prefs.eyeBreakEnabled && a11yOn) running++
        if (prefs.brightGuardEnabled && prefs.minBrightnessValue > 0 && hasChannel) running++

        val alerts = ArrayList<String>(2)
        if ((prefs.forceScrollEnabled || prefs.eyeBreakEnabled) && !a11yOn) {
            alerts.add(getString(R.string.keep_notif_a11y_missing))
        }
        if (prefs.brightGuardEnabled) {
            when {
                prefs.minBrightnessValue <= 0 -> alerts.add(getString(R.string.bg_notif_no_min))
                !hasChannel -> alerts.add(getString(R.string.bg_notif_no_channel))
            }
        }

        // 全部关闭时服务本就该停，这里只兜住 START_STICKY 重建的短暂空窗
        if (running == 0 && alerts.isEmpty()) {
            return getString(R.string.keep_notif_waiting)
        }

        val parts = ArrayList<String>(4)
        if (running > 0) parts.add(getString(R.string.keep_notif_summary, running))
        alerts.take(2).forEach { parts.add(it) }
        if (prefs.cell4gActive) parts.add(getString(R.string.keep_notif_4g))
        return parts.joinToString(" · ")
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_swissknife)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    /**
     * 「亮度守护」有没有可用的写入通道（WRITE_SETTINGS 直写或 Shizuku）。
     *
     * 结果缓存 [CHANNEL_CACHE_MS]：这个判断要问一次 ContentProvider 加一次 Shizuku binder，
     * 而看门狗每 30 秒就会重算一次通知文案（2026-11-09 功耗审查）。
     * 事件驱动的 [refresh] 会主动作废缓存，所以用户刚授权完回来不会滞后。
     */
    private fun hasBrightnessChannel(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (brightnessChannelCachedAt != 0L && now - brightnessChannelCachedAt < CHANNEL_CACHE_MS) {
            return brightnessChannelCached
        }
        brightnessChannelCached = BrightGuard.canWriteDirectly(this) || ShizukuShell.hasPermission()
        brightnessChannelCachedAt = now
        return brightnessChannelCached
    }

    private fun refreshNotification() {
        val text = notificationText()
        if (text == lastNotificationText) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
        lastNotificationText = text
    }

    companion object {
        private const val CHANNEL_ID = "swissknife_keepalive"
        private const val NOTIFICATION_ID = 2001

        /** 亮度通道判断的缓存时长，见 [hasBrightnessChannel]。 */
        private const val CHANNEL_CACHE_MS = 60_000L

        @Volatile
        var instance: KeepAliveService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * 无条件拉起（幂等）。已在运行时只是多走一次 onStartCommand，
         * 比"先判断单例再启动"可靠——实测 am stopservice 之后静态引用不一定及时清空。
         *
         * 后台启动前台服务可能被系统限制（Android 12+），失败就静默放弃：
         * 无障碍服务连接、进入界面、开机广播都会再次触发，不依赖单次成功。
         */
        /**
         * 拉起常驻服务（幂等）。
         *
         * @return **false 表示“已经在跑、没有发起启动”**。此时调用方必须自己调 [tick] ——
         *   省掉的是 AMS 往返，**不能顺手把心跳一起省掉**：`onStartCommand` 里挂着
         *   护眼到期表的兜底重挂（本机会丢长时间 `postDelayed`）与通知刷新。
         *
         *   这个坑当场踩到过（2026-11-09）：短路之后无障碍重连，通知一直停在
         *   “无障碍未连接”，护眼的到期兜底也停了。
         */
        fun ensureRunning(context: Context): Boolean {
            // 判据用「startForeground 真的执行过」而不是「实例存在」——
            // 后者可能正处在 stopSelf 的窗口里，那种情况必须重新拉起。
            if (instance?.foregroundStarted == true) return false
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java)
                )
            } catch (_: Throwable) {
            }
            return true
        }

        /**
         * 服务已在跑时，在主线程补跑 [onStartCommand] 里的两件轻活：
         * 重挂「护眼休息」的到期表 + 刷新通知文案。**零 IPC**，这正是它能替代
         * `startForegroundService` 的原因。两个动作都是幂等的，重复调用无害。
         */
        fun tick() {
            val svc = instance ?: return
            svc.main.post {
                EyeBreak.onServiceTick()
                svc.refreshNotification()
            }
        }

        /**
         * 按功能开关同步起停。这是唯一应该被外部调用的入口：
         * 有功能在用 → 拉起；全部关闭 → 停掉自己，不留通知也不占进程优先级。
         */
        fun sync(context: Context) {
            if (Prefs(context).anyFeatureEnabled) {
                if (!ensureRunning(context)) tick()
            } else {
                instance?.stopSelf()
            }
        }

        /** 通知文案需要更新时调用（球出现/消失、功能名变化）。可跨线程调用。 */
        fun refresh() {
            val svc = instance ?: return
            // 事件驱动：可能是用户刚去系统设置里授权回来，亮度通道缓存作废重算
            svc.brightnessChannelCachedAt = 0L
            svc.main.post { svc.refreshNotification() }
        }
    }
}
