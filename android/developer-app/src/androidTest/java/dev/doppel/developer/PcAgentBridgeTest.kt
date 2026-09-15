package dev.doppel.developer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AndroidWebResearch
import dev.doppel.sdk.DirectSkills
import dev.doppel.sdk.ModelApi
import dev.doppel.sdk.ModelProviders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in desktop transport for the unmodified production engine. Never installs a listener in the app. */
class PcAgentBridgeTest {
    @Test fun serve() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit pc_bridge=true required", args.getString("pc_bridge") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val port = (args.getString("bridge_port")?.toIntOrNull() ?: 38971).also { require(it in 1024..65535) }
        val seconds = (args.getString("limit_seconds")?.toLongOrNull() ?: 900).also { require(it in 10..3600) }
        val allowModel = args.getString("allow_model_calls") == "true"
        val session = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "pc-agent-bridge/$session").apply { check(mkdirs()) }
        val secretBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        secretBytes.fill(0)
        val sessionFile = File(context.filesDir, "pc-agent-bridge-session.json")
        val active = AtomicBoolean(true)
        val bridge = PcBridgeRuntime(context, folder, allowModel)
        ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1000
            sessionFile.writeText(JSONObject().put("port", port).put("token", token).put("session_id", session)
                .put("allow_model_calls", allowModel).toString())
            ins.sendStatus(0, Bundle().apply { putString("stream", "\nPC bridge ready; private session file: files/pc-agent-bridge-session.json\n") })
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000
            try {
                while (active.get() && SystemClock.elapsedRealtime() < deadline) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 120000
                        try {
                            val input = it.getInputStream().buffered()
                            val first = line(input, 2048).split(' ')
                            require(first.size == 3 && first[2] == "HTTP/1.1")
                            val headers = linkedMapOf<String, String>()
                            while (true) {
                                val h = line(input, 8192)
                                if (h.isEmpty()) break
                                require(headers.size < 40) { "Too many headers" }
                                val pair = h.split(':', limit = 2)
                                require(pair.size == 2 && pair[0].lowercase() !in headers)
                                headers[pair[0].lowercase()] = pair[1].trim()
                            }
                            val authorized = MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray())
                            if (!authorized) {
                                respond(it.getOutputStream(), 401, JSONObject().put("error", "Unauthorized"))
                            } else {
                                require(!headers.containsKey("transfer-encoding")) { "Chunked request unsupported" }
                                val length = headers["content-length"]?.toIntOrNull() ?: 0
                                require(length in 0..10 * 1024 * 1024)
                                val bytes = ByteArray(length)
                                var n = 0
                                while (n < length) { val count = input.read(bytes, n, length - n); require(count > 0); n += count }
                                val body = if (length == 0) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
                                val result = if (first[0] == "POST" && first[1] == "/shutdown") {
                                    active.set(false); JSONObject().put("stopped", true)
                                } else bridge.route(first[0], first[1], body)
                                respond(it.getOutputStream(), 200, result)
                            }
                        } catch (failure: Exception) {
                            runCatching { respond(it.getOutputStream(), 400, JSONObject().put("error", safeError(failure))) }
                        }
                    }
                }
            } finally {
                bridge.close()
                sessionFile.writeText(JSONObject().put("session_id", session).put("stopped", true).toString())
            }
        }
    }

    private fun line(input: InputStream, max: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= max) {
            val b = input.read(); require(b != -1) { "Truncated request" }
            if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(b)
        }
        error("Header too long")
    }

    private fun respond(output: java.io.OutputStream, status: Int, value: JSONObject) {
        val data = value.toString().toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(data); output.flush()
    }
}

/** Reflection only crosses Kotlin's module boundary; all model/state behavior remains in SDK. */
private class PcBridgeRuntime(context: Context, private val folder: File, private val allowModel: Boolean) {
    private val sdk = Class.forName("dev.doppel.sdk.SplitTaskEngine", true, ModelApi::class.java.classLoader)
    private val skills = DirectSkills(context)
    private val model = ModelApi(context)
    private val web = AndroidWebResearch()
    private val guiClass = Class.forName("dev.doppel.sdk.GuiGroundingClient", true, ModelApi::class.java.classLoader)
    private val gui = guiClass.getConstructor(Context::class.java).newInstance(context)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val worker = Executors.newSingleThreadExecutor()
    private val pumping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val providers = ModelProviders(context)
    private val probe = AtomicReference<JSONObject?>()
    private var id: String? = null
    private fun newEngine(saveTo: File, enhanced: Boolean? = null, clock: () -> Long = System::currentTimeMillis): Any = sdk.constructors.single { it.parameterCount == 6 }.newInstance(null,
        { text: String -> saveTo.writeText(text) },
        clock, { skills.list() },
        { goal: String, pkg: String, phase: String -> skills.relevant(goal, pkg, phase) },
        { enhanced ?: providers.routing().enhancementEnabled })
    private val engine = newEngine(File(folder, "engine.json"))

    private fun invokeEngine(target: Any, name: String, vararg args: Any?): Any? = try {
        val method = sdk.methods.single { it.name == name && it.parameterCount == args.size }
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }
    private fun call(name: String, vararg args: Any?): Any? = invokeEngine(engine, name, *args)

    fun route(method: String, path: String, body: JSONObject): JSONObject = when ("$method $path") {
        "GET /health" -> JSONObject().put("ok", true).put("engine", "production SplitTaskEngine")
            .put("allow_model_calls", allowModel).put("desktop_input", false)
        "POST /start" -> {
            require(id == null) { "One task per bridge session" }
            check(!pumping.get()) { "Wait for or cancel the current probe" }
            require(body.optString("goal").length in 1..8000)
            if (allowModel) check(providers.isReady()) { "Enabled model configuration must already be verified" }
            (call("create", JSONObject().put("device_id", "direct-this-phone").put("goal", body.getString("goal"))
                .put("mode", body.optString("mode", "full"))) as JSONObject).also { id = it.getString("id") }
        }
        "GET /next" -> (call("poll") as JSONObject).also { pump() }
        "POST /receipt" -> receipt(body)
        "GET /state" -> state()
        "POST /stop" -> {
            id?.let { call("control", it, "cancel", JSONObject()) }
            cancelProbe(); connection.getAndSet(null)?.disconnect(); web.cancel(); guiClass.getMethod("cancel").invoke(gui)
            state()
        }
        "POST /prepare" -> JSONObject().put("source_kind", "recorded_screenshot").put("desktop_input", false)
            .put("replayed_wait_history", (body.optJSONArray("replay_waits")?.length() ?: 0) > 0)
            .put("replayed_wait_count", body.optJSONArray("replay_waits")?.length() ?: 0)
            .put("virtual_wait_elapsed_ms", replayDuration(body))
            .put("payload", productionProbePayload(body))
        "POST /probe" -> {
            check(allowModel) { "Model calls disabled for this session" }
            check(id == null) { "Probe needs an idle fresh session" }
            val payload = if (body.has("payload")) body.getJSONObject("payload") else productionProbePayload(body)
            require(payload.getJSONArray("messages").length() in 1..12)
            check(pumping.compareAndSet(false, true)) { "Another model request is running" }
            val job = JSONObject().put("job_id", UUID.randomUUID().toString()).put("status", "running")
                .put("source_kind", "recorded_screenshot").put("desktop_input", false)
                .put("replayed_wait_history", body.optBoolean("replayed_wait_history") || (body.optJSONArray("replay_waits")?.length() ?: 0) > 0)
                .put("replayed_wait_count", body.optInt("replayed_wait_count", body.optJSONArray("replay_waits")?.length() ?: 0))
                .put("virtual_wait_elapsed_ms", body.optLong("virtual_wait_elapsed_ms", replayDuration(body)))
            probe.set(job)
            worker.execute {
                val started = SystemClock.elapsedRealtime()
                try {
                    val response = model.complete(payload) { c ->
                        connection.set(c)
                        if (closed.get() || synchronized(job) { job.optString("status") != "running" }) { c.disconnect(); error("Probe cancelled") }
                    }
                    val validation = try {
                        val protocol = Class.forName("dev.doppel.sdk.SplitAgentProtocol", true, ModelApi::class.java.classLoader)
                        val parsed = protocol.getMethod("content", JSONObject::class.java, JSONObject::class.java, String::class.java)
                            .invoke(protocol.getField("INSTANCE").get(null), response, payload.getJSONObject("response_format"), payload.getString("_doppel_role")) as JSONObject
                        JSONObject().put("valid", true).put("parsed", parsed)
                    } catch (e: Exception) {
                        val failure = if (e is InvocationTargetException) e.targetException else e
                        JSONObject().put("valid", false).put("error", safeError(failure))
                    }
                    synchronized(job) {
                        if (job.optString("status") == "running") job.put("status", "completed").put("response", response).put("validation", validation)
                    }
                } catch (e: Exception) {
                    synchronized(job) { if (job.optString("status") == "running") job.put("status", "failed").put("error", safeError(e)) }
                } finally {
                    connection.set(null)
                    synchronized(job) {
                        job.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        File(folder, "probe-${job.getString("job_id")}.json").writeText(job.toString(2))
                    }
                    pumping.set(false)
                }
            }
            synchronized(job) { JSONObject(job.toString()) }
        }
        "GET /probe-result" -> probe.get()?.let { synchronized(it) { JSONObject(it.toString()) } } ?: JSONObject().put("status", "none")
        else -> error("Unsupported bridge route")
    }

    /** Build a single production request from image+goal (A) or image+intent (B), without executing it. */
    private fun productionProbePayload(body: JSONObject): JSONObject {
        val encoded = body.getString("image_base64")
        require(encoded.length <= 7 * 1024 * 1024)
        val bytes = Base64.getDecoder().decode(encoded)
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
        require(size.outWidth in 1..4096 && size.outHeight in 1..4096 && size.outMimeType == "image/png")
        if (body.optString("role", "primary") == "grounding") {
            require((body.optJSONArray("replay_waits")?.length() ?: 0) == 0) { "B has no replay history" }
            val protocol = Class.forName("dev.doppel.sdk.SplitAgentProtocol", true, ModelApi::class.java.classLoader)
            return protocol.getMethod("grounder", String::class.java, JSONObject::class.java)
                .invoke(protocol.getField("INSTANCE").get(null), encoded, body.getJSONObject("intent")) as JSONObject
        }
        require(body.optString("role", "primary") == "primary")
        val goal = body.getString("goal"); require(goal.length in 1..8000)
        val sourcePackage = body.optString("source_package", "")
        require(sourcePackage.length <= 200 && (sourcePackage.isEmpty() || sourcePackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")))) {
            "source_package must be an Android package name or empty when unknown"
        }
        val waits = body.optJSONArray("replay_waits") ?: JSONArray()
        require(waits.length() <= 3) { "At most three prior waits may be replayed" }
        var virtualNow = System.currentTimeMillis()
        var virtualElapsed = 0L
        val temporary = newEngine(File(folder, "probe-engine-${UUID.randomUUID()}.json"), enhanced = true, clock = { virtualNow })
        val run = invokeEngine(temporary, "create", JSONObject().put("device_id", "direct-this-phone").put("goal", goal).put("mode", body.optString("mode", "full"))) as JSONObject
        fun submitImage(image: String) {
            require(image.length <= 7 * 1024 * 1024)
            val imageBytes = Base64.getDecoder().decode(image)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, dimensions)
            require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096 && dimensions.outMimeType == "image/png")
            val command = (invokeEngine(temporary, "poll") as JSONObject).getJSONObject("command")
            check(command.getString("kind") == "screenshot") { "Replay expected a production screenshot request" }
            val captured = SystemClock.elapsedRealtime() + virtualElapsed
            val frame = JSONObject().put("capture_id", UUID.randomUUID().toString()).put("screen_id", UUID.randomUUID().toString())
                .put("package_name", sourcePackage).put("display_width", dimensions.outWidth).put("display_height", dimensions.outHeight)
                .put("image_width", dimensions.outWidth).put("image_height", dimensions.outHeight).put("rotation", 0)
                .put("captured_at_elapsed_ms", captured).put("expires_at_elapsed_ms", captured + 45000)
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(imageBytes).joinToString("") { "%02x".format(it) })
            val accepted = invokeEngine(temporary, "result", JSONObject().put("run_id", run.getString("id")).put("command_id", command.getString("id"))
                .put("status", "ok").put("observation", JSONObject().put("package_name", sourcePackage))
                .put("data", JSONObject().put("image_base64", image).put("mime_type", "image/png").put("visual_frame", frame))) as JSONObject
            check(accepted.optBoolean("accepted"))
        }
        repeat(waits.length()) { index ->
            val wait = waits.getJSONObject(index)
            val duration = wait.getInt("duration_ms").also { require(it in 100..30000) }
            submitImage(wait.getString("image_base64"))
            val previousWork = checkNotNull(invokeEngine(temporary, "takeWork"))
            val waitDecision = JSONObject().put("kind", "wait").put("duration_ms", duration)
                .put("evidence", wait.getString("evidence")).put("wait_condition", wait.getString("wait_condition")).put("reason", wait.getString("reason"))
            val wrapper = JSONObject().put("decision", waitDecision).put("state", JSONObject.NULL)
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", wrapper.toString()))))
            invokeEngine(temporary, "accept", previousWork, response, null)
            val command = (invokeEngine(temporary, "poll") as JSONObject).getJSONObject("command")
            check(command.getString("kind") == "wait") { "Replay wait was rejected by production protocol" }
            virtualNow += duration; virtualElapsed += duration
            val accepted = invokeEngine(temporary, "result", JSONObject().put("run_id", run.getString("id")).put("command_id", command.getString("id"))
                .put("status", "ok").put("data", JSONObject().put("elapsed_ms", duration))) as JSONObject
            check(accepted.optBoolean("accepted"))
        }
        submitImage(encoded)
        val work = checkNotNull(invokeEngine(temporary, "takeWork"))
        val payload = work.javaClass.getMethod("getPayload").invoke(work) as JSONObject
        // Do not accept a model action or run a second request in a one-shot probe.
        invokeEngine(temporary, "control", run.getString("id"), "cancel", JSONObject())
        return payload
    }

    private fun replayDuration(body: JSONObject): Long {
        val waits = body.optJSONArray("replay_waits") ?: return 0L
        require(waits.length() <= 3)
        return (0 until waits.length()).sumOf { index -> waits.getJSONObject(index).getLong("duration_ms").also { require(it in 100..30000) } }
    }

    private fun receipt(body: JSONObject): JSONObject {
        val data = body.optJSONObject("data")
        if (data?.has("image_base64") == true) {
            val text = data.getString("image_base64"); require(text.length <= 7 * 1024 * 1024)
            val bytes = Base64.getDecoder().decode(text)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096 && dimensions.outMimeType == "image/png")
            val frame = data.getJSONObject("visual_frame")
            require(frame.getInt("image_width") == dimensions.outWidth && frame.getInt("image_height") == dimensions.outHeight)
            require(frame.getString("package_name") == "pc.Arknights.exe")
            require(frame.getString("sha256") == MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        val accepted = call("result", body) as JSONObject
        if (accepted.optBoolean("accepted") && data?.has("image_base64") == true) {
            val commandId = UUID.fromString(body.getString("command_id")).toString()
            File(folder, "$commandId.png").writeBytes(Base64.getDecoder().decode(data.getString("image_base64")))
            File(folder, "$commandId-frame.json").writeText(data.getJSONObject("visual_frame").toString(2))
        }
        pump()
        return accepted
    }

    private fun state() = JSONObject().put("run", id?.let { call("get", it) } ?: JSONObject.NULL)
        .put("events", id?.let { call("events", it) } ?: JSONObject()).put("model_busy", pumping.get())
        .put("model_calls_enabled", allowModel)

    private fun pump() {
        if (!allowModel || closed.get() || call("readyForWork") != true || !pumping.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (!closed.get()) {
                    val work = call("takeWork") ?: break
                    val cls = work.javaClass
                    val payload = cls.getMethod("getPayload").invoke(work) as JSONObject
                    val local = cls.getMethod("getLocalTool").invoke(work) as? String
                    if (local != null) {
                        try {
                            val result = when (local) {
                                "list_skills" -> skills.list(payload.optString("query"), payload.optInt("offset", 0), payload.optInt("limit", 20))
                                "load_skill" -> skills.read(payload.getString("name"), payload.optString("revision").ifBlank { null }, payload.optInt("offset", 0), payload.optInt("max_chars", 4500))
                                "read_skill_resource" -> skills.resource(payload.getString("name"), payload.getString("path"), payload.optString("revision").ifBlank { null }, payload.optInt("offset", 0), payload.optInt("max_chars", 4500))
                                "search_web" -> web.search(payload.getString("query")) { call("isCurrent", work) == true }
                                "read_web" -> web.read(payload.getString("url")) { call("isCurrent", work) == true }
                                "locate_ui" -> guiClass.methods.single { it.name == "locate" && it.parameterCount == 2 }
                                    .invoke(gui, payload, { call("isCurrent", work) == true }) as JSONObject
                                else -> error("Unsupported knowledge tool")
                            }
                            call("acceptLocal", work, result, false)
                        } catch (_: Exception) { call("acceptLocal", work, null, true) }
                    } else {
                        try {
                            val response = model.complete(payload) { c ->
                                connection.set(c)
                                if (closed.get() || call("isCurrent", work) != true) { c.disconnect(); error("Task cancelled") }
                            }
                            call("accept", work, response, null)
                        } catch (e: Exception) { call("accept", work, null, safeError(e)) }
                        finally { connection.set(null) }
                    }
                }
            } catch (e: Exception) { call("interrupt", "PC bridge: ${safeError(e)}") }
            finally { pumping.set(false); if (!closed.get() && call("readyForWork") == true) pump() }
        }
    }

    fun close() {
        closed.set(true)
        id?.let { call("control", it, "cancel", JSONObject()) }
        cancelProbe(); connection.getAndSet(null)?.disconnect(); web.cancel(); guiClass.getMethod("cancel").invoke(gui); worker.shutdownNow()
        File(folder, "final-state.json").writeText(state().toString(2))
    }

    private fun cancelProbe() {
        probe.get()?.let { synchronized(it) { if (it.optString("status") == "running") it.put("status", "cancelled") } }
    }
}

private fun safeError(error: Throwable): String = (error.javaClass.simpleName + ": " + error.message.orEmpty())
    .replace(Regex("(?i)sk-[a-z0-9_-]+|Bearer\\s+[^\\s\"]+"), "[redacted]").take(1200)
