package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Three exact production split swipes, no model or task creation.
 * Host first confirms neither installed package has a worker or armed automatic tasks,
 * and Settings starts near the top with room for three further short upward swipes.
 * -e settings_swipe_live true; each method records four real Settings screenshots.
 */
class SettingsSwipeDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun bytes(name: String) = File(context.noBackupFilesDir, name).takeIf { it.isFile }?.readBytes()
    private fun unchanged(before: ByteArray?, after: ByteArray?) = before?.contentEquals(after) ?: (after == null)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(40) }
        assertTrue(message, condition())
    }
    private fun companionVisible(worker: DeviceWorkerService): Boolean {
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        var visible = false
        inst.runOnMainSync {
            val overlay = field(worker, "overlay") ?: return@runOnMainSync
            val view = field(overlay, "root") as View
            val params = view.layoutParams as? WindowManager.LayoutParams
            visible = view.isAttachedToWindow && view.visibility == View.VISIBLE && view.alpha > 0 && (params?.alpha ?: 0f) > 0
        }
        return visible
    }
    private fun windows(service: DoppelAccessibilityService) = JSONArray(service.windows.map { window ->
        val bounds = Rect().also(window::getBoundsInScreen)
        val root = window.root
        try {
            JSONObject().put("id", window.id).put("type", window.type).put("layer", window.layer)
                .put("focused", window.isFocused).put("active", window.isActive).put("root_available", root != null)
                .put("package", root?.packageName?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        } finally { @Suppress("DEPRECATION") root?.recycle() }
    })
    private fun anchors(observation: JSONObject): Map<String, List<Int>> {
        val nodes = observation.getJSONArray("nodes")
        val candidates = (0 until nodes.length()).mapNotNull { i ->
            val node = nodes.getJSONObject(i)
            val bounds = node.optJSONArray("bounds") ?: return@mapNotNull null
            if (bounds.length() != 4 || node.optBoolean("password") || node.optBoolean("editable")) return@mapNotNull null
            if (node.optString("text").isBlank() && node.optString("description").isBlank()) return@mapNotNull null
            val box = (0..3).map(bounds::getInt)
            if (box[0] >= box[2] || box[1] >= box[3] || box[3] <= 0 || box[1] >= observation.getInt("height")) return@mapNotNull null
            val key = digest(JSONArray(listOf(node.optString("resource_id"), node.optString("text"), node.optString("description"))).toString().toByteArray())
            key to box
        }
        // Only unambiguous stable labels; a recycled node path is not an identity.
        return candidates.groupBy { it.first }.filterValues { it.size == 1 }.mapValues { it.value.single().second }
    }

    @Test fun threeSmallSettingsSwipesWithoutWorker() = verify(withWorker = false)
    @Test fun threeSmallSettingsSwipesWithPausedWorkerAndGuard() = verify(withWorker = true)

    private fun verify(withWorker: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("settings_swipe_live") == "true")
        assertEquals("dev.doppel.developer", context.packageName)
        assertNull("Retain every existing worker", DeviceWorkerService.instance)
        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse("Use an unlocked emulator", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(FirstUseConsent.isAccepted(context))
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("artemis_mode", false))
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        val tasksBefore = bytes("direct-runs-v1.json")
        val tasks = tasksBefore?.let { JSONArray(String(it, Charsets.UTF_8)) } ?: JSONArray()
        assertTrue("Retain unfinished tasks", (0 until tasks.length()).all {
            tasks.getJSONObject(it).optString("status") in setOf("completed", "failed", "cancelled")
        })
        val schedulesBefore = bytes("schedules-v1.json")
        val schedules = schedulesBefore?.let { JSONObject(String(it, Charsets.UTF_8)).getJSONArray("items") } ?: JSONArray()
        assertFalse("Do not alter armed schedules", (0 until schedules.length()).any { schedules.getJSONObject(it).optBoolean("enabled") })
        assertFalse("Retain schedule dispatch history requiring recovery", (0 until schedules.length()).any { i ->
            val history = schedules.getJSONObject(i).getJSONArray("history")
            (0 until history.length()).any { history.getJSONObject(it).optString("status") in setOf("dispatching", "started") }
        })
        val triggerPrefs = context.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE)
        val triggersBefore = triggerPrefs.getString("rules", null)
        val triggers = JSONArray(triggersBefore ?: "[]")
        assertFalse("Do not alter armed triggers", (0 until triggers.length()).any { triggers.getJSONObject(it).optBoolean("enabled") })
        val enabledBefore = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldConnection = listOf("direct_mode", "device_id", "action_feedback").associateWith { prefs.all[it] }
        val label = "${if (withWorker) "paused-worker" else "no-worker"}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val folder = File(context.getExternalFilesDir(null), "settings-swipe/$label").apply { check(mkdirs()) }
        val receipts = JSONArray()
        val captures = JSONArray()
        val report = JSONObject().put("status", "running").put("model_requests", 0).put("submitted_tasks", 0)
            .put("scope", "scripted coordinates through production screenshot and split_action; not autonomous model acceptance")
            .put("host_precondition", "Settings begins near the top; preserve its data and leave room for three short swipes")
            .put("with_paused_worker", withWorker).put("enabled_services_before", enabledBefore)
            .put("captures", captures).put("receipts", receipts)
        var startedWorker = false
        var connectionChanged = false
        var failure: Throwable? = null
        fun save() = File(folder, "report.json").writeText(report.toString(2))
        fun command(kind: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", label)
            .put("kind", kind).put("split_agent", true).put("mode", "full")
        try {
            save()
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            val service = requireNotNull(DoppelAccessibilityService.instance)
            assertFalse("Retain an existing guard", service.guardVisible)
            if (withWorker) {
                assertTrue(Settings.canDrawOverlays(context))
                // Even paused workers poll cleanup when device_id exists. Keep this test entirely offline.
                check(prefs.edit().putBoolean("direct_mode", false).putString("device_id", "").commit())
                connectionChanged = true
                startedWorker = true
                context.startForegroundService(Intent(context, DeviceWorkerService::class.java).setAction(DeviceWorkerService.PAUSE))
                await("Paused worker companion must be visible") { DeviceWorkerService.instance?.let { it.isPaused && companionVisible(it) } == true }
                service.setTouchGuard(true)
                await("Production guard must be visible") { service.guardVisible }
            }
            val ownedWorker = DeviceWorkerService.instance
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Actual Settings must be foreground") {
                service.windows.any { window ->
                    val root = window.root
                    try { (window.isFocused || window.isActive) && root?.packageName?.toString() == "com.android.settings" }
                    finally { @Suppress("DEPRECATION") root?.recycle() }
                }
            }
            Thread.sleep(500)
            fun capture(index: Int): JSONObject {
                val shot = service.execute(command("screenshot"))
                val data = shot.optJSONObject("data") ?: JSONObject()
                val entry = JSONObject().put("index", index).put("status", shot.optString("status"))
                    .put("reason_code", data.optString("reason_code")).put("capture_backend", data.optString("capture_backend"))
                    .put("windows", windows(service)).put("guard_visible", service.guardVisible)
                    .put("companion_visible", ownedWorker?.let(::companionVisible) ?: false)
                captures.put(entry); save()
                assertEquals("Capture $index must succeed; see report", "ok", shot.optString("status"))
                assertEquals("Never save another application's screenshot", "com.android.settings", shot.getJSONObject("observation").getString("package_name"))
                val image = Base64.decode(data.getString("image_base64"), Base64.NO_WRAP)
                val name = "$index-settings.png"
                File(folder, name).writeBytes(image)
                entry.put("image", name).put("sha256", digest(image))
                    .put("display_width", data.getJSONObject("visual_frame").getInt("display_width"))
                    .put("display_height", data.getJSONObject("visual_frame").getInt("display_height"))
                save()
                return shot
            }
            var before = capture(0)
            repeat(3) { index ->
                assertTrue("Only the owned worker may remain", DeviceWorkerService.instance === ownedWorker)
                assertTrue(prefs.getString("active_run", "").isNullOrBlank())
                val action = JSONObject().put("status", "located").put("action", "swipe").put("target", "系统设置列表向上小幅滑动")
                    .put("points", JSONArray().put(JSONArray(listOf(500, 800))).put(JSONArray(listOf(500, 700)))).put("duration_ms", 800)
                val started = SystemClock.elapsedRealtime()
                val result = service.execute(command("split_action").put("source", before.getJSONObject("data").getJSONObject("visual_frame")).put("action", action))
                val receipt = JSONObject().put("index", index + 1).put("requested_action", action).put("status", result.optString("status"))
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                val data = result.optJSONObject("data") ?: JSONObject()
                for (key in listOf("reason_code", "action_state", "completed_strokes", "unconfirmed_strokes", "humanized_swipes",
                    "feedback_targets", "touch_handoff", "guard_handoff", "post_action_delay_ms", "backend"))
                    if (data.has(key)) receipt.put(key, data.get(key))
                receipts.put(receipt); save()
                // execute already includes the production post-action delay. Capture the same way the next A step would.
                val after = capture(index + 1)
                val old = anchors(before.getJSONObject("observation"))
                val next = anchors(after.getJSONObject("observation"))
                val movements = JSONArray(old.mapNotNull { (hash, box) -> next[hash]?.let { other ->
                    JSONObject().put("label_sha256", hash).put("before", JSONArray(box)).put("after", JSONArray(other))
                        .put("dx", other[0] - box[0]).put("dy", other[1] - box[1])
                } })
                val movedUp = (0 until movements.length()).count { movements.getJSONObject(it).getInt("dy") <= -1 }
                receipt.put("matched_anchors", movements).put("anchors_moved_up", movedUp)
                    .put("image_changed", captures.getJSONObject(index).getString("sha256") != captures.getJSONObject(index + 1).getString("sha256"))
                before = after; save()
            }
            // Collect all three attempts before judging; a callback or animated pixels alone do not prove scrolling.
            assertTrue("Every production gesture must complete", (0 until receipts.length()).all { receipts.getJSONObject(it).optString("status") == "ok" })
            assertTrue("Each Settings swipe must move at least one matched child landmark upward; see receipts", (0 until receipts.length()).all {
                receipts.getJSONObject(it).getInt("anchors_moved_up") > 0
            })
            report.put("status", "passed")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "failed").put("failure_class", error.javaClass.simpleName)
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(work: () -> Unit) { try { work() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            } }
            cleanup {
                if (startedWorker) {
                    DoppelAccessibilityService.instance?.setTouchGuard(false)
                    context.stopService(Intent(context, DeviceWorkerService::class.java))
                    await("Fixture worker must stop") { DeviceWorkerService.instance == null }
                    await("Fixture guard must detach") { DoppelAccessibilityService.instance?.guardVisible != true }
                }
            }
            cleanup {
                check(DeviceWorkerService.instance == null) { "Do not reconnect a surviving fixture worker" }
                if (connectionChanged) check(prefs.edit().apply { oldConnection.forEach { (key, value) -> when (value) {
                    null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
                } } }.commit())
            }
            cleanup {
                assertTrue(unchanged(tasksBefore, bytes("direct-runs-v1.json")))
                assertTrue(unchanged(schedulesBefore, bytes("schedules-v1.json")))
                assertEquals(triggersBefore, triggerPrefs.getString("rules", null))
                assertTrue(prefs.getString("active_run", "").isNullOrBlank())
                assertEquals(enabledBefore, Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty())
                report.put("tasks_and_rules_unchanged", true).put("enabled_services_unchanged", true)
            }
            cleanupFailure?.let {
                report.put("status", "failed").put("cleanup_failure_class", it.javaClass.simpleName)
                if (failure == null) failure = it else failure!!.addSuppressed(it)
            }
            save()
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nSettings swipe evidence: ${folder.absolutePath}/report.json\n") })
        }
        failure?.let { throw AssertionError("Settings swipe diagnostic failed; see report.json", it) }
    }
}
