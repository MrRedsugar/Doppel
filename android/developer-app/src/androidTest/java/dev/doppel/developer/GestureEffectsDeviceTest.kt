@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Fixed commands through production engine/executor. Assertions use the other app's MotionEvents and visual state. */
class GestureEffectsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private lateinit var service: DoppelAccessibilityService
    private lateinit var folder: File
    private lateinit var report: JSONObject
    private val rows = JSONArray()
    private val fixture = "dev.doppel.testapp"
    private var session = ""
    private var captures = 0
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(message, condition())
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun stateOrNull(): JSONObject? {
        val all = nodes(ui.rootInActiveWindow)
        return try { all.firstOrNull { it.packageName?.toString() == fixture && it.contentDescription?.startsWith("gesture-result:") == true }
            ?.contentDescription?.toString()?.substringAfter("gesture-result:")?.let(::JSONObject) }
        finally { all.forEach { it.recycle() } }
    }
    private fun state() = requireNotNull(stateOrNull()).also { assertEquals(session, it.getString("session")) }
    private fun surface(): Rect {
        val all = nodes(ui.rootInActiveWindow)
        return try { Rect().also { out -> all.single { it.contentDescription?.toString() == "gesture-surface" }.getBoundsInScreen(out) } }
        finally { all.forEach { it.recycle() } }
    }
    private fun open(mode: String) {
        session = UUID.randomUUID().toString()
        context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity")
            .putExtra("mode", mode).putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("The new fixture session must be visible") { stateOrNull()?.optString("session") == session }
        SystemClock.sleep(300)
    }
    private fun command(kind: String) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("run_id", "gesture-effects-$session").put("kind", kind).put("split_agent", true).put("mode", "full")
    private fun capture(request: JSONObject = command("screenshot")): JSONObject {
        var shot = JSONObject()
        repeat(4) {
            if (shot.optString("status") != "ok") {
                SystemClock.sleep(350)
                shot = service.execute(request)
            }
        }
        assertEquals(shot.optString("message"), "ok", shot.optString("status"))
        assertEquals(fixture, shot.getJSONObject("observation").getString("package_name"))
        File(folder, "capture-${captures++}.png").writeBytes(Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.DEFAULT))
        return shot
    }
    private fun point(frame: JSONObject, x: Float, y: Float): JSONArray {
        val box = surface()
        return JSONArray(listOf((box.left + box.width() * x) * 1000.0 / frame.getInt("display_width"),
            (box.top + box.height() * y) * 1000.0 / frame.getInt("display_height")))
    }
    private fun gesture(kind: String, positions: List<Pair<Float, Float>>, duration: Int = 100): JSONObject {
        val shot = capture(); val frame = shot.getJSONObject("data").getJSONObject("visual_frame")
        val points = JSONArray(positions.map { point(frame, it.first, it.second) })
        val action = JSONObject().put("status", "located").put("action", kind).put("target", "一次确定性测试手势")
            .put("points", points).put("duration_ms", duration)
        if (kind == "double_tap") action.put("interval_ms", 100)
        val receipt = service.execute(command("split_action").put("source", frame).put("action", action))
        val row = JSONObject().put("action", action).put("receipt", receipt).put("observed", state())
        rows.put(row); save()
        assertEquals(receipt.toString(), "ok", receipt.getString("status"))
        return row
    }
    private fun save() { File(folder, "report.json").writeText(report.toString(2)) }
    private fun scenario(name: String, run: () -> Unit) {
        ui
        assertNull("Never replace an active Worker", DeviceWorkerService.instance)
        assertFalse("Never interrupt a user task", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse("Retain existing touch protection", service.guardVisible)
        folder = File(context.getExternalFilesDir(null), "gesture-effects/$name-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        report = JSONObject().put("test", name).put("passed", false).put("model_calls", 0)
            .put("scope", "real Android fixture effects, deterministic commands; not model acceptance").put("cases", rows)
        try { run(); report.put("passed", true) }
        catch (failure: Throwable) { report.put("failure", failure.toString().take(1200)); throw failure }
        finally { service.stopActionFeedback(); save() }
    }

    @Test fun tapDoubleTapAndLongPressReachActualViewWithCorrectTiming() = scenario("timing") {
        for ((kind, positions) in listOf("tap" to listOf(.4f to .4f), "double_tap" to listOf(.4f to .4f),
            "double_tap" to listOf(.3f to .4f, .7f to .4f), "long_press" to listOf(.4f to .4f))) {
            open("events")
            gesture(kind, positions, if (kind == "long_press") 700 else 100)
            val actual = state(); val count = if (kind == "double_tap") 2 else 1
            assertEquals(count, actual.getInt("down")); assertEquals(count, actual.getInt("up")); assertEquals(0, actual.getInt("cancel"))
            assertEquals(if (kind == "long_press") 1 else 0, actual.getInt("long"))
            assertEquals(if (kind == "long_press") 0 else count, actual.getInt("taps"))
            if (kind == "double_tap") assertEquals(if (positions.size == 1) 1 else 0, actual.getInt("double"))
            val strokes = actual.getJSONArray("strokes")
            repeat(strokes.length()) { index ->
                val duration = strokes.getJSONObject(index).getLong("duration_ms")
                assertTrue("Actual MotionEvent duration must match tap/long-press semantics: $duration", duration in
                    if (kind == "long_press") 500L..1600L else 40L..350L)
            }
            if (positions.size == 2) assertTrue(strokes.getJSONObject(1).getJSONArray("down").getDouble(0) >
                strokes.getJSONObject(0).getJSONArray("down").getDouble(0) + surface().width() * .2)
            capture()
        }
    }

    @Test fun eightTapsHaveDistinctActualDownUpDurationsWithoutAdditionalClicks() = scenario("tap-duration") {
        open("events")
        val durations = mutableListOf<Long>()
        repeat(8) { index ->
            gesture("tap", listOf(.45f to .45f), 100)
            val actual = state()
            assertEquals(index + 1, actual.getInt("down")); assertEquals(index + 1, actual.getInt("up"))
            assertEquals(index + 1, actual.getInt("taps")); assertEquals(0, actual.getInt("long"))
            assertEquals(0, actual.getInt("cancel")); assertEquals(0, actual.getInt("double"))
            val stroke = actual.getJSONArray("strokes").getJSONObject(index)
            val duration = stroke.getLong("duration_ms"); durations += duration
            assertTrue("Requested 100ms tap must remain short in actual MotionEvents: $duration", duration in 60L..160L)
            val box = surface()
            for (name in listOf("down", "up")) {
                val point = stroke.getJSONArray(name)
                assertEquals(box.width() * .45, point.getDouble(0), 2.0)
                assertEquals(box.height() * .45, point.getDouble(1), 2.0)
            }
        }
        report.put("actual_down_up_durations_ms", JSONArray(durations))
        assertTrue("Durations must vary in the receiving View, not only in executor metadata", durations.toSet().size > 1)
        capture()
    }

    @Test fun eightDirectionsMoveContentAndDragReachesItsTarget() = scenario("motion") {
        for ((dx, dy) in listOf(0f to -.12f, .12f to -.12f, .12f to 0f, .12f to .12f,
            0f to .12f, -.12f to .12f, -.12f to 0f, -.12f to -.12f)) {
            open("pan"); val box = surface()
            gesture("swipe", listOf(.5f to .5f, (.5f + dx) to (.5f + dy)), 800)
            val actual = state(); val offset = actual.getJSONArray("offset")
            assertEquals(1, actual.getInt("down")); assertEquals(1, actual.getInt("up")); assertEquals(0, actual.getInt("cancel"))
            assertTrue("Real MOVE events must reach the content", actual.getInt("moves") > 1)
            assertEquals(dx * box.width().toDouble(), offset.getDouble(0), 3.0)
            assertEquals(dy * box.height().toDouble(), offset.getDouble(1), 3.0)
            capture()
        }
        open("drag"); val box = surface()
        gesture("swipe", listOf(.25f to .7f, .75f to .25f), 900)
        val dragged = state().getJSONArray("token")
        assertEquals(box.width() * .75, dragged.getDouble(0), 3.0)
        assertEquals(box.height() * .25, dragged.getDouble(1), 3.0)
        capture()
        // Explicit intermediate points must not be replaced by endpoint-only interpolation.
        open("pan")
        gesture("swipe", listOf(.3f to .3f, .7f to .3f, .7f to .7f), 1200)
        val samples = state().getJSONArray("strokes").getJSONObject(0).getJSONArray("points")
        assertTrue("The actual path must pass the requested corner", (0 until samples.length()).any {
            val p = samples.getJSONArray(it)
            abs(p.getDouble(0) - surface().width() * .7) < 12 && abs(p.getDouble(1) - surface().height() * .3) < 12
        })
        capture()
    }

    @Test fun productionSequenceCapturesEachRealStrokeBeforeSendingBothImagesToPlanner() = scenario("sequence") {
        open("pan")
        val engine = SplitTaskEngine(null, {}, enhancementEnabled = { false })
        val runId = engine.create(JSONObject().put("goal", "测试两段实际滑动与段间截图")
            .put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
        val initial = capture(engine.poll().getJSONObject("command")); engine.result(initial)
        val frame = initial.getJSONObject("data").getJSONObject("visual_frame")
        val direction = JSONObject().put("gesture_semantics", "physical_gesture")
            .put("target_relative_direction", "unknown").put("intended_finger_direction", "right")
        val decision = JSONObject().put("kind", "swipe_sequence").put("target", "测试面板连续向右两次")
            .put("expected", "面板内容依次向右移动两次").put("screen_context", "测试手势面板")
            .put("swipe_extent", "small").put("scroll_goal", "inspect").put("boundary_reason", "")
            .put("gesture_contracts", JSONArray().put(direction).put(JSONObject(direction.toString())))
            .put("strokes", JSONArray().apply { repeat(2) { put(JSONObject().put("points", JSONArray()
                .put(point(frame, .4f, .5f)).put(point(frame, .5f, .5f))).put("duration_ms", 600)) } }).put("interval_ms", 100)
        val reply = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", JSONObject().put("decision", decision).put("state", JSONObject.NULL).toString()))))
        engine.accept(requireNotNull(engine.takeWork()), reply)
        val images = mutableListOf<String>(); val captureIds = mutableListOf<String>()
        try {
            repeat(2) { index ->
                var next = JSONObject()
                await("The next stroke must be dispatched after its minimum interval") {
                    next = engine.poll().optJSONObject("command") ?: JSONObject(); next.optString("kind") == "split_action"
                }
                assertEquals(index, next.getInt("stroke_index"))
                assertEquals(1, next.getJSONObject("action").getJSONArray("strokes").length())
                if (index == 1) assertEquals(captureIds[0], next.getJSONObject("source").getString("capture_id"))
                val receipt = service.execute(next)
                val effect = state(); rows.put(JSONObject().put("command", next).put("receipt", receipt).put("observed", effect)); save()
                assertEquals(receipt.toString(), "ok", receipt.getString("status"))
                assertEquals(index + 1, effect.getInt("up"))
                assertEquals(surface().width() * .1 * (index + 1), effect.getJSONArray("offset").getDouble(0), 4.0)
                engine.result(receipt)
                assertNull("No planner work between sequence strokes", engine.takeWork())
                val shotCommand = engine.poll().getJSONObject("command")
                assertEquals("screenshot", shotCommand.getString("kind")); assertEquals("swipe_step", shotCommand.getString("capture_purpose"))
                assertEquals(index, shotCommand.getInt("stroke_index"))
                val after = capture(shotCommand)
                captureIds += after.getJSONObject("data").getJSONObject("visual_frame").getString("capture_id")
                images += after.getJSONObject("data").getString("image_base64")
                engine.result(after)
            }
            assertNotEquals(captureIds[0], captureIds[1])
            val nextPlanner = requireNotNull(engine.takeWork()); assertFalse(nextPlanner.grounding)
            val messages = nextPlanner.payload.getJSONArray("messages")
            val payload = messages.toString()
            assertTrue(payload.contains("第 1 段滑动后的结果") && payload.contains("第 2 段滑动后的结果"))
            val deliveredImages = (0 until messages.length()).flatMap { index ->
                val content = messages.getJSONObject(index).optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { part ->
                    content.optJSONObject(part)?.optJSONObject("image_url")?.optString("url")
                }
            }
            assertEquals("Both actual images must arrive in stroke order", images.map { "data:image/png;base64,$it" }, deliveredImages)
            report.put("stroke_capture_ids", JSONArray(captureIds)).put("both_real_images_reached_planner_in_order", true)
        } finally { engine.control(runId, "cancel", JSONObject()) }
    }
}
