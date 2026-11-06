package com.swissknife

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swissknife.access.ForegroundWatcherService
import com.swissknife.core.BaseActivity
import com.swissknife.core.Prefs
import com.swissknife.core.ShizukuShell
import com.swissknife.keepalive.KeepAliveService
import com.swissknife.net.Saver4G
import com.swissknife.ui.ForceScrollActivity
import com.swissknife.ui.Saver4GActivity
import rikka.shizuku.Shizuku

/**
 * 首页：只做两件事——展示授权状态、作为各功能入口。
 * 具体功能的状态与设置都在各自的页面里，首页不堆业务。
 */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs

    private lateinit var shizukuValue: TextView
    private lateinit var accessibilityValue: TextView
    private lateinit var overlayValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var keepAliveValue: TextView

    /** 功能总开关的引用：外部（快捷方式、长按收球）也能改这个开关，界面必须回读真实值。 */
    private var forceScrollSwitch: android.widget.Switch? = null

    /** 「极致省电」的开关引用，同样需要回读。 */
    private var saver4gSwitch: android.widget.Switch? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refresh()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.status_ok))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(wrap(buildUi()))
        registerShortcut()
        requestPermissionsIfNeeded()
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        // 保活：进界面时按功能开关同步一次常驻服务（前台启动不受后台限制）
        KeepAliveService.sync(this)
        refresh()
        // 服务实例在 startForegroundService 之后才创建，立即投递一次刷新把状态补正
        window.decorView.post { refresh() }
        // 不再自动恢复球：球的有无完全由开关决定，避免"打开 App 就冒出来"
        ForegroundWatcherService.refresh()
    }

    /**
     * 通知权限是保活的前提：Android 13+ 没有它，前台服务的通知被静默拦截，
     * 用户会以为"服务没起来"而实际在跑。只申请这一项，其余权限走系统设置页。
     */
    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        val root = page()

        root.addView(title(getString(R.string.app_name)))
        root.addView(
            text(getString(R.string.home_subtitle), 13f, colorBody)
                .apply { setPadding(0, dp(8), 0, 0) }
        )

        // ---- 授权状态 ----
        // 已就绪：显示绿色"已就绪"；未就绪：显示可点的"去开启 ›"。
        // 合并成一列后不再需要下面四个独立按钮，未授权时直接点这一行即可跳转。
        root.addView(sectionTitle(getString(R.string.section_status)))
        root.addView(card {
            shizukuValue = authRow(it, getString(R.string.label_shizuku)) { onShizukuClick() }
            accessibilityValue = authRow(it, getString(R.string.label_accessibility)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            overlayValue = authRow(it, getString(R.string.label_overlay)) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Throwable) {
                    toast(getString(R.string.status_unknown))
                }
            }
            batteryValue = authRow(it, getString(R.string.label_battery)) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                    toast(getString(R.string.status_unknown))
                }
            }
            // 后台常驻状态：不是权限，而是「常驻服务在不在」。
            // 它在 = 悬浮球所在进程被抬到前台优先级，不会被系统冻结。
            keepAliveValue = authRow(it, getString(R.string.label_keepalive)) {
                when {
                    KeepAliveService.isRunning() -> toast(getString(R.string.keep_running))
                    !prefs.anyFeatureEnabled ->
                        toast(getString(R.string.toast_need_feature_first))
                    else -> {
                        KeepAliveService.ensureRunning(this)
                        toast(getString(R.string.keep_toast_started))
                        window.decorView.post { refresh() }
                    }
                }
            }
        })

        // ---- 功能 ----
        root.addView(sectionTitle(getString(R.string.section_features)))
        root.addView(featureEntry(
            titleText = getString(R.string.feature_force_scroll),
            descText = getString(R.string.feature_force_scroll_desc),
            initial = prefs.forceScrollEnabled,
            requiredAuth = { forceScrollAuth() },
            onToggle = { checked ->
                // 这个开关就是功能总开关：开=球出现，关=球消失
                prefs.forceScrollEnabled = checked
                if (checked) prefs.ballLabel = getString(R.string.feature_force_scroll)
                ForegroundWatcherService.refresh()
                // 无障碍服务没连接时上面的 prefListener 收不到通知，这里显式同步一次保活
                KeepAliveService.sync(this)
                window.decorView.post { refresh() }
            },
            onClick = { startActivity(Intent(this, ForceScrollActivity::class.java)) },
            switchRef = { forceScrollSwitch = it }
        ))

        root.addView(featureEntry(
            titleText = getString(R.string.feature_saver_4g),
            descText = getString(R.string.feature_saver_4g_desc),
            initial = prefs.saver4gEnabled,
            requiredAuth = { saver4gAuth() },
            onToggle = { checked ->
                prefs.saver4gEnabled = checked
                // 关掉时要立刻还原，不能等下一次省电广播——那时可能已经不在省电模式里了
                Saver4G.onSwitchChanged(this)
                KeepAliveService.sync(this)
                window.decorView.post { refresh() }
            },
            onClick = { startActivity(Intent(this, Saver4GActivity::class.java)) },
            switchRef = { saver4gSwitch = it }
        ))

        root.addView(
            text(getString(R.string.feature_coming), 12f, colorHint)
                .apply { setPadding(dp(2), dp(14), 0, 0) }
        )

        // ---- 关于 ----
        root.addView(sectionTitle(getString(R.string.section_about)))
        root.addView(card {
            val v = row(it, getString(R.string.label_version))
            v.text = versionName()
            v.setTextColor(colorTitle)
        })
        root.addView(
            text(getString(R.string.footer_note), 12f, colorHint)
                .apply { setPadding(dp(2), dp(14), 0, 0) }
        )

        return root
    }

    /**
     * 功能入口：标题 + 说明 + 右侧总开关。整行可点进次级页，开关独立响应。
     * requiredAuth 由各功能自行提供，开关会据此校验，缺什么就提示什么。
     */
    private fun featureEntry(
        titleText: String,
        descText: String,
        initial: Boolean,
        requiredAuth: () -> List<String>,
        onToggle: (Boolean) -> Unit,
        onClick: () -> Unit,
        switchRef: (android.widget.Switch) -> Unit = {}
    ): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(colorCard)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(titleText, 16f, colorTitle, bold = true))
            addView(
                text(descText, 12f, colorBody)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
        }
        line.addView(left)
        val sw = android.widget.Switch(this).apply {
            isChecked = initial
            // 用 OnClickListener 而不是 OnCheckedChangeListener：
            // 校验不通过时要把开关弹回去，用后者会递归触发自己。
            setOnClickListener {
                if (isChecked) {
                    val missing = requiredAuth()
                    if (missing.isNotEmpty()) {
                        isChecked = false
                        toast(getString(R.string.toast_need_auth, missing.joinToString("、")))
                    } else {
                        onToggle(true)
                    }
                } else {
                    onToggle(false)
                }
            }
        }
        line.addView(sw)
        switchRef(sw)
        return line
    }

    /**
     * 「强制滑动」需要的授权。
     * 每个功能各自声明自己的依赖：以后新增功能写自己的检查函数传给 featureEntry 即可，
     * 不要在这里堆 if 判断功能名。
     */
    /**
     * 动态注册桌面快捷方式（长按应用图标 → 开关悬浮球）。
     *
     * 静态声明（shortcuts.xml）里的 android:icon 在这台机器上读不出，
     * 系统记录的 icon 永远是 null，launcher 于是不展示；
     * 动态注册直接把 Icon 对象交给系统，不经过 XML 资源解析，最稳。
     */
    private fun registerShortcut() {
        try {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            val info = android.content.pm.ShortcutInfo.Builder(this, "toggle_force_scroll")
                .setShortLabel(getString(R.string.sc_toggle_short))
                .setLongLabel(getString(R.string.sc_toggle_long))
                .setIcon(
                    android.graphics.drawable.Icon.createWithResource(
                        this, R.drawable.ic_launcher
                    )
                )
                .setIntent(
                    Intent(this, ShortcutToggleActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                    }
                )
                .build()
            sm.dynamicShortcuts = listOf(info)
            android.util.Log.i("SwissKnife", "shortcut registered, max=${sm.maxShortcutCountPerActivity}")
        } catch (t: Throwable) {
            android.util.Log.e("SwissKnife", "shortcut register failed", t)
        }
    }

    private fun forceScrollAuth(): List<String> {
        val missing = mutableListOf<String>()
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
            missing.add(getString(R.string.label_shizuku))
        }
        if (!isAccessibilityEnabled()) {
            missing.add(getString(R.string.label_accessibility))
        }
        return missing
    }

    /**
     * 「极致省电」只依赖 Shizuku：改网络制式走 shell，全程不需要无障碍服务。
     * 每个功能声明自己的依赖，不要为了省事让它跟着「强制滑动」一起要无障碍。
     */
    private fun saver4gAuth(): List<String> {
        val missing = mutableListOf<String>()
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
            missing.add(getString(R.string.label_shizuku))
        }
        return missing
    }

    /** 授权行：整行可点，右侧 value 反映是否已就绪。 */
    private fun authRow(parent: LinearLayout, label: String, onFix: () -> Unit): TextView {
        val v = row(parent, label)
        (v.parent as? android.view.View)?.apply {
            isClickable = true
            setOnClickListener { onFix() }
        }
        return v
    }

    /**
     * 已就绪显示绿色状态；未就绪显示"去开启 ›"，并给整行加淡橙圆角底。
     * 光有文字颜色不足以让人意识到"这里能点"，加底之后一眼就是按钮。
     *
     * okText / badText 可覆盖默认文案：后台常驻这类状态行不是权限，
     * 说"已就绪/去开启"会误导，改用"运行中/未启动"。
     */
    private fun setAuth(
        view: TextView,
        ok: Boolean,
        okText: Int = R.string.status_ok,
        badText: Int = R.string.status_go_enable
    ) {
        val line = view.parent as? View
        if (ok) {
            view.text = getString(okText)
            view.setTextColor(colorOk)
            line?.background = null
            view.background = null
            view.setPadding(0, 0, 0, 0)
        } else {
            view.text = getString(badText)
            view.setTextColor(colorWarn)
            // 底色只包住这几个字，比卡片底色略深一档，够提示又不抢眼
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFFEAEAE6.toInt())
            }
            view.setPadding(dp(10), dp(3), dp(10), dp(3))
        }
    }

    private fun refresh() {
        // 授权状态：已就绪显示绿色，未就绪显示可点的"去开启 ›"
        setAuth(shizukuValue, ShizukuShell.isAvailable() && ShizukuShell.hasPermission())
        setAuth(accessibilityValue, isAccessibilityEnabled())
        // 悬浮窗按真实授权状态判断，只有「未开启 / 已就绪」两种，不再出现"未使用"
        // （悬浮球本身用的是无障碍自带的 overlay，不需要这项权限）
        setAuth(overlayValue, Settings.canDrawOverlays(this))
        setAuth(batteryValue, batteryIgnored())
        setAuth(
            keepAliveValue,
            KeepAliveService.isRunning(),
            R.string.keep_running,
            R.string.keep_stopped
        )
        // 开关的真实状态只有 Prefs 知道：快捷方式、长按收球都会绕过界面改它，
        // 不回读就会出现"开关显示关闭、球却在屏幕上"的矛盾。
        forceScrollSwitch?.isChecked = prefs.forceScrollEnabled
        saver4gSwitch?.isChecked = prefs.saver4gEnabled
    }

    private fun setState(view: TextView, textRes: Int, color: Int) {
        view.text = getString(textRes)
        view.setTextColor(color)
    }

    private fun onShizukuClick() {
        when {
            !ShizukuShell.isAvailable() -> toast(getString(R.string.toast_shizuku_missing))
            ShizukuShell.hasPermission() -> toast(getString(R.string.status_ok))
            else -> ShizukuShell.requestPermission()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (ForegroundWatcherService.isRunning()) return true
        val expected = ComponentName(this, ForegroundWatcherService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(expected.flattenToString(), ignoreCase = true) }
    }

    private fun batteryIgnored(): Boolean = try {
        val pm = getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(packageName) == true
    } catch (_: Throwable) {
        false
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
    }
}
