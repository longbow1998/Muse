package com.learn.antilazy

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Sole authority for active-use timing. Alarm fallback is intentionally watchdog-only. */
@SuppressLint("ApplySharedPref") // User intent and lock transitions must survive immediate death.
class MonitorService : Service() {

    private class Rt(
        val rule: Rule,
        var elapsedMs: Long,
        var nextRetryAtElapsed: Long = 0L
    )

    companion object {
        private const val TAG = "MonitorService"
        private const val TICK_MS = 1000L
        private const val DELIVERY_RETRY_MS = 30_000L
        private const val NOTIFICATION_ID_MONITOR = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isUnlocked = false
            private set

        @Volatile
        private var instance: MonitorService? = null

        fun start(context: Context): Boolean {
            val prefs = ReminderEngine.prefs(context)
            val enabledAt = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(RuleStore.KEY_RUNNING, true)
                .putBoolean(RuleStore.KEY_USER_STOPPED, false)
                .putLong(RuleStore.KEY_LAST_ENABLE_WALL, enabledAt)
                .commit()
            return try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
                TickReceiver.scheduleNext(context)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                prefs.edit()
                    .putBoolean(RuleStore.KEY_RUNNING, false)
                    .putBoolean(RuleStore.KEY_USER_STOPPED, true)
                    .commit()
                TickReceiver.cancel(context)
                false
            }
        }

        /** Best-effort recovery from a watchdog alarm without changing the user's intent. */
        fun restartIfExpected(context: Context): Boolean {
            val prefs = ReminderEngine.prefs(context)
            if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false) ||
                prefs.getBoolean(RuleStore.KEY_USER_STOPPED, false)
            ) return false
            return try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
                true
            } catch (e: Exception) {
                Log.w(TAG, "watchdog could not restart foreground service", e)
                false
            }
        }

        fun stop(context: Context) {
            val prefs = ReminderEngine.prefs(context)
            prefs.edit()
                .putBoolean(RuleStore.KEY_RUNNING, false)
                .putBoolean(RuleStore.KEY_USER_STOPPED, true)
                .commit()
            RuleStore.clearProgress(
                prefs,
                SystemClock.elapsedRealtime(),
                ReminderEngine.bootCount(context),
                sync = true
            )
            TickReceiver.cancel(context)
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun isAlive(): Boolean = instance != null

        fun wasRunningBefore(context: Context): Boolean =
            ReminderEngine.prefs(context).getBoolean(RuleStore.KEY_RUNNING, false)

        /** Honor Android's Active apps / Task Manager Stop instead of resurrecting via alarms. */
        fun applyUserRequestedStopIfNeeded(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            val prefs = ReminderEngine.prefs(context)
            if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false)) return false
            val enabledAt = prefs.getLong(RuleStore.KEY_LAST_ENABLE_WALL, 0L)
            if (enabledAt <= 0L) return false
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            val lastExit = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return false
            if (lastExit.reason != ApplicationExitInfo.REASON_USER_REQUESTED ||
                lastExit.timestamp <= enabledAt
            ) return false

            prefs.edit()
                .putBoolean(RuleStore.KEY_RUNNING, false)
                .putBoolean(RuleStore.KEY_USER_STOPPED, true)
                .commit()
            RuleStore.clearProgress(
                prefs,
                SystemClock.elapsedRealtime(),
                ReminderEngine.bootCount(context),
                sync = true
            )
            TickReceiver.cancel(context)
            return true
        }

        fun snapshot(context: Context): List<RuleView> {
            val service = instance
            if (service != null) return service.memorySnapshot()
            val prefs = ReminderEngine.prefs(context)
            val progress = RuleStore.loadProgress(prefs)
            return RuleStore.load(context).map {
                RuleView(it.id, it.text, it.intervalMinutes, it.enabled, progress[it.id] ?: 0L)
            }
        }

        fun setRules(context: Context, rules: List<Rule>) {
            RuleStore.save(context, rules)
            instance?.replaceRules(rules)
        }

        fun sendTestReminder(context: Context): Boolean {
            if (!wasRunningBefore(context)) return false
            val rule = RuleStore.load(context).firstOrNull { it.enabled }
            val text = rule?.text ?: context.getString(R.string.default_reminder_text)
            return Notifier.fireReminder(
                context,
                rule?.id ?: -1L,
                context.getString(R.string.test_reminder_title),
                enrichWithForeground(text, context)
            )
        }

        /** 提醒正文附加“此刻在用哪个 App、今天已用多久”；无权限或拿不到时静默跳过。 */
        private fun enrichWithForeground(ruleText: String, context: Context? = null): String {
            val ctx = context ?: instance
            val line = ctx?.let { runCatching { ForegroundProbe.describe(it) }.getOrNull() }
                ?: return ruleText
            return "$ruleText\n\n$line"
        }

        fun formatDuration(context: Context, ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> context.getString(R.string.duration_hm_fmt, hours, minutes)
                minutes > 0 -> context.getString(R.string.duration_ms_fmt, minutes, seconds)
                else -> context.getString(R.string.duration_s_fmt, seconds)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var runtimeRules: List<Rt> = emptyList()
    private var receiverRegistered = false
    private var initialized = false
    private var ticking = false
    private var lastTickElapsed = 0L
    private var lockedAtElapsed = 0L
    private var currentBootCount = -1
    private var lastBatterySampleAt = 0L

    /** 电量采样间隔：足够细以归因前台 App，又不至于频繁写盘。 */
    private val batterySampleIntervalMs = 60_000L

    private fun maybeSampleBattery() {
        val now = System.currentTimeMillis()
        if (now - lastBatterySampleAt < batterySampleIntervalMs) return
        lastBatterySampleAt = now
        runCatching { BatteryEstimator.takeSample(this) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> handleLockState()
            }
        }
    }

    /** 插拔电瞬间立即采样：把充电区间的电量边界切干净。 */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BatteryEstimator.takeSample(context)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (!ReminderEngine.isUnlockedNow(this@MonitorService)) {
                handleLockState()
                return
            }
            maybeSampleBattery()

            val now = SystemClock.elapsedRealtime()
            val gap = (now - lastTickElapsed).coerceAtLeast(0L)
            lastTickElapsed = now
            var syncCheckpoint = false

            if (TimerMath.isUncertainGap(gap)) {
                // A delayed main-loop tick does not prove that the device was locked.
                // Pause the unknown interval; only an observed long lock may reset progress.
                Log.d(TAG, "unknown active gap ${gap}ms -> progress preserved")
            } else {
                for (rt in runtimeRules) {
                    if (!rt.rule.enabled) continue
                    val interval = rt.rule.intervalMinutes * 60_000L
                    val wasWaitingForDelivery = rt.elapsedMs >= interval
                    val advanced = TimerMath.advance(rt.elapsedMs, interval, gap)
                    if (!advanced.due) {
                        rt.elapsedMs = advanced.elapsedMs
                        continue
                    }
                    if (now < rt.nextRetryAtElapsed) {
                        rt.elapsedMs = interval
                        continue
                    }
                    val delivered = Notifier.fireReminder(
                        this@MonitorService,
                        rt.rule.id,
                        getString(R.string.reminder_title),
                        enrichWithForeground(rt.rule.text)
                    )
                    if (delivered) {
                        rt.elapsedMs = if (wasWaitingForDelivery) 0L else advanced.elapsedMs
                        rt.nextRetryAtElapsed = 0L
                        syncCheckpoint = true
                    } else {
                        rt.elapsedMs = interval
                        rt.nextRetryAtElapsed = now + DELIVERY_RETRY_MS
                    }
                }
            }

            checkpoint(syncCheckpoint)
            updateForegroundNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageUtils.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ReminderEngine.prefs(this)
        currentBootCount = ReminderEngine.bootCount(this)
        Notifier.ensureChannels(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote first; state restoration must never consume the FGS promotion deadline.
        startForeground(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
        prefs.edit()
            .putBoolean(RuleStore.KEY_RUNNING, true)
            .putBoolean(RuleStore.KEY_USER_STOPPED, false)
            .apply()

        if (!initialized) {
            registerScreenReceiverIfNeeded()
            restoreState()
            initialized = true
        } else {
            handleLockState()
        }
        isRunning = true
        TickReceiver.scheduleNext(this)
        Notifier.dismissMonitorStoppedWarning(this)
        updateForegroundNotification()
        return START_STICKY
    }

    private fun restoreState() {
        val now = SystemClock.elapsedRealtime()
        val currentUnlocked = ReminderEngine.isUnlockedNow(this)
        val checkpoint = prefs.getLong(RuleStore.KEY_CHECKPOINT_ELAPSED, 0L)
        val sameBoot = TimerMath.isSameBoot(
            savedBootCount = prefs.getInt(RuleStore.KEY_BOOT_COUNT, -1),
            currentBootCount = currentBootCount,
            checkpointElapsed = checkpoint,
            nowElapsed = now
        )
        val wasUnlocked = prefs.getBoolean(RuleStore.KEY_WAS_UNLOCKED, false)
        val savedLockedAt = prefs.getLong(RuleStore.KEY_LOCKED_AT_ELAPSED, 0L)
        val savedProgress = if (sameBoot) RuleStore.loadProgress(prefs) else emptyMap()

        runtimeRules = RuleStore.load(this).map { Rt(it, savedProgress[it.id] ?: 0L) }
        val pendingLongLock = !wasUnlocked && currentUnlocked &&
            savedLockedAt in 1..now &&
            TimerMath.shouldResetAfterLock(now - savedLockedAt)
        if (!sameBoot || pendingLongLock) {
            runtimeRules.forEach { it.elapsedMs = 0L }
        }

        lockedAtElapsed = when {
            currentUnlocked -> 0L
            sameBoot && !wasUnlocked && savedLockedAt in 1..now -> savedLockedAt
            else -> now
        }
        isUnlocked = currentUnlocked
        lastTickElapsed = now
        checkpoint(sync = true)
        if (currentUnlocked) startTicking()
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
        isRunning = false
        isUnlocked = false
        if (::prefs.isInitialized) {
            if (prefs.getBoolean(RuleStore.KEY_USER_STOPPED, false)) {
                RuleStore.clearProgress(
                    prefs,
                    SystemClock.elapsedRealtime(),
                    currentBootCount,
                    sync = true
                )
                prefs.edit().putBoolean(RuleStore.KEY_RUNNING, false).commit()
            } else if (initialized) {
                checkpoint(sync = true)
            }
        }
        instance = null
        OverlayReminder.dismissAll()
        super.onDestroy()
    }

    private fun handleLockState() {
        val currentUnlocked = ReminderEngine.isUnlockedNow(this)
        if (currentUnlocked == isUnlocked) return
        val now = SystemClock.elapsedRealtime()
        maybeSampleBattery()
        if (!currentUnlocked) {
            isUnlocked = false
            lockedAtElapsed = now
            lastTickElapsed = now
            pauseTicking()
        } else {
            if (lockedAtElapsed in 1..now) {
                val lockDuration = now - lockedAtElapsed
                runtimeRules.forEach {
                    it.elapsedMs = TimerMath.elapsedAfterUnlock(it.elapsedMs, lockDuration)
                }
            }
            isUnlocked = true
            lockedAtElapsed = 0L
            lastTickElapsed = now
            startTicking()
        }
        checkpoint(sync = true)
        updateForegroundNotification()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun pauseTicking() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun checkpoint(sync: Boolean) {
        RuleStore.checkpoint(
            prefs = prefs,
            progress = runtimeRules.associate { it.rule.id to it.elapsedMs },
            checkpointElapsed = SystemClock.elapsedRealtime(),
            bootCount = currentBootCount,
            wasUnlocked = isUnlocked,
            lockedAtElapsed = lockedAtElapsed,
            sync = sync
        )
    }

    private fun replaceRules(rules: List<Rule>) {
        val oldElapsed = runtimeRules.associate { it.rule.id to it.elapsedMs }
        runtimeRules = rules.map { Rt(it, oldElapsed[it.id] ?: 0L) }
        checkpoint(sync = true)
        updateForegroundNotification()
    }

    private fun memorySnapshot(): List<RuleView> =
        runtimeRules.map {
            RuleView(it.rule.id, it.rule.text, it.rule.intervalMinutes, it.rule.enabled, it.elapsedMs)
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
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        )
        receiverRegistered = true
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_MONITOR,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Notifier.CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(fgStatusText())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
    }

    private fun fgStatusText(): String {
        if (!isUnlocked) return getString(R.string.status_locked_paused)
        val enabled = runtimeRules.filter { it.rule.enabled }
        if (enabled.isEmpty()) return getString(R.string.fg_no_enabled_rules)
        val nextMs = enabled.minOf {
            (it.rule.intervalMinutes * 60_000L - it.elapsedMs).coerceAtLeast(0L)
        }
        return getString(R.string.fg_summary_fmt, enabled.size, formatDuration(this, nextMs))
    }
}
