package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectVisualValidationTest {
    private var stored = ""
    private fun screen() = JSONObject().put("screen_id", "screen-a").put("package_name", "dev.fixture")
        .put("width", 1920).put("height", 1080).put("nodes", JSONArray())
    private fun frame() = VisualFrame("capture-a", "screen-a", "dev.fixture", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-a")
    private fun candidate() = JSONObject().put("capture_id", "capture-a").put("x", 384).put("y", 324)
        .put("duration_ms", 80).put("label", "开始按钮").put("screen_context", "游戏开始界面").put("safety", "safe")
    private fun reply(tool: String, args: JSONObject) = response(tool, args.toString())
    private fun response(tool: String, args: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", tool).put("arguments", args)))))))
    private fun deliver(engine: DirectTaskEngine, data: JSONObject = JSONObject()) {
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", screen()).put("data", data))
    }
    private fun prepare(): Triple<DirectTaskEngine, String, DirectTaskEngine.Work> {
        val engine = DirectTaskEngine(null, { stored = it }, { 2000L })
        val id = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "进入游戏").put("mode", "full")).getString("id")
        deliver(engine, JSONObject().put("device_profile", JSONObject().put("visual_gestures", true)))
        engine.accept(engine.takeWork()!!, reply("visual_action", JSONObject().put("intent", "点击开始")))
        deliver(engine, JSONObject().put("image_base64", "aW1hZ2U=").put("visual_frame", frame().json()))
        return Triple(engine, id, engine.takeWork()!!)
    }

    @Test fun eachPreviouslyGenericGestureFailureGetsAFixedReasonWithoutChangingItsRejection() {
        val invalid = listOf(
            Triple("propose_tap", candidate().put("duration_ms", 500), "visual_tap_duration"),
            Triple("propose_long_press", candidate().put("duration_ms", 80), "visual_long_press_duration"),
            Triple("propose_swipe", candidate().put("end_x", 672).put("end_y", 324).put("duration_ms", 80), "visual_swipe_duration"),
            Triple("propose_tap", candidate().put("end_x", 768), "visual_unexpected_endpoint"),
            Triple("propose_tap", candidate().put("duration_ms", "private-duration"), "visual_integer"),
            Triple("propose_tap", candidate().put("x", "private-coordinate"), "visual_coordinate_type"),
            Triple("propose_tap", candidate().put("private-extra-field", "private-value"), "visual_unknown_field"),
            Triple("propose_tap", candidate().put("kind", "tap"), "visual_unknown_field"),
            Triple("propose_tap", candidate().put("kind", "swipe"), "visual_unknown_field"),
            Triple("propose_tap", candidate().put("end_y", JSONObject.NULL), "visual_unexpected_endpoint"),
            Triple("propose_long_press", candidate().put("duration_ms", 700).put("end_x", JSONObject.NULL), "visual_unexpected_endpoint"),
            Triple("propose_swipe", candidate().put("duration_ms", 500).put("end_x", 768), "visual_coordinate_type"),
            Triple("propose_swipe", candidate().put("duration_ms", 500).put("end_x", 384).put("end_y", 324), "visual_swipe_distance"),
            Triple("propose_gesture", candidate(), "visual_kind"),
            Triple("private-unknown-tool", candidate(), "visual_kind"))
        for ((tool, body, code) in invalid) {
            val (engine, id, work) = prepare()
            engine.accept(work, reply(tool, body))
            val run = engine.get(id); val diagnostic = run.getJSONObject("validation_diagnostic")
            assertEquals("paused", run.getString("status")); assertTrue(engine.poll().isNull("command"))
            val pixelBoundary = code in setOf("visual_unexpected_endpoint", "visual_coordinate_type", "visual_unknown_field", "visual_kind")
            assertEquals(code, diagnostic.getString("validation_code"))
            assertEquals(if (pixelBoundary) "DirectGroundingPixels.kt" else "VisualGesture.kt", diagnostic.getString("source_file"))
            assertTrue(diagnostic.getInt("source_line") > 0); assertFalse(run.getString("message").contains("Failed requirement"))
            assertFalse(stored.contains("private")); assertEquals(0, run.getInt("successful_mutations"))
        }
    }
    @Test fun malformedModelArgumentsExposeOnlyOurParserLocation() {
        val (engine, id, work) = prepare()
        engine.accept(work, response("propose_tap", "{broken private-model-response"))
        val run = engine.get(id); val diagnostic = run.getJSONObject("validation_diagnostic")
        assertEquals("JSONException", diagnostic.getString("error_class")); assertEquals("DirectTaskEngine.kt", diagnostic.getString("source_file"))
        assertTrue(diagnostic.getInt("source_line") > 0); assertFalse(stored.contains("private"))
        assertEquals("paused", run.getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun groundingShapeKeepsJsonTypesWithoutUnknownNamesOrPrivateValues() {
        val secret = "private-grounding-secret-".repeat(2048)
        val body = candidate().put("capture_id", secret).put("x", JSONObject.NULL).put("y", true)
            .put("label", secret).put("screen_context", JSONObject().put("private-nested-field", secret))
            .put("safety", JSONArray().put(secret)).put("private-api-key-field", secret)
        val (engine, id, work) = prepare()
        engine.accept(work, reply("propose_tap", body))
        val run = engine.get(id)
        val shape = run.getJSONObject("grounding_tool_diagnostic")
        assertEquals("propose_tap", shape.getString("tool_name"))
        assertEquals(1, shape.getInt("unknown_field_count"))
        val fields = shape.getJSONObject("field_types")
        assertEquals("string", fields.getString("capture_id")); assertEquals("string", fields.getString("label"))
        assertEquals("null", fields.getString("x")); assertEquals("boolean", fields.getString("y"))
        assertEquals("number", fields.getString("duration_ms")); assertEquals("object", fields.getString("screen_context"))
        assertEquals("array", fields.getString("safety")); assertEquals(7, fields.length())
        assertFalse(fields.has("end_x")); assertFalse(shape.has("expected_kind_matches"))
        assertTrue(shape.toString().length < 1024); assertFalse(shape.toString().contains("private"))
        assertFalse(stored.contains("private"))
        assertEquals("visual_unknown_field", run.getJSONObject("validation_diagnostic").getString("validation_code"))
        assertEquals("paused", run.getString("status")); assertTrue(engine.poll().isNull("command"))
        assertEquals(0, run.getInt("successful_mutations"))
    }
    @Test fun groundingKindComparisonIsBooleanAndNeverAcceptsTheRedundantField() {
        for ((tool, expected) in listOf("propose_tap" to "tap", "propose_long_press" to "long_press", "propose_swipe" to "swipe")) {
            for ((kind, matches) in listOf(expected to true, "private-kind" to false)) {
                val (engine, id, work) = prepare()
                engine.accept(work, reply(tool, candidate().put("kind", kind)))
                val run = engine.get(id)
                val shape = run.getJSONObject("grounding_tool_diagnostic")
                assertEquals(tool, shape.getString("tool_name")); assertEquals(0, shape.getInt("unknown_field_count"))
                assertEquals("string", shape.getJSONObject("field_types").getString("kind"))
                assertEquals(matches, shape.getBoolean("expected_kind_matches"))
                assertFalse(shape.toString().contains("private")); assertFalse(shape.has("kind"))
                assertEquals("visual_unknown_field", run.getJSONObject("validation_diagnostic").getString("validation_code"))
                assertEquals("paused", run.getString("status")); assertTrue(engine.poll().isNull("command"))
                assertEquals(0, run.getInt("successful_mutations"))
            }
        }
    }
    @Test fun groundingUnknownToolAndExtraFieldCountStayBounded() {
        val body = candidate().put("kind", JSONObject.NULL)
        repeat(300) { body.put("private-extra-$it", "private-value-$it") }
        val (engine, id, work) = prepare()
        engine.accept(work, reply("private-unknown-tool", body))
        val run = engine.get(id)
        val shape = run.getJSONObject("grounding_tool_diagnostic")
        assertEquals("unknown", shape.getString("tool_name")); assertEquals(128, shape.getInt("unknown_field_count"))
        assertEquals("null", shape.getJSONObject("field_types").getString("kind"))
        assertFalse(shape.has("expected_kind_matches")); assertTrue(shape.toString().length < 1024)
        assertFalse(stored.contains("private"))
        assertEquals("visual_kind", run.getJSONObject("validation_diagnostic").getString("validation_code"))
        assertEquals("paused", run.getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun groundingDiagnosticsPreserveValidDispatchAndCannotGroundBehavior() {
        val (engine, id, work) = prepare()
        engine.accept(work, reply("propose_tap", candidate()))
        val run = engine.get(id)
        val shape = run.getJSONObject("grounding_tool_diagnostic")
        assertEquals("propose_tap", shape.getString("tool_name")); assertEquals(0, shape.getInt("unknown_field_count"))
        assertEquals("number", shape.getJSONObject("field_types").getString("x"))
        assertFalse(shape.toString().contains("0.4")); assertFalse(shape.toString().contains("开始按钮"))
        assertEquals("running", run.getString("status"))
        val command = engine.poll().getJSONObject("command")
        assertEquals("visual_gesture", command.getString("kind"))
        assertEquals(.4, command.getJSONObject("gesture").getDouble("x"), .000001)
        val (uncertain, uncertainId, uncertainWork) = prepare()
        uncertain.accept(uncertainWork, reply("cannot_ground", JSONObject().put("reason", "目标暂不可见")))
        val blocked = uncertain.get(uncertainId)
        val blockedShape = blocked.getJSONObject("grounding_tool_diagnostic")
        assertEquals("cannot_ground", blockedShape.getString("tool_name"))
        assertEquals("string", blockedShape.getJSONObject("field_types").getString("reason"))
        assertFalse(blockedShape.toString().contains("目标暂不可见")); assertFalse(blockedShape.has("expected_kind_matches"))
        assertEquals("paused", blocked.getString("status")); assertTrue(uncertain.poll().isNull("command"))
    }
    @Test fun staleReasonSurvivesFreshObservationAndNeverReplaysTheOldVisualAction() {
        val (engine, id, work) = prepare()
        engine.accept(work, reply("propose_tap", candidate()))
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "stale")
            .put("message", "private-screen-title").put("data", JSONObject().put("reason_code", "target_pixels_changed")
                .put("visual_diagnostic", JSONObject().put("reason_code", "target_pixels_changed").put("stage", "verify")
                    .put("frame_matches", true).put("pixels_match", false).put("source_age_ms", 4500L).put("private", "private-value")
                    .put("pixel_check", JSONObject().put("matches", false).put("mode", "rejected").put("reason", "core_changed")
                        .put("core_mean_max", 42.0).put("pixels", "private-pixels")))))
        val diagnostic = engine.get(id).getJSONObject("last_stale_diagnostic")
        assertEquals("target_pixels_changed", diagnostic.getString("reason_code"))
        assertTrue(diagnostic.getJSONObject("visual").getBoolean("frame_matches")); assertFalse(diagnostic.getJSONObject("visual").getBoolean("pixels_match"))
        assertEquals(42.0, diagnostic.getJSONObject("visual").getJSONObject("pixel_check").getDouble("core_mean_max"), 0.0)
        assertEquals("observe", engine.poll().getJSONObject("command").getString("kind"))
        deliver(engine)
        assertEquals(diagnostic.toString(), engine.get(id).getJSONObject("last_stale_diagnostic").toString())
        assertEquals(0, engine.get(id).getInt("successful_mutations")); assertTrue(engine.poll().isNull("command"))
        assertTrue(engine.events(id).toString().contains("目标区域像素已变化")); assertFalse(stored.contains("private"))
    }
    @Test fun successfulVisualActionRetainsOnlyItsBoundedMetricsAcrossLaterObservations() {
        val (engine, id, work) = prepare()
        engine.accept(work, reply("propose_tap", candidate()))
        val data = JSONObject().put("image_base64", "bmV3LWltYWdl").put("visual_frame", frame().copy(captureId = "capture-after").json())
            .put("visual_diagnostic", JSONObject().put("reason_code", "visual_pixels_verified").put("stage", "verify")
                .put("frame_matches", true).put("pixels_match", true).put("source_age_ms", 4042L).put("verification_age_ms", 9L)
                .put("pixel_check", JSONObject().put("matches", true).put("mode", "target_anchor").put("reason", "stable")
                    .put("global_mean", 56.7).put("core_mean_max", .257).put("path_points", 1).put("pixels", "private-pixels")))
        deliver(engine, data)
        val diagnostic = engine.get(id).getJSONObject("last_visual_success_diagnostic")
        assertEquals("ok", diagnostic.getString("status")); assertEquals("visual_gesture", diagnostic.getString("kind"))
        assertEquals("target_anchor", diagnostic.getJSONObject("visual").getJSONObject("pixel_check").getString("mode"))
        assertEquals(1, engine.get(id).getInt("successful_mutations")); assertFalse(stored.contains("private"))
        engine.control(id, "pause", JSONObject()); engine.control(id, "resume", JSONObject()); deliver(engine)
        assertEquals(diagnostic.toString(), engine.get(id).getJSONObject("last_visual_success_diagnostic").toString())
    }
}
