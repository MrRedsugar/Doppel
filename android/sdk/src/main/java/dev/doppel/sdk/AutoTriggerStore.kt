package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray

/** SharedPreferences rule store. Rule content never leaves the device. */
class AutoTriggerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cachedRules: List<AutoTriggerRule> = emptyList()
    fun list(): List<AutoTriggerRule> = synchronized(prefs) {
        val encoded = runCatching { prefs.getString("rules", "[]") }.getOrNull() ?: "[]"
        if (encoded != cachedRaw) {
            val raw = runCatching { JSONArray(encoded) }.getOrElse { JSONArray() }
            cachedRules = (0 until raw.length()).mapNotNull { runCatching { AutoTriggerRule.parse(raw.getJSONObject(it)) }.getOrNull() }
            cachedRaw = encoded
        }
        cachedRules.toList()
    }
    fun save(rule: AutoTriggerRule) = synchronized(prefs) {
        val all = list().filterNot { it.id == rule.id } + rule
        require(all.size <= 200) { "自动触发规则最多保存 200 条" }
        persist(all)
        rule
    }
    fun remove(id: String) = synchronized(prefs) { persist(list().filterNot { it.id == id }) }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(prefs) {
        val found = list().find { it.id == id } ?: error("自动触发规则不存在")
        persist(list().map { if (it.id == id) it.copy(enabled = enabled) else it }); found.copy(enabled = enabled)
    }
    fun clear() = synchronized(prefs) { check(prefs.edit().remove("rules").commit()) { "自动触发规则清理失败" } }
    private fun persist(values: List<AutoTriggerRule>) {
        val encoded = JSONArray().apply { values.forEach { put(it.json()) } }.toString()
        check(prefs.edit().putString("rules", encoded).commit()) { "自动触发规则保存失败" }
    }
}
