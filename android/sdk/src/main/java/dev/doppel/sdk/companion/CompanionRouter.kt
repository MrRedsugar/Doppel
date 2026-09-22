package dev.doppel.sdk.companion

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.InetAddress

/** Bounded JSON API only. TLS, HTTP framing and phone-local decisions are separate. */
internal class CompanionRouter(private val host: CompanionHost, private val pairs: CompanionPairings,
    private val lanAccess: ((InetAddress) -> (() -> Boolean))? = null,
    private val presence: ((CompanionAuthContext, InetAddress, Boolean) -> Unit)? = null) {
    fun handle(method: String, target: String, authorization: String?, body: String = "", peer: InetAddress? = null): CompanionResponse = try {
        if (target.length > 8192 || body.toByteArray(Charsets.UTF_8).size > 65_536) fail(413, "invalid_request")
        val uri = URI(target)
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) fail(400, "invalid_request")
        val path = uri.rawPath
        if (!path.startsWith("/companion/v1/") || '%' in path || '\\' in path) fail(404, "route_not_found")
        val query = linkedMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach {
            val fields = it.split('=', limit = 2)
            val key = URLDecoder.decode(fields[0], "UTF-8")
            val value = URLDecoder.decode(fields.getOrElse(1) { "" }, "UTF-8")
            if (key !in setOf("scope_id", "cursor", "limit") || query.put(key, value) != null) fail(400, "invalid_request")
        }
        if (method !in setOf("GET", "POST")) fail(405, "method_not_allowed")
        if (method == "GET" && body.isNotEmpty()) fail(400, "invalid_request")
        val data = if (method == "POST") {
            val parser = JSONTokener(body)
            val parsed = parser.nextValue()
            if (parsed !is JSONObject || parser.nextClean() != '\u0000') fail(400, "invalid_request")
            parsed
        } else JSONObject()
        val segments = path.removePrefix("/companion/v1/").split('/')
        if (segments.any { it.isBlank() || it.length > 256 }) fail(400, "invalid_request")
        fun token(scheme: String): String {
            val prefix = "$scheme "
            val value = authorization?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                ?: fail(401, "authentication_required")
            if (value.length != 43 || !value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) fail(401, "authentication_required")
            return value
        }
        fun scope() = query["scope_id"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: fail(400, "invalid_request")
        fun limit(): Int = query["limit"]?.toIntOrNull()?.takeIf { it in 1..100 }
            ?: if ("limit" in query) fail(400, "invalid_request") else 50

        when {
            segments == listOf("pairings") && method == "POST" -> {
                fields(data, setOf("pairing_id", "secret", "client_name"))
                CompanionResponse(201, pairs.request(string(data, "pairing_id", 128), string(data, "secret", 256), string(data, "client_name", 80)))
            }
            segments.size == 2 && segments[0] == "pairings" && method == "GET" -> pairs.poll(segments[1], token("Pairing"))
            segments.size == 3 && segments[0] == "pairings" && segments[2] == "activate" && method == "POST" -> {
                fields(data, emptySet())
                CompanionResponse(200, pairs.activate(segments[1], token("Bearer")))
            }
            else -> {
                val auth = pairs.authenticate(token("Bearer"))
                when {
                    segments == listOf("task-events") && method == "GET" -> {
                        if (query.isNotEmpty()) fail(400, "invalid_request")
                        auth.withAuthorization(setOf("state")) { Unit }
                        host.readTaskEvents(auth)
                    }
                    segments.first() == "handoff" && method == "POST" -> {
                        if (query.isNotEmpty() || segments.size > 2) fail(400, "invalid_request")
                        val operation = segments.getOrNull(1) ?: "start"
                        if (operation !in setOf("start", "frame", "action", "close")) fail(404, "route_not_found")
                        val allowed = when (operation) {
                            "start" -> setOf("run_id")
                            "action" -> setOf("session_id", "frame_id", "action_id", "kind", "x", "y", "end_x", "end_y", "duration_ms", "text")
                            else -> setOf("session_id")
                        }
                        fields(data, allowed)
                        string(data, if (operation == "start") "run_id" else "session_id", 256)
                        auth.withAuthorization(setOf("state", "screen_control")) { Unit }
                        val currentLan = (lanAccess ?: fail(503, "lan_required"))(peer ?: fail(403, "lan_required"))
                        if (!currentLan()) fail(403, "lan_required")
                        host.handoff(auth, operation, data, currentLan)
                    }
                    segments == listOf("presence") && method == "POST" -> {
                        fields(data, setOf("present"))
                        val present = if (data.has("present")) data.opt("present") as? Boolean ?: fail(422, "invalid_request") else true
                        val source = peer ?: fail(403, "lan_presence_unavailable")
                        (presence ?: fail(503, "lan_presence_unavailable"))(auth, source, present)
                        CompanionResponse(200, JSONObject().put("present", present)
                            .put("lease_ms", if (present) CompanionLanPresence.LEASE_MS else 0))
                    }
                    segments == listOf("snapshot") && method == "GET" -> host.readSnapshot(auth, query["scope_id"])
                    segments == listOf("operations") && method == "POST" -> {
                        val kind = string(data, "kind", 16)
                        string(data, "request_id", 128)
                        string(data, "scope_id", 256)
                        if (kind == "create") {
                            fields(data, setOf("kind", "request_id", "scope_id", "goal", "mode"))
                            if (string(data, "goal", 8000).isBlank() || string(data, "mode", 16) !in setOf("ask", "assist", "full")) fail(422, "invalid_request")
                            auth.withAuthorization(setOf("state", "submit")) { Unit }
                        } else if (kind == "control") {
                            fields(data, setOf("kind", "request_id", "scope_id", "run_id", "expected_sync_epoch", "expected_revision", "action", "pending_request_id", "text", "approve"))
                            string(data, "run_id", 256); string(data, "expected_sync_epoch", 256)
                            val revision = data.opt("expected_revision")
                            if (revision !is Long && revision !is Int || (revision as Number).toLong() < 0) fail(422, "invalid_request")
                            val action = string(data, "action", 16)
                            if (action !in setOf("pause", "resume", "cancel", "answer")) fail(422, "invalid_request")
                            if (action == "answer") {
                                string(data, "pending_request_id", 256)
                                if (data.has("text") == data.has("approve")) fail(422, "invalid_request")
                                if (data.has("text")) string(data, "text", 8000)
                                if (data.has("approve") && data.opt("approve") !is Boolean) fail(422, "invalid_request")
                            } else if (listOf("text", "approve", "pending_request_id").any(data::has)) fail(422, "invalid_request")
                            auth.withAuthorization(setOf("state", "control")) { Unit }
                        } else fail(422, "invalid_request")
                        host.submitOperation(auth, data) // Host rechecks at its atomic commit, not under a network lock.
                    }
                    segments.size == 2 && segments[0] == "operations" && method == "GET" -> host.readOperation(auth, scope(), segments[1])
                    segments.size == 1 && segments[0] in setOf("runs", "conversations") && method == "GET" -> {
                        auth.withAuthorization(setOf("history")) { Unit }
                        host.readHistory(auth, scope(), segments[0], query["cursor"], limit())
                    }
                    segments.size == 2 && segments[0] == "runs" && method == "GET" -> host.readRun(auth, scope(), segments[1])
                    segments.size == 2 && segments[0] == "conversations" && method == "GET" -> {
                        auth.withAuthorization(setOf("history")) { Unit }
                        host.readConversation(auth, scope(), segments[1], query["cursor"], limit())
                    }
                    else -> fail(404, "route_not_found")
                }
            }
        }
    } catch (error: CompanionProtocolException) {
        error(error.statusCode, error.code)
    } catch (error: CompanionAuthorizationException) {
        error(error.statusCode, error.code)
    } catch (_: JSONException) {
        error(400, "invalid_request")
    } catch (_: IllegalArgumentException) {
        error(400, "invalid_request")
    } catch (_: Exception) {
        error(500, "internal_error")
    }

    private fun fields(value: JSONObject, allowed: Set<String>) {
        if (value.keys().asSequence().any { it !in allowed }) fail(422, "invalid_request")
    }
    private fun string(value: JSONObject, key: String, max: Int): String =
        (value.opt(key) as? String)?.takeIf { it.isNotEmpty() && it.length <= max } ?: fail(422, "invalid_request")
    private fun fail(status: Int, code: String): Nothing = throw CompanionProtocolException(status, code)
    private fun error(status: Int, code: String) = CompanionResponse(status,
        JSONObject().put("error", JSONObject().put("code", code).put("message", "请求未完成，请根据状态检查连接或授权").put("retryable", false)))
}
