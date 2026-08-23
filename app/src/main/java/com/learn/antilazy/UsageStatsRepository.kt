package com.learn.antilazy

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * 本机应用使用时长查询。数据仅来自 UsageStatsManager，不联网、不上传。
 *
 * 不使用系统汇总的 totalTimeInForeground——它在灭屏/锁屏期间往往不结束
 * 最后一个前台应用，会把整夜锁屏时间记到睡前最后一个 App 上。
 * 这里改为重放 UsageEvents 原始事件：RESUMED/PAUSED 组成前台段，
 * 并用 SCREEN_* / KEYGUARD_* 事件（API 30+）在灭屏或锁屏瞬间强制结束
 * 当前段，保证灭屏/锁屏期间一毫秒都不计入。桌面/系统UI/本应用不计入。
 */
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

    /** 需要排除的非“使用”包：本应用、桌面、系统UI。 */
    private fun excludedPackages(context: Context): Set<String> {
        val set = HashSet<String>()
        set.add(context.packageName)
        set.add("com.android.systemui")
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            resolved?.activityInfo?.packageName?.let { set.add(it) }
        }
        return set
    }

    /** 某一天各包名前台时长（毫秒）；未来日期或无数据返回空 Map。后台线程调用。 */
    fun loadDayForegroundMs(context: Context, day: LocalDate): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val start = dayStartMillis(day)
        val endExclusive = dayStartMillis(day.plusDays(1))
        val clampedEnd = minOf(endExclusive, maxOf(System.currentTimeMillis(), start))
        if (clampedEnd <= start) return emptyMap()

        val excluded = excludedPackages(context)
        val events = runCatching { usm.queryEvents(start, clampedEnd) }.getOrNull()
            ?: return emptyMap()

        val merged = HashMap<String, Long>()
        var currentPkg: String? = null
        var currentSince = 0L
        // 会话可用 = 屏幕交互且未在锁屏；API<30 无这些事件时保持 true（退化为原始行为）
        var sessionUsable = true
        val e = UsageEvents.Event()

        fun closeSegment(uptoMs: Long) {
            val pkg = currentPkg ?: return
            if (pkg !in excluded && uptoMs > currentSince) {
                val ms = uptoMs - currentSince
                merged[pkg] = (merged[pkg] ?: 0L) + ms
            }
            currentPkg = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp.coerceIn(start, clampedEnd)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    closeSegment(t)
                    if (sessionUsable) {
                        currentPkg = e.packageName
                        currentSince = t
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (e.packageName == currentPkg) closeSegment(t)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> {
                    sessionUsable = false
                    closeSegment(t)
                }
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    sessionUsable = true
                }
            }
        }
        closeSegment(clampedEnd)
        return merged.filterValues { it > 0 }
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
