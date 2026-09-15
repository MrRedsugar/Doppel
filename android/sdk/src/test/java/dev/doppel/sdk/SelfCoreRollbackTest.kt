package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class SelfCoreRollbackTest {
    private fun apply(values: Map<String, Any?>, hasKey: Boolean = true): Map<String, Any?> {
        val result = values.toMutableMap()
        SelfCoreRollback.changes(values, developerBuild = true, hasKey = hasKey).forEach { (key, value) ->
            if (value == null) result.remove(key) else result[key] = value
        }
        return result
    }

    @Test fun restoresLocalConnectionWithoutKeepingRemoteWorkQueued() {
        val before = mapOf<String, Any?>(
            "artemis_mode" to true, "direct_mode" to false, "device_id" to "artemis-this-phone",
            "active_run" to "remote-run", "conversation_tail" to "remote-tail",
            "conversation_scope" to "remote-scope", "conversation_epoch" to "remote-epoch",
            "voice_pending_worker_run" to "remote-run", "voice_pending_worker_generation" to 4L,
            "last_result" to "remote result"
        )
        val after = apply(before)
        assertEquals(false, after["artemis_mode"])
        assertEquals(true, after["direct_mode"])
        assertEquals("direct-this-phone", after["device_id"])
        for (key in listOf("active_run", "conversation_tail", "conversation_scope", "conversation_epoch",
            "voice_pending_worker_run", "voice_pending_worker_generation", "last_result")) assertFalse(key, after.containsKey(key))
        assertEquals("remote-run", after["retired_artemis_active_run"])
        assertEquals("remote-tail", after["retired_artemis_conversation_tail"])
    }

    @Test fun retainsUserSettingsAndCredentialReferences() {
        val before = mapOf<String, Any?>(
            "artemis_mode" to true, "permission_mode" to "assist", "payment_delegation" to false,
            "theme" to "dark", "draft_goal" to "未提交的草稿", "server_device_id" to "gateway-phone",
            "screenshot_retention_days" to 7, "learning_enabled" to true, "artemis_url" to "http://127.0.0.1:8787"
        )
        val after = apply(before)
        for ((key, value) in before.filterKeys { it != "artemis_mode" }) assertEquals(key, value, after[key])
    }

    @Test fun neverEnablesDirectModeWithoutSavedCredentials() {
        val after = apply(mapOf("artemis_mode" to true, "device_id" to "artemis-this-phone", "server_device_id" to "gateway-phone"), hasKey = false)
        assertEquals(false, after["direct_mode"])
        assertEquals(false, after["artemis_mode"])
        assertEquals("gateway-phone", after["device_id"])
    }

    @Test fun ignoresExistingSelfCoreAndNonDeveloperInstallations() {
        assertTrue(SelfCoreRollback.changes(mapOf("direct_mode" to true, "active_run" to "direct-run"), true, true).isEmpty())
        assertTrue(SelfCoreRollback.changes(mapOf("artemis_mode" to false), true, true).isEmpty())
        assertTrue(SelfCoreRollback.changes(mapOf("artemis_mode" to true), false, true).isEmpty())
    }

    @Test fun repeatedStartupDoesNotClearNewLocalConversation() {
        val after = apply(mapOf("artemis_mode" to true)).toMutableMap().apply {
            put("active_run", "direct-run-new")
            put("conversation_tail", "direct-run-new")
        }
        assertTrue(SelfCoreRollback.changes(after, true, true).isEmpty())
        assertEquals("direct-run-new", apply(after)["active_run"])
    }
}
