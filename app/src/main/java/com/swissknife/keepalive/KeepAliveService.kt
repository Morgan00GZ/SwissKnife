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

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationText()))

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
     * 通知文案是"当前到底在做什么"的唯一常驻可见凭据，所以按真实状态拼：
     * 4G 是否生效、悬浮球是否在屏幕上、护眼计时到哪了、亮度守护有没有生效、都还没有时在等什么。
     *
     * 早先只认悬浮球，只开「极致省电」时会显示"等待无障碍服务连接"——那句话在本功能下
     * 完全不成立（本功能不依赖无障碍），用户会照着去查一个不存在的问题。
     * 新增两个功能后同理：每个新功能都必须能用自己的话说明"我在做什么"。
     */
    private fun notificationText(): String {
        val parts = ArrayList<String>(4)
        if (prefs.cell4gActive) parts.add(getString(R.string.keep_notif_4g))
        // 球只有「强制滑动」开着时才存在；无障碍服务连上不等于球在屏幕上
        if (prefs.forceScrollEnabled && ForegroundWatcherService.isRunning()) {
            parts.add(getString(R.string.keep_notif_running, prefs.ballLabel))
        }
        EyeBreak.notificationSegment(this)?.let { parts.add(it) }
        if (prefs.brightGuardEnabled) {
            parts.add(
                if (prefs.minBrightnessValue > 0) {
                    getString(R.string.bg_notif_active, prefs.minBrightnessValue)
                } else {
                    getString(R.string.bg_notif_idle)
                }
            )
        }
        if (parts.isEmpty()) {
            parts.add(
                when {
                    prefs.saver4gEnabled -> getString(R.string.keep_notif_saver_idle)
                    prefs.eyeBreakEnabled -> getString(R.string.eb_notif_waiting)
                    else -> getString(R.string.keep_notif_waiting)
                }
            )
        }
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
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveService::class.java)
                )
            } catch (_: Throwable) {
            }
        }

        /**
         * 按功能开关同步起停。这是唯一应该被外部调用的入口：
         * 有功能在用 → 拉起；全部关闭 → 停掉自己，不留通知也不占进程优先级。
         */
        fun sync(context: Context) {
            if (Prefs(context).anyFeatureEnabled) {
                ensureRunning(context)
            } else {
                instance?.stopSelf()
            }
        }

        /** 通知文案需要更新时调用（球出现/消失、功能名变化）。可跨线程调用。 */
        fun refresh() {
            val svc = instance ?: return
            svc.main.post { svc.refreshNotification() }
        }
    }
}
