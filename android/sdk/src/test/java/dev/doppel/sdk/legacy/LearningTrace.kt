package dev.doppel.sdk

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** Evidence adapter shared by the Android host and SDK integrations. */
object LearningTrace {
    private val supported = setOf("tap", "long_press", "scroll", "back")
    private val sensitive = Regex("(?i)验证码|密码|口令|支付|付款|转账|发送|删除|购买|下单|充值|授权扣款|password|verification|payment|purchase|delete|\\bsend\\b|[0-9]{5,}|[A-Za-z0-9_-]{24,}|https?://|@")
    internal fun label(value: String): String = value.trim().replace(Regex("\\s+"), " ")
        .takeIf { it.length in 1..100 && !sensitive.containsMatchIn(it) }.orEmpty()
    internal fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun identity(value: JSONObject?): JSONObject? {
        if (value == null) return null
        val result = JSONObject()
        for (key in listOf("package_name", "version_code", "version_name", "system", "locale")) {
            val text = value.optString(key)
            if (text.isBlank() || text.length > 255 || text.any { it.code < 32 }) return null
            result.put(key, text)
        }
        if (!result.getString("package_name").matches(Regex("[A-Za-z0-9_.]+"))) return null
        return result
    }
    internal fun signature(value: JSONObject?) = identity(value)?.let { hash(it.toString()) }
    private fun nodes(screen: JSONObject) = screen.optJSONArray("nodes")?.let { a ->
        (0 until minOf(a.length(), 300)).mapNotNull { a.optJSONObject(it) }
    }.orEmpty()
    private fun privateNode(node: JSONObject) = node.optBoolean("password") || node.optBoolean("editable") || node.has("visible") && !node.optBoolean("visible")
    internal fun targetLabel(screen: JSONObject, node: JSONObject): String {
        if (privateNode(node)) return ""
        val direct = node.optString("text").ifBlank { node.optString("description") }
        if (direct.isNotBlank()) return label(direct)
        val rect = node.optJSONArray("bounds") ?: return ""
        if (rect.length() != 4 || (0..3).any { rect.opt(it) !is Number }) return ""
        val area = (rect.getInt(2).toLong() - rect.getInt(0)) * (rect.getInt(3).toLong() - rect.getInt(1))
        if (area <= 0 || area > screen.optInt("width").toLong() * screen.optInt("height") / 4) return ""
        val contained = nodes(screen).filter { child ->
            val b = child.optJSONArray("bounds")
            child !== node && !privateNode(child) && !child.optBoolean("clickable") && !child.optBoolean("long_clickable") &&
                b != null && b.length() == 4 && (0..3).all { b.opt(it) is Number } &&
                b.getInt(0) >= rect.getInt(0) && b.getInt(1) >= rect.getInt(1) && b.getInt(2) <= rect.getInt(2) && b.getInt(3) <= rect.getInt(3)
        }
        val titles = contained.filter { it.optString("resource_id").substringAfterLast('/') == "title" }
        if (titles.size == 1) return label(titles.single().optString("text"))
        return label(contained.filter { it.optString("resource_id").substringAfterLast('/') != "summary" }
            .map { label(it.optString("text")) }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" · "))
    }
    private fun labels(screen: JSONObject) = nodes(screen).filter {
        !privateNode(it) && it.optString("resource_id").substringAfterLast('/') != "summary"
    }.map { label(it.optString("text")) }.filter { it.isNotBlank() && !it.matches(Regex("[0-9.,% %]+")) }.distinct()
    private fun page(screen: JSONObject, previous: JSONObject? = null): JSONObject {
        val all = labels(screen)
        val old = previous?.let(::labels).orEmpty().toSet()
        val preferred = if (previous == null) all else all.filter { it !in old } + all
        return JSONObject().put("screen_id", screen.optString("screen_id").take(128)).put("labels", JSONArray(preferred.distinct().take(6)))
    }
    fun block(run: JSONObject, reason: String) {
        run.put("learning_trace", JSONObject().put("blocked", reason.take(120)))
    }
    fun record(run: JSONObject, command: JSONObject, before: JSONObject?, after: JSONObject?, identity: JSONObject?) {
        val existing = run.optJSONObject("learning_trace")
        if (existing?.has("blocked") == true) return
        val kind = command.optString("kind")
        if (kind in setOf("observe", "wait") || kind == "launch" && existing == null) return
        val app = identity(identity)
        if (kind !in supported) { block(run, "此路线包含输入、跨应用或尚未支持的视觉步骤"); return }
        if (before == null || after == null || app == null || before.optString("package_name") != app.getString("package_name") ||
            after.optString("package_name") != app.getString("package_name")) { block(run, "应用身份或前后页面证据不完整"); return }
        if (before.optString("screen_id").isBlank() || after.optString("screen_id").isBlank() || before.optString("screen_id") == after.optString("screen_id")) {
            block(run, "未观察到明确页面变化"); return
        }
        val node = nodes(before).firstOrNull { it.optString("id") == command.optString("target") }
        val name = if (kind == "back") "返回" else node?.takeIf { it.optBoolean("enabled") }?.let { targetLabel(before, it) }.orEmpty()
        if (name.isBlank()) { block(run, "目标缺少可复用的文字身份或涉及敏感内容"); return }
        val trace = existing ?: JSONObject().put("app", app).put("steps", JSONArray()).also { run.put("learning_trace", it) }
        val steps = trace.getJSONArray("steps")
        if (steps.length() >= 24 || signature(trace.optJSONObject("app")) != signature(app) ||
            steps.length() > 0 && steps.getJSONObject(steps.length() - 1).getJSONObject("after").getString("screen_id") != before.getString("screen_id")) {
            block(run, "路线超出长度限制、版本变化或步骤间出现未记录的变化"); return
        }
        val step = JSONObject().put("kind", kind).put("label", name).put("before", page(before)).put("after", page(after, before))
            .put("evidence_id", command.optString("id").take(128))
        if (kind == "scroll") {
            val direction = command.optString("direction")
            if (direction !in setOf("up", "down", "left", "right")) { block(run, "缺少实际滚动方向"); return }
            step.put("direction", direction)
        }
        if (node?.optBoolean("checkable") == true) {
            val value = command.opt("desired_checked") as? Boolean
            if (value == null) { block(run, "开关步骤缺少目标状态"); return }
            step.put("desired_checked", value)
        }
        steps.put(step)
    }
    fun finish(run: JSONObject, finalScreen: JSONObject?): JSONObject? {
        if (run.optString("status") != "completed" || finalScreen == null) return null
        val trace = run.optJSONObject("learning_trace") ?: return null
        if (trace.has("blocked")) return null
        val steps = trace.optJSONArray("steps") ?: return null
        if (steps.length() !in 1..24 || steps.getJSONObject(steps.length() - 1).getJSONObject("after").optString("screen_id") != finalScreen.optString("screen_id") ||
            trace.getJSONObject("app").getString("package_name") != finalScreen.optString("package_name")) return null
        return JSONObject(trace.toString()).put("source_id", run.getString("id").take(128))
            .put("origin", if (run.optString("learning_origin") == "demonstration") "demonstration" else "completed_task")
            .put("proves_business_success", false)
    }
}
