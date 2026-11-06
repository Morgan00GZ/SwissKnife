package com.swissknife.inject

import android.content.Context
import android.util.Log
import com.swissknife.core.ShizukuShell
import java.io.File

/**
 * 滚动注入：把 assets 里的 inject.dex 交给 shell 身份执行。
 *
 * 为什么不在应用进程内直接注入：
 *   应用进程没有 INJECT_EVENTS 权限，必须借 Shizuku 的 shell 身份。
 *
 * 为什么 dex 要放到 /data/local/tmp：
 *   应用私有目录（/data/data/...）shell 读不了；
 *   应用可写、shell 也可读的公共位置只有 /data/local/tmp 与外部存储，
 *   而 app_process 从外部存储加载 dex 在部分机型上会被 SELinux 拦，故统一中转一次。
 */
object ScrollInjector {

    private const val TAG = "SwissKnife"
    private const val ASSET_NAME = "inject.dex"
    private const val REMOTE_DEX = "/data/local/tmp/sk_inject.dex"
    private const val PREFIX = "com.swissknife.inject.Injector"

    @Volatile
    private var dexReady = false

    /**
     * 确保设备上有一份可用的 dex。
     * 只在首次调用或本地资源版本变化时复制，之后走缓存，避免每次注入都付一次 IO。
     */
    fun ensureDex(context: Context): Boolean {
        if (dexReady) return true
        return try {
            val staged = File(context.cacheDir, ASSET_NAME)
            val needStage = !staged.exists() || staged.length() == 0L
            if (needStage) {
                context.assets.open(ASSET_NAME).use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // 用外部存储做中转：应用可写、shell 可读
            val relay = File(context.getExternalFilesDir(null) ?: context.cacheDir, ASSET_NAME)
            if (!relay.exists() || relay.length() != staged.length()) {
                staged.copyTo(relay, overwrite = true)
            }
            val out = ShizukuShell.execSync(
                "cp \"${relay.absolutePath}\" $REMOTE_DEX && chmod 644 $REMOTE_DEX && echo OK"
            )
            dexReady = out?.contains("OK") == true
            dexReady
        } catch (t: Throwable) {
            Log.w(TAG, "准备 dex 失败", t)
            false
        }
    }

    /**
     * 注入一次滚动。必须在子线程调用。
     *
     * @param x        触点横坐标（像素）
     * @param y        触点纵坐标（像素）
     * @param vscroll  滚动量，负值=向上翻（看下一集）
     * @param count    连续注入次数
     * @param interval 多次之间的间隔（毫秒）
     */
    fun inject(
        context: Context,
        x: Float,
        y: Float,
        vscroll: Float = -5f,
        count: Int = 1,
        interval: Int = 0
    ): Boolean {
        if (!ShizukuShell.hasPermission()) return false
        if (!ensureDex(context)) return false
        val cmd = "app_process -Djava.class.path=$REMOTE_DEX /system/bin $PREFIX " +
                "${x.toInt()} ${y.toInt()} $vscroll $count $interval"
        val out = ShizukuShell.execSync(cmd)
        val ok = out?.contains("inject=true") == true
        if (!ok) Log.w(TAG, "注入未成功：$out")
        return ok
    }

    /** 在屏幕中心注入一次，坐标按当前分辨率实时计算，不写死。 */
    fun injectAtCenter(context: Context, vscroll: Float = -5f): Boolean {
        val dm = context.resources.displayMetrics
        return inject(context, dm.widthPixels * 0.5f, dm.heightPixels * 0.5f, vscroll)
    }
}
