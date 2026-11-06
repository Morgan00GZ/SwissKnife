# SwissKnife 交接文档 / Handoff

> **本文件在仓库内，是继续开发的唯一入口。** 新会话开工第一步读这里，不要翻聊天记录。
> 旧的位置 `D:\WorkBuddy\.workbuddy\memory\SWISSKNIFE-HANDOFF.md` 只留一行指针，不再维护第二份。
>
> 最后更新：2026-11-06（v0.2 存档）

---

## 〇、文档分工（先看这张表，别在多处找）

| 文件 | 给谁看 | 内容 |
|---|---|---|
| `HANDOFF.md`（本文件） | 接手的会话 / 开发者 | 当前状态、踩过的坑、代码地图、验证命令、决策记录 |
| `README.md` | 仓库访客 | 功能、实现路径、权限、构建与安装步骤、已知限制 |
| `CHANGELOG.md` | 仓库访客 | 版本变更记录 |
| `docs/archive/` | 仓库访客 + 自己 | **已放弃的方案**及其判据（自动显隐五条路、白名单、闪背景） |
| `snapshots/v<版本>/` | 只有自己 | 该版 APK + VERSION.md（不进仓库，APK 走 Releases） |
| 安卓手册 `ANDROID-DEV-PLAYBOOK.md` §4.14 / §4.9 | 只有自己 | 十小节实测细节、保活验证方法论（在 `D:\WorkBuddy\.workbuddy\memory\`，不在仓库） |

---

## 一、一句话

一个安卓小工具集，当前两个功能：
1. **强制滑动**：视频广告倒计时锁住滑动时，点一下屏幕边缘的悬浮球补一次上滑。
2. **极致省电**：省电模式期间把默认数据卡从「含 5G」降到「只到 4G」，退出省电写回原值。

- 项目路径：`D:\WorkBuddy\SwissKnife`（已是 git 仓库，v0.1 / v0.2 已提交并打标签）
- 包名：`com.swissknife`
- 技术栈：Kotlin + 原生 View（**无 Compose、无 XML 布局**，全部代码构建 UI）
- 版本：`0.2`（versionCode 2）/ minSdk 26 / target 35（**极致省电尚未发版**，仍在 Unreleased）

---

## 二、当前状态

| 模块 | 状态 |
|---|---|
| 主界面（授权状态 + 功能开关） | ✅ 完成 |
| 功能页（运行状态 + 使用说明） | ✅ 完成 |
| 悬浮球（显示/拖动/点击补滑/长按收起） | ✅ 完成 |
| Shizuku 补滑注入 | ✅ 完成 |
| 桌面快捷方式（长按图标 → 开关悬浮球） | ✅ 完成 |
| 应用图标（白底黑线绿点） | ✅ 完成 |
| 框架级保活（前台服务 + 看门狗 + Doze 白名单 + 开机自愈） | ✅ 完成（2026-11-06） |
| **「极致省电」功能（省电联动降 4G + 二级状态页）** | ✅ 完成（2026-11-06，真机验证通过，未发版） |
| 仓库化（README / CHANGELOG / LICENSE / .gitignore / docs/archive） | ✅ 完成（2026-11-06） |
| v0.1 版本存档（`snapshots/v0.1/` + git tag） | ✅ 完成（2026-11-06） |
| v0.2 版本存档（`snapshots/v0.2/` + git tag）<br>修 3 个缺陷 + 权限/事件收窄 + 位置边界 | ✅ 完成（2026-11-06） |
| 正式签名 APK（release keystore + 混淆） | ❌ 未做 |
| 推 GitHub 远端 | ❌ 未做（本地仓库已就绪，只差建远程并 push） |

---

## 三、铁律：不要重复的坑

**动手前必须读 `D:\WorkBuddy\.workbuddy\memory\ANDROID-DEV-PLAYBOOK.md` §4.14**（悬浮球与无障碍专项，十小节）。
下面只列最致命的几条：

1. **日志说"悬浮球已显示"不等于肉眼看得见**。先截图确认，再查逻辑。这一条浪费了整三天。
2. **无障碍服务覆盖安装后会僵尸化**：绑定在、进程在，但 `onServiceConnected` 不执行、事件不来、日志文件都不生成。
   部署脚本必须"移除再加回"（已固化在 `tools/deploy.sh`）。
3. **`postDelayed` 在这台机器上只执行一次**，不能做轮询/兜底。要延迟用子线程 `Thread.sleep`。
4. **不要试图自动判断前台应用**。五条路全部实测失败（见 `docs/archive/README.md`）。
   当前设计是**手动显隐**：主界面开关 / 长按收起 / 桌面快捷方式。不要"优化"回自动。
5. **`monkey -p` / `am start` 与用户手点图标行为不同**，测出来的"无事件"不可外推。
   要复现用户操作，用 `input tap` 点真实图标，或让用户操作、自己看日志。
6. **不要用 `force-stop` 后的 `am start` 测"热启动"**：那等于冷启动，一定有事件。
7. **验证保活不能用 `am stopservice`**：shell 无权停前台服务，会返回 `Error stopping service`，
   看上去像"停不掉"。正确做法见第五节「保活怎么验证」。
8. **不要为了省事删掉保活那层**：球挂在无障碍服务里，而无障碍服务跑在普通应用进程里；
   没有前台服务时进程会被降为 cached 并冻结，**球静默消失、没有任何报错**。
   这类"莫名其妙没了"的现象，先查常驻服务在不在，再查球的逻辑。
9. **手动点击验证时只认 `dumpsys` 里的 frame，不要认 `params.y`，也不要靠截图目测**。
   读法（权威）：
   ```bash
   export MSYS_NO_PATHCONV=1
   ADB="D:/Software/AndroidEmulator/platform-tools/adb.exe"
   "$ADB" shell "dumpsys window windows" | grep -A14 "ACCESSIBILITY_OVERLAY" | grep -E "mAttrs|frame="
   ```
   `frame=[left,top][right,bottom]` 才是屏幕坐标，球心 = 中点。
   **历史坑（2026-11-06 已修）**：当时窗口只带 `FLAG_LAYOUT_NO_LIMITS`，`parent` 从 `(0,150)` 起算，
   于是 `mAttrs=(19,1397)` 实际渲染在 `frame=[19,1547][201,1729]`——差了一个状态栏高度，
   而且**这个偏移随绑定/重绑时机变化**（用户看到的就是"球自己挪下去了"）。
   已通过加 `FLAG_LAYOUT_IN_SCREEN` 让 `parent=[0,0]`、params 等于屏幕坐标。
   **如果以后又出现"球的位置和存的对不上"，先按上面命令读 frame 对比 `mAttrs`，不要先怀疑 Prefs。**

---

## 四、代码地图

```
app/src/main/java/com/swissknife/
├─ MainActivity.kt                主界面：授权状态、功能开关（回读 Prefs）、快捷方式注册、通知权限
├─ ShortcutToggleActivity.kt      透明 Activity，快捷方式的落点（切换开关 + 同步保活后 finish）
├─ core/
│  ├─ BaseActivity.kt             UI 基类：page/card/row/text/actionButton、配色常量
│  ├─ Prefs.kt                    SharedPreferences 封装 + anyFeatureEnabled 汇聚判据 + 4G 运行态
│  └─ ShizukuShell.kt             Shizuku 授权与 shell 命令（单线程串行）
├─ access/
│  └─ ForegroundWatcherService.kt 无障碍服务：挂球、拖动、点击补滑、长按收起 + 保活看门狗
├─ inject/
│  └─ ScrollInjector.kt           通过 Shizuku 注入滑动
├─ net/
│  └─ Saver4G.kt                  「极致省电」执行体：省电广播、isub 掩码读写、对账与强制还原
├─ keepalive/
│  ├─ KeepAliveService.kt         框架级保活：前台服务 + START_STICKY + Doze 白名单 + 功能钩子
│  └─ BootReceiver.kt             开机 / 覆盖安装后重新拉起常驻服务
└─ ui/
   ├─ ForceScrollActivity.kt      功能页：运行状态 + 今日计数 + 使用说明（无开关，开关在主界面）
   └─ Saver4GActivity.kt          功能页：策略值/实测值 + 依赖 + 使用说明

app/src/main/assets/inject.dex    注入器（源码在 tools/inject-src/，不进仓库的那一半）
app/src/main/res/drawable/ic_stat_swissknife.xml  通知小图标（纯透明底，不能带满幅底矩形）
```

### 保活设计（2026-11-06，照 EyeStrainRelief 移植）

| 层 | 机制 | 落点 |
|---|---|---|
| 1 | 前台服务 + `START_STICKY` | `KeepAliveService` |
| 2 | 无障碍服务看门狗（30 秒节流，借事件顺风车，不加定时器） | `ForegroundWatcherService.checkKeepAlive()` |
| 3 | `android:stopWithTask="false"` | manifest |
| 4 | Doze 白名单 `cmd deviceidle whitelist +pkg` | `KeepAliveService.onCreate`（Shizuku 可用时） |
| 5 | 开机 / 覆盖安装重新拉起 | `BootReceiver` + 无障碍被系统重新绑定 |

**与 ESR 的唯一差异**：ESR 需要 7×24 计时所以无条件常驻；SwissKnife 只在
`Prefs.anyFeatureEnabled` 为真时常驻，功能全关就 `stopSelf`，不留通知也不占进程优先级。
**新增功能时把自己的开关并入 `Prefs.anyFeatureEnabled`，保活自动跟随，不必改 KeepAliveService。**

**功能侧的进程内监听挂在 KeepAliveService 上**（`Saver4G.attach(this)` / `detach()`）：
它是"有功能在用"时唯一常驻的组件，监听跟着它的生死走，功能全关就不会留下任何注册。
**不要**为了监听再写第二个前台服务，也**不要**把它挂到无障碍服务上——那会让一个不依赖
无障碍的功能（极致省电）被迫要求无障碍权限。

**看门狗的判据是 `prefs.anyFeatureEnabled`**（2026-11-06 修正）。此前写的是
`prefs.forceScrollEnabled`，只开「极致省电」时球不存在、条件永不成立，常驻服务不会被补拉，
进程被冻结后就再也收不到省电广播，而且**没有任何报错**。新增功能时若改了判据，务必回读这一行。

**通知文案按真实状态拼**：4G 生效中 / 悬浮球在屏幕上 / 等待什么，多段用 ` · ` 连接。

**主界面「后台常驻」行**：显示 运行中/未启动，是保活是否生效的唯一可视判据。

`START_STICKY` 重建时 `onStartCommand` 会复检开关并 `stopSelf`——否则会留下一个空转的
常驻服务和一条无意义通知（用户在服务死亡的间隙关了功能的情况）。

### 关键设计

- **球的外观**：白色 62% 半透明 + 1dp 淡描边，圆形固定 56dp，文字按字数自动分成两行、字号随长度收缩。
- **球上文字不写死**：读 `Prefs.ballLabel`，哪个功能点亮球就写自己的名字（为以后多功能复用做准备）。
- **开关即总控**：`Prefs.forceScrollEnabled` 同时控制"功能启停"与"球显隐"；长按球会把开关一起关掉。
- **开关必须回读**：快捷方式与长按收球会绕过界面改开关，`refresh()` 每次都从 Prefs 回读，否则出现
  "开关显示关闭、球却在屏幕上"。
- **权限门禁**：打开开关前检查 Shizuku + 无障碍，缺什么弹 toast。每个功能通过 `featureEntry(requiredAuth = ...)` 自己声明依赖。

### 悬浮球的位置记忆（两条硬前提，缺一条就会"球乱跑"）

记忆本身只是 `Prefs.ballX/ballY` 两个整数，不存在"记不准"的问题。会出错的是**坐标口径**和**边界检查**：

1. **坐标系必须统一**：窗口必须带 `FLAG_LAYOUT_IN_SCREEN`，否则布局原点可能是状态栏下沿，
   同一个 `params.y` 会渲染出两个高度（实测差 150px，且随重绑时机变化）。
   带上之后 `params.x/y` 就等于屏幕坐标，存什么位置就出现在什么位置。
2. **恢复时必须夹进当前屏幕**：`y` 是"存的那一刻那块屏幕"上的坐标。横屏 / 分屏 / 改分辨率后
   旧坐标可能整块出屏（实测：屏幕高 1000 时 y=1397 → 完全看不见也拖不回来）。
   现在 `addBall` 会 clamp，`onConfigurationChanged` 会在几何变化时立即按 Prefs 重算。
   **clamp 只改窗口、不写 Prefs**，所以转回来能回到原位。

验证方式与结论见手册 §4.14 §12。

---

### 新增一个功能要改哪几处（照这个填，别复制整个服务）

框架已经把「悬浮球 + 保活 + 授权门禁 + 界面基类」抽象好了，新功能通常只碰四处：

| # | 改哪里 | 做什么 |
|---|---|---|
| 1 | `core/Prefs.kt` | 加 `var xxxEnabled`（自己的 key），并把它 **OR 进 `anyFeatureEnabled`** —— 保活会自动跟随，不用碰 KeepAliveService；运行态值（如"当前是否已改写"）也要落盘，否则进程被杀后无法恢复现场 |
| 2 | `MainActivity.buildUi()` 功能区块 | 加一个 `featureEntry(...)`：`requiredAuth` 声明本功能依赖哪些权限，`onToggle` 里写 `Prefs` + `ForegroundWatcherService.refresh()`（仅与球有关的）+ `KeepAliveService.sync(this)`，`switchRef` 交回引用以便回读 |
| 3 | `ui/XxxActivity.kt` + manifest | 功能页照 `ForceScrollActivity` / `Saver4GActivity` 抄骨架（状态卡 + 依赖 + 用法）；manifest 里 `exported="false"` |
| 4 | `access/ForegroundWatcherService.kt` | **只有"点球要做什么"不同才改**：`fireScroll()` 是点击落点。多功能时按 `prefs.ballLabel`（或显式的当前功能 key）分发，不要为每个功能复制一份服务 |
| 5 | `keepalive/KeepAliveService.kt` | **只有需要在进程里长期监听系统事件时才改**：在 `onCreate` 加一行 `Xxx.attach(this)`、`onDestroy` 加 `Xxx.detach()`（见 `Saver4G`）。业务逻辑写在功能自己的类里，这里只挂钩子 |

需要新的系统能力时：

- **注入事件**：复用 `inject/ScrollInjector.kt`，或按同样套路新增一个（Java 源码放 `tools/inject-src/`，编译产物进 `app/src/main/assets/`）。注意注入器源码目前不进仓库，见顶部「已知缺口」。
- **进程内系统事件监听**：把接收者注册进功能自己的 object，由常驻服务 attach/detach 它的生死（`Saver4G` 就是这样）。**不要**为了监听再写一个前台服务，也**不要**挂到无障碍服务上——那会让不依赖无障碍的功能被迫要无障碍权限。
- **新权限**：manifest 加 `<uses-permission>` + `MainActivity.refresh()` 加一行状态与授权入口。**用不上就不要加**（v0.2 刚删掉两项无读取者的权限，极致省电也一项没加）。

### 「极致省电」的实现要点（2026-11-06 真机实测，别再重试错的路）

**通道**：`cmd phone set-allowed-network-types-for-users -s <卡槽> <二进制掩码>`（Shizuku → shell）。

| 坑 | 现象 | 结论 |
|---|---|---|
| 掩码写成十进制 | `No valid NETWORK_TYPES_BITMASK`，看着像权限不足 | 参数是**二进制字符串**（源码 `Long.parseLong(s, 2)`） |
| 省略 `-s` | 回一句 `failed`，与权限失败无法区分 | 本机 `defaultVoiceSubId=-1`，默认槽解析失败 ⇒ **必须显式 `-s`** |
| 按打印的制式名反推掩码 | 本机打印 `HSPA+`、`CDMA - eHRPD` 这类别名，名表漏项会算错掩码 | 数值一律取 `dumpsys isub` 的 `allowedNetworkTypesForReasons=user=<整数>`，名字只用来看 |
| `settings put global preferred_network_mode1 9` | 写入成功，modem 不动，`allowed`/实测制式都不变 | 已判死，只有重启或切飞行模式才可能生效 |

**读权威值的三条命令**（`-s` 槽位由 `dumpsys isub` 的 `defaultDataSubId` + `simSlotIndex` 推）：

```bash
export MSYS_NO_PATHCONV=1
ADB="D:/Software/AndroidEmulator/platform-tools/adb.exe"
"$ADB" shell "dumpsys isub | grep -oE 'allowedNetworkTypesForReasons=user=[0-9]+' | head -1"   # 906119=含NR，381831=不含NR
"$ADB" shell "cmd phone get-allowed-network-types-for-users -s 0"                               # 策略（可读名）
"$ADB" shell "dumpsys telephony.registry | grep -oE 'getRilDataRadioTechnology=[0-9]+\([A-Z_]+\)' | head -1"  # 实测：NR_SA / LTE
"$ADB" shell "run-as com.swissknife cat files/net.log | tail -5"                                 # 应用自记日志
```

**开关省电模式的正确姿势**（`settings put global low_power 1` **不可靠**，设了也不一定真进省电）：

```bash
"$ADB" shell "dumpsys battery unplug"      # 充电状态下不掉电，系统可能立刻退出省电
"$ADB" shell "cmd power set-mode 1"        # 进省电；set-mode 0 退出
```

**一个必须知道的现象**：澎湃OS 自己也会在省电时动网络模式（`preferred_network_mode` 32→33，`secure` 里有 `pc_power_mode_5g_restored=1`）。所以代码里"读到 NR 已经不在"必须当作**已达目标**、且**不记还原点**，否则退出省电时会与系统自带的恢复互相覆盖。

**验证记录（2026-11-06，全部通过）**：

| 场景 | 权威判据 | 结果 |
|---|---|---|
| 省电开 + 开关开 | isub `381831`，RAT `LTE` | 5 秒内生效 |
| 省电关 | isub `906119`，RAT `NR_SA` | 自动还原 |
| 省电开 + 开关**关** | isub 保持 `906119` | 不动网络（符合"不开启就不改"） |
| 省电开着时开/关开关 | `已限 4G（开关变化）` / `已还原 5G（开关变化）` | 立即生效，不等广播 |
| 杀进程 → 关闭省电（广播丢失）→ 重绑无障碍 | `已还原 5G（服务启动）`，isub `906119` | 对账自愈 |
| 通知文案 | `4G 生效中（省电模式） · 强制滑动 · 悬浮球在屏幕上` | 与实现一致 |

**不要做的事**：

- 不要给每个功能各写一个前台服务——保活是框架层的，一个 `KeepAliveService` 管全部。
- 不要加"自动判断前台应用"的逻辑。五条路全部实测失败，判据在 `docs/archive/README.md`。
- 不要在 `onAccessibilityEvent()` 里塞业务逻辑。那里只跑保活看门狗；确实需要事件时再单独加类型，并记住：**多订阅一类事件 = 用户每次点击都白唤醒一次**（v0.2 刚砍掉三类）。
- 不要绕过 `tools/deploy.sh` 直接 `adb install`（会漏掉无障碍重绑，表现为"改了没生效"）。

---

## 五、命令（一行搞定，别手敲）

```bash
cd D:/WorkBuddy/SwissKnife

bash tools/build.sh      # 只编译，输出 BUILD_OK / BUILD_FAILED + 错误摘要
bash tools/deploy.sh     # 编译 + 安装 + 强制重绑无障碍 + 补 Doze 白名单 + 启动
bash tools/log.sh all    # 读应用自记日志（files/watch.log）
bash tools/log.sh clear  # 清日志
bash tools/shot.sh <名>  # 截图到 shots/<名>.png
```

**`deploy.sh` 做了三件必须做的事**（不要绕过它直接 `adb install`）：

1. 编译安装
2. **强制移除再加回无障碍服务**（否则服务僵尸化，功能不工作）
3. `cmd deviceidle whitelist +com.swissknife`（保活第 4 层，开发期直接设，省得手动点「电池优化」）

> 2026-11-06 起 `appops set GET_USAGE_STATS` 那一步已删除——用了 `PACKAGE_USAGE_STATS` 的功能
> 早已废弃，权限本身也从 manifest 里移除了。
>
> **改了 `accessibility_config.xml` 必须重新部署**：配置在服务绑定时读取，只装包不重绑不生效。
> 重绑后可以用 `dumpsys accessibility` 核对实际生效值，别凭 xml 猜：
> `Bound services:{Service[label=瑞士军刀, ..., capabilities=0, eventTypes=TYPE_WINDOW_STATE_CHANGED, ...]}`

### 保活怎么验证（三条硬指标，别用 `am stopservice`）

```bash
export MSYS_NO_PATHCONV=1
ADB="D:/Software/AndroidEmulator/platform-tools/adb.exe"

# ① 前台服务在不在、是不是真的 foreground
"$ADB" shell "dumpsys activity services com.swissknife" | grep -E "KeepAliveService|isForeground="
#   期望：isForeground=true foregroundId=2001 types=0x40000000

# ② 通知在不在（活动列表里，不是"关注状态"区）
"$ADB" shell "dumpsys notification --noredact" | grep "StatusBarNotification(pkg=com.swissknife"

# ③ Doze 白名单
"$ADB" shell "cmd deviceidle whitelist" | grep swissknife
```

**验证"进程被杀后能自己回来"**（等价于重启，且不必真的重启手机）：

```bash
"$ADB" shell "am force-stop com.swissknife"          # 进程 + 所有服务清零
"$ADB" shell "settings put secure enabled_accessibility_services 'com.swissknife/com.swissknife.access.ForegroundWatcherService'"
"$ADB" shell "settings put secure accessibility_enabled 1"
sleep 5
"$ADB" shell "dumpsys activity services com.swissknife" | grep isForeground=
"$ADB" shell "run-as com.swissknife cat files/watch.log" | tail -3   # 期望「悬浮球已显示」+「服务已连接」
```

实测结论（2026-11-06，真机）：无障碍重新绑定后 5 秒内进程、悬浮球、前台服务**三者同时自动恢复**，
且 `infoAllowStartForeground` 显示 `code:ACTIVITY_STARTER`、`BFGS denied: false`
—— 说明**从无障碍服务里启动前台服务不会被后台启动限制拦截**。

---

## 六、环境

| 用途 | 路径 |
|---|---|
| JDK 17 | `D:\DeepSeek Harness\AndroidDev\jdk-17` |
| SDK | `D:\DeepSeek Harness\AndroidDev\sdk` |
| Gradle | wrapper 8.9（腾讯云镜像，已缓存） |
| adb | `D:\Software\AndroidEmulator\platform-tools\adb.exe` |
| 真机 | 25098PN5AC（Android 17 / 澎湃OS / 1220×2656 / 520dpi） |

截图必须 `screencap` 到 `/data/local/tmp` 再 pull（`exec-out` 会污染 PNG）。
Git Bash 涉远程路径前 `export MSYS_NO_PATHCONV=1`。
构建需要 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT`（系统里没持久化，`tools/build.sh` 里已内置）。

**三个容易忘的环境事实**：

1. **`GRADLE_USER_HOME` 被指到了 `D:\DeepSeek Harness\.gradle`**，不是 `C:\Users\User\.gradle`。
   用户级 `gradle.properties` 要放在前者，放后者不生效（实测：放错位置时 clean 后构建仍失败）。
2. **aapt2 覆盖项在本机用户级配置里，不在仓库里**：
   `D:\DeepSeek Harness\.gradle\gradle.properties` →
   `android.aapt2FromMavenOverride=D:/DeepSeek Harness/AndroidDev/sdk/build-tools/35.0.0/aapt2.exe`
   原因：Maven 版 aapt2 在本机写 `stableIds.txt` 报「数据无效 (13)」，`processDebugResources` 必失败。
   **仓库内刻意不写这一行**（2026-11-06 移出），换机器不要抄。删掉该文件 = 回滚该覆盖。
3. **项目路径必须是纯 ASCII**。AGP 直接拒绝非 ASCII 路径（`Your project path contains non-ASCII
   characters`）。所以验证/试构建**不要放到 `Sessions\构建验证-20261106\` 这类中文目录**里，
   2026-11-06 实测踩过一次，白等 3 分钟才看到报错。用 `Sessions\build-check-YYYYMMDD\`。

**已知缺口（未修）**：注入器源码 `tools/inject-src/com/swissknife/inject/Injector.java` 在
`tools/` 里，而 `tools/` 不进仓库。因此 clone 下来**能编译**（`assets/inject.dex` 是提交过的二进制），
但**改不了注入逻辑**——要改必须在这台机器上重新 `javac`+`d8` 出 dex。若要仓库自洽，
应把 `inject-src` 移到仓库内（如 `injector/`）并在 README 写清重建命令。

**未实测的一条路径**：Shizuku 断开时「极致省电」只会记一条
`失败：Shizuku 未连接` 并保持原状（不重试、不谎报），但这条分支没有在真机上跑过
（要断开就得停掉 Shizuku 服务，会影响用户环境）。改动该分支时请补测。

**权限表里会多一条 `com.swissknife.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`**：
这是平台在 `registerReceiver(..., RECEIVER_NOT_EXPORTED)` 时**自动加的**，manifest 里没有声明。
下次清理权限时不要把它当"多申请的权限"删掉——删不掉，也不该删。

---

## 七、产品决策记录（用户定的，不要擅自改）

| 决策 | 理由 |
|---|---|
| **手动开关，不做自动显隐** | 系统给不了可靠信号，见 `docs/archive/README.md` |
| 白名单（守护列表）**已删除** | 球全局可用，不再需要范围限定 |
| 球显示功能名、不显示图标 | 以后多功能共用同一个球，名字由功能自己声明 |
| 一点就补滑，**不做自动检测** | "滑了但没滑成"无法观测，自动判据只会误翻 |
| 点击时**不闪任何东西** | 曾经用背景色闪烁，圆角被覆盖成方块，观感差 |
| 长按球 = 关闭功能（不只是收起） | 一个动作同时关掉功能与球，状态一致 |
| 保活只在有功能开启时常驻 | 没功能在跑时无需保护进程，常驻只会白占通知栏、白耗电 |
| 保活做成框架层、不绑死在某个功能上 | 新功能并入 `Prefs.anyFeatureEnabled` 即自动获得，不必复制一份服务 |
| 主界面开关必须回读 Prefs | 快捷方式与长按收球都会绕过界面改它，不回读就出现"开关关着、球在屏幕上" |
| 文档中英双语、英文在前 | 推 GitHub 的统一约定 |
| 无障碍只订阅 `typeWindowStateChanged` 一种事件，且不申请窗口内容读取 | 服务只把事件当保活心跳；多订阅的每一类事件都会让每次点击/刷新白唤醒一次 |
| 权限表即最小权限表，功能删了权限跟着删 | 没有读取者的权限会让用户白白授权，也容易被当成流氓应用 |
| 界面文案必须跟着实现走 | 出现过"球变绿"（根本没这回事）和"长按 1 秒收起、重开应用恢复"（实际 600ms 关功能、不恢复）这类错误说明 |
| **「极致省电」只作用于默认数据卡** | 本机 `defaultVoiceSubId=-1`，`-s` 取 `dumpsys isub` 的数据卡槽位；双卡另一张保持原设置（用户拍板） |
| **退出省电强制还原，即使期间手动改过网络模式** | 用户拍板。进入省电那一刻的状态是用户能预期、能解释的；留一个"我改过但没还原"的状态更像 bug |
| **「极致省电」不新增任何权限、不接悬浮球** | 省电检测是公开 API + 系统广播；改网络用已有的 Shizuku 身份。它不需要无障碍，所以 `requiredAuth` 只声明 Shizuku，不能顺手跟着强制滑动一起要无障碍 |
| 功能页「4G 是否生效」以 `dumpsys isub` 的掩码判定，不看内部 `cell4gActive` | 内部标记只回答"我欠不欠一次还原"；界面必须说系统事实，否则会出现"允许制式已限 4G、4G 是否生效却显示未生效"的自相矛盾（真机实测到过） |
| 常驻通知文案按真实状态拼（4G/球/等待） | 只开「极致省电」时球不存在，旧文案"等待无障碍服务连接"是假话，会把用户引去查一个不存在的权限 |
| 改系统级设置一律"先回读、再下结论" | `completed` 只代表命令执行完；`settings put` 可能完全不生效。判据只认 `dumpsys` / `cmd get` 的权威值 |

---

## 八、发版流程（用户说「保存为 X 版本」时照这个做）

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. `bash tools/build.sh` 出包，确认 `BUILD_OK`
3. 建 `snapshots/v<版本>/`，放 `SwissKnife-v<版本>-debug.apk` + `VERSION.md`（双语：版本、日期、包名、构建类型、文件大小与 sha256、本版变更、验证记录、升级说明、已知限制）
4. `CHANGELOG.md` 加一节（英文在前、双语对照，带日期）
5. `git add -A && git commit`，再 `git tag v<版本>`，核对 `git tag` 与 `git log --oneline -1`
6. `snapshots/` 与 `tools/` 有 `.gitignore` 兜着，**不会进仓库**；APK 走 GitHub Releases

**v0.1 / v0.2 已完成上述 1–5 步**（2026-11-06，均为未签名 debug 包）。

---

## 九、仓库发布约定（哪些不进仓库）

| 目录 | 处理 | 原因 |
|---|---|---|
| `app/` `docs/` `gradle/` `*.kts` `gradle.properties` `gradlew*` `README` `CHANGELOG` `LICENSE` `HANDOFF.md` | **进** | 换机器 clone 下来缺了它就跑不起来或看不明白 |
| `snapshots/` | 不进 | 版本存档与 APK，走 Releases |
| `tools/` | 不进 | 构建/部署/截图脚本，路径按这台机器写 |
| `shots/` | 不进 | 真机验证的临时截图 |
| `app/build/` `.gradle/` `.kotlin/` `local.properties` `*.apk` | 不进 | 构建产物与本机配置 |

**若要推远端**（尚未做）：先建远程仓库（账号 Morgan00GZ），再
`git remote add origin <url>` → `git push -u origin main --tags`。
**不要**为了瘦身去 `git rm --cached` 已误传的大文件——那不改历史体积，必须重写历史。

---

## 十、新会话开工提示词（可直接粘）

### A. 开发新功能（主用）

> 在 `D:\WorkBuddy\SwissKnife`（安卓工具集，v0.2，已有「强制滑动」）里新增一个功能：**【填功能名与一句话需求】**。
>
> 开工前先读三个地方，不要凭想象写代码：
> 1. `D:\WorkBuddy\SwissKnife\HANDOFF.md` —— 唯一入口。重点看「四、代码地图」里的
>    **「新增一个功能要改哪几处」** 表、「悬浮球的位置记忆」、「三、铁律」。
> 2. `D:\WorkBuddy\.workbuddy\memory\ANDROID-DEV-PLAYBOOK.md` 的 §4.14（悬浮球与无障碍，12 小节）
>    和 §4.9（保活）与 §4.15（状态记忆类功能的验证清单）。
> 3. `docs/archive/README.md` —— 已判死的路线，别重试。
>
> 硬约束（违反会返工）：
> - 部署一律 `bash tools/deploy.sh`，**不要** `adb install`（漏掉无障碍重绑 ⇒ "改了没生效"）
> - 保活是框架层的：新功能只需把开关并入 `Prefs.anyFeatureEnabled`，**不要**再写第二个前台服务
> - 不要加"自动判断前台应用"的逻辑，五条路已实测失败
> - 不要在 `onAccessibilityEvent()` 里塞业务逻辑；要新事件类型就先问，因为多订阅一类 = 每次点击白唤醒一次
> - 权限只加用得到的，加了就在首页给状态行 + 授权入口
> - 悬浮球窗口坐标必须带 `FLAG_LAYOUT_IN_SCREEN`，恢复位置必须 clamp 进当前屏幕
>
> 交付要求：
> - 先给「我理解的需求 / 预期效果 / 不做的部分 / 验收标准」，确认后再写代码
> - 真机验证：截图确认 + `dumpsys` 取权威值，不要只信日志（日志说"已显示"不等于肉眼看得见）
> - 收尾：改 `README` / `CHANGELOG`（双语、英文在前）、必要时更新 `HANDOFF`，再 commit
> - 用户说"保存为 X 版本"才做 `snapshots/vX/` + tag，平时不要自行发版

### B. 继续修 bug / 小迭代

> 继续开发 `D:\WorkBuddy\SwissKnife`（安卓工具集，当前有「强制滑动」功能，v0.2）。
> 先读 `D:\WorkBuddy\SwissKnife\HANDOFF.md`（唯一入口，含文档分工、铁律、命令、验证方法），
> 再读 `D:\WorkBuddy\.workbuddy\memory\ANDROID-DEV-PLAYBOOK.md` 的 §4.14（悬浮球与无障碍）和 §4.9（保活）。
> 部署一律用 `bash tools/deploy.sh`，不要直接 adb install（会漏掉无障碍重绑）。
> 不要试图恢复"自动显隐"，那五条路已经全部实测失败（`docs/archive/README.md`）。
