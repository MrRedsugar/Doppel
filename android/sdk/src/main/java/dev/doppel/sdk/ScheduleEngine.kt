package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface SchedulePort {
    /** Null means current host/device authorizes trying to create a new task. */
    fun readiness(job: JSONObject): String?
    fun create(job: JSONObject): String
    fun start(runId: String)
    /** "missing" means a definitively unavailable record; null or failure leaves its outcome unknown. */
    fun status(runId: String): String?
    fun finishDispatch() {}
}

/** Serialized state machine. Save must be atomic; a dispatch claim precedes every create. */
class ScheduleEngine(state: String?, private val save: (String) -> Unit,
                     private val clock: () -> Long = System::currentTimeMillis,
                     private val binding: () -> String) {
    companion object { const val GRACE_MS = 300000L; private val terminal = setOf("completed", "failed", "cancelled") }
    // Writers only mutate detached copies. Publish after saving so UI readers never wait on network work.
    @Volatile private var jobs: List<JSONObject> = emptyList()
    init {
        if (state != null) {
            val root = JSONObject(state); require(root.getInt("version") == 1) { "定时记录版本不支持" }
            val items = root.getJSONArray("items"); require(items.length() <= 100)
            jobs = (0 until items.length()).map { JSONObject(items.getJSONObject(it).toString()) }
            require(jobs.map { it.getString("id") }.toSet().size == jobs.size)
            for (job in jobs.toList()) {
                val copy = JSONObject(job.toString()); val history = copy.getJSONArray("history"); var changed = false
                require(history.length() <= 100); ScheduleRule.parse(copy.getJSONObject("rule"))
                for (i in 0 until history.length()) if (history.getJSONObject(i).optString("status") == "dispatching") {
                    history.getJSONObject(i).put("status", "uncertain").put("reason", "host_restarted_during_dispatch"); changed = true
                }
                if (changed) { copy.put("enabled", false).put("next_due_ms", JSONObject.NULL).put("waiting_reason", "review_uncertain_dispatch"); persist(copy) }
            }
        }
    }
    private fun commit(values: List<JSONObject>) {
        val root = JSONObject().put("version", 1).put("items", JSONArray(values))
        save(root.toString()); jobs = values.map { JSONObject(it.toString()) }
    }
    private fun persist(job: JSONObject) {
        val others = jobs.filter { it.getString("id") != job.getString("id") }
        commit(others + job)
    }
    fun list() = JSONObject().put("items", JSONArray(jobs.map { JSONObject(it.toString()) }))
    fun get(id: String): JSONObject = JSONObject((jobs.find { it.getString("id") == id } ?: error("定时任务不存在")).toString())
    private fun validate(body: JSONObject): JSONObject {
        require(body.keys().asSequence().all { it in setOf("device_id", "goal", "mode", "allowed_packages", "rule", "enabled") }) { "未知的计划字段" }
        val goal = body.opt("goal"); val device = body.opt("device_id"); val mode = body.optString("mode", "ask")
        require(goal is String && goal.isNotBlank() && goal.length <= 12000) { "请输入 1 至 12000 字的任务目标" }
        require(device is String && device.isNotBlank() && device.length <= 256) { "请先连接设备" }
        require(mode in setOf("ask", "assist", "full")) { "任务权限无效" }
        require(!body.has("enabled") || body.opt("enabled") is Boolean) { "启用状态必须为布尔值" }
        val packages = if (body.has("allowed_packages")) body.getJSONArray("allowed_packages") else JSONArray()
        require(packages.length() <= 40)
        for (i in 0 until packages.length()) require(packages.opt(i) is String && packages.getString(i).length <= 256 && Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packages.getString(i)))
        return JSONObject().put("device_id", device).put("goal", goal.trim()).put("mode", mode)
            .put("allowed_packages", JSONArray(packages.toString())).put("rule", ScheduleRule.parse(body.getJSONObject("rule")).json()).put("enabled", body.optBoolean("enabled", true))
    }
    @Synchronized fun create(body: JSONObject): JSONObject {
        require(jobs.size < 100) { "最多保存 100 个计划" }
        val job = validate(body); val now = clock()
        val due = ScheduleRule.parse(job.getJSONObject("rule")).nextAfter(now) ?: error("请选择未来的执行时间")
        job.put("id", UUID.randomUUID().toString()).put("binding", binding()).put("created_at_ms", now)
            .put("next_due_ms", if (job.getBoolean("enabled")) due else JSONObject.NULL).put("waiting_reason", JSONObject.NULL).put("history", JSONArray())
        persist(job); return JSONObject(job.toString())
    }
    @Synchronized fun update(id: String, patch: JSONObject): JSONObject {
        val old = get(id); val body = JSONObject()
        for (key in listOf("device_id", "goal", "mode", "allowed_packages", "rule", "enabled")) body.put(key, old.get(key))
        patch.keys().forEach { body.put(it, patch.get(it)) }; val fresh = validate(body)
        val changed = fresh.getJSONObject("rule").toString() != old.getJSONObject("rule").toString() || fresh.getBoolean("enabled") != old.getBoolean("enabled")
        val due = if (fresh.getBoolean("enabled") && changed) ScheduleRule.parse(fresh.getJSONObject("rule")).nextAfter(clock())
            else if (old.isNull("next_due_ms")) null else old.getLong("next_due_ms")
        check(!fresh.getBoolean("enabled") || due != null) { "请选择未来的执行时间" }
        fresh.keys().forEach { old.put(it, fresh.get(it)) }
        old.put("next_due_ms", if (fresh.getBoolean("enabled")) due else JSONObject.NULL).put("waiting_reason", JSONObject.NULL)
        // Explicit editing in a new connection rebinds the schedule; silent account switches do not.
        old.put("binding", binding()); persist(old); return JSONObject(old.toString())
    }
    @Synchronized fun delete(id: String): JSONObject { get(id); commit(jobs.filter { it.getString("id") != id }); return JSONObject().put("deleted", true) }
    /** A manual decision applies only to the currently due occurrence. */
    @Synchronized fun control(id: String, action: String, delayMs: Long = 600000L): JSONObject {
        val job = get(id); val now = clock()
        check(job.getBoolean("enabled") && !job.isNull("next_due_ms") && job.getLong("next_due_ms") <= now) { "这次计划已处理或还未到时间" }
        val due = job.getLong("next_due_ms")
        when (action) {
            "execute" -> job.put("manual_execute_due_ms", due).put("waiting_reason", JSONObject.NULL)
            "postpone" -> {
                require(delayMs in 60000L..86400000L)
                job.put("next_due_ms", now + delayMs).put("waiting_reason", "user_postponed").remove("manual_execute_due_ms")
            }
            "skip" -> {
                val history = job.getJSONArray("history")
                history.put(JSONObject().put("scheduled_at_ms", due).put("at_ms", now).put("status", "skipped").put("reason", "user_skipped").put("run_id", JSONObject.NULL))
                while (history.length() > 100) history.remove(0)
                val next = ScheduleRule.parse(job.getJSONObject("rule")).nextAfter(now)
                job.put("next_due_ms", next ?: JSONObject.NULL).put("enabled", next != null).put("waiting_reason", JSONObject.NULL).remove("manual_execute_due_ms")
            }
            else -> throw IllegalArgumentException("请选择执行、推迟或跳过")
        }
        persist(job); return JSONObject(job.toString())
    }
    fun nextDue(): Long? = jobs.filter { it.getBoolean("enabled") && !it.isNull("next_due_ms") }.minOfOrNull { it.getLong("next_due_ms") }
    /** Remove only this occurrence's manual approval after an immediate attempt could not start. */
    @Synchronized internal fun clearManualDecision(id: String, due: Long) {
        val job = get(id)
        if (!job.isNull("next_due_ms") && job.optLong("next_due_ms") == due && job.has("manual_execute_due_ms") && job.optLong("manual_execute_due_ms") == due) {
            job.remove("manual_execute_due_ms"); persist(job)
        }
    }
    @Synchronized fun tick(port: SchedulePort, onlyId: String? = null) {
        val now = clock()
        for (original in jobs.toList().filter { onlyId == null || it.optString("id") == onlyId }.sortedBy { it.optLong("next_due_ms", Long.MAX_VALUE) }) {
            val job = get(original.getString("id")); val history = job.getJSONArray("history"); var changed = false
            if ((0 until history.length()).any { history.getJSONObject(it).optString("status") == "dispatching" }) {
                // A previous write may have failed after run creation. Quarantine even without restart.
                for (i in 0 until history.length()) if (history.getJSONObject(i).optString("status") == "dispatching")
                    history.getJSONObject(i).put("status", "uncertain").put("reason", "dispatch_unconfirmed")
                persist(job.put("enabled", false).put("next_due_ms", JSONObject.NULL).put("waiting_reason", "review_uncertain_dispatch"))
                continue
            }
            val currentBinding = binding()
            for (i in 0 until history.length()) {
                if (binding() != currentBinding) break
                val entry = history.getJSONObject(i)
                if (entry.optString("status") == "started" && !entry.isNull("run_id") &&
                    entry.optString("binding", job.getString("binding")) == currentBinding) {
                    val status = runCatching { port.status(entry.getString("run_id")) }.getOrNull()
                    if (binding() != currentBinding) break
                    if (status in terminal) { entry.put("status", status).put("finished_at_ms", now); changed = true }
                    else if (status == "missing") {
                        entry.put("status", "unavailable").put("reason", "run_missing").put("checked_at_ms", clock())
                        changed = true
                    }
                }
            }
            if (!job.getBoolean("enabled") || job.isNull("next_due_ms") || job.getLong("next_due_ms") > now) { if (changed) persist(job); continue }
            val due = job.getLong("next_due_ms"); val missed = now - due > GRACE_MS
            if (!missed) {
                val reason = if (job.getString("binding") != binding()) "connection_changed" else port.readiness(JSONObject(job.toString()))
                if (reason != null) { if (changed || job.optString("waiting_reason") != reason) persist(job.put("waiting_reason", reason)); continue }
            }
            val entry = JSONObject().put("scheduled_at_ms", due).put("at_ms", now).put("status", if (missed) "missed" else "dispatching")
                .put("run_id", JSONObject.NULL).put("binding", currentBinding)
            if (missed) entry.put("reason", job.optString("waiting_reason").takeIf { it.isNotBlank() && it != "null" } ?: "host_unavailable_at_due_time")
            history.put(entry); while (history.length() > 100) history.remove(0)
            val next = ScheduleRule.parse(job.getJSONObject("rule")).nextAfter(now)
            job.put("next_due_ms", next ?: JSONObject.NULL).put("enabled", next != null).put("waiting_reason", JSONObject.NULL)
            persist(job) // Irrevocable occurrence claim. Unknown results are never automatic retries.
            if (missed) continue
            try {
                val runId = port.create(JSONObject(job.toString())); require(runId.isNotBlank())
                entry.put("status", "started").put("run_id", runId); persist(job)
                port.start(runId)
            } catch (_: Exception) {
                entry.put("status", "uncertain").put("reason", "dispatch_unconfirmed")
                job.put("enabled", false).put("next_due_ms", JSONObject.NULL).put("waiting_reason", "review_uncertain_dispatch"); persist(job)
            } finally { port.finishDispatch() }
        }
    }
}
