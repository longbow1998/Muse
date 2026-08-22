# AntiLazy 反懒惰提醒

手机在**解锁使用中**时后台自动计时，每累计 **5 分钟**弹出一次「通知 + 提示音 + 振动」：

> 该停下来想一想了 —— 不要懒惰，想一下现在要做什么

锁屏或灭屏时计时自动暂停，解锁后继续（累计的是真实使用时长）。

## 安装使用

APK 已构建好：`app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

或把 APK 传到手机上直接安装。

首次打开：

1. 允许通知权限（Android 13+ 会自动弹窗）
2. 打开「开始 / 停止监控」开关
3. 建议点「申请忽略电池优化」，防止后台被杀

状态栏会常驻一条低调的进度通知，显示已专注时长和距下次提醒的时间；锁屏时显示"计时已暂停"。

## 特性

- 仅在屏幕亮 + 已解锁时计时，锁屏/灭屏冻结累计值
- **多条提醒规则**：每条规则可自定义提醒文本与触发间隔（1–720 分钟），独立计时、到点各自触发；行内开关可临时停用某条
- 提醒通知（IMPORTANCE_HIGH 渠道，带声音和自定义振动节奏），触发后该规则重新计数
- 计时进度落盘：进程被杀重启后 2 分钟内接着上次进度继续计，不悄悄归零
- 重启手机后自动恢复监控（若之前处于开启状态）
- 零第三方依赖：Kotlin + 纯 Android 框架 API（含 org.json 序列化规则），minSdk 26 / targetSdk 34

## 构建

```bash
./gradlew assembleDebug        # 调试 APK
./gradlew assembleRelease      # 未签名 release
```

需要 JDK 17 和 Android SDK（`local.properties` 里已指向本机 SDK）。

## 核心代码导读

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/learn/antilazy/MonitorService.kt` | 前台服务：锁定判定、多规则秒级计时、发提醒 |
| `app/src/main/java/com/learn/antilazy/RuleStore.kt` | 规则数据模型 + SharedPreferences(JSON) 持久化 |
| `app/src/main/java/com/learn/antilazy/MainActivity.kt` | 监控开关、规则列表增删改、权限请求、实时状态 |
| `app/src/main/java/com/learn/antilazy/BootReceiver.kt` | 开机恢复监控 |
| `docs/plans/2026-08-22-antilazy-design.md` | 设计决策记录 |

## 注意事项（国产 ROM）

小米/华为/OPPO 等系统会激进杀后台。点 App 内的「**后台保活指南**」按步骤授权：
电池优化白名单、自启动、省电无限制、最近任务加锁。

即使进程被杀，兜底闹钟也会按时弹全屏提醒（每 30 秒对账一次）；
通知栏实时倒计时的连续刷新需要服务存活。

## 调试技巧

- `adb logcat -s MonitorService` 可看锁定状态切换、进度恢复、提醒触发日志
