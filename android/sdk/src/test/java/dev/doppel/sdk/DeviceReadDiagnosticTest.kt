package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceReadDiagnosticTest {
    @Test fun rootUnavailableUsesDedicatedTypeAndOwnCodeLocationOnly() {
        val error = ScreenNotReadyException().apply { stackTrace = arrayOf(
            StackTraceElement("android.private.Screen", "private-text", "private-file", 1),
            StackTraceElement("dev.doppel.sdk.DoppelAccessibilityService", "observe", "DoppelAccessibilityService.kt", 155)) }
        val data = DeviceReadDiagnostic.from(error)
        assertEquals("ScreenNotReadyException", data.getString("error_class"))
        assertEquals("DoppelAccessibilityService.kt", data.getString("source_file"))
        assertEquals(155, data.getInt("source_line")); assertEquals(4, data.length())
        assertEquals("root_unavailable", data.getString("reason_code"))
        assertFalse(data.toString().contains("private")); assertFalse(data.toString().contains("当前活动"))
    }
    @Test fun unknownExceptionsAndInjectedDiagnosticStringsDoNotExposeScreenData() {
        val data = DeviceReadDiagnostic.sanitize(JSONObject().put("error_class", "private-key").put("source_file", "private-screen")
            .put("source_line", 45).put("message", "private-input").put("stack", "private-stack"))!!
        assertEquals("OtherException", data.getString("error_class"))
        assertEquals("unknown", data.getString("source_file")); assertEquals(0, data.getInt("source_line"))
        assertEquals(3, data.length()); assertFalse(data.toString().contains("private"))
        assertNull(DeviceReadDiagnostic.sanitize(null))
    }
    @Test fun invalidLinesAndExceptionMessageAreNeverRetained() {
        val error = IllegalArgumentException("private-node-text").apply { stackTrace = arrayOf(
            StackTraceElement("dev.doppel.sdk.DoppelAccessibilityService", "private-method", "DoppelAccessibilityService.kt", -1)) }
        val data = DeviceReadDiagnostic.from(error)
        assertEquals(0, data.getInt("source_line")); assertFalse(data.toString().contains("private"))
    }
    @Test fun emptyFrameAndRootReasonsAreFixedAndSurviveSafeProjection() {
        for (reason in ScreenReadinessReason.entries) {
            val error = ScreenNotReadyException(reason)
            val diagnostic = DeviceReadDiagnostic.from(error)
            assertEquals(reason.code, diagnostic.getString("reason_code"))
            assertEquals(reason.code, DeviceReadDiagnostic.sanitize(diagnostic)!!.getString("reason_code"))
            assertEquals(reason.explanation, error.message)
            assertFalse(diagnostic.has("message")); assertEquals(4, diagnostic.length())
        }
        assertEquals(ScreenReadinessReason.ROOT, ScreenNotReadyException().reason)
    }
    @Test fun arbitraryOrMisclassifiedReadinessCodesCannotEnterDiagnostics() {
        val unknown = DeviceReadDiagnostic.sanitize(JSONObject().put("error_class", "ScreenNotReadyException")
            .put("source_file", "DoppelAccessibilityService.kt").put("source_line", 155)
            .put("reason_code", "private-frame-description").put("message", "private-image-data"))!!
        assertFalse(unknown.has("reason_code")); assertFalse(unknown.toString().contains("private"))
        val wrongClass = DeviceReadDiagnostic.sanitize(JSONObject().put("error_class", "SecurityException").put("reason_code", "empty_frame"))!!
        assertFalse(wrongClass.has("reason_code"))
    }
}
