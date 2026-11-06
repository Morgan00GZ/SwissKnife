# SwissKnife 交接文档 / Handoff

> **本文件在仓库内，是继续开发的唯一入口。** 新会话开工第一步读这里，不要翻聊天记录。
> 旧的位置 `D:\WorkBuddy\.workbuddy\memory\SWISSKNIFE-HANDOFF.md` 只留一行指针，不再维护第二份。
>
> 最后更新：2026-11-06（v0.2 存档；新增「多会话协作约定」，首页移除悬浮窗授权行）

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

## 〇·一、多会话协作约定（2026-11-06 用户定，重要）

这个项目按「一类工作一个会话」推进：主框架归一个会话，**每个新功能各开一个会话**。用户**不会让多个会话并发改代码**，所以每次开工时代码都是最新的——但历史提交里已经有别人的功能，读文档和改代码都要按下面的边界来。

| 角色 | 负责 | 不要碰 |
|---|---|---|
| **主框架会话** | 首页结构、授权门禁、悬浮球、保活、仓库与发版、文档 | 不写具体功能业务 |
| **功能会话**（每个功能一个） | 该功能的 `Prefs` 开关、`ui/XxxActivity`、`xxx/` 实现 | 不重排首页结构、不复制前台服务、不动别人的功能入口 |

### 触发词：「先读交接文档」

用户认为该对账了，就只会打这六个字：**先读交接文档**

- **打了** → 执行下面的「三段式流程」（对账 → 干活 → 收尾批量验证）。
- **没打** → 正常做他要你做的事，不要自作主张跑对账流程；但收尾时仍要把**本轮没验过的改动**
  补进「二·二 清单」（这是记账，不是检查，不能省）。

### 三段式流程（打了触发词就走这个）

**第 1 段 · 对账（只读，不动代码）**

```bash
cd D:/WorkBuddy/SwissKnife
TAG=$(git describe --tags --abbrev=0)        # 自动取最近版本标签，不要写死
git log "$TAG"..HEAD --oneline --stat        # 自上次发版以来【所有会话】的改动
git status                                    # 有没有别人留下的未提交改动
```

再读「二·二 待真机验证清单」和本文相关章节。**这一段不验证、不改代码**，
只要弄清「别人改了什么、欠了几笔验证」。改动是跨会话累积的，`git log -5` 必漏。

**第 2 段 · 干自己的事**

按角色边界只改自己负责的范围。首页功能列表、`Prefs`、`KeepAliveService`、`strings.xml`、
`MainActivity.kt` 是共享面，改动越小越好；发现别人功能的问题记进「二·二」或单列待办，不要顺手改。
提交按功能粒度，一个功能一个 commit。

**第 3 段 · 收尾批量验证（历史欠账 + 本轮改动，一次验完）**

这是这套流程的重点：**不在开工时验，而在收尾时把两笔账合起来一次验完**——本轮的改动刚做完、
印象最新，历史欠账也一并清掉，比开工时分两次验省一半时间。

1. `bash tools/deploy.sh` 装机（不要 `adb install`，会漏无障碍重绑）。
2. 把「二·二 清单」里所有 `⬜` **加上本轮自己的未验证项**，逐条走一遍。
   每条都用 `dumpsys` / 截图取权威值，**不要只信日志**。
3. 通过 → `⬜` 改 `✅`，写一句**实测现象 + 日期**（不写"已通过"这种空话）。
   不通过 → 改成 `❌ 不通过：<现象>`，**停下来说明**，不要顺手改代码。
4. 单独 commit：消息写清验了哪几条、各自现象；本轮发现的新问题另开 commit，不要混进去。

**没设备时**：第 3 段做不了，就把本轮未验证项连同判据写进「二·二」清单，别让欠账只存在对话里
（对话一关就没人记得了）。

### 一直在生效的三条（与触发词无关）

1. **只改自己负责的范围**，共享面改动越小越好。
2. **提交按功能粒度**，不要把别人的文件顺带带进去。
3. **上下文可能已过时**：沿用旧会话（resume）时，你记得的代码状态是当时的。
   改任何文件前**重新读一遍**，只做增量 Edit；**不要整文件 Write、不要
   `git checkout -- .` / `reset --hard`**，那会把别人的改动一起抹掉。

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
| 功能页统一骨架（状态 / 依赖 / 用法，`BaseActivity.featurePage`） | ✅ 完成（2026-11-07，真机待验见「二·二」V5–V7） |
| 悬浮球（显示/拖动/点击补滑/长按收起） | ✅ 完成 |
| Shizuku 补滑注入 | ✅ 完成 |
| 桌面快捷方式（长按图标 → 开关悬浮球） | ✅ 完成 |
| 应用图标（白底黑线绿点） | ✅ 完成 |
| 框架级保活（前台服务 + 看门狗 + Doze 白名单 + 开机自愈） | ✅ 完成（2026-11-06） |
| **「极致省电」功能（省电联动降 4G + 二级状态页）** | ✅ 完成（2026-11-06，真机验证通过，未发版） |
| 仓库化（README / CHANGELOG / LICENSE / .gitignore / docs/archive） | ✅ 完成（2026-11-06） |
| v0.1 版本存档（`snapshots/v0.1/` + git tag） | ✅ 完成（2026-11-06） |
| v0.2 版本存档（`snapshots/v0.2/` + git tag）<br>修 3 个缺陷 + 权限/事件收窄 + 位置边界 | ✅ 完成（2026-11-06） |
| 首页移除「悬浮窗」授权行 | ✅ 代码完成（2026-11-06）<br>真机验证见「二·二」V1 |
| 不进「最近任务」（`excludeFromRecents`） | ✅ 代码完成（2026-11-06）<br>真机验证见「二·二」V2 |
| 正式签名 APK（release keystore + 混淆） | ❌ 未做 |
| 推 GitHub 远端 | ❌ 未做（本地仓库已就绪，只差建远程并 push） |

---

### 二·二、待真机验证清单（累积，谁验谁勾）

**规则**（2026-11-06 用户定）：

- 任何会话，凡是**改了代码但没能真机验证**（无设备、设备锁屏、只能编译），收尾时必须在这里加一行；
- 有条件验证的会话，**在收尾时（不是开工时）把整张清单连同本轮自己的改动一次验完**，
  不要只验自己那几行；验证发生在做完事情之后，所以本轮的改动印象最新，和历史欠账一起清最快；
- 验过的行把 `⬜` 改成 `✅`，写一句**实测现象**（不是"已通过"三个字）和日期，然后 commit；
- **不允许留过期条目**：已经不成立的（例如功能被删掉）直接改成 `— 已随功能删除`；
- 清单为空时保留表头，写"（空）"，让下个会话一眼看出没有欠账。

| # | 事项 | 判据（怎么算通过） | 来源 | 状态 |
|---|---|---|---|---|
| V1 | 首页授权区不再有「悬浮窗」行 | 进首页看「授权状态」卡片：只有 4 行 —— Shizuku / 无障碍服务 / 电池优化 / 后台常驻，且没有「悬浮窗」 | `1819a9f` | ⬜ 待验证 |
| V2 | 应用不进「最近任务」 | 打开应用 → 回桌面 → 进任务切换器：没有本应用卡片；再从图标拉起，界面与功能正常 | `5d9b6b0` | ⬜ 待验证 |
| V3 | 只开「极致省电」、关掉强制滑动时，常驻状态正确 | 关掉强制滑动（球消失）但开着极致省电：首页「后台常驻」应显示**运行中**，通知文案应是与省电相关的那条（不是"等待无障碍服务连接"） | `0a171c5`（跨会话组合，未单独验） | ⬜ 待验证 |
| V4 | 长按球关闭功能后，省电功能仍在跑时服务不应退出 | 同时开着两个功能 → 长按球 600 ms → 球消失、强制滑动开关变 false，但首页「后台常驻」仍为**运行中**（因为 `anyFeatureEnabled` 里省电还开着），通知文案切到省电那条 | `0a171c5`（`sync()` 的语义，未单独验） | ⬜ 待验证 |
| V5 | 两个功能页结构完全一致 | 分别进「强制滑动」「极致省电」：都按 返回 / 标题 / 说明 / **当前状态** / **依赖** / **怎么用** 排列；强制滑动页新增了依赖卡，省电页的状态区标题由「运行状态」改为「当前状态」 | 本轮（统一二级页标准） | ⬜ 待验证（本轮无设备） |
| V6 | 强制滑动页依赖行可点、状态跟着变 | 页内「无障碍服务」在系统关掉后显示「去开启 ›」且点得进无障碍设置；重开后显示「已就绪」。Shizuku / 后台常驻 同理 | 本轮 | ⬜ 待验证 |
| V7 | 首页授权行样式没被重构改坏 | 首页「授权状态」四行与改动前一致：就绪=绿字，未就绪=橙字+淡橙圆角底；点行仍能跳转 | 本轮（`setAuth` 合并进 `applyAuth`） | ⬜ 待验证 |

> V3 / V4 是**跨会话组合项**：单个会话各自只验了自己的功能，组合后没有人看过。
> 这类项最容易漏，正好一次验证全清。V5–V7 是 2026-11-07「统一二级页标准」留下的欠账，
> 那一轮设备断开（`adb devices` 为空），只做到编译通过。

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

### 功能二级页统一标准（2026-11-07 定，强制）

**一句话**：所有功能页都由 `BaseActivity.featurePage(...)` 生成，槽位与顺序固定，
**不允许**每个页面自己 `page() + sectionTitle() + card()` 拼一套——那样用户每进一个功能
都要重新找"状态在哪、缺什么、怎么用"。

```
‹ 返回
功能名                    26sp 加粗
说明                      13sp，一段话讲清"做什么、为什么需要它"
── 当前状态 ──            status()   × N   右对齐值，配 Tone 上色
                          note()          卡下一行小字（最近动作 / 计数口径），可空
                          raw()           11sp 权威原始值，仅当系统侧真读得到时用
── 依赖 ──                dep()      × N   整行可点，点了就去修
── 怎么用 ──              steps(list)      1..N 编号，文案必须与实现一致
                          footnote(s)      末尾一段补充说明（不分条目），可空
                          action(label)    可选按钮（能在首页做的别放这）
```

| # | 规则 | 违反会怎样 |
|---|---|---|
| 1 | 只用 `featurePage` 追加，不自己拼 View | 页面之间结构漂移，用户找不到信息 |
| 2 | 值一律 `setStatus(v, text, Tone)` 上色；四种语义色 OK/WARN/NEUTRAL/MUTED | 同一个意思在不同页面颜色不同 |
| 3 | 依赖行一律 `dep()` + `applyAuth()`（与首页授权行同一实现） | 首页橙色、功能页绿色，像两个问题 |
| 4 | **开关只在首页**，功能页不放开关键 | 两处都能开，状态必然对不上 |
| 5 | 依赖只列本功能真正需要的（强制滑动：Shizuku+无障碍+常驻；极致省电：Shizuku+常驻） | 列了不需要的，用户会去开一个没用的权限 |
| 6 | 「怎么用」编号从 1 连续，每条都能在代码里指到实现 | v0.2 就出过"球变绿""长按 1 秒"这种不存在的说明 |
| 7 | 没有系统真值就不放 `raw()`；放了必须标来源（如"系统原值"） | 摆一堆无法验证的数字，反而降低可信度 |
| 8 | 分组标题用共享串 `section_current` / `section_deps` / `section_howto` | 每页各建一个"当前状态"，文案迟早分叉 |

现有落地：`ui/ForceScrollActivity.kt`、`ui/Saver4GActivity.kt` 两页都按此标准重做，
新增功能照抄这两个文件的结构即可。

> 2026-11-07 这一轮同时把「首页授权行」的绘制合并进 `BaseActivity.applyAuth`，
> `MainActivity.setAuth` 已删除。**不要再在页面里复制一份"绿字/橙底"的绘制代码。**

---

### 新增一个功能要改哪几处（照这个填，别复制整个服务）

框架已经把「悬浮球 + 保活 + 授权门禁 + 界面基类」抽象好了，新功能通常只碰四处：


| # | 改哪里 | 做什么 |
|---|---|---|
| 1 | `core/Prefs.kt` | 加 `var xxxEnabled`（自己的 key），并把它 **OR 进 `anyFeatureEnabled`** —— 保活会自动跟随，不用碰 KeepAliveService；运行态值（如"当前是否已改写"）也要落盘，否则进程被杀后无法恢复现场 |
| 2 | `MainActivity.buildUi()` 功能区块 | 加一个 `featureEntry(...)`：`requiredAuth` 声明本功能依赖哪些权限，`onToggle` 里写 `Prefs` + `ForegroundWatcherService.refresh()`（仅与球有关的）+ `KeepAliveService.sync(this)`，`switchRef` 交回引用以便回读 |
| 3 | `ui/XxxActivity.kt` + manifest | 功能页**必须**用 `featurePage(...)` 骨架（见上一节「功能二级页统一标准」），照 `ForceScrollActivity` / `Saver4GActivity` 的结构填；manifest 里 `exported="false"` |
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
| **应用不进「最近任务」**（2026-11-06，照 ESR） | 这是个靠无障碍浮窗 + 前台服务在后台起作用的工具，任务切换器里没有值得恢复的内容；声明在 launcher Activity 上即可（`excludeFromRecents` 只对任务根生效），不要给每个功能页都加 |
| **首页不放「悬浮窗」授权行**（2026-11-06 移除） | 没有任何功能需要 `SYSTEM_ALERT_WINDOW`：球是无障碍的 `TYPE_ACCESSIBILITY_OVERLAY`。那行常年停在「去开启 ›」，等于教用户开一个开了也没用的权限。以后真有功能需要普通悬浮窗，再加回来，且那时必须背后有真实依赖 |

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
> **如果我另发了「先读交接文档」六个字，就按〇·一的三段式流程走（对账 → 干活 → 收尾批量验证）；
> 没发就正常做。** 下面三处无论哪种情况都要读：
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

### C. 触发词「先读交接文档」的含义（用户只打这六个字时）

> 用户打这六个字，意思是：**先对账，然后照常干你要干的事，最后收尾时把历史欠账和本轮改动
> 合起来一次验完**。三段的完整说明见「〇·一」，这里只列执行要点：
>
> 1. **对账（只读）**：`TAG=$(git describe --tags --abbrev=0)`；`git log "$TAG"..HEAD --oneline --stat`
>    拉全所有会话的改动；`git status`。再读「二·二 待真机验证清单」。**这一步不验证、不改代码。**
> 2. **干活**：按角色边界改自己负责的部分，一个功能一个 commit。
> 3. **收尾批量验证**：`bash tools/deploy.sh` 装机 → 把清单里所有 `⬜` 加上本轮自己的未验证项
>    逐条验完（`dumpsys` / 截图取权威值，别只信日志）→ 通过写 `✅ + 实测现象 + 日期`，
>    不通过写 `❌ 不通过：<现象>` 并停下说明 → 验证单独 commit。
>
> 没有设备时：第 3 段做不了，就把本轮未验证项连同判据补进「二·二」，**别让欠账只留在对话里**。
