package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Local semantic memory, shared by chat and settings; never stores raw task/model payloads. */
internal class TaskReviewMemory(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        private const val VERSION = 1
        private const val MAX_RECORDS = 100
        private const val MAX_TEXT = 1200
        private const val MAX_FILE_BYTES = 512 * 1024
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

    private fun load(): JSONObject {
        if (!file.isFile) return JSONObject().put("version", VERSION).put("items", JSONArray())
        require(file.length() <= MAX_FILE_BYTES) { "长期记忆文件超过大小上限" }
        val root = JSONObject(file.readText(StandardCharsets.UTF_8))
        require(root.optInt("version") == VERSION) { "长期记忆版本不支持" }
        val items = root.getJSONArray("items")
        require(items.length() <= MAX_RECORDS) { "长期记忆超过 100 条，原始记录已保留" }
        repeat(items.length()) { index ->
            val item = items.getJSONObject(index)
            if (!item.has("content")) item.put("content", item.optString("correction"))
            if (!item.has("correction")) item.put("correction", item.optString("content"))
            if (!item.has("scope")) item.put("scope", if (item.optString("package_name").isBlank()) "global" else "package")
            if (!item.has("revision")) item.put("revision", 1L)
            if (!item.has("updated_at")) item.put("updated_at", item.optLong("created_at"))
            if (!item.has("source")) item.put("source", "review")
        }
        return root
    }

    private fun write(root: JSONObject) {
        val stored = JSONObject(root.toString())
        val items = stored.getJSONArray("items")
        repeat(items.length()) { items.getJSONObject(it).apply { if (optString("content") == optString("correction")) remove("correction") } }
        var bytes = stored.toString().toByteArray(StandardCharsets.UTF_8)
        val responses = stored.optJSONArray("responses")
        while (bytes.size > MAX_FILE_BYTES && responses != null && responses.length() > 1) {
            responses.remove(0)
            bytes = stored.toString().toByteArray(StandardCharsets.UTF_8)
        }
        require(bytes.size <= MAX_FILE_BYTES) { "长期记忆超过大小上限，原始记录已保留" }
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
        create(JSONObject(body.toString()).put("run_id", run.optString("id"))
            .put("goal", run.optString("goal")).put("source", "review"))
    }

    fun list(packageName: String? = null, limit: Int = 20): JSONObject = synchronized(lock) {
        val items = load().getJSONArray("items"); val out = JSONArray()
        for (i in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(i) ?: continue
            if (packageName != null && !inScope(item, packageName)) continue
            out.put(JSONObject(item.toString())); if (out.length() >= limit.coerceIn(1, MAX_RECORDS)) break
        }
        JSONObject().put("items", out)
    }

    fun listAll(): JSONObject = list(limit = MAX_RECORDS)

    /** Settings see every record; task context includes only global and the exact current app. */
    fun context(packageName: String? = null): JSONObject = synchronized(lock) {
        val items = load().getJSONArray("items")
        val out = JSONArray()
        val result = JSONObject().put("items", out)
        val candidates = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { inScope(it, packageName) }.sortedByDescending { it.optLong("updated_at") }
        for (item in candidates) {
            val compact = JSONObject().put("id", item.getString("id"))
                .put("revision", item.getLong("revision")).put("scope", item.optString("scope"))
                .apply { if (item.optString("scope") == "package") put("package_name", item.optString("package_name")) }
            for (key in listOf("goal", "when", "avoid")) if (item.has(key)) compact.put(key, item.optString(key).take(160))
            compact.put("content", item.optString("content"))
            out.put(compact)
            // Keep each supplied memory complete so a model edit cannot erase an unseen tail.
            if (result.toString().length > 3200) { out.remove(out.length() - 1); continue }
            if (out.length() == 4 || result.toString().length >= 3200) break
        }
        result
    }

    private fun inScope(item: JSONObject, packageName: String?): Boolean =
        item.optString("scope") == "global" || (packageName != null && item.optString("scope") == "package" && item.optString("package_name") == packageName)

    private fun content(body: JSONObject): String {
        val raw = (if (body.has("content")) body.get("content") else body.get("correction"))
        require(raw is String && raw.trim().length in 2..1600) { "记忆内容需为 2 至 1600 个字符" }
        require(!secret.containsMatchIn(raw)) { "记忆内容不能包含敏感凭据" }
        val value = normalizedCorrection(raw)
        require(value.length >= 2) { "记忆内容需为 2 至 1600 个字符" }
        return value
    }

    private fun edited(body: JSONObject, previous: JSONObject? = null): JSONObject {
        val value = content(body)
        val item = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("id", "review-memory-${UUID.randomUUID()}").put("created_at", now())
        val packageName = if (body.has("package_name")) body.getString("package_name").trim() else item.optString("package_name")
        val scope = if (body.has("scope")) body.getString("scope")
            else if (body.has("package_name")) { if (packageName.isBlank()) "global" else "package" }
            else item.optString("scope", "global")
        require(scope == "global" || scope == "package") { "记忆范围无效" }
        if (scope == "package") {
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "应用包名无效" }
            item.put("package_name", packageName)
        } else item.remove("package_name")
        item.put("content", value).put("correction", value).put("scope", scope).put("confirmed", true)
            .put("revision", (previous?.optLong("revision", 1L) ?: 0L) + 1).put("updated_at", now())
        for (key in listOf("goal", "when", "avoid", "run_id")) if (body.has(key)) item.put(key, text(body.getString(key), 500))
        if (previous == null) item.put("source", "settings")
        return item
    }

    private fun expected(body: JSONObject): Long? = if (body.has("expected_revision")) {
        val value = body.get("expected_revision")
        require(value is Number && value.toLong() > 0 && value.toDouble() == value.toLong().toDouble()) { "记忆版本无效" }
        value.toLong()
    } else null

    private fun checkRevision(item: JSONObject, revision: Long?) {
        require(revision == null || revision == item.optLong("revision", 1L)) { "记忆已被修改，请刷新后重试" }
    }

    private fun indexOf(items: JSONArray, id: String): Int = (0 until items.length()).firstOrNull { items.getJSONObject(it).optString("id") == id }
        ?: throw IllegalArgumentException("记忆不存在，请刷新后重试")

    private fun same(items: JSONArray, item: JSONObject): JSONObject? = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull {
        it.optString("content") == item.optString("content") && it.optString("scope") == item.optString("scope") && it.optString("package_name") == item.optString("package_name")
    }

    fun create(body: JSONObject): JSONObject = synchronized(lock) {
        val root = load(); val items = root.getJSONArray("items"); val item = edited(body)
        same(items, item)?.let { return@synchronized JSONObject(it.toString()).put("saved", true).put("duplicate", true) }
        require(items.length() < MAX_RECORDS) { "记忆已满 100 条，请先在设置中整理" }
        if (body.optString("source") == "review") item.put("source", "review")
        items.put(item); write(root); JSONObject(item.toString()).put("saved", true)
    }

    fun update(id: String, body: JSONObject, expectedRevision: Long? = null): JSONObject = synchronized(lock) {
        val root = load(); val items = root.getJSONArray("items"); val index = indexOf(items, id)
        val previous = items.getJSONObject(index); checkRevision(previous, expectedRevision ?: expected(body))
        val item = edited(body, previous).put("source", "settings")
        items.put(index, item); write(root); JSONObject(item.toString()).put("saved", true)
    }

    fun delete(id: String, expectedRevision: Long? = null): JSONObject = synchronized(lock) {
        val root = load(); val items = root.getJSONArray("items"); val index = indexOf(items, id)
        checkRevision(items.getJSONObject(index), expectedRevision)
        items.remove(index); write(root); JSONObject().put("deleted", true).put("id", id)
    }

    fun responseFor(conversationId: String, messageId: String): JSONObject? = synchronized(lock) {
        val key = digest(JSONArray().put(conversationId).put(messageId).toString())
        val responses = load().optJSONArray("responses") ?: return@synchronized null
        (0 until responses.length()).map { responses.getJSONObject(it) }.firstOrNull { it.optString("message_key") == key }
            ?.getJSONObject("response")?.let { JSONObject(it.toString()) }
    }

    /** One atomic write includes both changes and their replay marker, including deletions. */
    fun applyChanges(changes: JSONArray, sourceConversation: String, sourceMessageId: String, sourceRunId: String = "", goal: String = "", response: JSONObject? = null): JSONObject = synchronized(lock) {
        require(sourceConversation.isNotBlank() && sourceConversation.length <= 240 && sourceMessageId.isNotBlank() && sourceMessageId.length <= 240) { "记忆缺少有效的聊天来源" }
        require(changes.length() <= 4) { "一次最多更新 4 条记忆" }
        val root = load(); val items = root.getJSONArray("items")
        val processed = root.optJSONArray("processed_messages") ?: JSONArray().also { root.put("processed_messages", it) }
        val messageKey = digest(JSONArray().put(sourceConversation).put(sourceMessageId).toString())
        if ((0 until processed.length()).any { processed.optString(it) == messageKey }) {
            return@synchronized JSONObject().put("applied", 0).put("saved", 0).put("deleted", 0).put("duplicate", true).put("items", JSONArray())
        }
        val receipt = response?.let { value ->
            JSONObject().apply {
                for (key in listOf("intent", "reply", "title", "confidence", "task_goal", "question", "reason")) {
                    if (value.has(key)) {
                        val field = value.get(key)
                        require(field is String || (key == "confidence" && field is Number && field.toDouble() in 0.0..1.0)) { "聊天响应字段格式无效" }
                        put(key, field)
                    }
                }
                require(toString().length <= 12000) { "聊天响应超过记忆收据大小上限" }
            }
        }
        val results = JSONArray(); var saved = 0; var deleted = 0
        repeat(changes.length()) { index ->
            val change = changes.getJSONObject(index)
            val id = if (change.has("id")) change.getString("id") else ""
            val op = change.getString("op")
            require(op == "upsert" || op == "delete") { "记忆操作无效" }
            val existingIndex = if (id.isNotBlank()) indexOf(items, id) else -1
            val previous = if (existingIndex >= 0) items.getJSONObject(existingIndex) else null
            if (previous != null) {
                val revision = expected(change)
                require(revision != null) { "修改记忆需要提供原版本，请刷新后重试" }
                checkRevision(previous, revision)
            }
            if (op == "delete") {
                require(previous != null) { "删除记忆需要已有记录" }
                items.remove(existingIndex); results.put(JSONObject().put("id", id).put("deleted", true)); deleted++
            } else {
                val item = edited(change, previous)
                val duplicate = if (previous == null) same(items, item) else null
                if (duplicate != null) results.put(JSONObject(duplicate.toString()).put("duplicate", true))
                else {
                    require(previous != null || items.length() < MAX_RECORDS) { "记忆已满 100 条，请先在设置中整理" }
                    item.put("source", "chat").put("source_conversation", sourceConversation).put("source_message_id", sourceMessageId)
                        .put("source_run_id", sourceRunId.take(240)).put("run_id", sourceRunId.take(240)).put("goal", text(goal, 500))
                    if (previous == null) items.put(item) else items.put(existingIndex, item)
                    results.put(JSONObject(item.toString())); saved++
                }
            }
        }
        // ponytail: retain the latest 256 chat receipts; use a separate journal if older replay becomes a real need.
        processed.put(messageKey)
        while (processed.length() > 256) processed.remove(0)
        val result = JSONObject().put("applied", saved + deleted).put("saved", saved).put("deleted", deleted).put("duplicate", false)
        if (receipt != null) {
            receipt.put("memory_result", JSONObject(result.toString()))
            val responses = root.optJSONArray("responses") ?: JSONArray().also { root.put("responses", it) }
            responses.put(JSONObject().put("message_key", messageKey).put("response", receipt))
            while (responses.length() > 32) responses.remove(0)
        }
        write(root)
        result.put("items", results)
    }
}
