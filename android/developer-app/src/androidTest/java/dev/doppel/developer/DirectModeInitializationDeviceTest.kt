@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ModelProvider
import dev.doppel.sdk.ModelProviders
import dev.doppel.sdk.ModelRouting
import dev.doppel.sdk.ModelSelection
import dev.doppel.sdk.ModelVision
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService

/** Run this class alone in a fresh instrumentation process. No network, models or device actions. */
class DirectModeInitializationDeviceTest {
    @Test fun firstVerifiedConfigurationEnablesLocalConnectionWithoutOverridingAnExistingChoice() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val runtimeField = DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        assertNull("Run this test alone before any real DirectRuntime is initialized", runtimeField.get(null))
        val prefix = "direct-mode-initialization-${UUID.randomUUID()}-"
        val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                preferenceNames += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val realPreferenceNames = listOf("doppel", "doppel_consent", "doppel_skill_switches", "doppel_gui_grounding",
            "doppel_auto_triggers", "doppel_automatic_unlock_state", "doppel_schedule_wakeup", "doppel_direct_credentials")
        val preferencesBefore = realPreferenceNames.associateWith { base.getSharedPreferences(it, 0).all.toMap() }
        val filesBefore = fingerprint(base.noBackupFilesDir)
        val alias = "${base.packageName}.model.providers.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyExisted = keyStore.containsAlias(alias)
        try {
            assertTrue("This regression requires the developer build", DirectMode.available(context))
            assertTrue("Accept only the isolated test consent", FirstUseConsent.accept(context))
            val prefs = context.getSharedPreferences("doppel", 0)
            val providers = ModelProviders(context)
            val selected = ModelSelection("qwen-cn", "qwen3.8-flash")
            providers.saveProvider(ModelProvider.qwen(), "isolated-fixture-key", emptyMap())
            providers.saveRouting(ModelRouting(selected, false, selected))

            // A saved key/model is insufficient until the existing capability check has passed.
            assertFalse(providers.isReady())
            DirectMode.enableConfiguredDefault(context)
            val unverified = assertThrows(IllegalStateException::class.java) { Gateway(context).prepareUserConnection() }
            assertEquals("请在模型设置中验证并启用本机连接，或完成网关连接", unverified.message)
            assertFalse(prefs.contains("direct_mode"))
            assertFalse(prefs.contains("device_id"))
            assertNull(runtimeField.get(null))

            val target = providers.requestTarget("primary")
            providers.recordVision(selected.providerId, selected.model, ModelVision.VERIFIED, target.fingerprint)
            assertTrue(providers.isReady())

            for (field in listOf("base_url", "token", "device_id", "active_run")) {
                assertTrue(prefs.edit().clear().putString(field, "configured-$field").commit())
                val before = prefs.all.toMap()
                DirectMode.enableConfiguredDefault(context)
                assertTrue("An existing $field must prevent implicit connection switching", before == prefs.all)
                assertNull("Existing remote state must not initialize the local runtime", runtimeField.get(null))
            }

            assertTrue(prefs.edit().clear().putBoolean("direct_mode", false).commit())
            DirectMode.enableConfiguredDefault(context)
            assertFalse(DirectMode.isEnabled(context))
            assertFalse(prefs.contains("device_id"))
            assertNull(runtimeField.get(null))

            // Even an idempotent, explicit gateway selection must remain explicit on the next entry.
            assertTrue(prefs.edit().clear().commit())
            DirectMode.configure(context, false)
            assertTrue("An explicit false choice must be persisted", prefs.contains("direct_mode"))
            DirectMode.enableConfiguredDefault(context)
            assertFalse(DirectMode.isEnabled(context))
            assertFalse(prefs.contains("device_id"))

            assertTrue(prefs.edit().clear().commit())
            val device = Gateway(context).prepareUserConnection()
            val fixtureRuntime = runtimeField.get(null) as? DirectRuntime
            assertNotNull("Local initialization must use the isolated runtime", fixtureRuntime)
            assertTrue(DirectMode.isEnabled(context))
            assertEquals("The shared text/voice entry must return the newly initialized device", DirectRuntime.DEVICE_ID, device)
            assertEquals(DirectRuntime.DEVICE_ID, prefs.getString("device_id", ""))
            assertTrue("The initialized connection must be usable by task entry", Gateway(context).isConnected())
            assertFalse(fixtureRuntime!!.hasUnfinishedRun())
            val enabled = prefs.all.toMap()
            DirectMode.enableConfiguredDefault(context)
            assertTrue("Repeating initialization must not rewrite connection state", enabled == prefs.all)
            assertSame(fixtureRuntime, runtimeField.get(null))
        } finally {
            // No work is submitted. Release only this test's singleton so later component tests cannot inherit it.
            val current = runtimeField.get(null) as? DirectRuntime
            if (current != null) {
                val runtimeContext = DirectRuntime::class.java.getDeclaredField("context").apply { isAccessible = true }.get(current)
                assertSame("Never reset another context's runtime", context, runtimeContext)
                (DirectRuntime::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(current) as ExecutorService).shutdownNow()
                runtimeField.set(null, null)
            }
            for (name in preferenceNames) base.deleteSharedPreferences(name)
            assertTrue("Remove only the isolated fixture directory", directory.deleteRecursively())
            if (!keyExisted && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            assertTrue("User files must remain byte-for-byte unchanged", filesBefore == fingerprint(base.noBackupFilesDir))
            assertTrue("User preferences must remain unchanged", realPreferenceNames.all {
                preferencesBefore[it] == base.getSharedPreferences(it, 0).all
            })
            assertEquals("Preserve the existing credential key", keyExisted, keyStore.containsAlias(alias))
        }
    }

    private fun fingerprint(root: File): Map<String, String> = root.walkTopDown().filter(File::isFile).associate { file ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        file.relativeTo(root).invariantSeparatorsPath to digest.digest().joinToString("") { "%02x".format(it) }
    }
}
