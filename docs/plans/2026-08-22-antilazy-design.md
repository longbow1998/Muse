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

### 2026-08-22 修复与增强（v1.2）

真机反馈：后台不倒计时；提醒只在通知栏看不到；希望锁屏超 1 分钟重置进度。

1. **后台停摆**：纯 Handler 计时依赖进程存活，ROM 杀后台即全停。
   改为双保险：Handler 秒级 tick + `TickReceiver` 兜底闹钟（setAndAllowWhileIdle、
   非唤醒 ELAPSED_REALTIME，30 秒周期自续）。闹钟按墙钟差补算各启用规则进度
   （单次上限 5 分钟），与秒级 tick 写同一 KEY_LAST_TICK_WALL 天然去重；
   进程已死则直接拉起服务。锁屏中闹钟自动跳过（交由锁屏重置规则）。
2. **提醒可见性**：通知升级为 fullScreenIntent——到点整屏弹出 ReminderActivity
   （半透明遮罩+大字卡片，20 秒自动关闭或点「知道了」），盖在任意应用之上，
   声音/振动照常；API34+ 检测 canUseFullScreenIntent()，被关时状态区提示退回横幅。
   新增 USE_FULL_SCREEN_INTENT 权限。
3. **锁屏超 1 分钟重置**：LOCK_RESET_MS=60s 统一三处语义——暂停时记录 KEY_LOCKED_AT，
   解锁时超阈值全部清零；进程恢复 restore 的会话判定同步改为该阈值（原 2 分钟）。

### 2026-08-22 修复（v1.3）：后台 7 秒被杀后彻底停摆

真机反馈：切后台约 7 秒服务即被 ROM 杀死，倒计时永久停止。

根因：
1. ROM 对无后台权限应用激进杀进程（需用户授权，见保活指南）；
2. 兜底闹钟复活路径失效——Android 12+ 禁止从后台启动前台服务，
   而我们用的 setAndAllowWhileIdle 非精确闹钟不在豁免清单内，
   startForegroundService 抛 ForegroundServiceStartNotAllowedException
   → 服务死了永远起不来。

重构：计时核心抽为 ReminderEngine + Notifier，发提醒与推进进度
完全脱离 Service 实例：

- 墙钟增量单一消费点 consumeDelta：秒级 tick 与 30 秒兜底闹钟共用同一
  KEY_LAST_TICK_WALL，谁先跑谁消费，天然去重；服务存活时秒级消费、
  进程死亡时由闹钟一次性补算（上限 5 分钟）；
- TickReceiver.onAlarm 直接读写落盘规则/进度并经 Notifier 弹全屏提醒，
  全程无需拉起服务；监控停用后闹钟链自动终止；
- 锁定状态机 applyLockTransition 落盘跨进程生死跟踪，锁屏重置在任意路径生效；
  锁定切换处重新锚定墙钟，防止锁屏时长被误计为使用量；
- UI 快照 snapshot(context) 服务死后自动回退读落盘进度，倒计时仍可见；
- 新增「后台保活指南」按钮：电池优化白名单 / 自启动 / 省电无限制 /
  最近任务加锁 / 全屏通知权限的分机型操作步骤。

### 2026-08-22 复查修复（v1.3.1）

自查发现 5 个问题并修复：

1. **双路径重复计数**（严重）：服务存活时兜底闹钟仍基于滞后 ≤10s 的落盘值
   独立对账，边界情况重复弹同一提醒/进度回退。修复：onAlarm 检测
   MonitorService.isAlive() 直接跳过——秒级 tick 是唯一计数权威，
   闹钟仅在进程死亡时接管。
2. **优雅回收误杀监控**（严重）：onDestroy 无条件清 running 标记，部分 ROM
   划卡/清理走优雅销毁路径 → 闹钟链随之终止。修复：引入 KEY_USER_STOPPED，
   仅用户主动 stop() 才允许清除 running；系统回收保留标记，闹钟继续、
   sticky 尝试复活。
3. **UI 状态误报**：进程死后 companion isRunning=false，界面显示"已停止"
   但闹钟仍在计时。修复：以落盘 running 标记为准，新增
   "服务被系统回收，兜底闹钟计时中"状态行；onResume 开关同步改读标记。
4. **全屏提醒页主题**：Dialog 浮动窗 + match_parent 会退化成小卡片；
   改 windowIsTranslucent 全屏浮层。另加 taskAffinity="" 独立任务栈，
   关闭提醒后回到用户之前的应用（如抖音）而非本 App。
5. **锁屏时长误计**：解锁转换时（无论是否触发重置）统一重锚墙钟，
   锁屏期间一律不计入使用量；重置时清零包括停用规则在内的全部进度，
   与服务路径一致。

### 2026-08-22 深度检索修复（v1.3.2）

第二轮自查发现并修复 6 项：

1. **闹钟路径停用规则也累计进度**（bug）：与服务路径行为不一致，
   重新启用后可能瞬间误触发。修复：停用规则保持原进度不增加。
2. **设备休眠期闹钟静默被误计**（bug）：非唤醒闹钟在灭屏休眠时顺延，
   唤醒后首闹钟的 delta 可能包含整段锁屏时间。修复：delta > 90s
   （正常路径恒 ≤35s）视为休眠间隔——按锁屏重置处理并重锚墙钟，
   不计入使用量。
3. **通知卡片无限堆叠**：每次提醒递增 ID，历史卡片积压。
   修复：全屏页启动时清掉自己对应的通知卡片（降级为横幅时
   本页不启动，卡片保留作为痕迹，两全）。
4. **计时停滞无感知**：强停/深度拦截会同时杀掉服务与闹钟，
   UI 无任何提示。修复：监控开启且解锁状态下墙钟超 5 分钟未走动
   → 状态区警告"计时已停滞，请重新开关监控"。
5. **编辑对话框按钮被软键盘遮挡**：内容包一层 ScrollView。
6. **保活指南补充强停说明**：强制停止连闹钟一起停，需重开 App 恢复。

### 2026-08-22 第三轮深查（v1.3.3）

1. **默认规则不落盘**（严重）：load() 种入默认规则只写 seeded 标记未
   save(rules_json)，第二次 load 返回空——重启后规则区空白、引擎无规则。
   修复：种子规则立即 save。
2. **规则 id 复用串台进度**：删除最大 id 规则后新建会复用 id，残留
   progress 直接转给新规则（新建即误触发）。修复：KEY_NEXT_RULE_ID
   单调递增计数器，永不复用。
3. **服务 tick 缺休眠校正**：进程冻结恢复后 tick 一次性消费大增量导致
   假提醒。修复：抽出 consumeDeltaSanitized（gap>90s 按锁屏重置），
   tick 与闹钟两条路径统一走该入口。
4. **锁屏期重启丢失重置判定**：resetLockTracking 清零 locked_at，
   服务在锁屏中被杀重启后无法测出真实锁屏时长。修复：锁定态初始化
   时 locked_at=now（保守测距，长锁屏重置依然正确）。
5. **FSI 解锁态降级**：Android 10+ 对已解锁屏幕的 REMINDER 类
   fullScreenIntent 只弹横幅不启全屏页；改 CATEGORY_ALARM 提高
   全屏接管概率（闹钟类为白名单待遇）。
6. 体验：全屏页点遮罩任意处可关闭；服务被回收期间若处于锁屏，
   状态区显示"锁屏暂停"而非误导性的"服务被回收"。
