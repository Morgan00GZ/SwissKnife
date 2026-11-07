# SwissKnife

A small collection of system-level Android utilities driven by Shizuku. Four features so far: **Force Scroll** — when a video ad locks touch input during its countdown, one tap on the floating ball injects a single scroll and gets you past the ad; **Extreme Saver** — while the system is in battery-saver mode, the phone is dropped from 5G to 4G and restored afterwards; **Eye Break** — a full-screen rest overlay appears once continuous screen time reaches a threshold; **Minimum Brightness** — brightness is pulled back up whenever it is dragged below the floor you set. No root required.

一组由 Shizuku 驱动的系统级安卓小工具。目前有四个功能：**强制滑动** —— 视频广告倒计时期间会锁住触摸，点一下屏幕边缘的悬浮球即可补一次滑动；**极致省电** —— 省电模式期间自动把网络从 5G 降到 4G，退出省电再还原；**护眼休息** —— 连续用眼到阈值铺一层全屏休息浮窗；**最低亮度守护** —— 亮度被调得低于你设的下限时自动拉回。无需 root。

---

## What it does / 它做什么

Many apps show a full-screen or half-screen video ad with a countdown, and deliberately swallow touch input so you cannot skip to the next item. The screen is not frozen — only the touch dispatch on that view is locked — so a **generic motion event** (`ACTION_SCROLL`) still reaches the app.

The app draws a small translucent ball on top of everything, in a window created by an accessibility service, so it sits above the status bar. Tapping it asks Shizuku to inject exactly one `ACTION_SCROLL` at the centre of the screen. The ad's touch lock does not cover that code path, so the list scrolls.

很多应用的全屏/半屏视频广告带倒计时，并且会吞掉触摸事件，让你无法划到下一个作品。屏幕本身没有冻结——被锁住的只是那一层的触摸分发——因此**通用运动事件**（`ACTION_SCROLL`）依然能送进应用。

本应用用一个无障碍服务创建层级高于状态栏的窗口，在上面画一个半透明小球。点一下，通过 Shizuku 在屏幕中心注入一次 `ACTION_SCROLL`。广告的触摸锁不覆盖这条路径，列表就滚过去了。

---

## Features / 功能

| Feature | 功能 | Notes / 说明 |
|---|---|---|
| Floating ball | 悬浮球 | 56 dp fixed circle, 62 % white, 1 dp hairline stroke so it stays visible on both white and dark backgrounds. 56 dp 固定圆形，62 % 白底，1 dp 极淡描边，白色界面与深色视频上都能看出轮廓 |
| Tap = inject one scroll | 点击补滑 | Injects `ACTION_SCROLL` at the screen centre. Text turns orange for 400 ms when injection fails; nothing else flashes. 在屏幕中心注入一次滚动。失败时只把文字变橙 400 ms，不闪背景 |
| Drag and snap | 拖动吸附 | Drag anywhere, release and it snaps to the nearer screen edge. Position persists. 可拖到任意位置，松手吸附到较近的一侧，位置会记住 |
| Long press = off | 长按关闭 | Pressing for 600 ms turns the feature off and removes the ball — one gesture, one consistent state. 长按 600 ms 关闭功能并移除球，一个动作一个状态 |
| Label follows the feature | 球上文字随功能 | The ball reads its text from `Prefs.ballLabel`; the feature writes its own name. Reusable for future tools. 球的文字读 `Prefs.ballLabel`，由功能写入自己的名字，为以后复用做准备 |
| Launcher shortcut | 桌面快捷方式 | Long-press the app icon to toggle the feature without opening the app. 长按图标即可开关，不必进入应用 |
| Framework-level keep-alive | 框架级保活 | Foreground service + accessibility watchdog + Doze whitelist + boot rebind, so the ball does not silently die in the background. 前台服务 + 无障碍看门狗 + Doze 白名单 + 开机重绑，球不会在后台静默消失 |
| Drop 5G while in battery saver | 极致省电 | Entering battery-saver mode cuts the default SIM from "5G-capable" to "4G and below"; leaving it writes the captured original value back. No new permission, no accessibility dependency. 进入省电模式时把默认卡从「含 5G」降到「只到 4G」，退出时写回原值。不新增权限，不依赖无障碍 |
| Full-screen rest overlay | 护眼休息 | Accumulates screen-on (or gaze) time; at the threshold it covers the screen with a countdown, swallows Back / Home / Recents, and dismisses itself when the countdown ends. 累计亮屏（或注视）时长，到阈值铺一层全屏倒计时浮窗，期间吞掉返回 / Home / 最近任务，倒计时结束自动收起 |
| Minimum brightness floor | 最低亮度守护 | A `ContentObserver` on `SCREEN_BRIGHTNESS`: below your floor it is written back immediately, above it nothing happens. No accessibility dependency. 监听系统亮度，低于你设的下限立刻写回，高于下限完全不干预。不依赖无障碍 |
| Hidden from Recents | 不进最近任务 | `android:excludeFromRecents="true"` on the launcher activity, so the tool never leaves a card in the task switcher. 声明在 launcher Activity 上，应用不会在「最近任务」里留下卡片 |

**Show and hide are manual, by design.** Five automatic foreground-detection strategies were measured and all failed on the test device — see [docs/archive/](docs/archive/README.md). There is a toggle on the home screen, a launcher shortcut, and long-press-to-hide.

**显隐是手动的，这是刻意设计。** 五条自动前台判断路径在实测机型上全部失败（见 [docs/archive/](docs/archive/README.md)）。现有三条手动入口：首页总开关、桌面快捷方式、长按球收起。

---

## How it works / 实现路径

```
MainActivity ────────────── 授权状态、功能开关、动态注册快捷方式
  ├─ ForceScrollActivity ─── feature page: status / dependencies / how-to
  ├─ Saver4GActivity ────── feature page: same layout (BaseActivity.featurePage)
  ├─ EyeBreakActivity ───── feature page + 调参（阈值分钟）
  └─ BrightGuardActivity ── feature page + 调参（亮度下限）

ForegroundWatcherService ─── 无障碍服务
  ├─ 持有悬浮球（TYPE_ACCESSIBILITY_OVERLAY，层级高于状态栏）
  ├─ 拖动 / 吸附 / 点击 / 长按
  ├─ 点击 → ScrollInjector
  ├─ 休息浮窗（TYPE_ACCESSIBILITY_OVERLAY）+ 按键拦截 + 来电撤窗
  └─ checkKeepAlive()  ─── 保活看门狗，借窗口事件 30 秒节流拉起前台服务

KeepAliveService ────────── 前台服务（specialUse），START_STICKY
  ├─ 有功能开启才常驻；Shizuku 可用时补 Doze 白名单
  ├─ Saver4G.attach / detach       省电广播
  ├─ EyeBreak.attach / detach      屏幕广播 + 注视采样 + 到期定时器
  └─ BrightGuard.attach / detach   亮度 ContentObserver

ScrollInjector ─────────── 通过 Shizuku（shell，uid 2000）执行
  └─ assets/inject.dex → app_process → 反射 InputManagerGlobal.injectInputEvent
        MotionEvent.ACTION_SCROLL + SOURCE_MOUSE + AXIS_VSCROLL
```

**Why an injected dex instead of `adb shell input`.** `input roll` builds its `MotionEvent` with coordinates fixed at `(0.0, 0.0)`, which lands on the status bar and is dropped by InputDispatcher — it looks like "the app does not support scroll wheels" but nothing is ever delivered. A self-built injection with explicit coordinates works; so a small Java class is compiled to `assets/inject.dex` and run as `app_process` under the Shizuku shell.

**为什么自己注入而不是用 `input` 命令。** `input roll` 构造 `MotionEvent` 时坐标固定为 `(0.0, 0.0)`，落在状态栏区域被 InputDispatcher 直接丢弃——现象像"这个应用不支持滚轮"，其实是事件根本没送到。自建注入显式带坐标即可；因此有一个小 Java 类编译成 `assets/inject.dex`，由 Shizuku 的 shell 以 `app_process` 方式运行。

Note: Android 17 removed `MotionEvent.setAxisValue`. The injector uses `PointerCoords.setAxisValue`, which is public API and still exists.

注意：Android 17 已删除 `MotionEvent.setAxisValue`，注入器用的是 `PointerCoords.setAxisValue`（公开 API，一直存在）。

---

## Extreme Saver / 极致省电

Battery-saver mode already means the battery is tight, and 5G is the most power-hungry part of the radio. With the switch on, the app waits for `ACTION_POWER_SAVE_MODE_CHANGED`: entering saver mode it clears the NR bit from the default SIM's allowed network types; leaving saver mode it writes the captured original mask back.

省电模式意味着电量已经紧张，而 5G 是射频里最耗电的一环。打开开关后，应用监听 `ACTION_POWER_SAVE_MODE_CHANGED`：进入省电时清掉默认卡允许制式里的 NR 位，退出时写回进入省电前的原始掩码。

```
dumpsys isub                                   → defaultDataSubId → simSlotIndex + allowedNetworkTypesForReasons=user=<mask>
cmd phone set-allowed-network-types-for-users  -s <slot> <binary mask>     ← the mask argument is binary, and -s is mandatory
dumpsys telephony.registry                     → getRilDataRadioTechnology  ← observed, as opposed to policy
```

The detail page shows both: **policy** (what the system is allowed to use) and **observed** (what the modem is actually on). They disagree for a few seconds while the radio re-registers, and only both together mean "4G really took effect".

二级页同时展示两层：**策略**（系统被允许用哪些制式）与**实测**（modem 实际在哪一代网络）。射频重注册那几秒两者会不一致，只有两者都指向 4G 才算真的生效。

| Behaviour / 行为 | |
|---|---|
| Only acts when the switch is on | 只有开关打开才动手 |
| Only the default data SIM, slot read from `dumpsys isub` | 只作用于默认数据卡，槽位取自 `dumpsys isub` |
| Restore is **forced** — the pre-saver value is written back even if the mode was changed by hand meanwhile | 还原是**强制**的：即使省电期间手动改过网络模式，也写回进入省电前的值 |
| If NR is already absent (user or ROM already dropped it) nothing is written and no restore point is recorded | 若 NR 本就不在（用户或系统已经降过），不写、也不记还原点 |
| Three entry points reconcile against the current facts: service start, switch change, broadcast | 服务启动、开关变化、广播三个入口都按当前事实对账，漏广播也能自愈 |

Measured on the test device: the change takes effect within 5 s and mobile data re-registers once; turning the switch off restores immediately, without waiting for saver mode to end.

实测：切换 5 秒内生效，期间移动数据重注册一次；关掉开关立即还原，不必等省电结束。

**The ROM may already do this on its own.** The test device (HyperOS) changes `preferred_network_mode` by itself when saver mode engages, which is why the app treats "NR already absent" as success and records no restore point — it never fights the system's own restore.

**系统自己可能已经这么做了。** 实测机（澎湃OS）在省电模式生效时会自行修改 `preferred_network_mode`；因此应用把「NR 已经不在」当作已达目标、且不记还原点，不与系统自带的还原逻辑对着写。

---

## Eye Break & Minimum Brightness / 护眼休息与最低亮度守护

Both come from a separate app, **EyeStrainRelief**, and were ported over with their logic untouched. They are **two parallel features**: different switches, different pages, no dependency on each other. Only Eye Break needs the accessibility service.

这两个功能来自另一个独立应用 **EyeStrainRelief**，逻辑一行未改地搬了过来。它们是**两个平行功能**：各自的开关、各自的功能页、互不依赖。只有护眼休息需要无障碍服务。

**Eye Break / 护眼休息**

```
Prefs.eyeThresholdMs        default 20 min
SessionTimer                interval settlement + lazy evaluation, zero polling
  ├─ screen on/off broadcast  … off > 20 s clears the count, shorter gaps keep accumulating
  └─ AttentionProbe            cmd attention → 1 = looking, 3 = not looking, anything else = unknown
                              two consecutive "not looking" freeze the timer; unknown falls back to screen time
        ↓ threshold reached
ForegroundWatcherService.showBreak()   TYPE_ACCESSIBILITY_OVERLAY, full screen
  ├─ BreakOverlay             #0B0D10 background, 132 sp countdown, clock, pause button
  ├─ onKeyEvent               swallow Back / Home / Recents; volume-down ×5 escapes
  └─ WakeLock                 FLAG_KEEP_SCREEN_ON is useless on accessibility overlays
        ↓ countdown ends
BreakNotifier                 system notification sound (≤ 2 s) + two-pulse vibration
```

While the overlay is up, a window-state change re-attaches it to the top (the user may have switched apps behind it), and a ringing call dismisses it outright — a rest overlay above the call UI would mean a missed call.

浮窗显示期间，窗口切换事件会把它重新置顶（用户可能在后面切了应用）；来电响铃则直接撤窗——压在来电界面之上的休息浮窗意味着漏接电话。

**Minimum Brightness / 最低亮度守护**

`ContentObserver` on `SCREEN_BRIGHTNESS` → below the floor → write the floor back. Two channels: `WRITE_SETTINGS` (direct) or Shizuku (`settings put system screen_brightness`). The threshold is a **control value 0–255, not a percentage**: on the test device the system slider is a perceptual scale (≈ `100·√(value/255)`), so a control value of 64 sits about halfway up the bar. The page shows both so neither number lies.

监听 `SCREEN_BRIGHTNESS` → 低于下限就写回下限。两条通道：`WRITE_SETTINGS`（直写）或 Shizuku（`settings put system screen_brightness`）。阈值是**控制值 0–255，不是百分比**：实测机的系统亮度条是感知刻度（约 `100·√(值/255)`），控制值 64 大约只走到一半。页面上两个数都显示，避免哪个数字撒谎。

Closing the switch stops the guard immediately. There is no "restore" step by design — the guard only ever corrects downward drift, so it has no state to put back.

关掉开关立即停止纠偏。设计上**没有**"还原"步骤——守卫只纠正向下的漂移，没有需要放回去的状态。

---

## Permissions & system dependencies / 权限与系统依赖

| Item | 项目 | Why / 原因 |
|---|---|---|
| Shizuku (running + granted) | Shizuku（运行中且已授权） | The app itself cannot inject events into another app's window. Shizuku lends it a `shell` (uid 2000) identity. 应用自身无权向别的应用窗口注入事件，Shizuku 借出 shell 身份 |
| Accessibility service | 无障碍服务 | Provides `TYPE_ACCESSIBILITY_OVERLAY`, the only window type that can sit above the status bar without `SYSTEM_ALERT_WINDOW`. 提供 `TYPE_ACCESSIBILITY_OVERLAY`，这是除悬浮窗权限外唯一能压在状态栏之上的窗口类型 |
| `POST_NOTIFICATIONS` (API 33+) | 通知权限 | The keep-alive foreground service needs a visible notification. 常驻前台服务需要一条可见通知 |
| `RECEIVE_BOOT_COMPLETED` | 开机广播 | Restore the foreground service after a reboot. 重启后恢复常驻服务 |
| `WAKE_LOCK` | 唤醒锁 | Screen-on lock while the rest overlay counts down — `FLAG_KEEP_SCREEN_ON` has no effect on accessibility overlays. 浮窗倒计时期间持有屏幕常亮锁——`FLAG_KEEP_SCREEN_ON` 对无障碍浮窗无效 |
| `VIBRATE` | 震动 | Two-pulse vibration when a break ends. install-time, no authorisation row. 休息结束的两下短震。安装期权限，不需要授权行 |
| `WRITE_SETTINGS` | 修改系统设置 | Direct brightness write for the guard; without it the app falls back to Shizuku. 亮度守卫的直写通道；没有就退回 Shizuku |
| `READ_PHONE_STATE` | 电话状态 | Dismiss the rest overlay when a call rings; without it the app falls back to call-UI window names. 响铃时撤掉休息浮窗；没有就降级为来电界面窗口名检测 |

Extreme Saver adds **no permission at all**: battery-saver detection is a public API plus a system broadcast, and the network change goes through the Shizuku identity the app already holds.

极致省电**没有新增任何权限**：省电检测是公开 API 加系统广播，改网络用的是应用本来就有的 Shizuku 身份。

Minimum Brightness needs **no accessibility service**: enabling only that feature asks for nothing but brightness access, which is exactly why it is a separate switch from Eye Break.

最低亮度守护**不需要无障碍服务**：只开这一个功能时，除亮度权限外不会被要求任何东西——这正是它必须与护眼休息分开成一个开关的原因。

> `dumpsys package` also lists `com.swissknife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. That one is **added by the platform** because a receiver is registered with `Context.RECEIVER_NOT_EXPORTED` — it is not declared in the manifest.
>
> `dumpsys package` 里还会看到 `com.swissknife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。这条是**系统自动加的**（接收者用 `Context.RECEIVER_NOT_EXPORTED` 注册就会生成），清单里并没有声明它。

**No `INTERNET` permission at all** — everything runs locally, nothing is uploaded, there is no backend.

**完全不申请 `INTERNET` 权限** —— 全部本地运行，无上报、无后端。

The permission list is also a **least-privilege list**, and two permissions were deliberately dropped in the v0.2 cleanup rather than kept "just in case":

这份权限表同时是**最小权限表**。v0.2 收尾时主动删掉了两项，而不是留着"备而不用"：

| Dropped / 已移除 | Was for / 原本用途 | Why it is gone / 为什么删 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Listing installed apps for the per-app whitelist. 为应用白名单枚举已安装应用 | The whitelist was deleted; the ball is global. 白名单已删除，球全局可用 |
| `PACKAGE_USAGE_STATS` | Reading the foreground app for auto show/hide. 读前台应用以自动显隐 | That design was measured and dropped — five strategies failed. 该方案实测失败已放弃（五条路） |

The accessibility service declaration is still minimal: it subscribes to **one** event type (`typeWindowStateChanged`) and declares **no** window-content access. What it does with that event is a heartbeat for the keep-alive watchdog, plus — only while the rest overlay is showing — re-attaching the overlay and dismissing it on a call. It does declare `flagRequestFilterKeyEvents`, which is a **separate channel** and adds no event subscriptions at all; `onKeyEvent` returns `false` on its first line unless the rest overlay is up.

无障碍服务声明仍然最小化：只订阅**一种**事件（`typeWindowStateChanged`），并且**不声明**任何窗口内容读取能力。它拿这个事件做的事是给保活看门狗当心跳；另外**仅在休息浮窗显示期间**把浮窗重新置顶、以及来电阻断。它确实声明了 `flagRequestFilterKeyEvents`，但那是**另一条通路**、不增加任何事件订阅；浮窗不在时 `onKeyEvent` 第一行就返回 `false`。

**`SYSTEM_ALERT_WINDOW` is never requested**, and the home screen no longer shows a row for it. The ball is an accessibility overlay, which does not need that permission; no current feature uses a plain overlay either. A row that is permanently stuck at "去开启 ›" teaches the user to grant a permission that changes nothing.

**从不申请 `SYSTEM_ALERT_WINDOW`**，首页也不再展示这一行。球用的是无障碍 overlay，不需要该权限；现有功能也都不用普通悬浮窗。一个永远停在「去开启 ›」的行，只会教用户去开一个开了也没用的权限。

---

## Build / 构建

Requires JDK 17 and Android SDK platform 35. No Android Studio needed.

需要 JDK 17 与 Android SDK platform 35，不需要 Android Studio。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

Three build-environment notes — the repository itself carries **no** machine-specific paths, and a fresh clone was verified to build (payload-identical to the release APK):

三条构建环境说明 —— 仓库内**不含任何本机路径**，已实测「干净克隆 → 直接构建通过」（产物与发布 APK 内容一致）：

1. **The checkout path must be pure ASCII.** AGP refuses to apply on a project path containing non-ASCII characters and fails with `Your project path contains non-ASCII characters`; the workaround flag `android.overridePathCheck=true` exists but is not recommended. Cloning into a folder named 中文 or `代码/` will hit this.
   **检出路径必须是纯 ASCII。** AGP 拒绝在含非 ASCII 字符的项目路径上应用，报 `Your project path contains non-ASCII characters`；虽有 `android.overridePathCheck=true` 可绕过，但不建议。克隆到「中文」或「代码/」这类目录必踩。
2. The wrapper downloads Gradle from a Tencent Cloud mirror, and repositories are Aliyun-first — the official sources block for minutes on some networks.
   wrapper 从腾讯云镜像下载 Gradle，仓库以阿里云优先 —— 部分网络下官方源会长时间阻塞。
3. **If `:app:processDebugResources` fails with `stableIds.txt: error: failed to open`** (the message reads 数据无效 on a Chinese Windows), the aapt2 that AGP downloads from Maven cannot write its `--emit-ids` target file on that machine. Point Gradle at the SDK's own binary from **your own** user-level Gradle properties — never in the repository:
   **若 `:app:processDebugResources` 报 `stableIds.txt: failed to open`（中文 Windows 上显示「数据无效」）**，说明该机器上 AGP 从 Maven 下载的 aapt2 写不了 `--emit-ids` 目标文件。在自己机器的**用户级** Gradle 属性里指向 SDK 自带的二进制，**不要写进仓库**：

   ```properties
   # %GRADLE_USER_HOME%/gradle.properties (or ~/.gradle/gradle.properties)
   android.aapt2FromMavenOverride=/path/to/android-sdk/build-tools/<ver>/aapt2
   ```

   This is a machine quirk, not a project one. Note that `GRADLE_USER_HOME` may be redirected — the portable toolchain setups used during development point it away from `%USERPROFILE%\.gradle`, so check the value before creating the file.
   这是机器问题，不是项目问题。注意 `GRADLE_USER_HOME` 可能被改过——便携式工具链会把它指到别处，先确认这个值再建文件。

There is no signing config yet: release builds are unsigned, only debug builds are produced.

目前没有签名配置：release 未签名，只出 debug 包。

---

## Install / 安装

```bash
adb install -r app-debug.apk
```

Then, in order:

然后按顺序做：

1. Open the app → **Shizuku** row → grant permission.
   打开应用 → **Shizuku** 行 → 授权。
2. **无障碍服务** row → enable "SwissKnife" in system settings.
   **无障碍服务** 行 → 在系统设置里启用「瑞士军刀」。
3. Turn on the **强制滑动** switch — the ball appears. For **极致省电** only step 1 is required: it never needs the accessibility service or the ball.
   打开 **强制滑动** 开关 —— 球出现。**极致省电** 只需要第 1 步：它不需要无障碍服务，也不需要球。
4. **护眼休息** needs steps 1–2 (the overlay is an accessibility overlay). **最低亮度守护** needs neither: grant **修改系统设置** (or keep Shizuku) and turn it on.
   **护眼休息** 需要第 1–2 步（浮窗是无障碍浮窗）。**最低亮度守护** 两样都不需要：授权**修改系统设置**（或保持 Shizuku 在线）后打开开关即可。

> Reinstalling the APK invalidates the accessibility binding on some ROMs: the service stays listed as enabled but never receives `onServiceConnected`. Toggling it off and on again fixes it. `tools/deploy.sh` does this automatically for local development.
>
> 覆盖安装后部分 ROM 会让无障碍服务"绑定但不连接"：仍列在已启用里，却不再执行 `onServiceConnected`。关掉再打开一次即可恢复。本地开发用的 `tools/deploy.sh` 已自动执行这一步。

---

## Known limitations / 已知限制

1. **Shizuku does not survive a reboot** in wireless-debugging mode (a non-root device has no way to autostart it). The ball comes back, but tapping it fails until Shizuku is started again.
   **Shizuku 重启后不会自动运行**（无线调试模式的固有限制，非 root 设备无法自启）。球会回来，但要点它得先重新启动 Shizuku。
2. **Injection is blind.** Whether the target app actually scrolled cannot be observed, so there is no automatic retry — one tap is one attempt.
   **注入是"盲发"**。目标应用到底有没有滚动无法观测，因此不做自动重试，一次点击就是一次尝试。
3. **Full-screen gestures cannot be intercepted** by any app window; the system consumes them above everything.
   **全面屏手势无法拦截**，系统在所有应用窗口之上消费。
4. **Doze whitelist is best-effort.** It is set through Shizuku; without the grant, the user has to allow background running manually in system settings.
   **Doze 白名单是尽力而为**。通过 Shizuku 设置；没有授权时需用户到系统设置里手动允许后台运行。
5. **Vendor autostart management is out of reach.** On HyperOS/MIUI the app may still need "自启动" enabled by hand.
   **厂商自启动管理触不到**。澎湃OS/MIUI 上可能仍需手动打开「自启动」。
6. **Extreme Saver needs Shizuku alive.** Without it the network is left untouched and the detail page says why; it never claims "applied" while Shizuku is gone. If Shizuku is not running when saver mode ends, the phone stays on 4G until Shizuku is back and a reconcile runs (service start, switch change or the next broadcast).
   **极致省电依赖 Shizuku 在线**。没有它就不动网络，页面会显示原因，绝不谎报「已生效」。若退出省电时 Shizuku 恰好不在，手机会停在 4G，直到 Shizuku 恢复并触发一次对账（服务启动 / 开关变化 / 下一次广播）。
7. **Changing the network type re-registers the radio.** Mobile data drops for a few seconds (measured ≤ 5 s) and an in-flight call or download may be interrupted.
   **切换制式会让射频重注册**。移动数据中断数秒（实测 ≤ 5 秒），进行中的通话或下载可能被打断。
8. **Only the default data SIM is touched.** The second SIM of a dual-SIM setup keeps its own setting.
   **只改默认数据卡**。双卡里的另一张保持原有设置。
9. **The eye-break threshold is armed by a `Handler` timer that this device sometimes drops.** The timer is re-armed on every 30-second watchdog tick, so the worst case is a delay of one watchdog interval — never a missed break.
   **护眼休息的到期表用 `Handler` 定时器，而这台机器偶尔会丢弃它**。定时器在每次 30 秒看门狗心跳时重挂，因此最坏情况是延迟一个看门狗间隔，不会漏掉休息。
10. **The timing progress lives in memory.** A process kill restarts the count from zero; only settings (threshold, brightness floor, switches) are persisted. This matches the standalone app.
   **计时进度是内存态**。进程被杀就从零重新计时；只有设置项（阈值、亮度下限、开关）会持久化。这与独立版一致。
11. **Gaze detection is Xiaomi-specific and optional.** It needs `com.xiaomi.aon` plus Shizuku; without a result the app falls back to plain screen-on timing, which is the documented behaviour rather than a failure.
   **注视检测是小米专属且可选的**。需要 `com.xiaomi.aon` 与 Shizuku；取不到结果就退回纯亮屏计时，这是设计内的降级，不是故障。
12. **The brightness floor fights auto-brightness by design.** The system keeps adjusting to ambient light, so you may see the brightness hover near the floor instead of staying exactly on it.
   **亮度下限与自动亮度会持续拉锯，这是机制本身的结果**。系统会按环境光持续调整，所以亮度可能在阈值附近小幅波动，而不是精确停在阈值上。

---

## Repository layout / 仓库结构

```
.
├─ app/src/main/java/com/swissknife/
│  ├─ MainActivity.kt                 home: auth status, feature switches, shortcut
│  ├─ ShortcutToggleActivity.kt       transparent target of the launcher shortcut
│  ├─ core/                           UI base, Prefs, Shizuku command channel
│  ├─ access/                         accessibility service: the ball, the rest overlay, keep-alive watchdog
│  ├─ inject/                         scroll injection through Shizuku
│  ├─ net/                            Extreme Saver: battery-saver listener + network type switch
│  ├─ esr/                            Eye Break + Minimum Brightness (ported from EyeStrainRelief)
│  ├─ keepalive/                      foreground service + boot receiver (attaches every feature hook)
│  └─ ui/                             feature pages (all built on BaseActivity.featurePage)
├─ docs/
│  └─ archive/                        abandoned approaches and why they were dropped
├─ HANDOFF.md                         developer handoff — read this first when resuming
├─ CHANGELOG.md
└─ tools/                             local build/deploy scripts (not pushed)
```

`HANDOFF.md` is the single source of truth for continuing development: current state, the traps already paid for, code map, verification commands.

`HANDOFF.md` 是继续开发的唯一事实来源：当前状态、已经踩过的坑、代码地图、验证命令。

---

## License / 许可证

MIT — see [LICENSE](LICENSE).
