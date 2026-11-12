package com.swissknife.clean

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.swissknife.core.ShizukuShell
import rikka.shizuku.Shizuku

/**
 * 「空间回收」的 Shizuku UserService 绑定层。
 *
 * 为什么不用框架里已有的 [ShizukuShell]（它是 `sh -c` 通道，注入滑动/改网络都靠它）：
 * 1. 那个通道拿不到 errno —— 只能看 stderr 文本猜是"权限不足"还是"文件没了"，
 *    而本功能必须把 EACCES 与 ENOENT 分开处置（前者重试无用、后者要重扫）；
 * 2. 每清一个文件都要起一个 app_process 进程，清单有十几项时就是十几次进程启动。
 *    AIDL 一次绑定、多次调用，代价是一次 binder 往返。
 *
 * `bindUserService` 是公开 API；`newProcess` 在 13.x 里是 private（所以 [ShizukuShell] 得反射）。
 * 授权本身复用框架已有的 [ShizukuShell]，不另建一套权限流程。
 */
object ShizukuUserService {

    private const val TAG = "SwissKnife"

    enum class State {
        /** Shizuku 服务没在跑（多见于重启后未手动启动）。 */
        NOT_RUNNING,

        /** Shizuku 在跑但没给本应用授权。 */
        NO_PERMISSION,

        /** 已发起绑定，等 shell 进程起来。 */
        BINDING,

        READY,

        /** 绑定失败或被断开。 */
        FAILED,
    }

    @Volatile
    private var impl: ITruncateService? = null

    @Volatile
    private var bound = false

    @Volatile
    private var listener: ((State) -> Unit)? = null

    private var args: Shizuku.UserServiceArgs? = null
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    /** 已连上的服务；null 表示通道不可用。调用方必须先判空。 */
    fun service(): ITruncateService? = impl

    fun state(): State = when {
        impl != null -> State.READY
        !ShizukuShell.isAvailable() -> State.NOT_RUNNING
        !ShizukuShell.hasPermission() -> State.NO_PERMISSION
        bound -> State.BINDING
        else -> State.FAILED
    }

    /** 只允许一个观察者（本功能只有一个页面用它），避免多页面时状态串台。 */
    fun setListener(l: ((State) -> Unit)?) {
        listener = l
    }

    fun clearListener(l: ((State) -> Unit)?) {
        if (listener === l) listener = null
    }

    /**
     * 确保通道可用。可重复调用：已连上直接回 READY，正在绑回 BINDING。
     * 必须在主线程调用（Shizuku 的绑定回调走主线程 handler）。
     */
    fun ensureBound(context: Context, onState: (State) -> Unit) {
        appContext = context.applicationContext
        impl?.let { onState(State.READY); return }
        if (!ShizukuShell.isAvailable()) {
            onState(State.NOT_RUNNING)
            return
        }
        if (!ShizukuShell.hasPermission()) {
            onState(State.NO_PERMISSION)
            return
        }
        if (bound) {
            onState(State.BINDING)
            return
        }
        try {
            val a = args ?: buildArgs(context.applicationContext).also { args = it }
            Shizuku.bindUserService(a, connection)
            bound = true
            onState(State.BINDING)
        } catch (t: Throwable) {
            // 绑定本身就失败（例如 Shizuku 服务刚被系统回收）：不重试、不谎报已连接
            Log.w(TAG, "UserService 绑定失败", t)
            bound = false
            onState(State.FAILED)
        }
    }

    /** Shizuku 断开后复位，让下一次 ensureBound 重新走一遍。 */
    fun reset() {
        impl = null
        bound = false
        args = null
    }

    /**
     * 解绑并请求 Shizuku 结束服务进程（`remove=true`）。
     *
     * **必须在页面销毁时调用**：不解绑的后果实测过 —— 每进一次功能页就多一个
     * shell 身份的 `:clean` 进程，退出页面也不回收，`PPID=1` 常驻；
     * 2026-11-12 一轮测试累积到 11 个（本应用进程才 1 个）。
     * `daemon(false)` 只在"应用进程正常结束"时才管用，`force-stop` 走的是另一条路。
     */
    fun release() {
        val a = args
        if (a != null && bound) {
            runCatching { Shizuku.unbindUserService(a, connection, true) }
                .onFailure { Log.w(TAG, "UserService 解绑失败", it) }
        }
        impl = null
        bound = false
        args = null
    }

    private fun buildArgs(context: Context): Shizuku.UserServiceArgs {
        val versionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, TruncateService::class.java.name)
        )
            // daemon=false：服务进程随本应用进程一起结束，不在后台留下孤立的 shell 进程
            .daemon(false)
            .processNameSuffix("clean")
            .debuggable(debuggable)
            .version(versionCode)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            impl = ITruncateService.Stub.asInterface(binder)
            bound = true
            notifyState(State.READY)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            impl = null
            bound = false
            args = null
            notifyState(State.FAILED)
        }
    }

    private fun notifyState(s: State) {
        main.post { listener?.invoke(s) }
    }
}