package com.swissknife.esr

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 最低亮度守护。
 *
 * 用 ContentObserver 监听 SCREEN_BRIGHTNESS，属于事件驱动，没有任何轮询开销：
 * 只有亮度真的变化时才醒一次，值低于阈值就写回。
 *
 * 阈值直接是系统亮度控制值（0–255），不是百分比。原因：系统亮度条用感知刻度
 * （实机实测约为 100·√(值/255)），0–255 与条上位置差得很远，把它当百分比会误导。
 *
 * 写入走双通道：优先 WRITE_SETTINGS（无 IPC 延迟，拖动滑块时不会卡），
 * 没有该权限则退回 Shizuku 执行 settings 命令。
 *
 * 注意：自动亮度模式下系统会按环境光持续覆盖，本守卫会与其形成拉锯——
 * 每次被调低都会被拉回，用户不会看到低于阈值，但亮度可能在阈值附近小幅波动。
 *
 * ## 嵌入 SwissKnife 后的变化（2026-11-07）
 *
 * 从 EyeStrainRelief 的 `class BrightnessGuard(context, prefs)` 改成 object：
 * 生命周期跟随常驻服务（[attach]/[detach]），与 `Saver4G` 同一套模式。
 * **开关关闭 = 注销 observer，守卫完全停止**；它只"纠偏"不记账，
 * 所以没有需要还原的状态——关掉后亮度就停在当时的值。
 *
 * **不需要无障碍**：这是它独立于「护眼休息」的关键——只开本功能时，
 * 应用不该去要一个用不到的权限。
 */
object BrightGuard {

    private const val TAG = "SwissKnife"

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var observer: ContentObserver? = null

    private val main = Handler(Looper.getMainLooper())

    /** 上次真正写入的时刻，用于节流（见 [enforce]）。 */
    @Volatile
    private var lastEnforceAt = 0L

    /** 被节流合并掉的那次纠偏，延后再补一次。 */
    private val pendingEnforce = Runnable { ctx?.let { enforceInternal(it) } }

    /**
     * 状态变化回调（二级页在用）。
     * 写入可能发生在页面不可见时（用户在系统设置里拖亮度），
     * 页面订阅后按权威值重画，不必自己轮询。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    // ---------------- 生命周期 ----------------

    /**
     * 挂到常驻进程上（由 [com.swissknife.keepalive.KeepAliveService.onCreate] 调用）。
     *
     * 幂等。开关开着才注册 observer——**这是本条功能全部开销的唯一来源**，
     * 关着的时候一行代码都不跑。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        ctx = app
        if (!Prefs(app).brightGuardEnabled) return
        startInternal(app)
    }

    /** 从常驻进程摘除（[com.swissknife.keepalive.KeepAliveService.onDestroy]）。 */
    fun detach() {
        stopInternal()
        ctx = null
    }

    /**
     * 首页开关变化后调用。开启时立刻纠偏一次当前值（可能已经低于阈值），
     * 关闭时立即注销 observer——不等任何广播。
     */
    fun onSwitchChanged(context: Context) {
        val app = context.applicationContext
        ctx = app
        if (Prefs(app).brightGuardEnabled) {
            startInternal(app)
        } else {
            stopInternal()
        }
    }

    private fun startInternal(app: Context) {
        if (observer != null) {
            enforce()
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                enforce()
            }
        }
        try {
            // 必须同时观察两个键。自动亮度下调的是"显示亮度"，在部分 ROM 上只写
            // screen_brightness_float（API 30+）而不写 screen_brightness——
            // 只挂 int 键的结果就是：手动拖能拉回，自动亮度降下去却完全收不到回调。
            val cr = app.contentResolver
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, o
            )
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    cr.registerContentObserver(
                        Settings.System.getUriFor(KEY_BRIGHTNESS_FLOAT), false, o
                    )
                } catch (_: Throwable) {
                }
            }
            observer = o
            // 启动时先纠正一次当前值
            enforce()
        } catch (t: Throwable) {
            Log.w(TAG, "register brightness observer failed", t)
            observer = null
        }
    }

    private fun stopInternal() {
        val o = observer ?: return
        try {
            ctx?.contentResolver?.unregisterContentObserver(o)
        } catch (_: Throwable) {
        }
        observer = null
        main.removeCallbacks(pendingEnforce)
    }

    // ---------------- 纠偏 ----------------

    /**
     * 检查并拉回。
     *
     * [context] 只在"常驻服务还没起来、但用户已经在页面上改了参数"时用作兜底。
     *
     * **带节流**：系统在自动亮度下会持续写亮度，我们如果每收到一次回调就立刻写回，
     * 就变成一场高频对打——每次写回都是一次 IPC（没有直写权限时还要起一个 `sh -c` 进程），
     * 实测表现就是"息屏期间手机发热"。这里把同一时间窗内的多次触发合并成一次：
     * 幂等，且最终一定收敛到下限。
     */
    fun enforce(context: Context? = null) {
        val app = ctx ?: context?.applicationContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforceAt < ENFORCE_MIN_INTERVAL_MS) {
            main.removeCallbacks(pendingEnforce)
            main.postDelayed(pendingEnforce, ENFORCE_MIN_INTERVAL_MS)
            return
        }
        lastEnforceAt = now
        enforceInternal(app)
    }

    private fun enforceInternal(app: Context) {
        val prefs = Prefs(app)
        if (!prefs.brightGuardEnabled) return
        val minValue = prefs.minBrightnessValue
        if (minValue <= 0) return
        val floor = minValue.coerceIn(1, Prefs.MAX_BRIGHTNESS)
        val current = readCurrent(app)
        if (current < 0 || current >= floor) return
        Log.d(TAG, "brightness $current below min $floor, restoring")
        lastEnforceAt = SystemClock.elapsedRealtime()
        if (write(app, floor)) notifyListeners()
    }

    private fun readCurrent(app: Context): Int = try {
        Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
    } catch (_: Throwable) {
        -1
    }

    private fun write(app: Context, value: Int): Boolean {
        if (canWriteDirectly(app)) {
            return try {
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (_: Throwable) {
                false
            }
        }
        if (ShizukuShell.hasPermission()) {
            // 异步通道：写入结果由系统侧设置项自己反映，界面按权威值重画即可
            ShizukuShell.execAsync("settings put system screen_brightness $value")
            return true
        }
        return false
    }

    /** 是否具备直接写系统设置的权限（WRITE_SETTINGS）。 */
    fun canWriteDirectly(context: Context): Boolean = try {
        Settings.System.canWrite(context)
    } catch (_: Throwable) {
        false
    }

    /** 当前系统亮度控制值（0–255），读不到返回 -1。 */
    fun readCurrentValue(context: Context): Int = readCurrent(context)

    /** 二级页订阅：写入发生后回调（回调线程不保证，界面自己切主线程）。 */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        listeners.forEach { cb ->
            try {
                cb()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 把亮度控制值换算成亮度条上的位置（0–100），仅用于界面显示。
     *
     * 系统亮度条不是线性刻度：实测机型（HyperOS 4）上约为 100·√(值/255)，
     * 控制值 64 只走到亮度条的一半。原生 Android 是线性刻度，此处会偏高，
     * 所以界面上标注「约」。本换算不参与任何判定逻辑。
     */
    fun estimateBarPercent(value: Int): Int {
        if (value <= 0) return 0
        val ratio = value.coerceAtMost(Prefs.MAX_BRIGHTNESS).toDouble() / Prefs.MAX_BRIGHTNESS
        return (100.0 * sqrt(ratio)).roundToInt().coerceIn(1, 100)
    }

    // ---------------- 亮度模式（自动 / 手动） ----------------

    /**
     * 当前是不是自动亮度。
     *
     * **这是"下限守不住"的真正原因**：自动亮度下系统按环境光持续改显示亮度，
     * 守卫写回的值随时会被覆盖。所以页面必须把它显示出来，而不是让用户以为功能坏了。
     */
    fun isAutoBrightness(context: Context): Boolean = try {
        Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Throwable) {
        false
    }

    /**
     * 把亮度模式切到手动——只有切到手动，下限才真的守得住。
     *
     * 这是**用户主动点按钮**才会发生的事，本类不会偷偷改：改的是用户的系统设置，
     * 而且自动亮度是很多人日常依赖的功能，必须由用户自己决定要不要为这个下限放弃它。
     */
    fun setManualMode(context: Context): Boolean {
        val app = context.applicationContext
        if (canWriteDirectly(app)) {
            val ok = try {
                Settings.System.putInt(
                    app.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                enforce(app)
                return true
            }
        }
        if (ShizukuShell.hasPermission()) {
            ShizukuShell.execAsync("settings put system screen_brightness_mode 0") {
                enforce(app)
            }
            return true
        }
        return false
    }

    /** 受保护的系统键：部分机型只在自动亮度时写它，观察不到它就会漏掉整类亮度变化。 */
    private const val KEY_BRIGHTNESS_FLOAT = "screen_brightness_float"

    /**
     * 两次写入之间的最小间隔。低于它就把这次纠偏合并到稍后一次，
     * 避免与系统的连续覆盖打乒乓（那会变成高频 IPC / 起进程，是实打实的耗电与发热）。
     */
    private const val ENFORCE_MIN_INTERVAL_MS = 700L
}
