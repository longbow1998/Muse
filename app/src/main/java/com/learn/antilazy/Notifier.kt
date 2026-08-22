package com.learn.antilazy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 通知发送：不依赖任何 Service 实例，服务进程与兜底闹钟接收器共用。
 * 提醒走 fullScreenIntent 整屏弹出 ReminderActivity；系统不支持时退回横幅。
 */
object Notifier {

    const val CHANNEL_ID_MONITOR = "monitor"
    private const val CHANNEL_ID_REMINDER = "reminder"
    private const val NOTIFICATION_ID_REMINDER_BASE = 1000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val monitor = NotificationChannel(
            CHANNEL_ID_MONITOR,
            context.getString(R.string.channel_monitor),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
        }

        val reminder = NotificationChannel(
            CHANNEL_ID_REMINDER,
            context.getString(R.string.channel_reminder),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_reminder_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400)
            enableLights(true)
            lightColor = context.getColor(R.color.brand)
        }

        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(reminder)
    }

    /** 发出一条到点提醒；返回是否成功 */
    fun fireReminder(context: Context, title: String, text: String): Boolean =
        runCatching {
            ensureChannels(context)
            val prefs = ReminderEngine.prefs(context)
            val notifId = prefs.getInt(RuleStore.KEY_NEXT_NOTIF_ID, NOTIFICATION_ID_REMINDER_BASE)

            val pi = PendingIntent.getActivity(
                context,
                notifId,
                Intent(context, ReminderActivity::class.java)
                    .putExtra(ReminderActivity.EXTRA_TEXT, text)
                    .putExtra(ReminderActivity.EXTRA_NOTIF_ID, notifId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(context, CHANNEL_ID_REMINDER)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            context.getSystemService(NotificationManager::class.java)?.notify(notifId, notification)

            prefs.edit()
                .putInt(RuleStore.KEY_NEXT_NOTIF_ID, notifId + 1)
                .putInt(
                    RuleStore.KEY_REMIND_COUNT,
                    prefs.getInt(RuleStore.KEY_REMIND_COUNT, 0) + 1
                )
                .putLong(RuleStore.KEY_LAST_REMINDER_AT, System.currentTimeMillis())
                .apply()
        }.isSuccess
}
