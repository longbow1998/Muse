package com.learn.antilazy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Notification and overlay delivery. A reminder succeeds only when at least one is available. */
object Notifier {

    const val CHANNEL_ID_MONITOR = "monitor_v2"
    private const val CHANNEL_ID_REMINDER = "reminder"
    private const val CHANNEL_ID_HEALTH = "monitor_health"
    private const val HEALTH_NOTIFICATION_ID = 2
    private const val REMINDER_NOTIFICATION_BASE = 1000

    /** 通知文案跟随应用内语言设置；服务/广播传入的原始 Context 在此统一包装。 */
    private fun localized(context: Context): Context = LanguageUtils.wrap(context)

    fun ensureChannels(context: Context) {
        val manager = localized(context).getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MONITOR,
                context.getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
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
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_HEALTH,
                context.getString(R.string.channel_health),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_health_desc)
            }
        )
    }

    fun canPostReminders(context: Context): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID_REMINDER)
        return manager.areNotificationsEnabled() &&
            channel != null &&
            channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun canDeliverReminder(context: Context): Boolean =
        OverlayReminder.canShow(context) || canPostReminders(context)

    fun fireReminder(context: Context, ruleId: Long, title: String, text: String): Boolean {
        val ctx = localized(context)
        ensureChannels(ctx)
        val overlayShown = OverlayReminder.show(ctx, title, text)
        val notificationPosted = if (canPostReminders(ctx)) {
            postReminderNotification(ctx, ruleId, title, text)
        } else {
            false
        }
        if (overlayShown && !notificationPosted) alertManually(ctx)

        val delivered = overlayShown || notificationPosted
        if (delivered) {
            val prefs = ReminderEngine.prefs(ctx)
            prefs.edit()
                .putInt(
                    RuleStore.KEY_REMIND_COUNT,
                    prefs.getInt(RuleStore.KEY_REMIND_COUNT, 0) + 1
                )
                .putLong(RuleStore.KEY_LAST_REMINDER_AT, System.currentTimeMillis())
                .apply()
        }
        return delivered
    }

    fun showMonitorStoppedWarning(context: Context): Boolean {
        val ctx = localized(context)
        ensureChannels(ctx)
        val title = ctx.getString(R.string.health_title)
        val text = ctx.getString(R.string.health_text)
        val overlayShown = OverlayReminder.show(ctx, title, text)
        val notificationPosted = postHealthNotification(ctx, title, text)
        if (overlayShown && !notificationPosted) alertManually(context)
        return overlayShown || notificationPosted
    }

    fun dismissMonitorStoppedWarning(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(HEALTH_NOTIFICATION_ID)
    }

    private fun postReminderNotification(
        context: Context,
        ruleId: Long,
        title: String,
        text: String
    ): Boolean = runCatching {
        val notificationId = reminderNotificationId(ruleId)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, ReminderActivity::class.java)
                .putExtra(ReminderActivity.EXTRA_TEXT, text)
                .putExtra(ReminderActivity.EXTRA_NOTIF_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(NotificationManager::class.java)?.notify(
            notificationId,
            Notification.Builder(context, CHANNEL_ID_REMINDER)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
        ) ?: error("NotificationManager unavailable")
    }.isSuccess

    private fun postHealthNotification(context: Context, title: String, text: String): Boolean =
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: error("NotificationManager unavailable")
            if (!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL_ID_HEALTH)?.importance ==
                NotificationManager.IMPORTANCE_NONE
            ) return@runCatching false
            val contentIntent = PendingIntent.getActivity(
                context,
                HEALTH_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                HEALTH_NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID_HEALTH)
                    .setSmallIcon(R.drawable.ic_stat_reminder)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
            )
            true
        }.getOrDefault(false)

    private fun reminderNotificationId(ruleId: Long): Int {
        val positive = (ruleId xor (ruleId ushr 32)).toInt() and 0x3fffffff
        return REMINDER_NOTIFICATION_BASE + positive
    }

    private fun alertManually(context: Context) {
        runCatching {
            RingtoneManager.getRingtone(
                context.applicationContext,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1)
            )
        }
    }
}
