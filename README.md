# AntiLazy 防沉迷提醒

一款开源 Android 防沉迷应用：在**解锁使用手机**时按你配置的规则计时，到点弹出「跨应用悬浮提醒 + 通知 + 提示音 + 振动」，帮你从短视频和信息流里停下来。

> 默认提醒文案：**该停下来想一想了**

- 锁屏或灭屏时计时自动暂停
- 锁屏 ≤ 1 分钟：解锁后保留进度继续
- 锁屏 > 1 分钟：进度清零，解锁后重新计数
- 内置「今日使用统计」：查看今天每个 App 的前台使用时长（数据仅在本机计算，不上传）

## 安装使用

自行构建 APK：

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 与 Android SDK（compileSdk 34）。

首次打开建议依次完成：

1. 点「允许显示在其他应用上层」，确保在抖音等应用内也能看到强提醒
2. 允许通知权限（Android 13+）
3. 打开「开始 / 停止监控」开关，并新增/修改你的提醒规则
4. 如需查看各 App 使用时长，进入「今日使用统计」授权使用情况访问权限
5. 建议在电池优化设置中将本应用设为不优化（国产 ROM 另见应用内保活指南）

## 特性

- 仅在屏幕亮 + 已解锁时计时；短锁屏暂停后续计，长锁屏（>1 分钟）清零后续计
- **多条提醒规则**：每条规则自定义提醒文本与触发间隔（1–720 分钟），独立计时；行内开关可临时停用
- 提醒优先以 `TYPE_APPLICATION_OVERLAY` 悬浮层盖在任意应用上，同时发送高优先级通知；任一链路成功即算送达
- 投递失败不消费本轮计时，自动重试
- 进度、锁屏状态与系统 `BOOT_COUNT` 原子落盘，正确区分进程恢复与设备重启
- 服务被系统回收时先尽力自愈（watchdog），无法恢复则明确告警，绝不伪造使用进度
- 今日使用统计（UsageStatsManager），纯本机计算
- 运行时零第三方依赖：Kotlin + 纯 Android 框架 API，minSdk 26 / targetSdk 34

## 构建

```bash
./gradlew assembleDebug                              # 调试 APK
./gradlew testDebugUnitTest lintDebug assembleDebug  # 完整验证（测试 + lint + 打包）
```

## 核心代码导读

| 文件 | 职责 |
|---|---|
| `MonitorService.kt` | 前台服务：锁定判定、多规则秒级计时、投递重试 |
| `TimerMath.kt` | 可单测的计时、锁屏边界与开机身份判断 |
| `RuleStore.kt` | 规则与进度的 SharedPreferences(JSON) 持久化 |
| `OverlayReminder.kt` | 跨应用悬浮提醒与多规则排队 |
| `Notifier.kt` | 通知渠道、提醒通知、健康告警、声音振动 |
| `TickReceiver.kt` | 服务健康 watchdog 与尽力恢复 |
| `UsageStatsActivity.kt` | 今日各 App 使用时长统计页 |
| `MainActivity.kt` | 监控开关、规则增删改、权限引导、实时状态 |

## 注意事项（国产 ROM）

小米/华为/OPPO 等系统会激进杀后台。请按 App 内「后台保活指南」逐步授权：
悬浮窗、电池优化白名单、自启动、省电无限制、最近任务加锁。

Android 无法在服务死亡后准确还原期间的锁屏历史，因此服务被回收时计时会暂停并发健康告警，
不会把未知时间伪造为使用时长。重新打开 App 即可恢复。

## License

[MIT](LICENSE)
