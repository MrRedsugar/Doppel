package dev.doppel.sdk

import org.json.JSONObject

/** System timing is best effort; its failure must never negate an already committed plan. */
internal class ScheduleWakeupStatus(state: String?, private val save: (String) -> Unit,
                                    private val clock: () -> Long = System::currentTimeMillis) {
    private var value = runCatching {
        JSONObject(state ?: error("No prior status")).also {
            require(it.optString("status") in setOf("unknown", "scheduled", "idle", "waiting"))
            require(it.isNull("reason") || it.optString("reason") in setOf("permission_missing", "system_declined", "system_unavailable"))
        }
    }.getOrElse { JSONObject().put("status", "unknown").put("reason", JSONObject.NULL).put("checked_at_ms", 0L).put("status_persisted", false) }

    @Synchronized fun current() = JSONObject(value.toString())

    /** attempt returns scheduled/idle/declined; only stable failure codes cross this boundary. */
    @Synchronized fun refresh(attempt: () -> String): JSONObject {
        var status = "waiting"
        val reason = try {
            when (attempt()) {
                "scheduled" -> { status = "scheduled"; null }
                "idle" -> { status = "idle"; null }
                "declined" -> "system_declined"
                else -> "system_unavailable"
            }
        } catch (_: SecurityException) { "permission_missing" }
        catch (_: RuntimeException) { "system_unavailable" }
        if (value.optString("status") == status && value.opt("reason") == (reason ?: JSONObject.NULL) &&
            value.optBoolean("status_persisted")) return current()
        value = JSONObject().put("status", status).put("reason", reason ?: JSONObject.NULL)
            .put("checked_at_ms", clock()).put("status_persisted", true)
        try { save(value.toString()) }
        catch (_: Exception) { value.put("status_persisted", false) }
        return current()
    }
}
