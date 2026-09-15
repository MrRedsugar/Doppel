package dev.doppel.sdk

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Host-only authority bound to the observation that supplied the captured frame; never model payload. */
internal data class VisualNavigationAnchor(val screenId: String, val windowId: Int, val navigationGeneration: Int)

internal data class VisualCapture(val frame: VisualFrame, val pixels: VisualPixels, val navigation: VisualNavigationAnchor? = null) {
    private fun sourceNavigation() = navigation?.takeIf { it.screenId == frame.screenId }
    fun canVerifyAgainst(current: TargetScreenSnapshot?, width: Int, height: Int, rotation: Int, now: Long): Boolean {
        val anchor = sourceNavigation() ?: return false
        current ?: return false
        return anchor.windowId == current.windowId && anchor.navigationGeneration == current.navigationGeneration &&
            frame.matchesVisualContext(current.packageName, width, height, rotation, now)
    }
    fun contextDiagnostic(current: TargetScreenSnapshot?, width: Int, height: Int, rotation: Int, now: Long): JSONObject {
        val anchor = sourceNavigation()
        return JSONObject().put("frame_matches", canVerifyAgainst(current, width, height, rotation, now))
            .put("semantic_screen_matches", frame.screenId == current?.screenId)
            .put("package_matches", frame.packageName == current?.packageName)
            .put("geometry_matches", frame.displayWidth == width && frame.displayHeight == height)
            .put("rotation_matches", frame.rotation == rotation).put("age_matches", now in frame.capturedAt..frame.expiresAt)
            .put("navigation_anchor_present", anchor != null)
            .put("window_matches", anchor != null && current != null && anchor.windowId == current.windowId)
            .put("navigation_matches", anchor != null && current != null && anchor.navigationGeneration == current.navigationGeneration)
            .put("source_age_ms", now - frame.capturedAt)
    }
}

/** Stopping execution is independent of retaining the short-lived evidence awaiting approval. */
internal class VisualCaptureStore(private val now: () -> Long) {
    val generation = AtomicLong()
    private val captures = linkedMapOf<String, VisualCapture>()

    @Synchronized fun stopFeedback(): Long {
        val next = generation.incrementAndGet()
        prune()
        return next
    }

    @Synchronized fun put(capture: VisualCapture) {
        prune()
        if (now() !in capture.frame.capturedAt..capture.frame.expiresAt) return
        captures.remove(capture.frame.captureId)
        while (captures.size >= 3) captures.remove(captures.keys.first())
        captures[capture.frame.captureId] = capture
    }

    @Synchronized fun remove(id: String): VisualCapture? {
        prune()
        return captures.remove(id)
    }

    @Synchronized fun clear() { captures.clear() }

    private fun prune() {
        val time = now()
        captures.entries.removeAll { time !in it.value.frame.capturedAt..it.value.frame.expiresAt }
    }
}
