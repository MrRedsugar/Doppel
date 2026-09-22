@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import dev.doppel.sdk.companion.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Opt-in isolated host test. Injects only pairing/network authority; real frames and MotionEvents. No model calls. */
class LanHandoffDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(80)
        assertTrue(label, condition())
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun state(): JSONObject? {
        val all = nodes(ui.rootInActiveWindow)
        return try { all.firstOrNull { it.contentDescription?.startsWith("gesture-result:") == true }
            ?.contentDescription?.toString()?.substringAfter("gesture-result:")?.let(::JSONObject) }
        finally { all.forEach { it.recycle() } }
    }
    private fun bounds(label: String): Rect {
        val all = nodes(ui.rootInActiveWindow)
        return try { Rect().also { out -> all.first { it.contentDescription?.toString() == label || it.text?.toString() == label }.getBoundsInScreen(out) } }
        finally { all.forEach { it.recycle() } }
    }
    private fun expect(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (expected: CompanionProtocolException) { assertEquals(code, expected.code) }
    }
    @Test fun gatewayWorkerHandoffAndPersistedNotifications() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gateway_lan_handoff_test") == "true")
        assertEquals("Gateway fixture must never run in the owner's app", "dev.doppel.handoffqa", context.packageName)
        ui
        assertNull("Never replace an existing worker", DeviceWorkerService.instance)
        assertTrue(FirstUseConsent.isAccepted(context))
        val prefs = context.getSharedPreferences("doppel", 0)
        assertTrue("Requires an idle isolated installation", prefs.getString("active_run", "").isNullOrBlank())
        assertFalse(AutomaticUnlockSession.active); assertFalse(AutomaticUnlockSession.locked(context))
        assertTrue(TaskSubmissionGate.creating.compareAndSet(false, true))
        val names = listOf("doppel", "doppel_gateway_task_events")
        val saved = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val report = JSONObject().put("passed", false).put("model_calls", 0)
            .put("scope", "gateway HTTP responses, live DeviceWorker, real screenshot/tap; injected pairing authority and native takeover receipt")
        val folder = context.getExternalFilesDir(null)!!.resolve("gateway-lan-handoff").apply { mkdirs() }
        var worker: DeviceWorkerService? = null
        var workerRequested = false
        val local = HandoffGateway()
        try {
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNotNull(DoppelAccessibilityService.instance)
            check(context.getSharedPreferences("doppel_gateway_task_events", 0).edit().clear().commit())
            check(prefs.edit().putBoolean("direct_mode", false).putBoolean("artemis_mode", false)
                .putString("base_url", "http://127.0.0.1:${local.port}").putString("token", "gateway-handoff-fixture")
                .putString("device_id", local.device).putString("active_run", local.runId)
                .putBoolean("touch_pause", false).putBoolean("completion_speech", false).commit())
            val gateway = Gateway(context)
            val scope = gateway.reviewScope()
            assertFalse(gateway.isDirectMode())
            val fixtureSession = UUID.randomUUID().toString()
            context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity")
                .putExtra("mode", "events").putExtra("session", fixtureSession)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Gesture fixture visible") { state()?.optString("session") == fixtureSession }
            workerRequested = true
            assertTrue(TaskControl.startWorker(context))
            await("Gateway worker receives manual-only server wait") {
                DeviceWorkerService.instance?.companionGatewayState(scope)?.optString("status") == "awaiting_input"
            }
            worker = requireNotNull(DeviceWorkerService.instance)
            await("Manual-only notification persisted") { GatewayTaskEvents.read(context, scope).length() > 0 }
            assertEquals("manual_takeover", GatewayTaskEvents.read(context, scope).getJSONObject(0).getString("pause_category"))
            val auth = object : CompanionAuthContext {
                override val pairId = "gateway-instrumentation-only"
                override val pairGeneration = 1L
                override val grantedScopes = setOf("state", "screen_control")
                override fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T {
                    check(grantedScopes.containsAll(requiredScopes)); return action()
                }
            }
            val host = SdkLanHandoff(context)
            fun start() = host.handle(auth, "start", JSONObject().put("run_id", local.runId)) { true }.body.getString("session_id")
            fun request(session: String) = JSONObject().put("session_id", session)
            var session = start()
            assertTrue("Gateway executor is actually stopped before human input", worker.isPaused)
            SystemClock.sleep(450)
            val frame = host.handle(auth, "frame", request(session)) { true }.body
            folder.resolve("initial.png").writeBytes(Base64.decode(frame.getString("image_base64"), Base64.DEFAULT))
            val target = bounds("gesture-surface")
            val action = request(session).put("frame_id", frame.getString("frame_id")).put("action_id", UUID.randomUUID().toString())
                .put("kind", "tap").put("x", target.centerX() / (frame.getInt("display_width") - 1).toDouble())
                .put("y", target.centerY() / (frame.getInt("display_height") - 1).toDouble())
            assertEquals("ok", host.handle(auth, "action", action) { true }.body.getString("status"))
            await("Gateway-mode human tap reaches actual Android view") { state()?.optInt("taps") == 1 }
            assertEquals("ok", host.handle(auth, "action", action) { true }.body.getString("status"))
            assertEquals(1, state()!!.getInt("down"))
            report.put("real_tap_and_dedup", state())

            local.status = "running"
            TaskControl.invalidate()
            inst.runOnMainSync { worker.resume() }
            await("Worker observes resumed gateway task") { worker.companionGatewayState(scope)?.optString("status") == "running" }
            expect("handoff_expired") { host.handle(auth, "frame", request(session)) { true } }
            expect("task_not_interrupted") { start() }
            val oldTicket = TaskControl.currentGeneration()
            TaskControl.invalidate()
            assertNull("An outdated start cannot pause a newly resumed task", worker.pauseForLanHandoff(local.runId, scope, oldTicket))
            assertFalse(worker.isPaused)

            // The mutation response hook must retain brief transitions even if no worker poll sees them.
            gateway.request("POST", "/runs/${local.runId}/pause")
            gateway.request("POST", "/runs/${local.runId}/resume")
            gateway.request("POST", "/runs/${local.runId}/pause")
            val mutations = GatewayTaskEvents.read(context, scope)
            val statuses = (0 until mutations.length()).map { mutations.getJSONObject(it).getString("status") }
            assertEquals(listOf("paused", "running", "paused"), statuses.takeLast(3))
            val afterMutations = mutations.toString()
            gateway.request("GET", "/runs/${local.runId}")
            assertEquals("Review reads never manufacture task notifications", afterMutations, GatewayTaskEvents.read(context, scope).toString())

            local.status = "running"
            TaskControl.invalidate()
            inst.runOnMainSync { worker.resume() }
            await("Worker is running before injected native receipt") { worker.companionGatewayState(scope)?.optString("status") == "running" }
            val receipt = JSONObject().put("run_id", local.runId).put("message", "设备步骤需要手动接管")
                .put("data", JSONObject().put("human_takeover", "interruption"))
            val nativePause = DeviceWorkerService::class.java.getDeclaredMethod("pauseForTakeover", JSONObject::class.java).apply { isAccessible = true }
            inst.runOnMainSync { assertEquals(true, nativePause.invoke(worker, receipt)) }
            assertTrue(worker.isPaused)
            assertEquals("paused", worker.companionGatewayState(scope)!!.getString("status"))
            val events = GatewayTaskEvents.read(context, scope)
            assertEquals("manual_takeover", events.getJSONObject(events.length() - 1).getString("pause_category"))
            assertEquals(events.toString(), GatewayTaskEvents.read(context, scope).toString())
            report.put("events", events)
            session = start()
            check(prefs.edit().putString("active_run", "another-task").commit())
            expect("handoff_expired") { host.handle(auth, "frame", request(session)) { true } }
            assertNull(worker.pauseForLanHandoff(local.runId, scope, TaskControl.currentGeneration()))
            check(prefs.edit().putString("token", "changed-fixture-owner").commit())
            assertEquals(0, GatewayTaskEvents.read(context, gateway.reviewScope()).length())
            assertEquals("No model, history or extra polling endpoints", 0, local.unexpectedRequests)
            report.put("passed", true)
        } finally {
            try {
                assertTrue(DeviceWorkerService.instance == null || workerRequested && (worker == null || worker === DeviceWorkerService.instance))
                if (workerRequested) context.stopService(Intent(context, DeviceWorkerService::class.java))
                await("Fixture worker stopped before restoring gateway credentials") { DeviceWorkerService.instance == null }
                worker?.let { service ->
                    val executor = DeviceWorkerService::class.java.getDeclaredField("executor").apply { isAccessible = true }
                        .get(service) as java.util.concurrent.ExecutorService
                    assertTrue(executor.awaitTermination(8, TimeUnit.SECONDS))
                }
                names.forEach { name ->
                    val editor = context.getSharedPreferences(name, 0).edit().clear()
                    saved.getValue(name).forEach { (key, value) -> when (value) {
                        is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }
                    check(editor.commit())
                }
                report.put("original_preferences_restored", names.all { saved.getValue(it) == context.getSharedPreferences(it, 0).all })
            } finally {
                local.close(); TaskSubmissionGate.creating.set(false)
                folder.resolve("report.json").writeText(report.toString(2))
            }
        }
    }

    private class HandoffGateway : AutoCloseable {
        val device = "gateway-handoff-phone"
        val runId = UUID.randomUUID().toString()
        @Volatile var status = "awaiting_input"
        @Volatile var unexpectedRequests = 0
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        @Volatile private var open = true
        private fun run() = JSONObject().put("id", runId).put("device_id", device).put("status", status)
            .put("title", "网关手动接管测试").put("goal", "isolated fixture").put("mode", "full").put("events", JSONArray())
            .put("pending_request", if (status == "awaiting_input") JSONObject().put("id", "manual-one")
                .put("kind", "input").put("manual_only", true).put("message", "请手动操作") else JSONObject.NULL)
        private val thread = Thread({
            while (open) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String = buildString {
                        while (length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) append(c.toChar()) }
                    }
                    val first = line().split(' '); check(first.size >= 2)
                    val method = first[0]; val path = first[1].substringBefore('?')
                    var size = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    check(size in 0..1024 * 1024)
                    repeat(size) { check(input.read() >= 0) }
                    val response = when {
                        method == "GET" && path == "/v1/runs/$runId" -> run()
                        method == "POST" && path == "/v1/runs/$runId/pause" -> { status = "paused"; run() }
                        method == "POST" && path == "/v1/runs/$runId/resume" -> { status = "running"; run() }
                        method == "GET" && path == "/v1/devices/$device/data-cleanup" -> JSONObject().put("items", JSONArray())
                        method == "GET" && path == "/v1/devices/$device/commands" -> { SystemClock.sleep(100); JSONObject().put("command", JSONObject.NULL) }
                        else -> { unexpectedRequests++; JSONObject() }
                    }.toString().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 Fixture\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(response); flush()
                    }
                }
            } catch (_: Exception) { if (!open) break }
        }, "handoff-loopback").apply { isDaemon = true; start() }
        override fun close() { open = false; socket.close(); thread.join(1500) }
    }
    @Test fun realPixelsHumanGesturesDeduplicationAndRevocation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lan_handoff_test") == "true")
        ui
        assertNull("Never interrupt an existing worker", DeviceWorkerService.instance)
        assertTrue("Requires normal first-use consent", FirstUseConsent.isAccepted(context))
        assertTrue("Use an isolated configured developer installation", DirectMode.isEnabled(context))
        val runtime = DirectRuntime.get(context)
        val engine = DirectRuntime::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(runtime) as SplitTaskEngine
        assertEquals("Requires an empty isolated task store", 0, engine.list().getJSONArray("items").length())
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNotNull(DoppelAccessibilityService.instance)
        assertFalse(AutomaticUnlockSession.locked(context))
        val task = engine.create(JSONObject().put("goal", "LAN human handoff fixture only").put("device_id", DirectRuntime.DEVICE_ID)
            .put("mode", "full").put("conversation_enabled", false))
        val runId = task.getString("id")
        engine.control(runId, "pause", JSONObject())
        var authorized = true
        var lan = true
        val auth = object : CompanionAuthContext {
            override val pairId = "instrumentation-only"
            override val pairGeneration = 1L
            override val grantedScopes = setOf("state", "screen_control")
            override fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T {
                if (!authorized) throw CompanionAuthorizationException(401, "pairing_revoked")
                check(grantedScopes.containsAll(requiredScopes)); return action()
            }
        }
        val host = SdkLanHandoff(context)
        val evidence = JSONObject().put("passed", false).put("model_calls", 0)
            .put("scope", "real Android frame/action/session; injected test authority, not PC LAN transport")
        val actions = JSONArray(); evidence.put("actions", actions)
        val folder = context.getExternalFilesDir(null)!!.resolve("lan-handoff").apply { mkdirs() }
        fun open(mode: String) {
            val fixtureSession = UUID.randomUUID().toString()
            context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity")
                .putExtra("mode", mode).putExtra("session", fixtureSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Fixture visible") { state()?.optString("session") == fixtureSession }; SystemClock.sleep(350)
        }
        fun start() = host.handle(auth, "start", JSONObject().put("run_id", runId)) { lan }.body.getString("session_id")
        fun request(session: String) = JSONObject().put("session_id", session)
        fun frame(session: String): JSONObject {
            var error: Throwable? = null
            repeat(5) {
                try { return host.handle(auth, "frame", request(session)) { lan }.body }
                catch (failure: CompanionProtocolException) { error = failure; SystemClock.sleep(350) }
            }
            throw requireNotNull(error)
        }
        fun command(session: String, frame: JSONObject, kind: String) = request(session).put("frame_id", frame.getString("frame_id"))
            .put("action_id", UUID.randomUUID().toString()).put("kind", kind)
        fun point(command: JSONObject, frame: JSONObject, x: Float, y: Float, end: Boolean = false): JSONObject {
            val box = bounds("gesture-surface")
            return command.put(if (end) "end_x" else "x", (box.left + box.width() * x) / (frame.getInt("display_width") - 1).toDouble())
                .put(if (end) "end_y" else "y", (box.top + box.height() * y) / (frame.getInt("display_height") - 1).toDouble())
        }
        try {
            open("events")
            var session = start()
            var image = frame(session)
            folder.resolve("initial.png").writeBytes(Base64.decode(image.getString("image_base64"), Base64.DEFAULT))
            assertFalse(image.has("_window_id"))
            val tap = point(command(session, image, "tap"), image, .4f, .4f)
            assertEquals("ok", host.handle(auth, "action", tap) { lan }.body.getString("status"))
            await("Real tap reached fixture") { state()?.optInt("taps") == 1 }
            assertEquals("ok", host.handle(auth, "action", tap) { lan }.body.getString("status"))
            assertEquals("Retry must not inject twice", 1, state()!!.getInt("down"))
            expect("action_conflict") { host.handle(auth, "action", JSONObject(tap.toString()).put("x", .1)) { lan } }
            expect("frame_expired") { host.handle(auth, "action", JSONObject(tap.toString()).put("action_id", "fresh-old-frame")) { lan } }
            actions.put(JSONObject().put("tap_and_dedup", state()))
            image = frame(session)
            val hold = point(command(session, image, "long_press"), image, .4f, .4f).put("duration_ms", 700)
            assertEquals("ok", host.handle(auth, "action", hold) { lan }.body.getString("status"))
            await("Real long press") { state()?.optInt("long") == 1 }
            actions.put(JSONObject().put("long_press", state()))
            open("drag")
            image = frame(session)
            val drag = point(point(command(session, image, "swipe"), image, .25f, .7f), image, .75f, .25f, true).put("duration_ms", 850)
            assertEquals("ok", host.handle(auth, "action", drag) { lan }.body.getString("status"))
            val box = bounds("gesture-surface")
            val token = state()!!.getJSONArray("token")
            assertEquals(box.width() * .75, token.getDouble(0), 3.0)
            assertEquals(box.height() * .25, token.getDouble(1), 3.0)
            actions.put(JSONObject().put("drag", state()))
            image = frame(session)
            val staleTap = point(command(session, image, "tap"), image, .4f, .4f)
            context.startActivity(Intent().setClassName(fixture, "$fixture.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Input fixture visible") { nodes(ui.rootInActiveWindow).let { all ->
                try { all.any { it.text?.toString() == "模拟登录" } } finally { all.forEach { it.recycle() } }
            } }
            assertEquals("stale", host.handle(auth, "action", staleTap) { lan }.body.getString("status"))
            fun tapLabel(label: String) {
                val fresh = frame(session); val target = bounds(label)
                val body = command(session, fresh, "tap").put("x", target.centerX() / (fresh.getInt("display_width") - 1).toDouble())
                    .put("y", target.centerY() / (fresh.getInt("display_height") - 1).toDouble())
                assertEquals("ok", host.handle(auth, "action", body) { lan }.body.getString("status"))
                SystemClock.sleep(450)
            }
            tapLabel("模拟登录"); tapLabel("搜索关键词")
            ui.waitForIdle(500, 5000) // IME appearance changes the window generation; never reuse its opening frame.
            image = frame(session)
            assertEquals("ok", host.handle(auth, "action", command(session, image, "type").put("text", "LAN fixture input")) { lan }.body.getString("status"))
            await("Actual editable view received text") { nodes(ui.rootInActiveWindow).let { all ->
                try { all.any { it.isEditable && it.text?.toString() == "LAN fixture input" } } finally { all.forEach { it.recycle() } }
            } }
            actions.put(JSONObject().put("text_input", "verified in actual EditText"))
            image = frame(session)
            assertEquals("ok", host.handle(auth, "action", command(session, image, "back")) { lan }.body.getString("status"))
            SystemClock.sleep(450)
            image = frame(session)
            assertEquals("ok", host.handle(auth, "action", command(session, image, "home")) { lan }.body.getString("status"))
            SystemClock.sleep(450)
            assertNotEquals("Home navigated away from fixture", fixture, ui.rootInActiveWindow?.packageName?.toString())
            image = frame(session)
            assertEquals("ok", host.handle(auth, "action", command(session, image, "recents")) { lan }.body.getString("status"))
            actions.put(JSONObject().put("global_actions", "back, home, recents accepted; home foreground verified"))
            open("events")
            val privacyOwner = Any()
            DirectMode.enterSettings(privacyOwner)
            try { expect("handoff_expired") { host.handle(auth, "frame", request(session)) { lan } } }
            finally { DirectMode.leaveSettings(privacyOwner) }
            session = start()
            lan = false
            expect("lan_required") { host.handle(auth, "frame", request(session)) { lan } }
            lan = true
            expect("handoff_expired") { host.handle(auth, "frame", request(session)) { lan } }
            session = start()
            TaskControl.invalidate()
            expect("handoff_expired") { host.handle(auth, "frame", request(session)) { lan } }
            session = start(); image = frame(session)
            engine.control(runId, "resume", JSONObject()) // No pump(): verifies actual engine state, zero model requests.
            expect("handoff_expired") { host.handle(auth, "action", point(command(session, image, "tap"), image, .4f, .4f)) { lan } }
            engine.control(runId, "pause", JSONObject())
            session = start()
            authorized = false
            try { host.handle(auth, "frame", request(session)) { lan }; fail("Revocation must reject frame") }
            catch (expected: CompanionAuthorizationException) { assertEquals(401, expected.statusCode) }
            authorized = true
            host.handle(auth, "close", request(session)) { lan }
            assertEquals("Close never resumes AI", "paused", engine.get(runId).getString("status"))
            assertEquals(0, engine.get(runId).getInt("calls"))
            evidence.put("passed", true)
        } finally {
            engine.control(runId, "cancel", JSONObject()); engine.delete(runId)
            folder.resolve("report.json").writeText(evidence.toString(2))
        }
    }
}
