# Changelog / 更新记录

All notable changes to this project are recorded here, newest first. Each released version also has an APK and a fuller write-up under `snapshots/v<version>/`.

本项目的重要变更都记在这里，新的在上面。每个已发布的版本在 `snapshots/v<版本>/` 下另有 APK 与更详细的说明。

Format follows [Keep a Changelog](https://keepachangelog.com/); version numbers follow `0.x` while the app is pre-1.0.

格式参考 Keep a Changelog；1.0 之前版本号保持 `0.x`。

---

## [0.7] — 2026-11-20

No new features — this is a maintenance release with three parts: a **correctness fix** for the
"screen off for 20 s resets the counter" rule that occasionally failed, a **power pass** that removes
the redundant system calls in the 30-second heartbeat and halves gaze sampling while the user is
looking, and a **logging overhaul** so the app's own log files can no longer grow without bound and no
longer get deleted wholesale on overflow. Keep-alive behaviour is unchanged: front service, 30-second
watchdog, Doze whitelist and boot self-heal are untouched.

这一版不加功能，是维护性更新，三件事：修复「熄屏超过 20 秒清零」偶发不生效；做了一轮**功耗梳理**，
去掉 30 秒心跳里的冗余系统调用、把人持续注视时的采样频率减半；以及**日志重构**——应用自记日志
不再无上限增长，也不会在超限时被整份删除。保活行为未变：前台服务、30 秒看门狗、Doze 白名单、
开机自愈一处未动。

### Fixed / 修复

- **"熄屏超过 20 秒清零"偶发不生效 (reported 2026-11-20).** The reset depended on a *dynamically registered* screen on/off receiver, and those broadcasts are not delivered while the process is frozen — which is exactly what happens when the screen goes off and the device drops into Doze. Losing `SCREEN_OFF` left `offAt = 0`, so on wake the timer computed a 0-second gap: no reset, and worse, the settle step **counted the screen-off span as screen-on time** (phase was still `SCREEN`), so the counter ran ahead and the overlay came early. A second gap: `reconcileScreen()` returned early whenever the current state matched the last one, so a screen-off that ended between two 30-second heartbeats was invisible. Now: `SCREEN_ON/OFF` are **statically registered** (both actions are exempt from the Android 8 implicit-broadcast restrictions), `applyScreenState` is idempotent so the duplicated dynamic+static delivery cannot refresh `offAt`, and both outcomes are logged (`熄屏 25s ≥ 20s，用眼累计已清零` / `熄屏 7s < 20s，接着累计`).
  **「熄屏超过 20 秒清零」偶发不生效（2026-11-20 反馈）**。原来这条判定挂在**动态注册**的屏幕亮灭广播上，而进程被冻结时（熄屏进 Doze 正是这个时机）收不到广播。丢掉 `SCREEN_OFF` 会让 `offAt` 一直是 0，亮屏时算出的间隔是 0 秒：不清零；更糟的是结算时**把熄屏那段当成亮屏累计**（此时 phase 仍是 `SCREEN`），于是累计反而跑快、浮窗提前。另一处漏洞：`reconcileScreen()` 在"当前状态与上次相同"时直接返回，所以两次 30 秒心跳之间的那段熄屏完全看不见。现在：`SCREEN_ON/OFF` 改为**静态注册**（这两个 action 不在 Android 8 的隐式广播限制名单里），`applyScreenState` 加了幂等守卫（动态+静态重复投递不会把 `offAt` 刷成"刚刚熄屏"），两种结果都写日志（`熄屏 25s ≥ 20s，用眼累计已清零` / `熄屏 7s < 20s，接着累计`）。

### Changed / 优化

- **Power review: no redundant system calls left in the 30-second heartbeat, and gaze sampling is now two-tier (2026-11-20).** Measured baseline on the device: 7.4 s CPU over a 6 h 21 m battery cycle, of which 2.66 s fell in screen-off — i.e. the architecture had no hog, only a heartbeat that asked the system more times than it needed to. Seven changes, none of them touching the keep-alive chain (front service / watchdog 30 s / Doze whitelist / boot self-heal are untouched):
  1. `EyeBreak.onServiceTick()` asks `PowerManager.isInteractive` **once** per heartbeat and passes the answer to `reconcileScreen()` and `reschedule()` (was three calls, ~2900 extra binder round-trips per day).
  2. The heartbeat **skips the notification recompute while the screen is off** — the eye-break minutes do not advance there and nobody can see the notification, yet each recompute cost a ContentProvider query plus a Shizuku binder. The screen-off transition refreshes once through `refreshText()` so the segment still flips to `（熄灭）` immediately.
  3. Brightness-channel cache 60 s → 300 s. Event-driven `refresh()` still invalidates it, and the expiry is kept as the self-heal path if Shizuku loses its grant mid-run.
  4. `ScreenStateReceiver` now returns immediately unless eye-break or the clock ball is enabled. As a manifest receiver it is delivered on **every** screen toggle; after the feature set was turned off and the service stopped, each toggle used to pull the process up just to reach `ctx == null`.
  5. The clock ball **stops its 1-second tick chain on screen-off and restarts on screen-on**; the watch-dog only re-arms it when the screen is interactive (otherwise it would immediately undo the stop). The per-tick `isInteractive` call is gone — the chain's own liveness now carries that meaning.
  6. Gaze sampling **pauses while the break overlay is up**: those 25 seconds are not counted, so the sample had no consumer, and each round costs one `sh` process.
  7. Gaze cadence is now **60 s while continuously looking, 30 s on any change** (first result, just-found-not-looking, timer paused). Extracted as the pure function `GazeCadence.interval` with 5 unit tests, because a wrong branch here does not crash or log — it only samples slower. Trade-off accepted by the user: freezing the timer after the user looks away can be up to ~90 s late, about 7 % of the 20-minute threshold. Halves the `sh` processes on a long looking session (≈960 → ≈480 per 8 h), which are invisible in the app's own `batterystats`.
  **功耗审查：30 秒心跳里不再有冗余系统调用，注视采样改双档（2026-11-20）**。实机基线：6 小时 21 分的电池周期里应用总 CPU 7.4 秒，其中熄屏段 2.66 秒——说明架构本身没有黑洞，只有心跳问系统的次数超出必要。七处改动，**保活链路（前台服务 / 30 秒看门狗 / Doze 白名单 / 开机自愈）一处未动**：① 心跳只问一次 `isInteractive` 并把结果传给对账与重排（原来 3 次，每天约 2900 次多余往返）；② 熄屏时跳过通知重算（熄屏期间分钟数不涨、通知也看不见，而每次重算要问 ContentProvider + Shizuku），熄屏那一刻由 `refreshText()` 补刷一次，护眼段照旧立刻变「（熄灭）」；③ 亮度通道缓存 60 秒 → 5 分钟（`refresh()` 仍当场作废，保留到期自愈）；④ `ScreenStateReceiver` 先判开关，功能全关后不再被每次亮灭屏白拉起进程；⑤ 时间球灭屏停秒链、亮屏重挂，看门狗只在屏幕亮着时兜底（否则会把刚停的链又拉起来）；⑥ 浮窗 25 秒期间停注视采样（那段时间不计时，采样无人使用，每轮一个 `sh` 进程）；⑦ 注视节拍改为**连续在看 60 秒、其余 30 秒**，抽成纯函数 `GazeCadence` + 5 个单测（这条逻辑写反不会崩、不会报错，只会变慢，只能靠测试兜）。代价已确认：人离开后冻结计时最多晚约 90 秒，约为 20 分钟阈值的 7%。
- **Logging now has one writer and a hard cap per file (2026-11-20).** Four separate `log()` implementations (accessibility service, eye break, break notifier, storage clean) all appended to the same `watch.log`, each with its own size check — and the fifth (`net.log`) had one too. Every one of them **deleted the whole file** on overflow, so a problem reported two days later had no evidence left. Now `core/AppLog.kt` is the only writer: `watch.log` capped at 120 KB, `net.log` at 60 KB, overflow **keeps the tail half on a line boundary** instead of wiping everything, and the size check runs only every 50 writes (plus the first write of each process, so a file left oversized by a previous run converges at once). The trim itself is `core/LogFileTrimmer.kt`, pure JVM with 5 unit tests — it must never leave a half line or empty the file. Also removed debug logging that cannot be read on this device (logcat has been stalled since 09:58, 0 entries from this app): `AttentionProbe` per-sample `Log.d` (every 30/60 s), `BrightGuard` per-enforce `Log.d` (up to every 700 ms with auto-brightness), `MainActivity` shortcut-registered `Log.i`. The key-event log now records only `ACTION_DOWN` with `repeatCount == 0` — it used to log down, every repeat and up, which made one "volume-down ×5" cost 10 lines and accounted for 21 % of `watch.log`.
  **日志收拢为单一写入口，并给每个文件设硬上限（2026-11-20）**。此前有 4 份 `log()` 实现（无障碍服务 / 护眼 / 结束提示 / 空间回收）都往同一个 `watch.log` 追加，各自判一次大小，第 5 处（`net.log`）也一样——而它们**超限时都是整份删除**，等于两天后报上来的问题已经没有现场。现在 `core/AppLog.kt` 是唯一写入口：`watch.log` 上限 120 KB、`net.log` 60 KB，超限**保留尾部一半（切在换行处）**而不是全删，大小检查降到每 50 次写一次（外加每个进程的第一笔写，这样上一次运行遗留的超大文件会立刻收敛）。裁剪逻辑在 `core/LogFileTrimmer.kt`，纯 JVM + 5 个单测——它绝不能留下半行、也不能把文件裁空。同时删掉本机根本读不到的调试日志（logcat 自 09:58 停摆，本应用 0 条）：`AttentionProbe` 每轮采样的 `Log.d`（30/60 秒一条）、`BrightGuard` 每次纠偏的 `Log.d`（自动亮度下最快每 700 ms 一条）、`MainActivity` 的快捷方式注册成功 `Log.i`。按键日志改为只记 `ACTION_DOWN` 且 `repeatCount == 0`——原来按下、每个 repeat、松开各记一行，一次「音量下×5」就是 10 行，实测占 `watch.log` 的两成。

---

## [0.6] — 2026-11-16

The sixth feature: a **second, fully independent floating ball** that just shows the time. It is
not a mode of the force-scroll ball — both can be on at once, and turning one off never touches
the other.

第六个功能：**第二个完全独立的悬浮球**，只显示时间。它不是强制滑动那个球的模式——两个可以同时开着，
关掉一个从不影响另一个。

### Added / 新增

- **Suspended clock ball / 悬浮时间**: shows `HH:mm:ss` in 24-hour form, red text at 24 sp. The pill's
  size follows the font instead of a fixed diameter — height = font line height + 8 dp, width = the
  widest string `88:88:88` + 14 dp — so it hugs the digits with no empty margin, and the width never
  changes as the seconds tick. Refreshes once a second **aligned to the second boundary**; writes are
  skipped while the screen is off; the existing 30-second watch-dog re-arms the tick if the system
  dropped the handler message (long `postDelayed` is unreliable on this device).
  Turn on from the home switch, a launcher shortcut or the notification action; drag to move (position
  persists per ball); long-press 600 ms to turn it off. A single tap does nothing on purpose — it is a
  clock, jumping would read as a bug.
  **Needs only the accessibility service** — no Shizuku, because it injects nothing.
  显示 24 小时制 `HH:mm:ss`，红字 24sp。胶囊尺寸跟着字体算而不是固定直径——高=字体行高+8dp，宽=最长串
  `88:88:88` 测量值+14dp——所以刚好包住数字、不留空白边，宽度也不随秒数跳动。每秒**对齐整秒**刷新；
  灭屏不写文字；30 秒看门狗在系统丢掉 Handler 消息时重挂秒循环（本机长延迟不可靠）。
  三个开启入口（首页开关 / 桌面快捷方式 / 通知按钮），可拖动（每个球各存各的位置），长按 600ms 关闭。
  单击刻意无动作——这是时钟，跳一下会被当成故障。**只依赖无障碍服务**，不需要 Shizuku（不注入任何东西）。

### Changed / 变更

- One transparent activity (`ShortcutToggleActivity`) and one receiver (`ToggleReceiver`) now serve
  both features, distinguished by a `feature` extra; requests without the extra still mean force-scroll,
  so shortcuts registered by older builds keep working. The notification carries two actions now.
  一个透明 Activity 与一个 Receiver 现在同时服务两个功能，用 `feature` extra 区分；不带 extra 仍按
  强制滑动处理，老版本注册的快捷方式行为不变。通知上也变成两个按钮。

### Fixed / 修复

- `README.md`: the storage-reclaim row had its first two cells duplicated (`| a | b || a | b | ...`),
  which broke the table; the overview still said five features.
  README 的空间回收那一行把前两个单元格重复了一遍（表格是坏的）；概述还写着五个功能。

---

## [0.5] — 2026-11-14

The fifth feature arrives, and it is the first one that is a **manual tool** rather than a toggle: 空间回收 reclaims storage by clearing files under `Android/data` — deleting the entry when the parent directory allows it, truncating the contents when it does not.

第五个功能落地，也是第一个**手动工具**而不是开关：**空间回收**——`Android/data` 下那些占着空间又不好清掉的文件，能删就删条目，删不掉就把内容清空。

### Added / 新增

- **Storage reclaim / 空间回收** (manual tool, no switch). Binds a Shizuku UserService that runs as `shell` (uid 2000) and works on files under `/sdcard/Android`: **`Os.remove` first** — the parent directory's group bits decide whether that is allowed (`drwxrws---` 2770 is writable for `ext_data_rw`, measured on a real 108 MB APK that disappeared and freed 108 MB) — and **falling back to truncating the contents to 0 bytes** when it is not (`drwxr-s---` 2750). Results are reported separately: 「移除 N 项（条目消失）、清空 M 项（保留空壳）」. Truncation is refused outright while the target package still has a running process.
  **空间回收**（手动工具，无开关）。绑定以 `shell`（uid 2000）身份运行的 Shizuku UserService，处理 `/sdcard/Android` 下的文件：**先 `Os.remove`** —— 能不能删取决于**父目录组位**（`drwxrws---` 2770 对 `ext_data_rw` 组可写，实测一个 108 MB 的 apk 真的消失、腾出 108 MB）——**不行再把内容截断为 0 字节**（`drwxr-s---` 2750）。结果分开报：「移除 N 项（条目消失）、清空 M 项（保留空壳）」。目标应用还有进程在跑时整体拒绝执行。
- **Three scan entries, each with its own tuning / 三个扫描入口，各带调参**：调参在上、三个并列按钮在下 ——「大文件」（全盘 ≥ 阈值，只列文件）、「Cache 目录」（只列 cache / temp / tmp / files 这类目录，按目录合计 ≥ 阈值）、「指定目录」（只扫填的路径，不按大小过滤）。阈值是参数（1–10240 MB，默认 100），改完立刻重扫。
- **Cache directories expand and each file can be ticked / 缓存目录可展开、子文件可单独勾**：目录行带「展开 / 收起」，展开时按需向 shell 侧取内容（最多 300 条）；勾目录 = 清空整个目录并**保留空目录**（空目录只占 4 KB，避免应用不重建目录而报错）；目录与其下级同时勾选时按路径前缀去重。标「⚠ 含应用数据」的是应用自己的 `files` 目录（存档、数据库、登录态都在里面）。
- **Configurable size threshold / 阈值可调**：`clean_min_size_mb`，1–10240，默认 100。

### Changed / 变更

- **Results are grouped by app and foldable / 按应用分组、可折叠**：组头给包名、该应用合计、组根绝对路径；点名称折叠；组勾选框整组勾选且不折叠；条目显示相对组根的路径。依据实测分布：16 个命中散在 9 个应用、7 个只有 1 个文件，多层目录树大多是"独苗"链。
- 二级页骨架 `BaseActivity.featurePage` 新增 `content { }` 列表槽；`input(...)` 新增 `numberOnly`。首页新功能入口**不带开关**，不进 `anyFeatureEnabled`（手动工具不需要常驻）。

### Fixed / 修复

- **Eye Break never fired while the counter kept climbing.** Reaching the threshold asked the AON gaze probe whether anyone was looking and deferred 3 minutes on "no", with no limit; at night it always answers "no", so it deferred forever. **The probe is gone from the threshold path** (gaze only affects accumulation; the overlay only looks at the timer). 护眼休息一直不弹、计时却持续累加：到点后问 AON「在不在看」，判成"没看"就延后 3 分钟且无上限，夜里必然无限延后。**已把探测从到期路径删掉**：注视只作用于累计，弹窗只看计时。
- 依赖行只有标签没有状态值：`dep()` 只建行，值要靠 `applyAuth()` 填，本页漏了这句。
- Shizuku UserService 进程每进一次页面留一个且不回收（`PPID=1`、`shell` 身份，一轮累积 11 个）：本机 `unbindUserService(remove=true)` 只解 binder，改为服务侧 `shutdown()` 自杀；`tools/deploy.sh` 再兜一层（`USER_SERVICE_CLEANED n`），因为安装/强杀会绕过 `onDestroy`。
- 复用调参输入框导致崩溃：`removeAllViews()` 只摘行、没摘下输入框，再次 `addView` 抛 `IllegalStateException`；改为先摘父。
- 清理结果文案被随后的自动重扫冲掉；两个操作按钮原来在列表**下方**（26–300 项时被推出屏幕、实际点不到），现移到上方。

---

## [0.4] — 2026-11-10

Two tools ported in from **EyeStrainRelief (ESR)** as two **parallel** features — they share the framework but not each other. Real-device verification reached **28 of 29** items; the last one is incoming-call dismissal, which needs a live call.

从 **EyeStrainRelief（ESR）** 移植两个**平行**功能进来 —— 共用一个框架，但彼此不依赖。真机验证完成 **28/29**，剩最后一项「来电撤窗」需要真人拨一个电话。

### Added / 新增

- **Eye Break / 护眼休息** — accumulates screen-on time (gaze time when the Xiaomi AON probe is available) and, at the threshold, covers the screen with a full-screen rest overlay: large countdown, clock, pause-media button. Back / Home / Recents are swallowed while it counts down; volume-down ×5 escapes immediately; an incoming call dismisses it; the end is announced with the system notification sound plus a two-pulse vibration (silent/vibrate mode: vibration only).
  **护眼休息** —— 累计亮屏时长（可用小米 AON 注视检测时按注视时长），到阈值铺一层全屏休息浮窗：大号倒计时、时钟、暂停媒体按钮。倒计时期间吞掉返回 / Home / 最近任务；音量下连按 5 次立即脱出；来电立刻撤窗；结束时用系统通知音 + 两下短震提示（静音/振动模式只震）。

  Ported without touching the logic: `SessionTimer` (interval settlement, lazy evaluation, zero polling), `BreakOverlay` (same colours, same 132 sp countdown, same clock layout), `AttentionProbe` (`cmd attention`), `MediaPauseHelper` (never resumes without an active media session), `BreakNotifier`.
  逻辑一行未改地搬过来：`SessionTimer`（区间结算 + 惰性求值 + 零轮询）、`BreakOverlay`（同样的配色、132sp 倒计时、双行时钟）、`AttentionProbe`（`cmd attention`）、`MediaPauseHelper`（没有活跃媒体会话时绝不发 play）、`BreakNotifier`。

  The foreground-service shell around it was **not** ported: the business body now hangs off the existing `KeepAliveService` (`EyeBreak.attach/detach`), like Extreme Saver. No second foreground service exists.
  **没有**搬它外面那层前台服务：业务体挂在已有的 `KeepAliveService` 上（`EyeBreak.attach/detach`），与极致省电同一套模式。没有第二个前台服务。

- **Minimum Brightness / 最低亮度守护** — a `ContentObserver` on `SCREEN_BRIGHTNESS`; when the value drops below the user's floor it is written back immediately. Event-driven, zero polling, and it **never touches the brightness otherwise**.
  **最低亮度守护** —— 监听 `SCREEN_BRIGHTNESS` 的 `ContentObserver`；低于用户设定的下限就立刻写回。事件驱动、零轮询，其余时候完全不碰亮度。

  Two write channels: `WRITE_SETTINGS` (direct, no IPC latency while dragging the slider) with a Shizuku `settings put` fallback. It **does not need the accessibility service** — enabling only this feature asks for nothing but brightness access.
  写通道两条：`WRITE_SETTINGS` 直写（拖亮度条时不卡），没有则退回 Shizuku `settings put`。它**不需要无障碍服务** —— 只开这一个功能时，不会被要求去开一个用不到的权限。

  Closing the switch stops the guard and leaves the brightness where it is: the guard only corrects, it never records a "restore point", so there is nothing to restore.
  关掉开关即停止纠偏，亮度停在当时的值：守卫只纠偏、不记账，因此没有需要还原的东西。

- **Per-feature tuning, same as the standalone apps** — both pages gain a 调参 slot (`BaseActivity.FeaturePage.input`): minutes for the eye-break threshold, control value 1–255 for the brightness floor. Switches stay on the home screen only.
  **每个功能都能调参，与独立版一致** —— 两个页面各加了「调参」槽（`BaseActivity.FeaturePage.input`）：护眼休息调阈值（分钟），最低亮度守护调下限（控制值 1–255）。开关仍然只在首页。

### Fixed / 修复（2026-11-08，用户在真机上报的三个问题）

- **The end-of-break vibration fired only sometimes, in every ringer mode.** The old code called `vibrator.vibrate(VibrationEffect)`, the deprecated overload whose attributes default to `USAGE_UNKNOWN`; Android 13+ treats "unknown usage" as suppressible and ROMs are stricter still — hence "works sometimes, unrelated to silent mode". Vibration now declares `USAGE_ALARM` explicitly (the only public usage the system treats as must-deliver) through three channels: `VibrationAttributes` on API 33+, `AudioAttributes(USAGE_ALARM)` on 26–32, then the legacy overload. **Sound still follows the system** (no ring in silent/vibrate/DND); **vibration happens in every mode** — the split the user asked for. Each cue also writes a line to `watch.log` (ringer mode / DND / sound skipped / vibration accepted), and the feature page gained a **测试结束提示** button so all four modes can be checked in seconds instead of waiting out a countdown.
  **休息结束的震动"概率性不震"，且与静音状态无关** —— 老代码用的是 `vibrator.vibrate(VibrationEffect)`，这个废弃重载的属性默认 `USAGE_UNKNOWN`；Android 13+ 把"用途不明"当作可抑制的，各 ROM 收得更紧，于是"时好时坏、跟静音没关系"。现在显式声明 `USAGE_ALARM`（公开 API 里唯一被视为必须送达的用途），按 API 级别走三条通道：33+ 用 `VibrationAttributes`、26–32 用 `AudioAttributes(USAGE_ALARM)`、最后兜底旧重载。**声音仍严格跟随系统**（静音/振动/勿扰不响），**震动任何模式都震** —— 正是要求的分工。每次提示还会往 `watch.log` 记一行（铃声模式 / 勿扰 / 声音是否跳过 / 震动是否被接受），并在护眼页加了「测试结束提示」按钮，四种模式几秒就能验完，不必等倒计时。

- **The overlay could stay on screen without ever announcing the end**, when the 250 ms countdown chain lost a `postDelayed` message (a known trap on this device). A second, independent one-shot timer now backs the countdown up, so a dropped tick can no longer swallow the sound and vibration.
  **倒计时链一旦丢掉一条 `postDelayed`，浮窗会停在最后一个数字上、结束提示也一起没了**（本机已知会丢延迟消息）。现在多挂一条独立的一次性到期消息兜底，单条 tick 丢失不会再吞掉声音和震动。

- **Audit against the original ESR build.** The six files ported verbatim are identical apart from package names and log tags; every difference lives in the two classes that had to be re-hosted. Three self-invented gates were reverted to the ESR form (a duplicate "screen is off" flag in `reschedule` that could permanently stop scheduling, a side-effecting `snapshot()` getter, and a gaze chain that only set a flag instead of re-posting itself). Two things that had been dropped were restored: the threshold is now re-aligned from prefs on every watchdog tick (**changing it in the page had no effect before**), and a missing accessibility service is now stated outright instead of letting the feature look "occasionally broken".
  **与原版 ESR 逐文件核对**：六个纯移植文件除包名与日志 TAG 外完全一致；所有差异都在不得不换载体的两个类里。三处我自己加的"更稳"的闸门已回退成 ESR 写法（`reschedule` 里那份重复的"屏幕灭了"标记会让排表永久停摆、`snapshot()` 这个 getter 里跑状态机、注视链只置标记不重挂）。两处抄漏已补回：**阈值每次看门狗心跳都会从 Prefs 重新对齐（此前在页面上改阈值当场不生效）**；无障碍没开时直接说明，而不是让功能显得"偶尔不灵"。

- **Eye Break counted screen-off time** — screen on/off arrives as a dynamic broadcast, and the system does **not** re-deliver it to a frozen process. Once `ACTION_SCREEN_OFF` was missed, the elapsed off-time was accumulated as screen time (the user saw 100+ minutes), the "off for more than 20 s clears the counter" rule never fired, and the overlay popped the moment the screen came back. The timer now reconciles against `PowerManager.isInteractive` on every existing entry point (watchdog tick, snapshot, re-arm) — no timer, no extra wakeup.
  **护眼休息把熄屏时间算进了用眼时长** —— 屏幕亮灭是动态广播，系统**不会**给被冻结的进程补发。一旦漏掉 `ACTION_SCREEN_OFF`，熄屏那段就被当成亮屏累计（用户实测攒到 100+ 分钟），"熄屏超 20 秒清零"永不触发，一亮屏立刻弹窗。现在在三个本来就要跑的入口（看门狗心跳、取快照、重排到期表）核对 `PowerManager.isInteractive`，不加定时器、不增加唤醒。
- **The overlay could pop twice for one break.** Nothing checked "should this message still show anything" when a due message fired, and stale due messages came from four sources (screen-on re-arm, watchdog re-arm, the 3-minute presence re-check, manual test). Now it requires both *screen on* and *remaining ≤ 0*, plus a 60-second gap between breaks.
  **一次休息会连弹两次** —— 到期消息触发时不校验"现在到底该不该弹"，而过期消息有四个来源（亮屏重排、看门狗重排、3 分钟重判、手动测试）。现在要求"屏幕亮着 + 确实到阈值"两个条件，并且两次休息之间至少隔 60 秒。
- **The overlay could be shown while the screen was off, lighting it up via `SCREEN_BRIGHT_WAKE_LOCK`** — plausibly the source of the reported "phone got hot while asleep". Off-screen now: no overlay, no wake lock, and no pending due timer at all.
  **熄屏时也会弹窗，并用 `SCREEN_BRIGHT_WAKE_LOCK` 把屏幕点亮** —— 这很可能是用户报的"息屏发热"的来源。现在熄屏不弹窗、不持锁、连到期表都不留。
- **Minimum Brightness ignored auto-brightness.** Auto-brightness changes the *display* brightness, and on some ROMs only writes `screen_brightness_float`; observing just `screen_brightness` meant a manual slider drag was corrected while an auto drop was never even noticed. Both keys are observed now, writes are throttled to one per 700 ms (a system writing continuously plus an app writing back on every callback is a high-frequency IPC storm, itself a battery drain), and the page states the brightness mode explicitly — auto mode shows orange "系统会持续覆盖" plus a one-tap **关闭自动亮度** action. The app never flips that setting on its own.
  **最低亮度守护对自动亮度完全失效** —— 自动亮度改的是显示亮度，部分机型只写 `screen_brightness_float`；只观察 `screen_brightness` 的结果就是"手动拖能拉回、自动降下去根本没收到回调"。现在两个键都观察，写入节流到 700ms 一次（系统连续写 + 我们每次回调都写回 = 高频 IPC，本身就是耗电），并且页面把亮度模式摆出来：自动模式显示橙色「系统会持续覆盖」+ 一键「关闭自动亮度」。**应用不会自己去改这个设置。**

### Changed / 变更

- **Quiet hours for the gaze probe: 22:00–08:00 is screen-on time, full stop** (set by the user 2026-11-09). In low evening light the Xiaomi AON repeatedly reports "not looking", so the 20-minute threshold could barely be reached at night — the counter kept pausing. During quiet hours the probe is not run at all: no sampling, no pause, and screen-on time always counts. Two side benefits: the sample chain is skipped entirely (no `sh` process every 30 s), and any break already paused gets unfrozen as soon as the window starts. The boundary lives in a pure function (`QuietHours.isQuiet(hour)`), so 21:00 / 22:00 / 07:00 / 08:00 are locked down by unit tests instead of waiting for two real midnights. Notification wording follows: `连续用眼 N 分钟`, and `（未注视）` when it is paused.
  **夜间静默时段：22:00–08:00 只认亮屏**（2026-11-09 用户定）。晚上光照低时小米 AON 反复判成「没在看」，20 分钟阈值在夜里几乎走不完——计时被反复暂停。这段时间干脆不跑注视检测：不采样、不暂停、亮屏就累计。两个附带好处：整条采样链不启动（不用每 30 秒起 `sh` 进程），进入时段时已暂停的计时会立刻解冻。边界写成纯函数（`QuietHours.isQuiet(hour)`），21 点 / 22 点 / 7 点 / 8 点由单测锁死，不必等两个真实午夜来验。通知文案同步改成 `连续用眼 N 分钟`，暂停时是 `（未注视）`。

- **Eye-break progress is back in the notification** (requested 2026-11-09): `护眼 12 分钟`, and when the gaze probe says nobody is looking, `护眼 12 分钟（未在看）`; the break itself shows `护眼 休息中`. The summary also got shorter (`N 项运行中`) so the line still fits: `3 项运行中 · 护眼 12 分钟（未在看）` is 21 characters, and the collapsed line holds about 22. **Trade-off, stated plainly:** a minute counter that advances on its own means the notification is reposted once per minute again — that was the cost of the earlier "no self-advancing numbers" rule, and it was given up deliberately. Making it coarser (e.g. every 5 minutes) is a one-line change if it ever feels wasteful. Alerts (accessibility disconnected, brightness without a channel/floor) are capped at **one** entry now, because summary + eye-break + a second alert would not fit.
  **护眼进度回到通知**（2026-11-09 用户要求）：`护眼 12 分钟`；注视判定没人看时变 `护眼 12 分钟（未在看）`；休息浮窗期间是 `护眼 休息中`。摘要同时缩短成「N 项运行中」，保证还放得下：`3 项运行中 · 护眼 12 分钟（未在看）` 是 21 个字，折叠态一行约 22 个。**代价说清楚**：带自走的分钟数就意味着**每分钟重发一次通知**——这正是上一版"正文不含自走数字"放弃掉的东西，这次是有意换回来的。哪天觉得费电，把粒度改粗（如 5 分钟一跳）只是一行的事。异常项（无障碍未连接、亮度无通道/未设下限）现在**最多一条**，因为摘要 + 护眼 + 第二条异常放不下。

- **Power pass across the app** (2026-11-09, no behaviour change on the user's side). Seven spots trimmed; the two that actually mattered both hide in the *shell* account, not in the app's own CPU stats: the gaze probe used to `exec` **two** `sh` processes every 30 s (≈1920 a day) and now runs as **one** command with `sleep` inside it; and the watchdog no longer calls `startForegroundService` when the service is already confirmed foreground (it used to do so every 30 s, ≈2880 AMS round-trips a day). Also: the brightness-channel check behind the notification is cached for 60 s and invalidated on any event-driven refresh; the break-overlay countdown ticks at 1 s instead of 250 ms (the digits only change once a second); `log()` stats the log file every 50 writes instead of every write, and a *successful* scroll injection no longer writes a line; `Prefs.today()` reuses a thread-local `SimpleDateFormat`. Auto-brightness correction was deliberately left untouched at 700 ms.
  **全应用功耗梳理**（2026-11-09，对用户无行为变化）。改了七处，真正有意义的两处都藏在 **shell 账号**里、应用自己的 CPU 统计根本看不见：注视采样原来每 30 秒 `exec` **两个** `sh` 进程（约 1920 个/天），现在合成**一条**命令（`sleep` 放在命令内部）；看门狗在服务已确认处于前台时不再调 `startForegroundService`（原来每 30 秒一次、约 2880 次 AMS 往返/天）。另外：通知里的亮度通道判断缓存 60 秒、事件驱动刷新时作废；休息浮窗倒计时从 250 ms 改为 1 秒一跳（数字本来就每秒才变）；`log()` 改成每 50 次才 stat 一次日志文件，注入成功不再写日志；`Prefs.today()` 复用线程内的 `SimpleDateFormat`。**自动亮度的 700 ms 纠偏刻意保持原样**。

- **The persistent notification is now a summary, not a per-feature report.** The old text spelled out every feature and reached 59 characters with all four on, while a collapsed line fits about 22 — the second half was always cut off, and one more feature would have made it useless. It also carried "used N minutes", a number that advances by itself, so the notification was **reposted every minute**. Now the body contains no self-advancing numbers: normal state is a single **N 项功能运行中** (N counts features that are genuinely in effect), followed by up to two items that actually need you — accessibility not connected, or the brightness guard missing a write permission / a floor. `4G 生效中` stays last so truncation drops it first. Per-feature detail lives on the home screen's status rows, where it belongs.
  **常驻通知改成摘要式，不再是逐功能汇报** —— 旧文案把每个功能都说一遍，四个全开 59 个字符，而折叠态一行只放得下约 22 个汉字，后半截永远被截掉；再加一个功能就彻底失效。它还带「已用 N 分钟」，这个数字自己会走，于是**每分钟重发一次通知**。现在正文不含会自己变的数字：正常态只有一行 **N 项功能运行中**（N 只算真正生效的功能），后面最多跟两条**需要你动手**的事项（无障碍未连接；亮度守护缺写入权限或没设下限）。「4G 生效中」放最后，被截断时先丢它。逐功能细节回到首页的状态行——那才是它该待的地方。

- **First JVM unit tests** — `app/src/test/java/com/swissknife/esr/SessionTimerTest.kt`, 13 cases over `SessionTimer`: screen-off freeze, <20 s kept / 25 s cleared, the *exact* 20 000 ms boundary (and 1 ms below it), threshold hit, gaze accounting with heartbeat fallback, gaze suspension, snapshot idempotence, uninitialised snapshot. Runs in **6 ms** (`bash tools/test.sh` → `TEST_OK 13 0.006s`); the device equivalent takes 5+ minutes and nobody can time 19 s vs 21 s by hand. Test-only code — nothing ships in the APK and no runtime behaviour changed. Two things found while writing them: `SessionTimer` uses `0L` as its "not screen-on" sentinel, so a test starting at `now = 0` silently degenerates to `IDLE`; and `reset()` cannot be tested on the JVM because it reaches `SystemClock` through `Timebase`.
  **首批 JVM 单测** —— `SessionTimerTest.kt` 13 个用例：熄屏冻结、不足 20 秒保留 / 25 秒清零、**恰好 20 000 ms 边界**（以及差 1 ms）、阈值到点、注视计入与心跳超时回退、注视暂停、快照幂等、未初始化取快照。**6 毫秒**跑完（`bash tools/test.sh` → `TEST_OK 13 0.006s`），真机一轮要 5 分钟以上，而且没人能手掐出 19 秒与 21 秒的区别。纯测试代码，不进 APK、不改运行时行为。写的过程中发现两件事：`SessionTimer` 用 `0L` 当"当前不在亮屏"的哨兵，测试从 `now = 0` 起步会静默退化成 `IDLE`；`reset()` 在 JVM 上测不了，它经 `Timebase` 走到 `SystemClock`。

- **Auto-brightness: the guard does not take over your brightness mode** (decided 2026-11-09). It stays a pure corrector. Instead of implying the floor always holds, the page now spells out the two-value mechanism — the `SCREEN_BRIGHTNESS` value the guard can write back, versus the `screen_brightness_float` the system derives from ambient light — and keeps the one-tap **关闭自动亮度** for users who want the floor to actually bind. App-side text only; no behaviour change.
  **自动亮度：守卫不接管你的亮度模式**（2026-11-09 拍板）—— 保持"只纠偏"的定位。页面把两套值讲清楚（守卫能写回的 `SCREEN_BRIGHTNESS` 值，对比系统按环境光算出的 `screen_brightness_float` 实际显示亮度），不再暗示"下限永远守得住"，并保留一键「关闭自动亮度」给想真守住的人。**只改文案，不改行为。**

- **Home-screen feature order is now Eye Break → Minimum Brightness → Force Scroll → Extreme Saver** (set by the user). It is not the order things were built in; the list is sorted by "which one I want to reach first every day". New features go to the bottom unless the user says otherwise.
  **首页功能顺序改为：护眼休息 → 最低亮度守护 → 强制滑动 → 极致省电**（用户定）。这不是开发顺序，而是按"每天最先想开哪个"排；以后新增功能默认放最后，要调整先问。
- `Prefs.anyFeatureEnabled` is now a four-way OR (force scroll / saver / eye break / brightness guard). Keep-alive, watchdog and the "all off → stop the service" rule follow automatically; `KeepAliveService`'s start/stop logic was not touched.
  `Prefs.anyFeatureEnabled` 改为四路 OR（强制滑动 / 极致省电 / 护眼休息 / 最低亮度守护）。保活、看门狗、"全关就停服务"自动跟随；`KeepAliveService` 的起停逻辑没有改动。
- `KeepAliveService.onStartCommand` now also calls `EyeBreak.onServiceTick()`. That is the fallback for the known trap that a long `postDelayed` is **dropped** on this device (playbook §4.14 §3): the 20-minute due timer is re-armed on every 30-second watchdog tick, so the worst-case delay equals the watchdog interval — the same behaviour the standalone ESR has on the same device.
  `KeepAliveService.onStartCommand` 现在还调 `EyeBreak.onServiceTick()`。这是"本机长时间 `postDelayed` 会被丢弃"（手册 §4.14 §3）的兜底：20 分钟的到期表会在每次 30 秒看门狗心跳时重挂，最坏延迟等于看门狗间隔——与独立 ESR 在同一台机器上的行为一致。
- The resident notification now composes one segment per feature (eye-break timing / break in progress / brightness floor), and no longer claims "悬浮球在屏幕上" when the force-scroll switch is off.
  常驻通知改为按功能拼段（护眼计时 / 浮窗进行中 / 亮度下限），并且强制滑动开关关着时不再声称「悬浮球在屏幕上」。
- The accessibility service now declares `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents`. This adds **no** new `accessibilityEventTypes` (key events are a separate channel), so it does not wake the service on every tap; `onKeyEvent` returns `false` immediately unless the rest overlay is showing. The description shown on the system authorisation page was updated to match, since it now really does intercept Back during a break.
  无障碍服务新增 `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents`。这**不新增**任何 `accessibilityEventTypes` 订阅（按键是独立通路），因此不会每次点击都白唤醒；浮窗不在时 `onKeyEvent` 第一行就返回 `false`。系统授权页的描述同步改为如实说明"休息期间拦截返回键"。

### Permissions / 权限

Four added, each with a real reader and a status row plus a fix entry on the home screen:

新增四项，每项都有真实读取者，且首页都有状态行与授权入口：

| Permission / 权限 | Reader / 读取者 | If missing / 缺失时 |
|---|---|---|
| `WAKE_LOCK` | Screen-on lock while the rest overlay counts down (`FLAG_KEEP_SCREEN_ON` does not work on accessibility overlays) 浮窗倒计时期间的屏幕常亮锁（`FLAG_KEEP_SCREEN_ON` 对无障碍浮窗无效） | Overlay may be interrupted by screen timeout 浮窗可能被熄屏打断 |
| `VIBRATE` | Two-pulse vibration when a break ends 休息结束的两下短震 | No haptic cue, sound only 只剩声音 |
| `WRITE_SETTINGS` | Direct brightness write 亮度直写 | Falls back to Shizuku 退回 Shizuku |
| `READ_PHONE_STATE` | Dismiss the overlay when a call rings 响铃时立刻撤窗 | Falls back to call-UI window names 降级为来电界面窗口名检测 |

---

## [0.3] — 2026-11-07

The feature release: a second tool, Extreme Saver, plus one enforced layout for every feature page.

功能版本：第二个工具「极致省电」落地，同时把功能页版式统一成代码强制的标准。

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
