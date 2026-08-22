package com.learn.antilazy

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 今日各应用使用时长统计。数据仅来自本机 UsageStatsManager，不联网、不上传。 */
class UsageStatsActivity : Activity() {

    private lateinit var tvSummary: TextView
    private lateinit var llApps: LinearLayout
    private lateinit var btnGrant: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_stats)
        tvSummary = findViewById(R.id.tv_usage_summary)
        llApps = findViewById(R.id.ll_usage_apps)
        btnGrant = findViewById(R.id.btn_grant_usage)
        btnGrant.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Suppress("DEPRECATION") // checkOpNoThrow is the only option below API 29.
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun render() {
        val granted = hasUsageAccess()
        btnGrant.visibility = if (granted) View.GONE else View.VISIBLE

        llApps.removeAllViews()
        if (!granted) {
            tvSummary.text = getString(R.string.usage_no_permission)
            return
        }

        val usage = loadTodayForegroundMs()
        if (usage.isEmpty()) {
            tvSummary.text = getString(R.string.usage_empty)
            return
        }

        val sorted = usage.entries.sortedByDescending { it.value }
        val totalMs = sorted.sumOf { it.value }
        tvSummary.text = getString(
            R.string.usage_summary_fmt,
            MonitorService.formatDuration(totalMs),
            sorted.size
        )

        val pm = packageManager
        sorted.forEach { (pkg, ms) ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            addAppRow(label, MonitorService.formatDuration(ms))
        }
    }

    /** 合并当天所有 UsageStats 条目，按包名累计前台时长（毫秒）。 */
    private fun loadTodayForegroundMs(): Map<String, Long> {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()
        if (end <= start) return emptyMap()

        val merged = HashMap<String, Long>()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        }.getOrNull() ?: return emptyMap()
        stats.forEach { s ->
            val ms = s.totalTimeInForeground.coerceAtLeast(0L)
            if (ms > 0L) merged[s.packageName] = (merged[s.packageName] ?: 0L) + ms
        }
        return merged
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addAppRow(label: String, durationText: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val tvName = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvTime = TextView(this).apply {
            text = durationText
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(
            tvName,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(tvTime)
        llApps.addView(row)
    }
}
