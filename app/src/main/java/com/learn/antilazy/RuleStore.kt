package com.learn.antilazy

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

    private const val KEY_RULES = "rules_json"
    private const val KEY_PROGRESS = "progress_json"
    private const val KEY_SEEDED = "seeded_v1"

    /** 读取全部规则；首次使用自动种入一条默认规则 */
    fun load(context: Context): MutableList<Rule> {
        val prefs = prefs(context)
        val list = mutableListOf<Rule>()
        prefs.getString(KEY_RULES, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Rule(
                            id = o.getLong("id"),
                            intervalMinutes = o.getInt("minutes").coerceIn(1, 720),
                            text = o.getString("text"),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }
        if (list.isEmpty() && !prefs.getBoolean(KEY_SEEDED, false)) {
            list.add(
                Rule(
                    id = nextId(list),
                    intervalMinutes = DEFAULT_INTERVAL_MINUTES,
                    text = context.getString(R.string.default_reminder_text)
                )
            )
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        return list
    }

    fun save(context: Context, rules: List<Rule>) {
        prefs(context).edit().putString(KEY_RULES, encode(rules)).apply()
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

    fun saveProgress(prefs: SharedPreferences, progress: Map<Long, Long>) {
        val o = JSONObject()
        progress.forEach { (id, ms) -> o.put(id.toString(), ms) }
        prefs.edit().putString(KEY_PROGRESS, o.toString()).apply()
    }

    fun nextId(rules: List<Rule>): Long =
        (rules.maxOfOrNull { it.id } ?: 0L) + 1L

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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
