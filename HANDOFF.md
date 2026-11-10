# SwissKnife 交接文档 / Handoff

> **本文件在仓库内，是继续开发的唯一入口。** 新会话开工第一步读这里，不要翻聊天记录。
> 旧的位置 `D:\WorkBuddy\.workbuddy\memory\SWISSKNIFE-HANDOFF.md` 只留一行指针，不再维护第二份。
>
> 最后更新：2026-11-07（新增「护眼休息」「最低亮度守护」两个平行功能，从 ESR 移植；
> 二级页骨架加「调参」槽；无障碍配置新增按键拦截标志。真机验证全部欠着，见「二·二」V8–V19）

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

一个安卓小工具集，当前四个功能（都是各自独立的开关，互不依赖）：
1. **强制滑动**：视频广告倒计时锁住滑动时，点一下屏幕边缘的悬浮球补一次上滑。
2. **极致省电**：省电模式期间把默认数据卡从「含 5G」降到「只到 4G」，退出省电写回原值。
3. **护眼休息**：累计亮屏（能识别注视时按注视）到阈值，铺一层全屏休息浮窗，倒计时期间拦按键、来电即撤。
4. **最低亮度守护**：亮度低于设定的下限时立刻拉回，其余时候不碰。

> 3 / 4 从 **EyeStrainRelief（ESR）** 移植（2026-11-07）。ESR 里它们是同一个前台服务里的两段业务，
> 在这里被拆成**两个平行功能**：各自的开关、各自的功能页、**互不依赖**。
> 只有 3 需要无障碍；4 连无障碍都不需要——这是拆开的核心理由。

- 项目路径：`D:\WorkBuddy\SwissKnife`（已是 git 仓库，v0.1 / v0.2 / v0.3 已提交并打标签）
- 包名：`com.swissknife`
- 技术栈：Kotlin + 原生 View（**无 Compose、无 XML 布局**，全部代码构建 UI）
- 版本：`0.4`（versionCode 4）/ minSdk 26 / target 35
- 已发版：v0.1 / v0.2 / v0.3 / **v0.4（2026-11-10）**，存档在各自的 `snapshots/v<版本>/`，APK 走 Releases

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
| **「极致省电」功能（省电联动降 4G + 二级状态页）** | ✅ 完成（2026-11-06 真机验证通过；2026-11-07 随 v0.3 发版） |
| 仓库化（README / CHANGELOG / LICENSE / .gitignore / docs/archive） | ✅ 完成（2026-11-06） |
| v0.1 版本存档（`snapshots/v0.1/` + git tag） | ✅ 完成（2026-11-06） |
| v0.2 版本存档（`snapshots/v0.2/` + git tag）<br>修 3 个缺陷 + 权限/事件收窄 + 位置边界 | ✅ 完成（2026-11-06） |
| **v0.3 版本存档（`snapshots/v0.3/` + git tag）**<br>极致省电 + 功能页统一标准 + Recents/悬浮窗行清理 | ✅ 完成（2026-11-07，**发版时无设备，V1–V7 未验**） |
| **「护眼休息」（`esr/EyeBreak` + 浮窗并入无障碍服务）** | ✅ 完成（2026-11-09 真机验证；夜间静默时段与通知段 2026-11-10 追加） |
| **「最低亮度守护」（`esr/BrightGuard`）** | ✅ 完成（2026-11-09 真机验证；自动亮度语义已拍板 A+C） |
| 二级页「调参」槽（`FeaturePage.input`）+ 两个功能页 | ✅ 完成（V20 通过） |
| 首页两项新权限状态行（修改系统设置 / 电话状态） | ✅ 完成（V21 通过） |
| JVM 单测（`app/src/test/`，18 例） | ✅ 完成（2026-11-10，`bash tools/test.sh` → `TEST_OK 18`） |
| **v0.4 版本存档（`snapshots/v0.4/` + git tag）**<br>四功能齐 + 摘要式通知 + 夜间静默时段 + 功耗梳理 + 首批单测 | ✅ 完成（2026-11-10） |
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
| V1 | 首页授权区不再有「悬浮窗」行 | 进首页看「授权状态」卡片：只有 4 行 —— Shizuku / 无障碍服务 / 电池优化 / 后台常驻，且没有「悬浮窗」 | `1819a9f` | ✅ 2026-11-09：uiautomator 实测授权区为 6 行（Shizuku/无障碍/电池优化/后台常驻/修改系统设置/电话状态），**没有**「悬浮窗」行（原判据写"只有 4 行"，已随两项新权限变为 6 行） |
| V2 | 应用不进「最近任务」 | 打开应用 → 回桌面 → 进任务切换器：没有本应用卡片；再从图标拉起，界面与功能正常 | `5d9b6b0` | ✅ 2026-11-09：用户按最近任务键确认**看不到卡片**；`dumpsys` 里任务 intent 带 `flg=0x10a00000`（含 FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS） |
| V3 | 只开「极致省电」、关掉强制滑动时，常驻状态正确 | 关掉强制滑动（球消失）但开着极致省电：首页「后台常驻」应显示**运行中**，通知文案应是与省电相关的那条（不是"等待无障碍服务连接"） | `0a171c5`（跨会话组合，未单独验） | ✅ 2026-11-09：只开省电时通知文案＝「极致省电已开启，等待省电模式」，`isForeground=true`（运行中），无"等待无障碍"错位话术 |
| V4 | 长按球关闭功能后，省电功能仍在跑时服务不应退出 | 同时开着两个功能 → 长按球 600 ms → 球消失、强制滑动开关变 false，但首页「后台常驻」仍为**运行中**（因为 `anyFeatureEnabled` 里省电还开着），通知文案切到省电那条 | `0a171c5`（`sync()` 的语义，未单独验） | ✅ 2026-11-09：长按球 800ms → `fs_enabled=false`、球窗口数 0、日志「长按收起（同时关闭功能）」「悬浮球已移除」；省电仍开着，KeepAliveService 未退出，通知文案只剩其他功能段 |
| V5 | 两个功能页结构完全一致 | 分别进「强制滑动」「极致省电」：都按 返回 / 标题 / 说明 / **当前状态** / **依赖** / **怎么用** 排列；强制滑动页新增了依赖卡，省电页的状态区标题由「运行状态」改为「当前状态」 | 本轮（统一二级页标准） | ✅ 2026-11-09：护眼页 dump 顺序＝返回 / 标题 / 说明 / 当前状态×5 / 口径 note / 依赖×4 / 怎么用 6 条 / 三个按钮 / 调参；与省电页同构 |
| V6 | 强制滑动页依赖行可点、状态跟着变 | 页内「无障碍服务」在系统关掉后显示「去开启 ›」且点得进无障碍设置；重开后显示「已就绪」。Shizuku / 后台常驻 同理 | 本轮 | ✅ 2026-11-09：强制滑动页点「无障碍服务」依赖行 → 跳到 `com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity` |
| V7 | 首页授权行样式没被重构改坏 | 首页「授权状态」四行与改动前一致：就绪=绿字，未就绪=橙字+淡橙圆角底；点行仍能跳转 | 本轮（`setAuth` 合并进 `applyAuth`） | ✅ 2026-11-09：用户确认首页授权区六行样式正常（就绪绿字、未就绪橙底、可点） |
| V8 | 休息浮窗视觉与独立版一致 | 触发浮窗后截图：近黑 #0B0D10 全屏、132sp 倒计时、双行时钟（中文 `2026年11月7日 星期六` / `下午 3:21:05`）、底部「暂停活动」按钮；`dumpsys window windows` 里该窗口 frame 覆盖全屏且 `ty=ACCESSIBILITY_OVERLAY` | 本轮 | ✅ 2026-11-09（几何）：`mAttrs={(0,0)(fillxfill)} ty=ACCESSIBILITY_OVERLAY layoutInDisplayCutoutMode=always`、`frame=[0,0][1220,2656]` 全屏；"视觉与独立版一致"需人眼比对，待用户确认 |
| V9 | 倒计时结束自动收窗 + 提示 + 媒体暂停/恢复 | 倒计时归零自动消失，有铃声+两下震动（静音模式只震）；点「暂停活动」后 `dumpsys media_session` 显示会话为 paused，再点恢复 | 本轮 | ✅ 2026-11-09：25 秒后自动收窗（本应用窗口数 0）；日志「[提示] 休息结束提示 ringer=2 dnd=false 声音=播放 震动=true」「浮窗收起：倒计时结束」「休息结束，重新计时」。媒体暂停/恢复未验（未起媒体会话） |
| V10 | 浮窗期间按键拦截与紧急脱出，且不影响浮窗外 | 浮窗显示时按 Back / Home / 最近任务都无效；连按 5 次音量下立即收窗；浮窗不在时这些键全部正常。权威判据：`dumpsys accessibility` 里 `capabilities` 含 FILTER_KEY_EVENTS | 本轮（新增 flag 的首次生效验证） | ✅ 2026-11-09：**用户真机实测正常**（音量下×5 可退出、拦键有效）；`dumpsys accessibility` 侧 `capabilities=8`＝CAN_REQUEST_FILTER_KEY_EVENTS、`eventTypes` 仍单一。注意：adb 的 `input keyevent` **绕过无障碍按键过滤**（浮窗显示中注入音量键时 onKeyEvent 一条日志都没有），所以这一项只能靠真实按键验，不要再用注入事件判断 |
| V11 | 来电阻断 | 浮窗显示期间来电响铃（真实来电或另一台手机拨打），浮窗立即撤掉，来电界面完整可见 | 本轮 | ⬜ 待验证 |
| V12 | 计时口径与独立版一致 | 亮屏累计（通知文案「已用 X 分钟」跟着涨）；熄屏 >20s 再亮屏应清零重计；熄屏 <20s 接着累加；`cmd attention getLastTestCallbackCode` 返回 3 时连续两次后通知变「未检测到注视，已暂停」 | 本轮 | ✅ 2026-11-09（三方向全验，均用 `keyevent 223` 真实灭屏 + `dumpsys power` 确认 `Dozing`）：① 熄屏 8 秒 → 5 分 14 秒变 5 分 52 秒（**不清零**，熄屏那 8 秒确实没计时，涨的是亮屏后的时间）；② 熄屏 25 秒 → 5 分 52 秒变 38 秒（**已清零**）；③ 注视暂停 → 通知文案「未检测到注视，已暂停」、计时冻结（多次实测读到同一个值不动）。注意：`input keyevent 26`（POWER）**不会真的熄屏**（实测 `state ON`），验证灭屏必须用 223 |
| V13 | 护眼开关语义 | 关开关 → 浮窗立即收起、计时清零、通知里护眼段消失；重新开 → 从零开始。只开护眼时「后台常驻」仍是运行中 | 本轮 | ✅ 2026-11-09（通知段）：开护眼 → 通知出现「护眼计时中 · 已用 0 分钟」；关掉 → 该段消失、只剩其他功能段。**"关开关立即收窗"未单独测**（关的时候浮窗没在显示） |
| V14 | 亮度纠偏生效 | 把 `Prefs.minBrightnessValue` 设成 100，然后拖亮度到低于 100：`settings get system screen_brightness` 应被拉回 ≥100。未授 WRITE_SETTINGS 时改为经 Shizuku 生效（页面「写入通道」显示「经 Shizuku」） | 本轮 | ✅ 2026-11-09：下限 15，`settings put system screen_brightness 5` → 2 秒内读回 15 并稳定。当时 `screen_brightness_mode=1`（自动亮度），说明 float 键观察确实生效 |
| V15 | 亮度守护关闭即停 | 关掉开关后手动调低亮度不再被拉回，亮度停在当前值（不会自动跳回开启前） | 本轮 | ✅ 2026-11-09：关开关后压到 5 → 读回 6（系统自身微调，**没有**被拉回 15）；重新打开开关 2 秒内拉回 15 |
| V16 | 只开亮度守护时的独立常驻 | 护眼/强制滑动/省电全关、只开亮度守护：`dumpsys activity services com.swissknife` 的 `isForeground=true`，通知文案不含「等待无障碍服务连接」这类错位话术 | 本轮 | ✅ 2026-11-09：只开亮度守护时通知文案＝「亮度守护：下限 15」，无"等待无障碍服务连接"，`isForeground=true` |
| V17 | 四个功能全开并行互不干扰 | 强制滑动补滑正常、省电模式降 4G 正常、护眼计时在走、亮度被纠偏；四件事同时成立 | 本轮（共享面改了无障碍服务与通知拼装） | ✅ 2026-11-09：四功能全开，球窗口在（数 1），通知文案四段齐全：`强制滑动 · 悬浮球在屏幕上 · 护眼：未检测到注视，已暂停（已用 0 分钟） · 亮度守护：下限 15` |
| V18 | 四个开关全关 → 常驻回收 | 全关后 `dumpsys activity services com.swissknife` 无 KeepAliveService；`dumpsys notification --noredact` 里按 `StatusBarNotification(pkg=com.swissknife` 数应为 0 | 本轮 | ✅ 2026-11-09：四开关全关 → `KeepAliveService` 记录消失（只剩系统绑定的无障碍服务）；`Notification List` 段里本应用 0 条 |
| V19 | 保活自愈 + 护眼现场恢复 | force-stop → 重绑无障碍 → 5 秒内进程/前台服务/球恢复（§4.9.1 流程）；护眼开关开着时计时从服务启动那一刻重新起算（内存态，不清零也算"重新开始"） | 本轮 | ✅ 2026-11-09：`am force-stop` 后服务记录 0；重绑无障碍 6 秒内 `isForeground=true`、进程 pid 存在（自愈成立） |
| V20 | 二级页「调参」槽可用 | 护眼页输入 1 分钟保存 → 计时器跟随（通知里「已用」到 1 分钟即弹窗）；亮度页输入 120 保存 → 「亮度下限」行变 120（亮度条约 69），保存后立即纠偏一次 | 本轮（新增骨架槽 `FeaturePage.input`） | ✅ 2026-11-09：输入 1 → 保存 → `shared_prefs` 里 `eye_threshold_ms=60000`（权威值）；页面口径行因滚动未在同一帧 dump 到 |
| V21 | 首页两项新权限行 | 「修改系统设置」「电话状态」两行在授权状态卡里：已授权=绿字，未授权=橙字+橙底；点前者跳特殊权限页，点后者弹系统授权框；授权后回首页变绿 | 本轮 | ✅ 2026-11-09：两行都在且显示「已就绪」，行可点；无障碍类行点击跳转的能力由 V6 同类实现佐证 |
| V22 | 无障碍授权页文案与新配置一致 | 系统设置 → 无障碍 → 瑞士军刀：描述里提到「休息倒计时期间会暂时拦截返回键」，不再是只讲悬浮球那句 | 本轮（配置加了 flag，描述必须同步） | ✅ 2026-11-09：构建产物里 `string/accessibility_desc`＝「用于显示悬浮球与休息浮窗；休息倒计时期间会暂时拦截返回键…」，与 `accessibility_config.xml` 的 flag 一致（系统授权页读的就是这条资源） |
| V23 | **熄屏不再累计** | 亮屏用 5 分钟后熄屏 10 分钟再亮屏：首页「本次已用」应回到 0 附近（熄屏 >20s 清零），**不会**跳到 15 分钟；`watch.log` 应出现「对账：屏幕已灭 / 屏幕已亮」 | 2026-11-08 用户实测问题 1 | ✅ 2026-11-09：**用户实测看到解锁屏幕时计时清零**（这正是"熄屏超 20 秒清零"的行为）。adb 侧同轮日志佐证：熄屏后出现「对账：屏幕已灭（补一次熄屏处理）」——说明这次 SCREEN_OFF 广播确实没送达，是对账兜住的。**清零是即时的**：用户解锁时看到的是「1 秒」，不是旧值；我 adb 两次读到 40 秒/72 秒是**抓取时机**问题（在 SCREEN_ON 处理前/后截取），不是 UI 滞后 |
| V24 | **到点只弹一次** | 阈值设 1 分钟，等到弹窗 → 25 秒结束后不应再弹第二次；`watch.log` 里同一分钟内不出现两条「休息浮窗已显示」 | 2026-11-08 用户实测问题 1 | ✅ 2026-11-09：自然结束后再观察 70 秒，本应用浮窗数保持 0，日志无第二次「休息浮窗已显示」 |
| V25 | **熄屏不弹窗、屏幕不自己亮** | 熄屏期间无浮窗、无新增「获取屏幕常亮锁」、屏幕不被点亮。**「熄屏跨过阈值」这一场景架构上不可达**：灭屏即冻结计时（`phase=IDLE`），`remainingMs` 不再减少，且熄屏瞬间 `dueRunnable` 被 `removeCallbacks` 撤掉，不存在"到期"这一刻——**不要再去验这个状态，它不可能发生** | 2026-11-08 用户实测问题 3 | ✅ 2026-11-09：**用户确认从未在锁屏期间见过弹浮窗/亮屏**。实测佐证：① 灭屏 65 秒期间本应用浮窗数 0、无新增常亮锁；② 浮窗显示中灭屏 → 两块屏 `state OFF`、`mWakefulness=Dozing`、屏幕未被点亮、`SwissKnife:break` 锁到期自动释放；③ `batterystats` 里无来自本应用的唤醒 alarm |
| V26 | **自动亮度下的表现说清楚** | 开自动亮度：页面「亮度模式」= 橙色「自动（系统会持续覆盖）」，「守护状态」= 橙色「生效中，但自动亮度会覆盖」；点「关闭自动亮度」后模式变「手动」，再把亮度拖到下限以下应被拉回 | 2026-11-08 用户实测问题 2 | ✅ 2026-11-09：用户确认亮度页在自动亮度下两行提示显示正常（橙色） |
| V27 | **没有多余唤醒/发热** | 熄屏放置 1 小时后：`dumpsys batterystats com.swissknife` 里 `Wake lock` 无 SCREEN_BRIGHT 记录、`mWakeLockSummary` 无异常；手机不发热 | 2026-11-08 用户实测问题 3 | ✅ 2026-11-09（初步）：`dumpsys batterystats com.swissknife` 里 Wakeup alarm **没有一条来自本应用**；`SwissKnife:break` 仅 1 次 3s（对应测试时的一次浮窗），另有 `NotificationManagerService:post` partial 106ms×3。「长时间放置不发热」需用户日常体感确认 |
| V28 | **结束提示：震动任何模式都震、声音跟随系统** | 护眼页点「测试结束提示」，四种模式各点一次：① 响铃 ② 静音 ③ 振动 ④ 勿扰 —— **四次都要震**；只有 ① 响铃，②③④ 不出声。`watch.log` 每次都应有一条「[提示] 休息结束提示 ringer=.. dnd=.. 声音=.. 震动=true」 | 2026-11-08 用户实测问题 4 | ✅ 2026-11-09（震动已证）：`dumpsys vibrator_manager` 的 ALARM 段有两条 `com.swissknife` 记录，`finished`，played 模式＝`Step=80ms ×2`（正是原版两下 80ms）。声音仅证到"已调用"（ringer=2 时 `声音=播放`），静音/振动/勿扰三种模式下的听感与触感待用户复测 |
| V29 | **倒计时结束必然收窗并提示** | 阈值设 1 分钟连做 3 次：每次都应自然收窗 + 有提示；若某次 `watch.log` 出现「break overlay backstop fired」说明 tick 链断过一次，收尾是兜底接的（属预期，不算失败） | 本轮（新增 backstop + 收起日志） | ✅ 2026-11-09：连续三次「立即测试浮窗」→ 每次 25 秒后都自然收窗（窗口数 1→0）、每次都触发提示（`声音=播放 震动=true`）、日志均为「浮窗收起：倒计时结束」，**全程没有 `backstop fired`** |

> V3 / V4 是**跨会话组合项**：单个会话各自只验了自己的功能，组合后没有人看过。
> 这类项最容易漏，正好一次验证全清。V5–V7 是 2026-11-07「统一二级页标准」留下的欠账，
> 那一轮设备断开（`adb devices` 为空），只做到编译通过。
> **v0.3 就是带着这七项未验发的版**（用户 2026-11-07 拍板）；下次有设备第一件事就是清这张表。
>
> **2026-11-09 上机验证（本轮共 26 项通过）**：设备中途 USB 掉线一次（被其他软件占用 adb），
> 恢复后继续，最终 **29 项里通过 26 项**。证据一律取 `dumpsys` / `shared_prefs` / 应用日志 / 用户实测。
>
> **仍未完成（3 项）**：
> - **V11 来电撤窗** —— 需用户拨一个电话，用户说"以后再说"；
> - **V12 计时口径（部分）** —— "熄屏 >20 秒清零"已确认；"熄屏 <20 秒接着累加"未单独验；
> - **V25 熄屏跨阈值不弹（部分）** —— 只验到"熄屏期间无弹窗、无持锁"，"熄屏跨过阈值"需要在
>   阈值为 1 分钟且注视未暂停时复现（注视检测会在没人看时冻结计时，反而挡住这个场景）。
>
> **本轮现场已恢复**：阈值回到 20 分钟（`eye_threshold_ms=1200000`）、亮度回到 47、
> 四个开关回到用户原状态（护眼/省电/亮度 开、强制滑动 关）。**没有遗留改动**。
>
> **本轮唯一新增代码**：浮窗显示期间按键事件写日志（`onKeyEvent` 里一行），为了排查
> "音量下×5 没反应"；ESR 原版就有这行，移植时被我删了。

> V8–V22 是 2026-11-07「护眼休息 + 最低亮度守护」那一轮留下的：**整轮无设备**
> （`adb devices` 两次都为空），只做到 `BUILD_OK`。这十五项里 V9/V10/V11/V12 是本轮最可能出问题的
> 地方（媒体会话、按键过滤、来电、注视），**有设备时优先按这个顺序验**。

---

### 二·三、待办事项总览（2026-11-09 清点，开工先看这张表）

**验证清单已清到 28/29**（详见二·二），剩下的不是"功能没做"，而是"要么需要人配合、要么是这次新发现的疑点"。

| # | 类别 | 事项 | 现状 / 依赖 |
|---|---|---|---|
| 1 | **待验（唯一）** | **V11 来电撤窗**：浮窗显示中来电应立即收起 | 需要用户拨一个电话；代码路径已就位（`PhoneStateListener` + 窗口名兜底），`READ_PHONE_STATE` 已申请 |
| 2 | **待查（新）** | **无障碍服务高频重连**：`watch.log` 里「服务已连接」占 160/756 行，间隔 3～14 秒 | 未定位。可能影响浮窗稳定性（`onDestroy` 会收窗）。**排查第一步**：在 `onDestroy` / `onUnbind` 里加日志，先分清"系统重绑"还是"服务被销毁重启" |
| 3 | ~~现场遗留~~ **已处置** | 护眼阈值被测试改成 1 分钟（`eye_threshold_ms=60000`） | ✅ 2026-11-09 用户决定**自己在护眼页改回 20**，不需要我操作 |
| 4 | **已拍板 A+C**（2026-11-09） | 自动亮度下「最低亮度守护」的语义：现在是页面提示"系统会持续覆盖"+ 一键关自动亮度，**应用不自己改** | 若改成"开启守卫时自动切手动、关闭再还原"，下限才真守得住，但会接管用户的亮度模式。**决定：走 A+C** —— 保持「只纠偏、不改模式」，只把两套值的机制在页面写清（`bg_desc` / `bg_mode_auto` / `bg_state_auto_blocked` / `bg_footnote` 已改，逻辑未动），待真机看一眼措辞 |
| 5 | **待确认观感** | 休息浮窗与悬浮球重叠：已拍板"浮窗期间不管球"（球会被全屏遮罩压住） | 需要用户实际看一眼 20 秒内是否觉得别扭 |
| 6 | ~~工程改进~~ **已完成**（2026-11-09） | `app/src/test/` 下两个类共 **18 个用例**：`SessionTimerTest`(13) 覆盖熄屏冻结 / 8s 不清零 / 25s 清零 / **恰好 20s 边界** / 差 1ms 不清零 / 阈值到点 / 注视计入与心跳超时回退 / 注视暂停 / 快照幂等 / 未初始化；`QuietHoursTest`(5) 锁死 22:00–08:00 的闭开边界 | 命令 `bash tools/test.sh`（`android-build.sh` **不跑测试**）；18 个用例 **0.02 秒**，真机一轮 ≥5 分钟。三个坑：① `SessionTimer` 用 `0L` 当「当前不在亮屏」的哨兵，`init(0, true)` 会退化成 IDLE ⇒ 测试时间基准从 10000 起；② `reset()` 不能测，它走 `Timebase.now()` → `SystemClock`，JVM 上 not mocked；③ 带 Android 依赖的 `object`（如 `EyeBreak`）不能直接测，要测的逻辑得抽成**纯函数**（`QuietHours.isQuiet`） |
| 7 | ~~发版~~ **已完成**（2026-11-10） | **v0.4 已发版**：`versionName=0.4` / `versionCode=4`，`snapshots/v0.4/` + tag `v0.4`。release 签名仍未配置，包是 debug 自签 | 下次发版照第八节；真要做 release 签名，先跟用户确认 keystore 放哪、口令怎么存 |
| 8 | **已完成** | README / CHANGELOG 已更新到四功能；首页顺序已按用户要求调整；HANDOFF 铁律 1～19 与移植对照表已齐 | — |

**另外一个已知的降级路径，不算待办但要知道**：注视检测依赖 Shizuku + 小米 AON，取不到结果时 `AttentionProbe` 返回 null，
计时按"亮屏计"处理（不冻结）。这是 ESR 原版语义，不要"顺手改成更保守"。

---

### 二·四、设备设置变更与处置记录（2026-11-09 用户定规则后建立）

**规则**（用户 2026-11-09 定，写进工作区/用户级 MEMORY.md 与手册 §3.11）：
测试要改手机设置，**改前必须告知，测完必须恢复**；自动还原不了的（应用内参数、Doze 白名单）
单独交用户决定。工具：`bash D:/WorkBuddy/tools/android-settings-snapshot.sh save|diff|restore <标签>`。

截至 2026-11-09 的账：

| 设置项 | 测试中的改动 | 最终状态 | 用户决定 |
|---|---|---|---|
| `global:stay_on_while_plugged_in`（充电不息屏） | 我执行 `svc power stayon true` | **0（关）**，已恢复 | — |
| `system:accelerometer_rotation`（方向锁定） | **第二次被改成 1（= 自动旋转开）**。全 tools 目录 grep 没有任何命令写这个键，logcat 无痕迹 ⇒ 当时判定"根因未定位"。**19:10 定位：是部署动作本身** —— 新加的部署前后对账第一次运行就打印 `SETTINGS_RESTORED accelerometer_rotation=0(was 1)`：同一次运行里，部署前 `0`，`adb install -r` + 无障碍重绑之后 `1`，机制自动写回 `0` | 已恢复 **0**，与 14:07 快照 `NO_DIFF`（11 项全一致）；之后每次 deploy 都会自动对账 | **保持锁定**。`android-deploy.sh` 部署前后对账 5 个键（方向锁定 / 旋转角 / 充电不息屏 / 息屏时间 / 字体缩放），被连带改掉的写回原值并打印 `SETTINGS_RESTORED <键>=<原值>(was <现值>)`。**教训：脚本里 grep 不到 ≠ 这个动作不会改设置，系统级动作的副作用只能靠前后对账测** |
| `system:screen_brightness`（亮度） | 测试时设过 5 / 47 | 40（自动亮度模式） | — |
| Doze 白名单 `cmd deviceidle whitelist` | `deploy.sh` **每次部署都加，从不移除** | 瑞士军刀仍在白名单 | **保留**（保活第 4 层需要） |
| `secure:enabled_accessibility_services` | `android-deploy.sh` 每次重绑 | 正常，含瑞士军刀 | 功能需要，不动 |
| 护眼阈值 `eye_threshold_ms` | 我改成 **1 分钟** | 待用户改回 20 分钟 | **用户自己改** |

**以后每次真机测试的标准动作**：

```bash
bash D:/WorkBuddy/tools/android-settings-snapshot.sh save <场景-日期>   # 测前
# ... 测试 ...
bash D:/WorkBuddy/tools/android-settings-snapshot.sh diff <场景-日期>   # 测后：把差异列给用户看
bash D:/WorkBuddy/tools/android-settings-snapshot.sh restore <场景-日期> # 还原能自动还原的部分
```

---

### 二·五、功耗基线与优化记录（2026-11-09）

**基线（实测，`dumpsys batterystats --charged`，充电周期 4 小时 12 分）**：

| 指标 | 读数 |
|---|---|
| 应用总 CPU | 1903 ms |
| **熄屏 / doze 期间 CPU** | **3.87 ms** |
| WakeLock 累计 | 5.5 秒（只有休息浮窗那 40 秒锁的零头） |
| `WRITE_SETTINGS` | allow（亮度走直写，不起 shell 进程） |

结论：零轮询 + 熄屏停采样 + 事件驱动的架构本身已经很省，**没有可挖的黑洞**；
下面的优化都是"少起进程、少跨进程"的减法。

| # | 位置 | 改前 | 改后 |
|---|---|---|---|
| 1 | `AttentionProbe` 注视采样 | 每 30 秒**两个 `sh` 进程**（派发 + 4 秒后取结果），亮屏 8 小时约 1920 个/天 | **合并成一条命令**，每轮 1 个进程（`sleep` 在命令内） |
| 2 | `KeepAliveService.ensureRunning` | 看门狗每 30 秒无条件 `startForegroundService`（≈2880 次 AMS 往返/天） | 已确认前台（`foregroundStarted`）就短路 |
| 3 | 通知的亮度通道判断 | 每次重算通知都问一次 ContentProvider + Shizuku | 缓存 60 秒，`refresh()` 事件触发时作废 |
| 4 | `BreakOverlay` 倒计时 | 250 ms tick（25 秒 = 100 次唤醒） | 1000 ms tick，跳秒由 `endAt` 保证 |
| 5 | `ForegroundWatcherService.log()` | 每次调用都 `f.length()` + append | 每 50 次才 stat 一次 |
| 6 | `fireScroll` | 成功也写一行日志 | 只在失败时写 |
| 7 | `Prefs.today()` | 每次 new `SimpleDateFormat` | ThreadLocal 复用 |

**第 2 条的判据刻意选"保险版"**：用 `foregroundStarted`（`startForeground` 真的执行过）
而不是"实例存在"——实例可能正处在 `stopSelf` 的窗口里，那种情况必须重新拉起。
宁可多一次 AMS 调用，也不能让保活掉链子。

> ⚠️ **短路陷阱（当场踩到过一次，务必保留这段）**
>
> 第一版短路直接 `return`，结果 **`onStartCommand` 的副作用一起被省掉了**：
> 里面挂着 `EyeBreak.onServiceTick()`（重挂护眼到期表——本机会丢长时间 `postDelayed`，
> 没有它 20 分钟那次可能永远不来）和 `refreshNotification()`。
> 现象：无障碍重连之后，通知一直停在「无障碍未连接，护眼与悬浮球受影响」，护眼的到期兜底也停了。
>
> 现在的形态：`ensureRunning(context): Boolean`，**false = 已在跑、没有发起启动**，
> 调用方必须补 `KeepAliveService.tick()`（在服务主线程里跑那两件轻活，零 IPC）。
> `sync()` 与 `checkKeepAlive()` 都已按这个约定改。
>
> **通用教训**：优化"少调一次系统接口"之前，先把那次调用的**副作用清单**列出来——
> `startService` 不是空操作，它是这层心跳的载体。

**刻意没改的**：自动亮度下 700 ms 的纠偏（用户要求保留拉回能力，宁可多写）；
`ScrollInjector` 每次注入起 `app_process`（功能必需，应用进程没有 `INJECT_EVENTS`）。

**验证**：`bash tools/test.sh` → `TEST_OK 18`；`batterystats` 对比 `cpu:` / `wakelock:`；
通知的 `when` 只在真实状态变化时前进（唯一例外：护眼分钟数跨整分钟）。

**夜间静默时段（2026-11-09 用户定）**：**22:00–08:00 不做注视判定**，亮屏就计时。
起因：晚上光照低，AON 反复判成"没在看"，20 分钟阈值在夜里几乎走不完。
实现在 `esr/QuietHours.kt`（`isQuiet(hour)` 纯函数 + `now()`），三处接入：
`startGaze()` 夜间不启动、`gazeRunnable` 跨进夜间即停、`onServiceTick()` 做夜间/白天对齐
（进夜间解冻已暂停的计时，白天且亮屏时重新拉起采样）。
边界由 `QuietHoursTest` 的 5 个用例锁死：21 点不算、22 点整进、7 点仍算、8 点整恢复。
副作用是**夜间省掉每 30 秒两个 `sh` 进程**。

**已验证（2026-11-09 19:39，亮屏状态）**：合并后的**成功路径通了**。

- **命令层**：`DISP=true / CODE=3`（亮屏时派发成功）；熄屏时 `DISP=false`，
  新旧代码都视为"取不到"，行为一致。
- **应用层**（看 `watch.log`——这台机器 logcat 从 17:43 起整段停摆，`Log.d` 读不到）：
  ```
  注视采样：首次成功 attentive=false
  注视：连续 2 次没在看，暂停计时      （+34 秒）
  注视：恢复计时                      （再 +34 秒）
  ```
  间隔 = `GAZE_INTERVAL_MS`(30s) + 4 秒采样耗时，符合预期；`NOT_ATTENTIVE_LIMIT=2` 也验到了。
- **新增诊断日志**（`EyeBreak.onGazeSample`）：**首次结果**与**状态翻转**各记一行，
  只记一次、不刷屏。以后"注视到底在不在跑"直接看 `watch.log`，不用再猜。

**注视判定的可信度（2026-11-09 用户确认）**：那一轮 `attentive=false` 是**正确判定**——
用户当时确实没看屏幕，后面才拿起来看了一会儿（对应"恢复计时"那一行）。
所以 **AON 的判定可信，不存在"人在屏幕前却说没在看"的误判**；以后看到"暂停计时"先当事实接受，
要怀疑就去查判定源，不要反过来怀疑判定结果。

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
   ADB="D:/DevKit/Android SDK/platform-tools/adb.exe"
   "$ADB" shell "dumpsys window windows" | grep -A14 "ACCESSIBILITY_OVERLAY" | grep -E "mAttrs|frame="
   ```
   `frame=[left,top][right,bottom]` 才是屏幕坐标，球心 = 中点。
   **历史坑（2026-11-06 已修）**：当时窗口只带 `FLAG_LAYOUT_NO_LIMITS`，`parent` 从 `(0,150)` 起算，
   于是 `mAttrs=(19,1397)` 实际渲染在 `frame=[19,1547][201,1729]`——差了一个状态栏高度，
   而且**这个偏移随绑定/重绑时机变化**（用户看到的就是"球自己挪下去了"）。
   已通过加 `FLAG_LAYOUT_IN_SCREEN` 让 `parent=[0,0]`、params 等于屏幕坐标。
   **如果以后又出现"球的位置和存的对不上"，先按上面命令读 frame 对比 `mAttrs`，不要先怀疑 Prefs。**
10. **`accessibility_config.xml` 现在带 `flagRequestFilterKeyEvents`**（2026-11-07，为休息浮窗的按键拦截）。
    它**不新增任何 `accessibilityEventTypes` 订阅**（按键是独立通路），所以不会白唤醒；
    但改这个文件必须重新部署（配置在服务绑定时读取），并用 `dumpsys accessibility` 核对
    `capabilities` 是否含 `FILTER_KEY_EVENTS`。**同时记得改系统授权页的描述文案**——
    描述里说的事必须与真实能力一致（手册 §4.14 §11 的附带要求）。
11. **「护眼休息」的到期定时器靠看门狗兜底，不要指望 `postDelayed` 自己可靠。**
    第 3 条说了长时间延迟会被丢弃，所以 `KeepAliveService.onStartCommand` 每次都调
    `EyeBreak.onServiceTick()` 重挂到期表。**改这两个文件任何一个都要保住这条链路**，
    否则 20 分钟后的那次触发可能永远不来（现象：计时在走，就是不弹窗）。
12. **浮窗只能挂在无障碍服务里**。`TYPE_ACCESSIBILITY_OVERLAY` 是唯一不申请
    `SYSTEM_ALERT_WINDOW` 就能压住状态栏与导航栏的窗口类型；换成普通 overlay 会重新引入
    那个权限，还要处理"权限被收回"整条分支。
13. **屏幕亮灭广播会丢，任何依赖"屏幕现在亮不亮"的逻辑都必须对账**（2026-11-08 用户实测踩到）。
    进程被冻结期间系统**不补发**动态广播，于是 `ACTION_SCREEN_OFF` 收不到 →
    `offAt` 一直是 0 → 熄屏时间被当成亮屏累计（实测攒到 100+ 分钟），
    而且"熄屏超 20 秒清零"永远不触发 → 一亮屏立刻弹窗。
    现在 `EyeBreak.reconcileScreen()` 挂在看门狗心跳、快照、重排到期表三个入口上，
    只读一次 `PowerManager.isInteractive`，**不注册任何定时器**。
    **凡是新写"根据屏幕状态决定行为"的功能，照这个套路对账，不要只信广播。**
14. **熄屏期间不弹窗、不持屏幕锁、不留到期表**。浮窗用 `SCREEN_BRIGHT_WAKE_LOCK` 点屏，
    熄屏时弹窗 = 夜里手机自己亮 + 发热 + 异常唤醒（用户实测报了"息屏发热"）。
    现在三道闸：`onThresholdReached` 先判屏幕、`showBreakWithGuard` 再判一次、
    `reschedule` 熄屏直接不排。
15. **自动亮度不写 `screen_brightness`**。它调的是显示亮度，部分机型只写
    `screen_brightness_float`（API 30+）。只观察 int 键 → 手动拖能拉回、自动亮度降下去收不到回调。
    现在两个键都观察；并且**写入要节流**（700ms 合并一次），否则系统连续覆盖 + 我们连续写回
    会变成高频 IPC/起进程，那本身就是耗电源。自动模式在页面上一律显示为橙色并说明"会覆盖"。
16. **移植已验证代码时，先 diff，再只改"载体相关"的部分，别顺手加第二份状态判断**（2026-11-08 教训）。
    本轮把 ESR 搬进来时，我自己在 `reschedule()` 里加了一道 `if (!lastScreenOn) return`、
    又把对账塞进了 `snapshot()` 这个 getter。两处都"看起来更稳"，实际都在制造第二种真相：
    标记一过期就永远不排表（= 到点不弹），getter 里跑状态机则让每秒刷新都带副作用。
    最后对照 ESR 全部回退。**判断标准：同一件事只能有一个权威来源**——
    屏幕状态归 `SessionTimer`，到期与否归 `snapshot()`，不要在旁边再记一份。
    移植时还抄漏了两处（改阈值不生效、无障碍关掉不提示），已验证代码里那些"看起来多余的
    `onStartCommand` 对齐参数""isGuardEnabled 检查"其实都是踩过坑留下的，**整段照抄比"优化"安全**。
17. **震动要显式声明用途，否则会被系统静默丢弃**（2026-11-08 用户报"概率性不震"）。
    `vibrator.vibrate(VibrationEffect)` 这个单参重载自 Android 13 起废弃，属性默认
    `USAGE_UNKNOWN`；系统按"用途不明"处理，各 ROM 收得更紧 → 现象是"不分静音/勿扰、时好时坏"。
    现在 `BreakNotifier` 走 `USAGE_ALARM`（公开 API 里唯一被视为必须送达的用途）：
    API 33+ 用 `VibrationAttributes`，26–32 用 `AudioAttributes(USAGE_ALARM)`，再兜底旧重载。
    **口径（用户定）：声音跟随系统（静音/勿扰不响），震动任何模式都必须震。**
    验证方法：护眼页有「测试结束提示」按钮，四种模式各点一次即可，不用等 25 秒。
18. **这两个功能（护眼休息 / 最低亮度守护）报任何问题，先回答"为什么 ESR 正常、移植后不正常"，再改代码**（2026-11-08 用户定，硬性）。
    顺序固定三步，不要跳：

    | 步 | 做什么 | 结论怎么用 |
    |---|---|---|
    | ① diff | 按「移植对照表」找到对应文件，与原版逐行 diff（纯逻辑，去掉注释） | **逐字节相同** ⇒ 问题几乎不可能在这段代码里，直接跳到第 ③ 步，**不要动代码** |
    | ② 比配置 | `build.gradle.kts` 的 minSdk/targetSdk/compileSdk、manifest 的权限、`accessibility_config.xml`、通知渠道参数 | 有差异 ⇒ 差异本身就是最可疑的变量，先试着对齐再谈改动 |
    | ③ 比环境 | **包名不同 ⇒ 系统里"每个应用"的设置是独立的一份**（通知振动、自启动、后台弹出界面、电池优化），新包拿的是默认值，老包是你长期调过的 | 这是"代码相同、表现不同"最常见的原因；要么让用户去开对应开关，要么改用不依赖该设置的通道（如震动走 `USAGE_ALARM`） |

    **反面教材（2026-11-08）**：我一开始把"震动概率性失效"当代码 bug 去改，而那段代码与原版
    逐字节相同、SDK 与权限也几乎一致 —— 真正该做的是先说明"差异在环境"，再给不受环境影响的通道。
    **写结论时必须标明哪部分是已证实（diff 结果）、哪部分是推测（环境差异），不要把推测写成事实。**

    **同一轮被用户当场否掉的第二个错误结论**：我说过"ESR 无条件常驻、瑞士军刀按需常驻，
    所以保活能力不同"。**这是错的**——只要有一个功能开着，`anyFeatureEnabled` 就为真，
    `KeepAliveService` 就是 7×24 常驻，`START_STICKY`、Doze 白名单、无障碍看门狗、开机重绑
    与 ESR 逐项等同。ESR 本来就是"一个框架两个功能"，瑞士军刀是同一个框架四个功能，
    **框架层同源，不允许拿"框架不同"当解释**。同理"新包的系统默认设置不同"这类猜测，
    一旦用户已经查过（如通知振动是开的），要立刻放弃，不要换个说法继续坚持。
19. **三条协作约定（2026-11-09 用户定，硬性）**：
    1. **"推包"≠"放资料库"**（2026-11-09 纠正，同日进一步澄清）。
       - **手机已连接 ⇒ 改完代码就自动 `bash tools/deploy.sh` 装机**，不用问、不用等"推包"两个字，
         目的是让用户随时能拿到新版测试；
       - **放资料库 / 给我下载** = 上传到资料库，**只有用户明确这么说才做**；
       - **手机没连接时**：只报"已改完 + BUILD_OK"，既不推也不传；要打包上传必须用户明确要求。
    2. **需要靠截图确认的事，先用选择题问用户**（"是 A 还是 B"），别自己截图读图——
       截图+读图是本机最贵的算力之一，而用户就在设备前。问不清再截图，且要带着"确认什么"去看。
    3. **不要质疑用户的测试结论**。他说"没响 / 没震 / 没弹"，就是事实起点；
       不许猜"是不是没看清 / 漏看了 / 太轻了"。查不到原因就写"暂未定位"，
       并给出下次能拿到证据的最小手段（日志字段 / 一条 dumpsys）。

---

## 四、代码地图

```
app/src/main/java/com/swissknife/
├─ MainActivity.kt                主界面：授权状态、功能开关（回读 Prefs）、快捷方式注册、通知权限
├─ ShortcutToggleActivity.kt      透明 Activity，快捷方式的落点（切换开关 + 同步保活后 finish）
├─ core/
│  ├─ BaseActivity.kt             UI 基类：page/card/row/text/actionButton + featurePage 骨架（含 input 调参槽）
│  ├─ Prefs.kt                    SharedPreferences 封装 + anyFeatureEnabled 汇聚判据 + 4G/护眼/亮度参数
│  └─ ShizukuShell.kt             Shizuku 授权与 shell 命令（单线程串行）
├─ access/
│  └─ ForegroundWatcherService.kt 无障碍服务：悬浮球 + 休息浮窗（showBreak/onKeyEvent/来电撤窗）+ 保活看门狗
├─ inject/
│  └─ ScrollInjector.kt           通过 Shizuku 注入滑动
├─ net/
│  └─ Saver4G.kt                  「极致省电」执行体：省电广播、isub 掩码读写、对账与强制还原
├─ esr/                           ← 2026-11-07 从 EyeStrainRelief 移植（两个平行功能）
│  ├─ EyeBreak.kt                 「护眼休息」执行体：屏幕广播/注视采样/到期定时器/屏幕常亮锁
│  ├─ BrightGuard.kt              「最低亮度守护」执行体：亮度 ContentObserver + 双通道写入
│  ├─ BreakOverlay.kt             全屏休息浮窗（视觉与独立版一致，勿改字号/配色）
│  ├─ SessionTimer.kt             亮屏/注视计时状态机（区间结算 + 惰性求值，零轮询）
│  ├─ Timebase.kt                 elapsedRealtime 统一时间基准
│  ├─ AttentionProbe.kt           小米 AON 注视检测（cmd attention）
│  ├─ MediaPauseHelper.kt         浮窗期间暂停/恢复媒体（无会话不恢复）
│  └─ BreakNotifier.kt            休息结束的铃声 + 两下短震（不产生通知）
├─ keepalive/
│  ├─ KeepAliveService.kt         框架级保活：前台服务 + START_STICKY + Doze 白名单 + 三个功能钩子
│  └─ BootReceiver.kt             开机 / 覆盖安装后重新拉起常驻服务
└─ ui/
   ├─ ForceScrollActivity.kt      功能页：运行状态 + 今日计数 + 使用说明（无开关，开关在主界面）
   ├─ Saver4GActivity.kt          功能页：策略值/实测值 + 依赖 + 使用说明
   ├─ EyeBreakActivity.kt         功能页：计时状态 + 依赖 + 使用说明 + 调参（阈值分钟）
   └─ BrightGuardActivity.kt      功能页：亮度状态 + 写入通道 + 依赖 + 使用说明 + 调参（下限）

app/src/main/assets/inject.dex    注入器（源码在 tools/inject-src/，不进仓库的那一半）
app/src/main/res/drawable/ic_stat_swissknife.xml  通知小图标（纯透明底，不能带满幅底矩形）
app/src/main/res/xml/accessibility_config.xml     只订阅 typeWindowStateChanged + flagRequestFilterKeyEvents
```

### 移植对照表（ESR → SwissKnife，2026-11-08 建立）

**用途：这两个功能报任何问题，先查这张表判断"这块代码是不是原样"，再往下查。**

| 文件 | 与原版关系 | 已知的有意差异 |
|---|---|---|
| `esr/SessionTimer.kt` | **逐字节相同**（只改包名） | 无 |
| `esr/Timebase.kt` | 逐字节相同 | 无 |
| `esr/BreakOverlay.kt` | 逐字节相同 | 多一行 Log；多一条 `backstopRunnable` 到期兜底（防 `postDelayed` 丢消息吞掉收尾） |
| `esr/BreakNotifier.kt` | 逻辑相同 | 震动改走 `USAGE_ALARM` 三通道（原版 `vibrate(effect)` 属性为 `USAGE_UNKNOWN`，会被系统静默丢弃）；加 `watch.log` 记录 |
| `esr/MediaPauseHelper.kt` | 逐字节相同 | 只换 ShizukuShell 的 import |
| `esr/AttentionProbe.kt` | 逐字节相同 | 只换 import 与 TAG |
| `esr/BrightGuard.kt` | 由 `class` 改 `object`（生命周期跟常驻服务） | 观察 `screen_brightness_float`；写入节流 700ms；新增自动亮度识别与一键切手动 |
| `esr/EyeBreak.kt` | 由 `MonitorService`（独立前台服务）**拆成挂载在 KeepAliveService 的业务体** | 屏幕状态对账；熄屏不弹窗不持锁；到期前校验"该不该弹"；参数每次心跳从 Prefs 对齐 |
| `access/ForegroundWatcherService.kt` 的浮窗区块 | 由 `GuardAccessibilityService` 并入 | 同逻辑（showBreak / onKeyEvent / 来电撤窗 / 置顶），多日志 |
| `ui/EyeBreakActivity` / `BrightGuardActivity` | **重写**（原版是单一主界面） | 用 `featurePage` 骨架 + 调参槽 |
| 原版 `MonitorService` 的通知、`BootReceiver`、`USE_EXACT_ALARM` | **未移植** | 保活由框架层承担；通知并入 KeepAliveService 一条 |

**这张表的用法**：报障时先看对应文件是"逐字节相同"还是"重构过"。
- 逐字节相同 → 问题**几乎不可能**在该文件里，直接查运行环境（见铁律 18）。
- 重构过 → 按铁律 16 的方法把原版对应方法抄来对比。

---

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

2026-11-07 起有三个钩子，顺序与 attach 相反地摘除：

| 钩子 | 挂的是什么 | 开关关着时的开销 |
|---|---|---|
| `Saver4G.attach` | 省电模式广播 | 零（只在广播回调里判断开关） |
| `EyeBreak.attach` | 屏幕亮灭广播 + 注视采样 + 到期定时器 | 零（attach 里先看开关，关着直接返回） |
| `BrightGuard.attach` | 亮度 ContentObserver | 零（同上） |

> **`EyeBreak` 还有一个入口叫 `onServiceTick()`**：由 `KeepAliveService.onStartCommand` 每次被
> 看门狗拉起时调用，用来重挂到期定时器（本机长时间 `postDelayed` 会被丢弃，见铁律第 3/11 条）。
> 它不是"业务逻辑塞进保活服务"，只是让保活服务敲一下门——业务仍然全在 `EyeBreak` 里。

**看门狗的判据是 `prefs.anyFeatureEnabled`**（2026-11-06 修正）。此前写的是
`prefs.forceScrollEnabled`，只开「极致省电」时球不存在、条件永不成立，常驻服务不会被补拉，
进程被冻结后就再也收不到省电广播，而且**没有任何报错**。新增功能时若改了判据，务必回读这一行。

**通知文案：摘要 + 异常优先（2026-11-09 改版，旧写法已作废）**

旧写法是"每个功能各说一句、用 ` · ` 拼起来"，四个全开时 59 个字符，而折叠态一行只放得下
约 22 个汉字 —— 后半截直接被截掉；而且护眼那段带「已用 N 分钟」，数字自己走 ⇒ **每分钟一次 notify**。

现在的规则（`KeepAliveService.notificationText()`）：

1. **原则上不出现会自己变的数字**。唯一例外是「护眼 N 分钟」——用户明确要求看到进度与
   「未在看」状态（2026-11-09），代价是**跨整分钟重发一次通知**（无声、最低优先级）。
   这是用户权衡后的取舍；要降频只需把该段粒度改粗（如 5 分钟一跳）。
2. 正常态先报总数：「**N 项运行中**」，计数口径是**真正生效**的功能数（开关开着且条件齐全）。
3. 紧接着是**护眼段**：`连续用眼 N 分钟` / `连续用眼 N 分钟（未注视）` / `连续用眼 N 分钟（熄灭）` /
   `护眼 休息中`。**异常不在这里重复报**，由下一条统一出一条。
4. **只有需要用户动手的事才占一段，且最多一条**：无障碍未连接 → 亮度无写入权限 / 未设下限。
   折叠态一行约 22 个汉字，摘要 + 护眼 + 异常已经占满，第二条必然被截断。
5. 「4G 已生效」放最后，被截断时先丢它，让异常项露出来。

产品决策：**通知的职责是"要不要你动手" + "护眼进度"**；其余细节看首页每个功能的状态行。
功能再多也不会溢出，因为正文长度不再随功能数线性增长（只有异常项会，且最多一条）。

⚠️ **V3 / V16 / V17 里关于通知文案的判据已过时**（它们验的是"每个功能用自己的话说"，
新版不再逐功能复述）。再验通知时按上面的新规则判：正常态看到「N 项运行中 · 护眼 N 分钟」即正确。

### 保活能力对照：ESR vs SwissKnife（2026-11-08 逐层核实）

用户问过"瑞士军刀的保活是不是和 ESR 一样、甚至更好"。结论：**有功能开着时逐层等同，
另有加分项；只有一处刻意的减法**。逐条如下（每条都能在代码里指到）：

| 层 | ESR（`MonitorService`） | SwissKnife（`KeepAliveService`） | 判定 |
|---|---|---|---|
| 1 前台服务 + START_STICKY | onCreate 里 `startForeground`，IMPORTANCE_MIN 渠道 | 完全相同 | **等同** |
| 2 无障碍看门狗（30 秒节流） | `GuardAccessibilityService.checkMonitorAlive()`，**无条件** `MonitorService.start()` | `ForegroundWatcherService.checkKeepAlive()`，判据 `prefs.anyFeatureEnabled` 后 `ensureRunning()` | **有功能开着时等同**；全关时故意不拉（见下） |
| 3 `stopWithTask="false"` | 有 | 有 | **等同** |
| 4 Doze 白名单（Shizuku） | MonitorService.onCreate 里 `cmd deviceidle whitelist +pkg` | KeepAliveService.onCreate 里同一条命令 | **等同** |
| 5 开机 / 覆盖安装自愈 | BootReceiver（BOOT_COMPLETED + MY_PACKAGE_REPLACED + LOCKED_BOOT_COMPLETED）+ 无障碍重绑 | BootReceiver（前两条）+ 无障碍重绑 | **等同**——ESR 那条 `LOCKED_BOOT_COMPLETED` 其实**收不到**：全工程没有 `android:directBootAware="true"`，解锁前的广播不会送达，属无效声明 |
| 附加 A 应用侧主动拉起 | MainActivity.onCreate → `MonitorService.start` | MainActivity.onResume → `sync`；**另加**开关变化的 `prefListener` 也 sync | SwissKnife **略强** |
| 附加 B 电池优化状态可见 | 无（只能靠 Shizuku 或翻系统设置） | 首页「电池优化」行 + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 一键入口 | SwissKnife **更强** |
| 附加 C 计时到期表兜底 | 无（只靠 `postDelayed`） | `onStartCommand → EyeBreak.onServiceTick()` 每次心跳重挂 | SwissKnife **更强**（对"到点不弹"相关） |

**唯一的减法（刻意，不影响"有功能开着"的场景）**：全部功能关闭时 `KeepAliveService` 会 `stopSelf()`，
不留空转的前台服务和通知；ESR 是永远常驻。这是产品决策（见七章），
只有在"用户以为功能开着、实际开关是关的"这种自相矛盾状态才会显出差异，
而首页开关每次 `onResume` 都回读 Prefs，这种矛盾不会出现。

**理论上的唯一弱项**：第 2 层的看门狗要先读一次 `prefs.anyFeatureEnabled` 才决定拉不拉，
ESR 是无条件拉。若某天出现"开关文件读不到/被清"的极端情况，看门狗会比 ESR 少拉一次——
概率极低，且 `MainActivity.onResume`、开关回调、无障碍重绑三条路都会补。

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
── 调参 ──                input(hint, initial, saveLabel, onSave)   可选：数字输入框 + 保存按钮
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
| 9 | 需要调参时用 `input(...)` 槽（它自动加「调参」标题），**不要自己 new EditText 塞进 root** | 每个页面的输入框位置与样式各不同，用户找不到在哪改 |
| 10 | 调参只改**参数**，不是开关；开关永远只在首页 | 页面里能启停 = 两处状态必然对不上 |

现有落地：`ui/ForceScrollActivity.kt`、`ui/Saver4GActivity.kt`、`ui/EyeBreakActivity.kt`、
`ui/BrightGuardActivity.kt` 四页都按此标准，新增功能照抄其中结构最接近的一个即可。
（前两页是"只读"范例，后两页额外带 `input` 调参槽。）

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
| 5 | `keepalive/KeepAliveService.kt` | **只有需要在进程里长期监听系统事件时才改**：在 `onCreate` 加一行 `Xxx.attach(this)`、`onDestroy` 加 `Xxx.detach()`（见 `Saver4G` / `EyeBreak` / `BrightGuard`）。业务逻辑写在功能自己的类里，这里只挂钩子 |

**2026-11-07 那两个功能就是这套表的完整实例，照着判断要碰几处**：

| 功能 | 碰了哪几行 | 为什么 |
|---|---|---|
| 护眼休息 | 1 / 2 / 3 / 4 / 5 | 要计时（5）、要弹无障碍浮窗和拦按键（4）、要参数（1）、要页面与开关（2、3） |
| 最低亮度守护 | 1 / 2 / 3 / 5 | 完全没有 4：它不需要无障碍，这也正是它必须是**独立开关**的理由 |

**"不需要哪一行"比"需要哪一行"更重要**：为了让一个功能少碰一行而去共享别人的依赖
（比如把亮度守护并进护眼功能），结果就是用户为了调个亮度被迫开无障碍。

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
ADB="D:/DevKit/Android SDK/platform-tools/adb.exe"
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
| 通知文案 | `3 项功能运行中 · 4G 生效中（省电模式）` | 摘要式，2026-11-09 改版后 |

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
bash tools/test.sh       # 只跑 JVM 单测（SessionTimer 13 例），TEST_OK / TEST_FAILED
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
ADB="D:/DevKit/Android SDK/platform-tools/adb.exe"

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

### 「护眼休息 / 最低亮度守护」怎么验证（2026-11-07 新增，全部用权威值）

```bash
export MSYS_NO_PATHCONV=1
ADB="D:/DevKit/Android SDK/platform-tools/adb.exe"

# ① 浮窗真的画出来了吗（先截图，再读 frame——日志只能证明窗口对象存在）
bash tools/shot.sh break-overlay
"$ADB" shell "dumpsys window windows" | grep -A14 "ACCESSIBILITY_OVERLAY" | grep -E "mAttrs|frame=|ty="

# ② 按键过滤到底生没生效（新加的 flag 只有这一条能证明）
"$ADB" shell "dumpsys accessibility" | grep -A2 "Bound services"
#   期望 capabilities 含 FILTER_KEY_EVENTS，eventTypes 仍只有 TYPE_WINDOW_STATE_CHANGED

# ③ 媒体暂停/恢复（点浮窗上的「暂停活动」后读）
"$ADB" shell "dumpsys media_session" | grep -E "package=|state=PlaybackState"

# ④ 注视检测返回码（1=在看，3=没在看，其它=取不到）
"$ADB" shell "cmd attention getLastTestCallbackCode"

# ⑤ 亮度纠偏（唯一权威值，不要看页面上的数字）
"$ADB" shell "settings get system screen_brightness"

# ⑥ 两条计时链在不在（到期定时器靠看门狗重挂，日志里应反复出现）
"$ADB" shell "run-as com.swissknife cat files/watch.log" | tail -20
```

**把阈值调小再测**：护眼页调参槽填 `1`（分钟）保存，等一分钟就能看到浮窗，不必等 20 分钟；
测完记得改回 20。**验证完不要忘了把输入框里的值改回去**——那是用户设置，会落盘。

---

## 六、环境

| 用途 | 路径 |
|---|---|
| JDK 17 | `D:\DevKit\jdk-17` |
| SDK | `D:\DevKit\Android SDK`（**含空格**，cmd 里必须加引号） |
| Gradle | wrapper 8.9（腾讯云镜像，已缓存） |
| adb | `D:\DevKit\Android SDK\platform-tools\adb.exe` |
| 真机 | 25098PN5AC（Android 17 / 澎湃OS / 1220×2656 / 520dpi） |

截图必须 `screencap` 到 `/data/local/tmp` 再 pull（`exec-out` 会污染 PNG）。
Git Bash 涉远程路径前 `export MSYS_NO_PATHCONV=1`。
构建需要 `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT`（系统里没持久化，`tools/build.sh` 里已内置）。

**三个容易忘的环境事实**：

1. **`GRADLE_USER_HOME` 必须显式指到 `D:\WorkBuddy\.gradle`**（2026-11-10 工具链迁到 `D:\DevKit` 后的新约定）。
   用户级 `gradle.properties` 要放在那里，放 `C:\Users\User\.gradle` 不生效（实测：放错位置时 clean 后构建仍失败）。
   旧工具链曾把它指到 `D:\DeepSeek Harness\.gradle`——那是 DSH 的目录，不要再用也不要动。
2. **aapt2 覆盖项在本机用户级配置里，不在仓库里**：
   `D:\WorkBuddy\.gradle\gradle.properties` →
   `android.aapt2FromMavenOverride=D:/DevKit/Android SDK/build-tools/35.0.0/aapt2.exe`
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
| **ESR 拆成两个平行功能，不合并成一个「护眼」**（2026-11-07 用户定） | 定时浮窗与最低亮度守护本来就互不依赖；合起来会让"只想要亮度守卫"的人被迫开无障碍。两个开关各自可关，也是嵌入后相对独立版的新增能力 |
| **护眼开关关闭 = 计时清零重计**（2026-11-07 用户拍板） | 与「熄屏超过 20 秒清零」同一语义：重新打开就是一段新的使用时间，不会一开就被弹窗 |
| **亮度守护关闭后亮度停在当前值，不还原** | 它只纠正向下漂移、从不记账，没有"改之前的基线"可还原；硬造一个基线反而会在用户自己调亮之后把它按回去 |
| **休息浮窗期间悬浮球被盖住，不处理**（2026-11-07 用户拍板） | 浮窗只有 20 秒，且期间按不了键；为这 20 秒加"临时藏球/恢复球"的逻辑，多一条状态分支、多一处可能出错的地方，不划算 |
| **加 `WRITE_SETTINGS`**（2026-11-07 用户拍板） | 亮度直写无 IPC 延迟，拖滑块时纠偏不卡；没授权仍有 Shizuku 兜底，所以它是"增强"不是"门禁" |
| **照搬 `READ_PHONE_STATE`**（2026-11-07 用户拍板） | 来电撤窗的第一条路径；代码里本就带窗口名检测的降级路径，权限被拒也不影响功能可用 |
| **护眼的开关门禁只要求无障碍** | Shizuku（注视检测/媒体暂停）与电话状态都有降级路径。把它们写进 `requiredAuth` 等于拦住一个"其实能跑"的开关，用户还不知道为什么 |
| **调参照搬独立版，但放在二级页的 `input` 槽** | 参数属于功能设置，不是启停；开关仍然只在首页。槽位化保证四个页面的输入框位置一致 |
| **首页功能顺序：护眼休息 → 最低亮度守护 → 强制滑动 → 极致省电**（2026-11-07 用户定） | 按"每天最先想开哪个"排，不按开发顺序。**以后加新功能默认往后放**；要改顺序先问用户，不要因为"新功能更相关"就插到前面 |

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

> 在 `D:\WorkBuddy\SwissKnife`（安卓工具集，v0.3，已有「强制滑动」「极致省电」）里新增一个功能：**【填功能名与一句话需求】**。
>
> **如果我另发了「先读交接文档」六个字，就按〇·一的三段式流程走（对账 → 干活 → 收尾批量验证）；
> 没发就正常做。** 下面三处无论哪种情况都要读：
> 1. `D:\WorkBuddy\SwissKnife\HANDOFF.md` —— 唯一入口。重点看「四、代码地图」里的
>    **「新增一个功能要改哪几处」** 表、**「功能二级页统一标准」**、「悬浮球的位置记忆」、「三、铁律」。
> 2. `D:\WorkBuddy\.workbuddy\memory\ANDROID-DEV-PLAYBOOK.md` 的 §4.14（悬浮球与无障碍，12 小节）、
>    §4.9（保活）、§4.15（状态记忆类功能的验证清单）、§4.16（改移动网络制式的坑，动电话功能必读）。
> 3. `docs/archive/README.md` —— 已判死的路线，别重试。
>
> 硬约束（违反会返工）：
> - 部署一律 `bash tools/deploy.sh`，**不要** `adb install`（漏掉无障碍重绑 ⇒ "改了没生效"）
> - 保活是框架层的：新功能只需把开关并入 `Prefs.anyFeatureEnabled`，**不要**再写第二个前台服务
> - 功能二级页**必须**用 `BaseActivity.featurePage(...)` 骨架，值只走 `setStatus(...)`，不要自己拼 View
> - 不要加"自动判断前台应用"的逻辑，五条路已实测失败
> - 不要在 `onAccessibilityEvent()` 里塞业务逻辑；要新事件类型就先问，因为多订阅一类 = 每次点击白唤醒一次
> - 权限只加用得到的，加了就在首页给状态行 + 授权入口
> - 悬浮球窗口坐标必须带 `FLAG_LAYOUT_IN_SCREEN`，恢复位置必须 clamp 进当前屏幕
>
> 交付要求：
> - 先给「我理解的需求 / 预期效果 / 不做的部分 / 验收标准」，确认后再写代码
> - 真机验证：截图确认 + `dumpsys` 取权威值，不要只信日志（日志说"已显示"不等于肉眼看得见）
> - 收尾：改 `README` / `CHANGELOG`（双语、英文在前）、必要时更新 `HANDOFF`，
>   **没真机验证的改动要写进「二·二」清单**，再 commit
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
