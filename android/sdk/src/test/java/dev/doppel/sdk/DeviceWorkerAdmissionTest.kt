package dev.doppel.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWorkerAdmissionTest {
    @Test fun pollingWorkerReleasesDeviceAfterOwnerIsCleared() {
        assertTrue(DeviceWorkerService.hasActiveExecution("run", paused = false, starting = false))
        // Completion clears active_run without pausing the queue-polling service.
        assertFalse(DeviceWorkerService.hasActiveExecution("", paused = false, starting = false))
        assertFalse(DeviceWorkerService.hasActiveExecution("", paused = true, starting = false))
    }

    @Test fun pausedOwnerAllowsSelectionButResumeAndPromotionBlockIt() {
        assertFalse(DeviceWorkerService.hasActiveExecution("run", paused = true, starting = false))
        assertTrue(DeviceWorkerService.hasActiveExecution("run", paused = false, starting = false))
        // Queue promotion reserves the device before active_run is published,
        // including when the previous task left the local worker paused.
        for (paused in listOf(false, true)) {
            assertTrue(DeviceWorkerService.hasActiveExecution("", paused = paused, starting = true))
            assertTrue(DeviceWorkerService.hasActiveExecution("next", paused = paused, starting = true))
        }
    }
}
