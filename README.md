# SwissKnife

A small collection of system-level Android utilities driven by Shizuku. The first and only feature so far is **Force Scroll**: when a video ad locks touch input during its countdown, one tap on the floating ball injects a single scroll and gets you past the ad. No root required.

一组由 Shizuku 驱动的系统级安卓小工具。目前只有一个功能 **强制滑动**：视频广告倒计时期间会锁住触摸，手指上滑没有反应，点一下屏幕边缘的悬浮球即可补一次滑动。无需 root。

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

**Show and hide are manual, by design.** Five automatic foreground-detection strategies were measured and all failed on the test device — see [docs/archive/](docs/archive/README.md). There is a toggle on the home screen, a launcher shortcut, and long-press-to-hide.

**显隐是手动的，这是刻意设计。** 五条自动前台判断路径在实测机型上全部失败（见 [docs/archive/](docs/archive/README.md)）。现有三条手动入口：首页总开关、桌面快捷方式、长按球收起。

---

## How it works / 实现路径

```
MainActivity ────────────── 授权状态、功能开关、动态注册快捷方式
  └─ ForceScrollActivity ─── 功能页：运行状态、今日计数、使用说明

ForegroundWatcherService ─── 无障碍服务
  ├─ 持有悬浮球（TYPE_ACCESSIBILITY_OVERLAY，层级高于状态栏）
  ├─ 拖动 / 吸附 / 点击 / 长按
  ├─ 点击 → ScrollInjector
  └─ checkKeepAlive()  ─── 保活看门狗，借窗口事件 30 秒节流拉起前台服务

KeepAliveService ────────── 前台服务（specialUse），START_STICKY
  └─ 有功能开启才常驻；Shizuku 可用时补 Doze 白名单

ScrollInjector ─────────── 通过 Shizuku（shell，uid 2000）执行
  └─ assets/inject.dex → app_process → 反射 InputManagerGlobal.injectInputEvent
        MotionEvent.ACTION_SCROLL + SOURCE_MOUSE + AXIS_VSCROLL
```

**Why an injected dex instead of `adb shell input`.** `input roll` builds its `MotionEvent` with coordinates fixed at `(0.0, 0.0)`, which lands on the status bar and is dropped by InputDispatcher — it looks like "the app does not support scroll wheels" but nothing is ever delivered. A self-built injection with explicit coordinates works; so a small Java class is compiled to `assets/inject.dex` and run as `app_process` under the Shizuku shell.

**为什么自己注入而不是用 `input` 命令。** `input roll` 构造 `MotionEvent` 时坐标固定为 `(0.0, 0.0)`，落在状态栏区域被 InputDispatcher 直接丢弃——现象像"这个应用不支持滚轮"，其实是事件根本没送到。自建注入显式带坐标即可；因此有一个小 Java 类编译成 `assets/inject.dex`，由 Shizuku 的 shell 以 `app_process` 方式运行。

Note: Android 17 removed `MotionEvent.setAxisValue`. The injector uses `PointerCoords.setAxisValue`, which is public API and still exists.

注意：Android 17 已删除 `MotionEvent.setAxisValue`，注入器用的是 `PointerCoords.setAxisValue`（公开 API，一直存在）。

---

## Permissions & system dependencies / 权限与系统依赖

| Item | 项目 | Why / 原因 |
|---|---|---|
| Shizuku (running + granted) | Shizuku（运行中且已授权） | The app itself cannot inject events into another app's window. Shizuku lends it a `shell` (uid 2000) identity. 应用自身无权向别的应用窗口注入事件，Shizuku 借出 shell 身份 |
| Accessibility service | 无障碍服务 | Provides `TYPE_ACCESSIBILITY_OVERLAY`, the only window type that can sit above the status bar without `SYSTEM_ALERT_WINDOW`. 提供 `TYPE_ACCESSIBILITY_OVERLAY`，这是除悬浮窗权限外唯一能压在状态栏之上的窗口类型 |
| `POST_NOTIFICATIONS` (API 33+) | 通知权限 | The keep-alive foreground service needs a visible notification. 常驻前台服务需要一条可见通知 |
| `RECEIVE_BOOT_COMPLETED` | 开机广播 | Restore the foreground service after a reboot. 重启后恢复常驻服务 |

**No `INTERNET` permission at all** — everything runs locally, nothing is uploaded, there is no backend.

**完全不申请 `INTERNET` 权限** —— 全部本地运行，无上报、无后端。

Declared but currently unused (left over from a removed per-app whitelist): `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`.

声明但当前未使用（已删除的应用白名单遗留）：`QUERY_ALL_PACKAGES`、`PACKAGE_USAGE_STATS`。

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
3. Turn on the **强制滑动** switch — the ball appears.

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

---

## Repository layout / 仓库结构

```
.
├─ app/src/main/java/com/swissknife/
│  ├─ MainActivity.kt                 home: auth status, feature switches, shortcut
│  ├─ ShortcutToggleActivity.kt       transparent target of the launcher shortcut
│  ├─ core/                           UI base, Prefs, Shizuku command channel
│  ├─ access/                         accessibility service: the ball + keep-alive watchdog
│  ├─ inject/                         scroll injection through Shizuku
│  ├─ keepalive/                      foreground service + boot receiver
│  └─ ui/                             feature page
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
