# Changelog / 更新记录

All notable changes to this project are recorded here, newest first. Each released version also has an APK and a fuller write-up under `snapshots/v<version>/`.

本项目的重要变更都记在这里，新的在上面。每个已发布的版本在 `snapshots/v<版本>/` 下另有 APK 与更详细的说明。

Format follows [Keep a Changelog](https://keepachangelog.com/); version numbers follow `0.x` while the app is pre-1.0.

格式参考 Keep a Changelog；1.0 之前版本号保持 `0.x`。

---

## [Unreleased] / 未发布

### Added / 新增

- **Extreme Saver / 极致省电** — a second, independent feature: while the system is in battery-saver mode, the default SIM's allowed network types are reduced from "5G-capable" to "4G and below"; leaving saver mode writes the captured original value back. The switch lives on the home screen, a detail page shows live policy and observed state.
  **极致省电** —— 第二个独立功能：系统进入省电模式期间，把默认卡的允许制式从「含 5G」降到「只到 4G」；退出省电时写回进入前的原值。开关在首页，二级页展示实时的策略值与实测值。

  It needs **no new permission**: battery-saver detection uses `PowerManager` + `ACTION_POWER_SAVE_MODE_CHANGED`, and the network change goes through Shizuku (`cmd phone set-allowed-network-types-for-users`), reusing the identity the app already asks for. It also needs **no accessibility service** — the feature works with the ball switched off.
  **没有新增任何权限**：省电检测用 `PowerManager` + `ACTION_POWER_SAVE_MODE_CHANGED`，改网络走已有的 Shizuku 身份（`cmd phone set-allowed-network-types-for-users`）。它同样**不依赖无障碍服务**，悬浮球关着也能用。

  Measured on device (25098PN5AC / HyperOS): `allowedNetworkTypesForReasons` `906119` → `381831` (NR bit `1<<19` cleared), observed `getRilDataRadioTechnology` `NR_SA` → `LTE` within 5 s; restoring writes `906119` back and the radio returns to `NR_SA`.
  真机实测：`allowedNetworkTypesForReasons` 由 `906119` → `381831`（清掉 NR 位 `1<<19`），实测制式 `NR_SA` → `LTE` 在 5 秒内完成；还原时写回 `906119`，制式回到 `NR_SA`。

  Three traps this cost, all recorded in `net/Saver4G.kt` and the developer playbook:
  三个坑（都写在 `net/Saver4G.kt` 与开发者手册里）：

  1. The bitmask argument is a **binary string**; passing the decimal makes the command answer `No valid NETWORK_TYPES_BITMASK`, which reads like a permission problem but is not.
     掩码参数是**二进制字符串**；传十进制会得到 `No valid NETWORK_TYPES_BITMASK`，看着像权限问题，其实不是。
  2. `-s <slot>` is **mandatory** on this device: the command defaults to "the default voice SIM's slot", and `defaultVoiceSubId` is `-1` here (VoLTE-only), so it answers `failed` — indistinguishable from a permission failure. The slot comes from `dumpsys isub` (`defaultDataSubId` + `simSlotIndex`).
     本机 `-s <卡槽>` 是**必需**的：命令默认按"默认语音卡的槽位"解析，而本机 `defaultVoiceSubId=-1`（VoLTE-only），于是回 `failed`，与权限失败无法区分。槽位取 `dumpsys isub` 的 `defaultDataSubId` + `simSlotIndex`。
  3. Do **not** derive the mask from the printed type names: this ROM prints friendly aliases (`HSPA+`, `CDMA - eHRPD`) that do not match the `NETWORK_TYPE_*` constants, and a missing entry silently turns off a technology the user never agreed to lose. The numeric value comes from `dumpsys isub`.
     **不要**按打印出来的制式名反推掩码：本机打印的是 `HSPA+`、`CDMA - eHRPD` 这类友好名，与 `NETWORK_TYPE_*` 常量对不上，漏一项就等于顺手关掉一个用户没同意关的制式。数值一律取 `dumpsys isub`。

### Changed / 变更

- **The app no longer appears in Recents.** `android:excludeFromRecents="true"` is declared on the launcher activity, the same approach ESR uses. This is a tool that works from an accessibility overlay and a foreground service; there is nothing to "resume" from the task switcher, so the card was pure noise. The attribute only matters on the root activity of a task, so the feature pages inherit it.
  **应用不再出现在「最近任务」里。** 声明在 launcher Activity 上（`android:excludeFromRecents="true"`），做法与 ESR 一致。这个工具靠无障碍浮窗和前台服务工作，任务切换器里没有值得"恢复"的东西，那张卡片纯属噪音。该属性只对任务栈的根 Activity 生效，功能页自动继承。

  An entry created before the update is not retroactively removed — it disappears once that task is gone. Verified in the built APK: `aapt2 dump xmltree` reports `excludeFromRecents(0x01010017)=true`. On-device confirmation is still pending (no device attached at build time).
  更新前就已经存在的卡片不会被追溯删掉，等该任务结束才消失。已在构建产物中核实：`aapt2 dump xmltree` 显示 `excludeFromRecents(0x01010017)=true`。真机确认待做（构建时设备未连接）。

- **The keep-alive watchdog now checks `Prefs.anyFeatureEnabled` instead of the force-scroll switch.** With only Extreme Saver enabled the ball is absent, so the old condition never fired and the resident service was never re-raised — the feature would silently stop receiving battery-saver broadcasts after the process was frozen.
  **保活看门狗改判 `Prefs.anyFeatureEnabled`，不再只看强制滑动开关。** 只开「极致省电」时球不存在，旧条件永远不成立，常驻服务不会被补拉——进程被冻结后，功能就再也收不到省电广播，且毫无报错。
- **The foreground notification now describes what is actually running** (`4G 生效中（省电模式）` / `极致省电已开启，等待省电模式`) instead of always talking about the ball, which was false when only Extreme Saver was on.
  **常驻通知改为如实描述当前在做什么**（`4G 生效中（省电模式）` / `极致省电已开启，等待省电模式`），不再无论什么情况都说悬浮球——只开「极致省电」时那句话根本不成立。
- The resident service hosts one feature hook (`Saver4G.attach/detach`) so in-process listeners follow the service's lifetime; no second foreground service was added.
  常驻服务挂了一个功能钩子（`Saver4G.attach/detach`），让功能侧的进程内监听跟着服务生死走；没有新增第二个前台服务。
- **Every feature page now uses one enforced layout.** `BaseActivity.featurePage(...)` produces the fixed sequence 返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用, and both pages were rebuilt on it. The force-scroll page gains a 依赖 card (Shizuku / accessibility / resident service) with clickable rows, so its old "等待授权" now says *which* dependency is missing and lets the user fix it from that row. "已暂停" also stopped being orange — a feature the user switched off is not a fault. Values are painted through one `setStatus(v, text, Tone)` with four semantic tones (OK / WARN / NEUTRAL / MUTED).
  **所有功能页改用同一套强制版式**：`BaseActivity.featurePage(...)` 固定产出 返回 / 标题 / 说明 / 当前状态 / 依赖 / 怎么用，两个页面都按它重做。强制滑动页新增「依赖」卡（Shizuku / 无障碍 / 常驻），行可点——原来那句「等待授权」现在会说清缺的是哪一项，并能从那一行直接去修。「已暂停」也不再是橙色：用户自己关掉的功能不是故障。值的颜色统一走 `setStatus(v, text, Tone)`，只有四种语义色。
- The home screen's auth-row painting moved into the same `applyAuth`; the private copy is gone, so home and feature pages can no longer drift apart.
  首页授权行的绘制并入同一个 `applyAuth`，私有拷贝已删除——首页与功能页不会再各改一半。
- Four per-page section titles (`fs_rule_title`, `sv_status_title`, `sv_auth_title`, `sv_rule_title`) were replaced by three shared ones (`section_current` / `section_deps` / `section_howto`). The strings file is still free of unreferenced entries — 76 strings, all referenced.
  四条各页自建的分组标题合并成三条共享文案（`section_current` / `section_deps` / `section_howto`）。字符串文件仍是零未引用——76 条全部有引用者。

### Removed / 移除

- **The home screen's "悬浮窗" (overlay) authorisation row.** Nothing in the app needs `SYSTEM_ALERT_WINDOW`: the ball is a `TYPE_ACCESSIBILITY_OVERLAY` window created by the accessibility service, and no other feature uses a plain overlay either. The row therefore sat at "去开启 ›" permanently and taught the user to grant a permission that changed nothing. It comes back the day a feature actually needs an ordinary overlay — with a real dependency behind it.
  **首页「悬浮窗」授权行已移除。** 应用里没有任何东西需要 `SYSTEM_ALERT_WINDOW`：球是无障碍服务创建的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口，其它功能也不用普通悬浮窗。所以那行常年停在「去开启 ›」，等于教用户去开一个开了也没用的权限。等真有功能需要普通悬浮窗时再加回来——到那时它背后得有真实的依赖。

- `label_overlay` string and the `android.net.Uri` import that only that row used.
  只被这一行使用的 `label_overlay` 字符串与 `android.net.Uri` 导入。

### Notes / 说明

- `dumpsys package` now lists `com.swissknife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It is **added by the platform**, not declared by the manifest — it appears because a receiver is registered with `Context.RECEIVER_NOT_EXPORTED`.
  `dumpsys package` 里会多一条 `com.swissknife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。这是**系统自动加的**，不是清单里声明的——只要有接收者用 `Context.RECEIVER_NOT_EXPORTED` 注册就会出现。

---

## [0.2] — 2026-11-06

No new feature — a hardening release. Three defects fixed, permissions and event subscriptions cut to what is actually used, repository made portable.

没有新功能，是加固版本：修了三个缺陷，把权限与事件订阅砍到实际使用的范围，仓库改为可换机器构建。

### Fixed / 修复

- **A saved position could put the ball off-screen after the display geometry changed.** The stored `x/y` is a coordinate on the screen it was saved on; rotate to landscape, enter split screen or change resolution and the ball can end up entirely outside the new bounds — invisible, and therefore impossible to drag back. Restoring now clamps the position into the current display, and `onConfigurationChanged` re-applies it live. The stored position is **never overwritten**: come back to the original geometry and the ball returns to exactly where it was put.
  **屏幕几何变化后，存下来的位置可能把球放到屏幕外。** 存的 `x/y` 是"存的那一刻那块屏幕"上的坐标；转到横屏、进分屏或改分辨率后，球可能整块落在新边界之外——看不见，也就拖不回来。现在恢复位置时会夹进当前屏幕范围，并由 `onConfigurationChanged` 在变化时立即重算。**存的位置不会被改写**：转回原来的屏幕，球回到原地。

  Measured by overriding the display to 1220×1000 (ball frame had been `[19,1397][201,1579]`, i.e. fully below a 1000 px tall screen): after the fix the frame becomes `[19,818][201,1000]` (visible, prefs still `19,1397`), and resetting the display returns it to `[19,1397][201,1579]` on its own.
  实测方式：把显示覆盖成 1220×1000（此前球 frame 是 `[19,1397][201,1579]`，完全落在 1000px 高的屏幕下方）。修复后 frame 变为 `[19,818][201,1000]`（可见，prefs 仍是 `19,1397`），恢复分辨率后自动回到 `[19,1397][201,1579]`。

- **The ball could sit 150 px (one status-bar height) lower than where it was saved.** `WindowManager.LayoutParams` only carried `FLAG_LAYOUT_NO_LIMITS`, so the window's layout origin was sometimes the bottom of the status bar instead of the top of the screen. The saved `y` was applied verbatim either way, which meant the same stored position rendered at two different heights depending on when the service happened to be rebound — from the user's side, "the ball moved by itself after an update". Adding `FLAG_LAYOUT_IN_SCREEN` makes the origin `(0,0)` and `params.x/y` equal real screen coordinates.
  **球的真实位置可能比存下来的位置低 150 px（正好一个状态栏高度）。** 窗口参数只有 `FLAG_LAYOUT_NO_LIMITS`，布局原点有时是状态栏下沿、有时是屏幕顶端，而存下来的 `y` 在两种情况下都被原样套用——于是同一个位置会随"服务什么时候被重绑"渲染出两个高度，用户看到的现象就是"球自己挪了"。加上 `FLAG_LAYOUT_IN_SCREEN` 后原点固定为 `(0,0)`，`params.x/y` 就等于真实屏幕坐标。

  Measured with `dumpsys window windows`: before the fix `parent=[0,150]` / `frame=[19,1547][201,1729]` while `mAttrs` said `(19,1397)`; after, `parent=[0,0]` / `frame=[19,1397][201,1579]`.
  用 `dumpsys window windows` 实测：修复前 `parent=[0,150]`、`frame=[19,1547][201,1729]`，而 `mAttrs` 写的是 `(19,1397)`；修复后为 `parent=[0,0]`、`frame=[19,1397][201,1579]`。

- **Two of the five usage notes described behaviour that does not exist.** "球变绿表示已触发" — a successful tap gives no feedback at all; only a failure turns the text orange for 400 ms. "长按圆球 1 秒：收起，再次打开应用即恢复" — the threshold is 600 ms and a long press *turns the feature off*; reopening the app does not restore anything (that auto-restore was deliberately removed). Both now match the implementation.
  **五条使用说明里有两条描述的是不存在的行为了。**「球变绿表示已触发」——成功时根本没有反馈，只有失败才会把文字变橙 400 毫秒；「长按圆球 1 秒：收起，再次打开应用即恢复」——阈值是 600 毫秒，且长按是**关闭功能**，重新打开应用也不会恢复（那条自动恢复逻辑是刻意删掉的）。两处均已改成与实现一致。

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

- 14 unused strings from earlier UI iterations (`status_missing/denied/absent/unused`, `btn_go_enable`, `btn_grant_shizuku`, `btn_battery`, `fs_switch`, `fs_btn_show`, `fs_need_switch`, `fs_shown`, `toast_saved`, `toast_need_accessibility`, `toast_need_shizuku`). Checked by scanning every `<string name>`, every `R.string.x` and `@string/x`; the file is now 47 strings with zero unreferenced.
  14 个更早几轮 UI 迭代留下的字符串（上列）。用脚本扫过全部 `<string name>` 与 `R.string.x` / `@string/x` 引用，现在 47 个字符串零未引用。

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
