package com.learn.antilazy

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/** 到点全屏弹出的提醒页：盖在任何应用之上，20 秒后自动关闭 */
class ReminderActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        private const val AUTO_FINISH_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val autoFinish = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.default_reminder_text)
        findViewById<TextView>(R.id.tv_reminder_text).text = text

        // 全屏已展示，清掉通知栏对应卡片，避免历史卡片无限堆叠
        // （全屏被系统降级为横幅时本页不会启动，卡片自然保留作为痕迹）
        intent.getIntExtra(EXTRA_NOTIF_ID, 0).takeIf { it > 0 }?.let {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(it)
        }

        findViewById<Button>(R.id.btn_dismiss).setOnClickListener { finish() }

        // 点击遮罩任意空白处同样关闭
        findViewById<android.view.View>(R.id.reminder_root).setOnClickListener { finish() }

        handler.postDelayed(autoFinish, AUTO_FINISH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoFinish)
        super.onDestroy()
    }
}
