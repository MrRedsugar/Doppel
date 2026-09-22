package dev.doppel.sdk

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One device-side dispatcher. Durable ordering and atomic promotion belong to the runtime. */
internal class TaskQueueDispatcher(context: Context, private val alive: () -> Boolean,
                                   private val accept: (JSONObject, () -> Boolean) -> Boolean, private val waiting: (String) -> Unit) {
    private val app = context.applicationContext
    private val gateway = Gateway(app)
    private val starting = AtomicBoolean(false)
    val isStarting: Boolean get() = starting.get()
    @Volatile private var headId = ""
    @Volatile private var closed = false
    private var checkedAt = 0L

    fun poll(connection: Gateway.ReviewConnection) {
        if (closed || !alive() || starting.get() || !gateway.prefs.getString("active_run", "").isNullOrBlank()) return
        if (gateway.prefs.getBoolean("queue_dispatch_paused", false)) { waiting("队列已暂停"); return }
        if (connection.deviceId.isBlank() || SystemClock.elapsedRealtime() - checkedAt < 1000) return
        checkedAt = SystemClock.elapsedRealtime()
        val ticket = TaskControl.currentGeneration()
        val snapshot = connection.request("GET", "/devices/${connection.deviceId}/queue")
        if (closed || !alive() || !TaskControl.isCurrent(ticket) || connection.scope != gateway.reviewScope()) return
        val head = snapshot.optJSONArray("items")?.optJSONObject(0)
        val id = head?.optString("id").orEmpty()
        if (headId != id) { AutomaticQueuedTaskAdmission.complete(headId, false); headId = id }
        if (head == null) return
        if (AutomaticUnlockSession.active) return
        val pendingKey = "queue_start_pending_${connection.scope}"
        if (gateway.prefs.getString(pendingKey, "") == id) {
            if (head.optString("status") == "running") {
                // A lost start ACK cannot authorize execution on a later poll.
                connection.request("POST", "/runs/$id/pause", JSONObject())
                return
            }
            check(gateway.prefs.edit().remove(pendingKey).commit()) { "启动状态未保存" }
        }
        fun valid() = !closed && alive() && headId == id && TaskControl.isCurrent(ticket) &&
            !gateway.prefs.getBoolean("queue_dispatch_paused", false) && connection.scope == gateway.reviewScope() &&
            gateway.prefs.getString("active_run", "").isNullOrBlank() &&
            (head.optString("status") != "queued" || !deviceBusy(id))
        if (head.optString("status") != "queued") {
            if (head.optString("status") == "running" && AutomaticUnlockSession.locked(app)) {
                waiting("等待解锁手机后继续任务"); return
            }
            accept(head, ::valid)
            return
        }
        if (deviceBusy(id)) {
            waiting("排队中，等待设备操作结束"); return
        }
        if (head.optString("source") in setOf("schedule", "trigger")) {
            AutomaticQueuedTaskAdmission.prepare(app, head, ::valid,
                onReady = { start(connection, head, ticket, ::valid, true) },
                onSkip = {
                    TaskControl.serialize {
                        runCatching { connection.request("POST", "/runs/$id/cancel", JSONObject().put("expected_status", "queued")) }
                    }
                },
                onBlocked = { waiting(if (it == "device_locked") "排队中，等待解锁手机" else "排队中，等待设备可用") })
        } else if (AutomaticUnlockSession.locked(app) || DoppelAccessibilityService.instance == null) {
            waiting("排队中，等待手机解锁及无障碍服务")
        } else start(connection, head, ticket, ::valid, false)
    }

    private fun deviceBusy(id: String) = VoiceActivity.isVisible || TaskPanelActivity.isVisible ||
        PaymentConsent.settingsVisible || DirectMode.settingsVisible || AccessibilityControlPicker.active ||
        TaskSubmissionGate.creating.get() && !AutomaticUnlockSession.matches("queued:$id")

    private fun start(connection: Gateway.ReviewConnection, head: JSONObject, ticket: Long, valid: () -> Boolean, automatic: Boolean) {
        if (!starting.compareAndSet(false, true)) return
        fun ready() = valid() && !AutomaticUnlockSession.locked(app) && DoppelAccessibilityService.instance != null
        TaskControl.serialize {
            val id = head.getString("id")
            var started = false
            var promotionRequested = false
            try {
                check(ready()) { "任务启动条件已变化" }
                check(DoppelAccessibilityService.instance?.awaitExecutionStopped() == true) { "上一项操作尚未停止" }
                check(!AutomaticUnlockSession.active || automatic && AutomaticUnlockSession.matches("queued:$id")) { "正在完成上一项任务的锁屏保护" }
                check(ready()) { "任务启动条件已变化" }
                check(gateway.prefs.edit().putString("queue_start_pending_${connection.scope}", id).commit()) { "任务启动记录未保存" }
                promotionRequested = true
                val run = connection.request("POST", "/runs/$id/start", JSONObject())
                if (!ready()) {
                    if (run.optString("status") == "running") connection.request("POST", "/runs/$id/pause", JSONObject())
                    return@serialize
                }
                check(run.optString("id") == id && run.optString("status") == "running") { "任务尚未取得执行资格" }
                check(TaskControl.isCurrent(ticket))
                check(accept(run, ::ready)) { "任务启动条件已变化" }
                started = true
                if (!gateway.prefs.edit().remove("queue_start_pending_${connection.scope}").commit()) {
                    gateway.prefs.edit().putString("queue_start_pending_${connection.scope}", id).commit()
                    error("任务启动确认未保存")
                }
            } catch (_: Exception) {
                if (promotionRequested) runCatching {
                    if (connection.request("GET", "/runs/$id").optString("status") == "running")
                        connection.request("POST", "/runs/$id/pause", JSONObject())
                }
                waiting("排队中，任务启动尚未确认，将核对状态")
            } finally {
                if (automatic) AutomaticQueuedTaskAdmission.complete(id, started)
                starting.set(false)
            }
        }
    }

    fun close() { closed = true; AutomaticQueuedTaskAdmission.complete(headId, false) }
}
