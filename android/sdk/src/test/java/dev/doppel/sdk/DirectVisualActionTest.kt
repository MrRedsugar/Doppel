package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectVisualActionTest {
    private var stored = ""
    private fun engine() = DirectTaskEngine(null, { stored = it }, { 2000L })
    private fun screen(label: String = "") = JSONObject().put("screen_id", "screen-a").put("package_name", "dev.fixture")
        .put("width", 1920).put("height", 1080).put("nodes", JSONArray().put(JSONObject().put("id", "n0").put("text", label)))
    private fun frame() = VisualFrame("capture-a", "screen-a", "dev.fixture", 1920, 1080, 960, 540, 1, 1000, 46000, "hash-a")
    private fun candidate() = JSONObject().put("capture_id", "capture-a").put("x", 384).put("y", 324)
        .put("duration_ms", 80).put("label", "开始行动").put("screen_context", "关卡准备界面").put("safety", "safe")
    private fun reply(tool: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", tool).put("arguments", args.toString())))))))
    private fun deliver(engine: DirectTaskEngine, observation: JSONObject = screen(), data: JSONObject = JSONObject()): JSONObject {
        val command = engine.poll().getJSONObject("command")
        assertTrue(engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
            .put("status", "ok").put("observation", observation).put("data", data)).getBoolean("accepted"))
        return command
    }
    private fun create(engine: DirectTaskEngine, mode: String = "full", capable: Boolean = true, label: String = ""): String {
        val id = engine.create(JSONObject().put("goal", "操作当前画布").put("device_id", DirectRuntime.DEVICE_ID).put("mode", mode)).getString("id")
        deliver(engine, screen(label), JSONObject().put("device_profile", JSONObject().put("visual_gestures", capable)))
        return id
    }
    private fun grounding(engine: DirectTaskEngine, label: String = ""): DirectTaskEngine.Work {
        engine.accept(engine.takeWork()!!, reply("visual_action", JSONObject().put("intent", "点击当前画面中的开始行动按钮")))
        assertTrue(engine.poll().getJSONObject("command").getBoolean("include_screenshot"))
        deliver(engine, screen(label), JSONObject().put("image_base64", "aW1hZ2U=").put("mime_type", "image/png").put("visual_frame", frame().json()))
        return engine.takeWork()!!
    }
    @Test fun capabilityIsDeviceReportedAndCannotBeInventedByPlanner() {
        val engine = engine(); val id = create(engine, capable = false)
        val work = engine.takeWork()!!
        assertFalse(work.payload.getJSONArray("tools").toString().contains("visual_action"))
        engine.accept(work, reply("visual_action", JSONObject().put("intent", "点击")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun groundingToolsSeparateGestureShapesAndTheirDurationBounds() {
        val engine = engine(); create(engine)
        val tools = grounding(engine).payload.getJSONArray("tools")
        val functions = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .associateBy { it.getString("name") }
        assertEquals(setOf("propose_tap", "propose_long_press", "propose_swipe", "cannot_ground"), functions.keys)
        assertTrue("All grounding functions request the provider's documented strict argument schema", functions.values.all { it.optBoolean("strict") })
        for ((name, bounds) in mapOf("propose_tap" to (40 to 200), "propose_long_press" to (500 to 2000), "propose_swipe" to (150 to 2000))) {
            val schema = functions.getValue(name).getJSONObject("parameters")
            val properties = schema.getJSONObject("properties")
            val requiredJson = schema.getJSONArray("required")
            val required = (0 until requiredJson.length()).map { requiredJson.getString(it) }.toSet()
            val common = setOf("capture_id", "x", "y", "duration_ms", "label", "screen_context", "safety")
            val expected = if (name == "propose_swipe") common + setOf("end_x", "end_y") else common
            assertEquals(expected, properties.keys().asSequence().toSet()); assertEquals(expected, required)
            assertFalse(properties.has("kind")); assertFalse(schema.getBoolean("additionalProperties"))
            assertEquals(bounds.first, properties.getJSONObject("duration_ms").getInt("minimum"))
            assertEquals(bounds.second, properties.getJSONObject("duration_ms").getInt("maximum"))
            assertEquals("capture-a", properties.getJSONObject("capture_id").getJSONArray("enum").getString(0))
            for ((key, maximum) in mapOf("x" to 959, "y" to 539) +
                if (name == "propose_swipe") mapOf("end_x" to 959, "end_y" to 539) else emptyMap()) {
                val coordinate = properties.getJSONObject(key)
                assertEquals("Image coordinates must be integer pixels", "integer", coordinate.getString("type"))
                assertEquals(0, coordinate.getInt("minimum")); assertEquals(maximum, coordinate.getInt("maximum"))
            }
        }
    }
    @Test fun imagePixelProposalBecomesTheExistingNormalizedDeviceGesture() {
        val engine = engine(); val id = create(engine)
        val body = candidate().put("x", 384).put("y", 324)
        engine.accept(grounding(engine), reply("propose_tap", body))
        assertEquals("Valid image pixels must reach the existing host authorization path", "running", engine.get(id).getString("status"))
        val command = engine.poll().getJSONObject("command")
        val gesture = VisualGesture.parse(command.getJSONObject("gesture"))
        assertEquals(.4, gesture.x, 0.000001); assertEquals(.6, gesture.y, 0.000001)
        assertEquals(768f, gesture.start(frame()).x, 0.001f); assertEquals(648f, gesture.start(frame()).y, 0.001f)
        assertTrue(VisualGesturePermits.consume(command.getString("visual_permit"), id, gesture, 2001))
        assertEquals(384, body.getInt("x")); assertEquals(324, body.getInt("y")); assertFalse(body.has("kind"))
    }
    @Test fun eachTypedCandidateBecomesTheSameHostOwnedDeviceCommand() {
        for ((tool, kind, duration) in listOf(Triple("propose_tap", "tap", 80), Triple("propose_long_press", "long_press", 700), Triple("propose_swipe", "swipe", 500))) {
            val engine = engine(); val id = create(engine)
            val body = candidate().put("duration_ms", duration)
            if (kind == "swipe") body.put("end_x", 768).put("end_y", 378)
            val before = body.toString()
            engine.accept(grounding(engine), reply(tool, body))
            val command = engine.poll().getJSONObject("command")
            assertEquals("visual_gesture", command.getString("kind"))
            val gesture = VisualGesture.parse(command.getJSONObject("gesture"))
            assertEquals(kind, gesture.kind); assertEquals(duration.toLong(), gesture.durationMs)
            assertEquals(.4, gesture.x, .000001); assertEquals(.6, gesture.y, .000001)
            if (kind == "swipe") { assertEquals(.8, gesture.endX!!, .000001); assertEquals(.7, gesture.endY!!, .000001) }
            assertEquals(kind == "swipe", gesture.endX != null)
            assertEquals(before, body.toString()); assertFalse(body.has("kind"))
            assertTrue(VisualGesturePermits.consume(command.getString("visual_permit"), id, gesture, 2001))
        }
    }
    @Test fun fullModeGroundsCurrentImageDispatchesPermitAndRequiresNewVisualEvidence() {
        val engine = engine(); val id = create(engine)
        val work = grounding(engine)
        assertTrue(work.vision); assertTrue(work.grounding)
        assertEquals("auto", work.payload.getString("tool_choice"))
        assertEquals("mimo-v2.5", work.payload.getString("model"))
        assertTrue(work.payload.toString().contains("capture-a")); assertTrue(work.payload.toString().contains("image_url"))
        engine.accept(work, reply("propose_tap", candidate()))
        val command = engine.poll().getJSONObject("command")
        assertEquals("visual_gesture", command.getString("kind"))
        assertEquals("capture-a", command.getJSONObject("gesture").getString("capture_id"))
        assertTrue(VisualGesturePermits.consume(command.getString("visual_permit"), id, VisualGesture.parse(command.getJSONObject("gesture")), 2001))
        assertFalse(stored.contains("aW1hZ2U="))
        deliver(engine, data = JSONObject().put("image_base64", "bmV3LWltYWdl").put("mime_type", "image/png").put("visual_frame", frame().copy(captureId = "capture-after").json()))
        val verification = engine.takeWork()!!
        assertTrue(verification.vision); assertFalse(verification.grounding)
        assertEquals(1, engine.get(id).getInt("successful_mutations"))
    }
    @Test fun assistAndAskRequireExplicitApprovalBeforeAnyPermitIsIssued() {
        for (mode in listOf("ask", "assist")) {
            val engine = engine(); val id = create(engine, mode)
            val captures = VisualCaptureStore { 2000L }
            val source = VisualCapture(frame(), VisualPixels(960, 540, ByteArray(960 * 540) { 100 }))
            captures.put(source)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            assertEquals("awaiting_approval", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
            assertFalse(stored.contains("visual_permit"))
            // Non-running worker polls and TaskControl's local suspension both stop feedback.
            val previousExecution = captures.generation.get()
            repeat(4) { captures.stopFeedback() }
            val request = engine.get(id).getJSONObject("pending_request").getString("id")
            engine.control(id, "answer", JSONObject().put("request_id", request).put("approve", true))
            deliver(engine)
            val command = engine.poll().getJSONObject("command")
            assertEquals("visual_gesture", command.getString("kind"))
            val gesture = VisualGesture.parse(command.getJSONObject("gesture"))
            assertTrue(captures.generation.get() > previousExecution)
            assertTrue(VisualGesturePermits.consume(command.getString("visual_permit"), id, gesture, 2001))
            assertSame("Explicit approval must retain its original device-owned pixels", source, captures.remove(gesture.captureId))
            assertNull(captures.remove(gesture.captureId))
        }
    }
    @Test fun stoppingAnApprovedOrFullActionRevokesAuthorityEvenWhenPixelsRemain() {
        for (mode in listOf("ask", "assist", "full")) for (control in listOf("pause", "cancel")) {
            val engine = engine(); val id = create(engine, mode)
            val captures = VisualCaptureStore { 2000L }
            val source = VisualCapture(frame(), VisualPixels(960, 540, ByteArray(960 * 540) { 100 }))
            captures.put(source)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            if (mode != "full") {
                val request = engine.get(id).getJSONObject("pending_request").getString("id")
                engine.control(id, "answer", JSONObject().put("request_id", request).put("approve", true))
                deliver(engine)
            }
            val command = engine.poll().getJSONObject("command")
            engine.control(id, control, JSONObject()); captures.stopFeedback()
            assertSame(source, captures.remove("capture-a"))
            assertFalse(VisualGesturePermits.consume(command.getString("visual_permit"), id,
                VisualGesture.parse(command.getJSONObject("gesture")), 2001))
            assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun paymentAndVerificationRemainManualEvenWhenFullOrModelDeclaresSafe() {
        for (label in listOf("收银台", "安全验证")) {
            val engine = engine(); val id = create(engine, label = label)
            engine.accept(grounding(engine, label), reply("propose_tap", candidate()))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
            assertTrue(engine.get(id).getJSONObject("pending_request").getBoolean("manual_only"))
        }
    }
    @Test fun wrongCaptureExtraAuthorityFieldsOrOutOfRangeCoordinatesDoNotDispatch() {
        for (candidate in listOf(candidate().put("capture_id", "other"), candidate().put("approved", true), candidate().put("x", 960), candidate().put("x", .4))) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun missingPostGestureScreenshotPausesWithoutReplayingAcceptedGesture() {
        val engine = engine(); val id = create(engine)
        engine.accept(grounding(engine), reply("propose_tap", candidate()))
        deliver(engine)
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        assertEquals(1, engine.get(id).getInt("successful_mutations"))
    }
    private fun acceptedWhileLoading(reason: String = "empty_frame") = JSONObject().put("action_state", "accepted")
        .put("read_diagnostic", JSONObject().put("error_class", "ScreenNotReadyException")
            .put("source_file", "DoppelAccessibilityService.kt").put("source_line", 759).put("reason_code", reason))
        .put("visual_diagnostic", JSONObject().put("reason_code", "visual_pixels_verified").put("stage", "verify")
            .put("frame_matches", true).put("pixels_match", true))
    private fun resultFor(command: JSONObject, status: String, data: JSONObject = JSONObject(), observation: JSONObject? = screen()) = JSONObject()
        .put("run_id", command.getString("run_id")).put("command_id", command.getString("id")).put("status", status)
        .put("observation", observation ?: JSONObject.NULL).put("data", data)
    private fun postActionFrame() = JSONObject().put("image_base64", "bmV3LWltYWdl").put("mime_type", "image/png")
        .put("visual_frame", frame().copy(captureId = "capture-after").json())

    @Test fun captureTransitionAfterAcceptedInputOnlyReadsAgainAndRetainsBothDiagnostics() {
        for (reason in listOf("capture_screen_changed", "capture_geometry_changed", "capture_companion_changed")) {
            val e = engine(); val id = create(e)
            e.accept(grounding(e), reply("propose_tap", candidate()))
            val data = acceptedWhileLoading().apply { remove("read_diagnostic") }.put("post_action_read_diagnostic", JSONObject()
                .put("status", "stale").put("reason_code", reason).put("visual_diagnostic", JSONObject().put("reason_code", reason).put("stage", "capture")))
            deliver(e, data = data)
            assertEquals("running", e.get(id).getString("status"))
            assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
            assertEquals(1, e.get(id).getInt("successful_mutations"))
            val diagnostic = e.get(id).getJSONObject("last_visual_success_diagnostic")
            assertEquals("visual_pixels_verified", diagnostic.getJSONObject("visual").getString("reason_code"))
            assertEquals("stale", diagnostic.getJSONObject("post_action_read").getString("status"))
        }
    }

    @Test fun postActionCapturePermissionLossCannotBeRetriedAsPageTransition() {
        val e = engine(); val id = create(e)
        e.accept(grounding(e), reply("propose_tap", candidate()))
        val data = acceptedWhileLoading().apply { remove("read_diagnostic") }.put("human_takeover", "screen_capture_required")
            .put("post_action_read_diagnostic", JSONObject().put("status", "blocked").put("human_takeover", "screen_capture_required"))
        deliver(e, data = data)
        assertEquals("paused", e.get(id).getString("status")); assertTrue(e.poll().isNull("command"))
    }
    @Test fun explicitNewCaptureFailureCannotFallBackToLegacyReadiness() {
        for (diagnostic in listOf<Any>("malformed", JSONObject.NULL, JSONObject().put("status", "blocked"),
            JSONObject().put("status", "error").put("reason_code", "unrecognized_failure")
                .put("read_diagnostic", acceptedWhileLoading().getJSONObject("read_diagnostic")))) {
            val e = engine(); val id = create(e)
            e.accept(grounding(e), reply("propose_tap", candidate()))
            deliver(e, data = acceptedWhileLoading().put("post_action_read_diagnostic", diagnostic))
            assertEquals("paused", e.get(id).getString("status"))
            assertTrue(e.poll().isNull("command"))
        }
    }
    @Test fun transientReadFailuresNeverConsumeTheFirstActionRecoveryBudget() {
        val e = engine()
        val id = e.create(JSONObject().put("goal", "点击当前画布").put("device_id", DirectRuntime.DEVICE_ID).put("mode", "full")).getString("id")
        repeat(2) {
            val read = e.poll().getJSONObject("command")
            assertEquals("observe", read.getString("kind"))
            e.result(resultFor(read, "stale", JSONObject().put("reason_code", "capture_screen_changed")
                .put("visual_diagnostic", JSONObject().put("reason_code", "capture_screen_changed").put("stage", "capture"))))
            assertEquals(0, e.get(id).optInt("consecutive_stale"))
        }
        deliver(e, data = JSONObject().put("device_profile", JSONObject().put("visual_gestures", true)))
        assertEquals(0, e.get(id).optInt("consecutive_read_stale"))
        e.accept(grounding(e), reply("propose_tap", candidate()))
        val action = e.poll().getJSONObject("command")
        e.result(resultFor(action, "stale", JSONObject().put("reason_code", "verification_capture_failed")
            .put("visual_diagnostic", JSONObject().put("reason_code", "verification_capture_failed").put("stage", "verify")
                .put("capture_reason_code", "capture_screen_changed"))))
        assertEquals("running", e.get(id).getString("status"))
        assertEquals(1, e.get(id).getInt("consecutive_stale"))
        assertEquals("observe", e.poll().getJSONObject("command").getString("kind"))
    }

    @Test fun aConfirmedGestureWithKnownLoadingOnlyQueuesOneReadAndThenVisualVerification() {
        for (reason in listOf("root_unavailable", "empty_frame")) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            val calls = engine.get(id).getInt("calls")
            val action = deliver(engine, data = acceptedWhileLoading(reason))
            assertEquals("running", engine.get(id).getString("status")); assertEquals(1, engine.get(id).getInt("successful_mutations"))
            assertEquals(calls, engine.get(id).getInt("calls")); assertNull(engine.takeWork())
            val read = engine.poll().getJSONObject("command")
            assertEquals("observe", read.getString("kind")); assertTrue(read.getBoolean("include_screenshot"))
            assertNotEquals(action.getString("id"), read.getString("id")); assertFalse(read.has("gesture")); assertFalse(read.has("visual_permit"))
            assertFalse(engine.result(resultFor(action, "ok", acceptedWhileLoading(reason))).getBoolean("accepted"))
            assertEquals(read.getString("id"), engine.poll().getJSONObject("command").getString("id"))
            deliver(engine, data = postActionFrame())
            val verification = engine.takeWork()!!
            assertTrue(verification.vision); assertFalse(verification.grounding); assertFalse(verification.payload.has("tools"))
            assertEquals(calls + 1, engine.get(id).getInt("calls")); assertEquals(1, engine.get(id).getInt("successful_mutations"))
            assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun anUnknownSupplementalFailureCancelledOrMissingImagePausesWithoutAnotherRead() {
        for (status in listOf("error", "stale", "cancelled", "ok")) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            deliver(engine, data = acceptedWhileLoading())
            val read = engine.poll().getJSONObject("command")
            val calls = engine.get(id).getInt("calls")
            assertTrue(engine.result(resultFor(read, status, acceptedWhileLoading("unknown_reason"))).getBoolean("accepted"))
            assertEquals("paused", engine.get(id).getString("status")); assertEquals(calls, engine.get(id).getInt("calls"))
            assertEquals(1, engine.get(id).getInt("successful_mutations")); assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
        }
    }
    @Test fun unconfirmedActionsUnknownFailuresAndUntrustedVerificationFlagsKeepTheOriginalPause() {
        val invalid = listOf(
            acceptedWhileLoading().put("action_state", "unconfirmed"),
            acceptedWhileLoading().put("action_state", "not_dispatched"),
            acceptedWhileLoading().apply { remove("action_state") },
            acceptedWhileLoading("unknown_reason"),
            acceptedWhileLoading().apply { getJSONObject("read_diagnostic").put("error_class", "SecurityException") },
            acceptedWhileLoading().apply { getJSONObject("visual_diagnostic").put("frame_matches", false) },
            acceptedWhileLoading().apply { getJSONObject("visual_diagnostic").put("pixels_match", "true") },
            acceptedWhileLoading().apply { getJSONObject("visual_diagnostic").put("reason_code", "target_pixels_changed") },
            acceptedWhileLoading().apply { remove("visual_diagnostic") })
        for (data in invalid) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            val calls = engine.get(id).getInt("calls")
            deliver(engine, data = data)
            assertEquals("paused", engine.get(id).getString("status")); assertEquals(calls, engine.get(id).getInt("calls"))
            assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
        }
    }
    @Test fun supplementalPixelsWithoutFreshFrameIdentityOrObservationCannotStartVision() {
        for (invalid in listOf("missing_frame", "invalid_frame", "different_screen", "missing_observation")) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            deliver(engine, data = acceptedWhileLoading())
            val read = engine.poll().getJSONObject("command")
            val data = postActionFrame()
            when (invalid) {
                "missing_frame" -> data.remove("visual_frame")
                "invalid_frame" -> data.put("visual_frame", JSONObject())
                "different_screen" -> data.put("visual_frame", frame().copy(screenId = "other-screen").json())
            }
            engine.result(resultFor(read, "ok", data, if (invalid == "missing_observation") null else screen()))
            assertEquals("paused", engine.get(id).getString("status")); assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun pauseAndCancelDuringSupplementalReadingDiscardLateScreenshots() {
        for (action in listOf("pause", "cancel")) {
            val engine = engine(); val id = create(engine)
            engine.accept(grounding(engine), reply("propose_tap", candidate()))
            deliver(engine, data = acceptedWhileLoading())
            val read = engine.poll().getJSONObject("command")
            engine.control(id, action, JSONObject())
            assertFalse(engine.result(resultFor(read, "ok", postActionFrame())).getBoolean("accepted"))
            assertEquals(if (action == "pause") "paused" else "cancelled", engine.get(id).getString("status"))
            assertEquals(1, engine.get(id).getInt("successful_mutations")); assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun restartingWhileSupplementalReadingNeverRestoresTheReadOrItsGesture() {
        val engine = engine(); val id = create(engine)
        engine.accept(grounding(engine), reply("propose_tap", candidate()))
        deliver(engine, data = acceptedWhileLoading())
        val oldRead = engine.poll().getJSONObject("command")
        val restored = DirectTaskEngine(stored, { stored = it }, { 2000L })
        assertEquals("paused", restored.get(id).getString("status")); assertTrue(restored.poll().isNull("command"))
        assertFalse(restored.result(resultFor(oldRead, "ok", postActionFrame())).getBoolean("accepted"))
        assertNull(restored.takeWork()); assertEquals(1, restored.get(id).getInt("successful_mutations"))
        restored.control(id, "resume", JSONObject())
        val fresh = restored.poll().getJSONObject("command")
        assertEquals("observe", fresh.getString("kind")); assertNotEquals(oldRead.getString("id"), fresh.getString("id"))
        assertFalse(fresh.optBoolean("include_screenshot")); assertFalse(fresh.has("gesture"))
    }
    @Test fun cannotGroundAndPauseCannotProduceAnActionFromOldModelResponse() {
        val engine = engine(); val id = create(engine)
        engine.accept(grounding(engine), reply("cannot_ground", JSONObject().put("reason", "目标被遮挡")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        engine.control(id, "resume", JSONObject()); deliver(engine)
        val old = grounding(engine)
        engine.control(id, "pause", JSONObject())
        engine.accept(old, reply("propose_tap", candidate()))
        assertTrue(engine.poll().isNull("command")); assertEquals("paused", engine.get(id).getString("status"))
    }
    @Test fun waitingCanCoverLongAnimationWithoutIncreasingModelLoopLimit() {
        val engine = engine(); create(engine)
        engine.accept(engine.takeWork()!!, reply("navigate", JSONObject().put("kind", "wait").put("duration_ms", 120000)))
        assertEquals(30000, engine.poll().getJSONObject("command").getInt("duration_ms"))
    }
}
