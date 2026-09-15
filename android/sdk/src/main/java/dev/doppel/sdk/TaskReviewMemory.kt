package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * User-confirmed lessons from completed task reviews.
 *
 * A failed run is evidence for a review only; it is never written here
 * automatically.  Records intentionally contain semantic text and reason
 * codes, but no screenshots, coordinates, model wire payloads or credentials.
 */
internal class TaskReviewMemory(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val VERSION = 1
        private const val MAX_RECORDS = 100
        private const val MAX_TEXT = 1200
        private val secret = Regex("(?i)(password|passwd|密码|验证码|verification\\s*code|otp|token|api[_ -]?key|secret)\\s*[:：=]?\\s*[^，,；;\\s]{1,120}")
        private val coordinate = Regex("(?i)(?:x|y|坐标|points?)\\s*[:：=]\\s*[-+]?\\d+(?:\\.\\d+)?")

        private fun text(value: String?, limit: Int = MAX_TEXT): String = value.orEmpty().replace(secret, "[已隐藏]")
            .replace(coordinate, "[位置已隐藏]").replace(Regex("\\s+"), " ").trim().take(limit)
        internal fun normalizedCorrection(value: String): String = text(value, 1600)

        private fun safeEvent(event: JSONObject): JSONObject = JSONObject().apply {
            put("message", text(event.optString("message"), 500))
            if (event.has("created_at")) put("created_at", event.optLong("created_at"))
            event.optJSONObject("detail")?.let { detail ->
                // Only stable diagnostics are useful for a review. Model output,
                // source frames and payloads stay out of this memory.
                val d = JSONObject()
                for (key in listOf("status", "reason", "reason_code", "error_class", "role", "action_state")) {
                    if (detail.has(key)) d.put(key, text(detail.optString(key), 240))
                }
                if (d.length() > 0) put("diagnostic", d)
            }
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val lock = Any()

    private fun load(): JSONArray {
        if (!file.isFile) return JSONArray()
        require(file.length() <= 512 * 1024) { "复盘记忆文件超过大小上限" }
        val root = JSONObject(file.readText(StandardCharsets.UTF_8))
        require(root.optInt("version") == VERSION) { "复盘记忆版本不支持" }
        return root.optJSONArray("items") ?: JSONArray()
    }

    private fun write(items: JSONArray) {
        val root = JSONObject().put("version", VERSION).put("items", items)
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "复盘记忆超过大小上限" }
        file.parentFile?.mkdirs()
        val stage = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            stage.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
            Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (stage.exists()) stage.delete() }
    }

    /** Produce a reviewable, redacted view of a run. This never persists anything. */
    fun review(run: JSONObject): JSONObject = synchronized(lock) {
        val events = run.optJSONArray("events") ?: JSONArray()
        val timeline = JSONArray(); val errors = JSONArray()
        repeat(events.length()) { index ->
            val event = events.optJSONObject(index) ?: return@repeat
            val safe = safeEvent(event)
            timeline.put(safe)
            val message = event.optString("message")
            if (Regex("(?i)(错误|失败|未完成|中断|无法|拒绝|不一致|超时|error|failed|unsupported|mismatch)").containsMatchIn(message)) errors.put(safe)
        }
        val result = JSONObject().put("run_id", run.optString("id")).put("goal", text(run.optString("goal"), 2000))
            .put("status", run.optString("status")).put("timeline", timeline).put("errors", errors)
            .put("task_state", run.optJSONObject("task_state")?.let { state ->
                JSONObject().apply {
                    for (key in listOf("phase", "facts", "completed_steps", "remaining_steps", "failed_routes")) {
                        if (state.has(key)) put(key, when (val value = state.opt(key)) {
                            is JSONArray -> JSONArray((0 until minOf(value.length(), 20)).map { text(value.optString(it), 500) })
                            else -> text(value.toString(), 500)
                        })
                    }
                    state.optJSONObject("progress")?.let { progress ->
                        val input = progress.optJSONArray("plan") ?: JSONArray()
                        val plan = JSONArray((0 until minOf(input.length(), 5)).mapNotNull { index ->
                            (input.opt(index) as? String)?.let { text(it, 60) }?.takeIf(String::isNotBlank)
                        })
                        put("progress", JSONObject().put("plan", plan).put("completed", progress.optInt("completed").coerceIn(0, plan.length()))
                            .put("total_known", progress.optBoolean("total_known", false)))
                    }
                }
            } ?: JSONObject())
        run.optJSONObject("model_metrics")?.let { metrics ->
            result.put("metrics", JSONObject().apply {
                for (role in listOf("primary", "grounding")) metrics.optJSONObject(role)?.let { m ->
                    put(role, JSONObject().put("calls", m.optInt("calls")).put("elapsed_ms", m.optLong("elapsed_ms")))
                }
            })
        }
        result.put("memory_key", digest(run.optString("id"))).put("can_save", true)
    }

    /** Save only after an explicit user confirmation in the request body. */
    fun save(run: JSONObject, body: JSONObject): JSONObject = synchronized(lock) {
        require(body.optBoolean("confirmed", false)) { "请先确认要记住这条复盘结论" }
        val correction = normalizedCorrection(body.optString("correction"))
        require(correction.length in 2..1600 && !secret.containsMatchIn(body.optString("correction"))) { "复盘结论不能为空或包含敏感凭据" }
        val trigger = text(body.optString("when"), 500)
        val avoid = text(body.optString("avoid"), 1000)
        val item = JSONObject().put("id", "review-memory-${UUID.randomUUID()}")
            .put("run_id", run.optString("id")).put("goal", text(run.optString("goal"), 500))
            .put("correction", correction).put("confirmed", true).put("created_at", now())
        body.optString("package_name").takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) }
            ?.let { item.put("package_name", it) }
        if (trigger.isNotBlank()) item.put("when", trigger)
        if (avoid.isNotBlank()) item.put("avoid", avoid)
        val items = load(); items.put(item)
        while (items.length() > MAX_RECORDS) items.remove(0)
        write(items); JSONObject(item.toString()).put("saved", true)
    }

    fun list(packageName: String? = null, limit: Int = 20): JSONObject = synchronized(lock) {
        val items = load(); val out = JSONArray()
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: continue
            // Package scope is optional; old records without one remain usable.
            if (packageName != null && item.optString("package_name").isNotBlank() && item.optString("package_name") != packageName) continue
            out.put(JSONObject(item.toString())); if (out.length() >= limit.coerceIn(1, 50)) break
        }
        JSONObject().put("items", out)
    }

    fun delete(id: String): JSONObject = synchronized(lock) {
        val items = load(); val kept = JSONArray(); var found = false
        repeat(items.length()) { val item = items.optJSONObject(it); if (item?.optString("id") == id) found = true else item?.let(kept::put) }
        require(found) { "复盘记忆不存在" }; write(kept); JSONObject().put("deleted", true)
    }
}
