package dev.doppel.sdk.cloud

import android.content.Context
import android.os.SystemClock
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.ServerTaskHost
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The visible service owns one instance. Host owns task state and the durable receipt outbox. */
internal class CloudPhoneConnection(context: Context, private val onState: (String) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val store = CloudSessionStore(app)
    private val host = ServerTaskHost(app)
    private val http = CloudAccountClient(app).http
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "cloud-phone").apply { isDaemon = true } }
    private var generation = UUID.randomUUID().mostSignificantBits
    private var sessionRef: ServerTaskHost.SessionRef? = null
    @Volatile private var stopping: Stop? = null
    private val revokedSessions = mutableSetOf<String>()
    private var storageFailed = false
    private var ticker: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile private var current: Channel? = null
    internal val wantsConnection: Boolean get() = running
    internal val isStopping: Boolean get() = stopping != null

    private class Stop(val ref: ServerTaskHost.SessionRef, val channel: Channel?, var reason: String, var full: Boolean) {
        var invokedFull = false
        var result: Boolean? = null
        var invalidationId: String? = null
        var timeout: ScheduledFuture<*>? = null
        var waitingForInvalidation = false
    }

    private class Channel(val session: CloudSession, val ref: ServerTaskHost.SessionRef) {
        val lease = CloudExecutionLease(SystemClock::elapsedRealtime)
        var socket: WebSocket? = null
        var open = false
        @Volatile var revoked = false
        var nextHeartbeat = 0L
        var lastSnapshot = ""
        val sentReceipts = mutableSetOf<Pair<String, Long>>()
    }

    fun start() = enqueue {
        FirstUseConsent.requireAccepted(app)
        running = true
        if (ticker == null) ticker = io.scheduleWithFixedDelay({ guarded { pulse() } }, 1, 1, TimeUnit.SECONDS)
        val saved = try { store.load() } catch (_: Exception) {
            storageFailed = true
            current?.let { disconnect(it, "storage_unavailable", false, false) }
            publish("storage_unavailable"); return@enqueue
        }
        val old = current
        if (stopping != null) return@enqueue // Completion reconciles this latest start intent.
        if (old != null && saved?.sessionId == old.session.sessionId) {
            if (old.lease.expired()) disconnect(old, "lease_expired", remoteOnly = true, reconnect = true)
        } else if (old != null) disconnect(old, "replaced", remoteOnly = false, reconnect = true)
        else {
            val prior = sessionRef
            if (prior != null && saved?.sessionId != prior.sessionId) {
                beginStop(prior, null, "replaced", full = true)
            } else connectSaved()
        }
    }

    fun clear(expectedSessionId: String, reason: String = "logout") {
        current?.takeIf { it.session.sessionId == expectedSessionId }?.lease?.close()
        enqueue {
            val saved = try { store.load() } catch (_: Exception) { storageFailed = true; null }
            val old = current?.takeIf { it.session.sessionId == expectedSessionId }
            val ref = old?.ref ?: stopping?.ref?.takeIf { it.sessionId == expectedSessionId }
                ?: sessionRef?.takeIf { it.sessionId == expectedSessionId }
                ?: saved?.takeIf { it.sessionId == expectedSessionId }?.let {
                    ServerTaskHost.SessionRef(it.accountId, it.sessionId, ++generation).also { value -> sessionRef = value }
                }
            if (saved?.sessionId != expectedSessionId && ref == null) return@enqueue
            if (saved == null || saved.sessionId == expectedSessionId) {
                running = false; ticker?.cancel(false); ticker = null
            }
            eraseSession(expectedSessionId)
            if (ref != null) {
                old?.revoked = true
                beginStop(ref, old, reason, full = true,
                    waitForInvalidation = old?.open == true && reason in setOf("logout", "password_changed"))
            } else publish("signed_out")
        }
    }

    private fun eraseSession(id: String) {
        revokedSessions.add(id)
        try { store.clearIfSession(id); storageFailed = false }
        catch (_: Exception) { storageFailed = true; publish("storage_unavailable") }
    }

    private fun publish(value: String) {
        onState(if (storageFailed && value != "stop_unconfirmed") "storage_unavailable" else value)
    }

    private fun beginStop(ref: ServerTaskHost.SessionRef, channel: Channel?, reason: String, full: Boolean,
        invalidationId: String? = null, waitForInvalidation: Boolean = false) {
        val pending = stopping
        if (pending != null) {
            check(pending.ref.sessionId == ref.sessionId)
            if (full) { pending.full = true; pending.reason = reason }
            if (invalidationId != null) {
                require(pending.invalidationId == null || pending.invalidationId == invalidationId)
                pending.invalidationId = invalidationId; pending.waitingForInvalidation = false
            }
            if (pending.result != null) {
                if (pending.full && !pending.invokedFull) invokeStop(pending) else finishStop(pending)
            }
            return
        }
        val stop = Stop(ref, channel, reason, full).also {
            it.invalidationId = invalidationId
            it.waitingForInvalidation = waitForInvalidation && invalidationId == null
        }
        stopping = stop
        stop.timeout = io.schedule({ guarded {
            if (stopping !== stop) return@guarded
            stop.waitingForInvalidation = false
            channel?.open = false; channel?.socket?.cancel()
            if (stop.result == null) publish("stop_unconfirmed") else finishStop(stop)
        } }, 30, TimeUnit.SECONDS)
        invokeStop(stop)
    }

    private fun invokeStop(stop: Stop) {
        stop.result = null; stop.invokedFull = stop.full
        host.invalidate(stop.ref, stop.reason, !stop.invokedFull) { result -> enqueue {
            if (stopping !== stop) return@enqueue
            stop.result = result.optBoolean("stopped")
            if (stop.full && !stop.invokedFull) invokeStop(stop) else finishStop(stop)
        } }
    }

    private fun finishStop(stop: Stop) {
        if (stopping !== stop || stop.result == null) return
        if (stop.waitingForInvalidation) { publish(if (stop.result == true) "signed_out" else "stop_unconfirmed"); return }
        val channel = stop.channel
        val invalidation = stop.invalidationId
        if (channel != null && invalidation != null && channel.open) {
            send(channel, JSONObject().put("type", "stop_ack").put("session_id", stop.ref.sessionId)
                .put("invalidation_id", invalidation).put("stopped", stop.result)
                .put("error_code", if (stop.result == true) JSONObject.NULL else "stop_unconfirmed"))
        }
        stop.timeout?.cancel(false); stop.timeout = null
        channel?.socket?.close(1000, "session ended")
        if (current === channel) current = null
        if (stop.result != true) { publish("stop_unconfirmed"); return }
        stopping = null
        if (stop.full && sessionRef == stop.ref) sessionRef = null
        if (running) {
            if (stop.reason in setOf("connection_lost", "lease_expired")) io.schedule({ guarded { connectSaved() } }, 2, TimeUnit.SECONDS)
            else connectSaved()
        } else {
            val saved = try { store.load() } catch (_: Exception) { storageFailed = true; null }
            publish(if (stop.full && (saved == null || saved.sessionId in revokedSessions)) "signed_out" else "connection_closed")
        }
    }

    override fun close() {
        current?.lease?.close()
        enqueue {
            running = false
            ticker?.cancel(false); ticker = null
            if (stopping != null) return@enqueue // Do not tear down the invalidation acknowledgement socket.
            val old = current
            if (old != null) disconnect(old, "connection_closed", remoteOnly = true, reconnect = false)
            else publish("connection_closed")
        }
    }

    private fun connectSaved() {
        if (!running || stopping != null || current != null) return
        val session = try { store.load() } catch (_: Exception) { storageFailed = true; publish("storage_unavailable"); return }
            ?: run { running = false; ticker?.cancel(false); ticker = null; publish("signed_out"); return }
        if (session.sessionId in revokedSessions) {
            running = false; ticker?.cancel(false); ticker = null; publish("signed_out"); return
        }
        storageFailed = false // A successfully loaded replacement has superseded the failed old credential cleanup.
        val ref = sessionRef?.takeIf { it.sessionId == session.sessionId && it.accountId == session.accountId }
            ?: ServerTaskHost.SessionRef(session.accountId, session.sessionId, ++generation).also { sessionRef = it }
        val channel = Channel(session, ref)
        current = channel
        publish("connecting")
        channel.socket = http.newWebSocket(Request.Builder().url("${session.baseUrl}/v1/phone/events")
            .header("Authorization", "Bearer ${session.token}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = enqueue {
                if (current !== channel) { webSocket.cancel(); return@enqueue }
                channel.open = true
                pulse()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = enqueue {
                if (current === channel) receive(channel, text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = enqueue {
                if (current === channel) disconnect(channel, "invalid_frame", true, false)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = enqueue { closed(channel, code) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = enqueue { closed(channel, code) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = enqueue {
                if (current === channel && channel.revoked) closed(channel, 4401)
                else if (response?.code == 401 || response?.code == 403) revoke(channel, null, "session_revoked")
                else if (current === channel) disconnect(channel, "connection_lost", true, true)
            }
        })
    }

    private fun valid(channel: Channel) = running && current === channel && !channel.revoked && channel.lease.valid()

    private fun pulse() {
        val channel = current ?: return
        if (channel.revoked) return
        if (!FirstUseConsent.isAccepted(app)) { clear(channel.session.sessionId, "consent_withdrawn"); return }
        if (channel.lease.expired()) { disconnect(channel, "lease_expired", true, true); return }
        if (!running || !channel.open) return
        val snapshot = host.snapshot(channel.ref)
        val task = snapshot.optJSONObject("current_task") ?: JSONObject.NULL
        val events = snapshot.optJSONArray("recent_task_events") ?: JSONArray()
        val payload = JSONObject().put("type", "task_snapshot").put("current_task", task).put("recent_task_events", events)
        val serialized = payload.toString()
        if (serialized != channel.lastSnapshot) {
            send(channel, payload)
            channel.lastSnapshot = serialized
            flushReceipts(channel) // Publish the matching outcome without waiting for the next heartbeat.
        }
        if (SystemClock.elapsedRealtime() >= channel.nextHeartbeat) {
            val id = UUID.randomUUID().toString()
            if (!channel.lease.heartbeat(id)) return
            send(channel, JSONObject().put("type", "heartbeat").put("heartbeat_id", id)
                .put("available_operations", snapshot.optJSONArray("available_operations") ?: JSONArray())
                .put("reason_code", snapshot.opt("reason_code") ?: JSONObject.NULL).put("current_task", task).put("recent_task_events", events))
            channel.nextHeartbeat = SystemClock.elapsedRealtime() + 10_000
            channel.sentReceipts.clear() // Retry only immutable persisted receipts; never commands.
            flushReceipts(channel)
        }
    }

    private fun receive(channel: Channel, text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= 65536)
        val reader = JSONTokener(text)
        val value = reader.nextValue() as? JSONObject ?: error("invalid frame")
        require(reader.nextClean() == '\u0000')
        val type = value.getString("type")
        if (channel.revoked && type != "invalidate") return
        when (type) {
            "heartbeat_ack" -> if (channel.lease.acknowledge(id(value, "heartbeat_id"), number(value, "lease_ms"), number(value, "server_time_ms"))) {
                publish("online")
                flushReceipts(channel)
            }
            "command" -> {
                require(id(value, "session_id") == channel.session.sessionId)
                id(value, "operation_id")
                val expires = number(value, "expires_at_ms")
                host.accept(channel.ref, value, { valid(channel) && channel.lease.mayAccept(expires) }, { valid(channel) }) {
                    enqueue { if (current === channel && !channel.revoked) { flushReceipts(channel); pulse() } }
                }
            }
            "receipt_ack" -> {
                val operation = id(value, "operation_id")
                val sequence = number(value, "receipt_seq")
                require(sequence > 0)
                host.acknowledgeReceipt(channel.ref, operation, sequence)
                channel.sentReceipts.remove(operation to sequence)
                flushReceipts(channel)
            }
            "invalidate" -> {
                require(id(value, "session_id") == channel.session.sessionId)
                val reason = value.getString("reason")
                require(reason in setOf("replaced", "logout", "password_changed", "password_reset", "account_disabled", "admin_revoked"))
                revoke(channel, id(value, "invalidation_id"), reason)
            }
            else -> error("unexpected frame")
        }
    }

    private fun flushReceipts(channel: Channel) {
        if (!valid(channel) || !channel.open) return
        for (receipt in host.pendingReceipts(channel.ref).take(32)) {
            require(id(receipt, "session_id") == channel.session.sessionId && receipt.getString("type") == "receipt")
            val key = id(receipt, "operation_id") to number(receipt, "receipt_seq")
            if (key !in channel.sentReceipts && send(channel, receipt)) channel.sentReceipts.add(key)
        }
    }

    private fun revoke(channel: Channel, invalidationId: String?, reason: String) {
        if (current !== channel) return
        channel.revoked = true
        channel.lease.close()
        publish("session_revoked")
        eraseSession(channel.session.sessionId)
        beginStop(channel.ref, channel, reason, full = true, invalidationId = invalidationId)
    }

    private fun closed(channel: Channel, code: Int) {
        if (current !== channel) return
        channel.open = false
        channel.socket?.close(code, null)
        if (channel.revoked) {
            stopping?.takeIf { it.channel === channel }?.let { it.waitingForInvalidation = false; finishStop(it) }
        } else if (code == 4401) revoke(channel, null, "session_revoked")
        else disconnect(channel, if (code == 4409) "connection_replaced" else "connection_lost", true, code != 4409)
    }

    private fun disconnect(channel: Channel, reason: String, remoteOnly: Boolean, reconnect: Boolean) {
        if (current !== channel) return
        channel.lease.close()
        current = null
        channel.socket?.cancel()
        if (!reconnect) running = false
        publish(reason)
        beginStop(channel.ref, null, reason, full = !remoteOnly)
    }

    private fun send(channel: Channel, value: JSONObject): Boolean {
        val text = value.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= 65536)
        val socket = channel.socket ?: return false
        if (socket.queueSize() > 256 * 1024 || !socket.send(text)) {
            if (!channel.revoked) disconnect(channel, "connection_lost", true, true)
            return false
        }
        return true
    }

    private fun id(value: JSONObject, key: String): String = value.getString(key).also {
        require(it.length in 1..128 && it.all { c -> c.code in 33..126 })
    }
    private fun number(value: JSONObject, key: String): Long {
        val raw = value.get(key)
        require(raw is Int || raw is Long)
        return (raw as Number).toLong().also { require(it >= 0) }
    }
    private fun enqueue(work: () -> Unit) { io.execute { guarded(work) } }
    private fun guarded(work: () -> Unit) {
        try { work() } catch (_: Exception) {
            publish("connection_error")
            current?.let { channel -> runCatching { disconnect(channel, "connection_error", true, false) } }
        }
    }
}
