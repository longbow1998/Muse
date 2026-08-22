package com.learn.antilazy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class MonitorService : Service() {

    /** 运行时单条规则状态（定义 + 已累计时长） */
    private class Rt(val rule: Rule, var elapsedMs: Long)

    companion object {
        private const val TAG = "MonitorService"

        private const val TICK_MS = 1000L
        private const val PERSIST_EVERY_MS = 10_000L
        private const val NOTIFICATION_ID_MONITOR = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isUnlocked = false
            private set

        /** 仅主线程读写；UI 通过 snapshot(context) 读取，进程死后自动回退到持久化数据 */
        @Volatile
        private var runtimeRules: List<Rt> = emptyList()

        @Volatile
        private var instance: MonitorService? = null

        fun start(context: Context): Boolean =
            try {
                // 清除"用户主动停止"标记：此后若服务被系统回收，兜底闹钟应继续计时
                ReminderEngine.prefs(context).edit()
                    .putBoolean(RuleStore.KEY_USER_STOPPED, false).apply()
                context.startForegroundService(Intent(context, MonitorService::class.java))
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                false
            }

        fun stop(context: Context) {
            // 标记用户主动停止：onDestroy 才允许清除运行标记，终止闹钟链
            ReminderEngine.prefs(context).edit()
                .putBoolean(RuleStore.KEY_USER_STOPPED, true).apply()
            context.stopService(Intent(context, MonitorService::class.java))
        }

        /** 服务实例是否存活（决定兜底闹钟是否接管计数） */
        fun isAlive(): Boolean = instance != null

        /** UI 展示快照：服务活着读内存（秒级新鲜度），死了回退到落盘进度 */
        fun snapshot(context: Context): List<RuleView> {
            val service = instance
            if (service != null) return service.memorySnapshot()
            val prefs = ReminderEngine.prefs(context)
            val progress = RuleStore.loadProgress(prefs)
            return RuleStore.load(context).map {
                RuleView(it.id, it.text, it.intervalMinutes, it.enabled, progress[it.id] ?: 0L)
            }
        }

        /**
         * 应用新的规则集合：无论服务是否在运行都持久化；
         * 运行中则热更新内存规则，保留同 id 的已计进度。
         */
        fun setRules(context: Context, rules: List<Rule>) {
            RuleStore.save(context, rules)
            instance?.replaceRules(rules) ?: run { runtimeRules = emptyList() }
        }

        fun sendTestReminder(context: Context): Boolean {
            val prefs = ReminderEngine.prefs(context)
            if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false)) return false
            val text = RuleStore.load(context).firstOrNull { it.enabled }?.text
                ?: context.getString(R.string.default_reminder_text)
            return Notifier.fireReminder(
                context,
                context.getString(R.string.test_reminder_title),
                text
            )
        }

        fun wasRunningBefore(context: Context): Boolean =
            context.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(RuleStore.KEY_RUNNING, false)

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var ticking = false
    private var receiverRegistered = false
    private var lastPersistAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> refreshLockState()
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return

            // 与兜底闹钟共用同一墙钟增量：服务存活时增量被这里逐秒消费，
            // 闹钟路径自然拿不到剩余量；服务死亡时则全部由闹钟补算。
            // 间隙异常大（进程被冻结/设备休眠）时按锁屏重置，防假提醒。
            val deltaResult = ReminderEngine.consumeDeltaSanitized(prefs)
            if (deltaResult.resetProgress) {
                runtimeRules.forEach { it.elapsedMs = 0 }
                persistProgress()
                Log.d(TAG, "tick gap too large -> progress reset")
            } else if (deltaResult.deltaMs > 0) {
                for (rt in runtimeRules) {
                    if (!rt.rule.enabled) continue
                    rt.elapsedMs += deltaResult.deltaMs
                    if (rt.elapsedMs >= rt.rule.intervalMinutes * 60_000L) {
                        rt.elapsedMs = 0
                        Notifier.fireReminder(
                            this@MonitorService,
                            getString(R.string.reminder_title),
                            rt.rule.text
                        )
                    }
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastPersistAt >= PERSIST_EVERY_MS) {
                persistProgress()
                lastPersistAt = now
            }
            updateForegroundNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restoreOrResetProgress()
        // 必须第一时间进入前台态：快速启停时若迟迟不调用 startForeground，
        // 会触发 "did not then call Service.startForeground()" 系统崩溃
        startForeground(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
        registerScreenReceiverIfNeeded()
        prefs.edit().putBoolean(RuleStore.KEY_RUNNING, true).apply()
        isRunning = true
        refreshLockState()
        TickReceiver.scheduleNext(this)
        return START_STICKY
    }

    /**
     * 进度恢复：距最后落盘不超过锁屏重置阈值则接着上次进度继续计；
     * 超过视为新会话清零。同时重置锁定状态机的初值，避免陈旧的
     * locked_at 触发误重置。
     */
    private fun restoreOrResetProgress() {
        prefs = ReminderEngine.prefs(this)
        val wasRunning = prefs.getBoolean(RuleStore.KEY_RUNNING, false)
        val savedAt = prefs.getLong(RuleStore.KEY_SAVED_AT, 0L)
        val withinSession = wasRunning &&
            savedAt > 0 &&
            System.currentTimeMillis() - savedAt <= ReminderEngine.LOCK_RESET_MS
        val progress = if (withinSession) RuleStore.loadProgress(prefs) else emptyMap()
        runtimeRules = RuleStore.load(this).map { Rt(it, progress[it.id] ?: 0L) }
        ReminderEngine.resetLockTracking(prefs, unlockedNow = isUnlockedNow())
        markWallClock()
        Log.d(TAG, "progress restored: withinSession=$withinSession, rules=${runtimeRules.size}")
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        isRunning = false
        isUnlocked = false
        persistProgress()
        // 仅用户主动停止才终止监控；系统回收（划卡/内存回收）时
        // 保留 running 标记，由兜底闹钟继续计时、START_STICKY 尝试复活
        val userStopped = prefs.getBoolean(RuleStore.KEY_USER_STOPPED, false)
        if (userStopped) {
            prefs.edit().putBoolean(RuleStore.KEY_RUNNING, false).apply()
        } else {
            ReminderEngine.markWallClock(prefs)
        }
        instance = null
        super.onDestroy()
    }

    private fun memorySnapshot(): List<RuleView> =
        runtimeRules.map {
            RuleView(it.rule.id, it.rule.text, it.rule.intervalMinutes, it.rule.enabled, it.elapsedMs)
        }

    private fun isUnlockedNow(): Boolean = ReminderEngine.isUnlockedNow(this)

    private fun markWallClock() {
        ReminderEngine.markWallClock(prefs)
    }

    private fun persistProgress() {
        RuleStore.saveProgress(prefs, runtimeRules.associate { it.rule.id to it.elapsedMs })
        prefs.edit().putLong(RuleStore.KEY_SAVED_AT, System.currentTimeMillis()).apply()
    }

    private fun replaceRules(rules: List<Rule>) {
        val oldElapsed = runtimeRules.associate { it.rule.id to it.elapsedMs }
        runtimeRules = rules.map { Rt(it, oldElapsed[it.id] ?: 0L) }
    }

    private fun registerScreenReceiverIfNeeded() {
        if (receiverRegistered) return
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        )
        receiverRegistered = true
    }

    /**
     * 核心规则：
     * 1. 屏幕亮 且 keyguard 已解锁才计时；锁屏/灭屏暂停累计；
     * 2. 本次锁屏超过 LOCK_RESET_MS 时，解锁后所有规则进度清零重新计。
     */
    private fun refreshLockState() {
        val unlocked = isUnlockedNow()
        val state = ReminderEngine.applyLockTransition(prefs, unlocked)
        if (state.reset && unlocked) {
            runtimeRules.forEach { it.elapsedMs = 0 }
            Log.d(TAG, "lock exceeded ${ReminderEngine.LOCK_RESET_MS}ms -> progress reset")
        }
        // 状态切换处重新锚定墙钟：锁屏期间的流逝时间不得被当作使用量补算
        markWallClock()
        isUnlocked = unlocked
        if (unlocked) startTicking() else pauseTicking()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        updateForegroundNotification()
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun pauseTicking() {
        if (!ticking) return
        ticking = false
        handler.removeCallbacks(tickRunnable)
        persistProgress()
        updateForegroundNotification()
    }

    private fun buildForegroundNotification(): Notification =
        Notification.Builder(this, Notifier.CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(fgStatusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateForegroundNotification() {
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
    }

    private fun fgStatusText(): String {
        if (!isUnlocked) return getString(R.string.status_locked_paused)
        val enabled = runtimeRules.filter { it.rule.enabled }
        if (enabled.isEmpty()) return getString(R.string.fg_no_enabled_rules)
        val nextMs = enabled.minOf {
            (it.rule.intervalMinutes * 60_000L - it.elapsedMs).coerceAtLeast(0L)
        }
        return getString(R.string.fg_summary_fmt, enabled.size, formatDuration(nextMs))
    }
}
