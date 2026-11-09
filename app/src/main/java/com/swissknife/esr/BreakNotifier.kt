package com.swissknife.esr

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File

/**
 * 浮窗结束提示。
 *
 * 目标：像收到一条系统通知那样响铃 + 震动，但**不产生任何通知对象**——
 * 通知栏、锁屏、通知历史都不会留下痕迹。
 *
 * ## 分工（2026-11-08 用户定的口径）
 *
 * - **声音跟随系统**：静音 / 振动 / 勿扰模式下不响，避免开会或夜里突然出声。
 * - **震动无条件必震**：任何模式都要震，这是"休息结束"唯一可靠的触觉凭据。
 *
 * ## 为什么之前"概率性不震"（2026-11-08 修）
 *
 * 老写法是 `vibrator.vibrate(VibrationEffect)`——这个重载自 Android 13 起被标记废弃，
 * **属性默认是 `USAGE_UNKNOWN`**。系统对"用途不明"的震动按当前策略处理，各 ROM 收得更紧，
 * 于是出现"与静音状态无关、时好时坏"的现象。
 *
 * 现在显式声明 `USAGE_ALARM`：这是公开 API 里唯一被视为"必须送达"的用途，
 * 静音、振动、勿扰都不会吞掉它。按 API 级别走三条通道，任一条成功即可：
 *
 * 1. API 33+：`vibrate(effect, VibrationAttributes(USAGE_ALARM))`
 * 2. API 26–32：`vibrate(effect, AudioAttributes(USAGE_ALARM))`
 * 3. 最后兜底：最老的 `vibrate(timings, repeat)`
 *
 * 每次提示都会往 `files/watch.log` 记一行（铃声模式 / 勿扰 / 声音是否跳过 / 震动是否成功），
 * 下次再出现"没听见/没感觉到"可以直接查，而不用猜。
 *
 * 从 EyeStrainRelief 移植（2026-11-07），2026-11-08 按上面的结论重写震动通道。
 */
object BreakNotifier {

    private const val TAG = "SwissKnife"

    /** 铃声最长播放时长，避免个别机型通知音过长 */
    private const val RING_MAX_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var playing: Ringtone? = null

    /** 上一次震动是否成功提交给系统，仅供日志与排查使用。 */
    @Volatile
    private var lastVibrateOk = false

    fun notifyBreakEnd(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val dnd = isDoNotDisturb(app)

        // 震动先做，且与任何模式无关——它是"休息结束"最可靠的凭据
        lastVibrateOk = vibrate(app)

        // 声音严格跟随系统：静音 / 振动 / 勿扰都不响
        val silent = dnd || ringerMode != AudioManager.RINGER_MODE_NORMAL
        if (!silent) playNotificationSound(app)

        log(
            app,
            "休息结束提示 ringer=$ringerMode dnd=$dnd " +
                "声音=${if (silent) "跳过" else "播放"} 震动=$lastVibrateOk"
        )
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Throwable) {
            false
        }
    }

    private fun playNotificationSound(context: Context) {
        stopPlaying()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            // 明确走通知音量通道，与系统通知的响度保持一致
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            playing = ringtone
            handler.postDelayed({ stopPlaying() }, RING_MAX_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "play notification sound failed", t)
        }
    }

    private fun stopPlaying() {
        val ringtone = playing ?: return
        playing = null
        try {
            if (ringtone.isPlaying) ringtone.stop()
        } catch (_: Throwable) {
        }
    }

    /**
     * 两下短震：80ms 振动 + 120ms 间隔 + 80ms 振动 —— **节奏与原版 ESR 完全一致**。
     *
     * 2026-11-09 按用户要求改回原节奏（此前我擅自改成三下 120ms，理由是"两下太轻可能没感觉到"，
     * 那是在质疑用户的测试结论，方向也错了）。**这里只管节奏，不要因为"想让用户注意到"再动它。**
     *
     * 与 ESR 唯一的差别只有一处：显式声明 `USAGE_ALARM` 用途（见下方三条通道），
     * 这**不改变震动强度与节奏**，只改变系统"是否肯把它送出去"。
     */
    private fun vibrate(context: Context): Boolean {
        val vibrator = resolveVibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false

        val timings = longArrayOf(0L, 80L, 120L, 80L)
        val amplitudes = intArrayOf(
            0,
            VibrationEffect.DEFAULT_AMPLITUDE,
            0,
            VibrationEffect.DEFAULT_AMPLITUDE
        )
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)

        // ① Android 13+：显式 ALARM 用途（唯一"必须送达"的语义）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build()
                )
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "vibrate(USAGE_ALARM attrs) failed", t)
            }
        }

        // ② Android 8–12：同语义的旧重载
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "vibrate(USAGE_ALARM audio attrs) failed", t)
            }
        }

        // ③ 兜底：最老的重载。属性回到默认值，至少比完全不震强
        try {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate(legacy) failed", t)
        }
        return false
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 记到 `files/watch.log`：出问题时有据可查，不用靠回忆猜是不是响过。 */
    private fun log(context: Context, msg: String) {
        try {
            val f = File(context.filesDir, "watch.log")
            if (f.length() > 120_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} [提示] $msg\n")
        } catch (_: Throwable) {
        }
    }
}
