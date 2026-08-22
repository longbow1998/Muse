package com.learn.antilazy

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** 本机应用使用时长查询。数据仅来自 UsageStatsManager，不联网、不上传。 */
object UsageStatsRepository {

    data class RangeData(
        /** 每日总时长，按日期升序（含今天）。 */
        val dailyTotalsMs: List<Long>,
        /** 时段内各包名累计前台时长。 */
        val perAppTotalsMs: Map<String, Long>
    )

    @Suppress("DEPRECATION") // checkOpNoThrow is the only option below API 29.
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun dayStartMillis(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 某一天各包名前台时长（毫秒）；未来日期或无数据返回空 Map。 */
    fun loadDayForegroundMs(context: Context, day: LocalDate): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val start = dayStartMillis(day)
        val endExclusive = dayStartMillis(day.plusDays(1))
        val clampedEnd = minOf(endExclusive, maxOf(System.currentTimeMillis(), start))
        if (clampedEnd <= start) return emptyMap()

        val merged = HashMap<String, Long>()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, clampedEnd)
        }.getOrNull() ?: return emptyMap()
        stats.forEach { s ->
            val ms = s.totalTimeInForeground.coerceAtLeast(0L)
            if (ms > 0L) merged[s.packageName] = (merged[s.packageName] ?: 0L) + ms
        }
        return merged
    }

    /**
     * 近 N 天（含今天）的每日总量与各 App 汇总。
     * 每天一次独立查询保证本地时区边界准确；请在后台线程调用。
     */
    fun loadRange(context: Context, days: Int): RangeData {
        require(days > 0)
        val today = LocalDate.now()
        val totals = ArrayList<Long>(days)
        val perApp = HashMap<String, Long>()
        for (offset in days - 1 downTo 0) {
            val dayMap = loadDayForegroundMs(context, today.minusDays(offset.toLong()))
            totals.add(dayMap.values.sum())
            dayMap.forEach { (pkg, ms) -> perApp[pkg] = (perApp[pkg] ?: 0L) + ms }
        }
        return RangeData(totals, perApp)
    }
}
