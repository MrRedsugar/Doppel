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
    internal fun reconcileCreatedRun(context: Context, run: JSONObject, ticket: Long,
                                     complete: (JSONObject?, String?) -> Unit) {
        val gateway = Gateway(context.applicationContext)
        io.execute {
            var result: JSONObject? = null
            var error: String? = null
            try {
                val id = run.getString("id")
                // Compensation shares the user-control queue, so it cannot overtake a later resume.
                try {
                    result = if (isCurrent(ticket)) run else gateway.request("POST", "/runs/$id/pause")
                    check(result.optString("id") == id) { "任务状态与请求不匹配" }
                } catch (_: Exception) { result = null; error = "任务状态未确认，执行保持暂停" }
                synchronized(gateway.prefs) {
                    val active = gateway.prefs.getString("active_run", "").orEmpty()
                    check(active.isBlank() || active == id) { "当前任务已改变，请查看任务列表" }
                    val edit = gateway.prefs.edit().putString("active_run", id)
                    if (error == null) edit.remove("draft_goal")
                    check(edit.commit()) { "任务记录保存失败" }
                }
            } catch (_: Exception) { result = null; error = "任务状态未确认，执行保持暂停" }
            main.post { complete(result, error) }
        }
    }
    fun startWorker(context: Context, ticket: Long = currentGeneration()): Boolean {
        if (!isCurrent(ticket)) return false
        context.startForegroundService(Intent(context, DeviceWorkerService::class.java).putExtra(EXTRA_GENERATION, ticket))
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
        val ticket = generation.incrementAndGet()
        DeviceWorkerService.instance?.suspendLocallyPreservingPauseNotice(preserveNotice || action == "pause")
        val gateway = Gateway(context.applicationContext)
        io.execute {
            var response: JSONObject? = null
            var error: String? = null
            try {
                check(isCurrent(ticket)) { "操作已被后续操作取代" }
                response = if (action in setOf("resume", "pause")) {
                    // A preceding pause may still be in flight when the UI offers continuation.
                    val current = gateway.request("GET", "/runs/$runId")
                    check(isCurrent(ticket)) { "操作已被后续操作取代" }
                    check(current.optString("id") == runId) { "任务状态与请求不匹配" }
                    if (action == "pause") {
                        if (current.optString("status") == "paused") current else gateway.request("POST", "/runs/$runId/pause", body)
                    } else if (current.optString("status") == "paused") gateway.request("POST", "/runs/$runId/resume", body)
                    else current
                } else gateway.request("POST", "/runs/$runId/$action", body)
                check(response.optString("id") == runId) { "任务状态与请求不匹配" }
            }
            catch (failure: Exception) { response = null; error = failure.message ?: "连接不可用" }
            main.post { if (ticket == generation.get()) complete(response, error) else complete(null, "操作已被后续操作取代") }
        }
    }

    fun invalidate() { generation.incrementAndGet() }
}
