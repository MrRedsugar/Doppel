package dev.doppel.sdk

import org.json.JSONObject

/** Explicit interval association, not an assertion that tree and pixels were captured atomically. */
internal class CaptureObservationBinding private constructor(
    private val acquired: ScreenshotPayloadPlan,
    private val before: TargetScreenSnapshot,
    private val after: TargetScreenSnapshot
) {
    val plan: ScreenshotPayloadPlan = acquired.copy(screenId = after.screenId)
    // Window and generation authority always come from the original pre-capture observation.
    val navigation = VisualNavigationAnchor(after.screenId, before.windowId, before.navigationGeneration)

    fun bindFrame(frame: VisualFrame): VisualFrame {
        require(frame.captureId == acquired.captureId && frame.screenId == acquired.screenId && frame.packageName == acquired.packageName &&
            frame.displayWidth == acquired.displayWidth && frame.displayHeight == acquired.displayHeight &&
            frame.imageWidth == acquired.imageWidth && frame.imageHeight == acquired.imageHeight && frame.rotation == acquired.rotation &&
            frame.capturedAt == acquired.capturedAt && frame.expiresAt == acquired.expiresAt) { "Capture provenance does not match its observation interval" }
        // No new screenshot is claimed: ID, digest, acquisition time, expiry and dimensions remain immutable.
        return frame.copy(screenId = after.screenId)
    }

    fun metadata(): JSONObject = JSONObject().put("binding", "same_window_navigation").put("capture_id", acquired.captureId)
        .put("before_screen_id", before.screenId).put("after_screen_id", after.screenId)
        .put("before_observed_at_elapsed_ms", before.capturedAt).put("captured_at_elapsed_ms", acquired.capturedAt)
        .put("after_observed_at_elapsed_ms", after.capturedAt)
        .put("semantic_changed_during_capture", before.screenId != after.screenId)

    companion object {
        fun bind(acquired: ScreenshotPayloadPlan, before: TargetScreenSnapshot?, after: TargetScreenSnapshot?,
            beforeGeometry: Triple<Int, Int, Int>, afterGeometry: Triple<Int, Int, Int>, now: Long): CaptureObservationBinding? {
            before ?: return null
            after ?: return null
            if (before.screenId != acquired.screenId || before.packageName != acquired.packageName || after.packageName != acquired.packageName ||
                before.windowId != after.windowId || before.navigationGeneration != after.navigationGeneration ||
                before.width != after.width || before.height != after.height || beforeGeometry != afterGeometry ||
                beforeGeometry.first != acquired.displayWidth || beforeGeometry.second != acquired.displayHeight || beforeGeometry.third != acquired.rotation ||
                before.capturedAt < 0 || before.capturedAt > acquired.capturedAt || acquired.capturedAt > after.capturedAt ||
                after.capturedAt > now || now !in acquired.capturedAt..acquired.expiresAt) return null
            return CaptureObservationBinding(acquired, before, after)
        }

        /** Metadata is explanatory only. It never reconstructs the host navigation anchor or grants an action. */
        fun sanitize(value: JSONObject?): JSONObject? {
            value ?: return null
            if (value.opt("binding") != "same_window_navigation") return null
            fun id(key: String) = (value.opt(key) as? String)?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9._:-]+")) }
            fun time(key: String): Long? = when (val raw = value.opt(key)) {
                is Int -> raw.toLong().takeIf { it >= 0 }
                is Long -> raw.takeIf { it >= 0 }
                else -> null
            }
            val captureId = id("capture_id") ?: return null
            val beforeId = id("before_screen_id") ?: return null
            val afterId = id("after_screen_id") ?: return null
            val beforeAt = time("before_observed_at_elapsed_ms") ?: return null
            val acquiredAt = time("captured_at_elapsed_ms") ?: return null
            val afterAt = time("after_observed_at_elapsed_ms") ?: return null
            val changed = value.opt("semantic_changed_during_capture") as? Boolean ?: return null
            if (beforeAt > acquiredAt || acquiredAt > afterAt || afterAt - acquiredAt > 45000 || changed != (beforeId != afterId)) return null
            return JSONObject().put("binding", "same_window_navigation").put("capture_id", captureId)
                .put("before_screen_id", beforeId).put("after_screen_id", afterId)
                .put("before_observed_at_elapsed_ms", beforeAt).put("captured_at_elapsed_ms", acquiredAt)
                .put("after_observed_at_elapsed_ms", afterAt).put("semantic_changed_during_capture", changed)
        }
    }
}
