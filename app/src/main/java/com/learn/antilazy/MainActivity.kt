package com.learn.antilazy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        /** 发起启动后的宽限期，期间不判定"服务没起来" */
        private const val START_GRACE_MS = 3000L
    }

    private lateinit var switchToggle: Switch
    private lateinit var tvStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnTest: Button

    private val handler = Handler(Looper.getMainLooper())

    /** 用户期望的目标状态；UI 只跟随它，避免和服务异步状态互相打架 */
    private var desiredRunning = false
    private var lastStartRequestAt = 0L

    private val statusUpdater = object : Runnable {
        override fun run() {
            renderStatusText()
            reconcileService()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchToggle = findViewById(R.id.sw_toggle)
        tvStatus = findViewById(R.id.tv_status)
        btnBattery = findViewById(R.id.btn_battery)
        btnTest = findViewById(R.id.btn_test)

        switchToggle.setOnCheckedChangeListener { _, checked ->
            // 去抖：程序化设置 isChecked 或重复点击同一状态时不重复启停
            if (checked == desiredRunning) return@setOnCheckedChangeListener
            desiredRunning = checked
            if (checked) {
                ensureNotificationPermission()
                requestStart(showToastOnFail = true)
            } else {
                MonitorService.stop(this)
            }
        }

        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        btnTest.setOnClickListener {
            if (!MonitorService.isRunning || !MonitorService.sendTestReminder()) {
                Toast.makeText(this, R.string.test_hint_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureNotificationPermission()
        desiredRunning = MonitorService.isRunning
        switchToggle.isChecked = desiredRunning
        handler.post(statusUpdater)
        renderBatteryButton()
    }

    override fun onPause() {
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    /** 状态文本实时刷新；绝不回写开关控件，避免和用户点击竞争 */
    private fun renderStatusText() {
        val sb = StringBuilder()

        sb.append(
            when {
                !MonitorService.isRunning -> getString(R.string.status_stopped)
                !MonitorService.isUnlocked -> getString(R.string.status_locked_paused)
                else -> getString(
                    R.string.status_running_fmt,
                    MonitorService.formatDuration(MonitorService.activeMs),
                    MonitorService.formatDuration(MonitorService.INTERVAL_MS - MonitorService.activeMs)
                )
            }
        )

        if (MonitorService.isRunning) {
            sb.append('\n')
            val last = MonitorService.lastReminderAt
            sb.append(
                if (last > 0) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(last))
                    getString(R.string.status_last_reminder_fmt, time, MonitorService.reminderCount)
                } else {
                    getString(R.string.status_no_reminder_yet)
                }
            )
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sb.append('\n').append(getString(R.string.notif_denied_warn))
        }

        tvStatus.text = sb.toString()
    }

    /**
     * 自愈：用户要开、但服务没起来（启动竞态或被系统杀），
     * 宽限期外自动补一次启动。
     */
    private fun reconcileService() {
        if (!desiredRunning || MonitorService.isRunning) return
        if (SystemClock.elapsedRealtime() - lastStartRequestAt < START_GRACE_MS) return
        requestStart(showToastOnFail = false)
    }

    private fun requestStart(showToastOnFail: Boolean) {
        lastStartRequestAt = SystemClock.elapsedRealtime()
        val ok = MonitorService.start(this)
        if (!ok) {
            desiredRunning = false
            if (switchToggle.isChecked) switchToggle.isChecked = false
            if (showToastOnFail) {
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun renderBatteryButton() {
        val ignoring = batteryManager().isIgnoringBatteryOptimizations(packageName)
        btnBattery.isEnabled = !ignoring
        btnBattery.text = getString(if (ignoring) R.string.battery_done else R.string.battery_request)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = batteryManager()
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun batteryManager(): PowerManager = getSystemService(PowerManager::class.java)
}
