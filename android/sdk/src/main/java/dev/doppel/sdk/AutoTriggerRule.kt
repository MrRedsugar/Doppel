package dev.doppel.sdk

import org.json.JSONObject
import java.util.UUID

/** A small, local accessibility rule. It deliberately contains no model or screenshot work. */
data class AutoTriggerRule(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val matchResourceId: String = "",
    val matchText: String = "",
    val targetResourceId: String = "",
    val targetText: String = "",
    val taskGoal: String = "",
    val action: String = "click",
    val cooldownMs: Long = 3000L,
    val burstLimit: Int = 3,
    val burstWindowMs: Long = 15000L,
    val enabled: Boolean = true
) {
    init {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "应用包名无效" }
        // Triggers are intentionally anchored to a stable accessibility resource ID.
        // Text-only selectors are too broad and may fire on the wrong control; controls
        // without an ID are ignored during rule creation.
        require(matchResourceId.isNotBlank()) { "触发控件没有 resource-id，无法创建规则" }
        require(action in setOf("task", "click", "dismiss", "back")) { "不支持的触发动作" }
        require(action != "task" || taskGoal.isNotBlank()) { "执行任务规则必须填写任务内容" }
        require(cooldownMs in 500L..600_000L && burstWindowMs in 1_000L..3_600_000L) { "触发冷却时间无效" }
        require(burstLimit in 1..20) { "短时间触发次数无效" }
    }

    fun json() = JSONObject().apply {
        put("id", id).put("package_name", packageName).put("match_resource_id", matchResourceId)
            .put("match_text", matchText).put("target_resource_id", targetResourceId).put("target_text", targetText)
            .put("task_goal", taskGoal)
            .put("action", action).put("cooldown_ms", cooldownMs).put("burst_limit", burstLimit)
            .put("burst_window_ms", burstWindowMs).put("enabled", enabled)
    }

    /** Friendly metadata only; matching remains strictly package + resource ID. */
    fun controlLabel(context: android.content.Context): String {
        val appName = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        return "$appName · ${matchText.ifBlank { matchResourceId }}"
    }

    fun actionLabel(): String = when (action) {
        "task" -> taskGoal
        "dismiss" -> "关闭弹窗"
        "back" -> "返回"
        else -> "点击${targetText.ifBlank { "该控件" }}"
    }

    companion object {
        fun parse(value: JSONObject) = AutoTriggerRule(
            id = value.optString("id").ifBlank { UUID.randomUUID().toString() },
            packageName = value.getString("package_name"),
            matchResourceId = value.optString("match_resource_id"), matchText = value.optString("match_text"),
            targetResourceId = value.optString("target_resource_id"), targetText = value.optString("target_text"),
            taskGoal = value.optString("task_goal"),
            action = value.optString("action", "click"), cooldownMs = value.optLong("cooldown_ms", 3000L),
            burstLimit = value.optInt("burst_limit", 3), burstWindowMs = value.optLong("burst_window_ms", 15000L),
            enabled = value.optBoolean("enabled", true)
        )
    }
}
