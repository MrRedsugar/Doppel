@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DirectRunStateFile
import dev.doppel.sdk.SplitTaskEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DirectRunRecoveryDeviceTest {
    @Test fun committedBackupRestoresTasksInsteadOfReportingEmpty() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "qa-run-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val base = File(root, "direct-runs-v1.json")
        val backup = File(root, "direct-runs-v1.json.bak")
        val state = DirectRunStateFile(root)
        try {
            assertNull(state.read())
            val old = JSONArray().put(JSONObject().put("id", "recoverable-task").put("goal", "恢复任务")
                .put("status", "running").put("created_at", 1000)).toString()
            backup.writeText(old)
            assertEquals(old, state.read())
            assertTrue(base.exists()); assertFalse(backup.exists())
            val engine = SplitTaskEngine(state.read(), { state.write(it) })
            assertTrue(engine.hasUnfinished())
            assertEquals("paused", engine.get("recoverable-task").getString("status"))
            assertTrue(engine.poll().isNull("command"))
            backup.writeText(old)
            base.writeText("interrupted replacement")
            assertEquals(old, state.read())
            state.write("[]")
            assertEquals("[]", DirectRunStateFile(root).read())
            backup.writeText("x".repeat(2 * 1024 * 1024 + 1))
            try { state.read(); fail("Oversized recovered file must not be treated as empty") }
            catch (_: IllegalStateException) { }
            assertTrue(base.length() > 2 * 1024 * 1024)
        } finally {
            assertEquals(cache.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.deleteRecursively())
        }
    }
}
