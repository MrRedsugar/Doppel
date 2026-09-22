@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import dev.doppel.sdk.companion.CompanionAuthContext
import dev.doppel.sdk.companion.CompanionProtocolException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real paused worker/overlays and native receiving view; injected pairing authority, zero model calls. */
class LanHandoffOverlayDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block)
        inst.runOnMainSync(task)
        return task.get(2, TimeUnit.SECONDS)
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
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
    private fun surface(): Rect {
        val all = nodes(ui.rootInActiveWindow)
        return try { Rect().also { bounds -> all.first { it.contentDescription == "gesture-surface" }.getBoundsInScreen(bounds) } }
        finally { all.forEach { it.recycle() } }
    }
    private fun overlayState(overlay: CompanionOverlay): JSONObject = main {
        val root = field(overlay, "root") as View
        val params = field(overlay, "params") as WindowManager.LayoutParams
        JSONObject().put("attached", root.isAttachedToWindow).put("alpha", params.alpha)
            .put("not_touchable", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test fun humanTouchesReachTheAppUnderTheRealPausedCompanion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lan_handoff_overlay_test") == "true")
        assertEquals("Never run against a user's installation", "dev.doppel.handoffqa", context.packageName)
        ui
        assertNull("Never replace an existing worker", DeviceWorkerService.instance)
        assertTrue(FirstUseConsent.isAccepted(context)); assertTrue(DirectMode.isEnabled(context))
        assertTrue("Isolated host needs its normal overlay permission", Settings.canDrawOverlays(context))
        assertFalse(AutomaticUnlockSession.active); assertFalse(AutomaticUnlockSession.locked(context))
        val prefs = context.getSharedPreferences("doppel", 0)
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        val runtime = DirectRuntime.get(context)
        val engine = field(runtime, "engine") as SplitTaskEngine
        assertEquals(0, engine.list().getJSONArray("items").length())
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        assertNotNull(DoppelAccessibilityService.instance)
        assertTrue(TaskSubmissionGate.creating.compareAndSet(false, true))
        val saved = prefs.all.toMap()
        val folder = context.getExternalFilesDir(null)!!.resolve("lan-handoff-overlay").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("model_calls", 0)
            .put("scope", "real paused direct worker, pause sheet, production companion and native input; injected pairing/network authority")
            .put("frame_transitions", JSONArray())
        var stage = "setup"
        var runId: String? = null
        var worker: DeviceWorkerService? = null
        var workerRequested = false
        var session: String? = null
        val auth = object : CompanionAuthContext {
            override val pairId = "overlay-instrumentation-only"
            override val pairGeneration = 1L
            override val grantedScopes = setOf("state", "screen_control")
            override fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T {
                check(grantedScopes.containsAll(requiredScopes)); return action()
            }
        }
        val host = SdkLanHandoff(context)
        try {
            val id = engine.create(JSONObject().put("goal", "Isolated LAN overlay handoff fixture")
                .put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full").put("conversation_enabled", false)).getString("id")
            runId = id
            engine.control(id, "pause", JSONObject())
            check(prefs.edit().putString("active_run", id).putBoolean("touch_pause", false)
                .putBoolean("completion_speech", false).putInt("companion_y", (180 * context.resources.displayMetrics.density).toInt()).commit())
            val fixtureSession = UUID.randomUUID().toString()
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.GestureEffectsFixtureActivity")
                .putExtra("mode", "events").putExtra("session", fixtureSession)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Native receiving fixture visible") { state()?.optString("session") == fixtureSession }
            workerRequested = true
            // The default start intent resumes tasks; this fixture must remain paused throughout.
            context.startForegroundService(Intent(context, DeviceWorkerService::class.java).setAction(DeviceWorkerService.PAUSE))
            await("Worker reconciles its paused run without a model call") { main {
                DeviceWorkerService.instance?.let { it.isPaused && (field(it, "lastRun") as? JSONObject)?.optString("id") == id } == true
            } }
            val service = requireNotNull(DeviceWorkerService.instance); worker = service
            val overlay = main { field(service, "overlay") as CompanionOverlay }
            await("Companion is attached and touchable") { overlayState(overlay).let { it.getBoolean("attached") && !it.getBoolean("not_touchable") && it.getDouble("alpha") == 1.0 } }
            val completion = field(service, "completion") as TaskCompletionDelivery
            val paused = engine.get(id).put("message", "当前页面需要手动处理")
                .put("pending_request", JSONObject().put("kind", "input").put("manual_only", true).put("reason", "interruption"))
            assertTrue(main { completion.revealPause(paused) { service.isPaused } })
            assertTrue(main { (field(completion, "view") as? View)?.isAttachedToWindow == true })
            SystemClock.sleep(350)
            ui.takeScreenshot()?.let { bitmap ->
                try { folder.resolve("before-handoff.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            session = host.handle(auth, "start", JSONObject().put("run_id", id)) { true }.body.getString("session_id")
            assertNull("Starting direct handoff must dismiss the touchable pause sheet", main { field(completion, "view") })
            report.put("pause_sheet_dismissed", true).put("overlay_before", overlayState(overlay))
            SystemClock.sleep(250)
            fun request() = JSONObject().put("session_id", session)
            fun frame(): JSONObject {
                repeat(3) { attempt ->
                    try { return host.handle(auth, "frame", request()) { true }.body }
                    catch (error: CompanionProtocolException) {
                        report.getJSONArray("frame_transitions").put(JSONObject().put("stage", stage)
                            .put("attempt", attempt + 1).put("code", error.code))
                        // Match PC's bounded frame-transition retries; input is never replayed.
                        if (error.code !in setOf("frame_changed", "capture_window_changed", "capture_screen_changed",
                                "capture_geometry_changed", "capture_companion_changed", "capture_overlay_changed",
                                "capture_rate_limited") || attempt == 2) throw error
                        SystemClock.sleep(750)
                    }
                }
                error("Unreachable")
            }
            for (kind in listOf("tap", "long_press", "swipe")) {
                stage = "${kind}_frame"
                val frame = frame()
                if (kind == "tap") folder.resolve("remote-frame.png").writeBytes(Base64.decode(frame.getString("image_base64"), Base64.DEFAULT))
                val area = surface()
                val obstruction = main { requireNotNull(service.companionBounds()) }
                assertTrue("The injection must hit the companion's actual touch region", area.contains(obstruction.centerX(), obstruction.centerY()))
                val command = request().put("frame_id", frame.getString("frame_id")).put("action_id", UUID.randomUUID().toString())
                    .put("kind", kind).put("x", obstruction.centerX() / (frame.getInt("display_width") - 1).toDouble())
                    .put("y", obstruction.centerY() / (frame.getInt("display_height") - 1).toDouble())
                if (kind == "long_press") command.put("duration_ms", 700)
                if (kind == "swipe") command.put("duration_ms", 650)
                    .put("end_x", area.centerX() / (frame.getInt("display_width") - 1).toDouble())
                    .put("end_y", obstruction.centerY() / (frame.getInt("display_height") - 1).toDouble())
                stage = "${kind}_action"
                val result = host.handle(auth, "action", command) { true }.body
                assertEquals("Remote $kind must dispatch successfully", "ok", result.getString("status"))
                await("Real $kind reaches underlying application") { state()?.let {
                    when (kind) { "tap" -> it.optInt("taps") == 1; "long_press" -> it.optInt("long") == 1; else -> it.optInt("moves") > 0 && it.optInt("up") == 3 }
                } == true }
                assertEquals(fixtureSession, state()!!.getString("session"))
                await("Companion input and opacity restored after $kind") { overlayState(overlay).let {
                    it.getBoolean("attached") && !it.getBoolean("not_touchable") && it.getDouble("alpha") == 1.0
                } }
                report.put(kind, JSONObject().put("native_events", state()).put("restored", overlayState(overlay))
                    .put("touch_pass", service.companionGestureTouchPassDiagnostic()))
                assertTrue(service.isPaused); assertEquals("paused", engine.get(id).getString("status"))
            }
            assertEquals(3, state()!!.getInt("down")); assertEquals(3, state()!!.getInt("up"))
            assertEquals(0, state()!!.getInt("cancel")); assertEquals(0, engine.get(id).getInt("calls"))
            host.handle(auth, "close", request()) { true }; session = null
            assertEquals("Closing mirror does not resume AI", "paused", engine.get(id).getString("status"))
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("failure_stage", stage).put("error", error.javaClass.simpleName).put("message", error.message)
            if (error is CompanionProtocolException) report.put("protocol_code", error.code)
            throw error
        } finally {
            try {
                session?.let { host.handle(auth, "close", JSONObject().put("session_id", it)) { true } }
                if (workerRequested) context.stopService(Intent(context, DeviceWorkerService::class.java))
                await("Fixture worker stopped before restoring preferences") { DeviceWorkerService.instance == null }
                worker?.let { assertTrue((field(it, "executor") as java.util.concurrent.ExecutorService).awaitTermination(8, TimeUnit.SECONDS)) }
                runId?.let { engine.control(it, "cancel", JSONObject()); engine.delete(it) }
                val edit = prefs.edit().clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(edit.commit()); report.put("preferences_restored", prefs.all == saved)
            } finally {
                TaskSubmissionGate.creating.set(false)
                folder.resolve("report.json").writeText(report.toString(2))
            }
        }
    }
}
