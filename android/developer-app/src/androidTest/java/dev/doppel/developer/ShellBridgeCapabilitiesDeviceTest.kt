package dev.doppel.developer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.ShellBridgeClient
import dev.doppel.sdk.ShellBridgeImeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Platform receipts on explicitly prepared disposable real-app screens; never an Agent business score. */
class ShellBridgeCapabilitiesDeviceTest {
    private val harness = ShellBridgeLiveHarness()
    private val args get() = InstrumentationRegistry.getArguments()
    private val calculator = "com.darkempire78.opencalculator"
    private val allowed = setOf(calculator, "net.gsantner.markor", "cn.wps.moffice_eng")
    private val runId = "bridge-capability-" + UUID.randomUUID()
    private var expectedPackage = ""

    @Before fun requirePreparedDisposableScreen() {
        harness.requireOptIn()
        ShellBridgeSdkReflection.verifyContract()
        expectedPackage = args.getString("expected_package").orEmpty()
        check(expectedPackage in allowed) { "expected_package must select an authorized real test app" }
        check(args.getString("disposable_screen") == "true") { "Requires explicit disposable_screen=true" }
    }

    @Test fun chineseCommitUsesActualInputConnectionAndRestoresBlankField() = scenario("chinese-commit") { service, report ->
        harness.withTaskIme {
            val editor = focusBlankEditor(service)
            val session = currentIme(service, editor)
            val marker = "桥接中文验证"
            var verified = false
            try {
                val receipt = commitImeText(service, editor, session, marker, false, "")
                report.put("commit", compact(receipt)).put("component", "ShellBridgeImeService.performTextAuthorized")
                assertAccepted(receipt, "doppel_task_ime")
                assertTrue("The real editor must expose the exact committed Chinese text", waitFor(3000) {
                    findEditor(service.observe(), editor).optString("text") == marker
                })
                verified = true
                report.put("chinese_text_visible", true).put("committed_characters", marker.length)
            } finally {
                // Restore only the exact test-owned value after a confirmed receipt; never retry uncertain input.
                if (verified) {
                    val restore = commitImeText(service, editor, session, "", true, marker)
                    assertAccepted(restore, "doppel_task_ime")
                    assertTrue("The disposable editor must be blank again", waitFor(2000) { findEditor(service.observe(), editor).optString("text").isEmpty() })
                    report.put("blank_field_restored", true)
                }
            }
        }
    }

    @Test fun advertisedDoneOrNextUsesServiceEditorAction() = scenario("editor-action") { service, report ->
        val action = args.getString("editor_action").orEmpty()
        check(action in setOf("done", "next")) { "Select done or next explicitly for the currently prepared editor" }
        harness.withTaskIme {
            val editor = focusBlankEditor(service)
            val session = currentIme(service, editor)
            assertTrue("Current EditorInfo must advertise an actual editor action", session.optBoolean("available"))
            assertEquals("A missing action is a coverage gap, never substituted with Enter", action, session.optString("action"))
            val before = observe(service)
            val node = findEditor(before, editor)
            assertEquals(action, node.optString("ime_action"))
            assertEquals(session.getString("editor_id"), node.optString("ime_editor_id"))
            val receipt = service.execute(command("ime_action").put("screen_id", before.getString("screen_id"))
                .put("target", node.getString("id")).put("editor_id", session.getString("editor_id")).put("action", action))
            report.put("action", action).put("receipt", compact(receipt))
            assertAccepted(receipt, "doppel_task_ime")
            if (action == "next") {
                assertTrue("NEXT must move to a different actual editor session", waitFor(2500) {
                    val current = ShellBridgeImeService.capability()
                    current.optBoolean("input_available") && current.optString("package_name") == expectedPackage &&
                        current.optString("editor_id") != session.optString("editor_id")
                })
            }
            val after = service.observe()
            report.put("observed_screen_changed", before.optString("screen_id") != after.optString("screen_id"))
                .put("editor_session_changed", ShellBridgeImeService.capability().optString("editor_id") != session.optString("editor_id"))
                .put("business_success_asserted", false)
        }
    }

    @Test fun serviceTypeUsesImeOnlyAfterKnownNativeFailure() = scenario("service-ime-fallback") { service, report ->
        harness.withTaskIme {
            val editor = focusBlankEditor(service)
            val session = currentIme(service, editor)
            val screen = observe(service)
            val node = findEditor(screen, editor)
            val marker = "服务中文验证"
            val result = service.execute(command("type").put("screen_id", screen.getString("screen_id"))
                .put("target", node.getString("id")).put("text", marker))
            report.put("receipt", compact(result)).put("native_action_state", result.optJSONObject("data")?.optString("native_action_state"))
            val accepted = result.optJSONObject("data")?.optString("action_state") == "accepted"
            try {
                assertAccepted(result, "doppel_task_ime")
                assertEquals("The native ACTION_SET_TEXT must explicitly fail before IME fallback", "failed", result.getJSONObject("data").optString("native_action_state"))
                assertTrue(waitFor(2500) { findEditor(observe(service), editor).optString("text") == marker })
                report.put("service_ime_chinese_visible", true)
            } finally {
                if (accepted && currentEditorMatches(service, editor, session) && findEditor(observe(service), editor).optString("text") == marker) {
                    val restore = commitImeText(service, editor, session, "", true, marker)
                    assertAccepted(restore, "doppel_task_ime")
                    assertTrue(waitFor(2000) { findEditor(observe(service), editor).optString("text").isEmpty() })
                    report.put("blank_field_restored", true)
                }
            }
        }
    }

    @Test fun currentSafeSubpageBackUsesActualShellReceipt() = scenario("back") { service, report ->
        check(args.getString("allow_back") == "true") { "Requires a prepared safe subpage and allow_back=true" }
        val before = observe(service)
        val result = service.execute(command("back"))
        report.put("receipt", compact(result))
        assertAccepted(result, "adb_shell_local_socket")
        assertProcessExitZero(result)
        val after = service.observe()
        report.put("before_package", before.optString("package_name")).put("after_package", after.optString("package_name"))
            .put("observed_screen_changed", before.optString("screen_id") != after.optString("screen_id"))
    }

    @Test fun calculatorGestureUsesServiceCapturePermitAndCurrentTarget() = scenario("gesture") { service, report ->
        requireBlankCalculator(service)
        val shot = screenshot(service)
        val gesture = calculatorSeven(shot)
        val permit = ShellBridgeSdkReflection.issue(runId, gesture, "ask", true)
        try {
            val result = service.execute(command("visual_gesture").put("gesture", gestureJson(gesture)).put("visual_permit", permit))
            report.put("receipt", compact(result)).put("target", gestureJson(gesture))
            assertAccepted(result, "adb_shell_local_socket")
            assertProcessExitZero(result)
            assertTrue("OpenCalc must visibly contain one 7", waitFor(2000) { calculatorInput(observe(service)) == "7" })
            report.put("single_digit_observed", true)
            clearVerifiedSeven(service)
            report.put("calculator_restored", true)
        } finally { ShellBridgeSdkReflection.revoke(runId) }
    }

    @Test fun disconnectAfterOneRequestDoesNotReplayOrFallBack() = scenario("disconnect") { service, report ->
        check(args.getString("inject_socket_disconnect") == "true") { "Requires explicit inject_socket_disconnect=true" }
        requireBlankCalculator(service)
        val first = screenshot(service)
        val gesture = calculatorSeven(first)
        val second = screenshot(service)
        val frame = ShellBridgeSdkReflection.frame(second.getJSONObject("data").getJSONObject("visual_frame"))
        val beforePixels: Any = pixels(first); val afterPixels: Any = pixels(second)
        val pixelFacts = ShellBridgeSdkReflection.comparePixels(beforePixels, afterPixels, gesture)
        assertTrue("Only actual current target RGB/edges may authorize the diagnostic tap", pixelFacts.getBoolean("matches"))
        report.put("pixel_check", pixelFacts)
        val source = ShellBridgeClient.source(second.getJSONObject("observation"), frame.getInt("rotation"), frame.getString("capture_id"))
            .put("captured_at", frame.getLong("captured_at_elapsed_ms")).put("pixel_verification", "host_target_rgb_edges")
        check(SystemClock.elapsedRealtime() - frame.getLong("captured_at_elapsed_ms") in 0..1000) { "Source expired before the one diagnostic dispatch" }
        val client = ShellBridgeClient.get(harness.context)
        synchronized(client) {
        val socketField = client.javaClass.getDeclaredField("socket").apply { isAccessible = true }
        val outputField = client.javaClass.getDeclaredField("output").apply { isAccessible = true }
        val socket = socketField.get(client) as LocalSocket
        val original = outputField.get(client) as DataOutputStream
        val id = "bridge-disconnect-" + UUID.randomUUID()
        var mutationWrites = 0
        val written = ByteArrayOutputStream()
        val cutAfterWrite = DataOutputStream(object : OutputStream() {
            override fun write(value: Int) { original.write(value); written.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { original.write(bytes, offset, length); written.write(bytes, offset, length) }
            override fun flush() {
                original.flush()
                val bytes = written.toByteArray(); written.reset()
                val request = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val size = input.readInt(); check(size in 2..8192)
                    JSONObject(ByteArray(size).also(input::readFully).toString(Charsets.UTF_8))
                }
                check(request.getString("id") == id && request.getString("op") == "tap")
                mutationWrites++
                socket.close() // Only this App connection; production helper and ADB transport remain alive.
            }
        })
        outputField.set(client, cutAfterWrite)
        try {
            val coordinates = gestureJson(gesture)
            val result = client.executeAuthorized(id, runId, source, "tap", JSONObject().put("x", (coordinates.getDouble("x") * frame.getInt("display_width")).toInt()).put("y", (coordinates.getDouble("y") * frame.getInt("display_height")).toInt()),
                { ShellBridgeClient.source(observe(service), rotation()) }, { DoppelAccessibilityService.instance === service && DeviceWorkerService.instance == null })
            report.put("receipt", compact(result)).put("mutation_api_calls", 1).put("mutation_request_writes", mutationWrites)
            assertEquals(1, mutationWrites)
            assertEquals("error", result.optString("status"))
            assertEquals("unconfirmed", result.optString("action_state"))
            assertEquals("backend_result_unconfirmed", result.optString("reason_code"))
            assertFalse(result.optBoolean("proves_business_success"))
            val observed = linkedSetOf<String>()
            val until = SystemClock.elapsedRealtime() + 4500
            do {
                val value = calculatorInput(observe(service))
                assertTrue("An uncertain single request must never become duplicate input", value in setOf("", "0", "7"))
                observed.add(value)
                Thread.sleep(100)
            } while (SystemClock.elapsedRealtime() < until)
            // This uses the real client's read-only ping reconnect. The uncertain mutation is never submitted again.
            val state = client.status()
            assertTrue("Read-only reconnect must find the same shell backend", state.optBoolean("connected") && state.optInt("uid") == 2000)
            val afterReconnect = calculatorInput(observe(service))
            assertEquals(observed.last(), afterReconnect)
            report.put("observed_input_states", JSONArray(observed)).put("reconnected_read_only", true)
                .put("single_request_effect_observed", afterReconnect == "7").put("disconnect_after_full_request_write", true)
                .put("layer", "ShellBridgeClient").put("duplicate_digit_observed", false).put("business_success_asserted", false)
            // Leave a visible 7 for review if injection occurred. No cleanup action follows an uncertain result.
        } finally {
            if (outputField.get(client) === cutAfterWrite) outputField.set(client, original)
            written.reset()
        }
        }
    }

    private fun scenario(name: String, body: (DoppelAccessibilityService, JSONObject) -> Unit) {
        val report = JSONObject().put("platform_acceptance_only", true).put("business_success_asserted", false).put("case", name).put("ok", false)
        val folder = File(harness.context.getExternalFilesDir(null), "shell-capabilities/$runId").apply { check(mkdirs() || isDirectory) }
        var primary: Throwable? = null
        try { harness.withService { service -> observe(service); body(service, report) }; report.put("ok", true) }
        catch (failure: Throwable) { primary = failure; report.put("failure_class", failure.javaClass.name); throw failure }
        finally {
            report.put("cleanup_failures", harness.cleanupFailures).put("ime_binding", harness.imeDiagnostics)
            try { File(folder, "$name.json").writeText(report.toString(2)) }
            catch (failure: Throwable) { if (primary != null) primary.addSuppressed(failure) else throw failure }
        }
    }
    private fun command(kind: String) = JSONObject().put("id", "capability-" + UUID.randomUUID()).put("run_id", runId).put("kind", kind)
    private fun observe(service: DoppelAccessibilityService): JSONObject = service.observe().also {
        assertEquals("Only the explicitly prepared real app may be operated", expectedPackage, it.getString("package_name"))
        val nodes = nodes(it)
        check(nodes.none { node -> node.optBoolean("password") }) { "Sensitive editor screen is outside this diagnostic scope" }
    }
    private fun nodes(screen: JSONObject) = screen.getJSONArray("nodes").let { array -> (0 until array.length()).map(array::getJSONObject) }
    private fun focusBlankEditor(service: DoppelAccessibilityService): String {
        var screen = observe(service)
        val selector = args.getString("editor_resource_id").orEmpty()
        val editors = nodes(screen).filter { it.optBoolean("editable") && it.optBoolean("enabled") && !it.optBoolean("password") &&
            (selector.isBlank() || it.optString("resource_id") == selector) }
        val editor = editors.singleOrNull() ?: error("Provide editor_resource_id from the current screen when the editable target is ambiguous")
        check(editor.optString("text").isEmpty()) { "A disposable blank editor is required; existing content must not be overwritten" }
        val resource = editor.optString("resource_id")
        check(resource.isNotBlank()) { "A stable current editor resource ID is required" }
        if (!editor.optBoolean("focused")) {
            val focus = service.execute(command("tap").put("screen_id", screen.getString("screen_id")).put("target", editor.getString("id")))
            assertEquals("ok", focus.optString("status"))
        }
        assertTrue("Real editor focus and IME session must become available", waitFor(4000) {
            screen = observe(service)
            findEditor(screen, resource).optBoolean("focused") && ShellBridgeImeService.capability().optBoolean("input_available")
        })
        check(findEditor(screen, resource).optString("text").isEmpty())
        return resource
    }
    private fun findEditor(screen: JSONObject, resource: String): JSONObject = nodes(screen).single {
        it.optBoolean("editable") && it.optString("resource_id") == resource
    }
    private fun currentIme(service: DoppelAccessibilityService, resource: String): JSONObject = ShellBridgeImeService.capability().also {
        harness.requireTaskImeBinding()
        check(it.optBoolean("input_available") && it.optString("package_name") == expectedPackage)
        check(findEditor(observe(service), resource).optBoolean("focused"))
        check(it.optString("editor_id").isNotBlank())
    }
    private fun currentEditorMatches(service: DoppelAccessibilityService, resource: String, session: JSONObject) = runCatching {
        val current = currentIme(service, resource)
        current.getString("editor_id") == session.getString("editor_id") && DoppelAccessibilityService.instance === service && DeviceWorkerService.instance == null
    }.getOrDefault(false)
    private fun commitImeText(service: DoppelAccessibilityService, resource: String, session: JSONObject,
        text: String, replace: Boolean, expectedBefore: String): JSONObject {
        // These real binding/focus/content checks run on the instrumentation thread, before onMain.
        check(currentEditorMatches(service, resource, session)) { "Editor changed before diagnostic input" }
        check(findEditor(observe(service), resource).optString("text") == expectedBefore) { "Do not overwrite changed editor content" }
        val guard = ShellBridgeImeCallbackGuard(expectedPackage, session.getString("editor_id"), SystemClock.elapsedRealtime(),
            SystemClock::elapsedRealtime, { DoppelAccessibilityService.instance === service && DeviceWorkerService.instance == null },
            ShellBridgeImeService::capability)
        return try {
            // The real product API invokes this callback on the IME main thread. Only local reads here.
            ShellBridgeImeService.performTextAuthorized(expectedPackage, session.getString("editor_id"), text, replace) { guard.isCurrent() }
        } finally { guard.close() } // A timed-out call never leaves a test callback authorizing late input.
    }
    private fun screenshot(service: DoppelAccessibilityService): JSONObject = service.execute(command("screenshot")).also {
        assertEquals("Current host screenshot is required", "ok", it.optString("status"))
        assertEquals("adb_shell", it.getJSONObject("data").getString("capture_backend"))
        assertEquals(expectedPackage, it.getJSONObject("observation").getString("package_name"))
    }
    private fun requireBlankCalculator(service: DoppelAccessibilityService) {
        check(expectedPackage == calculator)
        check(calculatorInput(observe(service)) in setOf("", "0")) { "Prepare OpenCalc with a blank or zero disposable input" }
    }
    private fun calculatorInput(screen: JSONObject) = nodes(screen).single { it.optString("resource_id") == "$calculator:id/input" }.optString("text")
    private fun calculatorSeven(shot: JSONObject): Any {
        val frame = ShellBridgeSdkReflection.frame(shot.getJSONObject("data").getJSONObject("visual_frame"))
        val target = nodes(shot.getJSONObject("observation")).single { it.optBoolean("clickable") && it.optString("text") == "7" }
        val bounds = target.getJSONArray("bounds")
        return ShellBridgeSdkReflection.gesture(JSONObject().put("kind", "tap").put("capture_id", frame.getString("capture_id"))
            .put("x", (bounds.getDouble(0) + bounds.getDouble(2)) / (2 * frame.getInt("display_width")))
            .put("y", (bounds.getDouble(1) + bounds.getDouble(3)) / (2 * frame.getInt("display_height")))
            .put("duration_ms", 80).put("label", "OpenCalc digit 7")
            .put("screen_context", "Authorized disposable OpenCalc keypad").put("safety", "safe"))
    }
    private fun gestureJson(gesture: Any) = ShellBridgeSdkReflection.gestureJson(gesture)
    private fun clearVerifiedSeven(service: DoppelAccessibilityService) {
        val screen = observe(service)
        check(calculatorInput(screen) == "7")
        val clear = nodes(screen).single { it.optBoolean("clickable") && it.optString("text") == "AC" }
        val result = service.execute(command("tap").put("screen_id", screen.getString("screen_id")).put("target", clear.getString("id")))
        assertEquals("ok", result.optString("status"))
        assertTrue(waitFor(2000) { calculatorInput(observe(service)) in setOf("", "0") })
    }
    private fun pixels(shot: JSONObject): Any {
        val bytes = Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.NO_WRAP)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try { ShellBridgeSdkReflection.pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }) }
        finally { bitmap.recycle() }
    }
    private fun compact(result: JSONObject): JSONObject {
        val data = result.optJSONObject("data") ?: result
        return JSONObject().put("status", result.optString("status")).put("action_state", data.optString("action_state"))
            .put("backend", data.optString("backend")).put("reason_code", data.optString("reason_code"))
            .apply { ShellBridgeSdkReflection.diagnostic(data.optJSONObject("shell_diagnostic"))?.let { put("shell_diagnostic", it) } }
    }
    private fun assertAccepted(result: JSONObject, backend: String) {
        val data = result.optJSONObject("data") ?: result
        assertEquals("ok", result.optString("status")); assertEquals("accepted", data.optString("action_state")); assertEquals(backend, data.optString("backend"))
    }
    private fun assertProcessExitZero(result: JSONObject) {
        val diagnostic = result.getJSONObject("data").getJSONObject("shell_diagnostic")
        assertTrue(diagnostic.getBoolean("process_exited")); assertEquals(0, diagnostic.getInt("exit_code")); assertEquals("process_ok", diagnostic.getString("reason_code"))
    }
    @Suppress("DEPRECATION") private fun rotation() = (harness.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    private fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(80)
        return condition()
    }
}
