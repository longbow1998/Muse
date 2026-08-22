package com.learn.antilazy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 兜底闹钟：即使服务进程被系统杀掉，也会周期性被拉起。
 * ReminderEngine.onAlarm 直接用落盘数据补算进度并弹提醒，
 * 不需要服务存活；监控停用后闹钟链自动终止。
 */
class TickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ReminderEngine.onAlarm(context)) {
            scheduleNext(context)
        }
    }

    companion object {
        private const val REQUEST_CODE = 1001
        private const val INTERVAL_MS = 30_000L

        /** 非唤醒闹钟：灭屏期间顺延，亮屏使用时准时触发 */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, TickReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pi
            )
        }
    }
}
