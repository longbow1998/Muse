package com.learn.antilazy

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/** Shared state helpers and a watchdog. The foreground service is the only timer authority. */
object ReminderEngine {

    private const val HEALTH_WARNING_INTERVAL_MS = 5 * 60_000L

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)

    fun isUnlockedNow(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    fun lastCheckpointAgeMs(context: Context): Long {
        val now = SystemClock.elapsedRealtime()
        val checkpoint = prefs(context).getLong(RuleStore.KEY_CHECKPOINT_ELAPSED, 0L)
        return if (checkpoint <= 0L || checkpoint > now) Long.MAX_VALUE else now - checkpoint
    }

    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /**
     * Alarm fallback only detects an unhealthy monitor. Sparse alarms cannot reconstruct
     * lock/unlock history, so they must never fabricate active-use progress.
     */
    fun onAlarm(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false)) return false
        runCatching { BatteryEstimator.takeSample(context) }
        if (MonitorService.isAlive()) return true
        val restartRequested = MonitorService.restartIfExpected(context)
        if (restartRequested && lastCheckpointAgeMs(context) <= TimerMath.LOCK_RESET_MS) return true

        val now = SystemClock.elapsedRealtime()
        val lastWarning = prefs.getLong(RuleStore.KEY_LAST_HEALTH_WARN_ELAPSED, 0L)
        if (isUnlockedNow(context) &&
            (lastWarning <= 0L || lastWarning > now || now - lastWarning >= HEALTH_WARNING_INTERVAL_MS)
        ) {
            if (Notifier.showMonitorStoppedWarning(context)) {
                prefs.edit().putLong(RuleStore.KEY_LAST_HEALTH_WARN_ELAPSED, now).apply()
            }
        }
        return true
    }
}
