# Archive / 历史存档

Approaches that were implemented, measured on a real device, and then dropped. They are kept because the measurements that killed them are also the justification for the current design — and because a path that does not work is worth more to the next person than a path that merely works.

已实现、在真机上实测过、然后被放弃的方案。保留的原因有两个：杀死它们的实测数据正是当前设计的依据；而一条走不通的路，对后来者的价值往往高于一条能走通的路。

The full ten-part write-up (with `dumpsys` output and log excerpts) lives in the developer's local Android playbook, §4.14. This file only keeps what a reader of the repository needs.

完整的十小节记录（含 `dumpsys` 输出与日志摘录）在开发者的本地安卓手册 §4.14 中，本文件只保留仓库读者需要的那部分。

---

## Automatic show/hide — dropped / 自动显隐 —— 已放弃

**Goal.** Show the ball only while a "watched" app is in the foreground, hide it otherwise.
**目标.** 只在"被守护"的应用位于前台时显示球，其余时候隐藏。

**Why it was dropped.** Every signal that could tell us "which app is in front" turned out to be unreliable on the test device (25098PN5AC / Android 17 / HyperOS OS.4.0). Not "unreliable sometimes" — unreliable in a way that produces a user-visible bug: the ball vanishes while the user is watching a video, and reappears on the launcher.

**为什么放弃.** 所有能回答"哪个应用在前台"的信号，在实测机型上都不可靠。不是"偶尔不准"，而是错到用户能直接看出来：看视频时球消失，回到桌面又冒出来。

| # | Strategy / 路径 | Measured result / 实测结果 |
|---|---|---|
| 1 | `TYPE_WINDOW_STATE_CHANGED` event package name | Fires on cold start, often missing on a warm resume. Whether it arrives depends on the target app's implementation. 冷启动有事件，热启动常常没有；来不来取决于目标应用的实现 |
| 2 | Window list sorted by layer | Reports `com.miui.home` while Douyin is actually on screen. 抖音实际在前台时仍返回桌面 |
| 3 | Window list filtered by `isActive` | Same wrong answer as #2. 与第二条同样错 |
| 4 | `rootInActiveWindow` | Still returns the launcher 1.5 s after switching apps. 切走 1.5 秒后仍返回桌面 |
| 5 | `UsageStats.queryEvents` | Writes are delayed 10–25 s and the call often returns `null`. 写入延迟 10–25 秒，且经常返回 `null` |

Two traps that make this worse / 两个让问题更糟的坑：

- **Different apps behave differently on resume.** Douyin rebuilds its window, so events appear; WeChat resumes with an identical view tree and emits nothing at all. The same gesture on the same device gives opposite results depending on the app — this is what made the bug look like "works sometimes".
  **不同应用恢复时行为不同。** 抖音恢复会重建窗口，因此有事件；微信恢复时视图树完全不变，一个事件都不发。同一台机器同一个操作，换个应用结果相反——这就是"时好时坏"的真正原因。
- **Other accessibility services also post window events.** Four were installed on the test device; their events arrive in the same stream and can knock the ball off.
  **别的无障碍服务也会抛窗口事件。** 实测机上装了 4 个，它们的事件混在同一条流里，会把球顶掉。

**Consequence / 结果.** The ball is now controlled manually: a switch on the home screen, a launcher shortcut, and long-press-to-hide. One ball, always available, no per-app scope.

球改为手动控制：首页开关、桌面快捷方式、长按收起。一个球全局可用，不再做应用范围限定。

---

## Per-app whitelist — deleted / 应用白名单 —— 已删除

The whitelist only existed to bound *where* the ball could appear, which only mattered while automatic show/hide was being attempted. With the ball always available it had no remaining purpose, so `Prefs.whitelist` and the app picker were removed.

白名单的唯一作用是限定球"能出现在哪些应用"，而这只在自动显隐方案下才有意义。球改成全局可用后它就没有用途了，于是删掉了 `Prefs.whitelist` 与应用选择页。

The `QUERY_ALL_PACKAGES` and `PACKAGE_USAGE_STATS` declarations are leftovers from that period and are still in the manifest, unused.

`QUERY_ALL_PACKAGES` 与 `PACKAGE_USAGE_STATS` 是那个时期留下的声明，仍在 manifest 里但未使用。

---

## Tap feedback by flashing the background — reverted / 点击时闪背景色 —— 已回退

Tapping the ball used to flash its background colour as confirmation. The ball uses a rounded background drawable, and replacing the background with a solid colour discarded the corner radius, so a tap produced **a square flash** — visually worse than no feedback at all.

最初点击球会闪一下背景色表示"已触发"。球用的是圆角背景 drawable，换成纯色背景会把圆角一起覆盖掉，结果是**闪一个方块**，观感比没有反馈更差。

Now only the text colour changes, to orange for 400 ms, and only when injection fails. A successful tap gives no visual feedback at all.

现在只改文字颜色：注入失败时变橙 400 ms。成功时没有任何视觉反馈。
