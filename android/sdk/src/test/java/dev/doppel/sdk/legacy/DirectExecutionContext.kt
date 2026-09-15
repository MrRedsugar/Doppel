package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Explanatory history only: never an action, target cache, approval, or completion proof. */
internal object DirectExecutionContext {
    private const val MAX_RECEIPTS = 8
    private const val MAX_ANALYSIS = 1000
    private val inputs = setOf("type", "login_phone", "login_code", "login_password")

    private fun state(run: JSONObject): JSONObject = run.optJSONObject("execution_context") ?: JSONObject()
        .put("history_only", true).put("receipts", JSONArray()).also { run.put("execution_context", it) }

    fun snapshot(run: JSONObject): JSONObject = JSONObject(state(run).toString())

    fun page(observation: JSONObject?, frame: VisualFrame?, evidenceId: String?): JSONObject = JSONObject().apply {
        observation?.let {
            put("screen_id", it.optString("screen_id").take(128))
            put("package_name", it.optString("package_name").take(255))
        }
        if (observation != null && frame != null && frame.screenId == observation.optString("screen_id") && frame.packageName == observation.optString("package_name")) {
            put("capture_id", frame.captureId.take(128))
            put("sha256", frame.sha256.take(128))
        }
        evidenceId?.takeIf { it.isNotBlank() }?.let { put("evidence_id", it.take(128)) }
    }

    fun accepted(run: JSONObject, sent: JSONObject, source: JSONObject, result: JSONObject, targetLabel: String?, at: Long) {
        val context = state(run)
        val receipts = context.getJSONArray("receipts")
        while (receipts.length() >= MAX_RECEIPTS) receipts.remove(0)
        val kind = sent.getString("kind")
        val receipt = JSONObject().put("command_id", sent.getString("id")).put("kind", kind)
            .put("accepted_at", at).put("source", source).put("result", result)
            .put("proves_business_success", false)
        // Allowlisted descriptions only; never copy command arguments, worker messages, or input values.
        if (kind !in inputs) {
            val description = JSONObject().put("untrusted", true)
            if (kind == "visual_gesture") {
                sent.optJSONObject("gesture")?.let {
                    description.put("label", it.optString("label").take(220))
                    description.put("screen_context", it.optString("screen_context").take(300))
                }
            } else if (!targetLabel.isNullOrBlank()) description.put("label", targetLabel.take(220))
            if (description.length() > 1) receipt.put("target_description", description)
            val previous = context.optJSONObject("latest_visual_analysis")
            if (previous != null && sameImage(previous.optJSONObject("source"), source))
                receipt.put("before_analysis", JSONObject(previous.toString()))
        }
        if (kind == "launch") receipt.put("package_name", sent.optString("package_name").take(255))
        receipts.put(receipt)
    }

    /** Only the explicit post-gesture read may supply an initially missing result frame. */
    fun completeResultFrame(run: JSONObject, commandId: String?, result: JSONObject) {
        receipt(run, commandId)?.put("result", JSONObject(result.toString()))
    }

    fun interpreted(run: JSONObject, text: String, source: JSONObject, commandId: String?) {
        val interpretation = JSONObject().put("model_interpretation", true).put("untrusted", true)
            .put("source_scope", "historical_frame_not_current_evidence")
            .put("source", JSONObject(source.toString())).put("text", text.take(MAX_ANALYSIS))
        state(run).put("latest_visual_analysis", interpretation)
        val receipt = receipt(run, commandId) ?: return
        val result = receipt.optJSONObject("result") ?: return
        // Bind to the Work's immutable source, not whichever frame happens to be current at accept time.
        if (samePage(source, result) && listOf("capture_id", "evidence_id").all {
                source.optString(it).isNotBlank() && source.optString(it) == result.optString(it)
            }) receipt.put("after_analysis", JSONObject(interpretation.toString()))
    }

    private fun receipt(run: JSONObject, commandId: String?): JSONObject? {
        if (commandId.isNullOrBlank()) return null
        val receipts = state(run).getJSONArray("receipts")
        return (0 until receipts.length()).map { receipts.getJSONObject(it) }.firstOrNull { it.optString("command_id") == commandId }
    }

    private fun samePage(left: JSONObject?, right: JSONObject): Boolean = left != null &&
        listOf("screen_id", "package_name").all { left.optString(it).isNotBlank() && left.optString(it) == right.optString(it) }

    // A canvas may keep its accessibility screen ID while rendering a completely different page.
    private fun sameImage(left: JSONObject?, right: JSONObject): Boolean = samePage(left, right) &&
        listOf("capture_id", "sha256").any { !left!!.optString(it).isBlank() && left.optString(it) == right.optString(it) }
}
