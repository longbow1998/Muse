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

## 变更记录

### 2026-08-22 真机反馈修复

真机反馈三个问题，根因与修复：

1. **开关点击闪退（偶发）**：UI 每秒用服务真实状态回写 Switch，用户刚打开就被程序
   弹回，监听器连带把服务停掉；快速启停竞态下服务销毁时还没调 `startForeground()`，
   违反前台服务契约抛 RemoteServiceException。
   修复：onStartCommand 第一行即 startForeground；UI 引入 desiredRunning 目标态 +
   去抖，程序化 setChecked 不再触发重复启停。
2. **要点好多次才切换**：同上根因。修复：状态刷新只更新文本不回写开关；
   onResume 才同步一次开关位置。
3. **通知栏倒计时不实时**：原每 30 秒刷新一次前台通知，改为每秒刷新
   （仅解锁计时中；IMPORTANCE_MIN + setOnlyAlertOnce 静默更新）。

另加自愈逻辑：desiredRunning=true 但服务 3 秒宽限期后仍未运行时自动补启动，
顺带覆盖"服务被系统杀死后自动拉起"。

### 2026-08-22 真机反馈修复 2：到点不提醒

反馈：停在 App 内满 5 分钟无任何提醒。

根因分析：计时值只存内存（companion var），进程被 ROM 电池管理硬杀后 START_STICKY
重启时 activeMs 归零，倒计时悄悄从头再来——用户感知为"时间到了却没提醒"。
次要风险：通知权限被拒时提醒静默丢失，UI 无任何提示，难以排查。

修复与增强：

1. 计时进度落盘（每 10 秒 + 锁屏暂停时 + onDestroy）；进程重启时若距上次落盘
   < 2 分钟且 running 标记仍在（说明是硬杀），接着上次进度继续计，否则视为新会话清零；
2. 提醒留痕：lastReminderAt / reminderCount 持久化并在状态区展示
   "上次提醒 HH:mm:ss · 已提醒 N 次"，触发与否一目了然；
3. 新增「立即测试一次提醒」按钮，无需等 5 分钟即可验证提醒链路；
4. API 33+ 未授予通知权限时状态区红字警告；
5. 提醒通知补充 CATEGORY_REMINDER 与 setDefaults，提升部分 ROM 的呈现率。

### 2026-08-22 功能：多规则可配置（v1.1）

需求：提醒时间与文本可配置，支持创建多条规则。

设计：

- `Rule(id, intervalMinutes, text, enabled)`；`RuleStore` 用框架自带 org.json
  序列化到 SharedPreferences（定义与各规则进度分开两个 key），零第三方依赖；
  首次运行种入默认规则（5 分钟 / 不要懒惰，想一下现在要做什么）。
- 引擎：tick 每秒遍历启用规则独立累计、到点各自触发归零；前台通知显示
  "已启用 N 条 · 最近一条 X 后提醒"。规则热更新：`MonitorService.setRules()`
  持久化 + 运行中按 id 保留已计进度；间隔改小导致进度超过新间隔时下一秒立即触发。
- UI：规则卡片列表（程序化构建，结构签名变化才重建行，倒计时每秒轻量刷新），
  行内 Switch 停用/启用，点行弹出编辑对话框（多行内容 + 分钟校验 1–720 + 删除）。
- 服务停止时规则编辑照常持久化，服务启动时从存储恢复。
