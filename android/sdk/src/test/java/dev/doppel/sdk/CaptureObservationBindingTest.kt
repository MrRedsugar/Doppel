package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptureObservationBindingTest {
    private val geometry = Triple(1440, 3200, 0)
    private fun snapshot(id: String, at: Long = 9000) = TargetScreenSnapshot(id, "cn.wps.moffice_eng", 7, 21, 1440, 3200, at, emptyList())
    private fun plan() = ScreenshotPayloadPlan("capture", "before", "cn.wps.moffice_eng", 1440, 3200, 0, 10000, false)
    private fun binding(before: TargetScreenSnapshot? = snapshot("before"), after: TargetScreenSnapshot? = snapshot("after", 11000),
        beforeGeometry: Triple<Int, Int, Int> = geometry, afterGeometry: Triple<Int, Int, Int> = geometry,
        now: Long = 12000, source: ScreenshotPayloadPlan = plan()) =
        CaptureObservationBinding.bind(source, before, after, beforeGeometry, afterGeometry, now)
    private fun frame() = VisualFrame("capture", "before", "cn.wps.moffice_eng", 1440, 3200, 864, 1920, 0, 10000, 55000, "original-png-sha256")

    @Test fun semanticChangesPairWithTheAfterObservationWithoutChangingPixelProvenance() {
        val bound = requireNotNull(binding())
        val original = frame()
        val paired = bound.bindFrame(original)
        assertEquals(original.copy(screenId = "after"), paired)
        assertEquals(plan().copy(screenId = "after"), bound.plan)
        assertEquals(VisualNavigationAnchor("after", 7, 21), bound.navigation)
        assertEquals("before", original.screenId)
        val metadata = bound.metadata()
        assertEquals("same_window_navigation", metadata.getString("binding"))
        assertEquals("before", metadata.getString("before_screen_id"))
        assertEquals("after", metadata.getString("after_screen_id"))
        assertEquals(9000L, metadata.getLong("before_observed_at_elapsed_ms"))
        assertEquals(10000L, metadata.getLong("captured_at_elapsed_ms"))
        assertEquals(11000L, metadata.getLong("after_observed_at_elapsed_ms"))
        assertTrue(metadata.getBoolean("semantic_changed_during_capture"))
    }

    @Test fun unchangedSemanticsKeepTheOriginalIdentityAndStillExposeTrueCaptureTiming() {
        val bound = requireNotNull(binding(after = snapshot("before", 11000)))
        assertEquals(frame(), bound.bindFrame(frame()))
        assertFalse(bound.metadata().getBoolean("semantic_changed_during_capture"))
        assertEquals("before", bound.plan.screenId)
    }

    @Test fun missingOrMisassociatedBeforeAndAfterSourcesCannotBeBound() {
        assertNull(binding(before = null))
        assertNull(binding(after = null))
        assertNull(binding(before = snapshot("another-source")))
        assertNull(binding(before = snapshot("before").copy(packageName = "other.package")))
        assertNull(binding(after = snapshot("after", 11000).copy(packageName = "other.package")))
        assertNull(binding(after = snapshot("after", 11000).copy(windowId = 8)))
        assertNull(binding(after = snapshot("after", 11000).copy(navigationGeneration = 22)))
    }

    @Test fun rotatedResizedAndInconsistentObservationDimensionsRemainRejected() {
        assertNull(binding(afterGeometry = Triple(3200, 1440, 1)))
        assertNull(binding(beforeGeometry = Triple(3200, 1440, 1), afterGeometry = Triple(3200, 1440, 1)))
        assertNull(binding(beforeGeometry = Triple(1440, 3200, 1), afterGeometry = Triple(1440, 3200, 1)))
        assertNull(binding(after = snapshot("after", 11000).copy(width = 3200, height = 1440)))
    }

    @Test fun actualCaptureMustBeInsideItsObservedIntervalAndOriginalLifetime() {
        assertNull(binding(before = snapshot("before", 10001)))
        assertNull(binding(after = snapshot("after", 9999)))
        assertNull(binding(now = 10999))
        assertNull(binding(now = 55001))
        assertNotNull(binding(before = snapshot("before", 10000), after = snapshot("after", 10000), now = 10000))
        assertNotNull(binding(now = 55000))
    }

    @Test fun pairingCannotReplaceTheCaptureIdentityTimeDimensionsOrDigest() {
        val bound = requireNotNull(binding())
        for (foreign in listOf(frame().copy(captureId = "another-capture"), frame().copy(screenId = "other-source"),
            frame().copy(capturedAt = 9999, expiresAt = 54999), frame().copy(displayWidth = 1441), frame().copy(rotation = 1),
            frame().copy(imageWidth = 863))) {
            assertThrows(IllegalArgumentException::class.java) { bound.bindFrame(foreign) }
        }
        assertEquals(frame().sha256, bound.bindFrame(frame()).sha256)
        assertEquals(frame().captureId, bound.bindFrame(frame()).captureId)
        assertEquals(frame().capturedAt, bound.bindFrame(frame()).capturedAt)
        assertEquals(frame().expiresAt, bound.bindFrame(frame()).expiresAt)
    }

    @Test fun metadataIsStrictlyBoundedAndDoesNotGrantNavigationOrInputAuthority() {
        val metadata = requireNotNull(binding()).metadata().put("image_base64", "private-image")
            .put("window_id", 7).put("navigation_generation", 21).put("approved", true)
        val safe = requireNotNull(CaptureObservationBinding.sanitize(metadata))
        assertFalse(safe.toString().contains("private-"))
        assertFalse(safe.has("window_id")); assertFalse(safe.has("navigation_generation")); assertFalse(safe.has("approved"))
        val variants = listOf(
            JSONObject(metadata.toString()).put("binding", "trust-model"),
            JSONObject(metadata.toString()).put("captured_at_elapsed_ms", "10000"),
            JSONObject(metadata.toString()).put("captured_at_elapsed_ms", 12000),
            JSONObject(metadata.toString()).put("semantic_changed_during_capture", false),
            JSONObject(metadata.toString()).put("capture_id", "untrusted\ncontent")
        )
        variants.forEach { assertNull(CaptureObservationBinding.sanitize(it)) }
        assertNull(CaptureObservationBinding.sanitize(null))
    }

    @Test fun acceptedActionKeepsTheSmallBindingMetadataWithItsResultScreenshot() {
        val metadata = requireNotNull(binding()).metadata()
        val shot = JSONObject().put("status", "ok").put("data", JSONObject().put("image_base64", "original-image")
            .put("mime_type", "image/png").put("visual_frame", requireNotNull(binding()).bindFrame(frame()).json())
            .put("capture_observation", metadata))
        val data = PostActionCaptureResult.attach(JSONObject().put("action_state", "accepted"), shot)
        assertEquals("original-image", data.getString("image_base64"))
        assertEquals("accepted", data.getString("action_state"))
        assertEquals(metadata.toString(), data.getJSONObject("capture_observation").toString())
        metadata.put("before_screen_id", "later-mutated")
        assertEquals("before", data.getJSONObject("capture_observation").getString("before_screen_id"))
    }
}
