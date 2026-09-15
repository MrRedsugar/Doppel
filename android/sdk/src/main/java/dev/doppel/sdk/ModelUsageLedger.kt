package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/** Token counts only, independent of retained task history. Never stores prompts or credentials. */
internal class ModelUsageLedger(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = File(root, "model-usage-v1.json")
    companion object { private val lock = Any() }
    private fun day(at: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(at))
    private fun count(value: JSONObject, key: String) = (value.opt(key) as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L

    private fun load(): JSONObject {
        if (file.exists()) {
            check(file.length() in 1..262144) { "用量记录大小异常" }
            return JSONObject(file.readText()).also { check(it.optInt("version") == 1) { "用量记录版本不支持" } }
        }
        val state = JSONObject().put("version", 1).put("started_at", now()).put("daily", JSONArray())
            .put("input_tokens", 0L).put("output_tokens", 0L).put("requests", 0L).put("unknown_usage_requests", 0L)
        // This is an explicit one-time lower bound, not a reconstruction of deleted history.
        val legacy = File(root, "direct-runs-v1.json")
        if (legacy.exists()) {
            check(legacy.length() <= 2 * 1024 * 1024) { "原任务用量无法读取" }
            val runs = JSONArray(legacy.readText())
            repeat(runs.length()) { index ->
                val run = runs.getJSONObject(index)
                add(state, day(run.optLong("created_at", now())), count(run, "prompt_tokens"), count(run, "completion_tokens"), count(run, "calls"))
            }
        }
        state.put("historical_seed_input_tokens", state.getLong("input_tokens"))
            .put("historical_seed_output_tokens", state.getLong("output_tokens"))
            .put("historical_coverage", "retained_tasks_at_migration")
        save(state)
        return state
    }

    private fun add(state: JSONObject, date: String, input: Long, output: Long, requests: Long) {
        val daily = state.getJSONArray("daily")
        val row = (0 until daily.length()).map { daily.getJSONObject(it) }.firstOrNull { it.getString("date") == date }
            ?: JSONObject().put("date", date).put("input_tokens", 0L).put("output_tokens", 0L).put("requests", 0L).also { daily.put(it) }
        for ((key, value) in listOf("input_tokens" to input, "output_tokens" to output, "requests" to requests)) {
            state.put(key, Math.addExact(count(state, key), value))
            row.put(key, Math.addExact(count(row, key), value))
        }
        state.put("daily", JSONArray((0 until daily.length()).map { daily.getJSONObject(it) }.sortedBy { it.getString("date") }.takeLast(31)))
    }

    private fun save(state: JSONObject) {
        check(root.isDirectory || root.mkdirs()) { "用量记录目录不可用" }
        val temporary = File(root, "model-usage-v1.json.part")
        try {
            temporary.outputStream().use { out -> out.write(state.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun initialize() = synchronized(lock) { load(); Unit }

    fun record(usage: JSONObject?) = synchronized(lock) {
        val state = load()
        val known = usage != null && usage.opt("prompt_tokens") is Number && usage.opt("completion_tokens") is Number
        add(state, day(now()), usage?.let { count(it, "prompt_tokens") } ?: 0, usage?.let { count(it, "completion_tokens") } ?: 0, 1)
        if (!known) state.put("unknown_usage_requests", count(state, "unknown_usage_requests") + 1)
        save(state)
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        load().apply {
            put("lifetime_input_tokens", getLong("input_tokens")); put("lifetime_output_tokens", getLong("output_tokens"))
            put("lifetime_requests", getLong("requests")); put("source", "local_model_ledger")
        }
    }
}
