package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.hypot

internal enum class VisualValidationReason(val code: String, val explanation: String) {
    FRAME_IDENTITY("visual_frame_identity", "截图来源标识无效"),
    FRAME_DIMENSIONS("visual_frame_dimensions", "截图尺寸超出支持范围"),
    FRAME_LIFETIME("visual_frame_lifetime", "截图方向或有效期无效"),
    FRAME_DIGEST("visual_frame_digest", "截图缺少像素摘要"),
    UNKNOWN_FIELD("visual_unknown_field", "视觉候选含未支持的字段"),
    KIND("visual_kind", "视觉动作类型必须为点击、长按或滑动"),
    TAP_ENDPOINT("visual_unexpected_endpoint", "点击或长按不应提供滑动终点"),
    SWIPE_DISTANCE("visual_swipe_distance", "滑动起点与终点过近"),
    TAP_DURATION("visual_tap_duration", "视觉点击时长必须为 40 至 200 毫秒"),
    LONG_PRESS_DURATION("visual_long_press_duration", "视觉长按时长必须为 500 至 2000 毫秒"),
    SWIPE_DURATION("visual_swipe_duration", "视觉滑动时长必须为 150 至 2000 毫秒"),
    SAFETY("visual_safety", "视觉候选缺少有效的风险分类"),
    TEXT("visual_text", "视觉候选的文本字段缺失或长度无效"),
    INTEGER("visual_integer", "视觉候选的整数参数格式无效"),
    COORDINATE_TYPE("visual_coordinate_type", "视觉坐标必须为数值"),
    COORDINATE_RANGE("visual_coordinate_range", "视觉坐标必须大于等于 0 且小于 1"),
    PIXEL_COORDINATE_RANGE("visual_pixel_coordinate_range", "视觉坐标必须位于当前截图的像素范围内"),
    FRAME_INTEGER("visual_frame_integer", "截图尺寸或方向数值越界")
}

internal class VisualValidationException(val reason: VisualValidationReason, field: String? = null) : IllegalArgumentException(reason.explanation) {
    val field = field?.takeIf { it in setOf("kind", "capture_id", "screen_id", "package_name", "x", "y", "end_x", "end_y", "duration_ms",
        "label", "screen_context", "safety", "display_width", "display_height", "image_width", "image_height", "rotation",
        "captured_at_elapsed_ms", "expires_at_elapsed_ms", "sha256") }
}

private fun visualRequire(valid: Boolean, reason: VisualValidationReason) {
    if (!valid) throw VisualValidationException(reason)
}

/** Device-owned pixels; screenId identifies their paired semantic observation. Time and a tree hash alone never prove pixel freshness. */
internal data class VisualFrame(
    val captureId: String, val screenId: String, val packageName: String,
    val displayWidth: Int, val displayHeight: Int, val imageWidth: Int, val imageHeight: Int,
    val rotation: Int, val capturedAt: Long, val expiresAt: Long, val sha256: String
) {
    init {
        visualRequire(captureId.length in 1..128 && screenId.length in 1..128 && packageName.length in 1..255, VisualValidationReason.FRAME_IDENTITY)
        visualRequire(displayWidth in 1..16384 && displayHeight in 1..16384 && imageWidth in 1..4096 && imageHeight in 1..4096, VisualValidationReason.FRAME_DIMENSIONS)
        visualRequire(rotation in 0..3 && capturedAt >= 0 && expiresAt > capturedAt && expiresAt - capturedAt <= 45000, VisualValidationReason.FRAME_LIFETIME)
        visualRequire(sha256.isNotBlank(), VisualValidationReason.FRAME_DIGEST)
    }
    fun matches(screen: String, pkg: String, width: Int, height: Int, direction: Int, now: Long) =
        screenId == screen && packageName == pkg && displayWidth == width && displayHeight == height && rotation == direction && now in capturedAt..expiresAt
    /** Metadata only permits a fresh pixel check; it does not authorize a gesture or replace a navigation anchor. */
    fun matchesVisualContext(pkg: String, width: Int, height: Int, direction: Int, now: Long) =
        packageName == pkg && displayWidth == width && displayHeight == height && rotation == direction && now in capturedAt..expiresAt
    fun json() = JSONObject().put("capture_id", captureId).put("screen_id", screenId).put("package_name", packageName)
        .put("display_width", displayWidth).put("display_height", displayHeight).put("image_width", imageWidth).put("image_height", imageHeight)
        .put("rotation", rotation).put("captured_at_elapsed_ms", capturedAt).put("expires_at_elapsed_ms", expiresAt).put("sha256", sha256)
    companion object {
        fun parse(value: JSONObject) = VisualFrame(text(value, "capture_id", 128), text(value, "screen_id", 128), text(value, "package_name", 255),
            smallInteger(value, "display_width"), smallInteger(value, "display_height"), smallInteger(value, "image_width"), smallInteger(value, "image_height"),
            smallInteger(value, "rotation"), integer(value, "captured_at_elapsed_ms"), integer(value, "expires_at_elapsed_ms"), text(value, "sha256", 128))
    }
}

internal data class VisualGesture(
    val kind: String, val captureId: String, val x: Double, val y: Double, val endX: Double?, val endY: Double?,
    val durationMs: Long, val label: String, val screenContext: String, val safety: String
) {
    fun start(frame: VisualFrame) = FeedbackPoint((x * frame.displayWidth).toFloat().coerceIn(0f, (frame.displayWidth - 1).toFloat()),
        (y * frame.displayHeight).toFloat().coerceIn(0f, (frame.displayHeight - 1).toFloat()))
    fun end(frame: VisualFrame) = endX?.let { FeedbackPoint((it * frame.displayWidth).toFloat().coerceIn(0f, (frame.displayWidth - 1).toFloat()),
        (requireNotNull(endY) * frame.displayHeight).toFloat().coerceIn(0f, (frame.displayHeight - 1).toFloat())) }
    fun json() = JSONObject().put("kind", kind).put("capture_id", captureId).put("x", x).put("y", y).put("duration_ms", durationMs)
        .put("label", label).put("screen_context", screenContext).put("safety", safety).apply {
            if (endX != null) put("end_x", endX).put("end_y", endY)
        }
    fun blockedReason(labels: List<String>, sensitiveInput: Boolean): String? {
        val combined = labels + label + screenContext
        if (safety == "verification" || Policy.verificationRequired(combined)) return "verification"
        if (sensitiveInput || safety == "sensitive") return "sensitive"
        // Visual payment never delegates a charge, even if node payment delegation is enabled.
        if (safety == "payment" || Policy.paymentContext(combined) || Policy.manualFinancialContext(combined) || Policy.sensitive(label) ||
            Regex("充值|购买|抽卡|寻访|源石兑换|恢复理智|recharge|buy|purchase", RegexOption.IGNORE_CASE).containsMatchIn(label)) return "payment"
        if (safety != "safe") return "uncertain"
        return null
    }
    fun geometry(frame: VisualFrame): ActionFeedbackGeometry {
        val first = start(frame); val last = end(frame)
        val radius = max(12, min(frame.displayWidth, frame.displayHeight) / 45)
        val bounds = listOf((min(first.x, last?.x ?: first.x) - radius).toInt().coerceAtLeast(0),
            (min(first.y, last?.y ?: first.y) - radius).toInt().coerceAtLeast(0),
            (max(first.x, last?.x ?: first.x) + radius).toInt().coerceAtMost(frame.displayWidth),
            (max(first.y, last?.y ?: first.y) + radius).toInt().coerceAtMost(frame.displayHeight))
        val path = if (kind == "swipe" && last != null) {
            val from = android.graphics.PointF().apply { x = first.x; y = first.y }
            val to = android.graphics.PointF().apply { x = last.x; y = last.y }
            HumanGesturePath.samples(from, to, frame.displayWidth, frame.displayHeight).map { FeedbackPoint(it.x, it.y) }
        } else emptyList()
        return ActionFeedbackGeometry(if (kind == "swipe") "scroll" else kind, bounds, first, if (last != null) first else null, last, path)
    }
    companion object {
        private val keys = setOf("kind", "capture_id", "x", "y", "end_x", "end_y", "duration_ms", "label", "screen_context", "safety")
        private val proposalKinds = linkedMapOf("propose_tap" to "tap", "propose_long_press" to "long_press", "propose_swipe" to "swipe")
        val proposalToolNames: Set<String> get() = proposalKinds.keys
        private fun proposalKind(toolName: String): String = proposalKinds[toolName]
            ?: throw VisualValidationException(VisualValidationReason.KIND)

        /** Model tools select the type; model arguments cannot override it or smuggle another tool's fields. */
        fun parseProposal(toolName: String, value: JSONObject): VisualGesture {
            val kind = proposalKind(toolName)
            visualRequire(value.keys().asSequence().all { it in keys && it != "kind" }, VisualValidationReason.UNKNOWN_FIELD)
            if (kind != "swipe") visualRequire(!value.has("end_x") && !value.has("end_y"), VisualValidationReason.TAP_ENDPOINT)
            return parse(JSONObject(value.toString()).put("kind", kind))
        }

        fun parse(value: JSONObject): VisualGesture {
            visualRequire(value.keys().asSequence().all { it in keys }, VisualValidationReason.UNKNOWN_FIELD)
            val kind = text(value, "kind", 20); visualRequire(kind in setOf("tap", "long_press", "swipe"), VisualValidationReason.KIND)
            val x = coordinate(value, "x"); val y = coordinate(value, "y")
            val endX = if (kind == "swipe") coordinate(value, "end_x") else null
            val endY = if (kind == "swipe") coordinate(value, "end_y") else null
            if (kind != "swipe") visualRequire(value.isNull("end_x") && value.isNull("end_y"), VisualValidationReason.TAP_ENDPOINT)
            else visualRequire(abs(requireNotNull(endX) - x) + abs(requireNotNull(endY) - y) >= .01, VisualValidationReason.SWIPE_DISTANCE)
            val duration = integer(value, "duration_ms")
            visualRequire(duration in when (kind) { "tap" -> 40L..200L; "long_press" -> 500L..2000L; else -> 150L..2000L },
                when (kind) { "tap" -> VisualValidationReason.TAP_DURATION; "long_press" -> VisualValidationReason.LONG_PRESS_DURATION; else -> VisualValidationReason.SWIPE_DURATION })
            val safety = text(value, "safety", 20); visualRequire(safety in setOf("safe", "payment", "verification", "sensitive", "uncertain"), VisualValidationReason.SAFETY)
            return VisualGesture(kind, text(value, "capture_id", 128), x, y, endX, endY, duration,
                text(value, "label", 240), text(value, "screen_context", 1600), safety)
        }
        fun proposalSchema(toolName: String, captureId: String): JSONObject {
            val kind = proposalKind(toolName)
            fun string() = JSONObject().put("type", "string")
            fun coordinate() = JSONObject().put("type", "number").put("minimum", 0).put("maximum", .999999)
            val duration = when (kind) { "tap" -> 40..200; "long_press" -> 500..2000; else -> 150..2000 }
            val props = JSONObject().put("capture_id", string().put("enum", JSONArray(listOf(captureId))))
                .put("x", coordinate()).put("y", coordinate())
                .put("duration_ms", JSONObject().put("type", "integer").put("minimum", duration.first).put("maximum", duration.last))
                .put("label", string()).put("screen_context", string())
                .put("safety", string().put("enum", JSONArray(listOf("safe", "payment", "verification", "sensitive", "uncertain"))))
            val required = mutableListOf("capture_id", "x", "y", "duration_ms", "label", "screen_context", "safety")
            if (kind == "swipe") { props.put("end_x", coordinate()).put("end_y", coordinate()); required.addAll(listOf("end_x", "end_y")) }
            return JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required)).put("additionalProperties", false)
        }
    }
}

/** Single-use authority is issued by the host after its permission decision, never by a model field. */
internal object VisualGesturePermits {
    private data class Permit(val run: String, val signature: String, val at: Long)
    private val permits = linkedMapOf<String, Permit>()
    @Synchronized fun issue(run: String, gesture: VisualGesture, mode: String, approved: Boolean, now: Long = System.currentTimeMillis()): String {
        require(mode in setOf("ask", "assist", "full") && (mode == "full" || approved))
        while (permits.size >= 64) permits.remove(permits.keys.first())
        return UUID.randomUUID().toString().also { permits[it] = Permit(run, gesture.json().toString(), now) }
    }
    @Synchronized fun consume(token: String, run: String, gesture: VisualGesture, now: Long = System.currentTimeMillis()): Boolean {
        val permit = permits.remove(token) ?: return false
        return permit.run == run && permit.signature == gesture.json().toString() && now - permit.at in 0..45000
    }
    @Synchronized fun revoke(run: String) { permits.entries.removeAll { it.value.run == run } }
}

internal class VisualPixelCheck(val matches: Boolean, val mode: String, val reason: String, private val metrics: JSONObject) {
    fun json(): JSONObject = JSONObject(metrics.toString()).put("matches", matches).put("mode", mode).put("reason", reason)
}

/** Preserve the target's RGB/edge identity even when decorative pixels elsewhere animate. */
internal class VisualPixels(val width: Int, val height: Int, private val pixels: IntArray) {
    constructor(width: Int, height: Int, gray: ByteArray) : this(width, height, IntArray(gray.size) { index ->
        val value = gray[index].toInt() and 255; value shl 16 or (value shl 8) or value
    })
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
    fun matches(other: VisualPixels, gesture: VisualGesture): Boolean = compare(other, gesture).matches

    fun compare(other: VisualPixels, gesture: VisualGesture, allowMinorToneChange: Boolean = false): VisualPixelCheck {
        val metrics = JSONObject()
        fun result(matches: Boolean, mode: String, reason: String) = VisualPixelCheck(matches, mode, reason, metrics)
        if (width != other.width || height != other.height) return result(false, "rejected", "dimensions")
        fun grid(cx: Double, cy: Double, rx: Double, ry: Double, columns: Int, rows: Int): Region {
            var difference = 0L; var changed = 0
            for (row in 0 until rows) for (column in 0 until columns) {
                val x = ((cx - rx + 2 * rx * (column + .5) / columns) * width).toInt().coerceIn(0, width - 1)
                val y = ((cy - ry + 2 * ry * (row + .5) / rows) * height).toInt().coerceIn(0, height - 1)
                val previous = pixels[y * width + x]; val current = other.pixels[y * width + x]
                val delta = rgbDifference(previous, current)
                difference += delta; if (delta > 40) changed++
            }
            val count = rows * columns
            return Region(difference.toDouble() / count, changed.toDouble() / count)
        }
        val global = grid(.5, .5, .5, .5, 32, 18)
        metrics.put("global_mean", global.mean).put("global_changed_fraction", global.changed)
        // Every point along a swipe corridor is checked; an obstacle cannot hide between five samples.
        val radius = (min(width, height) * .0125).toInt().coerceIn(8, 16)
        val points = if (gesture.endX == null) 1 else
            (ceil(hypot((gesture.endX - gesture.x) * width, (requireNotNull(gesture.endY) - gesture.y) * height) / radius).toInt() + 1).coerceIn(2, 512)
        var localMean = 0.0; var localChanged = 0.0
        var coreMean = 0.0; var coreChanged = 0.0; var coreContrast = 255; var coreEdges = 1.0; var edgeAgreement = 1.0
        var coreCorrelation = 1.0
        var contextMean = 0.0; var contextChanged = 0.0
        var strictLocal = true; var coresStable = true; var contextStable = true
        var endpointAnchors = true
        for (index in 0 until points) {
            val progress = if (points == 1) 0.0 else index.toDouble() / (points - 1)
            val x = gesture.x + ((gesture.endX ?: gesture.x) - gesture.x) * progress
            val y = gesture.y + ((gesture.endY ?: gesture.y) - gesture.y) * progress
            val local = grid(x, y, max(.025, 12.0 / width), max(.025, 12.0 / height), 12, 12)
            localMean = max(localMean, local.mean); localChanged = max(localChanged, local.changed)
            strictLocal = strictLocal && local.passes(18.0, .15)
            val cx = (x * width).toInt(); val cy = (y * height).toInt()
            val core = core(other, cx, cy, radius)
            coreMean = max(coreMean, core.region.mean); coreChanged = max(coreChanged, core.region.changed)
            coreContrast = min(coreContrast, core.contrast); coreEdges = min(coreEdges, core.edgeFraction)
            edgeAgreement = min(edgeAgreement, core.edgeAgreement)
            coreCorrelation = min(coreCorrelation, core.correlation)
            // Game UI can fade its brightness without changing its target. Require an almost
            // identical RGB structure plus bounded absolute difference; never search/move a point.
            val toneStable = allowMinorToneChange && core.region.passes(24.0, .08) &&
                core.contrast >= 48 && core.edgeFraction >= .06 && core.correlation >= .995
            coresStable = coresStable && ((core.region.passes(12.0, .08) && core.edgeAgreement >= .96) || toneStable)
            if (index == 0 || index == points - 1) endpointAnchors = endpointAnchors && core.fullyVisible && core.contrast >= 48 && core.edgeFraction >= .06
            val context = ring(other, cx, cy, radius, radius * 3)
            contextMean = max(contextMean, context.mean); contextChanged = max(contextChanged, context.changed)
            contextStable = contextStable && context.passes(36.0, .25)
        }
        metrics.put("path_points", points).put("local_mean_max", localMean).put("local_changed_fraction_max", localChanged)
            .put("core_mean_max", coreMean).put("core_changed_fraction_max", coreChanged).put("core_contrast_min", coreContrast)
            .put("core_edge_fraction_min", coreEdges).put("core_edge_agreement_min", edgeAgreement)
            .put("core_rgb_correlation_min", coreCorrelation)
            .put("context_mean_max", contextMean).put("context_changed_fraction_max", contextChanged)
            .put("endpoint_anchors", endpointAnchors)
        if (!coresStable) return result(false, "rejected", if (points > 1) "path_changed" else "core_changed")
        if (global.passes(24.0, .30) && strictLocal) return result(true, "strict", "stable")
        if (!endpointAnchors) return result(false, "rejected", "anchor_missing")
        if (!contextStable) return result(false, "rejected", "context_changed")
        return result(true, "target_anchor", "stable")
    }

    private data class Region(val mean: Double, val changed: Double) {
        fun passes(meanLimit: Double, changedLimit: Double) = mean <= meanLimit && changed <= changedLimit
    }
    private data class Core(val region: Region, val contrast: Int, val edgeFraction: Double, val edgeAgreement: Double, val fullyVisible: Boolean, val correlation: Double)

    private fun core(other: VisualPixels, cx: Int, cy: Int, radius: Int): Core {
        val left = max(0, cx - radius); val right = min(width, cx + radius)
        val top = max(0, cy - radius); val bottom = min(height, cy + radius)
        var total = 0L; var changed = 0; var count = 0; var pairs = 0; var beforeEdges = 0; var afterEdges = 0; var edgeUnion = 0; var stableEdges = 0
        val beforeMin = IntArray(3) { 255 }; val beforeMax = IntArray(3); val afterMin = IntArray(3) { 255 }; val afterMax = IntArray(3)
        var sumBefore=0.0;var sumAfter=0.0;var sumBefore2=0.0;var sumAfter2=0.0;var sumProduct=0.0
        fun edge(first: Int, second: Int) {
            pairs++
            val before = rgbDifference(pixels[first], pixels[second]) >= 24
            val after = rgbDifference(other.pixels[first], other.pixels[second]) >= 24
            if (before) beforeEdges++; if (after) afterEdges++
            if (before || after) {
                edgeUnion++
                var difference = 0
                for (channel in 0..2) {
                    val shift = channel * 8
                    val oldGradient = (pixels[first] shr shift and 255) - (pixels[second] shr shift and 255)
                    val newGradient = (other.pixels[first] shr shift and 255) - (other.pixels[second] shr shift and 255)
                    difference = max(difference, abs(oldGradient - newGradient))
                }
                if (difference <= 12) stableEdges++
            }
        }
        for (y in top until bottom) for (x in left until right) {
            val offset = y * width + x; val previous = pixels[offset]; val current = other.pixels[offset]
            val delta = rgbDifference(previous, current); total += delta; if (delta > 40) changed++; count++
            for (channel in 0..2) {
                val shift = channel * 8; val before = previous shr shift and 255; val after = current shr shift and 255
                sumBefore+=before;sumAfter+=after;sumBefore2+=before*before;sumAfter2+=after*after;sumProduct+=before*after
                beforeMin[channel] = min(beforeMin[channel], before); beforeMax[channel] = max(beforeMax[channel], before)
                afterMin[channel] = min(afterMin[channel], after); afterMax[channel] = max(afterMax[channel], after)
            }
            if (x + 1 < right) edge(offset, offset + 1)
            if (y + 1 < bottom) edge(offset, offset + width)
        }
        val contrast = min((0..2).maxOf { beforeMax[it] - beforeMin[it] }, (0..2).maxOf { afterMax[it] - afterMin[it] })
        val n=(count*3).coerceAtLeast(1).toDouble()
        val varianceProduct=(sumBefore2-sumBefore*sumBefore/n)*(sumAfter2-sumAfter*sumAfter/n)
        val correlation=if(varianceProduct>1.0) ((sumProduct-sumBefore*sumAfter/n)/kotlin.math.sqrt(varianceProduct)).coerceIn(-1.0,1.0) else 0.0
        return Core(Region(total.toDouble() / count.coerceAtLeast(1), changed.toDouble() / count.coerceAtLeast(1)), contrast,
            min(beforeEdges, afterEdges).toDouble() / pairs.coerceAtLeast(1), if (edgeUnion == 0) 1.0 else stableEdges.toDouble() / edgeUnion,
            left == cx - radius && right == cx + radius && top == cy - radius && bottom == cy + radius, correlation)
    }

    private fun ring(other: VisualPixels, cx: Int, cy: Int, inner: Int, outer: Int): Region {
        var total = 0L; var changed = 0; var count = 0
        for (y in max(0, cy - outer) until min(height, cy + outer)) for (x in max(0, cx - outer) until min(width, cx + outer)) {
            if (x in cx - inner until cx + inner && y in cy - inner until cy + inner) continue
            val delta = rgbDifference(pixels[y * width + x], other.pixels[y * width + x])
            total += delta; if (delta > 40) changed++; count++
        }
        return Region(total.toDouble() / count.coerceAtLeast(1), changed.toDouble() / count.coerceAtLeast(1))
    }

    private fun rgbDifference(previous: Int, current: Int) = maxOf(abs((previous shr 16 and 255) - (current shr 16 and 255)),
        abs((previous shr 8 and 255) - (current shr 8 and 255)), abs((previous and 255) - (current and 255)))
}

private fun text(value: JSONObject, key: String, max: Int): String = (value.opt(key) as? String)?.trim()
    ?.takeIf { it.length in 1..max } ?: throw VisualValidationException(VisualValidationReason.TEXT, key)
private fun integer(value: JSONObject, key: String): Long = when (val number = value.opt(key)) {
    is Int -> number.toLong()
    is Long -> number
    else -> throw VisualValidationException(VisualValidationReason.INTEGER, key)
}
private fun coordinate(value: JSONObject, key: String): Double {
    val number = (value.opt(key) as? Number)?.toDouble() ?: throw VisualValidationException(VisualValidationReason.COORDINATE_TYPE, key)
    if (!number.isFinite() || number < 0.0 || number >= 1.0) throw VisualValidationException(VisualValidationReason.COORDINATE_RANGE, key)
    return number
}
private fun smallInteger(value: JSONObject, key: String): Int {
    val number = integer(value, key)
    if (number !in 0..16384) throw VisualValidationException(VisualValidationReason.FRAME_INTEGER, key)
    return number.toInt()
}
