package com.learn.antilazy

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

class MonitorService : Service() {

    /** 运行时单条规则状态（定义 + 已累计时长） */
    private class Rt(val rule: Rule, var elapsedMs: Long)

    companion object {
        private const val TAG = "MonitorService"

        private const val TICK_MS = 1000L

        /** 墙钟对账兜底闹钟的周期 */
        private const val WATCHDOG_ALARM_MS = 30_000L

        /** 单次补算的进度上限，防止异常大跳 */
        private const val MAX_CATCHUP_MS = 5 * 60 * 1000L

        /** 锁屏超过该时长，解锁后所有规则进度清零重新计 */
        const val LOCK_RESET_MS = 60_000L

        private const val CHANNEL_ID_MONITOR = "monitor"
        private const val CHANNEL_ID_REMINDER = "reminder"
        private const val NOTIFICATION_ID_MONITOR = 1
        private const val NOTIFICATION_ID_REMINDER_BASE = 1000

        private const val KEY_RUNNING = "running"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_LAST_TICK_WALL = "last_tick_wall"
        private const val KEY_LOCKED_AT = "locked_at"
        private const val KEY_LAST_REMINDER_AT = "last_reminder_at"
        private const val KEY_REMIND_COUNT = "remind_count"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isUnlocked = false
            private set

        @Volatile
        var lastReminderAt = 0L
            private set

        @Volatile
        var reminderCount = 0
            private set

        /** 仅主线程读写（tick 与 UI 同在主线程），@Volatile 保证跨线程可见性 */
        @Volatile
        private var runtimeRules: List<Rt> = emptyList()

        @Volatile
        private var instance: MonitorService? = null

        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        /** UI 展示快照 */
        fun snapshot(): List<RuleView> =
            runtimeRules.map { RuleView(it.rule.id, it.rule.text, it.rule.intervalMinutes, it.rule.enabled, it.elapsedMs) }

        /**
         * 应用新的规则集合：无论服务是否在运行都持久化；
         * 运行中则热更新内存规则，保留同 id 的已计进度。
         */
        fun setRules(context: Context, rules: List<Rule>) {
            RuleStore.save(context, rules)
            instance?.replaceRules(rules) ?: run { runtimeRules = emptyList() }
        }

        fun sendTestReminder(): Boolean {
            val service = instance ?: return false
            val text = runtimeRules.firstOrNull { it.rule.enabled }?.rule?.text
                ?: service.getString(R.string.default_reminder_text)
            service.fireReminder(text, isTest = true)
            return true
        }

        fun wasRunningBefore(context: Context): Boolean =
            context.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)

        /**
         * 兜底闹钟入口：进程活着就直接按墙钟差补算；
         * 进程死了但监控应为开启态则拉起服务（restore 时会按锁屏重置规则处理）。
         * 锁屏中不推进——下次解锁按"锁屏超 1 分钟重置"处理。
         */
        fun catchUp(context: Context) {
            val prefs = context.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_RUNNING, false)) return
            val pm = context.getSystemService(PowerManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            if (!(pm.isInteractive && !km.isKeyguardLocked)) return
            val service = instance
            if (service != null) {
                service.advanceByWallClockDelta()
            } else {
                start(context)
            }
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var reminderId = NOTIFICATION_ID_REMINDER_BASE
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
            markWallClock()
            for (rt in runtimeRules) {
                if (!rt.rule.enabled) continue
                rt.elapsedMs += TICK_MS
                if (rt.elapsedMs >= rt.rule.intervalMinutes * 60_000L) {
                    rt.elapsedMs = 0
                    fireReminder(rt.rule.text, isTest = false)
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPersistAt >= 10_000L) {
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
        createNotificationChannels()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restoreOrResetProgress()
        // 必须第一时间进入前台态：快速启停时若迟迟不调用 startForeground，
        // 会触发 "did not then call Service.startForeground()" 系统崩溃
        startForeground(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
        registerScreenReceiverIfNeeded()
        isRunning = true
        prefs.edit().putBoolean(KEY_RUNNING, true).apply()
        refreshLockState()
        TickReceiver.scheduleNext(this)
        return START_STICKY
    }

    /**
     * 进度持久化与恢复：
     * - 上次为硬杀（running 标记仍在）且距最后落盘 <= 锁屏重置阈值，接着上次进度继续计；
     * - 超过阈值视为新会话清零（与"锁屏超 1 分钟重置"语义一致）。
     */
    private fun restoreOrResetProgress() {
        prefs = getSharedPreferences(RuleStore.PREFS_NAME, MODE_PRIVATE)
        val wasRunning = prefs.getBoolean(KEY_RUNNING, false)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val withinSession = wasRunning &&
            savedAt > 0 &&
            System.currentTimeMillis() - savedAt <= LOCK_RESET_MS
        val progress = if (withinSession) RuleStore.loadProgress(prefs) else emptyMap()
        runtimeRules = RuleStore.load(this).map { Rt(it, progress[it.id] ?: 0L) }
        lastReminderAt = prefs.getLong(KEY_LAST_REMINDER_AT, 0L)
        reminderCount = prefs.getInt(KEY_REMIND_COUNT, 0)
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
        prefs.edit().putBoolean(KEY_RUNNING, false).apply()
        instance = null
        super.onDestroy()
    }

    private fun markWallClock() {
        prefs.edit().putLong(KEY_LAST_TICK_WALL, System.currentTimeMillis()).apply()
    }

    /**
     * 兜底路径：按上次 tick 的墙钟时间差一次性补算所有启用规则的进度。
     * 与秒级 tick 写同一个 KEY_LAST_TICK_WALL，天然去重。
     */
    private fun advanceByWallClockDelta() {
        handler.post {
            if (!ticking) return@post
            val last = prefs.getLong(KEY_LAST_TICK_WALL, 0L)
            val delta = (System.currentTimeMillis() - last).coerceIn(0L, MAX_CATCHUP_MS)
            if (delta < TICK_MS) return@post
            markWallClock()
            for (rt in runtimeRules) {
                if (!rt.rule.enabled) continue
                rt.elapsedMs += delta
                if (rt.elapsedMs >= rt.rule.intervalMinutes * 60_000L) {
                    rt.elapsedMs = 0
                    fireReminder(rt.rule.text, isTest = false)
                }
            }
            persistProgress()
            updateForegroundNotification()
            Log.d(TAG, "catch-up advanced $delta ms")
        }
    }

    private fun persistProgress() {
        RuleStore.saveProgress(prefs, runtimeRules.associate { it.rule.id to it.elapsedMs })
        prefs.edit().putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
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
     * 2. 本次锁屏时长超过 LOCK_RESET_MS 时，解锁后所有规则进度清零重新计。
     */
    private fun refreshLockState() {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val unlocked = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
        if (unlocked && !isUnlocked) {
            // 从锁定恢复：检查本次锁屏是否超过重置阈值
            val lockedAt = prefs.getLong(KEY_LOCKED_AT, 0L)
            if (lockedAt > 0 && System.currentTimeMillis() - lockedAt > LOCK_RESET_MS) {
                runtimeRules.forEach { it.elapsedMs = 0 }
                persistProgress()
                Log.d(TAG, "lock exceeded ${LOCK_RESET_MS}ms -> all progress reset")
            }
        }
        isUnlocked = unlocked
        Log.d(TAG, "lock state changed: unlocked=$isUnlocked")
        if (isUnlocked) startTicking() else pauseTicking()
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
        prefs.edit().putLong(KEY_LOCKED_AT, System.currentTimeMillis()).apply()
        persistProgress()
        updateForegroundNotification()
    }

    private fun fireReminder(text: String, isTest: Boolean) {
        reminderCount++
        lastReminderAt = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_REMINDER_AT, lastReminderAt)
            .putInt(KEY_REMIND_COUNT, reminderCount)
            .apply()
        val title = getString(
            if (isTest) R.string.test_reminder_title else R.string.reminder_title
        )
        getSystemService(NotificationManager::class.java)
            .notify(reminderId++, buildReminderNotification(title, text))
        Log.d(TAG, "reminder fired: isTest=$isTest, count=$reminderCount")
    }

    private fun reminderContentIntent(text: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, ReminderActivity::class.java)
                .putExtra(ReminderActivity.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 全屏提醒：到点直接弹整页卡片盖在任何应用之上；不支持时退回普通横幅 */
    private fun buildReminderNotification(title: String, text: String): Notification {
        val fullScreenPi = reminderContentIntent(text, reminderId + 50000)
        return Notification.Builder(this, CHANNEL_ID_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            CHANNEL_ID_MONITOR,
            getString(R.string.channel_monitor),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.channel_monitor_desc)
            setShowBadge(false)
        }

        val reminder = NotificationChannel(
            CHANNEL_ID_REMINDER,
            getString(R.string.channel_reminder),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_reminder_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400)
            enableLights(true)
            lightColor = getColor(R.color.brand)
        }

        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(reminder)
    }

    private fun buildForegroundNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(fgStatusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
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
