package com.swissknife.access

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.inject.ScrollInjector
import com.swissknife.keepalive.KeepAliveService
import java.io.File
import kotlin.math.abs

/**
 * 悬浮球：所有需要"系统级点击"的功能共用的入口。
 *
 * 显隐完全由用户控制（功能开关 / 长按收起），不做任何前台应用判断。
 *
 * 为什么不自动显隐（三天实测结论，均已验证）：
 *   1. 无障碍服务会随机进入"绑定但未连接"的僵尸态：系统把它列在启用列表里，
 *      但 onServiceConnected 不执行、事件也不分发。每次只能手动重连救活一段时间。
 *   2. 即使服务正常，不同应用恢复时的行为也不一致：有的会重建窗口从而产生事件，
 *      有的（如微信）完全不发。
 *   3. Handler 的延迟消息在这台机器上会被丢弃（实测只执行一次就没了），
 *      所以任何"隔一会儿再查一次"的兜底都不可靠。
 *   结论：球的显隐只能由用户决定，但球一旦创建就独立于服务存在，服务僵尸也不影响点击。
 */
class ForegroundWatcherService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var ball: TextView? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var dragging = false

    /** 上次检查常驻服务是否存活的时间，用于节流（避免每条事件都碰一次 AMS）。 */
    private var lastWatchdogAt = 0L

    /** 开关一变就同步球的去留，并同步常驻服务的起停。 */
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_FS_ENABLED -> {
                    syncBall()
                    // 有功能开启才需要常驻；全部关闭就让服务退出，不白占通知栏
                    KeepAliveService.sync(this@ForegroundWatcherService)
                }
                Prefs.KEY_BALL_LABEL -> {
                    ball?.text = formatLabel(prefs.ballLabel)
                    KeepAliveService.refresh()
                }
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        instance = this
        try {
            getSharedPreferences("swissknife", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Throwable) {
        }
        syncBall()
        // 保活第 5 层：系统重启或覆盖安装后会把已启用的无障碍服务重新绑定，
        // 这里顺势把常驻服务拉起来——这条路径能绕开厂商的自启动管理，比 BOOT_COMPLETED 可靠。
        KeepAliveService.sync(this)
        log("服务已连接")
    }

    override fun onDestroy() {
        try {
            getSharedPreferences("swissknife", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Throwable) {
        }
        removeBall()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /**
     * 不消费任何无障碍事件：显隐由用户控制。
     * 但事件本身可以当"心跳"用——保活第 2 层就挂在这里，见 [checkKeepAlive]。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        checkKeepAlive()
    }

    /**
     * 保活第 2 层：常驻服务看门狗。
     *
     * 借无障碍事件的顺风车，**不额外注册任何定时器**（30 秒内最多执行一次）。
     * 为什么能这么做：无障碍服务由系统框架持有，是本类应用唯一可靠的"系统级锚点"，
     * 它自己几乎不会被回收；而它跑在同一个进程里，正好可以顺手把进程的前台服务补回来。
     *
     * 这里**不判断单例是否存在**，直接无条件拉起：startForegroundService 本身幂等，
     * 已在运行时只是多走一次 onStartCommand。实测用单例判断反而不可靠
     * （am stopservice 之后静态引用不一定及时清空，看门狗会误判为存活 → 永不拉起）。
     *
     * 注意不能用 postDelayed 做这套检查：本机 Handler 的延迟消息实测只执行一次就被丢弃，
     * 30 秒以上的延迟必然失效（手册 §4.14 第三节）。
     */
    private fun checkKeepAlive() {
        if (!::prefs.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchdogAt < WATCHDOG_INTERVAL_MS) return
        lastWatchdogAt = now
        if (prefs.forceScrollEnabled) KeepAliveService.ensureRunning(this)
    }

    /** 按开关与收起状态同步球。 */
    private fun syncBall() {
        // 球的有无只由总开关决定
        val want = prefs.forceScrollEnabled
        when {
            want && ball == null -> addBall()
            !want && ball != null -> removeBall()
        }
    }

    /** 功能页按钮调用：立刻显示（服务僵尸被救活后也能用）。 */
    fun showBallNow(label: String? = null) {
        if (label != null) prefs.ballLabel = label
        prefs.forceScrollEnabled = true
        if (ball == null) addBall()
        log("手动点亮 label=${prefs.ballLabel}")
    }

    private fun addBall(retry: Int = 0) {
        // 已存在就直接返回：重试链与开关回调可能同时触发 addBall，
        // 重复 addView 会抛 BadTokenException 并把状态搞乱。
        if (ball != null) return

        val manager = wm ?: getSystemService(WindowManager::class.java) ?: run {
            log("无法获取 WindowManager")
            return
        }
        wm = manager

        val size = dp(BALL_SIZE_DP)
        val view = TextView(this).apply {
            text = formatLabel(prefs.ballLabel)
            // 文字透明度与球底保持一致，避免"字比球实"的割裂感
            setTextColor(BALL_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize(prefs.ballLabel.length))
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
            setLineSpacing(0f, 0.95f)
            setPadding(dp(6), 0, dp(6), 0)
            background = ballBackground()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (prefs.ballX >= 0) prefs.ballX else dm.widthPixels - dp(150)
            y = if (prefs.ballY >= 0) prefs.ballY else (dm.heightPixels * 0.55f).toInt()
        }

        view.setOnTouchListener { v, ev -> onBallTouch(v, ev, params) }

        try {
            manager.addView(view, params)
            ball = view
            log("悬浮球已显示：${prefs.ballLabel}")
        } catch (t: Throwable) {
            // 服务刚连上时窗口 token 可能还没就绪，报 BadTokenException，稍后重试即可
            log("悬浮球显示失败(第${retry + 1}次)：$t")
            if (retry < MAX_ADD_RETRY) {
                main.postDelayed({ addBall(retry + 1) }, ADD_RETRY_MS)
            }
        }
    }

    private fun removeBall() {
        val v = ball ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        ball = null
        log("悬浮球已移除")
    }

    private fun onBallTouch(
        v: View,
        e: MotionEvent,
        params: WindowManager.LayoutParams
    ): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = params.x
                startY = params.y
                downAt = System.currentTimeMillis()
                dragging = false
                v.alpha = 1f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downRawX).toInt()
                val dy = (e.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) dragging = true
                if (dragging) {
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        wm?.updateViewLayout(v, params)
                    } catch (_: Throwable) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.alpha = 0.92f
                if (dragging) {
                    snapToEdge(v, params)
                    prefs.ballX = params.x
                    prefs.ballY = params.y
                } else if (System.currentTimeMillis() - downAt > LONG_PRESS_MS) {
                    // 长按 = 关掉功能：球消失，主界面开关同步变灰
                    log("长按收起（同时关闭功能）")
                    prefs.forceScrollEnabled = false
                    removeBall()
                } else {
                    fireScroll(v)
                }
                return true
            }
        }
        return false
    }

    /** 松手后吸附到较近的一侧，避免挡住中间内容。 */
    private fun snapToEdge(v: View, params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val w = v.width.takeIf { it > 0 } ?: dp(80)
        params.x = if (params.x + w / 2 < dm.widthPixels / 2) dp(6) else dm.widthPixels - w - dp(6)
        try {
            wm?.updateViewLayout(v, params)
        } catch (_: Throwable) {
        }
    }

    /** 点一下 = 补一次滑动。 */
    private fun fireScroll(v: View) {
        ShizukuShell.submit {
            val ok = ScrollInjector.injectAtCenter(this)
            if (ok) prefs.incCount()
            log("注入 ok=$ok")
            if (!ok) main.post { hintFail(v) }
        }
    }

    /**
     * 注入失败时的轻提示：只把文字变橙 400ms。
     *
     * 以前用整块背景色闪烁，会覆盖掉圆角背景变成直角方块，
     * 观感上就是"一个绿色方块闪一下"，所以改成只动文字颜色。
     */
    private fun hintFail(v: View) {
        val tv = v as? TextView ?: return
        try {
            tv.setTextColor(0xFFD85A30.toInt())
            main.postDelayed({
                try {
                    tv.setTextColor(BALL_TEXT_COLOR)
                } catch (_: Throwable) {
                }
            }, 400)
        } catch (_: Throwable) {
        }
    }

    /**
     * 半透明白底、圆角胶囊。
     * 加了一圈极淡的描边：纯白底放在白色界面上（如微信）边界会消失，
     * 1dp 的 8% 黑边在不影响"白底"观感的前提下保证任何背景都能看出轮廓。
     */
    private fun ballBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(BALL_BG_COLOR)
        setStroke(dp(1), 0x0D000000)
    }

    /**
     * 按字数主动分成两行，避免系统自动换行排出「3+1」这种难看的断法。
     * 4 字排成「强制 / 滑动」，6 字排成「三个 / 三个」。
     */
    private fun formatLabel(label: String): String {
        val len = label.length
        if (len <= 3) return label
        val half = (len + 1) / 2
        return label.substring(0, half) + "\n" + label.substring(half)
    }

    /**
     * 字号随文字长度收缩——球的大小固定，让文字适应球，而不是球被文字撑大。
     * 最多两行，超出两行的部分由 maxLines 裁掉。
     */
    private fun labelSize(len: Int): Float = when {
        len <= 2 -> 16f
        len <= 3 -> 13f
        len <= 4 -> 12f
        len <= 6 -> 10f
        len <= 8 -> 8.5f
        else -> 7.5f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun log(msg: String) {
        try {
            val f = File(filesDir, "watch.log")
            if (f.length() > 120_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} $msg\n")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val LONG_PRESS_MS = 600L

        /** 看门狗节流窗口：30 秒内最多检查一次常驻服务。 */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** 球直径（dp）。固定尺寸，文字自适应。 */
        private const val BALL_SIZE_DP = 56

        /** 球底与文字统一用 62% 透明度，比之前更透气。 */
        private const val BALL_BG_COLOR = 0x9EFFFFFF.toInt()
        private const val BALL_TEXT_COLOR = 0x9E000000.toInt()
        private const val MAX_ADD_RETRY = 3
        private const val ADD_RETRY_MS = 800L

        @Volatile
        var instance: ForegroundWatcherService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun refresh() {
            instance?.syncBall()
        }

        fun showNow(label: String? = null) {
            instance?.showBallNow(label)
        }
    }
}
