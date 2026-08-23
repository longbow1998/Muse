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

### 2026-08-22 语义与平台收敛（v1.4.0）

1. 前台服务成为唯一计时权威；watchdog 不再根据稀疏采样伪造锁屏历史，服务死亡期间暂停计时，先尽力恢复，失败则告警。
2. 计时检查点改用 `elapsedRealtime + BOOT_COUNT`，进度、锁定状态和开机身份同代落盘，避免墙钟调整及跨重启误恢复。
3. 锁屏严格按 `> 60 秒` 清零：短锁屏保留后续计，长锁屏清零后续计；未知调度间隙只暂停、不再冒充长锁屏。
4. Android 13/14 解锁态不再依赖 full-screen intent，改用用户授权的 `TYPE_APPLICATION_OVERLAY`；多规则同时到期按队列展示，通知作为独立降级链路。
5. 投递失败保持到期状态并定时重试；服务关闭、Task Manager Stop、设备重启和损坏规则 JSON 均有明确恢复策略。

### 2026-08-22 更名与使用统计（v1.5.0）

1. 应用更名「反懒惰提醒」→「防沉迷提醒」，默认提醒文案与内置种子规则统一为
   "该停下来想一想了"。
2. 新增「今日使用统计」：`UsageStatsActivity` 用 UsageStatsManager 合并当天各包名
   前台时长，按降序展示并汇总总时长；权限走 `PACKAGE_USAGE_STATS`
   （AppOps 检测 + 系统使用情况设置页引导），数据仅本机计算。
3. 开源准备：补充 MIT LICENSE 与面向公众的 README。

### 2026-08-22 统计增强与前台感知（v1.6.0）

1. 使用统计升级：今日/近7天/近30天 Tab；零依赖自绘 `UsageBarChartView`
   柱状图（今日视图高亮今天，长区间每周刻度）；日视图各 App 占比条
   （`ShareBarView`）与"比过去 7 天日均少/多 X"对比文案。
   数据层抽为 `UsageStatsRepository`，按本地时区逐日查询合并，后台线程加载。
2. 前台应用感知：提醒触发时经 `UsageEvents` 找到当前前台 App
   （排除本应用、桌面、SystemUI），附加"此刻在用：X · 今天已用 Y"；
   无使用情况权限时静默降级，不影响提醒主链路。
3. 版本 1.6.0 (10)。

### 2026-08-22 权限自检、在线更新与耗电估算（v1.7.0）

1. 时长格式统一：满 1 小时显示"X小时Y分钟"，不足 1 小时保持"X分Y秒"。
2. 通知权限：首次打开即主动申请；被拒后弹对话框直达系统通知设置；
   主状态文字在通知不可用时也可点击跳转。
3. 「权限状态」面板：通知/悬浮窗/使用情况访问/忽略电池优化四项，
   实时显示已开启/未开启，点击行直达对应设置页（替换原两个独立按钮）。
4. 应用内「检查更新」：读取 GitHub Releases latest API，比较语义化版本号，
   DownloadManager 后台下载 APK 并唤起系统安装器（REQUEST_INSTALL_PACKAGES）。
5. 耗电估算：系统不开放分应用真实耗电（BatteryStats 为内部 API），
   采用电量采样差值 + UsageEvents 前台时长占比的本机估算模型：
   服务 tick 每分钟与锁屏切换/watchdog 处采样 CHARGE_COUNTER(µAh) 与充电状态，
   放电区间按前台秒数分摊到各 App；展示 mAh 绝对值与
   "占电池容量百分比"双单位（跨充电周期可 >100%），电池容量可手动设置。
6. 版本 1.7.0 (11)。

### 2026-08-22 文档与发布渠道收敛（v1.7.0 后续）

README 安装章节改为以 GitHub Releases 为唯一分发入口（应用内「检查更新」为升级通道），
自行构建降级为可选附录；仓库描述同步更新。

### 2026-08-22 真机反馈修复（v1.7.1）

1. **检查更新秒失败**（严重）：Manifest 缺 `INTERNET` 权限——本应用此前
   零联网功能从未申请过，任何连接立即抛 SecurityException。补上权限；
   Updater 增加失败原因日志便于后续诊断。
2. 更新失败交互：改为对话框，提供「重试」与「浏览器打开下载页」（api.github.com
   在部分网络不可达时，网页 302 重定向作为第二通道已在上一版加入）。
3. 电池优化入口：恢复系统直连授权对话框（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
   仅侧载分发不受商店政策限制），替代跳转通用列表页——后者在 OPPO 等 ROM
   上打开的是原生页面而非厂商耗电管理。
4. 权限面板下新增提示行：不同手机路径不同，未能开启时按「后台保活指南」设置。

### 2026-08-22 更名 Muse 与国际化（v1.8.0）

1. 品牌：仓库与产品更名 Muse（中文名「缪思」），定位文案
   "使用手机片刻就提醒思考，放下手机、开始做值得做的事情"；
   新增应用图标（缪思主题插画）并生成全密度启动图；README 重写为中英双语。
2. 国际化：默认资源改为英文（values/），中文迁至 values-zh/；
   时长格式化、星期标签、"今天"等动态文案改走资源；
   LanguageUtils 在 attachBaseContext 包装 Configuration 实现应用内切换
   （跟随系统 / 中文 / English），服务与广播通知同样跟随。
3. 产物与更新通道更名：APK 命名 Muse-v{ver}-{type}.apk，
   Updater 指向 longbow1998/Muse，网页兜底直链同步。
   包名保持 com.learn.antilazy 以支持老用户覆盖升级。

### 2026-08-22 修复签名冲突：固定发布签名（v1.8.1）

问题：CI runner 每次构建生成全新 debug 签名，导致各版本 Release APK 签名互不相同，
覆盖安装报「已安装了签名冲突的应用」。

修复：生成专用签名密钥 muse.keystore 随仓库分发（个人开源侧载项目的通行做法，
等效公开可验证身份），本地与 CI 的 release 构建统一使用；CI 改为发布
assembleRelease 产物（Muse-v{ver}-release.apk）。早期临时签名版本需卸载重装一次，
此后所有版本可无缝覆盖升级与应用内更新。

### 2026-08-22 UI 改版：Material 3（v1.9.0）

引入 Google 官方 Material Components（唯一 UI 依赖），Activity 迁移至
AppCompatActivity + Theme.Material3.DayNight：

1. 深色模式：M3 DayNight 主题 + values-night 色板（背景/卡片/文字/状态色/
   图表轨道全部资源化），状态栏图标明暗随系统切换。
2. 信息架构重组：检查更新与语言收进右上角 ⋮ 菜单；「今日使用统计」升级为
   带图标的醒目卡片入口；保活指南降级为页脚链接；主按钮仅剩「＋新增规则」，
   测试提醒移至规则区标题行图标。
3. 状态可视化：状态色点 + 着色标题（绿=运行/橙=暂停或恢复中/红=停滞），
   大号倒计时数字，警告行按严重度着色（Spannable）。
4. 图标体系：自绘 10 个矢量图标（菜单/测试/统计/权限四项/新增等），
   权限行前缀图标、MaterialSwitch 全面替换旧 Switch。
5. 卡片描边适配深浅色（card_stroke），Tab/占比条/柱状图轨道色资源化。

### 2026-08-23 真机反馈文案修复（v1.9.1）

1. 状态标题 %1$d 占位符原样显示（重构时漏传格式化参数）；统一为「使用中 · N 条规则」。
2. 测试提醒入口由 ▶ 图标改为文字按钮「测试提醒」（品牌色），语义自明。
3. 规则编辑框示例文案改为「你此刻的注意力，值得花在更重要的事上」。
4. 「今日使用统计」更名为「使用统计」（含今日/7天/30天三档）。
5. 应用名显示为包名（如 com.tencent.mm）：Manifest 增加 <queries> 声明
   带桌面图标的应用可见性——非权限、无需授权，仅用于解析真实应用名。

### 2026-08-23 统计口径修复：灭屏不计时 + 耗电归因重构（v1.9.2）

1. 使用统计虚高（严重）：系统 totalTimeInForeground 在灭屏/锁屏期间不结束
   最后一个前台 App，整夜锁屏被记给睡前最后一个应用。
   改为重放 UsageEvents 原始事件：RESUMED/PAUSED 组段，
   SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN（API 30+）瞬间强制关段；
   桌面/系统UI/本应用不计入；API<30 无屏幕事件，退化为原始精度。
2. 耗电估算同规则裁剪前台分摊；无法归因到前台 App 的消耗
   （灭屏、后台同步、待机）单列「系统与后台」，不再硬摊给前台应用。
   Android 不向第三方开放分应用真实耗电（BATTERY_STATS 为
   signature|privileged），此为专业耗电 App 同款估算思路。
3. 插拔电广播（POWER_CONNECTED/DISCONNECTED）瞬间即时采样，
   充电区间电量边界更干净。
