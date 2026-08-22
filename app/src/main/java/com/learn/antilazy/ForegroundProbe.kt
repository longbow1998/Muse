package com.learn.antilazy

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 前台应用感知：提醒触发时附带“正在用哪个 App、今天用了多久”。 */
object ForegroundProbe {

    private const val LOOKBACK_MS = 30 * 60_000L

    data class Info(
        val packageName: String,
        val label: String,
        val todayMs: Long
    )

    fun probe(context: Context): Info? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val pm = context.packageManager
        val launchers = launcherPackages(pm)
        val end = System.currentTimeMillis()
        val start = end - LOOKBACK_MS

        var currentPkg: String? = null
        val eventsOk = runCatching {
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> currentPkg = event.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        if (currentPkg == event.packageName) currentPkg = null
                }
            }
        }.isSuccess
        if (!eventsOk) return null

        val pkg = currentPkg ?: return null
        if (pkg == context.packageName || pkg in launchers || pkg.endsWith(".systemui")) return null

        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val todayMs = UsageStatsRepository
            .loadDayForegroundMs(context, java.time.LocalDate.now())[pkg] ?: 0L
        return Info(pkg, label, todayMs)
    }

    /** 生成附加到提醒正文的上下文行；信息不足或权限未授权时返回 null。 */
    fun describe(context: Context): String? {
        val info = probe(context) ?: return null
        return if (info.todayMs >= 60_000L) {
            context.getString(
                R.string.context_app_fmt,
                info.label,
                MonitorService.formatDuration(context, info.todayMs)
            )
        } else {
            context.getString(R.string.context_app_only_fmt, info.label)
        }
    }

    private fun launcherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return setOfNotNull(resolved?.activityInfo?.packageName)
    }
}
