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
import java.util.concurrent.Executors

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
        private const val FOREGROUND_QUERY_TIMEOUT_MS = 5_000L
        private const val MAX_DELIVERY_OBSERVATION_AGE_MS = 250L
        private const val NOTIFICATION_ID_MONITOR = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isUnlocked = false
            private set

        @Volatile
        var isWhitelistPaused = false
            private set

        @Volatile
        var isForegroundUnknown = false
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

        fun setWhitelistedPackages(context: Context, packages: Set<String>) {
            WhitelistStore.save(context, packages)
            instance?.replaceWhitelist(WhitelistStore.load(context))
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
    private var pausedAtElapsed = 0L
    private var pauseIncludesWhitelist = false
    private var timingActive = false
    private var activeNotBeforeElapsed = 0L
    private var currentBootCount = -1
    private var lastBatterySampleAt = 0L
    private var whitelistPackages: Set<String> = emptySet()
    private val foregroundTracker = ForegroundAppTracker()
    private val foregroundExecutor = Executors.newSingleThreadExecutor()
    private var lastForegroundObservation: ForegroundAppTracker.Observation? = null
    private var foregroundGeneration = 0L
    private var foregroundQueryInFlight = false
    private var foregroundQueryStartedElapsed = 0L

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
            handler.postDelayed(this, TICK_MS)
            if (!ReminderEngine.isUnlockedNow(this@MonitorService)) {
                handleLockState()
                return
            }
            maybeSampleBattery()

            if (whitelistPackages.isEmpty()) {
                isForegroundUnknown = false
                processTimerTickWithoutWhitelist()
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (foregroundQueryInFlight) {
                freezeUnknownInterval(now)
                if (now - foregroundQueryStartedElapsed > FOREGROUND_QUERY_TIMEOUT_MS) {
                    isForegroundUnknown = true
                    updateForegroundNotification()
                }
                return
            }
            submitForegroundQuery()
        }
    }

    private fun submitForegroundQuery() {
        val generation = foregroundGeneration
        val tracker = foregroundTracker
        val executor = foregroundExecutor
        foregroundQueryInFlight = true
        foregroundQueryStartedElapsed = SystemClock.elapsedRealtime()
        runCatching {
            executor.execute {
                val observation = runCatching { tracker.observe(this@MonitorService) }.getOrNull()
                handler.post {
                    foregroundQueryInFlight = false
                    foregroundQueryStartedElapsed = 0L
                    if (!ticking || generation != foregroundGeneration) return@post
                    if (observation == null) {
                        markForegroundUnknown(SystemClock.elapsedRealtime())
                    } else {
                        processForegroundObservation(observation)
                    }
                }
            }
        }.onFailure {
            foregroundQueryInFlight = false
            foregroundQueryStartedElapsed = 0L
            markForegroundUnknown(SystemClock.elapsedRealtime())
        }
    }

    private fun processTimerTickWithoutWhitelist() {
        if (!ticking) return
        if (!ReminderEngine.isUnlockedNow(this)) {
            handleLockState()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val stateChanged = applyTimingState(
            unlocked = true,
            whitelistPaused = false,
            transitionAtElapsed = now
        )
        val delivered = if (timingActive) advanceRules(now, allowDelivery = true) else false
        checkpoint(delivered || stateChanged)
        updateForegroundNotification()
    }

    private fun processForegroundObservation(observation: ForegroundAppTracker.Observation) {
        if (!ticking) return
        if (!ReminderEngine.isUnlockedNow(this)) {
            handleLockState()
            return
        }
        if (!observation.reliable) {
            markForegroundUnknown(SystemClock.elapsedRealtime())
            return
        }

        isForegroundUnknown = false
        lastForegroundObservation = observation
        val now = SystemClock.elapsedRealtime()
        var stateChanged = false
        if (observation.hasUsageAccess) {
            observation.transitions.forEach {
                stateChanged = applyObservedPackage(
                    it.packageName,
                    it.changedAtWallMs,
                    now
                ) || stateChanged
            }
            val pkg = observation.packageName
            if (pkg != null) {
                stateChanged = applyObservedPackage(
                    pkg,
                    observation.changedAtWallMs,
                    now
                ) || stateChanged
            }
        } else {
            stateChanged = applyTimingState(
                unlocked = true,
                whitelistPaused = false,
                transitionAtElapsed = now
            )
        }

        val observedThrough = wallTimeToElapsed(observation.observedThroughWallMs, now)
        val observationAge = (System.currentTimeMillis() - observation.observedThroughWallMs)
            .coerceAtLeast(0L)
        val mayDeliver = observation.observedThroughWallMs > 0L &&
            observationAge <= MAX_DELIVERY_OBSERVATION_AGE_MS
        val delivered = if (timingActive) {
            advanceRules(observedThrough, allowDelivery = mayDeliver)
        } else {
            false
        }
        checkpoint(delivered || stateChanged)
        updateForegroundNotification()
    }

    private fun applyObservedPackage(
        packageName: String,
        changedAtWallMs: Long,
        nowElapsed: Long
    ): Boolean {
        val whitelistPaused = packageName in whitelistPackages
        val observedAt = wallTimeToElapsed(changedAtWallMs, nowElapsed)
        val transitionAt = if (!whitelistPaused && activeNotBeforeElapsed > 0L) {
            maxOf(observedAt, activeNotBeforeElapsed)
        } else {
            observedAt
        }
        if (timingActive && whitelistPaused) {
            advanceRules(transitionAt, allowDelivery = false)
        }
        return applyTimingState(
            unlocked = true,
            whitelistPaused = whitelistPaused,
            transitionAtElapsed = transitionAt
        )
    }

    private fun advanceRules(toElapsed: Long, allowDelivery: Boolean): Boolean {
        val effectiveTo = maxOf(toElapsed, lastTickElapsed)
        val gap = effectiveTo - lastTickElapsed
        lastTickElapsed = effectiveTo
        if (TimerMath.isUncertainGap(gap)) {
            Log.d(TAG, "unknown active gap ${gap}ms -> progress preserved")
            return false
        }

        var deliveredAny = false
        for (rt in runtimeRules) {
            if (!rt.rule.enabled) continue
            val interval = rt.rule.intervalMinutes * 60_000L
            val wasWaitingForDelivery = rt.elapsedMs >= interval
            if (wasWaitingForDelivery && !allowDelivery) continue
            val advanced = TimerMath.advance(rt.elapsedMs, interval, gap)
            if (!advanced.due) {
                rt.elapsedMs = advanced.elapsedMs
                continue
            }
            if (!allowDelivery) {
                rt.elapsedMs = interval
                continue
            }
            if (effectiveTo < rt.nextRetryAtElapsed) {
                rt.elapsedMs = interval
                continue
            }
            val delivered = Notifier.fireReminder(
                this,
                rt.rule.id,
                getString(R.string.reminder_title),
                enrichWithForeground(rt.rule.text)
            )
            if (delivered) {
                rt.elapsedMs = if (wasWaitingForDelivery) 0L else advanced.elapsedMs
                rt.nextRetryAtElapsed = 0L
                deliveredAny = true
            } else {
                rt.elapsedMs = interval
                rt.nextRetryAtElapsed = effectiveTo + DELIVERY_RETRY_MS
            }
        }
        return deliveredAny
    }

    private fun wallTimeToElapsed(changedAtWallMs: Long, nowElapsed: Long): Long {
        val nowWall = System.currentTimeMillis()
        if (changedAtWallMs <= 0L || changedAtWallMs > nowWall) return nowElapsed
        val age = (nowWall - changedAtWallMs).coerceIn(0L, nowElapsed)
        return nowElapsed - age
    }

    private fun freezeUnknownInterval(now: Long) {
        lastTickElapsed = now
        checkpoint(sync = false)
    }

    private fun markForegroundUnknown(now: Long) {
        isForegroundUnknown = true
        freezeUnknownInterval(now)
        updateForegroundNotification()
    }

    private fun invalidateForegroundQuery() {
        foregroundGeneration++
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
        whitelistPackages = WhitelistStore.load(this)
        val checkpoint = prefs.getLong(RuleStore.KEY_CHECKPOINT_ELAPSED, 0L)
        val sameBoot = TimerMath.isSameBoot(
            savedBootCount = prefs.getInt(RuleStore.KEY_BOOT_COUNT, -1),
            currentBootCount = currentBootCount,
            checkpointElapsed = checkpoint,
            nowElapsed = now
        )
        val wasTimingActive = prefs.getBoolean(RuleStore.KEY_WAS_TIMING_ACTIVE, false)
        val savedPausedAt = prefs.getLong(RuleStore.KEY_PAUSED_AT_ELAPSED, 0L)
        val savedPauseIncludesWhitelist = prefs.getBoolean(
            RuleStore.KEY_PAUSE_INCLUDES_WHITELIST,
            false
        )
        val savedProgress = if (sameBoot) RuleStore.loadProgress(prefs) else emptyMap()
        val currentTimingActive = currentUnlocked && when {
            !sameBoot -> true
            whitelistPackages.isEmpty() -> true
            wasTimingActive -> true
            else -> false
        }
        val currentWhitelistPaused = currentUnlocked && !currentTimingActive &&
            savedPauseIncludesWhitelist && whitelistPackages.isNotEmpty()

        runtimeRules = RuleStore.load(this).map { Rt(it, savedProgress[it.id] ?: 0L) }
        val pendingLongPause = !wasTimingActive && currentTimingActive &&
            savedPausedAt in 1..now &&
            TimerMath.shouldResetAfterLock(now - savedPausedAt)
        if (!sameBoot || pendingLongPause) {
            runtimeRules.forEach { it.elapsedMs = 0L }
        }

        pausedAtElapsed = when {
            currentTimingActive -> 0L
            sameBoot && !wasTimingActive && savedPausedAt in 1..now -> savedPausedAt
            else -> now
        }
        isUnlocked = currentUnlocked
        isWhitelistPaused = currentWhitelistPaused
        pauseIncludesWhitelist = !currentTimingActive && savedPauseIncludesWhitelist
        timingActive = currentTimingActive
        activeNotBeforeElapsed = if (currentUnlocked && !currentTimingActive &&
            !savedPauseIncludesWhitelist
        ) {
            now
        } else {
            0L
        }
        lastTickElapsed = now
        if (currentWhitelistPaused) dismissVisibleReminders()
        checkpoint(sync = true)
        if (currentUnlocked) startTicking()
    }

    override fun onDestroy() {
        ticking = false
        invalidateForegroundQuery()
        handler.removeCallbacksAndMessages(null)
        foregroundExecutor.shutdownNow()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
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
        isRunning = false
        isUnlocked = false
        isWhitelistPaused = false
        isForegroundUnknown = false
        timingActive = false
        instance = null
        OverlayReminder.dismissAll()
        super.onDestroy()
    }

    private fun handleLockState() {
        val currentUnlocked = ReminderEngine.isUnlockedNow(this)
        if (currentUnlocked == isUnlocked) return
        invalidateForegroundQuery()
        val now = SystemClock.elapsedRealtime()
        maybeSampleBattery()
        if (currentUnlocked && whitelistPackages.isNotEmpty()) {
            // Wait for a post-unlock UsageEvents query before ending the continuous pause.
            isUnlocked = true
            activeNotBeforeElapsed = now
            startTicking()
            checkpoint(sync = true)
            updateForegroundNotification()
            return
        }
        applyTimingState(
            unlocked = currentUnlocked,
            whitelistPaused = false,
            transitionAtElapsed = now
        )
        if (currentUnlocked) startTicking() else pauseTicking()
        checkpoint(sync = true)
        updateForegroundNotification()
    }

    private fun applyTimingState(
        unlocked: Boolean,
        whitelistPaused: Boolean,
        transitionAtElapsed: Long
    ): Boolean {
        val transitionAt = transitionAtElapsed.coerceAtLeast(1L)
        val normalizedWhitelistPause = unlocked && whitelistPaused
        val nextTimingActive = unlocked && !normalizedWhitelistPause
        var changed = unlocked != isUnlocked || normalizedWhitelistPause != isWhitelistPaused

        if (nextTimingActive != timingActive) {
            if (nextTimingActive) {
                if (pausedAtElapsed in 1..transitionAt) {
                    val pauseDuration = transitionAt - pausedAtElapsed
                    runtimeRules.forEach {
                        it.elapsedMs = TimerMath.elapsedAfterPause(it.elapsedMs, pauseDuration)
                    }
                }
                pausedAtElapsed = 0L
                pauseIncludesWhitelist = false
                activeNotBeforeElapsed = 0L
            } else {
                pausedAtElapsed = TimerMath.pauseStartedAt(pausedAtElapsed, transitionAt)
                pauseIncludesWhitelist = normalizedWhitelistPause
            }
            timingActive = nextTimingActive
            lastTickElapsed = maxOf(lastTickElapsed, transitionAt)
            changed = true
        } else if (!nextTimingActive) {
            val pauseStart = TimerMath.pauseStartedAt(pausedAtElapsed, transitionAt)
            if (pauseStart != pausedAtElapsed ||
                normalizedWhitelistPause && !pauseIncludesWhitelist
            ) {
                pausedAtElapsed = pauseStart
                pauseIncludesWhitelist = pauseIncludesWhitelist || normalizedWhitelistPause
                changed = true
            }
        }

        if (normalizedWhitelistPause && !isWhitelistPaused) dismissVisibleReminders()
        isUnlocked = unlocked
        isWhitelistPaused = normalizedWhitelistPause
        return changed
    }

    private fun dismissVisibleReminders() {
        Notifier.dismissInterruptions(this)
    }

    private fun cachedWhitelistPause(): Boolean {
        if (whitelistPackages.isEmpty()) return false
        val observation = lastForegroundObservation
        return when {
            observation == null || !observation.reliable -> pauseIncludesWhitelist
            !observation.hasUsageAccess -> false
            else -> observation.packageName in whitelistPackages
        }
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
            wasTimingActive = timingActive,
            pausedAtElapsed = pausedAtElapsed,
            pauseIncludesWhitelist = pauseIncludesWhitelist,
            sync = sync
        )
    }

    private fun replaceRules(rules: List<Rule>) {
        val oldElapsed = runtimeRules.associate { it.rule.id to it.elapsedMs }
        runtimeRules = rules.map { Rt(it, oldElapsed[it.id] ?: 0L) }
        checkpoint(sync = true)
        updateForegroundNotification()
    }

    private fun replaceWhitelist(packages: Set<String>) {
        invalidateForegroundQuery()
        whitelistPackages = packages
        if (packages.isEmpty()) isForegroundUnknown = false
        if (!initialized || !isUnlocked) return
        val now = SystemClock.elapsedRealtime()
        val whitelistPaused = cachedWhitelistPause()
        if (applyTimingState(
                unlocked = true,
                whitelistPaused = whitelistPaused,
                transitionAtElapsed = now
            )
        ) {
            checkpoint(sync = true)
            updateForegroundNotification()
        }
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
        if (isWhitelistPaused) return getString(R.string.status_whitelist_paused)
        if (isForegroundUnknown) return getString(R.string.status_foreground_unknown)
        val enabled = runtimeRules.filter { it.rule.enabled }
        if (enabled.isEmpty()) return getString(R.string.fg_no_enabled_rules)
        val nextMs = enabled.minOf {
            (it.rule.intervalMinutes * 60_000L - it.elapsedMs).coerceAtLeast(0L)
        }
        return getString(R.string.fg_summary_fmt, enabled.size, formatDuration(this, nextMs))
    }
}
