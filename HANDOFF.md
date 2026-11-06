# SwissKnife 交接文档 / Handoff

> **本文件在仓库内，是继续开发的唯一入口。** 新会话开工第一步读这里，不要翻聊天记录。
> 旧的位置 `D:\WorkBuddy\.workbuddy\memory\SWISSKNIFE-HANDOFF.md` 只留一行指针，不再维护第二份。
>
> 最后更新：2026-11-06（v0.1 存档）

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

一个安卓小工具集，当前只有「强制滑动」一个功能：**视频广告倒计时锁住滑动时，点一下屏幕边缘的悬浮球补一次上滑**。

- 项目路径：`D:\WorkBuddy\SwissKnife`（已是 git 仓库，v0.1 已提交并打标签）
- 包名：`com.swissknife`
- 技术栈：Kotlin + 原生 View（**无 Compose、无 XML 布局**，全部代码构建 UI）
- 版本：`0.1`（versionCode 1）/ minSdk 26 / target 35

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
| 仓库化（README / CHANGELOG / LICENSE / .gitignore / docs/archive） | ✅ 完成（2026-11-06） |
| v0.1 版本存档（`snapshots/v0.1/` + git tag） | ✅ 完成（2026-11-06） |
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
9. **手动点击验证时先量准球的真实位置**。球的 `params.y` 不等于屏幕上看到的坐标
   （`FLAG_LAYOUT_NO_LIMITS` + 状态栏偏移），照着 prefs 里的 `ball_y` 去 `input tap`
   会点到球下面的界面元素（实测点进了功能页）。先截图、按像素比例换算再点。

---

## 四、代码地图

```
app/src/main/java/com/swissknife/
├─ MainActivity.kt                主界面：授权状态、功能开关（回读 Prefs）、快捷方式注册、通知权限
├─ ShortcutToggleActivity.kt      透明 Activity，快捷方式的落点（切换开关 + 同步保活后 finish）
├─ core/
│  ├─ BaseActivity.kt             UI 基类：page/card/row/text/actionButton、配色常量
│  ├─ Prefs.kt                    SharedPreferences 封装 + anyFeatureEnabled 汇聚判据
│  └─ ShizukuShell.kt             Shizuku 授权与 shell 命令（单线程串行）
├─ access/
│  └─ ForegroundWatcherService.kt 无障碍服务：挂球、拖动、点击补滑、长按收起 + 保活看门狗
├─ inject/
│  └─ ScrollInjector.kt           通过 Shizuku 注入滑动
├─ keepalive/
│  ├─ KeepAliveService.kt         框架级保活：前台服务 + START_STICKY + Doze 白名单
│  └─ BootReceiver.kt             开机 / 覆盖安装后重新拉起常驻服务
└─ ui/
   └─ ForceScrollActivity.kt      功能页：运行状态 + 今日计数 + 使用说明（无开关，开关在主界面）

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

---

## 五、命令（一行搞定，别手敲）

```bash
cd D:/WorkBuddy/SwissKnife

bash tools/build.sh      # 只编译，输出 BUILD_OK / BUILD_FAILED + 错误摘要
bash tools/deploy.sh     # 编译 + 安装 + 强制重绑无障碍 + 补 appops/Doze + 启动
bash tools/log.sh all    # 读应用自记日志（files/watch.log）
bash tools/log.sh clear  # 清日志
bash tools/shot.sh <名>  # 截图到 shots/<名>.png
```

**`deploy.sh` 做了三件必须做的事**（不要绕过它直接 `adb install`）：

1. 编译安装
2. **强制移除再加回无障碍服务**（否则服务僵尸化，功能不工作）
3. `appops set com.swissknife GET_USAGE_STATS allow` + `cmd deviceidle whitelist +com.swissknife`
   （前者是历史遗留，后者是保活第 4 层，开发期直接设省得手动点「电池优化」）

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

---

## 八、发版流程（用户说「保存为 X 版本」时照这个做）

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. `bash tools/build.sh` 出包，确认 `BUILD_OK`
3. 建 `snapshots/v<版本>/`，放 `SwissKnife-v<版本>-debug.apk` + `VERSION.md`（双语：版本、日期、包名、构建类型、文件大小与 sha256、本版变更、验证记录、升级说明、已知限制）
4. `CHANGELOG.md` 加一节（英文在前、双语对照，带日期）
5. `git add -A && git commit`，再 `git tag v<版本>`，核对 `git tag` 与 `git log --oneline -1`
6. `snapshots/` 与 `tools/` 有 `.gitignore` 兜着，**不会进仓库**；APK 走 GitHub Releases

**v0.1 已完成上述 1–5 步**（2026-11-06，未签名 debug 包）。

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

> 继续开发 `D:\WorkBuddy\SwissKnife`（安卓工具集，当前有「强制滑动」功能，v0.1）。
> 先读 `D:\WorkBuddy\SwissKnife\HANDOFF.md`（唯一入口，含文档分工、铁律、命令、验证方法），
> 再读 `D:\WorkBuddy\.workbuddy\memory\ANDROID-DEV-PLAYBOOK.md` 的 §4.14（悬浮球与无障碍）和 §4.9（保活）。
> 部署一律用 `bash tools/deploy.sh`，不要直接 adb install（会漏掉无障碍重绑）。
> 不要试图恢复"自动显隐"，那五条路已经全部实测失败（`docs/archive/README.md`）。
