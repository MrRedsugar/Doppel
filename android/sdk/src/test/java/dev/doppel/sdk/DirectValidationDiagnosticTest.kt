package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectValidationDiagnosticTest {
    @Test fun sourceLocationAndFixedReasonExcludeExceptionMessagesAndForeignFrames() {
        val error = VisualValidationException(VisualValidationReason.TAP_DURATION, "private-field").apply {
            stackTrace = arrayOf(
                StackTraceElement("private-provider", "private-method", "VisualGesture.kt", 66),
                StackTraceElement("dev.doppel.sdk.VisualGesture\$Companion", "private-method", "VisualGesture.kt", 117))
        }
        val diagnostic = DirectValidationDiagnostic.from(error)
        assertEquals("VisualValidationException", diagnostic.getString("error_class"))
        assertEquals("VisualGesture.kt", diagnostic.getString("source_file")); assertEquals(117, diagnostic.getInt("source_line"))
        assertEquals("visual_tap_duration", diagnostic.getString("validation_code"))
        assertEquals(4, diagnostic.length()); assertFalse(diagnostic.toString().contains("private"))
    }
    @Test fun unexpectedExceptionAndUntrustedFilenameDoNotEnterDiagnostics() {
        val error = object : Exception("private response and arguments") {}.apply {
            stackTrace = arrayOf(StackTraceElement("dev.doppel.sdk.DirectTaskEngine", "private-method", "private.kt", 123))
        }
        val diagnostic = DirectValidationDiagnostic.from(error)
        assertEquals("OtherException", diagnostic.getString("error_class"))
        assertEquals("unknown", diagnostic.getString("source_file")); assertEquals(0, diagnostic.getInt("source_line"))
        assertEquals(3, diagnostic.length()); assertFalse(diagnostic.toString().contains("private"))
    }
    @Test fun visualMetadataAcceptsOnlyFixedStagesReasonsBooleansAndBoundedIntegers() {
        val source = JSONObject().put("reason_code", "verification_capture_failed").put("stage", "verify")
            .put("source_age_ms", 5000L).put("verification_age_ms", 50).put("frame_matches", true).put("pixels_match", false)
            .put("capture_reason_code", "capture_screen_changed").put("arguments", "private-model-value").put("image_base64", "private-image")
        val diagnostic = DirectVisualDiagnostic.sanitize(source)!!
        assertEquals(7, diagnostic.length()); assertFalse(diagnostic.toString().contains("private"))
        assertEquals(5000L, diagnostic.getLong("source_age_ms")); assertFalse(diagnostic.getBoolean("pixels_match"))
        val bad = DirectVisualDiagnostic.sanitize(JSONObject().put("reason_code", "source_capture_missing").put("stage", "private-stage")
            .put("source_age_ms", "private-age").put("verification_age_ms", Double.POSITIVE_INFINITY.toString())
            .put("frame_matches", "true").put("capture_reason_code", "private-reason"))!!
        assertEquals(1, bad.length()); assertNull(DirectVisualDiagnostic.sanitize(JSONObject().put("reason_code", "private-code")))
    }
    @Test fun staleDescriptionsAreLocallyGeneratedAndUnknownStringsAreDiscarded() {
        val known = DirectStaleReason.from(JSONObject().put("reason_code", "source_capture_missing"), "private-screen-title")
        assertEquals("来源截图已失效，请重新观察", known.getString("message")); assertFalse(known.toString().contains("private"))
        val legacy = DirectStaleReason.from(JSONObject(), "截图期间页面发生变化，请重新观察")
        assertEquals("capture_screen_changed", legacy.getString("reason_code"))
        val unknown = DirectStaleReason.from(JSONObject().put("reason_code", "private-code"), "private-screen-title")
        assertEquals("stale_unknown", unknown.getString("reason_code")); assertFalse(unknown.toString().contains("private"))
    }
    @Test fun pixelMetricsRetainAcceptedAndRejectedEvidenceWithoutRawPixelsOrCoordinates() {
        for ((mode, reason) in listOf("strict" to "stable", "target_anchor" to "stable", "rejected" to "core_changed")) {
            val check = JSONObject().put("matches", mode != "rejected").put("mode", mode).put("reason", reason)
                .put("endpoint_anchors", true).put("path_points", 512L).put("core_contrast_min", 255)
                .put("global_mean", 255.0).put("local_mean_max", 12.5).put("core_mean_max", 0).put("context_mean_max", 36.0)
                .put("global_changed_fraction", 1.0).put("local_changed_fraction_max", .1).put("core_changed_fraction_max", .08)
                .put("core_edge_fraction_min", .06).put("core_edge_agreement_min", .96).put("context_changed_fraction_max", .25)
                .put("pixels", "private-pixels").put("capture_id", "private-capture").put("x", .2).put("y", .3)
            val source = JSONObject().put("reason_code", if (mode == "rejected") "target_pixels_changed" else "visual_pixels_verified")
                .put("stage", "verify").put("pixel_check", check)
            val clean = DirectVisualDiagnostic.sanitize(source)!!.getJSONObject("pixel_check")
            assertEquals(16, clean.length()); assertEquals(mode, clean.getString("mode"))
            assertEquals(mode != "rejected", clean.getBoolean("matches")); assertEquals(512, clean.getInt("path_points"))
            assertEquals(.96, clean.getDouble("core_edge_agreement_min"), .000001)
            assertFalse(clean.has("x")); assertFalse(clean.has("y")); assertFalse(clean.toString().contains("private"))
        }
    }
    @Test fun malformedPixelMetricsCannotEnterPersistedDiagnostics() {
        val check = JSONObject().put("matches", false).put("mode", "rejected").put("reason", "path_changed")
            .put("endpoint_anchors", "true").put("path_points", 513).put("core_contrast_min", 256)
            .put("global_mean", "NaN").put("local_mean_max", "12.0").put("core_mean_max", -1).put("context_mean_max", 256.0)
            .put("global_changed_fraction", -0.1).put("core_changed_fraction_max", 1.01).put("core_edge_agreement_min", "Infinity")
            .put("untrusted", JSONObject().put("pixels", "private-pixels"))
        val source = JSONObject().put("reason_code", "target_pixels_changed").put("pixel_check", check)
        assertEquals(3, DirectVisualDiagnostic.sanitize(source)!!.getJSONObject("pixel_check").length())
        for ((key, value) in listOf("matches" to "true", "mode" to "private-mode", "reason" to "private-reason")) {
            val invalid = JSONObject(source.toString())
            invalid.getJSONObject("pixel_check").put(key, value)
            assertFalse(DirectVisualDiagnostic.sanitize(invalid)!!.has("pixel_check"))
        }
        check.put("path_points", 1.5).put("core_contrast_min", 10.5)
        val clean = DirectVisualDiagnostic.sanitize(source)!!.getJSONObject("pixel_check")
        assertFalse(clean.has("path_points")); assertFalse(clean.has("core_contrast_min"))
    }
}
