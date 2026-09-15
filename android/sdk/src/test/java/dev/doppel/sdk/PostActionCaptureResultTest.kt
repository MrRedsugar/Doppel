package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostActionCaptureResultTest {
    private fun input() = JSONObject().put("action_state", "accepted").put("backend", "adb_shell_local_socket")
        .put("action_completed_at_elapsed_ms", 44973668L)
        .put("visual_diagnostic", JSONObject().put("reason_code", "visual_pixels_verified").put("stage", "verify")
            .put("frame_matches", true).put("pixels_match", true))
    private fun capture(status: String, data: JSONObject = JSONObject()) = JSONObject().put("status", status)
        .put("message", "采集结果").put("data", data)
    private fun transition(reason: String = "capture_screen_changed") = capture("stale", JSONObject().put("reason_code", reason)
        .put("visual_diagnostic", JSONObject().put("reason_code", reason).put("stage", "capture")))
    private fun attach(shot: JSONObject) = PostActionCaptureResult.attach(input(), shot)
    private fun diagnostic(data: JSONObject) = data.getJSONObject(PostActionCaptureResult.KEY)

    @Test fun anAcceptedLaunchKeepsInputAndPostCaptureEvidenceSeparately() {
        val result = attach(transition())
        assertEquals("accepted", result.getString("action_state"))
        assertEquals(44973668L, result.getLong("action_completed_at_elapsed_ms"))
        assertEquals("visual_pixels_verified", result.getJSONObject("visual_diagnostic").getString("reason_code"))
        assertEquals("stale", diagnostic(result).getString("status"))
        assertEquals("capture_screen_changed", diagnostic(result).getString("reason_code"))
        assertTrue(PostActionCaptureResult.knownTransition(diagnostic(result)))
        assertFalse(result.has("image_base64"))
    }

    @Test fun eachProvenCaptureTransitionCanRequestOnlyANewObservation() {
        for (reason in listOf("capture_screen_changed", "capture_geometry_changed", "capture_companion_changed")) {
            val data = attach(transition(reason))
            assertTrue(reason, PostActionCaptureResult.knownTransition(diagnostic(data)))
            assertEquals("accepted", data.getString("action_state"))
        }
    }

    @Test fun typedMissingWindowAndEmptyFrameRemainDifferentFromArbitraryErrors() {
        for (reason in ScreenReadinessReason.entries) {
            val data = attach(capture("error", JSONObject().put("read_diagnostic", DeviceReadDiagnostic.from(ScreenNotReadyException(reason)))))
            assertTrue(PostActionCaptureResult.knownTransition(diagnostic(data)))
            assertEquals(reason.code, diagnostic(data).getJSONObject("read_diagnostic").getString("reason_code"))
        }
        for (error in listOf(SecurityException(), IllegalStateException(), NullPointerException())) {
            val data = attach(capture("error", JSONObject().put("read_diagnostic", DeviceReadDiagnostic.from(error))))
            assertFalse(PostActionCaptureResult.knownTransition(diagnostic(data)))
            assertEquals("accepted", data.getString("action_state"))
        }
    }

    @Test fun privacyAndPermissionTakeoverSurviveAfterAnAcceptedAction() {
        for (takeover in listOf("login", "verification", "screen_capture_required")) {
            val shot = transition().put("status", "blocked")
            shot.getJSONObject("data").put("human_takeover", takeover)
            val data = attach(shot)
            assertEquals(takeover, data.getString("human_takeover"))
            assertEquals(takeover, diagnostic(data).getString("human_takeover"))
            assertFalse(PostActionCaptureResult.knownTransition(diagnostic(data)))
        }
    }

    @Test fun cancellationAndMalformedStatesCannotBeConvertedIntoReadiness() {
        for (status in listOf("cancelled", "blocked", "ok", "unknown")) {
            assertFalse(PostActionCaptureResult.knownTransition(diagnostic(attach(transition().put("status", status)))))
        }
        val malformed = transition().put("status", true)
        assertEquals("unknown", diagnostic(attach(malformed)).getString("status"))
        assertFalse(PostActionCaptureResult.knownTransition(diagnostic(attach(malformed))))
        val unknownTakeover = transition().apply { getJSONObject("data").put("human_takeover", JSONObject()) }
        assertEquals("unknown", attach(unknownTakeover).getString("human_takeover"))
        assertFalse(PostActionCaptureResult.knownTransition(diagnostic(attach(unknownTakeover))))
    }

    @Test fun successfulDeliveryCannotOverwriteInputReceiptOrCopyArbitraryFields() {
        val frame = JSONObject().put("capture_id", "new-frame")
        val shot = capture("ok", JSONObject().put("image_base64", "new-png").put("mime_type", "image/png")
            .put("visual_frame", frame).put("capture_backend", "adb_shell")
            .put("action_state", "failed").put("backend", "other").put("action_completed_at_elapsed_ms", 0)
            .put("visual_diagnostic", JSONObject().put("reason_code", "capture_screen_changed"))
            .put("command", "private-command").put("human_takeover", "login"))
        val data = attach(shot)
        assertEquals("accepted", data.getString("action_state"))
        assertEquals("adb_shell_local_socket", data.getString("backend"))
        assertEquals(44973668L, data.getLong("action_completed_at_elapsed_ms"))
        assertEquals("visual_pixels_verified", data.getJSONObject("visual_diagnostic").getString("reason_code"))
        // A contradictory takeover still wins over an ostensibly successful screenshot.
        assertEquals("login", data.getString("human_takeover"))
        assertFalse(data.has("image_base64"))
        shot.getJSONObject("data").remove("human_takeover")
        val delivered = attach(shot)
        assertEquals("new-png", delivered.getString("image_base64"))
        assertEquals("adb_shell", delivered.getString("capture_backend"))
        assertEquals("new-frame", delivered.getJSONObject("visual_frame").getString("capture_id"))
        assertFalse(delivered.has("command"))
        assertFalse(delivered.has("screenshot_error"))
        frame.put("capture_id", "mutated")
        assertEquals("new-frame", delivered.getJSONObject("visual_frame").getString("capture_id"))
    }

    @Test fun failedCaptureNeverLeaksPixelsOrPrivatePayloadIntoDiagnostics() {
        val shot = transition().apply {
            getJSONObject("data").put("image_base64", "private-pixels").put("command", "private-command")
                .put("visual_diagnostic", getJSONObject("data").getJSONObject("visual_diagnostic").put("reasoning_content", "private-reasoning"))
            put("observation", JSONObject().put("nodes", "private-nodes"))
        }
        val data = attach(shot)
        assertFalse(data.has("image_base64"))
        assertFalse(diagnostic(data).toString().contains("private-"))
        assertTrue(PostActionCaptureResult.knownTransition(diagnostic(data)))
    }

    @Test fun shellCaptureFailureKeepsItsOwnDiagnosticWithoutReplacingInputBackendFacts() {
        val shell = JSONObject().put("source", "client").put("operation", "screenshot").put("stage", "response_read")
            .put("reason_code", "backend_response_failed").put("elapsed_ms", 200)
            .put("stdout", "private-stdout")
        val original = input().put("shell_diagnostic", JSONObject().put("operation", "tap").put("reason_code", "process_ok"))
        val data = PostActionCaptureResult.attach(original, capture("error", JSONObject().put("reason_code", "backend_disconnected")
            .put("shell_diagnostic", shell)))
        assertEquals("tap", data.getJSONObject("shell_diagnostic").getString("operation"))
        assertEquals("screenshot", diagnostic(data).getJSONObject("shell_diagnostic").getString("operation"))
        assertEquals("backend_disconnected", diagnostic(data).getString("reason_code"))
        assertFalse(PostActionCaptureResult.knownTransition(diagnostic(data)))
        assertFalse(diagnostic(data).toString().contains("private-stdout"))
    }

    @Test fun knownWordsWithoutConsistentTypedEvidenceAreNotProofOfTransition() {
        val variants = listOf(
            transition().apply { getJSONObject("data").remove("visual_diagnostic") },
            transition().apply { getJSONObject("data").getJSONObject("visual_diagnostic").put("stage", "verify") },
            transition().apply { getJSONObject("data").getJSONObject("visual_diagnostic").put("reason_code", "capture_geometry_changed") },
            transition().apply { getJSONObject("data").put("read_diagnostic", DeviceReadDiagnostic.from(SecurityException())) },
            transition().apply { getJSONObject("data").put("human_takeover", "") }
        )
        for (variant in variants) assertFalse(PostActionCaptureResult.knownTransition(diagnostic(attach(variant))))
        assertFalse(PostActionCaptureResult.knownTransition(null))
        assertNull(PostActionCaptureResult.sanitize(null))
    }

    @Test fun diagnosticsAreDetachedAndContainOnlyBoundedVocabulary() {
        val data = attach(transition())
        val original = diagnostic(data).put("message", "private-message").put("image_base64", "private-pixels")
        val safe = requireNotNull(PostActionCaptureResult.sanitize(original))
        original.getJSONObject("visual_diagnostic").put("reason_code", "private-reason")
        assertEquals("capture_screen_changed", safe.getJSONObject("visual_diagnostic").getString("reason_code"))
        assertFalse(safe.toString().contains("private-"))
    }

    @Test fun readingSmallDiagnosticsDoesNotSerializeTheScreenshotPayload() {
        val png = "A".repeat(4 * 1024 * 1024)
        val captureData = object : JSONObject() {
            var serializations = 0
            override fun toString(): String { serializations++; return super.toString() }
        }
        captureData.put("image_base64", png).put("mime_type", "image/png").put("capture_backend", "adb_shell")
        val result = attach(capture("ok", captureData))
        assertEquals("Small diagnostics must not serialize a multi-megabyte screenshot", 0, captureData.serializations)
        assertSame(png, result.get("image_base64"))
    }

    @Test fun anUnknownReasonCannotDisappearAndPromoteAnErrorToReadiness() {
        val invalidReasons = listOf<Any>("future_capture_error", "", 23, true, JSONObject(), JSONObject.NULL)
        for (invalid in invalidReasons) {
            val shot = capture("error", JSONObject().put("reason_code", invalid)
                .put("read_diagnostic", DeviceReadDiagnostic.from(ScreenNotReadyException())))
            val first = diagnostic(attach(shot))
            assertEquals("unknown", first.optString("reason_code"))
            assertFalse(PostActionCaptureResult.knownTransition(first))
            val persisted = requireNotNull(PostActionCaptureResult.sanitize(JSONObject(first.toString())))
            assertEquals("unknown", persisted.optString("reason_code"))
            assertFalse(PostActionCaptureResult.knownTransition(persisted))
        }
    }

    @Test fun takeoverFeedbackUsesBoundedHostReasonsInsteadOfArbitraryCaptureText() {
        val expectedWords = mapOf("login" to "登录", "verification" to "验证", "payment" to "付款",
            "interruption" to "干扰", "screen_capture_required" to "授权", "sensitive" to "敏感", "uncertain" to "核对")
        for ((takeover, word) in expectedWords) {
            val shot = capture("blocked", JSONObject().put("human_takeover", takeover))
                .put("message", "private-password-and-arbitrary-host-text".repeat(100))
            val data = attach(shot)
            val message = data.getString("screenshot_error")
            assertTrue(message, message.contains(word))
            assertFalse(message.contains("private-"))
            assertTrue(message.length in 1..160)
            assertFalse(PostActionCaptureResult.knownTransition(diagnostic(data)))
        }
        val unknown = attach(capture("blocked", JSONObject().put("human_takeover", "private-host-code"))
            .put("message", "private-unbounded-message"))
        assertFalse(unknown.getString("screenshot_error").contains("private-"))
        assertTrue(unknown.getString("screenshot_error").contains("检查"))
    }
}
