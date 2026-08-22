package com.learn.antilazy

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 耗电估算：Android 不向第三方应用开放系统级分应用耗电（BatteryStats），
 * 这里用「电量采样差值 + 前台时长占比」做纯本机估算：
 * 周期性记录剩余电量(µAh)与充电状态；两段采样之间若在放电，
 * 把这段电量下降按各 App 的前台秒数占比分摊。
 */
object BatteryEstimator {

    private const val KEY_SAMPLES = "battery_samples_v1"
    private const val KEY_CAPACITY_MAH = "battery_capacity_mah"
    private const val DEFAULT_CAPACITY_MAH = 5000
    private const val KEEP_MS = 26 * 60 * 60_000L
    private const val MAX_SAMPLES = 700

    data class DayEstimate(
        /** 当天估算总放电量（mAh，含无法归因到 App 的部分）。 */
        val totalDrainMah: Double,
        /** 包名 -> 估算消耗（mAh）。 */
        val perAppMah: Map<String, Double>
    )

    fun capacityMah(context: Context): Int =
        ReminderEngine.prefs(context).getInt(KEY_CAPACITY_MAH, DEFAULT_CAPACITY_MAH)

    fun setCapacityMah(context: Context, mah: Int) {
        ReminderEngine.prefs(context).edit()
            .putInt(KEY_CAPACITY_MAH, mah.coerceIn(500, 30000))
            .apply()
    }

    /** 记录一次电量样本。开销极小，可在服务 tick / 锁屏切换 / watchdog 中调用。 */
    fun takeSample(context: Context) {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        val uah = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrDefault(Int.MIN_VALUE)
        if (uah == Int.MIN_VALUE || uah == 0) return
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        appendSample(context, System.currentTimeMillis(), uah.toLong(), charging)
    }

    private fun appendSample(context: Context, t: Long, uah: Long, charging: Boolean) {
        val prefs = ReminderEngine.prefs(context)
        val arr = runCatching { JSONArray(prefs.getString(KEY_SAMPLES, "[]")) }
            .getOrDefault(JSONArray())
        arr.put(JSONObject().put("t", t).put("u", uah).put("c", charging))
        while (arr.length() > MAX_SAMPLES) arr.remove(0)
        val cutoff = t - KEEP_MS
        var drop = 0
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optLong("t") >= cutoff) break
            drop++
        }
        repeat(drop) { if (arr.length() > 0) arr.remove(0) }
        prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
    }

    private fun readSamples(context: Context): JSONArray =
        runCatching {
            JSONArray(ReminderEngine.prefs(context).getString(KEY_SAMPLES, "[]"))
        }.getOrDefault(JSONArray())

    /** 估算某天各 App 耗电；请在后台线程调用。 */
    fun estimateDay(context: Context, day: LocalDate): DayEstimate {
        val samples = readSamples(context)
        val zone = ZoneId.systemDefault()
        val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = minOf(
            System.currentTimeMillis(),
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
        if (endMs <= startMs || samples.length() < 2) {
            return DayEstimate(0.0, emptyMap())
        }

        // 收集窗口内采样点
        val points = ArrayList<Triple<Long, Long, Boolean>>() // t, uAh, charging
        for (i in 0 until samples.length()) {
            val o = samples.getJSONObject(i)
            val t = o.optLong("t")
            if (t in startMs..endMs) points.add(Triple(t, o.optLong("u"), o.optBoolean("c")))
        }

        var totalDrainUah = 0L
        val perAppUah = HashMap<String, Long>()
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            // 区间结束时在充电则视为“净充入”，不计入消耗
            if (cur.third) continue
            val drainUah = prev.second - cur.second
            if (drainUah <= 0L) continue
            totalDrainUah += drainUah
            val fgSeconds = foregroundSeconds(context, prev.first, cur.first)
            val totalSec = fgSeconds.values.sum()
            if (totalSec <= 0) continue
            fgSeconds.forEach { (pkg, sec) ->
                perAppUah[pkg] = (perAppUah[pkg] ?: 0L) + drainUah * sec / totalSec
            }
        }
        return DayEstimate(
            totalDrainMah = totalDrainUah / 1000.0,
            perAppMah = perAppUah.mapValues { it.value / 1000.0 }
        )
    }

    /** 窗口 [startMs, endMs] 内各包名前台秒数（UsageEvents）。 */
    private fun foregroundSeconds(
        context: Context,
        startMs: Long,
        endMs: Long
    ): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = runCatching { usm.queryEvents(startMs, endMs) }.getOrNull() ?: return emptyMap()
        val seconds = HashMap<String, Long>()
        var currentPkg: String? = null
        var resumedAt = 0L
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (currentPkg != null && e.timeStamp > resumedAt) {
                        addSeconds(seconds, currentPkg!!, resumedAt, e.timeStamp, startMs, endMs)
                    }
                    currentPkg = e.packageName
                    resumedAt = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (e.packageName == currentPkg) {
                        addSeconds(seconds, currentPkg!!, resumedAt, e.timeStamp, startMs, endMs)
                        currentPkg = null
                    }
                }
            }
        }
        if (currentPkg != null) {
            addSeconds(seconds, currentPkg!!, resumedAt, endMs, startMs, endMs)
        }
        return seconds.filterValues { it > 0 }
    }

    private fun addSeconds(
        into: MutableMap<String, Long>,
        pkg: String,
        fromMs: Long,
        toMs: Long,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        val s = maxOf(fromMs, windowStartMs)
        val t = minOf(toMs, windowEndMs)
        if (t <= s) return
        into[pkg] = (into[pkg] ?: 0L) + (t - s) / 1000
    }
}
