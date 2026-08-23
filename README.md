<div align="center">

<img src="docs/assets/muse-icon.png" width="120" alt="Muse icon"/>

# Muse 缪思

**Put the phone down. Start something worth doing.**
**放下手机，开始做值得做的事情。**

A tiny, open-source Android digital-wellbeing app: it quietly counts your unlocked screen time and — after a moment of scrolling — reminds you to pause, think, and get back to real life.

一款开源 Android 防沉迷应用：解锁使用手机片刻后提醒你停下来想一想，放下手机、去做真正值得做的事。

[Download](https://github.com/longbow1998/Muse/releases/latest) · [中文说明](#功能) · [English](#features)

</div>

---

## 安装使用 / Install

**从 [Releases](https://github.com/longbow1998/Muse/releases/latest) 下载最新的 `Muse-vX.Y.Z-release.apk` 直接安装即可**；安装后在应用内点「检查更新」即可升级。所有 Release 使用同一固定签名，可放心覆盖安装（早期 v1.8.0 及之前的临时签名版本需先卸载一次）。

Download the latest APK from [Releases](https://github.com/longbow1998/Muse/releases/latest) and install it. In-app "Check for updates" keeps you current afterwards.

首次打开建议：① 允许通知权限 ② 在「权限状态」面板开启悬浮窗强提醒与使用情况访问 ③ 打开监控开关并配置规则。界面跟随系统语言（中/英），也可在应用内「Language」按钮手动切换。

On first launch: allow notifications, grant the permissions listed in the in-app checklist (overlay + usage access recommended), then toggle monitoring on. The UI follows your system language (Chinese/English) and can be switched from the in-app "Language" button.

## 功能 / Features

### 中文

- **多规则提醒**：自定义提醒文字与间隔（1–720 分钟），独立计时、行内开关
- **强提醒链路**：跨应用悬浮层 + 高优先级通知 + 声音振动；任一通道成功即算送达，失败自动重试且不消耗本轮计时
- **免打扰应用**：导航等所选应用位于前台时暂停计时并撤下提醒；连续超过 1 分钟后清零进度，使用统计仍如实记录
- **前台感知**：提醒自动附上「此刻在用哪个 App、今天已用多久」
- **使用统计**：今日/近7天/近30天柱状图趋势、各 App 占比条、7 天日均对比
- **耗电估算**：mAh 与「占电池容量百分比」双单位（本机估算）
- **权限自检面板**：通知/悬浮窗/使用情况/电池优化逐项状态，点击直达设置
- **在线更新**：应用内检查 GitHub Releases 并安装
- 仅在解锁使用时计时：短锁屏暂停续计，锁屏 >1 分钟清零重计；服务被回收时暂停并告警，绝不伪造进度
- 中英双语可切换；Material 3 设计语言、自动深色模式；仅依赖 Google 官方库，无其他第三方依赖

### English

- **Multiple rules**: custom text & interval per rule (1–720 min), independently timed
- **Strong delivery**: overlay above any app + high-priority notification + sound/vibration; retry with no interval consumed until delivered
- **Do Not Disturb apps**: pause timing and remove reminders while selected apps such as navigation are in the foreground; reset progress after 1 minute while keeping usage statistics intact
- **Foreground awareness**: reminders append which app you're in and today's total
- **Usage stats**: today/7d/30d bar-chart trends, per-app share bars, 7-day average comparison
- **Battery estimate**: mAh + % of battery capacity (local estimation)
- **Permission checklist**: live status with one-tap jumps to system settings
- **In-app updates** via GitHub Releases
- Counts only while unlocked; short locks pause, locks >1 min reset; never fabricates progress after service death
- Chinese/English UI switchable; Material 3 design with automatic dark mode; only official Google libraries, no other third-party deps

## 提醒逻辑 / How reminders work

### 中文

**计时条件**

- 仅在「屏幕亮 + 已解锁」时计时；锁屏、灭屏、亮屏但停留在锁屏界面均暂停
- 多条规则独立计时，互不影响

**锁屏规则**

- 锁屏 ≤ 1 分钟：暂停，解锁后保留进度继续
- 锁屏 > 1 分钟：进度清零，解锁后从零开始
- 系统时间调整、设备重启：通过 `BOOT_COUNT` + 单调时钟识别，不误算进度

**免打扰应用**

- 可将导航、打车等工具应用加入白名单；所选应用位于前台时暂停所有规则计时，且不显示悬浮提醒或高优先级提醒通知
- 连续停留 ≤ 1 分钟：离开后保留进度继续；连续停留 > 1 分钟：清零进度后重新计时，与锁屏规则一致
- 白名单只影响提醒引擎，使用统计仍完整记录这些应用的实际前台时长
- 前台应用识别依赖「使用情况访问」权限；未授权时应用会明确提示白名单尚未生效

**提醒触发与送达**

- 任一启用规则的累计时长达到其设定间隔 → 触发提醒
- 触发后同时尝试两条通道：
  1. **跨应用悬浮层**（`TYPE_APPLICATION_OVERLAY`）：盖在抖音等任意应用上，20 秒自动消失或手动关闭
  2. **高优先级通知**（IMPORTANCE_HIGH + 声音/振动）
- **任一通道成功即算送达**，该规则归零重新计时
- 两通道都失败（如权限被关）：本轮计时不消费，每 30 秒重试直至成功——不会白等一轮
- 提醒正文自动附带「此刻在用哪个 App · 今天已用多久」（需使用情况访问权限）

**服务可靠性**

- 前台服务是唯一计时权威；被系统回收时 watchdog 每 30 秒检测并尽力恢复
- 无法恢复时明确告警提示，**绝不伪造服务死亡期间的计时进度**
- 用户主动停止 / 系统「强制停止」：尊重用户意图，闹钟链终止，重启手机后按上次状态恢复

### English

**Counting**: only while screen-on & keyguard-unlocked; per-rule independent timers.
**Locks**: ≤1 min pause-and-resume; >1 min resets progress. Clock changes/reboots handled via monotonic clock + `BOOT_COUNT`.
**Do Not Disturb apps**: selected foreground apps pause every rule and suppress both overlays and reminder notifications. A visit up to 1 minute preserves progress; a longer visit resets it. Usage statistics remain complete. Usage access is required for foreground detection.
**Delivery**: rule reaches its interval → overlay above any app + high-priority notification, sound/vibration. Either channel counts as delivered and the rule restarts. If both fail, the due interval is retained and retried every 30 s — never silently consumed.
**Context**: reminders append which app you're in and today's total for it (with usage access).
**Reliability**: foreground service is the sole timing authority; a watchdog attempts recovery when the system reclaims it and alerts honestly instead of fabricating progress. User-initiated stops are respected.

## 构建 / Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Requires JDK 17 + Android SDK (compileSdk 34). CI builds every push and attaches APKs to Releases automatically.

## License

[MIT](LICENSE)
