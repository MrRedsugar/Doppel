package dev.doppel.sdk

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Orders touch takeover and UI requests so a delayed pause cannot follow resume. */
object TaskControl {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // Pending creation tickets from another process require explicit continuation.
    private val generation = AtomicLong(java.util.UUID.randomUUID().mostSignificantBits)
    internal const val EXTRA_GENERATION = "dev.doppel.control_generation"

    fun currentGeneration(): Long = generation.get()
    fun isCurrent(ticket: Long): Boolean = ticket == generation.get()
    internal fun invalidateIfCurrent(ticket: Long): Long? = if (generation.compareAndSet(ticket, ticket + 1)) ticket + 1 else null
    internal fun serialize(work: () -> Unit) { io.execute(work) }
    private val executionGate = TaskExecutionGate(::currentGeneration)
    internal fun installExecutionPermit(runId: String, isCurrent: () -> Boolean): AutoCloseable =
        executionGate.install(runId, isCurrent)
    internal fun captureExecutionPermit(runId: String, ticket: Long, isCurrent: () -> Boolean = { true }): () -> Boolean =
        executionGate.capture(runId, ticket, isCurrent)
    internal fun reconcileCreatedRun(context: Context, run: JSONObject, ticket: Long,
                                     connection: Gateway.ReviewConnection = Gateway(context.applicationContext).captureReviewConnection(),
                                     complete: (JSONObject?, String?) -> Unit) {
        val gateway = Gateway(context.applicationContext)
        io.execute {
            var result: JSONObject? = null
            var error: String? = null
            try {
                val id = run.getString("id")
                // Compensation shares the user-control queue, so it cannot overtake a later resume.
                try {
                    result = if (run.optString("status") == "queued" || isCurrent(ticket)) run else connection.request("POST", "/runs/$id/pause")
                    check(result.optString("id") == id) { "任务状态与请求不匹配" }
                    check(connection.scope == gateway.reviewScope()) { "连接已改变，任务保留在原队列" }
                } catch (_: Exception) { result = null; error = "任务状态未确认，执行保持暂停" }
            } catch (_: Exception) { result = null; error = "任务状态未确认，执行保持暂停" }
            main.post {
                if (connection.scope == gateway.reviewScope()) complete(result, error)
                else complete(null, "连接已改变，任务保留在原队列")
            }
        }
    }
    fun startWorker(context: Context, ticket: Long = currentGeneration()): Boolean {
        if (!isCurrent(ticket)) return false
        context.startForegroundService(Intent(context, DeviceWorkerService::class.java).putExtra(EXTRA_GENERATION, ticket))
        return true
    }
    /** Enqueue/wakeup is not a request to resume an interrupted execution. */
    fun wakeQueue(context: Context, ticket: Long = currentGeneration()): Boolean {
        if (!isCurrent(ticket)) return false
        context.startForegroundService(Intent(context, DeviceWorkerService::class.java)
            .setAction(DeviceWorkerService.WAKE_QUEUE).putExtra(EXTRA_GENERATION, ticket))
        return true
    }

    fun request(context: Context, runId: String, action: String, body: JSONObject = JSONObject(),
                complete: (JSONObject?, String?) -> Unit = { _, _ -> }) = requestInternal(context, runId, action, body, false, complete)

    internal fun requestKeepingPauseNotice(context: Context, runId: String, action: String,
                                          complete: (JSONObject?, String?) -> Unit) =
        requestInternal(context, runId, action, JSONObject(), true, complete)

    private fun requestInternal(context: Context, runId: String, action: String, body: JSONObject,
                                preserveNotice: Boolean, complete: (JSONObject?, String?) -> Unit) {
        require(action in setOf("pause", "resume", "cancel", "answer"))
        val gateway = Gateway(context.applicationContext)
        val connection = gateway.captureReviewConnection()
        if (action == "cancel" && gateway.prefs.getString("active_run", "") != runId) {
            // The runtime checks the observed status under its lock: a concurrent promotion or
            // resume cannot bypass the normal stop/drain path for an executing task.
            io.execute {
                try {
                    check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                    val current = connection.request("GET", "/runs/$runId")
                    check(current.optString("id") == runId) { "任务状态与请求不匹配" }
                    val status = current.optString("status")
                    if (!TaskPresentation.terminal(status) && status != "queued") {
                        // After process recovery the interrupted FIFO head may not yet have a
                        // local execution pointer. It is still cancellable, never a later task.
                        check(status in setOf("paused", "awaiting_input", "awaiting_approval")) { "任务已开始，请刷新后重试" }
                        val head = connection.request("GET", "/devices/${connection.deviceId}/queue")
                            .optJSONArray("items")?.optJSONObject(0)
                        check(head?.optString("id") == runId && gateway.prefs.getString("active_run", "").isNullOrBlank()) { "执行任务已变化，请刷新后重试" }
                        check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                    }
                    check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                    val response = if (TaskPresentation.terminal(status)) current
                        else connection.request("POST", "/runs/$runId/cancel", JSONObject(body.toString()).put("expected_status", status))
                    check(response.optString("id") == runId) { "任务状态与请求不匹配" }
                    if (status != "queued" && !TaskPresentation.terminal(status)) {
                        check(TaskPresentation.terminal(response.optString("status"))) { "取消结果尚未确认，请刷新后重试" }
                        if (connection.scope == gateway.reviewScope()) {
                            // Stop locally only after the atomic status check; an early local
                            // pause would hide a concurrent resume from expected_status.
                            generation.incrementAndGet()
                            DeviceWorkerService.instance?.suspendLocallyPreservingPauseNotice(preserveNotice)
                        }
                        check(DoppelAccessibilityService.instance?.awaitExecutionStopped(setOf(runId)) != false) { "正在等待当前操作停止，请稍后重试" }
                        if (connection.scope == gateway.reviewScope()) AutomaticUnlockSession.taskState(runId, response.optString("status"))
                    }
                    main.post {
                        if (connection.scope != gateway.reviewScope()) complete(null, "连接已改变，请刷新后重试")
                        else {
                            DeviceWorkerService.instance?.acceptEndedRun(response)
                            complete(response, null)
                            runCatching { wakeQueue(context) }
                        }
                    }
                } catch (failure: Exception) {
                    main.post { complete(null, failure.message ?: "任务状态已变化，请刷新后重试") }
                }
            }
            return
        }
        val ticket = generation.incrementAndGet()
        DeviceWorkerService.instance?.suspendLocallyPreservingPauseNotice(preserveNotice || action == "pause")
        io.execute {
            var response: JSONObject? = null
            var error: String? = null
            try {
                check(isCurrent(ticket)) { "操作已被后续操作取代" }
                check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                response = if (action in setOf("resume", "pause")) {
                    // A preceding pause may still be in flight when the UI offers continuation.
                    val current = connection.request("GET", "/runs/$runId")
                    check(isCurrent(ticket)) { "操作已被后续操作取代" }
                    check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                    check(current.optString("id") == runId) { "任务状态与请求不匹配" }
                    if (action == "pause") {
                        if (current.optString("status") == "paused") current else connection.request("POST", "/runs/$runId/pause", body)
                    } else if (current.optString("status") == "paused") connection.request("POST", "/runs/$runId/resume", body)
                    else current
                } else connection.request("POST", "/runs/$runId/$action", body)
                check(response.optString("id") == runId) { "任务状态与请求不匹配" }
                check(connection.scope == gateway.reviewScope()) { "连接已改变，请刷新后重试" }
                if (action == "cancel" && TaskPresentation.terminal(response.optString("status"))) {
                    check(DoppelAccessibilityService.instance?.awaitExecutionStopped(setOf(runId)) != false) { "正在等待当前操作停止，请稍后重试" }
                    AutomaticUnlockSession.taskState(runId, response.optString("status"))
                }
            }
            catch (failure: Exception) { response = null; error = failure.message ?: "连接不可用" }
            main.post {
                if (connection.scope != gateway.reviewScope()) complete(null, "连接已改变，请刷新后重试")
                else if (response != null && action == "cancel" && TaskPresentation.terminal(response!!.optString("status"))) {
                    DeviceWorkerService.instance?.acceptEndedRun(response!!)
                    complete(response, error)
                    runCatching { wakeQueue(context) }
                } else if (ticket == generation.get()) complete(response, error) else complete(null, "操作已被后续操作取代")
            }
        }
    }

    fun invalidate() { generation.incrementAndGet() }
}
