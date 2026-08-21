# AntiLazy 反懒惰提醒 — 设计文档

日期：2026-08-22

## 目标

手机在非锁定（用户正在使用）状态下后台自动计时，每累计 5 分钟以「通知 + 提示音 + 振动」
提醒一次："不要懒惰，想一下现在要做什么"。锁屏/灭屏期间不计时不提醒。

## 方案选型

- **前台服务 + 动态广播接收器 + Handler 秒级 tick**（选定）：
  - 计时只在亮屏解锁时进行，此时 Doze 不生效，Handler 定时足够可靠；
  - 无需 AlarmManager / WorkManager 的复杂调度与权限适配；
  - 前台服务保证进程优先级，通知栏常驻可见进度。
- 备选被否：AlarmManager setExactAndAllowWhileIdle（锁屏也要触发才需要，本需求锁屏暂停，
  且国产 ROM 对精确闹钟限制多）；WorkManager 周期任务最小间隔 15 分钟，不满足 5 分钟。

## 锁定判定

`PowerManager.isInteractive && !KeyguardManager.isKeyguardLocked`

监听三个受保护广播动态刷新：ACTION_SCREEN_ON / ACTION_SCREEN_OFF / ACTION_USER_PRESENT。
（SCREEN_ON/OFF 无法静态注册，必须运行时 registerReceiver。）

## 组件

- `MonitorService`（foregroundServiceType=specialUse）：核心计时、双通知渠道
  （monitor=IMPORTANCE_MIN 常驻进度；reminder=IMPORTANCE_HIGH，自定义振动 pattern）。
  START_STICKY + SharedPreferences 记录运行态供重启恢复。
- `MainActivity`：开关控制启停、实时状态（已专注 x / 距下次提醒 y）、通知权限请求
  （API 33+）、忽略电池优化引导。
- `BootReceiver`：BOOT_COMPLETED 后按上次运行态自动恢复。

## 关键决策

- 锁屏暂停 = 冻结累计值，解锁继续（忠实于"锁屏不计时"的语义），而非每次解锁重置。
- 提醒后计数归零，循环进行。
- 零第三方依赖（无 AndroidX/Compose），Kotlin + 纯框架 API，minSdk 26 / targetSdk 34。

## 验证

- `./gradlew assembleDebug` 通过，产物 app-debug.apk（793KB）；
- aapt badging 校验包名、权限、label 正确。
