package com.learn.antilazy

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log

/**
 * 计时核心：前台服务的秒级 tick 与兜底闹钟两条路径共用。
 *
 * 通过消费同一份墙钟时间戳（KEY_LAST_TICK_WALL）保证两条路径
 * 不重不漏：谁先跑谁消费增量，服务存活时秒级 tick 消费掉全部增量，
 * 进程被杀后由后到的闹钟一次性补算。
 *
 * 锁定状态机跨进程生死跟踪"解锁→锁定→解锁"，实现
 * 「锁屏超过 LOCK_RESET_MS 解锁后进度清零」规则。
 *
 * 发提醒经 Notifier 完成，完全不依赖服务进程存活。
 */
object ReminderEngine {

    /** 锁屏超过该时长，解锁后所有规则进度清零重新计 */
    const val LOCK_RESET_MS = 60_000L

    /** 单次补算的进度上限，防止异常大跳 */
    private const val MAX_CATCHUP_MS = 5 * 60 * 1000L

    private const val MIN_DELTA_MS = 500L

    /**
     * 增量超过该值说明设备休眠期间闹钟没触发过（非唤醒闹钟灭屏会顺延），
     * 这段时间必然处于锁屏/灭屏，不得计为使用量：直接按锁屏重置处理。
     * 正常场景（用户使用中）闹钟每 30 秒都能触发，delta 不会超过 ~35 秒。
     */
    private const val GAP_SUSPICIOUS_MS = 90_000L

    private const val KEY_LAST_TICK_WALL = "last_tick_wall"
    private const val KEY_WAS_UNLOCKED = "was_unlocked"
    private const val KEY_LOCKED_AT = "locked_at"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(RuleStore.PREFS_NAME, Context.MODE_PRIVATE)

    fun isUnlockedNow(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** 取走自上次计时以来的墙钟增量（仅主线程调用） */
    fun consumeDelta(prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()): Long {
        val last = prefs.getLong(KEY_LAST_TICK_WALL, 0L)
        prefs.edit().putLong(KEY_LAST_TICK_WALL, nowMs).apply()
        if (last <= 0L) return 0L
        return (nowMs - last).coerceIn(0L, MAX_CATCHUP_MS)
    }

    fun markWallClock(prefs: SharedPreferences) {
        prefs.edit().putLong(KEY_LAST_TICK_WALL, System.currentTimeMillis()).apply()
    }

    /** consumeDelta + 休眠间隙校正的结果 */
    class DeltaResult(val deltaMs: Long, val resetProgress: Boolean)

    /**
     * 统一的增量消费入口（服务 tick 与兜底闹钟共用）：
     * 间隙超过 GAP_SUSPICIOUS_MS 说明期间设备休眠/进程冻结
     * （正常使用中闹钟与 tick 恒 ≤35s 一跳），该段时间不得计为
     * 使用量——返回 resetProgress=true 由调用方清零全部进度。
     */
    fun consumeDeltaSanitized(prefs: SharedPreferences): DeltaResult {
        val delta = consumeDelta(prefs)
        return if (delta > GAP_SUSPICIOUS_MS) {
            DeltaResult(0L, resetProgress = true)
        } else {
            DeltaResult(delta, resetProgress = false)
        }
    }

    /**
     * 服务启动恢复进度时调用：以当前真实状态初始化状态机。
     * 锁定态启动时把 locked_at 锚定为当前时刻（保守测距）：
     * 若真实锁屏远长于重启时刻，超时重置依然正确触发；
     * 解锁态启动则清零，避免陈旧 locked_at 误触发。
     */
    fun resetLockTracking(prefs: SharedPreferences, unlockedNow: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WAS_UNLOCKED, unlockedNow)
            .putLong(KEY_LOCKED_AT, if (unlockedNow) 0L else System.currentTimeMillis())
            .apply()
    }

    /** 锁定状态机的转换结果 */
    class LockState(
        /** 刚经历一次超过 LOCK_RESET_MS 的锁屏，进度应清零 */
        val reset: Boolean,
        /** 刚发生"锁定→解锁"转换（无论时长），本轮不应补算增量 */
        val justUnlocked: Boolean
    )

    /**
     * 锁定状态机（跨进程生死跟踪"解锁→锁定→解锁"转换）。
     * 返回 LockState：reset=应清零全部进度；justUnlocked=刚解锁，不应补算增量。
     */
    fun applyLockTransition(prefs: SharedPreferences, unlockedNow: Boolean): LockState {
        val wasUnlocked = prefs.getBoolean(KEY_WAS_UNLOCKED, false)
        var reset = false
        var justUnlocked = false
        if (!unlockedNow && wasUnlocked) {
            prefs.edit().putLong(KEY_LOCKED_AT, System.currentTimeMillis()).apply()
        } else if (unlockedNow && !wasUnlocked) {
            justUnlocked = true
            val lockedAt = prefs.getLong(KEY_LOCKED_AT, 0L)
            reset = lockedAt > 0 && System.currentTimeMillis() - lockedAt > LOCK_RESET_MS
        }
        if (wasUnlocked != unlockedNow) {
            prefs.edit().putBoolean(KEY_WAS_UNLOCKED, unlockedNow).apply()
        }
        return LockState(reset, justUnlocked)
    }

    /**
     * 兜底闹钟主入口（进程死活均可）。返回 false 表示监控已停用，
     * 调用方应停止闹钟链。
     *
     * 服务存活时直接跳过：秒级 tick 是唯一计数权威，
     * 闹钟若基于滞后落盘值对账会导致重复提醒/进度回退。
     */
    fun onAlarm(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false)) return false
        if (MonitorService.isAlive()) return true

        val unlocked = isUnlockedNow(context)
        val state = applyLockTransition(prefs, unlocked)
        if (!unlocked) return true // 锁屏中不推进；解锁后按重置规则处理
        if (state.justUnlocked) {
            // 刚解锁：重新锚定墙钟，锁屏期间（无论是否超过重置阈值）不计入使用量
            markWallClock(prefs)
            if (state.reset) {
                val zeroed = RuleStore.load(context).associate { it.id to 0L }
                RuleStore.saveProgress(prefs, zeroed)
                prefs.edit().putLong(RuleStore.KEY_SAVED_AT, System.currentTimeMillis()).apply()
            }
            return true
        }

        val result = consumeDeltaSanitized(prefs)
        if (result.resetProgress) {
            val zeroed = RuleStore.load(context).associate { it.id to 0L }
            RuleStore.saveProgress(prefs, zeroed)
            prefs.edit().putLong(RuleStore.KEY_SAVED_AT, System.currentTimeMillis()).apply()
            Log.d("ReminderEngine", "gap too large -> treat as lock, progress reset")
            return true
        }
        if (result.deltaMs < MIN_DELTA_MS) return true

        val rules = RuleStore.load(context)
        val progress = RuleStore.loadProgress(prefs)
        val newProgress = mutableMapOf<Long, Long>()
        for (rule in rules) {
            if (!rule.enabled) {
                progress[rule.id]?.let { newProgress[rule.id] = it }
                continue
            }
            val elapsed = (progress[rule.id] ?: 0L) + result.deltaMs
            if (elapsed >= rule.intervalMinutes * 60_000L) {
                newProgress[rule.id] = 0L
                Notifier.fireReminder(
                    context,
                    context.getString(R.string.reminder_title),
                    rule.text
                )
                Log.d("ReminderEngine", "alarm fired reminder id=${rule.id}")
            } else {
                newProgress[rule.id] = elapsed
            }
        }
        RuleStore.saveProgress(prefs, newProgress)
        prefs.edit().putLong(RuleStore.KEY_SAVED_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** 距最后一次计时的时长，用于 UI 检测计时停滞 */
    fun lastTickAgeMs(context: Context): Long {
        val last = prefs(context).getLong(KEY_LAST_TICK_WALL, 0L)
        return if (last <= 0) Long.MAX_VALUE else System.currentTimeMillis() - last
    }
}
