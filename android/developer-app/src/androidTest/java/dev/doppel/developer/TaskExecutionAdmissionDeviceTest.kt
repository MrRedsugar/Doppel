@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutomaticUnlockSession
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.TaskControl
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real accessibility entry, denied before reading the screen or submitting any system action. */
class TaskExecutionAdmissionDeviceTest {
    @Test fun staleGenerationAndRevokedRunCannotEnterTheExecutor() {
        assertTrue("Do not interrupt a user task", DeviceWorkerService.instance?.isPaused != false)
        assertFalse("Do not interrupt an automatic unlock", AutomaticUnlockSession.active)
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        val runId = "admission-${UUID.randomUUID()}"
        fun command() = JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", runId)
            .put("kind", "launch").put("package_name", "") // Invalid target even if admission regresses.
        val stale = TaskControl.currentGeneration()
        TaskControl.invalidate()
        assertEquals("cancelled", service.execute(command(), stale).getString("status"))
        val ticket = TaskControl.currentGeneration()
        val permit = TaskControl.installExecutionPermit(runId) { true }
        val queued = TaskControl.captureExecutionPermit(runId, ticket)
        permit.close()
        assertEquals("cancelled", service.execute(command(), ticket).getString("status"))
        val resumed = TaskControl.installExecutionPermit(runId) { true }
        try {
            assertEquals("cancelled", service.execute(command(), ticket, queued).getString("status"))
            assertTrue(TaskControl.captureExecutionPermit(runId, ticket)())
            assertTrue(TaskControl.captureExecutionPermit("unrelated-local-run", ticket)())
        } finally { resumed.close() }
    }
}
