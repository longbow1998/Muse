package com.learn.antilazy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

        private const val MIN_MINUTES = 1
        private const val MAX_MINUTES = 720
    }

    private lateinit var switchToggle: Switch
    private lateinit var tvStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnTest: Button
    private lateinit var btnAddRule: Button
    private lateinit var btnGuide: Button
    private lateinit var llRules: LinearLayout

    private val handler = Handler(Looper.getMainLooper())

    /** 用户期望的目标状态；UI 只跟随它，避免和服务异步状态互相打架 */
    private var desiredRunning = false
    private var lastStartRequestAt = 0L

    /** 规则列表的本地事实源（编辑后立即回显，不依赖服务是否运行） */
    private var uiRules: List<Rule> = emptyList()
    private var rowsSignature = ""
    private var snapElapsed: Map<Long, Long> = emptyMap()

    private class RowH(val id: Long, val tvSub: TextView)

    private val rowViews = mutableListOf<RowH>()

    private val statusUpdater = object : Runnable {
        override fun run() {
            refreshFromService()
            renderStatusText()
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
        btnAddRule = findViewById(R.id.btn_add_rule)
        btnGuide = findViewById(R.id.btn_guide)
        llRules = findViewById(R.id.ll_rules)

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
            if (!MonitorService.sendTestReminder(this)) {
                Toast.makeText(this, R.string.test_hint_toast, Toast.LENGTH_SHORT).show()
            }
        }

        btnAddRule.setOnClickListener { openEditor(existing = null) }

        findViewById<Button>(R.id.btn_guide).setOnClickListener { showKeepAliveGuide() }

        uiRules = RuleStore.load(this)
        rebuildRows()
    }

    override fun onResume() {
        super.onResume()
        ensureNotificationPermission()
        // 以落盘的运行标记为准：进程被杀后 companion 变量失真，
        // 而兜底闹钟仍在按标记计时
        desiredRunning = MonitorService.wasRunningBefore(this)
        switchToggle.isChecked = desiredRunning
        handler.post(statusUpdater)
        renderBatteryButton()
    }

    override fun onPause() {
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    /** 拉取服务的实时快照（各规则已计时长），必要时同步规则结构 */
    private fun refreshFromService() {
        snapElapsed = MonitorService.snapshot(this).associate { it.id to it.elapsedMs }
        syncRowsIfNeeded()
    }

    // ---------- 规则列表 ----------

    private fun applyRules(newRules: List<Rule>) {
        uiRules = newRules
        MonitorService.setRules(this, newRules)
        rowsSignature = ""
        syncRowsIfNeeded()
    }

    private fun toggleRule(id: Long, enabled: Boolean) {
        applyRules(uiRules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun deleteRule(id: Long) {
        applyRules(uiRules.filter { it.id != id })
        Toast.makeText(this, R.string.toast_rule_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 结构（内容/间隔/启用/增删）变化才重建行；倒计时数字走轻量文本更新 */
    private fun syncRowsIfNeeded() {
        val sig = uiRules.joinToString("|") { "${it.id},${it.enabled},${it.intervalMinutes},${it.text}" }
        if (sig == rowsSignature) return
        rowsSignature = sig
        rebuildRows()
    }

    private fun rebuildRows() {
        llRules.removeAllViews()
        rowViews.clear()
        uiRules.forEach { rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tvMain = TextView(this).apply {
                text = rule.text
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvSub = TextView(this).apply {
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            }
            col.addView(tvMain)
            col.addView(tvSub)

            val sw = Switch(this).apply {
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked -> toggleRule(rule.id, checked) }
            }

            row.addView(
                col,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            row.addView(sw)

            row.setOnClickListener { openEditor(uiRules.firstOrNull { it.id == rule.id }) }

            llRules.addView(row)
            rowViews.add(RowH(rule.id, tvSub))
        }
        updateCountdownTexts()
        if (uiRules.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_enabled_rules)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            }
            llRules.addView(empty)
        }
    }

    private fun updateCountdownTexts() {
        uiRules.forEach { rule ->
            val tvSub = rowViews.firstOrNull { it.id == rule.id }?.tvSub ?: return@forEach
            tvSub.text =
                if (rule.enabled) {
                    val remaining = (rule.intervalMinutes * 60_000L - (snapElapsed[rule.id] ?: 0L))
                        .coerceAtLeast(0L)
                    getString(
                        R.string.rule_sub_fmt,
                        rule.intervalMinutes,
                        MonitorService.formatDuration(remaining)
                    )
                } else {
                    getString(R.string.rule_sub_disabled, rule.intervalMinutes)
                }
        }
    }

    private fun renderStatusText() {
        updateCountdownTexts()

        val sb = StringBuilder()
        val active = MonitorService.wasRunningBefore(this)

        sb.append(
            when {
                !active -> getString(R.string.status_stopped)
                !MonitorService.isRunning ->
                    if (ReminderEngine.isUnlockedNow(this)) {
                        getString(R.string.status_service_dead)
                    } else {
                        getString(R.string.status_locked_paused)
                    }
                !MonitorService.isUnlocked -> getString(R.string.status_locked_paused)
                else -> {
                    val enabled = uiRules.count { it.enabled }
                    if (enabled == 0) {
                        getString(R.string.no_enabled_rules)
                    } else {
                        val nextMs = uiRules.filter { it.enabled }.minOf { rule ->
                            (rule.intervalMinutes * 60_000L - (snapElapsed[rule.id] ?: 0L))
                                .coerceAtLeast(0L)
                        }
                        getString(R.string.master_summary_fmt, enabled, MonitorService.formatDuration(nextMs))
                    }
                }
            }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sb.append('\n').append(getString(R.string.notif_denied_warn))
        }

        if (active && Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.canUseFullScreenIntent() == false) {
                sb.append('\n').append(getString(R.string.fsr_denied_hint))
            }
        }

        if (active) {
            // 计时停滞自检：监控开启且屏幕解锁，但墙钟超过 5 分钟没走动，
            // 说明服务与闹钟都被系统拦截了（常见于强停/深度休眠）
            if (ReminderEngine.isUnlockedNow(this) &&
                ReminderEngine.lastTickAgeMs(this) > 5 * 60_000L
            ) {
                sb.append('\n').append(getString(R.string.status_stalled_warn))
            }
            val last = ReminderEngine.prefs(this).getLong(RuleStore.KEY_LAST_REMINDER_AT, 0L)
            sb.append('\n')
            sb.append(
                if (last > 0) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(last))
                    getString(
                        R.string.status_last_reminder_fmt,
                        time,
                        ReminderEngine.prefs(this).getInt(RuleStore.KEY_REMIND_COUNT, 0)
                    )
                } else {
                    getString(R.string.status_no_reminder_yet)
                }
            )
        }

        tvStatus.text = sb.toString()
    }

    /** 软键盘弹出时避免按钮被遮挡：对话框内容包一层滚动容器 */
    private fun scrollWrap(child: android.view.View): android.widget.ScrollView =
        android.widget.ScrollView(this).apply { addView(child) }

    private fun showKeepAliveGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.keepalive_title)
            .setMessage(R.string.keepalive_steps)
            .setPositiveButton(R.string.keepalive_ok, null)
            .setNeutralButton(R.string.keepalive_open_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .show()
    }

    // ---------- 编辑对话框 ----------

    private fun openEditor(existing: Rule?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        fun label(textRes: Int): TextView = TextView(this).apply {
            text = getString(textRes)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_secondary))
        }

        val etText = EditText(this).apply {
            hint = getString(R.string.editor_hint_text)
            setText(existing?.text ?: "")
            minLines = 2
            gravity = Gravity.TOP
        }
        val etMinutes = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.intervalMinutes?.toString() ?: RuleStore.DEFAULT_INTERVAL_MINUTES.toString())
        }

        container.addView(label(R.string.editor_label_text))
        container.addView(etText)
        container.addView(label(R.string.editor_label_minutes).apply { setPadding(0, dp(16), 0, 0) })
        container.addView(etMinutes)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.editor_title_new else R.string.editor_title_edit))
            .setView(scrollWrap(container))
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = Button.GONE
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = Button.GONE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = Button.GONE

        val btnSave = Button(this).apply { setText(R.string.editor_save) }
        val btnDelete = Button(this).apply {
            setText(R.string.editor_delete)
            visibility = if (existing == null) Button.GONE else Button.VISIBLE
        }
        val btnCancel = Button(this).apply {
            setText(R.string.editor_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnDelete)
        btnRow.addView(btnSave)
        container.addView(btnRow)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            existing?.let { deleteRule(it.id) }
            dialog.dismiss()
        }
        btnSave.setOnClickListener {
            val text = etText.text.toString().trim()
            val minutes = etMinutes.text.toString().toIntOrNull()
            when {
                text.isEmpty() ->
                    Toast.makeText(this, R.string.toast_need_text, Toast.LENGTH_SHORT).show()
                minutes == null || minutes < MIN_MINUTES || minutes > MAX_MINUTES ->
                    Toast.makeText(this, R.string.toast_bad_minutes, Toast.LENGTH_SHORT).show()
                else -> {
                    val newRule = Rule(
                        id = existing?.id ?: RuleStore.nextId(this, uiRules),
                        intervalMinutes = minutes,
                        text = text,
                        enabled = existing?.enabled ?: true
                    )
                    applyRules(
                        if (existing == null) uiRules + newRule
                        else uiRules.map { if (it.id == existing.id) newRule else it }
                    )
                    dialog.dismiss()
                }
            }
        }
    }

    // ---------- 监控开关与权限 ----------

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
