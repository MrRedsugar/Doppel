package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

internal object SessionTrajectory {
    private const val KEY = "_session_trajectory"
    private const val MAX_TRANSACTIONS = 8
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_OBSERVATION_CHARS = 12000
    private val readKinds = setOf("observe", "wait")
    private val statusValues = setOf("ok", "error", "stale", "cancelled", "blocked", "timeout")
    private val codePattern = Regex("[A-Za-z0-9_.:-]{1,100}")
    private val feedbackKeys = listOf("recovery_feedback", "visual_tool_feedback", "model_protocol_feedback", "validation_diagnostic")
    private val planKeys = listOf("local_plan", "local_visual_plan")

    /** Protocol context only. Nothing in this private store is a queue or an execution authority. */
    fun messages(run: JSONObject, current: JSONArray): JSONArray {
        val state = run.optJSONObject(KEY) ?: return JSONArray(current.toString())
        bound(state)
        val output = JSONArray()
        for (index in 0 until current.length()) current.optJSONObject(index)?.takeIf { it.optString("role") == "system" }
            ?.let { output.put(copy(it)) }
        val transactions = state.optJSONArray("transactions") ?: JSONArray()
        for (index in 0 until transactions.length()) {
            val transaction = transactions.optJSONObject(index) ?: continue
            val assistant = transaction.optJSONObject("assistant") ?: continue
            val tool = transaction.optJSONObject("tool") ?: continue
            val id = callId(assistant) ?: continue
            if (tool.optString("role") != "tool" || tool.optString("tool_call_id") != id || tool.opt("content") !is String) continue
            transaction.optJSONArray("observations")?.let { observations ->
                for (j in 0 until observations.length()) observations.optJSONObject(j)?.let { output.put(copy(it)) }
            }
            output.put(copy(assistant)).put(copy(tool))
        }
        for (index in 0 until current.length()) current.optJSONObject(index)?.takeIf { it.optString("role") != "system" }
            ?.let { output.put(copy(it)) }
        return output
    }

    /** Called after offered-tool validation, before parsing a copy or starting host work. */
    fun proposed(run: JSONObject, current: JSONArray, assistant: JSONObject) {
        val state = state(run)
        if (callId(assistant) == null || containsImage(assistant)) { discarded(state); bound(state); return }
        if (state.has("pending")) invalidate(run, "新请求替代了未结算事务；旧工作结果未确认")
        val pending = JSONObject().put("assistant", copy(assistant)).put("observations", observation(current))
            .put("baseline", hostSnapshot(run)).put("receipts", JSONArray())
        // Reject an individual oversized transaction before evicting any useful complete history.
        if (size(pending) > MAX_BYTES - 2048) { discarded(state); bound(state); return }
        state.put("pending", pending)
        bound(state)
    }

    /** The caller has matched its live command; both identity layers remain distinct here. */
    fun receipt(run: JSONObject, command: JSONObject, result: JSONObject) {
        val state = run.optJSONObject(KEY) ?: return
        val pending = state.optJSONObject("pending") ?: return
        val id = command.optString("id")
        val runId = run.optString("id")
        if (id.isBlank() || runId.isBlank() || command.optString("run_id") != runId ||
            result.optString("run_id") != runId || result.optString("command_id") != id || result.optString("status") !in statusValues) return
        val receipts = pending.optJSONArray("receipts") ?: return
        if ((0 until receipts.length()).any { receipts.optJSONObject(it)?.optString("command_id") == id }) return
        val actual = JSONObject().put("status", result.getString("status"))
        result.optJSONObject("data")?.let { actual.put("data", resultFacts(it)) }
        receipts.put(JSONObject().put("command_id", id).put("kind", command.optString("kind"))
            .put("result", actual))
        bound(state)
    }

    /** Call only at the next remote-request boundary, after deferred GUI/local-plan processing. */
    fun settle(run: JSONObject) = close(run, null)

    /** Revokes pending context, not historical facts; absence of a receipt never proves non-execution. */
    fun invalidate(run: JSONObject, reason: String) = close(run, reason)

    fun clear(run: JSONObject) { run.remove(KEY) }

    private fun close(run: JSONObject, interrupted: String?) {
        val state = run.optJSONObject(KEY) ?: return
        val pending = state.optJSONObject("pending") ?: return
        state.remove("pending")
        val assistant = pending.optJSONObject("assistant")
        val id = assistant?.let(::callId)
        if (assistant == null || id == null) { discarded(state); bound(state); return }
        val receipts = pending.optJSONArray("receipts") ?: JSONArray()
        val summary = JSONObject().put("source", "host_transaction_outcome")
            .put("run_status", run.optString("status"))
            .put("execution_status", if ((0 until receipts.length()).any {
                receipts.optJSONObject(it)?.optString("kind")?.let { kind -> kind !in readKinds } == true
            }) "host_results_recorded" else "unconfirmed")
            .put("pending_disposition", if (interrupted == null) "settled" else "revoked")
            .put("commands_replayable", false).put("proves_business_success", false)
            .put("receipts", JSONArray(receipts.toString()))
        if (interrupted != null) summary.put("reason", interrupted)
        val snapshot = hostSnapshot(run)
        val baseline = pending.optJSONObject("baseline") ?: JSONObject()
        for (key in snapshot.keys()) {
            val value = snapshot.get(key)
            if (!baseline.has(key) || baseline.get(key).toString() != value.toString()) summary.put(key, value)
        }
        val tool = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", summary.toString())
        val transaction = JSONObject().put("observations", pending.optJSONArray("observations") ?: JSONArray())
            .put("assistant", assistant).put("tool", tool)
        if (size(transaction) > MAX_BYTES - 2048) { discarded(state); bound(state); return }
        val transactions = state.optJSONArray("transactions") ?: JSONArray().also { state.put("transactions", it) }
        transactions.put(transaction)
        bound(state)
    }

    private fun state(run: JSONObject) = run.optJSONObject(KEY) ?: JSONObject().put("version", 1)
        .put("transactions", JSONArray()).put("discarded_transactions", 0).also { run.put(KEY, it) }
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun size(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8).size
    private fun discarded(state: JSONObject) {
        state.put("discarded_transactions", (state.optLong("discarded_transactions").coerceIn(0, Int.MAX_VALUE.toLong() - 1) + 1).toInt())
    }
    private fun bound(state: JSONObject) {
        val transactions = state.optJSONArray("transactions") ?: JSONArray().also { state.put("transactions", it) }
        state.optJSONObject("pending")?.takeIf { size(it) > MAX_BYTES - 2048 }?.let {
            state.remove("pending"); discarded(state)
        }
        while (transactions.length() > MAX_TRANSACTIONS || size(state) > MAX_BYTES && transactions.length() > 0) {
            transactions.remove(0); discarded(state)
        }
        if (size(state) > MAX_BYTES) { state.remove("pending"); discarded(state) }
    }
    private fun callId(assistant: JSONObject): String? {
        if (assistant.opt("role") != "assistant") return null
        val calls = assistant.optJSONArray("tool_calls") ?: return null
        if (calls.length() != 1) return null
        val call = calls.optJSONObject(0) ?: return null
        val id = (call.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        if (call.opt("type") != "function") return null
        val function = call.optJSONObject("function") ?: return null
        if ((function.opt("name") as? String)?.isNotBlank() != true || function.opt("arguments") !is String) return null
        return id
    }

    /** Work.payload may already contain trajectory messages: archive only its latest user observation. */
    private fun observation(current: JSONArray): JSONArray {
        val latest = (current.length() - 1 downTo 0).mapNotNull { current.optJSONObject(it) }.firstOrNull { it.optString("role") == "user" }
            ?: return JSONArray()
        val text = when (val content = latest.opt("content")) {
            is String -> content.takeUnless { it.contains("data:image/", ignoreCase = true) }
            is JSONArray -> (0 until content.length()).mapNotNull { index ->
                content.optJSONObject(index)?.takeIf { it.optString("type") in setOf("text", "input_text") }
                    ?.opt("text")?.let { it as? String }?.takeUnless { it.contains("data:image/", ignoreCase = true) }
            }.joinToString("\n")
            else -> null
        }
        val bounded = if (text != null && text.length > MAX_OBSERVATION_CHARS)
            text.take(MAX_OBSERVATION_CHARS) + "\n[Historical observation text truncated; use the complete current observation.]" else text
        return if (bounded.isNullOrBlank()) JSONArray() else JSONArray().put(JSONObject().put("role", "user").put("content", bounded))
    }
    private fun containsImage(value: Any?): Boolean = when (value) {
        is String -> value.contains("data:image/", ignoreCase = true)
        is JSONArray -> (0 until value.length()).any { containsImage(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().any { key -> key in setOf("image_base64", "screenshot_base64", "b64_json", "image_url") || containsImage(value.opt(key)) }
        else -> false
    }

    /** No raw observation, arbitrary result text, input value, token, permit, screenshot or arguments. */
    private fun resultFacts(data: JSONObject): JSONObject {
        val output = JSONObject()
        CaptureObservationBinding.sanitize(data.optJSONObject("capture_observation"))?.let { output.put("capture_observation", it) }
        for (key in listOf("no_op", "cancelled", "payment_attempted", "process_started", "process_exited", "node_present",
            "frame_matches", "pixels_match", "result_confirmed")) (data.opt(key) as? Boolean)?.let { output.put(key, it) }
        for (key in listOf("status", "action_state", "human_takeover", "reason_code", "stage", "error_class"))
            (data.opt(key) as? String)?.takeIf { codePattern.matches(it) }?.let { output.put(key, it) }
        for (key in listOf("action_completed_at_elapsed_ms", "source_age_ms", "verification_age_ms", "elapsed_ms", "exit_code"))
            (data.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.let { output.put(key, data.get(key)) }
        for (key in listOf("action_diagnostic", "visual_diagnostic", "read_diagnostic", "shell_diagnostic", "post_action_read_diagnostic"))
            data.optJSONObject(key)?.let { output.put(key, resultFacts(it)) }
        return output
    }

    private fun hostSnapshot(run: JSONObject): JSONObject {
        val snapshot = JSONObject()
        for (key in feedbackKeys) run.optJSONObject(key)?.let { source ->
            val safe = JSONObject()
            for (field in listOf("code", "message", "reason")) (source.opt(field) as? String)?.let { safe.put(field, it) }
            (source.opt("action_executed") as? Boolean)?.let { safe.put("action_executed", it) }
            snapshot.put(key, safe)
        }
        for (key in planKeys) run.optJSONObject(key)?.let { source ->
            val safe = JSONObject()
            for (field in listOf("objective", "state", "reason")) (source.opt(field) as? String)?.let { safe.put(field, it) }
            for (field in listOf("steps", "accepted_steps", "next_step")) (source.opt(field) as? Number)?.let { safe.put(field, it) }
            snapshot.put(key, safe)
        }
        run.optJSONObject("gui_grounding")?.let { source ->
            val safe = JSONObject()
            for (field in listOf("status", "kind")) (source.opt(field) as? String)?.let { safe.put(field, it) }
            (source.opt("source_verified") as? Boolean)?.let { safe.put("source_verified", it) }
            safe.put("proves_dispatch", false); snapshot.put("gui_grounding", safe)
        }
        return snapshot
    }
}
