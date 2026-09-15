package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualContextTest {
    private fun frame() = VisualFrame("capture", "screen-before-ad", "com.android.launcher3", 1440, 3200, 864, 1920, 0, 1000, 46000, "digest")
    private fun snapshot(screen: String = "screen-before-ad", window: Int = 7, navigation: Int = 21, pkg: String = "com.android.launcher3") =
        TargetScreenSnapshot(screen, pkg, window, navigation, 1440, 3200, 9000, emptyList())
    private fun source(anchor: VisualNavigationAnchor? = VisualNavigationAnchor("screen-before-ad", 7, 21)) =
        VisualCapture(frame(), VisualPixels(100, 100, IntArray(10000) { 0x646464 }), anchor)
    private fun canVerify(source: VisualCapture = source(), current: TargetScreenSnapshot? = snapshot(), width: Int = 1440,
        height: Int = 3200, rotation: Int = 0, time: Long = 9063) = source.canVerifyAgainst(current, width, height, rotation, time)

    @Test fun unrelatedSemanticChangeCanReachFreshPixelVerificationWithoutChangingLegacyIdentity() {
        val current = snapshot("screen-after-ad")
        assertFalse(frame().matches(current.screenId, current.packageName, 1440, 3200, 0, 9063))
        assertTrue(canVerify(current = current))
        val facts = source().contextDiagnostic(current, 1440, 3200, 0, 9063)
        assertFalse(facts.getBoolean("semantic_screen_matches"))
        for (key in listOf("frame_matches", "package_matches", "geometry_matches", "rotation_matches", "age_matches",
            "navigation_anchor_present", "window_matches", "navigation_matches")) assertTrue(key, facts.getBoolean(key))
        assertEquals(8063L, facts.getLong("source_age_ms"))
    }

    @Test fun differentApplicationDisplayRotationAndExpiredOrFutureFramesStayRejected() {
        assertFalse(canVerify(current = snapshot(pkg = "other.package")))
        assertFalse(canVerify(width = 3200, height = 1440))
        assertFalse(canVerify(width = 1439))
        assertFalse(canVerify(height = 3199))
        assertFalse(canVerify(rotation = 1))
        assertFalse(canVerify(time = 999))
        assertFalse(canVerify(time = 46001))
        assertTrue(canVerify(time = 1000))
        assertTrue(canVerify(time = 46000))
    }

    @Test fun aMatchingImageCannotBypassMissingOrMisboundNavigationAuthority() {
        assertFalse(canVerify(source = source(null)))
        assertFalse(canVerify(source = source(VisualNavigationAnchor("other-source", 7, 21))))
        assertFalse(canVerify(current = null))
        assertFalse(canVerify(current = snapshot(window = 8)))
        assertFalse(canVerify(current = snapshot(navigation = 22)))
        val facts = source(null).contextDiagnostic(snapshot(), 1440, 3200, 0, 9063)
        assertFalse(facts.getBoolean("navigation_anchor_present"))
        assertFalse(facts.getBoolean("frame_matches"))
    }

    @Test fun matchingContextOnlyPermitsVerificationAndTargetChangesStillFailPixelCheck() {
        val before = IntArray(10000) { 0x646464 }
        val bannerChanged = before.clone()
        for (y in 4..8) for (x in 10..80) bannerChanged[y * 100 + x] = 0x9999ff
        val gesture = VisualGesture("tap", "capture", .5, .65, null, null, 80, "WPS", "launcher", "safe")
        assertTrue(canVerify(current = snapshot("screen-after-ad")))
        assertTrue(VisualPixels(100, 100, before).compare(VisualPixels(100, 100, bannerChanged), gesture).matches)
        val targetCovered = bannerChanged.clone()
        for (y in 50..80) for (x in 35..65) targetCovered[y * 100 + x] = 0x000000
        val rejected = VisualPixels(100, 100, before).compare(VisualPixels(100, 100, targetCovered), gesture)
        assertFalse(rejected.matches)
        assertEquals("core_changed", rejected.reason)
    }

    @Test fun captureDiagnosticsNameTheSpecificMismatchedBoundaryWithoutPageContent() {
        val current = snapshot("screen-after-ad", window = 8, navigation = 22, pkg = "other.package")
        val facts = source().contextDiagnostic(current, 3200, 1440, 1, 46001)
        for (key in listOf("frame_matches", "semantic_screen_matches", "package_matches", "geometry_matches", "rotation_matches",
            "age_matches", "window_matches", "navigation_matches")) assertFalse(key, facts.getBoolean(key))
        assertFalse(facts.toString().contains("other.package"))
        assertFalse(facts.toString().contains("screen-after-ad"))
    }

    @Test fun navigationAuthorityIsNotPartOfTheModelFrameOrGesturePayload() {
        val capture = source()
        val json = capture.frame.json()
        assertFalse(json.has("navigation_generation"))
        assertFalse(json.has("window_id"))
        val proposal = JSONObject().put("kind", "tap").put("capture_id", "capture").put("x", .5).put("y", .65)
            .put("duration_ms", 80).put("label", "WPS").put("screen_context", "launcher").put("safety", "safe")
            .put("navigation_generation", 21)
        assertThrows(VisualValidationException::class.java) { VisualGesture.parse(proposal) }
    }

    @Test fun sourceBoundaryDiagnosticsPreserveBooleansWithoutCoercionOrPageContent() {
        val facts = source().contextDiagnostic(snapshot("screen-after-ad"), 1440, 3200, 0, 9063)
            .put("reason_code", "visual_pixels_verified").put("stage", "verify")
            .put("screen_id", "private-screen").put("window_id", 7).put("navigation_generation", 21)
        val safe = requireNotNull(DirectVisualDiagnostic.sanitize(facts))
        assertFalse(safe.getBoolean("semantic_screen_matches"))
        assertTrue(safe.getBoolean("navigation_matches"))
        assertTrue(safe.getBoolean("window_matches"))
        assertTrue(safe.getBoolean("age_matches"))
        assertFalse(safe.toString().contains("private-"))
        assertFalse(safe.has("window_id"))
        assertFalse(safe.has("navigation_generation"))
        facts.put("navigation_matches", "true")
        assertFalse(requireNotNull(DirectVisualDiagnostic.sanitize(facts)).has("navigation_matches"))
    }
}
