package dev.doppel.sdk

import dev.doppel.sdk.companion.CompanionProtocolException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanHandoffActionTest {
    private fun action(kind: String) = JSONObject().put("session_id", "session").put("frame_id", "frame")
        .put("action_id", "action").put("kind", kind)
    private fun rejected(value: JSONObject) {
        try { LanHandoffAction.parse(value); fail("Invalid human command was accepted") }
        catch (expected: CompanionProtocolException) { assertEquals(422, expected.statusCode) }
    }
    @Test fun explicitActionsAreBoundedAndCannotSmuggleModelOrSystemCommands() {
        for (kind in listOf("back", "home", "recents")) assertEquals("action", LanHandoffAction.parse(action(kind)).id)
        val tap = action("tap").put("x", 0).put("y", 1)
        assertEquals("action", LanHandoffAction.parse(tap).id)
        rejected(JSONObject(tap.toString()).put("x", -0.01))
        rejected(JSONObject(tap.toString()).put("y", 1.001))
        rejected(JSONObject(tap.toString()).put("x", "0.5"))
        rejected(JSONObject(tap.toString()).put("text", "hidden instruction"))
        rejected(JSONObject(tap.toString()).put("duration_ms", 60))
        val swipe = action("swipe").put("x", .1).put("y", .9).put("end_x", .7).put("end_y", .2).put("duration_ms", 450)
        LanHandoffAction.parse(swipe)
        rejected(JSONObject(swipe.toString()).put("duration_ms", 3001))
        rejected(JSONObject(swipe.toString()).put("duration_ms", 450.5))
        LanHandoffAction.parse(action("long_press").put("x", .5).put("y", .5).put("duration_ms", 650))
        rejected(action("launch").put("package", "com.example"))
        rejected(action("shell").put("command", "anything"))
        rejected(action("type").put("text", "a".repeat(4001)))
    }
    @Test fun retryDigestIsOrderIndependentButDistinguishesTextAndCoordinates() {
        val first = action("type").put("text", "fixture-only-password")
        val reordered = JSONObject().apply { first.keys().asSequence().toList().reversed().forEach { put(it, first.get(it)) } }
        val digest = LanHandoffAction.parse(first).canonical
        assertEquals(digest, LanHandoffAction.parse(reordered).canonical)
        assertFalse(digest.contains("fixture-only-password"))
        assertEquals(64, digest.length)
        assertNotEquals(digest, LanHandoffAction.parse(first.put("text", "changed")).canonical)
    }
}
