package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiGroundingDiagnosticTest {
    @Test fun transportFailureRetainsStatusButCannotBecomeAClaimedPoint() {
        val failure = JSONObject().put("transport_diagnostic", JSONObject().put("code", "http_error")
            .put("http_status", 503).put("body", "secret")).put("x", 0).put("y", 0).put("status", "point")
        val value = result(response = failure)
        assertEquals("http_error", value.getString("error_code"))
        assertEquals(503, value.getJSONObject("transport_diagnostic").getInt("http_status"))
        assertFalse(value.has("source_verified")); assertFalse(value.has("x"))
        assertFalse(value.toString().contains("secret"))
    }
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/W9sAAAAASUVORK5CYII="
    private val sha = "06b5378be92db104507e166b9fd885382c9d94bd004ead66ea8baf8963d5075c"
    private fun frame() = VisualFrame("capture-current", "screen-current", "example.app", 400, 800, 1, 1, 0, 100, 20000, sha)
    private fun request() = JSONObject().put("visual_frame", frame().json()).put("image_base64", png)
        .put("kind", "tap").put("target", "private target text").put("screen_context", "private screen text").put("safety", "safe")
    private fun response() = JSONObject().put("capture_id", "capture-current").put("image_sha256", sha)
        .put("width", 1).put("height", 1).put("status", "point").put("x", 0).put("y", 0)
        .put("model", "example/locator-2b").put("revision", "a".repeat(40)).put("latency_ms", 1877.5)
    private fun result(request: JSONObject = request(), response: JSONObject? = response()) =
        GuiGroundingDiagnostic.from(request, response, 1000, 3000)

    @Test fun preparedCapturesOnlyBoundedRequestMetadataAndDoesNotClaimVerification() {
        val value = GuiGroundingDiagnostic.prepared(request(), 1000)
        assertEquals("prepared", value.getString("status"))
        assertEquals("capture-current", value.getString("capture_id"))
        assertEquals(sha, value.getString("image_sha256"))
        assertEquals("tap", value.getString("kind"))
        assertEquals(1000L, value.getLong("requested_at"))
        assertFalse(value.has("source_verified"))
        assertFalse(value.has("x"))
        assertFalse(value.toString().contains("private"))
        assertFalse(value.toString().contains(png))
    }

    @Test fun matchedResponseRecordsPixelPointWithExactSourceAndTimes() {
        val value = result()
        assertEquals("point", value.getString("status"))
        assertTrue(value.getBoolean("source_verified"))
        assertEquals("image_pixels", value.getString("coordinate_space"))
        assertEquals(0, value.getInt("x")); assertEquals(0, value.getInt("y"))
        assertEquals(1, value.getInt("width")); assertEquals(1, value.getInt("height"))
        assertEquals("screen-current", value.getString("screen_id"))
        assertEquals("example.app", value.getString("package_name"))
        assertEquals(1000L, value.getLong("requested_at")); assertEquals(3000L, value.getLong("received_at"))
        assertEquals(1877.5, value.getDouble("latency_ms"), 0.0)
        assertEquals("example/locator-2b", value.getString("model"))
        assertFalse(value.getBoolean("proves_target_hit"))
        assertFalse(value.getBoolean("proves_dispatch"))
    }

    @Test fun mismatchedCaptureHashOrDimensionsNeverProduceVerifiedPoint() {
        val invalid = listOf(response().put("capture_id", "other"), response().put("image_sha256", "b".repeat(64)),
            response().put("width", 2), response().put("height", 2))
        for (response in invalid) {
            val value = result(response = response)
            assertEquals("error", value.getString("status"))
            assertFalse(value.has("source_verified")); assertFalse(value.has("x")); assertFalse(value.has("y"))
        }
    }

    @Test fun malformedOrOutOfBoundsCoordinatesDoNotBecomeVerifiedMetadata() {
        for (point in listOf(0.5, -1, 1, "0", JSONObject.NULL)) {
            val value = result(response = response().put("x", point))
            assertEquals("error", value.getString("status"))
            assertFalse(value.has("source_verified")); assertFalse(value.has("x"))
        }
    }

    @Test fun actualRequestPngHashAndDimensionsMustMatchItsFrame() {
        val wrongDigest = request().put("image_base64", "aGVsbG8=")
        val wrongFrame = request().put("visual_frame", frame().json().put("image_width", 2))
        val matchingLie = response().put("width", 2)
        for (value in listOf(result(wrongDigest), result(wrongFrame, matchingLie))) {
            assertEquals("error", value.getString("status")); assertFalse(value.has("source_verified"))
        }
    }

    @Test fun notFoundVerifiesOnlyTheSourceAndNeverRetainsUntrustedCoordinates() {
        val value = result(response = response().put("status", "not_found"))
        assertEquals("not_found", value.getString("status")); assertTrue(value.getBoolean("source_verified"))
        assertFalse(value.has("x")); assertFalse(value.has("y"))
        assertFalse(value.getBoolean("proves_target_hit"))
    }

    @Test fun eachAttemptOverwritesAFormerSuccessWithItsOwnPreparedOrFailureRecord() {
        val run = JSONObject().put("gui_grounding", result())
        val next = request().put("visual_frame", frame().json().put("capture_id", "capture-next"))
        run.put("gui_grounding", GuiGroundingDiagnostic.prepared(next, 4000))
        assertFalse(run.getJSONObject("gui_grounding").has("x"))
        run.put("gui_grounding", GuiGroundingDiagnostic.from(next, null, 4000, 5000))
        val value = run.getJSONObject("gui_grounding")
        assertEquals("error", value.getString("status"))
        assertEquals("capture-next", value.getString("capture_id"))
        assertEquals(4000L, value.getLong("requested_at"))
        assertFalse(value.has("source_verified")); assertFalse(value.has("model")); assertFalse(value.has("x"))
    }

    @Test fun arbitraryResponseAndRequestFieldsCannotLeakIntoTheDiagnostic() {
        val request = request().put("headers", JSONObject().put("Authorization", "credential-secret"))
            .put("key", "key-secret").put("input", "input-secret")
        val response = response().put("reasoning", "reasoning-secret").put("raw", "response-secret")
            .put("target", "echo-secret").put("image_base64", png)
        val originalRequest = request.toString(); val originalResponse = response.toString()
        val value = result(request, response)
        for (secret in listOf("private", "credential-secret", "key-secret", "input-secret", "reasoning-secret", "response-secret", "echo-secret", png)) {
            assertFalse(secret, value.toString().contains(secret))
        }
        assertEquals(originalRequest, request.toString()); assertEquals(originalResponse, response.toString())
    }

    @Test fun unsupportedKindInvalidFrameAndOversizedImageFailWithoutThrowing() {
        for (request in listOf(request().put("kind", "swipe"), request().put("visual_frame", "bad"),
            request().put("image_base64", "A".repeat(6 * 1024 * 1024 + 1)))) {
            val value = result(request)
            assertEquals("error", value.getString("status")); assertFalse(value.has("source_verified"))
        }
    }

    @Test fun malformedResponseMetadataIsOmittedAndBackwardsResponseTimeIsRejected() {
        val value = result(response = response().put("model", JSONObject().put("key", "secret"))
            .put("revision", "r".repeat(500)).put("latency_ms", -1))
        assertTrue(value.getBoolean("source_verified"))
        assertFalse(value.has("model")); assertTrue(value.optString("revision").length <= 80)
        assertFalse(value.has("latency_ms"))
        assertFalse(GuiGroundingDiagnostic.from(request(), response(), 3000, 2000).has("source_verified"))
    }

    private fun plannerPoint()=JSONObject().put("capture_id","capture-current").put("coordinate_space","image_pixels").put("x",0).put("y",0)

    @Test fun plannerPointIsRetainedBeforeResponseAndDisplacementRequiresVerifiedGuiPoint() {
        val request=request().put("planner_point",plannerPoint())
        val prepared=GuiGroundingDiagnostic.prepared(request,1000)
        assertEquals("available",prepared.getString("planner_point_status"))
        assertEquals(0,prepared.getJSONObject("planner_point").getInt("x"))
        assertFalse(prepared.has("displacement"))
        val point=result(request)
        assertEquals(0.0,point.getJSONObject("displacement").getDouble("distance_px"),0.0)
        assertFalse(point.getBoolean("proves_target_hit"))
        for(value in listOf(result(request,null),result(request,response().put("status","not_found")),result(request,response().put("capture_id","wrong"))))
            assertFalse(value.has("displacement"))
    }

    @Test fun absentOrMalformedPlannerPointIsNeverGuessedOrCopied() {
        assertEquals("unavailable",result().getString("planner_point_status"))
        for(invalid in listOf(plannerPoint().put("capture_id","old"),plannerPoint().put("coordinate_space","device_pixels"),
            plannerPoint().put("x",1),plannerPoint().put("y",-1),plannerPoint().put("x",0.5),plannerPoint().put("x","0"))) {
            val value=result(request().put("planner_point",invalid))
            assertEquals("invalid",value.getString("planner_point_status"))
            assertFalse(value.has("planner_point"));assertFalse(value.has("displacement"))
            assertTrue(value.getBoolean("source_verified"))
        }
    }

    @Test fun displacementUsesOriginalImagePixelsRatherThanDeviceDimensions() {
        val frame=VisualFrame("capture-big","screen","example.app",1440,3200,864,1920,0,100,20000,"a".repeat(64))
        val planner=JSONObject().put("capture_id",frame.captureId).put("coordinate_space","image_pixels").put("x",100).put("y",300)
        val displacement=GuiGroundingDiagnostic.displacement(frame,planner,107,278)
        assertEquals(7,displacement.getInt("dx"));assertEquals(-22,displacement.getInt("dy"))
        assertEquals(kotlin.math.hypot(7.0,22.0),displacement.getDouble("distance_px"),0.00001)
        assertEquals(kotlin.math.hypot(7.0,22.0)/kotlin.math.hypot(864.0,1920.0),displacement.getDouble("screen_diagonal_fraction"),0.000001)
    }
}
