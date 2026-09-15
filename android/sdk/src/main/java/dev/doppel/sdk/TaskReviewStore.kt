package dev.doppel.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, local review journal used by the task timeline.  Reviews are user
 * authored corrections, so they are kept separate from the model transcript
 * and never silently become an action or an authorization.
 */
internal class TaskReviewStore(context: Context) {
    companion object {
        private val session = java.util.UUID.randomUUID().toString()
        fun stateLabel(item: JSONObject): String = when (item.optString("sync_state")) {
            "local" -> "仅保存在本机，尚未加入记忆"
            "pending" -> "正在加入记忆…"
            "saved" -> "已加入记忆，下次规划会参考"
            "failed" -> "加入记忆失败，可以重试"
            else -> "保存结果待核对，可能已加入记忆，请先核对，勿重复提交"
        }
    }
    private val prefs = context.getSharedPreferences("doppel_task_reviews", Context.MODE_PRIVATE)
    private val key = "items"

    fun add(runId: String, goal: String, note: String, outcome: String, scope: String = ""): JSONObject = synchronized(prefs) {
        val value = note.trim()
        require(value.length in 2..1600) { "复盘内容需为 2 至 1600 个字符" }
        require(!Regex("(?i)(password|passwd|验证码|otp|token|api[_ -]?key|secret)\\s*[:：=]").containsMatchIn(value)) {
            "复盘内容不能包含凭据或验证码"
        }
        val all = read()
        (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull {
            it.optString("run_id") == runId && it.optString("note") == value && it.optString("scope") == scope
        }?.let { return@synchronized JSONObject(it.toString()) }
        val item = JSONObject().put("id", java.util.UUID.randomUUID().toString())
            .put("run_id", runId).put("goal", goal.take(500)).put("note", value)
            .put("outcome", outcome).put("created_at", System.currentTimeMillis()).put("scope", scope).put("sync_state", "local")
        all.put(item)
        while (all.length() > 50) {
            val removable = (0 until all.length()).firstOrNull { all.getJSONObject(it).optString("sync_state") != "pending" }
                ?: error("正在保存的复盘过多，请稍后重试")
            check(removable < all.length() - 1) { "正在保存的复盘过多，请稍后重试" }
            all.remove(removable)
        }
        write(all)
        JSONObject(item.toString())
    }

    fun forRun(runId: String, scope: String? = null): List<JSONObject> = synchronized(prefs) { read().let { all ->
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter {
            it.optString("run_id") == runId && (scope == null || it.optString("scope").isBlank() || it.optString("scope") == scope)
        }.reversed()
    } }

    fun get(id: String): JSONObject = synchronized(prefs) {
        val all = read()
        (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            ?: error("复盘记录已不存在")
    }

    private fun update(id: String, change: (JSONObject) -> Unit): JSONObject = synchronized(prefs) {
        val all = read()
        val item = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            ?: error("复盘记录已不存在")
        change(item); write(all); JSONObject(item.toString())
    }

    /** Neither memory API is idempotent. Ambiguous delivery is never retried automatically. */
    fun submit(id: String, gateway: Gateway, onStarted: () -> Unit = {}): JSONObject {
        val connection = gateway.captureReviewConnection()
        var claimed = false
        val item = update(id) {
            check(it.optString("scope") == connection.scope) { "连接已更改，请切回原连接后重试" }
            if (it.optString("sync_state") in setOf("local", "failed")) {
                it.put("sync_state", "pending").put("sync_session", session); it.remove("sync_error"); claimed = true
            }
        }
        if (!claimed) return item
        var dispatched = false
        return try {
            onStarted()
            dispatched = true
            val response = if (connection.direct) connection.request("POST", "/runs/${item.getString("run_id")}/review-memory",
                JSONObject().put("confirmed", true).put("correction", item.getString("note")))
            else connection.request("POST", "/memories", JSONObject().put("content", "任务复盘：${item.optString("goal").take(400)}；用户纠错：${item.getString("note")}"))
            check(response.optString("id").isNotBlank()) { "记忆服务未返回保存凭据" }
            update(id) { it.put("sync_state", "saved").put("memory_id", response.optString("id")); it.remove("sync_error") }
        } catch (error: Exception) {
            val rejected = !dispatched || error is IllegalArgumentException ||
                (error is GatewayHttpException && error.statusCode in 400..499 && error.statusCode != 408) ||
                error is java.net.ConnectException || error is java.net.UnknownHostException
            update(id) {
                it.put("sync_state", if (rejected) "failed" else "unknown")
                    .put("sync_error", if (rejected) "未能加入记忆，请检查连接或纠错内容后重试" else "未能确认保存结果，不会自动重发")
            }
        }
    }

    /** A missing entry in a bounded list is not proof that a previous POST never succeeded. */
    fun verify(id: String, gateway: Gateway): JSONObject {
        val item = get(id)
        val connection = gateway.captureReviewConnection()
        check(item.optString("scope") == connection.scope) { "请切回原连接后核对；旧记录未标记连接时需自行检查原记忆" }
        val direct = connection.direct
        val response = connection.request("GET", if (direct) "/review-memory" else "/memories")
        val items = response.optJSONArray("items") ?: error("记忆列表未返回，请稍后核对")
        val expected = if (direct) TaskReviewMemory.normalizedCorrection(item.getString("note"))
            else "任务复盘：${item.optString("goal").take(400)}；用户纠错：${item.getString("note")}"
        val match = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.firstOrNull {
            it.optString("id").isNotBlank() && (if (direct) it.optString("run_id") == item.optString("run_id") && it.optString("correction") == expected
            else it.optString("content") == expected)
        }
        return update(id) {
            if (match != null) { it.put("sync_state", "saved").put("memory_id", match.optString("id")); it.remove("sync_error") }
            else it.put("sync_error", "当前记忆列表未找到这条纠错；列表可能不完整，暂不重复提交")
        }
    }

    fun recent(limit: Int = 3): List<JSONObject> = synchronized(prefs) { read().let { all ->
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }.takeLast(limit).reversed()
    } }

    /** Compact text for a future model-context adapter; never contains credentials. */
    fun context(limit: Int = 8): String = recent(limit).joinToString("\n") {
        "- ${it.optString("note").replace(Regex("\\s+"), " ").take(800)}"
    }

    private fun read(): JSONArray {
        val all = try { JSONArray(prefs.getString(key, "[]").orEmpty()) }
        catch (_: Exception) { error("复盘记录损坏，原始内容已保留，请检查本机存储") }
        for (i in 0 until all.length()) all.getJSONObject(i).let { item ->
            if (!item.has("sync_state") || (item.optString("sync_state") == "pending" && item.optString("sync_session") != session))
                item.put("sync_state", "unknown")
        }
        return all
    }
    private fun write(all: JSONArray) {
        check(prefs.edit().putString(key, all.toString()).commit()) { "复盘保存失败" }
    }
}
