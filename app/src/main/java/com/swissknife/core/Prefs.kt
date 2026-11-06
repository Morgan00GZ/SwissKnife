package com.swissknife.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全部本地存储，无网络、无云同步。
 *
 * 白名单、开关、当天计数都存在这里；计数按自然日重置，
 * 用日期字符串比对，不挂定时器（符合功耗优先：零轮询）。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("swissknife", Context.MODE_PRIVATE)

    /** 「强制滑动」功能自己的开关。以后新增功能各用一个 key，互不影响。 */
    var forceScrollEnabled: Boolean
        get() = sp.getBoolean(KEY_FS_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_FS_ENABLED, v).apply()

    /**
     * 是否有任一功能处于开启状态。
     *
     * 这是**保活服务的唯一判据**：有功能在用就常驻，全部关闭就停掉自己。
     * 新增功能时把自己的开关或进来，保活自动跟随，不必改 KeepAliveService。
     */
    val anyFeatureEnabled: Boolean
        get() = forceScrollEnabled

    /** 守护的应用包名集合。 */
    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_WHITELIST, v).apply()

    fun addToWhitelist(pkg: String) {
        whitelist = whitelist + pkg
    }

    fun removeFromWhitelist(pkg: String) {
        whitelist = whitelist - pkg
    }

    /** 当天累计补滑次数，跨天自动归零。 */
    var todayCount: Int
        get() = if (sp.getString(KEY_COUNT_DATE, "") == today()) {
            sp.getInt(KEY_COUNT, 0)
        } else {
            0
        }
        set(v) {
            sp.edit()
                .putString(KEY_COUNT_DATE, today())
                .putInt(KEY_COUNT, v)
                .apply()
        }

    fun incCount() {
        todayCount = todayCount + 1
    }

    /** 悬浮球上次停靠的位置，-1 表示尚未设置过，用默认位置。 */
    var ballX: Int
        get() = sp.getInt(KEY_BALL_X, -1)
        set(v) = sp.edit().putInt(KEY_BALL_X, v).apply()

    var ballY: Int
        get() = sp.getInt(KEY_BALL_Y, -1)
        set(v) = sp.edit().putInt(KEY_BALL_Y, v).apply()

    /**
     * 悬浮球上显示的功能名称。
     * 不写死：哪个功能点亮了球，就把自己的名字写进来，
     * 以后新增功能复用同一个球时不用改服务代码。
     */
    var ballLabel: String
        get() = sp.getString(KEY_BALL_LABEL, "强制滑动") ?: "强制滑动"
        set(v) = sp.edit().putString(KEY_BALL_LABEL, v).apply()

    /** 用户长按收起了悬浮球。下次打开本应用时自动恢复。 */
    var ballHidden: Boolean
        get() = sp.getBoolean(KEY_BALL_HIDDEN, false)
        set(v) = sp.edit().putBoolean(KEY_BALL_HIDDEN, v).apply()

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    companion object {
        const val KEY_FS_ENABLED = "fs_enabled"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_COUNT = "count"
        const val KEY_COUNT_DATE = "count_date"
        const val KEY_BALL_X = "ball_x"
        const val KEY_BALL_Y = "ball_y"
        const val KEY_BALL_HIDDEN = "ball_hidden"
        const val KEY_BALL_LABEL = "ball_label"
    }
}
