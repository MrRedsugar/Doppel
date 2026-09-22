package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSetupAdmissionTest {
    private fun archives(rows: JSONArray) = listOf(rows.toString(), JSONObject().put("version", 2).put("items", rows).toString())

    @Test fun terminalHistoryAllowsSetupForLegacyAndCurrentArchives() {
        assertTrue(PermissionSetupActivity.idleStoredTasks(null, ""))
        for (archive in archives(JSONArray())) assertTrue(PermissionSetupActivity.idleStoredTasks(archive, ""))
        val rows = JSONArray(listOf("completed", "failed", "cancelled").map { status -> JSONObject().put("id", status).put("status", status) })
        for (archive in archives(rows)) {
            assertTrue(PermissionSetupActivity.idleStoredTasks(archive, ""))
            assertTrue(PermissionSetupActivity.idleStoredTasks(archive, "cancelled"))
            assertFalse(PermissionSetupActivity.idleStoredTasks(archive, "missing"))
        }
    }

    @Test fun unfinishedOrUnreadableStateStillBlocksSetup() {
        for (status in listOf("queued", "running", "paused", "awaiting_input", "awaiting_approval", "")) {
            val rows = JSONArray().put(JSONObject().put("id", "old").put("status", "cancelled"))
                .put(JSONObject().put("id", "active").put("status", status))
            for (archive in archives(rows)) assertFalse(status, PermissionSetupActivity.idleStoredTasks(archive, "old"))
        }
        for (archive in listOf(null, "[]", "", "broken", "{}", "[null]", "{\"version\":3,\"items\":[]}", "{\"version\":2}")) {
            assertFalse(PermissionSetupActivity.idleStoredTasks(archive, "missing"))
        }
        for (archive in listOf("", "broken", "{}", "[null]", "{\"version\":3,\"items\":[]}", "{\"version\":2}")) {
            assertFalse(PermissionSetupActivity.idleStoredTasks(archive, ""))
        }
    }
}
