package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import dev.doppel.sdk.DeveloperConnectionActivity
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectCredentials
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.FirstUseConsent
import java.security.KeyStore
import java.util.UUID

/** No model traffic or device gestures: uses a synthetic key and restores the previous configuration. */
class DirectRuntimeFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test
    fun testDeveloperMarkerAndReleaseGate() {
        val context = instrumentation.targetContext
        assertTrue(DirectMode.isDeveloperBuild(context))
        val release = object : ContextWrapper(context) {
            override fun getApplicationInfo() = ApplicationInfo(context.applicationInfo).apply { flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv() }
        }
        assertFalse(DirectMode.isDeveloperBuild(release))
        val noMarker = object : ContextWrapper(context) { override fun getPackageName() = "android" }
        assertFalse(DirectMode.isDeveloperBuild(noMarker))
    }

    @Test
    fun testEncryptedCredentialsAndConnectionRoundTrip() {
        val context = instrumentation.targetContext
        val gateway = Gateway(context)
        assertNull("Settings QA requires an idle developer worker", DeviceWorkerService.instance)
        assertTrue("Existing task must be stopped before this isolated settings test", gateway.prefs.getString("active_run", "").isNullOrBlank())
        assertTrue("Existing submission must finish before settings QA", gateway.prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
        assertFalse("Existing direct tasks must be finished first", DirectRuntime.get(context).hasUnfinishedRun())
        val prefs = gateway.prefs
        val credentialPrefs = context.getSharedPreferences("doppel_direct_credentials", Context.MODE_PRIVATE)
        val consentPrefs = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val credentialsBefore = credentialPrefs.all.toMap()
        val consentBefore = consentPrefs.all.toMap()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "${context.packageName}.developer.mimo.v1"
        val existed = store.containsAlias(alias)
        val testRuns = mutableListOf<String>()
        try {
            assertTrue(FirstUseConsent.accept(context))
            check(prefs.edit().putBoolean("direct_mode", false).putString("device_id", "fixture-server-device").putString("token", "fixture-server-token").commit())
            val secret = "sk-synthetic-test-" + UUID.randomUUID()
            val credentials = DirectCredentials(context)
            credentials.save(secret)
            assertTrue(credentials.hasKey())
            assertFalse("Only ciphertext may be persisted", credentialPrefs.all.values.any { it.toString().contains(secret) })
            DirectMode.configure(context, true)
            assertTrue(gateway.isDirectMode()); assertTrue(gateway.isConnected())
            assertEquals(DirectRuntime.DEVICE_ID, prefs.getString("device_id", ""))
            assertEquals("fixture-server-token", prefs.getString("token", ""))
            var unsupported = false
            try { gateway.request("GET", "/documents") } catch (_: IllegalStateException) { unsupported = true }
            assertTrue("Server-only features must not fall back to the old server", unsupported)
            fun stoppedRun(): String {
                val id = gateway.request("POST", "/runs", JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "Synthetic deletion regression").put("mode", "assist")).getString("id")
                testRuns.add(id)
                gateway.request("POST", "/runs/$id/cancel", JSONObject())
                return id
            }
            // No observe result or worker is supplied, so these synthetic tasks cannot call a model.
            val first = stoppedRun(); val second = stoppedRun()
            check(prefs.edit().putString("active_run", second).commit())
            gateway.request("DELETE", "/runs/$first")
            assertEquals("Deleting another terminal task must retain the active reference", second, prefs.getString("active_run", ""))
            gateway.request("DELETE", "/runs/$second")
            assertTrue("Deleting the referenced terminal task must clear its pointer", prefs.getString("active_run", "").isNullOrBlank())
            DirectMode.configure(context, false)
            assertEquals("fixture-server-device", prefs.getString("device_id", ""))
            credentials.delete(); assertFalse(credentials.hasKey())
        } finally {
            if (gateway.isDirectMode()) testRuns.forEach { id ->
                runCatching { gateway.request("POST", "/runs/$id/cancel", JSONObject()); gateway.request("DELETE", "/runs/$id") }
            }
            restore(prefs, before); restore(credentialPrefs, credentialsBefore)
            restore(consentPrefs, consentBefore)
            if (!existed && store.containsAlias(alias)) store.deleteEntry(alias)
        }
    }

    @Test
    fun testCredentialScreenSecureAndHiddenFromScreenshotPipeline() {
        val context = instrumentation.targetContext
        assertNull("Secure-screen QA must not pause an existing worker", DeviceWorkerService.instance)
        assertFalse("Secure-screen QA requires no unfinished local task", DirectRuntime.get(context).hasUnfinishedRun())
        val activity = instrumentation.startActivitySync(Intent(context, DeveloperConnectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(DirectMode.settingsVisible)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
        assertFalse(DirectMode.settingsVisible)
    }

    private fun restore(prefs: android.content.SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }
}
