@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real pause overlay -> protected settings navigation; never dispatch a model or a resume. */
class LoginGuidanceDeviceTest {
    @Test fun firstLoginTakeoverOffersSettingsAndOpeningItRetainsThePausedTask() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertTrue(context.packageName.startsWith("dev.doppel.loginqa"))
        assertNull(DeviceWorkerService.instance)
        assertTrue(Settings.canDrawOverlays(context))
        val prefs = context.getSharedPreferences("doppel", 0)
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        val oldPause = prefs.getString("local_pause_detail", null)
        val id = UUID.randomUUID().toString()
        val run = JSONObject().put("id", id).put("status", "paused").put("message", "请补全该应用的登录资料")
            .put("updated_at", System.currentTimeMillis()).put("pending_request", JSONObject().put("id", "login-fixture")
                .put("kind", "input").put("manual_only", true).put("reason", "login").put("package_name", "dev.doppel.testapp"))
        check(prefs.edit().putString("active_run", id).commit())
        PauseDetails.remember(context, run)
        val savedPause = requireNotNull(PauseDetails.cached(context, id)).toString()
        var host: Activity? = null
        var settings: Activity? = null
        var delivery: TaskCompletionDelivery? = null
        val monitor = inst.addMonitor(LoginSettingsActivity::class.java.name, null, false)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            host = inst.startActivitySync(Intent(context, AppearanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            inst.runOnMainSync {
                delivery = TaskCompletionDelivery(context)
                assertTrue(requireNotNull(delivery).revealPause(run) { true })
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val buttons = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<Button>()
                val setup = buttons.single { it.text.toString() == "设置登录方式" }
                assertTrue(setup.performClick())
            }
            settings = inst.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("The real overlay button must open the unified login Activity", settings)
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertEquals("dev.doppel.testapp", settings!!.intent.getStringExtra("package_name"))
                assertTrue(settings!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue("Only a PIN gate is visible before login details", views(settings!!.window.decorView)
                    .filterIsInstance<android.widget.EditText>().all { it.inputType and android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0 })
                assertTrue("The interruption overlay must stop covering settings", WindowInspector.getGlobalWindowViews()
                    .flatMap(::views).none { it is PauseActionSheet })
            }
            assertEquals(id, prefs.getString("active_run", ""))
            assertEquals(savedPause, requireNotNull(PauseDetails.cached(context, id)).toString())
            assertNull("Entering settings cannot start execution", DeviceWorkerService.instance)
            inst.runOnMainSync { settings!!.finish() }
            inst.waitForIdleSync()
            assertEquals(savedPause, requireNotNull(PauseDetails.cached(context, id)).toString())
            assertNull("Returning from settings cannot start execution", DeviceWorkerService.instance)
            File(context.getExternalFilesDir(null), "login-guidance-check.json").writeText(JSONObject()
                .put("first_login_pause_has_button", true).put("button_opens_correct_app_settings", true)
                .put("pin_protection_retained", true).put("overlay_dismissed", true)
                .put("task_remains_paused_on_entry_and_return", true).put("model_requests", 0).toString(2))
        } finally {
            inst.runOnMainSync { settings?.finish(); host?.finish(); delivery?.close() }
            inst.removeMonitor(monitor)
            check(prefs.edit().remove("active_run").apply {
                if (oldPause == null) remove("local_pause_detail") else putString("local_pause_detail", oldPause)
            }.commit())
        }
    }
}
