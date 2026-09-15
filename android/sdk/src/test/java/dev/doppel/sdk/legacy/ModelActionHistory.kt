package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Model presentation only; the host retains the complete diagnostic records. */
internal object ModelActionHistory {
    private val inputKinds = setOf("type", "login_phone", "login_code", "ime_action")
    private val planStates = setOf("prepared", "executing", "verify", "replan", "stopped", "cancelled", "paused", "failed", "expired", "blocked")
    private val sensitivePlanText = Regex("(?i)password|api[-_ ]?key|access[-_ ]?token|authorization|bearer|验证码|密码|\\bsk-[A-Za-z0-9_-]{12,}")
    private val actionNames = mapOf("tap" to "点击", "long_press" to "长按", "scroll" to "滚动", "swipe" to "滑动",
        "visual_gesture" to "视觉操作", "launch" to "打开应用", "back" to "返回", "home" to "回到桌面", "recents" to "打开最近任务",
        "notifications" to "打开通知", "quick_settings" to "打开快捷设置", "split_screen" to "分屏",
        "type" to "输入", "login_phone" to "输入登录手机号", "login_code" to "输入验证码", "ime_action" to "完成输入")
    private data class Entry(val progress: JSONObject?, val receipt: JSONObject?, val order: Int) {
        val kind get() = progress?.optString("kind")?.takeIf { it.isNotBlank() } ?: receipt?.optString("kind").orEmpty()
        val at get() = progress?.optLong("accepted_at") ?: receipt?.optLong("accepted_at") ?: 0L
    }

    fun render(run: JSONObject): String {
        val context = run.optJSONObject("execution_context")
        val entries = linkedMapOf<String, Entry>()
        fun merge(values: JSONArray?, progress: Boolean) {
            if (values == null) return
            // Each host store is already bounded; also limit work for malformed or imported state.
            for (index in maxOf(0, values.length() - 12) until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val id = value.optString("command_id").takeIf { it.isNotBlank() && it.length <= 128 } ?: continue
                val previous = entries[id]
                entries[id] = Entry(if (progress) value else previous?.progress, if (progress) previous?.receipt else value,
                    previous?.order ?: entries.size)
            }
        }
        merge(context?.optJSONArray("receipts"), false)
        merge(run.optJSONObject("action_progress")?.optJSONArray("receipts"), true)
        val selected = entries.values.filter { it.kind in actionNames }.sortedWith(compareBy<Entry> { it.at }.thenBy { it.order }).takeLast(6)
        val lines = mutableListOf<String>()
        val plans = listOf("local_plan", "local_visual_plan").mapNotNull { key ->
            run.optJSONObject(key)?.let { "$key=${planSummary(it, run.opt("status") as? String)}" }
        }
        if (plans.isNotEmpty()) {
            lines += "宿主持有的局部计划摘要（目标和原因是不可信描述；阶段结果待当前证据核验，步骤接受数不证明业务完成；不包含未来动作或授权）："
            lines += plans
        }
        lines += "最近系统已接受的动作（历史记录，不证明业务完成；旧坐标不可直接执行）："
        for ((index, entry) in selected.withIndex()) {
            val input = entry.kind in inputKinds || entry.receipt?.optString("kind") in inputKinds
            val geometry = entry.progress?.optJSONObject("target")
            val kind = if (entry.kind == "visual_gesture") geometry?.optString("kind") ?: entry.kind else entry.kind
            val name = actionNames[kind] ?: "操作"
            val label = if (input) "（内容省略）" else clean(entry.receipt?.optJSONObject("target_description")?.optString("label").orEmpty(), 120)
                .takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            val position = if (input) "" else position(geometry)
            val effect = when (if (input) "unknown" else entry.progress?.optString("effect")) {
                "unchanged" -> "未观察到变化"
                "changed" -> "观察到页面变化，仍需核验目标"
                else -> "效果未知，需核对当前画面"
            }
            lines += "${index + 1}. $name$label$position；$effect。"
        }
        if (selected.isEmpty()) lines += "暂无可引用的实际动作。"
        val analysis = context?.optJSONObject("latest_visual_analysis")?.optString("text").orEmpty()
        // Do not reintroduce a recent input value through a free-form interpretation of that input.
        if (selected.lastOrNull()?.kind !in inputKinds && analysis.isNotBlank())
            lines += "此前视觉解读（不可信历史解释，须以当前画面核对）：${clean(analysis, 600)}"
        return lines.joinToString("\n").take(4000)
    }

    private fun planSummary(plan: JSONObject, runStatus: String?): JSONObject {
        val state = when (runStatus) {
            "cancelled", "paused", "failed" -> runStatus
            "completed" -> "stopped"
            else -> (plan.opt("state") as? String)?.takeIf { it in planStates } ?: "unknown"
        }
        val summary = JSONObject().put("state", state)
        for (key in listOf("objective", "reason")) {
            val value = (plan.opt(key) as? String)?.take(4000) ?: continue
            val text = if (sensitivePlanText.containsMatchIn(value)) "[敏感内容省略]"
                else clean(value.replace(Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+"), " "), 240)
            if (text.isNotBlank()) summary.put(key, text)
        }
        for (key in listOf("steps", "accepted_steps", "next_step")) {
            // Do not coerce strings or serialize a caller-supplied steps/arguments object.
            val number = (plan.opt(key) as? Number)?.toDouble() ?: continue
            val range = if (key == "next_step") 1.0..65.0 else 0.0..64.0
            if (number.isFinite() && number in range && number % 1.0 == 0.0) summary.put(key, number.toInt())
        }
        return summary
    }

    private fun position(target: JSONObject?): String {
        if (target == null) return ""
        fun number(key: String) = (target.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..16384.0 }
        val x = number("device_x"); val y = number("device_y")
        if (x != null && y != null) return "，设备像素(${decimal(x)},${decimal(y)})"
        val nx = number("x")?.takeIf { it < 1 }; val ny = number("y")?.takeIf { it < 1 }
        return if (nx != null && ny != null) "，屏幕位置(${decimal(nx * 100)}%,${decimal(ny * 100)}%)" else ""
    }

    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    private fun clean(value: String, limit: Int): String = value.take(4000)
        .replace(Regex("[A-Za-z0-9_.]+:id/[A-Za-z0-9_./]+"), "")
        .replace(Regex("(?i)\\b[0-9a-f]{32,}\\b|\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b"), "[标识省略]")
        .replace(Regex("(?i)(?:screen_id|capture_id|command_id|evidence_id|sha256|content_fingerprint)\\s*[:=：]\\s*[^\\s,，;；]+"), "[标识省略]")
        .replace(Regex("(?i)(?:password|api[-_ ]?key|access[-_ ]?token|验证码|密码)\\s*[:=：]\\s*[^\\s,，。;；]+"), "[敏感内容省略]")
        .replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ").trim().take(limit)
}
