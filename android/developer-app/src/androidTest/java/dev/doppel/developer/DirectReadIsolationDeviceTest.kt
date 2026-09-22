@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.SplitTaskEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual runtime routes, isolated storage, no provider/consent and therefore no network calls. */
class DirectReadIsolationDeviceTest {
    @Test fun browsingDoesNotStartPlannerButCommandPollingStillDoes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-read-isolation-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode).also { preferences.add(prefix + name) }
        }
        var executor: ExecutorService? = null
        try {
            assertTrue(context.getSharedPreferences("doppel", 0).edit().putBoolean("direct_mode", true).commit())
            assertFalse(FirstUseConsent.isAccepted(context))
            val runtime = DirectRuntime::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context)
            val engine = DirectRuntime::class.java.getDeclaredField("engine").apply { isAccessible = true }
                .get(runtime) as SplitTaskEngine
            executor = DirectRuntime::class.java.getDeclaredField("executor").apply { isAccessible = true }
                .get(runtime) as ExecutorService
            val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
                .put("goal", "只读路由隔离测试").put("mode", "full")).getString("id")
            val command = engine.poll().getJSONObject("command")
            engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "ok")
                .put("observation", JSONObject().put("package_name", "fixture.page").put("screen_id", "fixture-screen"))
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                    .put("display_width", 1000).put("display_height", 1000))))
            assertTrue(engine.readyForWork())
            for (path in listOf("/runs", "/runs/$id", "/runs/$id/events", "/runs/$id/conversation", "/usage", "/data-retention")) {
                runtime.request("GET", path, null)
                executor.submit { }.get(5, TimeUnit.SECONDS)
                assertEquals("Read $path must not start model work", 0, engine.get(id).getInt("calls"))
                assertTrue(engine.readyForWork())
            }
            runtime.request("GET", "/devices/${DirectRuntime.DEVICE_ID}/commands", null)
            executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(1, engine.get(id).getInt("calls"))
            assertEquals("paused", engine.get(id).getString("status"))
            assertTrue(engine.get(id).getString("message").contains(FirstUseConsent.REQUIRED_MESSAGE))
        } finally {
            executor?.shutdownNow()
            executor?.awaitTermination(5, TimeUnit.SECONDS)
            preferences.forEach { base.deleteSharedPreferences(it) }
            assertEquals(File(base.cacheDir.canonicalFile, prefix), root.canonicalFile)
            assertTrue(root.deleteRecursively())
        }
    }
}
