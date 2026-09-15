package dev.doppel.sdk

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** App-owned shell backend; callers supply the existing host authorization and live source checks. */
class ShellBridgeClient private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ShellBridgeClient? = null
        fun get(context: Context): ShellBridgeClient = instance ?: synchronized(this) {
            instance ?: ShellBridgeClient(context.applicationContext).also { instance = it }
        }
        fun source(observation: JSONObject, rotation: Int, captureId: String? = null): JSONObject = JSONObject()
            .put("screen_id", observation.getString("screen_id")).put("package_name", observation.getString("package_name"))
            .put("width", observation.getInt("width")).put("height", observation.getInt("height")).put("rotation", rotation)
            .put("captured_at", SystemClock.elapsedRealtime()).apply { captureId?.let { put("capture_id", it) } }
    }
    private val prefs = context.getSharedPreferences("shell_bridge", Context.MODE_PRIVATE)
    private val uid = context.applicationInfo.uid
    private var socket: LocalSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var token: String? = null
    private var last = JSONObject().put("connected", false)
    private var connectionError: JSONObject? = null
    private val responses = Executors.newSingleThreadExecutor { task -> Thread(task, "shell-bridge-response").apply { isDaemon=true } }
    val socketName: String get() = "doppel.shell.$uid"
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)

    @Synchronized fun activate(): JSONObject {
        connectionError = null
        var stage = "connect"
        try {
            connect()
            stage = "ping"
            val result = exchange("ping", UUID.randomUUID().toString())
            check(result.optString("status") == "ok" && result.optInt("uid") == 2000) { "invalid_ping" }
            stage = "save_activation"
            check(prefs.edit().putBoolean("enabled", true).commit()) { "activation_not_saved" }
            last = JSONObject(result.toString()).put("connected", true)
            return status(false)
        } catch (failure: Exception) {
            if (connectionError == null) rememberFailure(stage, failure)
            disconnect()
            throw failure
        }
    }
    @Synchronized fun deactivate(): JSONObject {
        prefs.edit().putBoolean("enabled", false).commit()
        runCatching { if (socket != null) exchange("shutdown", UUID.randomUUID().toString()) }
        disconnect()
        return status(false)
    }
    @Synchronized fun status(refresh: Boolean = true): JSONObject {
        if (refresh && enabled()) runCatching {
            connectionError = null
            ensureConnected()
            last = exchange("ping", UUID.randomUUID().toString()).put("connected", true)
        }.onFailure { if (connectionError == null) rememberFailure("ping", it); disconnect() }
        return JSONObject(last.toString()).put("enabled", enabled()).put("connected", socket != null)
            .put("socket", socketName).put("app_uid", uid)
            .put("activation", "authorized_adb_app_process").put("wireless_pairing_in_app", false)
            .put("connection_error", connectionError ?: JSONObject.NULL)
            .put("ime", ShellBridgeImeService.capability())
    }
    /** Privacy/overlay checks must be the same as the normal screenshot path, immediately before and after. */
    @Synchronized fun captureAuthorized(isCurrent: () -> Boolean, screenshotAllowed: () -> Boolean): JSONObject {
        if (!enabled() || !isCurrent() || !screenshotAllowed()) return rejected("capture_not_authorized")
        val startedAt = SystemClock.elapsedRealtime()
        var stage = "client_connect"
        return try {
            ensureConnected()
            if (!isCurrent() || !screenshotAllowed()) return rejected("capture_not_authorized")
            val id = UUID.randomUUID().toString()
            stage = "request_write"
            send(request("screenshot", id))
            stage = "response_read"
            val value = sanitizedResponse(receive())
            stage = "response_id"
            check(value.optString("id") == id)
            if (!isCurrent() || !screenshotAllowed()) rejected("capture_revoked") else value
        } catch (failure: Exception) {
            disconnect()
            rejected("backend_disconnected").put("shell_diagnostic", clientDiagnostic("screenshot", stage, failureReason(stage), startedAt, failure))
        }
    }
    /** Never call a fallback backend after unconfirmed: this request may already have injected its event. */
    @Synchronized fun executeAuthorized(commandId: String, runId: String, source: JSONObject, operation: String,
        args: JSONObject, currentSource: () -> JSONObject?, isCurrent: () -> Boolean): JSONObject {
        if (!enabled() || !isCurrent()) return rejected("host_authorization_revoked")
        val startedAt = SystemClock.elapsedRealtime()
        var stage = "client_connect"
        var dispatchAttempted = false
        return try {
            ensureConnected()
            val current = currentSource() ?: return rejected("source_unavailable")
            val matches = listOf("screen_id", "package_name", "width", "height", "rotation").all {
                source.has(it) && current.has(it) && source.opt(it) == current.opt(it)
            }
            if (!matches || !isCurrent()) return rejected("source_changed", "stale")
            val request = request(operation, commandId).put("run_id", runId).put("source", JSONObject(source.toString()))
                .put("args", JSONObject(args.toString()))
            ShellBridgeProtocol.validate(request, SystemClock.elapsedRealtime())
            if (!isCurrent()) return rejected("host_authorization_revoked")
            stage = "request_write"
            dispatchAttempted = true // A partial write is uncertain too; it must never permit fallback.
            send(request)
            stage = "response_read"
            val pending=responses.submit<JSONObject> { receive() }
            var value: JSONObject? = null
            try {
                while (true) {
                    if (!isCurrent()) {
                        runCatching { send(JSONObject().put("op", "cancel").put("id", commandId).put("token", token)) }
                        pending.cancel(true); disconnect()
                        return JSONObject().put("status", "cancelled").put("action_state", "unconfirmed")
                            .put("reason_code", "host_cancelled_after_dispatch").put("backend", "adb_shell_local_socket")
                            .put("shell_diagnostic", clientDiagnostic(operation, "host_cancel", "host_cancelled_after_dispatch", startedAt))
                    }
                    try { value=pending.get(50,TimeUnit.MILLISECONDS); break }
                    catch (_: TimeoutException) { /* Check the host's live cancellation token while the helper works. */ }
                }
            } finally { if (!pending.isDone) pending.cancel(true) }
            stage = "response_id"
            check(requireNotNull(value).optString("id") == commandId) { "mismatched_response" }
            sanitizedResponse(value).put("proves_business_success", false)
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException && !dispatchAttempted) return rejected("invalid_typed_request")
            disconnect()
            JSONObject().put("status", "error").put("action_state", "unconfirmed")
                .put("reason_code", "backend_result_unconfirmed").put("backend", "adb_shell_local_socket")
                .put("shell_diagnostic", clientDiagnostic(operation, stage, failureReason(stage), startedAt, failure))
        }
    }
    private fun sanitizedResponse(value: JSONObject): JSONObject = value.apply {
        val safe = ShellBridgeDiagnostic.sanitize(optJSONObject("shell_diagnostic"))
        remove("shell_diagnostic")
        if (safe != null) put("shell_diagnostic", safe)
    }
    private fun failureReason(stage: String) = when (stage) {
        "client_connect" -> "backend_connect_failed"
        "request_write" -> "backend_write_failed"
        "response_id" -> "backend_response_mismatch"
        else -> "backend_response_failed"
    }
    private fun clientDiagnostic(operation: String, stage: String, reason: String, startedAt: Long, failure: Throwable? = null): JSONObject? {
        val cause = if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure
        val facts = JSONObject().put("source", "client").put("operation", operation).put("stage", stage).put("reason_code", reason)
            .put("elapsed_ms", (SystemClock.elapsedRealtime() - startedAt).coerceIn(0L, 60000L))
            .put("cancel_requested", stage == "host_cancel")
        cause?.let { facts.put("exception_class", it.javaClass.name) }
        return ShellBridgeDiagnostic.sanitize(facts)
    }
    private fun ensureConnected() { check(enabled()); if (socket == null) connect() }
    private fun connect() {
        disconnect()
        val connected = LocalSocket()
        var stage = "connect"
        try {
            connected.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            // LocalSocket creates its descriptor during connect; API28 rejects options before that.
            stage = "read_timeout"
            connected.soTimeout = 7000
            stage = "peer_identity"
            check(connected.peerCredentials.uid == 2000) { "unexpected_peer_uid" }
            socket = connected; input = DataInputStream(connected.inputStream); output = DataOutputStream(connected.outputStream)
            token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            stage = "handshake"
            send(JSONObject().put("version", 1).put("op", "hello").put("token", token))
            val handshake = receive()
            check(handshake.optString("session") == "authenticated" && handshake.optInt("uid") == 2000) { "invalid_handshake" }
            last = handshake.put("connected", true)
            connectionError = null
        } catch (failure: Exception) { rememberFailure(stage, failure); runCatching { connected.close() }; disconnect(); throw failure }
    }
    private fun request(op: String, id: String) = JSONObject().put("version", 1).put("id", id).put("op", op)
        .put("token", token).put("expires_at", SystemClock.elapsedRealtime() + 4500).put("args", JSONObject())
    private fun exchange(op: String, id: String): JSONObject { send(request(op, id)); return receive().also { check(it.optString("id") == id) } }
    private fun send(value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= ShellBridgeProtocol.MAX_REQUEST)
        requireNotNull(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private fun receive(): JSONObject {
        val stream = requireNotNull(input); val length = stream.readInt()
        require(length in 2..ShellBridgeProtocol.MAX_RESPONSE)
        return JSONObject(ByteArray(length).also(stream::readFully).toString(Charsets.UTF_8))
    }
    private fun disconnect() { runCatching { socket?.close() }; socket=null; input=null; output=null; token=null; last=JSONObject().put("connected", false) }
    private fun rememberFailure(stage: String, failure: Throwable) {
        // Only fixed reason codes and exception class are exposed; never raw socket/request/token text.
        val message = failure.message.orEmpty().lowercase()
        val reason = when {
            "socket not created" in message -> "socket_not_created"
            "econnrefused" in message || "connection refused" in message -> "connection_refused"
            "enoent" in message || "no such file" in message -> "backend_not_listening"
            "eacces" in message || "permission denied" in message -> "socket_permission_denied"
            "timed out" in message || failure is java.net.SocketTimeoutException -> "backend_timeout"
            message == "unexpected_peer_uid" -> "unexpected_peer_uid"
            message == "invalid_handshake" -> "invalid_handshake"
            message == "invalid_ping" -> "invalid_ping"
            message == "activation_not_saved" -> "activation_not_saved"
            failure is java.io.EOFException -> "backend_closed_connection"
            failure is java.io.IOException -> "backend_io_error"
            else -> "activation_failed"
        }
        connectionError = JSONObject().put("stage", stage).put("reason_code", reason)
            .put("exception", failure.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(80))
    }
    private fun rejected(reason: String, status: String = "blocked") = JSONObject().put("status", status)
        .put("action_state", "not_dispatched").put("reason_code", reason).put("backend", "adb_shell_local_socket")
}
