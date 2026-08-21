package com.learn.antilazy

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"

        /** 连续解锁使用多久提醒一次：5 分钟 */
        const val INTERVAL_MS = 5 * 60 * 1000L

        private const val TICK_MS = 1000L
        private const val FG_UPDATE_EVERY_TICKS = 30L
        private const val CHANNEL_ID_MONITOR = "monitor"
        private const val CHANNEL_ID_REMINDER = "reminder"
        private const val PREFS_NAME = "anti_lazy"
        private const val KEY_RUNNING = "running"
        private const val NOTIFICATION_ID_MONITOR = 1
        private const val NOTIFICATION_ID_REMINDER_BASE = 1000

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isUnlocked = false
            private set

        @Volatile
        var activeMs = 0L
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun wasRunningBefore(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reminderId = NOTIFICATION_ID_REMINDER_BASE
    private var tickCount = 0L
    private var ticking = false
    private var receiverRegistered = false

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
            activeMs += TICK_MS
            tickCount++
            if (activeMs >= INTERVAL_MS) {
                activeMs = 0
                sendReminder()
            }
            if (tickCount % FG_UPDATE_EVERY_TICKS == 0L) {
                updateForegroundNotification()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerScreenReceiverIfNeeded()
        isRunning = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, true).apply()
        startForeground(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
        refreshLockState()
        return START_STICKY
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
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
     * 核心规则：屏幕亮着 且 keyguard 已解锁（真正在使用中）才计时；
     * 锁屏或灭屏时暂停累计，解锁后继续。
     */
    private fun refreshLockState() {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        isUnlocked = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
        Log.d(TAG, "lock state changed: unlocked=$isUnlocked, activeMs=$activeMs")
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
        updateForegroundNotification()
    }

    private fun sendReminder() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(reminderId++, buildReminderNotification())
    }

    private fun reminderContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildReminderNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(getString(R.string.reminder_title))
            .setContentText(getString(R.string.reminder_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.reminder_text)))
            .setContentIntent(reminderContentIntent())
            .setAutoCancel(true)
            .build()

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
            .setContentText(statusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(reminderContentIntent())
            .build()

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_MONITOR, buildForegroundNotification())
    }

    private fun statusText(): String =
        if (!isUnlocked) {
            getString(R.string.status_locked_paused)
        } else {
            getString(
                R.string.status_running_fmt,
                formatDuration(activeMs),
                formatDuration(INTERVAL_MS - activeMs)
            )
        }
}
