package dev.doppel.sdk

import java.util.UUID
import kotlin.math.abs

/** Metadata must come from the trusted capture host, using its monotonic clock. */
internal data class VisualAnchorFrame(val captureId: String, val sha256: String, val packageName: String,
    val width: Int, val height: Int, val rotation: Int, val capturedAtMs: Long)
internal data class VisualAnchorStep(val kind: String, val startAnchorId: String? = null, val endAnchorId: String? = null,
    val durationMs: Long = 80, val requiredAnchorIds: List<String> = emptyList(), val waitTimeoutMs: Long = 0)
internal data class VisualAnchorPoint(val x: Int, val y: Int)
internal sealed class VisualAnchorPlanPreparation {
    data class Prepared(val plan: VisualAnchorPlan) : VisualAnchorPlanPreparation()
    data class Rejected(val reason: String) : VisualAnchorPlanPreparation()
}
internal sealed class VisualAnchorDecision {
    data class Ready(val token: String, val stepIndex: Int, val kind: String, val start: VisualAnchorPoint?,
        val end: VisualAnchorPoint?, val durationMs: Long, val captureId: String) : VisualAnchorDecision()
    data class Waiting(val reason: String) : VisualAnchorDecision()
    data class Stopped(val reason: String) : VisualAnchorDecision()
    object Complete : VisualAnchorDecision()
}
/**
 * A short, in-memory local visual segment. It only locates candidate gestures: the host must still
 * authorize and validate each candidate through its normal execution path. Complete means every
 * requested dispatch/visual condition was acknowledged, never that the user's business goal passed.
 *
 * Templates come from one planning frame. New screens, occluded/changed templates, unsupported
 * actions, and expiry return control to the planner; no template is learned from an uncertain match.
 */
internal class VisualAnchorPlan private constructor(
    private val source: VisualAnchorFrame,
    private val anchors: Map<String, VisualAnchor>,
    private val steps: List<VisualAnchorStep>,
    private val expiresAtMs: Long,
    private val maxFrameAgeMs: Long,
    private val tracker: VisualAnchorTracker
) {
    private var cursor = 0
    private var stopped: String? = null
    private var pending: VisualAnchorDecision.Ready? = null
    private var pendingAtMs = -1L
    private var completedAtMs = -1L
    private var lastCaptureId: String? = null
    private var waitStartedAtMs: Long? = null

    @Synchronized fun evaluate(frame: VisualAnchorFrame, image: VisualAnchorImage, nowMs: Long): VisualAnchorDecision {
        stopped?.let { return VisualAnchorDecision.Stopped(it) }
        fun stop(reason: String): VisualAnchorDecision.Stopped { stopped = reason; pending = null; return VisualAnchorDecision.Stopped(reason) }
        if (nowMs < source.capturedAtMs || nowMs > expiresAtMs) return stop("plan_expired")
        if (frame.packageName != source.packageName) return stop("package_changed")
        if (frame.width != source.width || frame.height != source.height || image.width != frame.width || image.height != frame.height) return stop("dimensions_changed")
        if (frame.rotation != source.rotation) return stop("rotation_changed")
        if (!validFrame(frame) || frame.capturedAtMs > nowMs || nowMs - frame.capturedAtMs > maxFrameAgeMs) return stop("frame_not_fresh")
        if (frame.captureId == source.captureId && (frame.sha256 != source.sha256 || frame.capturedAtMs != source.capturedAtMs) ||
            frame.captureId != source.captureId && frame.capturedAtMs <= source.capturedAtMs) return stop("source_frame_mismatch")
        if (pending != null) return VisualAnchorDecision.Waiting("awaiting_action_result")
        if (cursor >= steps.size) return VisualAnchorDecision.Complete
        if (frame.capturedAtMs <= completedAtMs || (completedAtMs >= 0 && frame.captureId == lastCaptureId)) {
            return VisualAnchorDecision.Waiting("awaiting_fresh_frame")
        }
        while (cursor < steps.size) {
            val step = steps[cursor]
            if (waitStartedAtMs == null) waitStartedAtMs = nowMs
            val needed = (step.requiredAnchorIds + listOfNotNull(step.startAnchorId, step.endAnchorId)).distinct()
            val locations = linkedMapOf<String, VisualAnchorMatch.Matched>()
            for (id in needed) {
                when (val match = tracker.locate(anchors.getValue(id), image)) {
                    is VisualAnchorMatch.Matched -> locations[id] = match
                    is VisualAnchorMatch.Rejected -> {
                        val reason = "anchor_${id}_${match.reason}"
                        // Ambiguity/context replacement is not a loading condition and must replan.
                        if (step.waitTimeoutMs > 0 && match.reason == "not_visible_or_changed") {
                            if (nowMs - requireNotNull(waitStartedAtMs) <= step.waitTimeoutMs) return VisualAnchorDecision.Waiting(reason)
                            return stop("condition_timeout")
                        }
                        return stop(reason)
                    }
                }
            }
            if (step.kind == "wait_visible") { cursor++; waitStartedAtMs = null; continue }
            val start = locations.getValue(requireNotNull(step.startAnchorId)).let { VisualAnchorPoint(it.x, it.y) }
            val end = step.endAnchorId?.let { locations.getValue(it).let { point -> VisualAnchorPoint(point.x, point.y) } }
            // Preserve the existing gesture parser's minimum normalized swipe distance after relocation.
            if (end != null && abs(end.x - start.x).toDouble() / frame.width + abs(end.y - start.y).toDouble() / frame.height < .01) return stop("swipe_too_short")
            return VisualAnchorDecision.Ready(UUID.randomUUID().toString(), cursor, step.kind, start, end, step.durationMs, frame.captureId).also {
                pending = it; pendingAtMs = nowMs; lastCaptureId = frame.captureId
            }
        }
        return VisualAnchorDecision.Complete
    }

    /** Call only after the host has confirmed gesture dispatch completion, not merely queue acceptance. */
    @Synchronized fun acknowledge(token: String, confirmedDispatched: Boolean, completedAtMs: Long): Boolean {
        if (stopped != null) return false
        val current = pending
        if (current == null || current.token != token || completedAtMs < pendingAtMs || completedAtMs > expiresAtMs) {
            stopped = "invalid_action_result"; pending = null; return false
        }
        pending = null
        if (!confirmedDispatched) { stopped = "action_not_confirmed"; return true }
        this.completedAtMs = completedAtMs; cursor++; waitStartedAtMs = null
        return true
    }

    @Synchronized fun cancel() { stopped = "cancelled"; pending = null }

    companion object {
        fun prepare(source: VisualAnchorFrame, image: VisualAnchorImage, specs: List<VisualAnchorSpec>, steps: List<VisualAnchorStep>, nowMs: Long,
            ttlMs: Long = 15000, maxFrameAgeMs: Long = 1500,
            sourceExpiresAtMs: Long = if (source.capturedAtMs <= Long.MAX_VALUE - 45000) source.capturedAtMs + 45000 else -1): VisualAnchorPlanPreparation {
            if (!validFrame(source) || nowMs < source.capturedAtMs || ttlMs !in 1..45000 || maxFrameAgeMs !in 1..5000 ||
                sourceExpiresAtMs <= source.capturedAtMs || sourceExpiresAtMs - source.capturedAtMs > 45000 || nowMs > sourceExpiresAtMs ||
                nowMs > Long.MAX_VALUE - ttlMs || source.width != image.width || source.height != image.height) {
                return VisualAnchorPlanPreparation.Rejected("invalid_source_frame")
            }
            if (specs.size !in 1..16 || specs.map { it.id }.distinct().size != specs.size || steps.size !in 1..8) {
                return VisualAnchorPlanPreparation.Rejected("invalid_plan")
            }
            val ids = specs.map { it.id }.toSet()
            for (step in steps) {
                val references = step.requiredAnchorIds + listOfNotNull(step.startAnchorId, step.endAnchorId)
                val schema = when (step.kind) {
                    "tap" -> step.startAnchorId != null && step.endAnchorId == null && step.durationMs in 40..200
                    "long_press" -> step.startAnchorId != null && step.endAnchorId == null && step.durationMs in 500..2000
                    "swipe" -> step.startAnchorId != null && step.endAnchorId != null && step.startAnchorId != step.endAnchorId && step.durationMs in 150..2000
                    "wait_visible" -> step.startAnchorId == null && step.endAnchorId == null && step.requiredAnchorIds.isNotEmpty() && step.durationMs == 0L
                    else -> false
                }
                if (!schema || references.size > 16 || references.any { it !in ids } || step.waitTimeoutMs !in 0..ttlMs) return VisualAnchorPlanPreparation.Rejected("invalid_step")
            }
            val tracker = VisualAnchorTracker(); val anchors = linkedMapOf<String, VisualAnchor>()
            for (spec in specs) {
                when (val result = tracker.prepare(image, spec)) {
                    is VisualAnchorPreparation.Prepared -> anchors[spec.id] = result.anchor
                    is VisualAnchorPreparation.Rejected -> return VisualAnchorPlanPreparation.Rejected("anchor_${spec.id}_${result.reason}")
                }
            }
            return VisualAnchorPlanPreparation.Prepared(VisualAnchorPlan(source, anchors,
                steps.map { it.copy(requiredAnchorIds = it.requiredAnchorIds.toList()) }, minOf(nowMs + ttlMs, sourceExpiresAtMs), maxFrameAgeMs, tracker))
        }

        private fun validFrame(frame: VisualAnchorFrame): Boolean = frame.captureId.length in 1..128 && frame.packageName.length in 1..255 &&
            frame.width in 1..4096 && frame.height in 1..4096 && frame.rotation in 0..3 && frame.capturedAtMs >= 0 &&
            frame.sha256.length == 64 && frame.sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
