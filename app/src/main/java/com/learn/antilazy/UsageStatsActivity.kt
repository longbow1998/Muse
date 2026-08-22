package com.learn.antilazy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 今日/近7天/近30天使用统计：柱状图趋势 + 各 App 占比列表。数据仅在本机计算。 */
class UsageStatsActivity : Activity() {

    private companion object {
        val TAB_IDS = intArrayOf(R.id.tv_tab_day, R.id.tv_tab_week, R.id.tv_tab_month)
        val MODE_DAYS = intArrayOf(1, 7, 30)
        const val CHART_WINDOW_FOR_DAY = 7
    }

    private lateinit var llTabs: LinearLayout
    private lateinit var tabViews: List<TextView>
    private lateinit var tvSummary: TextView
    private lateinit var llApps: LinearLayout
    private lateinit var btnGrant: Button
    private lateinit var btnSetCapacity: Button
    private lateinit var chart: UsageBarChartView
    private lateinit var llBattery: LinearLayout

    private var modeIndex = 0
    private var loadSeq = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageUtils.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_stats)
        llTabs = findViewById(R.id.ll_tabs)
        tabViews = TAB_IDS.map { findViewById(it) }
        tvSummary = findViewById(R.id.tv_usage_summary)
        llApps = findViewById(R.id.ll_usage_apps)
        btnGrant = findViewById(R.id.btn_grant_usage)
        btnSetCapacity = findViewById(R.id.btn_set_capacity)
        llBattery = findViewById(R.id.ll_battery)
        chart = findViewById(R.id.usage_chart)

        btnSetCapacity.setOnClickListener { showCapacityDialog() }

        tabViews.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                if (modeIndex != index) {
                    modeIndex = index
                    render()
                }
            }
        }
        btnGrant.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Suppress("DEPRECATION") // checkOpNoThrow is the only option below API 29.
    private fun hasUsageAccess(): Boolean =
        UsageStatsRepository.hasUsageAccess(this)

    private fun render() {
        val granted = hasUsageAccess()
        btnGrant.visibility = if (granted) View.GONE else View.VISIBLE
        renderTabSelection()

        if (!granted) {
            tvSummary.text = getString(R.string.usage_no_permission)
            chart.setData(emptyList(), emptyList(), -1)
            llApps.removeAllViews()
            return
        }
        loadAsync()
    }

    private fun renderTabSelection() {
        tabViews.forEachIndexed { index, tab -> tab.isSelected = index == modeIndex }
    }

    private fun loadAsync() {
        val seq = ++loadSeq
        val days = MODE_DAYS[modeIndex]
        Thread {
            BatteryEstimator.takeSample(this)
            val model = runCatching { buildModel(days) }.getOrNull()
            val battery = runCatching {
                BatteryEstimator.estimateDay(this, LocalDate.now())
            }.getOrNull()
            runOnUiThread {
                if (seq == loadSeq && !isDestroyed) {
                    bindModel(model)
                    bindBattery(battery)
                }
            }
        }.start()
    }

    private fun buildModel(days: Int): Model {
        val today = LocalDate.now()
        return if (days == 1) {
            val todayMap = UsageStatsRepository.loadDayForegroundMs(this, today)
            val prevTotalsAsc = (CHART_WINDOW_FOR_DAY downTo 1).map { offset ->
                UsageStatsRepository
                    .loadDayForegroundMs(this, today.minusDays(offset.toLong()))
                    .values.sum()
            }
            val chartValues = prevTotalsAsc + listOf(todayMap.values.sum())
            Model(
                summary = buildDaySummary(todayMap.values.sum(), todayMap.size, prevTotalsAsc),
                chartValues = chartValues,
                chartHighlight = chartValues.lastIndex,
                chartLabels = shortLabels(chartValues.size),
                apps = todayMap.entries.sortedByDescending { it.value }
                    .map { AppRow(appLabel(it.key), it.value) },
                emptyTextRes = R.string.usage_empty
            )
        } else {
            val range = UsageStatsRepository.loadRange(this, days)
            val total = range.dailyTotalsMs.sum()
            val avg = total / days
            Model(
                summary = getString(
                    R.string.usage_range_summary_fmt,
                    days,
                    MonitorService.formatDuration(this, total),
                    MonitorService.formatDuration(this, avg),
                    range.perAppTotalsMs.size
                ),
                chartValues = range.dailyTotalsMs,
                chartHighlight = -1,
                chartLabels = sparseLabels(range.dailyTotalsMs.size),
                apps = range.perAppTotalsMs.entries.sortedByDescending { it.value }
                    .map { AppRow(appLabel(it.key), it.value) },
                emptyTextRes = R.string.usage_empty_range
            )
        }
    }

    private data class AppRow(val label: String, val ms: Long)

    private data class Model(
        val summary: String,
        val chartValues: List<Long>,
        val chartHighlight: Int,
        val chartLabels: List<String>,
        val apps: List<AppRow>,
        val emptyTextRes: Int
    )

    private fun buildDaySummary(totalMs: Long, appCount: Int, prevTotalsAsc: List<Long>): String {
        val base = getString(R.string.usage_summary_fmt, MonitorService.formatDuration(this, totalMs), appCount)
        val prevTotal = prevTotalsAsc.sum()
        if (prevTotal <= 0L || prevTotalsAsc.isEmpty()) return base
        val diff = totalMs - prevTotal.toDouble() / prevTotalsAsc.size
        val compare = when {
            diff <= -60_000 -> getString(R.string.usage_less_than_avg_fmt, MonitorService.formatDuration(this, (-diff).toLong()))
            diff >= 60_000 -> getString(R.string.usage_more_than_avg_fmt, MonitorService.formatDuration(this, diff.toLong()))
            else -> getString(R.string.usage_compare_flat)
        }
        return "$base\n$compare"
    }

    /** 近 N 天标签：每天显示星期几，最后一天固定“今天”。 */
    private fun shortLabels(count: Int): List<String> {
        val weekdays = resources.getStringArray(R.array.weekdays_short)
        val today = LocalDate.now()
        return (count - 1 downTo 0).map { offset ->
            when {
                offset == 0 -> getString(R.string.today_label)
                else -> weekdays[today.minusDays(offset.toLong()).dayOfWeek.value - 1]
            }
        }
    }

    /** 长区间稀疏标签：每周一个刻度 + 最后一天“今天”。 */
    private fun sparseLabels(count: Int): List<String> {
        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofPattern("M/d")
        return (count - 1 downTo 0).map { offset ->
            when {
                offset == 0 -> getString(R.string.today_label)
                offset % 7 == 0 -> today.minusDays(offset.toLong()).format(fmt)
                else -> ""
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun bindModel(model: Model?) {
        if (model == null) {
            tvSummary.text = getString(R.string.usage_empty_range)
            chart.setData(emptyList(), emptyList(), -1)
            llApps.removeAllViews()
            return
        }
        tvSummary.text = model.summary
        chart.setData(model.chartValues, model.chartLabels, model.chartHighlight)

        llApps.removeAllViews()
        if (model.apps.isEmpty()) {
            llApps.addView(TextView(this).apply {
                text = getString(model.emptyTextRes)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val maxMs = model.apps.maxOf { it.ms }.coerceAtLeast(1L)
        model.apps.forEach { row -> llApps.addView(buildAppRow(row.label, row.ms, row.ms.toFloat() / maxMs)) }
    }

    private fun buildAppRow(label: String, durationMs: Long, fraction: Float): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvName = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvTime = TextView(this).apply {
            text = MonitorService.formatDuration(this@UsageStatsActivity, durationMs)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            typeface = Typeface.DEFAULT_BOLD
        }
        head.addView(tvName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tvTime)

        val bar = ShareBarView(this)
        bar.fraction = fraction

        container.addView(head)
        container.addView(
            bar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(5)
            }
        )
        return container
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 耗电估算 ----------

    private fun bindBattery(estimate: BatteryEstimator.DayEstimate?) {
        llBattery.removeAllViews()
        if (estimate == null) {
            addBatteryText(getString(R.string.battery_empty))
            return
        }
        val capacity = BatteryEstimator.capacityMah(this)
        val capacityPct = if (capacity > 0) estimate.totalDrainMah / capacity * 100 else 0.0
        addBatteryText(
            getString(
                R.string.battery_summary_fmt,
                trimNum(estimate.totalDrainMah),
                trimNum(capacityPct)
            ),
            bold = true
        )
        val sorted = estimate.perAppMah.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) {
            addBatteryText(getString(R.string.battery_empty))
            return
        }
        sorted.forEach { (pkg, mah) ->
            val pct = if (capacity > 0) mah / capacity * 100 else 0.0
            addBatteryText("${appLabel(pkg)} · ${trimNum(mah)} mAh（${trimNum(pct)}%）")
        }
    }

    private fun trimNum(v: Double): String {
        return if (v >= 100) v.toInt().toString()
        else String.format(java.util.Locale.CHINESE, "%.1f", v).removeSuffix(".0").let {
            if (it.endsWith(".0")) it.dropLast(2) else it
        }
    }

    private fun addBatteryText(text: String, bold: Boolean = false) {
        llBattery.addView(TextView(this).apply {
            this.text = text
            textSize = if (bold) 15f else 13f
            setTextColor(getColor(if (bold) R.color.text_primary else R.color.text_secondary))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    private fun showCapacityDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.battery_capacity_hint)
            setText(BatteryEstimator.capacityMah(this@UsageStatsActivity).toString())
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.battery_capacity_title,
                    BatteryEstimator.capacityMah(this)
                )
            )
            .setView(container)
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value in 500..30000) {
                    BatteryEstimator.setCapacityMah(this, value)
                    Toast.makeText(this, R.string.battery_capacity_saved, Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    Toast.makeText(this, R.string.battery_capacity_invalid, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
