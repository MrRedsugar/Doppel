package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualActionHistoryTest {
    private fun frame(id: String) = VisualFrame("capture-$id", id, "dev.notes", 1440, 3200, 648, 1440, 0, 1000, 46000, "hash-$id")
    @Test fun `history keeps only two actual actions with source image coordinate units`() {
        val history = VisualActionHistory()
        repeat(3) { i -> history.record(frame("$i"), "image-$i", JSONObject().put("kind", "visual_gesture")
            .put("gesture", JSONObject().put("kind", "tap").put("x", .5).put("y", .6).put("label", "button"))) }
        val entries = history.entries()
        assertEquals(2, entries.length())
        assertFalse(entries.toString().contains("image-0"))
        assertTrue(entries.toString().contains("image-2"))
        assertTrue(entries.toString().contains("324"))
        assertTrue(entries.toString().contains("864"))
    }
    @Test fun `input source images and values never enter visual action history`() {
        val history = VisualActionHistory()
        for (kind in listOf("type", "login_phone", "login_code", "observe", "wait"))
            history.record(frame(kind), "private-image", JSONObject().put("kind", kind).put("text", "private-input"))
        assertEquals(0, history.entries().length())
    }
    @Test fun `history copy and reset do not leak mutable stale frame state`() {
        val history = VisualActionHistory()
        history.record(frame("one"), "image", JSONObject().put("kind", "tap").put("target", "n0_1").put("visual_permit", "secret"))
        val snapshot = history.entries()
        snapshot.getJSONObject(0).put("label", "changed")
        assertFalse(history.entries().toString().contains("changed"))
        assertFalse(history.entries().toString().contains("secret"))
        history.clear()
        assertEquals(0, history.entries().length())
        assertEquals(1, snapshot.length())
    }

    @Test fun `semantic action history identifies its command and source image geometry`() {
        val history = VisualActionHistory()
        val source = frame("source")
        val observed = JSONObject().put("screen_id", "source").put("package_name", "dev.notes")
            .put("nodes", JSONArray().put(JSONObject().put("id", "n0_1").put("text", "详情")
                .put("bounds", JSONArray(listOf(320, 1600, 640, 2000))).put("password", false).put("editable", false)))
        history.record(source, "source-image", JSONObject().put("id", "accepted-command-42").put("kind", "tap").put("target", "n0_1"), observed)
        val entry = history.entries().getJSONObject(0)
        val action = entry.getJSONObject("action")
        assertEquals("capture-source", entry.getString("capture_id"))
        assertEquals("accepted-command-42", action.getString("command_id"))
        assertEquals("accepted", action.getString("action_state"))
        assertEquals("image_pixels", action.getString("coordinate_space"))
        assertEquals(216, action.getInt("x"))
        assertEquals(810, action.getInt("y"))
    }

    @Test fun `pixel 183 survives normalized device gesture round trip exactly`() {
        val history = VisualActionHistory()
        val source = frame("roundtrip")
        val proposal = JSONObject().put("capture_id", source.captureId).put("x", 183).put("y", 973)
            .put("duration_ms", 100).put("label", "详情").put("screen_context", "列表").put("safety", "safe")
        val normalized = DirectGroundingPixels.parseProposal("propose_tap", proposal, source)
        history.record(source, "source-image", JSONObject().put("id", "roundtrip-command").put("kind", "visual_gesture")
            .put("gesture", normalized.json()))
        val action = history.entries().getJSONObject(0).getJSONObject("action")
        assertEquals(183, action.getInt("x"))
        assertEquals(973, action.getInt("y"))
        assertEquals("roundtrip-command", action.getString("command_id"))
    }
}
