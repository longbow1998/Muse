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
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var switchToggle: Switch
    private lateinit var tvStatus: TextView
    private lateinit var btnBattery: Button

    private val handler = Handler(Looper.getMainLooper())

    private val statusUpdater = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchToggle = findViewById(R.id.sw_toggle)
        tvStatus = findViewById(R.id.tv_status)
        btnBattery = findViewById(R.id.btn_battery)

        switchToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                ensureNotificationPermission()
                MonitorService.start(this)
            } else {
                MonitorService.stop(this)
            }
            handler.post(statusUpdater)
        }

        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onResume() {
        super.onResume()
        ensureNotificationPermission()
        switchToggle.isChecked = MonitorService.isRunning
        handler.post(statusUpdater)
        renderBatteryButton()
    }

    override fun onPause() {
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    private fun renderStatus() {
        if (switchToggle.isChecked != MonitorService.isRunning) {
            switchToggle.isChecked = MonitorService.isRunning
        }
        tvStatus.text = when {
            !MonitorService.isRunning -> getString(R.string.status_stopped)
            !MonitorService.isUnlocked -> getString(R.string.status_locked_paused)
            else -> getString(
                R.string.status_running_fmt,
                MonitorService.formatDuration(MonitorService.activeMs),
                MonitorService.formatDuration(MonitorService.INTERVAL_MS - MonitorService.activeMs)
            )
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
