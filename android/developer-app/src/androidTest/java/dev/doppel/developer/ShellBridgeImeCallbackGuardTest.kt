package dev.doppel.developer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShellBridgeImeCallbackGuardTest {
    private fun capability() = JSONObject().put("input_available", true).put("package_name", "example.editor").put("editor_id", "session-a")

    @Test fun repeatedChecksAreLocalAndExpireAtTheOriginalDeadline() {
        var now=1000L; var reads=0
        val guard=ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{now},{true},{reads++;capability()})
        assertTrue(guard.isCurrent());now=2999;assertTrue(guard.isCurrent());assertEquals(2,reads)
        now=3000;assertFalse(guard.isCurrent());assertEquals(2,reads)
    }
    @Test fun closingTheCallRevokesEvenAnOtherwiseValidLateCallback() {
        var reads=0
        val guard=ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{1001},{true},{reads++;capability()})
        assertTrue(guard.isCurrent());guard.close();assertFalse(guard.isCurrent());assertEquals(1,reads)
    }
    @Test fun hostChangeRejectsWithoutReadingSession() {
        var current=true;var reads=0
        val guard=ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{1001},{current},{reads++;capability()})
        assertTrue(guard.isCurrent());current=false;assertFalse(guard.isCurrent());assertEquals(1,reads)
    }
    @Test fun changedSessionPackageOrAvailabilityIsNeverAccepted() {
        for (state in listOf(capability().put("editor_id","session-b"), capability().put("package_name","another.editor"),
            capability().put("input_available",false),JSONObject())) {
            assertFalse(ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{1001},{true},{state}).isCurrent())
        }
    }
    @Test fun cancellationDuringSnapshotOrClockRollbackRejects() {
        lateinit var guard:ShellBridgeImeCallbackGuard
        guard=ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{1001},{true},{guard.close();capability()})
        assertFalse(guard.isCurrent())
        assertFalse(ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{999},{true},{capability()}).isCurrent())
    }
    @Test fun snapshotFailureDoesNotEscapeAndCannotAuthorizeInput() {
        val guard=ShellBridgeImeCallbackGuard("example.editor","session-a",1000,{1001},{true},{error("snapshot unavailable")})
        assertFalse(guard.isCurrent())
    }
}
