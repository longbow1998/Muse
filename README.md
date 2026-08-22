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

**从 [Releases](https://github.com/longbow1998/Muse/releases/latest) 下载最新的 `Muse-vX.Y.Z-debug.apk` 直接安装即可**；安装后在应用内点「检查更新」即可升级。

Download the latest APK from [Releases](https://github.com/longbow1998/Muse/releases/latest) and install it. In-app "Check for updates" keeps you current afterwards.

首次打开建议：① 允许通知权限 ② 在「权限状态」面板开启悬浮窗强提醒与使用情况访问 ③ 打开监控开关并配置规则。界面跟随系统语言（中/英），也可在应用内「Language」按钮手动切换。

On first launch: allow notifications, grant the permissions listed in the in-app checklist (overlay + usage access recommended), then toggle monitoring on. The UI follows your system language (Chinese/English) and can be switched from the in-app "Language" button.

## 功能 / Features

### 中文

- **多规则提醒**：自定义提醒文字与间隔（1–720 分钟），独立计时、行内开关
- **强提醒链路**：跨应用悬浮层 + 高优先级通知 + 声音振动；任一通道成功即算送达，失败自动重试且不消耗本轮计时
- **前台感知**：提醒自动附上「此刻在用哪个 App、今天已用多久」
- **使用统计**：今日/近7天/近30天柱状图趋势、各 App 占比条、7 天日均对比
- **耗电估算**：mAh 与「占电池容量百分比」双单位（本机估算）
- **权限自检面板**：通知/悬浮窗/使用情况/电池优化逐项状态，点击直达设置
- **在线更新**：应用内检查 GitHub Releases 并安装
- 仅在解锁使用时计时：短锁屏暂停续计，锁屏 >1 分钟清零重计；服务被回收时暂停并告警，绝不伪造进度
- 中英双语可切换；运行时零第三方依赖（Kotlin + 纯框架 API）

### English

- **Multiple rules**: custom text & interval per rule (1–720 min), independently timed
- **Strong delivery**: overlay above any app + high-priority notification + sound/vibration; retry with no interval consumed until delivered
- **Foreground awareness**: reminders append which app you're in and today's total
- **Usage stats**: today/7d/30d bar-chart trends, per-app share bars, 7-day average comparison
- **Battery estimate**: mAh + % of battery capacity (local estimation)
- **Permission checklist**: live status with one-tap jumps to system settings
- **In-app updates** via GitHub Releases
- Counts only while unlocked; short locks pause, locks >1 min reset; never fabricates progress after service death
- Chinese/English UI switchable; zero third-party runtime dependencies

## 构建 / Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Requires JDK 17 + Android SDK (compileSdk 34). CI builds every push and attaches APKs to Releases automatically.

## License

[MIT](LICENSE)
