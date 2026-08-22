package com.learn.antilazy

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条提醒规则 */
data class Rule(
    val id: Long,
    val intervalMinutes: Int,
    val text: String,
    val enabled: Boolean = true
)

/** 给 UI 展示用的运行时快照（含当前已计时长） */
data class RuleView(
    val id: Long,
    val text: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val elapsedMs: Long
)

/**
 * 规则的持久化存储：定义与进度分开存，均为主线程访问。
 * 用框架自带的 org.json 序列化，保持零第三方依赖。
 */
object RuleStore {

    const val PREFS_NAME = "anti_lazy"
    const val DEFAULT_INTERVAL_MINUTES = 5

    // 与服务、引擎、通知器共享的偏好键
    const val KEY_RUNNING = "running"
    const val KEY_USER_STOPPED = "user_stopped"
    const val KEY_CHECKPOINT_ELAPSED = "checkpoint_elapsed"
    const val KEY_BOOT_COUNT = "boot_count"
    const val KEY_WAS_UNLOCKED = "was_unlocked"
    const val KEY_LOCKED_AT_ELAPSED = "locked_at_elapsed"
    const val KEY_LAST_ENABLE_WALL = "last_enable_wall"
    const val KEY_LAST_HEALTH_WARN_ELAPSED = "last_health_warn_elapsed"
    const val KEY_LAST_REMINDER_AT = "last_reminder_at"
    const val KEY_REMIND_COUNT = "remind_count"

    private const val KEY_RULES = "rules_json"
    private const val KEY_PROGRESS = "progress_json"
    private const val KEY_SEEDED = "seeded_v1"
    private const val KEY_NEXT_RULE_ID = "next_rule_id"

    /** 读取全部规则；首次使用自动种入一条默认规则 */
    fun load(context: Context): MutableList<Rule> {
        val prefs = prefs(context)
        val list = mutableListOf<Rule>()
        val raw = prefs.getString(KEY_RULES, null)
        val parsed = raw?.let {
            runCatching {
                val parsedRules = mutableListOf<Rule>()
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    parsedRules.add(
                        Rule(
                            id = o.getLong("id"),
                            intervalMinutes = o.getInt("minutes").coerceIn(1, 720),
                            text = o.getString("text").take(500),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
                parsedRules
            }.getOrNull()
        }
        if (parsed != null) list.addAll(parsed)
        // Missing/corrupt JSON also repairs installs affected by old persistence bugs.
        val needsRepair = raw == null || parsed == null
        if (list.isEmpty() && (needsRepair || !prefs.getBoolean(KEY_SEEDED, false))) {
            list.add(
                Rule(
                    id = nextId(context, list),
                    intervalMinutes = DEFAULT_INTERVAL_MINUTES,
                    text = context.getString(R.string.default_reminder_text)
                )
            )
            // 必须立即落盘：否则下次 load 时 seeded=true 却无规则，默认规则消失
            save(context, list)
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        return list
    }

    fun save(context: Context, rules: List<Rule>) {
        val prefs = prefs(context)
        val ids = rules.mapTo(mutableSetOf()) { it.id }
        val cleanedProgress = loadProgress(prefs).filterKeys { it in ids }
        prefs.edit()
            .putString(KEY_RULES, encode(rules))
            .putString(KEY_PROGRESS, encodeProgress(cleanedProgress))
            .apply()
    }

    fun loadProgress(prefs: SharedPreferences): MutableMap<Long, Long> {
        val map = mutableMapOf<Long, Long>()
        prefs.getString(KEY_PROGRESS, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.keys().forEach { key ->
                    map[key.toLong()] = o.getLong(key).coerceAtLeast(0L)
                }
            }
        }
        return map
    }

    /** Progress and its monotonic anchor are always stored in the same generation. */
    @SuppressLint("ApplySharedPref") // sync=true is used only at lifecycle boundaries.
    fun checkpoint(
        prefs: SharedPreferences,
        progress: Map<Long, Long>,
        checkpointElapsed: Long,
        bootCount: Int,
        wasUnlocked: Boolean,
        lockedAtElapsed: Long,
        sync: Boolean
    ): Boolean {
        val editor = prefs.edit()
            .putString(KEY_PROGRESS, encodeProgress(progress))
            .putLong(KEY_CHECKPOINT_ELAPSED, checkpointElapsed)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putBoolean(KEY_WAS_UNLOCKED, wasUnlocked)
            .putLong(KEY_LOCKED_AT_ELAPSED, lockedAtElapsed)
        return if (sync) editor.commit() else {
            editor.apply()
            true
        }
    }

    fun clearProgress(
        prefs: SharedPreferences,
        nowElapsed: Long,
        bootCount: Int,
        sync: Boolean
    ): Boolean =
        checkpoint(
            prefs = prefs,
            progress = emptyMap(),
            checkpointElapsed = nowElapsed,
            bootCount = bootCount,
            wasUnlocked = false,
            lockedAtElapsed = nowElapsed,
            sync = sync
        )

    /** 规则 id 单调递增，永不复用：防止新规则继承已删规则的残留进度 */
    fun nextId(context: Context, rules: List<Rule>): Long {
        val prefs = prefs(context)
        val maxSeen = (rules.maxOfOrNull { it.id } ?: 0L)
            .coerceAtLeast(prefs.getLong(KEY_NEXT_RULE_ID, 0L))
        val next = maxSeen + 1L
        prefs.edit().putLong(KEY_NEXT_RULE_ID, next).apply()
        return next
    }

    private fun encode(rules: List<Rule>): String {
        val arr = JSONArray()
        rules.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("minutes", it.intervalMinutes)
                    .put("text", it.text)
                    .put("enabled", it.enabled)
            )
        }
        return arr.toString()
    }

    private fun encodeProgress(progress: Map<Long, Long>): String {
        val o = JSONObject()
        progress.forEach { (id, ms) -> o.put(id.toString(), ms.coerceAtLeast(0L)) }
        return o.toString()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
