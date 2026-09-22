package dev.doppel.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The relay carries commands; this host uses the same local engine and device worker as the phone UI. */
internal class ServerTaskHost(context: Context) {
    data class SessionRef(val accountId: String, val sessionId: String, val generation: Long)
    private val app = context.applicationContext
    private val ledger = ServerOperationStore(app)
    private val prefs = app.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val revoked = ConcurrentHashMap.newKeySet<String>()
    private val permits = ConcurrentHashMap<String, AutoCloseable>()
    private fun runtime() = DirectRuntime.get(app)

    fun accept(session: SessionRef, command: JSONObject, mayAccept: () -> Boolean, isCurrent: () -> Boolean,
               onReceipt: (JSONObject) -> Unit) {
        val input = JSONObject(command.toString())
        val ticket = TaskControl.currentGeneration()
        TaskControl.serialize {
            synchronized(this) {
                var claimed = false
                var operation = ""
                var mutated = false
                try {
                    validate(input, session.sessionId)
                    operation = input.getString("operation_id")
                    val kind = input.getString("kind")
                    val payload = input.getJSONObject("payload")
                    claimed = ledger.claim(session.accountId, session.sessionId, operation, kind,
                        digest(canonical(JSONObject().put("kind", kind).put("payload", payload))))
                    if (!claimed) {
                        // A crash between claiming and publishing is queried, never executed again.
                        val prior = ledger.find(session.accountId, session.sessionId, operation)!!
                        val receipt = prior.optJSONObject("latest_receipt") ?: recover(session, prior)
                        onReceipt(receipt); return@synchronized
                    }
                    check(session.sessionId !in revoked && TaskControl.isCurrent(ticket) && mayAccept()) { "operation_expired" }
                    check(FirstUseConsent.isAccepted(app) && DirectMode.isEnabled(app)) { "permission_required" }
                    val result = if (kind == "create_task") create(session, operation, payload, ticket, mayAccept, isCurrent) { mutated = true }
                        else control(session, operation, payload, { TaskControl.isCurrent(ticket) && mayAccept() }, isCurrent) { mutated = true }
                    onReceipt(result)
                } catch (failure: Exception) {
                    if (claimed) runCatching {
                        val row = ledger.find(session.accountId, session.sessionId, operation)!!
                        val taskId = row.optString("task_id").takeUnless { it.isBlank() || it == "null" }
                        val task = taskId?.let { id -> runtime().serverRuns(session.accountId, session.sessionId).firstOrNull { it.optString("id") == id } }
                        if (mutated && task != null && !TaskPresentation.terminal(task.optString("status"))) {
                            permits.remove(taskId)?.close()
                            TaskControl.installExecutionPermit(taskId) { false }.close()
                            runCatching { runtime().serverStop(taskId) }
                        }
                        val code = failure.message?.takeIf { it in ERRORS } ?: "phone_not_ready"
                        // A linked task may already have changed even if persistence/startup failed.
                        onReceipt(write(session, operation, if (mutated) "outcome_unknown" else "rejected", task, code))
                    }
                }
            }
        }
    }

    private fun create(session: SessionRef, operation: String, payload: JSONObject, ticket: Long,
                       mayAccept: () -> Boolean, current: () -> Boolean, onMutation: () -> Unit): JSONObject {
        run {
            check(ModelProviders(app).isReady()) { "permission_required" }
            check(TaskControl.isCurrent(ticket) && mayAccept()) { "operation_expired" }
            val id = "server-run-" + digest(session.accountId + "\n" + operation)
            ledger.attachTask(session.accountId, session.sessionId, operation, id)
            installPermit(session, id, current)
            val body = JSONObject(payload.toString()).put("device_id", DirectRuntime.DEVICE_ID)
                .put("source", "user").put("conversation_enabled", true).put("defer_start", true)
                .put("request_id", TaskSubmissionKey.create(nonce = id))
            onMutation()
            val run = runtime().serverCreate(body, id, SplitTaskEngine.ServerOwner(session.accountId, session.sessionId, true))
            val receipt = write(session, operation, "accepted_by_phone", run)
            check(TaskControl.isCurrent(ticket) && current()) { "operation_expired" }
            check(TaskControl.wakeQueue(app, ticket)) { "phone_not_ready" }
            return receipt
        }
    }

    private fun control(session: SessionRef, operation: String, payload: JSONObject,
                        mayAccept: () -> Boolean, current: () -> Boolean, onMutation: () -> Unit): JSONObject {
        check(payload.getString("expected_phone_session_id") == session.sessionId) { "phone_session_changed" }
        val id = payload.getString("task_id")
        val action = payload.getString("action")
        val prior = runtime().serverRuns(session.accountId, session.sessionId).firstOrNull { it.optString("id") == id }
            ?: error("stale_task")
        val queued = prior.optString("status") == "queued"
        check(queued || !AutomaticUnlockSession.active) { "protected" }
        if (action == "resume") check(readiness() == null) { readiness() ?: "phone_not_ready" }
        check(runtime().serverRuns(session.accountId, session.sessionId).any { it.optString("id") == id }) { "stale_task" }
        ledger.attachTask(session.accountId, session.sessionId, operation, id)
        val run = runtime().serverControl(id, session.accountId, session.sessionId,
            payload.getLong("expected_task_revision"), action) {
                val allowed = session.sessionId !in revoked && mayAccept()
                if (allowed) onMutation()
                allowed
            }
        if (action == "resume") {
            val owned = runtime().serverRuns(session.accountId, session.sessionId, true).any { it.optString("id") == id }
            if (owned) installPermit(session, id, current)
            check(prefs.edit().putString("active_run", id).commit()) { "storage_unavailable" }
            check(current() && TaskControl.startWorker(app)) { "phone_not_ready" }
        } else if (!queued) {
            check(DoppelAccessibilityService.instance?.awaitExecutionStopped(setOf(id)) != false) { "stop_unconfirmed" }
            if (action == "cancel") clearActive(run)
        }
        if (action == "cancel") {
            permits.remove(id)?.close()
            AutomaticQueuedTaskAdmission.complete(id, false)
            runCatching { TaskControl.wakeQueue(app) }
        }
        return write(session, operation, "control_applied", run)
    }

    private fun installPermit(session: SessionRef, id: String, current: () -> Boolean) {
        permits.remove(id)?.close()
        permits[id] = TaskControl.installExecutionPermit(id) { session.sessionId !in revoked && current() }
    }

    fun invalidate(session: SessionRef, reason: String, remoteOnly: Boolean, complete: (JSONObject) -> Unit) {
        if (!remoteOnly) revoked.add(session.sessionId)
        // Close authority before waiting in the user-control queue, including locally created owned tasks.
        runCatching { runtime().serverRuns(session.accountId, session.sessionId, remoteOnly).forEach {
            val id = it.getString("id")
            permits.remove(id)?.close()
            TaskControl.installExecutionPermit(id) { false }.close()
        } }
        TaskControl.serialize {
            var stopped = false
            try {
                synchronized(this) {
                    val owned = runtime().serverRuns(session.accountId, session.sessionId, remoteOnly)
                    val runs = owned.filterNot { TaskPresentation.terminal(it.optString("status")) }
                    // The transport closes remote leases synchronously; explicit revocation also closes local owned runs.
                    for (run in runs) {
                        val id = run.getString("id")
                        permits.remove(id)?.close()
                        TaskControl.installExecutionPermit(id) { false }.close()
                    }
                    if (runs.isNotEmpty()) {
                        for (run in runs) {
                            val result = runtime().serverStop(run.getString("id"))
                            clearActive(result)
                            // Exact run matching keeps an unrelated protected automatic task untouched.
                            AutomaticUnlockSession.taskState(result.getString("id"), "cancelled")
                            check(AutomaticUnlockSession.awaitRunCleanup(result.getString("id"))) { "stop_unconfirmed" }
                        }
                    }
                    check(DoppelAccessibilityService.instance?.awaitExecutionStopped(owned.map { it.getString("id") }.toSet()) != false) { "stop_unconfirmed" }
                    refreshReceipts(session)
                    runtime().checkpoint()
                    stopped = true
                }
            } catch (_: Exception) { /* Admission stays closed; do not turn a failed save into a stop ACK. */ }
            complete(JSONObject().put("stopped", stopped))
        }
    }

    @Synchronized fun snapshot(session: SessionRef): JSONObject {
        if (!FirstUseConsent.isAccepted(app) || !DirectMode.isEnabled(app) || session.sessionId in revoked)
            return JSONObject().put("available_operations", JSONArray()).put("reason_code", "permission_required").put("current_task", JSONObject.NULL)
                .put("recent_task_events", JSONArray())
        refreshReceipts(session)
        val run = runtime().serverTask(session.accountId, session.sessionId)
        val reason = readiness()
        val active = run?.takeUnless { TaskPresentation.terminal(it.optString("status")) }
        val controls = if (reason == "protected") emptyList() else active?.let { runtime().serverControls(it) }.orEmpty()
            .filter { it != "resume" || reason == null }
        val available = (if (ModelProviders(app).isReady()) listOf("create_task") else emptyList()) + controls
        return JSONObject().put("available_operations", JSONArray(available))
            .put("reason_code", reason ?: if ("create_task" in available) JSONObject.NULL else if (runtime().hasUnfinishedRun()) "busy" else "permission_required")
            .put("current_task", run?.let { metadata(it, controls) } ?: JSONObject.NULL)
            .put("recent_task_events", runtime().serverTaskEvents(session.accountId, session.sessionId))
    }

    @Synchronized fun pendingReceipts(session: SessionRef): List<JSONObject> {
        refreshReceipts(session)
        return ledger.pendingReceipts(session.accountId, session.sessionId)
    }
    fun acknowledgeReceipt(session: SessionRef, operationId: String, receiptSeq: Long): Boolean =
        ledger.acknowledgeReceipt(session.accountId, session.sessionId, operationId, receiptSeq)

    private fun readiness(): String? = when {
        AutomaticUnlockSession.active || DirectMode.settingsVisible || PaymentConsent.settingsVisible -> "protected"
        AutomaticUnlockSession.locked(app) -> "locked"
        DoppelAccessibilityService.instance == null -> "accessibility_off"
        else -> null
    }
    private fun metadata(run: JSONObject, controls: List<String>) = JSONObject().put("task_id", run.getString("id"))
        .put("status", run.getString("status")).put("revision", run.optLong("revision"))
        .put("queue_position", run.optInt("queue_position"))
        .put("step_index", run.optLong("command_sequence")).put("allowed_controls", JSONArray(controls))

    private fun clearActive(run: JSONObject) {
        val id = run.getString("id")
        synchronized(prefs) {
            if (prefs.getString("active_run", "") == id) check(prefs.edit().remove("active_run").commit()) { "storage_unavailable" }
        }
        main.post { DeviceWorkerService.instance?.acceptEndedRun(run) }
    }
    private fun recover(session: SessionRef, row: JSONObject): JSONObject {
        val task = runtime().serverRuns(session.accountId, session.sessionId).firstOrNull { it.optString("id") == row.optString("task_id") }
        return write(session, row.getString("operation_id"), if (row.getString("kind") == "create_task" && task != null)
            task.optString("status").takeIf(TaskPresentation::terminal) ?: "accepted_by_phone" else "outcome_unknown", task)
    }
    private fun refreshReceipts(session: SessionRef) {
        val tasks = runtime().serverRuns(session.accountId, session.sessionId).associateBy { it.getString("id") }
        for (row in ledger.recordsForSession(session.accountId, session.sessionId)) {
            if (row.optString("kind") != "create_task") continue
            val task = tasks[row.optString("task_id")] ?: continue
            val status = task.optString("status")
            val prior = row.optJSONObject("latest_receipt")
            if (TaskPresentation.terminal(status) && prior?.optString("phase") !in setOf("completed", "failed", "cancelled", "rejected")) {
                runtime().checkpoint()
                write(session, row.getString("operation_id"), status, task)
                permits.remove(task.getString("id"))?.close()
            } else if (!TaskPresentation.terminal(status) && prior?.optString("phase") == "accepted_by_phone" &&
                prior.optString("task_status") != status) {
                write(session, row.getString("operation_id"), "accepted_by_phone", task)
            }
        }
    }
    private fun write(session: SessionRef, operation: String, phase: String, run: JSONObject?, error: String? = null): JSONObject =
        ledger.appendReceipt(session.accountId, session.sessionId, operation, JSONObject().put("phase", phase)
            .put("task_status", run?.optString("status") ?: JSONObject.NULL)
            .put("task_revision", run?.optLong("revision") ?: JSONObject.NULL).put("error_code", error ?: JSONObject.NULL))

    companion object {
        private val ERRORS = setOf("operation_expired", "operation_conflict", "phone_not_ready", "phone_session_changed", "stale_task",
            "busy", "locked", "protected", "accessibility_off", "permission_required", "storage_unavailable", "stop_unconfirmed")
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
        }
        internal fun validate(command: JSONObject, sessionId: String) {
            fun id(key: String, obj: JSONObject = command) = (obj.get(key) as? String)?.also {
                require(it.length in 1..128 && it.all { c -> c.code in 33..126 })
            } ?: error("Invalid identifier")
            require(command.getString("type") == "command" && id("session_id") == sessionId)
            id("operation_id")
            require(command.opt("expires_at_ms").let { (it is Long || it is Int) && (it as Number).toLong() >= 0 })
            val payload = command.getJSONObject("payload")
            when (command.getString("kind")) {
                "create_task" -> {
                    require(payload.keys().asSequence().all { it in setOf("goal", "mode", "title") })
                    val goal = payload.get("goal") as? String ?: error("Invalid goal")
                    require(goal.isNotBlank() && goal.codePointCount(0, goal.length) <= 8000)
                    require(payload.opt("mode") in setOf("ask", "assist", "full"))
                    if (payload.has("title")) {
                        val title = payload.get("title") as? String ?: error("Invalid title")
                        require(title.codePointCount(0, title.length) <= 120)
                    }
                }
                "control_task" -> {
                    require(payload.keys().asSequence().toSet() == setOf("task_id", "action", "expected_task_revision", "expected_phone_session_id"))
                    id("task_id", payload); id("expected_phone_session_id", payload)
                    require(payload.opt("action") in setOf("pause", "resume", "cancel"))
                    require(payload.opt("expected_task_revision").let { (it is Long || it is Int) && (it as Number).toLong() >= 0 })
                }
                else -> error("Invalid operation kind")
            }
        }
    }
}
