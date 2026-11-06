# Changelog / 更新记录

All notable changes to this project are recorded here, newest first. Each released version also has an APK and a fuller write-up under `snapshots/v<version>/`.

本项目的重要变更都记在这里，新的在上面。每个已发布的版本在 `snapshots/v<版本>/` 下另有 APK 与更详细的说明。

Format follows [Keep a Changelog](https://keepachangelog.com/); version numbers follow `0.x` while the app is pre-1.0.

格式参考 Keep a Changelog；1.0 之前版本号保持 `0.x`。

---

## [Unreleased] / 未发布

### Removed / 移除

- **Three quarters of the accessibility event subscription.** The service was still subscribed to `typeWindowsChanged`, `typeWindowContentChanged` and `typeViewClicked` from the abandoned auto-show/hide design. It never read those events — it only uses a window-state change as a heartbeat for the keep-alive watchdog — so every tap and every list refresh woke the service for nothing. Now it subscribes to `typeWindowStateChanged` alone, and no longer declares `canRetrieveWindowContent` / `flagRetrieveInteractiveWindows`.
  **无障碍事件订阅砍掉四分之三。** 服务还保留着已废弃自动显隐方案留下的 `typeWindowsChanged`、`typeWindowContentChanged`、`typeViewClicked`。它从不读取这些事件，只拿"窗口切换"当保活看门狗的心跳，等于每一次点击、每一次列表刷新都白唤醒一次。现只订阅 `typeWindowStateChanged`，同时不再声明 `canRetrieveWindowContent` / `flagRetrieveInteractiveWindows`。

  Verified on device: `dumpsys accessibility` reports `eventTypes=TYPE_WINDOW_STATE_CHANGED` and `capabilities=0`. The event the watchdog depends on was already in the old set, so its behaviour is unchanged — only the noise is gone.
  真机验证：`dumpsys accessibility` 显示 `eventTypes=TYPE_WINDOW_STATE_CHANGED`、`capabilities=0`。看门狗依赖的那个事件原本就在集合里，因此行为不变，去掉的只是噪音。

- **Dead residue from the deleted per-app whitelist.** `QUERY_ALL_PACKAGES` and `PACKAGE_USAGE_STATS` permissions, `Prefs.whitelist` with its two helpers, the "使用情况访问" status row on the home screen with its `hasUsageAccess()` probe, the app-picker strings, and the `appops set GET_USAGE_STATS` line in the deploy script. None of it had a reader left.
  **已删除的应用白名单留下的死代码。** `QUERY_ALL_PACKAGES` 与 `PACKAGE_USAGE_STATS` 两项权限、`Prefs.whitelist` 及其两个辅助方法、首页「使用情况访问」状态行与它的 `hasUsageAccess()` 探测、应用选择页的字符串、部署脚本里的 `appops set GET_USAGE_STATS`。这些东西都已没有任何读取者。

- `Prefs.ballHidden`, dead since long-press was changed to "turn the feature off" rather than "hide the ball". Its doc comment described behaviour that no longer existed.
  `Prefs.ballHidden`，自「长按改为关闭功能」起就已无人读取，注释里描述的还是旧行为。

- The accessibility service description shown in the system permission dialog still said it "judges which app is in front" — a leftover from the same design, and untrue since the app stopped doing that. It now says what the service actually does.
  系统无障碍授权页里那句描述还写着"用于判断当前打开的是哪个应用"——同一套方案的遗留，而且应用早就不做这件事了。现改为如实描述。

### Changed / 变更

- **The repository no longer carries a machine-specific path.** `android.aapt2FromMavenOverride` used to sit in the committed `gradle.properties`, pointing at an absolute path under one particular `D:/DeepSeek Harness/...` install — on any other machine that line breaks `:app:processDebugResources` instead of fixing it. It now lives in the local Gradle user home, so a fresh clone builds without edits.
  **仓库内不再有本机绝对路径。** `android.aapt2FromMavenOverride` 原先写在会被提交的 `gradle.properties` 里，指向某台机器上的 `D:/DeepSeek Harness/...`——换台机器那行不是修问题而是制造问题。现已移到本机 Gradle 用户目录，clone 下来直接可构建。

  Verified by cleaning the build and rebuilding from scratch: the override is still required *on this machine* (without it, `stableIds.txt: failed to open: 数据无效 (13)`), and with it in the user-level file the build succeeds. The resulting APK is payload-identical to the v0.1 archive — all 109 entries match by CRC32 and the compressed total is byte-for-byte the same; the 65 KB file-size difference is only zipalign padding.
  验证方式：清空产物后全量重建。覆盖项在**本机**仍然必需（去掉后必报 `stableIds.txt: failed to open: 数据无效 (13)`），放到用户级文件后构建成功。产出的 APK 与 v0.1 存档**内容一致**——109 个条目 CRC32 全等、压缩后总计字节相同，文件大小差的 65 KB 只是 zipalign 填充。

---

## [0.1] — 2026-11-06

First packaged version. Everything below landed before the repository was initialised, so it is recorded as one release rather than several.

首个打包版本。以下内容在仓库初始化之前就已全部完成，因此合并记作一个版本，而不是拆成多次。

### Added / 新增

- **Force Scroll / 强制滑动** — a translucent ball above the status bar; one tap injects a single `ACTION_SCROLL` at the screen centre, so a video ad's touch lock no longer blocks paging.
  **强制滑动** —— 状态栏之上的半透明球，点一下在屏幕中心注入一次 `ACTION_SCROLL`，广告的触摸锁不再挡住翻页。
- **Shizuku injection channel** — `assets/inject.dex` run through `app_process` under the shell identity, reflecting `InputManagerGlobal.injectInputEvent`. Uses `PointerCoords.setAxisValue`, since Android 17 removed `MotionEvent.setAxisValue`.
  **Shizuku 注入通道** —— `assets/inject.dex` 以 shell 身份经 `app_process` 运行，反射 `InputManagerGlobal.injectInputEvent`。使用 `PointerCoords.setAxisValue`，因为 Android 17 已删除 `MotionEvent.setAxisValue`。
- **Framework-level keep-alive / 框架级保活** — five layers, so the ball does not silently disappear when the process is demoted to cached:
  五层结构，避免进程被降为 cached 后球静默消失：

  | Layer / 层 | Mechanism / 机制 |
  |---|---|
  | 1 | Foreground service + `START_STICKY` / 前台服务 + `START_STICKY` |
  | 2 | Accessibility-service watchdog, 30 s throttle, no extra timer / 无障碍看门狗，30 秒节流，不额外注册定时器 |
  | 3 | `android:stopWithTask="false"` |
  | 4 | Doze whitelist `cmd deviceidle whitelist +<pkg>` via Shizuku / 经 Shizuku 补 Doze 白名单 |
  | 5 | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` + accessibility rebind / 开机广播 + 无障碍被系统重绑 |

- Home screen **后台常驻** status row, plus `POST_NOTIFICATIONS` runtime request on API 33+.
  首页「后台常驻」状态行；API 33+ 申请通知权限。
- Launcher shortcut (long-press the icon) to toggle the feature without opening the app.
  桌面快捷方式（长按图标）开关功能，不必进入应用。
- App icon: white background, three dark sliders, green dots.
  应用图标：白底、三条黑滑块、绿色圆点。

### Changed / 变更

- The feature switch on the home screen now **reads back** `Prefs` on resume. Previously the launcher shortcut and long-press-to-hide changed the stored flag without updating the switch, so the UI could show "off" while the ball was on screen.
  首页功能开关改为每次 `onResume` **回读** `Prefs`。此前快捷方式与长按收球只改存储值、不回写界面，会出现"开关显示关闭、球却在屏幕上"的矛盾。
- The ball label is re-formatted when renamed, instead of being written raw.
  球重命名时改为走统一的分行格式化，不再直接赋原文。

### Design decisions worth recording / 值得记录的设计取舍

- **Manual show/hide.** Five automatic foreground-detection strategies were measured and all failed on the test device; they are documented under `docs/archive/`. Not a missing feature — a measured dead end.
  **显隐手动**。五条自动前台判断路径在实测机型上全部失败，记录在 `docs/archive/`。这不是缺功能，是实测走不通。
- **Keep-alive only while a feature is on.** No foreground service when nothing is running: no pointless notification, no wasted battery. New features only need to be folded into `Prefs.anyFeatureEnabled`.
  **保活只在有功能开启时生效**。没有功能在跑就不起前台服务：不占通知栏、不白耗电。新功能只需并入 `Prefs.anyFeatureEnabled`。
- **One ball, feature-provided label.** The ball is a shared entry point, not owned by one feature.
  **一个球、标签由功能提供**。球是共享入口，不归属某个功能。

### Known limitations / 已知限制

- Shizuku does not restart itself after a reboot (wireless-debugging mode, non-root device).
  Shizuku 重启后不会自己运行（无线调试模式，非 root 设备）。
- Injection is blind: whether the target app scrolled cannot be observed, so there is no automatic retry.
  注入是盲发：目标是否真的滚动无法观测，因此不做自动重试。
- Full-screen gestures cannot be intercepted by any app window.
  全面屏手势无法拦截。
- Release builds are unsigned; only debug APKs are produced for now.
  release 未签名，目前只出 debug 包。
