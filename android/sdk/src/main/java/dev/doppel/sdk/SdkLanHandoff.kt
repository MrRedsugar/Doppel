package dev.doppel.sdk

import android.content.Context
import android.os.SystemClock
import dev.doppel.sdk.companion.CompanionAuthContext
import dev.doppel.sdk.companion.CompanionProtocolException
import dev.doppel.sdk.companion.CompanionResponse
import org.json.JSONObject
import java.util.UUID

/** One short-lived human control session per phone. No model, task resume, stored pixels or cloud I/O. */
internal class SdkLanHandoff(private val context: Context) {
    private class Session(val auth: CompanionAuthContext, val runId: String, val scope: String,
                          val generation: Long, val network: () -> Boolean) {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = SystemClock.elapsedRealtime()
        var frame: JSONObject? = null
        var frameAt = 0L
        val receipts = linkedMapOf<String, Pair<String, JSONObject>>()
    }
    @Volatile private var session: Session? = null
    private val scopes = setOf("state", "screen_control")
    private fun fail(status: Int, code: String): Nothing = throw CompanionProtocolException(status, code)
    private fun stopped(runId: String, scope: String, requireLocalPause: Boolean = false): Boolean {
        val connection = Gateway(context).captureReviewConnection()
        if (connection.scope != scope) return false
        val state = if (connection.direct) DirectRuntime.get(context).companionState() else {
            val worker = DeviceWorkerService.instance ?: return false
            if (requireLocalPause && !worker.isPaused) return false
            worker.companionGatewayState(scope)
        }
        return state?.let {
            it.optString("id") == runId && it.optString("status") in setOf("paused", "awaiting_input", "awaiting_approval")
        } == true
    }

    private fun current(owner: Session): Boolean = runCatching {
        session === owner && SystemClock.elapsedRealtime() - owner.touched in 0 until 12_000 &&
            owner.network() && FirstUseConsent.isAccepted(context) && TaskControl.isCurrent(owner.generation) &&
            Gateway(context).captureReviewConnection().scope == owner.scope &&
            !AutomaticUnlockSession.active && !AutomaticUnlockSession.locked(context) &&
            !DirectMode.settingsVisible && !LoginAssist.settingsVisible && !PaymentConsent.settingsVisible &&
            owner.auth.withAuthorization(scopes) { true } && stopped(owner.runId, owner.scope, requireLocalPause = true)
    }.getOrDefault(false)

    @Synchronized fun handle(auth: CompanionAuthContext, operation: String, body: JSONObject, network: () -> Boolean): CompanionResponse {
        auth.withAuthorization(scopes) { Unit }
        if (!network()) {
            if (session?.auth?.pairId == auth.pairId) session = null
            fail(403, "lan_required")
        }
        if (!FirstUseConsent.isAccepted(context)) fail(403, "consent_required")
        val gateway = Gateway(context)
        val connection = gateway.captureReviewConnection()
        if (!connection.direct && !gateway.isConnected()) fail(501, "capability_unavailable")
        if (operation == "start") {
            if (AutomaticUnlockSession.locked(context)) fail(409, "device_locked")
            if (AutomaticUnlockSession.active) fail(409, "protected_session")
            val runId = body.getString("run_id")
            val ticket = TaskControl.currentGeneration()
            val worker = DeviceWorkerService.instance
            if (!stopped(runId, connection.scope)) fail(409, "task_not_interrupted")
            val previous = session
            if (previous != null && current(previous) && (previous.auth.pairId != auth.pairId || previous.runId != runId))
                fail(409, "handoff_busy")
            session = null
            val service = DoppelAccessibilityService.instance ?: fail(503, "accessibility_unavailable")
            val generation = if (!connection.direct) {
                // Stop the local executor even if a different gateway client later resumes the server run.
                worker?.pauseForLanHandoff(runId, connection.scope, ticket) ?: fail(409, "task_not_interrupted")
            } else ticket
            val owner = Session(auth, runId, connection.scope, generation, network)
            session = owner
            if (!current(owner) || DeviceWorkerService.instance?.dismissPauseForLanHandoff { current(owner) } == false ||
                !service.awaitExecutionStopped(setOf(runId)) || !current(owner)) {
                session = null; fail(409, "task_not_interrupted")
            }
            return CompanionResponse(200, JSONObject().put("session_id", owner.id).put("run_id", runId))
        }
        val owner = session?.takeIf { it.id == body.optString("session_id") && it.auth.pairId == auth.pairId &&
            it.auth.pairGeneration == auth.pairGeneration } ?: fail(410, "handoff_expired")
        if (operation == "close") {
            session = null
            return CompanionResponse(200, JSONObject().put("status", "closed"))
        }
        if (!current(owner)) { session = null; fail(410, "handoff_expired") }
        owner.touched = SystemClock.elapsedRealtime()
        val service = DoppelAccessibilityService.instance ?: fail(503, "accessibility_unavailable")
        fun allowed() = network() && current(owner)
        if (operation == "frame") {
            val frame = service.captureHandoffFrame(::allowed)
            if (!allowed()) { session = null; fail(410, "handoff_expired") }
            val id = UUID.randomUUID().toString()
            owner.frameAt = SystemClock.elapsedRealtime()
            owner.frame = JSONObject(frame.toString()).apply { remove("image_base64"); put("frame_id", id) }
            frame.put("frame_id", id)
            listOf("_window_id", "_navigation_generation", "_package_name").forEach(frame::remove)
            return auth.withAuthorization(scopes) { CompanionResponse(200, frame) }
        }
        if (operation != "action") fail(404, "route_not_found")
        val action = LanHandoffAction.parse(body)
        owner.receipts[action.id]?.let { (request, response) ->
            if (request != action.canonical) fail(409, "action_conflict")
            return CompanionResponse(200, JSONObject(response.toString()))
        }
        // Never evict action IDs and then accidentally replay a timed-out user click.
        if (owner.receipts.size >= 128) { session = null; fail(410, "handoff_limit") }
        val frame = owner.frame?.takeIf { it.optString("frame_id") == body.optString("frame_id") &&
            SystemClock.elapsedRealtime() - owner.frameAt in 0..5000 } ?: fail(409, "frame_expired")
        owner.frame = null // A failed/uncertain injection also consumes this frame.
        val uncertain = JSONObject().put("status", "unconfirmed")
        owner.receipts[action.id] = action.canonical to uncertain
        val result = service.executeHandoffAction(owner.runId, frame, body, ::allowed)
        owner.receipts[action.id] = action.canonical to JSONObject(result.toString())
        return CompanionResponse(200, result)
    }
}

/** Strict native command boundary, deliberately independent of AI prompts and intent parsing. */
internal data class LanHandoffAction(val id: String, val canonical: String) {
    companion object {
        fun parse(body: JSONObject): LanHandoffAction {
            fun invalid(): Nothing = throw CompanionProtocolException(422, "invalid_request")
            fun text(key: String, max: Int, empty: Boolean = false): String = (body.opt(key) as? String)
                ?.takeIf { it.length <= max && (empty || it.isNotBlank()) } ?: invalid()
            val kind = text("kind", 24)
            val id = text("action_id", 128)
            text("session_id", 256); text("frame_id", 256)
            val fields = mutableSetOf("session_id", "frame_id", "action_id", "kind")
            fun coordinate(key: String) {
                fields.add(key)
                val value = (body.opt(key) as? Number)?.toDouble() ?: invalid()
                if (!value.isFinite() || value !in 0.0..1.0) invalid()
            }
            fun duration(min: Long, max: Long) {
                fields.add("duration_ms")
                val value = body.opt("duration_ms")
                if (value !is Int && value !is Long || (value as Number).toLong() !in min..max) invalid()
            }
            when (kind) {
                "tap" -> { coordinate("x"); coordinate("y") }
                "long_press" -> { coordinate("x"); coordinate("y"); duration(400, 3000) }
                "swipe" -> { coordinate("x"); coordinate("y"); coordinate("end_x"); coordinate("end_y"); duration(80, 3000) }
                "type" -> { fields.add("text"); text("text", 4000, true) }
                "back", "home", "recents" -> Unit
                else -> invalid()
            }
            if (body.keys().asSequence().any { it !in fields }) invalid()
            val serialized = JSONObject().apply { fields.sorted().forEach { put(it, body.get(it)) } }.toString()
            // Retain only a digest for deduplication, never human-entered passwords in the receipt cache.
            val canonical = java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return LanHandoffAction(id, canonical)
        }
    }
}
