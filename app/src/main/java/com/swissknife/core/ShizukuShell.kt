package com.swissknife.core

import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * Shizuku 命令通道：以 shell（uid 2000）身份执行命令。
 *
 * 这是本应用唯一能"跨应用"的入口——自身进程没有注入事件的权限。
 * 所有调用会阻塞，统一放在单线程池，绝不在主线程跑。
 */
object ShizukuShell {

    const val PERMISSION_REQUEST_CODE = 2001
    private const val TAG = "SwissKnife"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-shell").apply { isDaemon = true }
    }

    /**
     * Shizuku 13.x 把 newProcess 设为私有，编译期不可见，这里反射拿一次。
     * 拿不到不影响其它功能，只是无法执行命令。
     */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        try {
            rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "newProcess 不可用（Shizuku 版本差异）", t)
            null
        }
    }

    fun isAvailable(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun requestPermission() {
        if (!isAvailable()) return
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission 失败", t)
        }
    }

    /** 同步执行，必须在子线程调用。返回 stdout；失败返回 null。 */
    fun execSync(command: String): String? {
        if (!hasPermission()) return null
        val method = newProcessMethod ?: return null
        return try {
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as? Process ?: return null
            // 先读完输出再 waitFor，避免管道缓冲写满导致死锁
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (t: Throwable) {
            Log.w(TAG, "执行失败：$command", t)
            null
        }
    }

    fun execAsync(command: String, onResult: ((String?) -> Unit)? = null) {
        executor.execute {
            val result = execSync(command)
            onResult?.invoke(result)
        }
    }

    /** 需要串联多条命令时用，保证与 execAsync 共用同一条串行队列。 */
    fun submit(task: () -> Unit) {
        executor.execute(task)
    }
}
