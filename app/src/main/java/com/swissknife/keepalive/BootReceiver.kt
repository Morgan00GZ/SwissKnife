package com.swissknife.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机与覆盖安装后的自愈入口（保活第 5 层的应用侧一半）。
 *
 * 只处理 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED，**刻意不处理 LOCKED_BOOT_COMPLETED**：
 * 那条广播需要 android:directBootAware="true" 才会送达，而解锁前读不到
 * credential-encrypted 的 SharedPreferences（功能开关就在里面），
 * 收不到开关等于不知道该不该起服务，硬起只会得到一个空转的前台服务。
 *
 * 更可靠的那一半在无障碍服务上：系统重启后会自行把已启用的无障碍服务重新绑定，
 * 那时的 onServiceConnected 会再拉一次。实测这条路能绕开厂商的自启动管理。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> KeepAliveService.sync(context)
        }
    }
}
