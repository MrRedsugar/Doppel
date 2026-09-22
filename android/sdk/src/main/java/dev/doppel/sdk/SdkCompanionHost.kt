package dev.doppel.sdk

import android.content.Context
import dev.doppel.sdk.companion.CompanionAuthContext
import dev.doppel.sdk.companion.CompanionHost
import dev.doppel.sdk.companion.CompanionResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Task reads stay passive; screen control uses a separate, explicitly granted local session. */
internal class SdkCompanionHost(
    private val companionDeviceId: String,
    private val readFacts: (includeState: Boolean) -> Facts,
) : CompanionHost {
    internal data class Facts(val scope: String, val direct: Boolean, val device: JSONObject?, val run: JSONObject?, val available: Boolean = direct)

    constructor(context: Context, companionDeviceId: String) : this(companionDeviceId, { includeState ->
        val gateway = Gateway(context.applicationContext)
        val connection = gateway.captureReviewConnection()
        val readable = connection.direct || gateway.isConnected()
        var run: JSONObject? = null
        var device: JSONObject? = null
        if (includeState) {
            run = if (connection.direct) DirectRuntime.get(context).companionState()
                else if (readable) DeviceWorkerService.instance?.companionGatewayState(connection.scope) else null
            val locked = AutomaticUnlockSession.locked(context)
            device = JSONObject().put("readiness", when {
                !readable -> "unavailable"
                run != null -> "busy"
                locked -> "needs_phone"
                else -> "unavailable" // Task submission is not connected at this milestone.
            }).put("reason_code", if (readable) "companion_read_only" else "runtime_not_connected")
                .put("lock_state", if (locked) "locked" else "unlocked")
                .put("protected_session", AutomaticUnlockSession.active)
        }
        check(connection.scope == gateway.captureReviewConnection().scope) { "Companion scope changed during read" }
        Facts(connection.scope, connection.direct, device, run, readable)
    }) {
        handoffHost = SdkLanHandoff(context.applicationContext)
        readEvents = {
            val gateway = Gateway(context.applicationContext)
            val connection = gateway.captureReviewConnection()
            if (!connection.direct && !gateway.isConnected()) throw dev.doppel.sdk.companion.CompanionProtocolException(501, "capability_unavailable")
            val events = if (connection.direct) DirectRuntime.get(context).companionTaskEvents()
                else GatewayTaskEvents.read(context, connection.scope)
            check(connection.scope == gateway.captureReviewConnection().scope)
            events
        }
    }

    private var handoffHost: SdkLanHandoff? = null
    private var readEvents: (() -> JSONArray)? = null

    override fun readTaskEvents(auth: CompanionAuthContext): CompanionResponse {
        auth.withAuthorization(setOf("state")) { Unit }
        val events = readEvents?.invoke() ?: return unavailable(auth)
        return auth.withAuthorization(setOf("state")) {
            CompanionResponse(200, JSONObject().put("companion_device_id", companionDeviceId).put("events", events))
        }
    }

    override fun handoff(auth: CompanionAuthContext, operation: String, body: JSONObject, currentLan: () -> Boolean): CompanionResponse =
        handoffHost?.handle(auth, operation, body, currentLan) ?: unavailable(auth)

    private var scope: String? = null
    private var epoch = UUID.randomUUID().toString()
    private var revision = 0L
    private var lastState: String? = null

    @Synchronized override fun readSnapshot(auth: CompanionAuthContext, scopeId: String?): CompanionResponse {
        auth.withAuthorization(emptySet()) { Unit }
        val hasState = "state" in auth.grantedScopes
        val facts = readFacts(hasState)
        return auth.withAuthorization(if (hasState) setOf("state") else emptySet()) {
            if (scopeId != null && scopeId != facts.scope) return@withAuthorization failure(409, "scope_changed")
            if (scope != facts.scope) {
                scope = facts.scope
                epoch = UUID.randomUUID().toString()
                revision = 0
                lastState = null
            }
            val value = JSONObject().put("api_version", 1).put("companion_device_id", companionDeviceId)
                .put("scope_id", facts.scope).put("sync_epoch", epoch)
                .put("state_access", if (hasState) "granted" else "denied")
                .put("capabilities", JSONObject().apply {
                    for (name in listOf("task_submit", "task_control", "task_history", "conversation_history",
                        "chat_submit", "locked_task_submit", "voice_input", "remote_delete")) put(name, false)
                    put("task_events", readEvents != null && facts.available && hasState)
                    put("screen_control", handoffHost != null && facts.available && "screen_control" in auth.grantedScopes)
                })
            if (hasState) {
                val state = JSONObject().put("device_state", facts.device ?: JSONObject.NULL)
                    .put("current_run", facts.run ?: JSONObject.NULL)
                // Only fact changes advance the revision; wall-clock polling never does.
                val serialized = state.toString()
                if (serialized != lastState) { revision++; lastState = serialized }
                val run = facts.run?.let { JSONObject(it.toString()) }?.apply {
                    put("scope_id", facts.scope).put("sync_epoch", epoch).put("revision", revision)
                    put("allowed_controls", JSONArray()).put("control_block_reason", "companion_read_only")
                    put("requires_phone_verification", facts.device?.optBoolean("protected_session") == true)
                    put("local_hold", JSONObject.NULL)
                    put("reason", JSONObject().put("code", "unknown").put("message", "").put("next_step", ""))
                }
                value.put("runtime_mode", if (facts.direct) "direct" else "gateway")
                    .put("revision", revision).put("observed_at_ms", System.currentTimeMillis())
                    .put("max_goal_chars", 8000).put("supported_modes", JSONArray())
                    .put("device_state", facts.device ?: JSONObject.NULL).put("current_run", run ?: JSONObject.NULL)
            }
            CompanionResponse(200, value)
        }
    }

    private fun unavailable(auth: CompanionAuthContext) = auth.withAuthorization(emptySet()) {
        failure(501, "capability_unavailable")
    }
    override fun readRun(auth: CompanionAuthContext, scopeId: String, runId: String) = unavailable(auth)
    override fun readHistory(auth: CompanionAuthContext, scopeId: String, collection: String, cursor: String?, limit: Int) = unavailable(auth)
    override fun readConversation(auth: CompanionAuthContext, scopeId: String, conversationId: String, cursor: String?, limit: Int) = unavailable(auth)
    override fun submitOperation(auth: CompanionAuthContext, body: JSONObject) = unavailable(auth)
    override fun readOperation(auth: CompanionAuthContext, scopeId: String, requestId: String) = unavailable(auth)

    private fun failure(status: Int, code: String) = CompanionResponse(status, JSONObject().put("error",
        JSONObject().put("code", code).put("message", if (status == 409) "手机连接已变化，请刷新" else "此接口尚未接入")
            .put("retryable", false)))
}
