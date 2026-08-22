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

    /** 服务启动恢复进度时调用：以当前真实状态初始化状态机，避免陈旧 locked_at 误触发重置 */
    fun resetLockTracking(prefs: SharedPreferences, unlockedNow: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WAS_UNLOCKED, unlockedNow)
            .putLong(KEY_LOCKED_AT, 0L)
            .apply()
    }

    /**
     * 锁定状态机。返回 true 表示刚经历一次超过 LOCK_RESET_MS 的锁屏，
     * 调用方应将所有规则进度清零。
     */
    fun applyLockTransition(prefs: SharedPreferences, unlockedNow: Boolean): Boolean {
        val wasUnlocked = prefs.getBoolean(KEY_WAS_UNLOCKED, false)
        var reset = false
        if (!unlockedNow && wasUnlocked) {
            prefs.edit().putLong(KEY_LOCKED_AT, System.currentTimeMillis()).apply()
        } else if (unlockedNow && !wasUnlocked) {
            val lockedAt = prefs.getLong(KEY_LOCKED_AT, 0L)
            reset = lockedAt > 0 && System.currentTimeMillis() - lockedAt > LOCK_RESET_MS
        }
        if (wasUnlocked != unlockedNow) {
            prefs.edit().putBoolean(KEY_WAS_UNLOCKED, unlockedNow).apply()
        }
        return reset
    }

    /**
     * 兜底闹钟主入口（进程死活均可）。返回 false 表示监控已停用，
     * 调用方应停止闹钟链。
     */
    fun onAlarm(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(RuleStore.KEY_RUNNING, false)) return false

        val unlocked = isUnlockedNow(context)
        val reset = applyLockTransition(prefs, unlocked)
        if (!unlocked) return true // 锁屏中不推进；解锁后按重置规则处理

        val delta = consumeDelta(prefs)
        if (!reset && delta < MIN_DELTA_MS) return true

        val rules = RuleStore.load(context)
        val progress = RuleStore.loadProgress(prefs)
        val newProgress = mutableMapOf<Long, Long>()
        for (rule in rules) {
            if (!rule.enabled) {
                progress[rule.id]?.let { newProgress[rule.id] = it }
                continue
            }
            if (reset) {
                newProgress[rule.id] = 0L
                continue
            }
            val elapsed = (progress[rule.id] ?: 0L) + delta
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
}
