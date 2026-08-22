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
        private const val AUTO_FINISH_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val autoFinish = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(R.string.default_reminder_text)
        findViewById<TextView>(R.id.tv_reminder_text).text = text

        findViewById<Button>(R.id.btn_dismiss).setOnClickListener { finish() }

        handler.postDelayed(autoFinish, AUTO_FINISH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoFinish)
        super.onDestroy()
    }
}
