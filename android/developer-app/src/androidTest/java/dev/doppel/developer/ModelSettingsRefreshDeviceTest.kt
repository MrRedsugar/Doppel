@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** One real GET /models refresh per configured platform; no generation, task, key or provider edits. */
class ModelSettingsRefreshDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val providers by lazy { ModelProviders(context) }
    private val folder by lazy { File(context.getExternalFilesDir(null), "full-feature/model-settings").apply { mkdirs() } }
    private val screenshots = JSONArray()

    @Test fun allPlatformStatusesDisplayAndEnhancementSettingSurvivesReopenWithoutChangingCredentials() {
        assertTrue(FirstUseConsent.isAccepted(context) && !FirstUseConsent.needsGuide(context))
        assertTrue(DirectMode.isEnabled(context) && Gateway(context).isConnected())
        assertNull("Run when the worker is stopped", DeviceWorkerService.instance)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val runs = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val recorded = if (runs.exists()) JSONArray(runs.readText()) else JSONArray()
        repeat(recorded.length()) { assertTrue("Preserve unfinished tasks; this setting-edit test requires no unfinished task", recorded.getJSONObject(it).optString("status") in setOf("completed", "failed", "cancelled")) }
        val originalRouting = providers.routing()
        val originalProviders = providers.list()
        val originalFingerprints = fingerprints()
        val originalCiphertext = AtomicFile(File(context.noBackupFilesDir, "model-providers-v1.bin")).readFully()
        val originalPrefs = context.getSharedPreferences("doppel", 0).all.toMap()
        val runsHash = hash(runs)
        val states = JSONObject()
        var activity: Activity? = null
        var passed = false
        var logicalStatePreserved = false
        try {
            activity = launch()
            click(activity, "刷新所有平台状态")
            val current = activity
            await("Every platform must reach a final refresh state", (originalProviders.size * 45_000L + 10_000).coerceAtLeast(10000)) {
                ui { !busy(current) && texts(current).contains("平台状态已刷新（${originalProviders.size} 个）") }
            }
            val results = ui {
                @Suppress("UNCHECKED_CAST")
                (ModelSettingsActivity::class.java.getDeclaredField("providerStatus").apply { isAccessible = true }.get(current) as Map<String, String>).toMap()
            }
            assertEquals(originalProviders.map { it.id }.toSet(), results.keys)
            for (provider in originalProviders) {
                val value = results.getValue(provider.id)
                assertTrue("A platform must show success, a readable failure or missing authentication", value.startsWith("连接正常") || value.startsWith("连接失败") || value == "未配置认证")
                ui {
                    val row = all(current.window.decorView).filterIsInstance<TextView>().singleOrNull { it.text.toString().contains("${provider.baseUrl}\n状态：$value") }
                    assertNotNull("The platform status must be present in its actual UI row", row)
                    row!!.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                }
                states.put(provider.id, value)
            }
            assertTrue("A status refresh must not rewrite stored provider credentials or routing", originalCiphertext.contentEquals(AtomicFile(File(context.noBackupFilesDir, "model-providers-v1.bin")).readFully()))
            capture("01-platform-statuses", current)
            for (enabled in listOf(false, true)) {
                toggle(current, enabled)
                assertEquals(originalRouting.primary, providers.routing().primary)
                assertEquals(originalRouting.enhancement, providers.routing().enhancement)
                capture(if (enabled) "03-enhancement-enabled" else "02-enhancement-disabled", current)
            }
            finish(current)
            activity = launch()
            val reopened = activity
            await("Reopen must load saved enhancement and the same model selections") {
                ui { switch(reopened).isChecked && texts(reopened).contains(originalRouting.primary.model) }
            }
            assertEquals(originalProviders, providers.list())
            toggle(reopened, originalRouting.enhancementEnabled)
            assertEquals(originalRouting, providers.routing())
            assertTrue("Status checks and the enhancement switch must preserve every credential fingerprint", originalFingerprints == fingerprints())
            capture("04-restored-routing", reopened)
            passed = true
        } finally {
            // Finish first so no queued UI write can race restoration of the original encrypted file.
            finish(activity)
            logicalStatePreserved = providers.list() == originalProviders && fingerprints() == originalFingerprints &&
                providers.routing().primary == originalRouting.primary && providers.routing().enhancement == originalRouting.enhancement
            val file = AtomicFile(File(context.noBackupFilesDir, "model-providers-v1.bin"))
            val output = file.startWrite()
            try { output.write(originalCiphertext); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
            val restored = providers.routing() == originalRouting && providers.list() == originalProviders && fingerprints() == originalFingerprints &&
                originalPrefs == context.getSharedPreferences("doppel", 0).all && runsHash == hash(runs)
            File(folder, "report.json").writeText(JSONObject().put("passed", passed && logicalStatePreserved && restored)
                .put("original_state_preserved", restored).put("platform_statuses", states).put("model_generations_requested", 0)
                .put("refresh_rounds", 1).put("screenshots", screenshots).toString(2))
            assertTrue("Preserve provider definitions, credentials, original routing and user tasks", logicalStatePreserved && restored)
        }
    }

    private fun fingerprints(): Map<String, String> = providers.list().associate { provider ->
        provider.id to if (providers.hasCredentials(provider.id)) providers.requestTarget(provider.id, "").fingerprint else "unconfigured"
    }
    private fun launch(): Activity = inst.startActivitySync(Intent(context, ModelSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).also { page ->
        await("Model settings must be foreground") { ui { page.hasWindowFocus() && texts(page).contains("模型连接") } }
    }
    private fun finish(activity: Activity?) { if (activity != null) { ui { if (!activity.isFinishing) activity.finish() }; inst.waitForIdleSync() } }
    private fun busy(activity: Activity) = ModelSettingsActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(activity)
    private fun switch(activity: Activity) = all(activity.window.decorView).filterIsInstance<Switch>().single { it.text.toString() == "独立视觉增强" }
    private fun toggle(activity: Activity, expected: Boolean) {
        await("Model settings must finish its preceding operation") { ui { !busy(activity) } }
        if (ui { switch(activity).isChecked } != expected) click(activity, "独立视觉增强")
        await("Enhancement choice must be persisted and rendered") { providers.routing().enhancementEnabled == expected && ui { !busy(activity) && switch(activity).isChecked == expected } }
    }
    private fun click(activity: Activity, label: String) {
        ui {
            val view = all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label && it.isShown }
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            assertTrue("Control must be enabled: $label", view.isEnabled)
            // CompoundButton toggles before invoking its optional OnClickListener;
            // performClick can return false even when OnCheckedChangeListener ran.
            val handled = view.performClick()
            assertTrue("Control must handle the click: $label", view is Switch || handled)
        }
        inst.waitForIdleSync()
    }
    private fun texts(activity: Activity) = all(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun <T> ui(block: () -> T): T { var result: Result<T>? = null; inst.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
    private fun await(message: String, timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail(message); SystemClock.sleep(100) }
    }
    private fun capture(name: String, activity: Activity) {
        await("Capture only the foreground model page") { ui { activity.hasWindowFocus() } }
        inst.waitForIdleSync(); SystemClock.sleep(450)
        val bitmap = automation.takeScreenshot()
        if (bitmap != null) { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        screenshots.put(JSONObject().put("name", name).put("secure_window", true).put("screenshot_saved", bitmap != null))
    }
    private fun hash(file: File): List<Byte>? = if (file.exists()) MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() else null
}
