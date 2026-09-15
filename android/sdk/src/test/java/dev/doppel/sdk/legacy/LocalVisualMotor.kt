package dev.doppel.sdk

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal sealed class LocalVisualMotorPreparation {
    data class Prepared(val motor: LocalVisualMotor) : LocalVisualMotorPreparation()
    data class Rejected(val reason: String) : LocalVisualMotorPreparation()
}
internal sealed class LocalVisualMotorDecision {
    /** Candidate only. The caller must dispatch through the usual permission and fresh-pixel checks. */
    data class Ready(val token: String, val stepIndex: Int, val action: JSONObject) : LocalVisualMotorDecision()
    data class Waiting(val reason: String) : LocalVisualMotorDecision()
    data class Stopped(val reason: String) : LocalVisualMotorDecision()
    object Complete : LocalVisualMotorDecision()
}

/** Android image/JSON adapter for a local visual segment. No ADB, gesture injection or permit issuance. */
internal class LocalVisualMotor private constructor(
    private val plan: VisualAnchorPlan,
    private val source: VisualFrame,
    private val descriptions: List<Description>,
    private val decode: (ByteArray) -> VisualAnchorImage?
) {
    private data class Description(val label: String, val context: String, val safety: String)
    private var pendingToken: String? = null
    private var pendingCommand: String? = null
    private var stopped: String? = null

    @Synchronized fun evaluate(capture: JSONObject, nowMs: Long): LocalVisualMotorDecision {
        stopped?.let { return LocalVisualMotorDecision.Stopped(it) }
        val parsed = try { readCapture(capture, decode) } catch (_: Exception) { return stop("capture_invalid") }
        val (frame, pixels) = parsed
        if (frame.displayWidth != source.displayWidth || frame.displayHeight != source.displayHeight) return stop("display_dimensions_changed")
        if (nowMs !in frame.capturedAt..frame.expiresAt) return stop("capture_expired")
        return when (val decision = plan.evaluate(frame.anchorFrame(), pixels, nowMs)) {
            is VisualAnchorDecision.Ready -> {
                val text = descriptions[decision.stepIndex]
                val start = requireNotNull(decision.start)
                val gesture = VisualGesture(decision.kind, frame.captureId, start.x.toDouble() / frame.imageWidth,
                    start.y.toDouble() / frame.imageHeight, decision.end?.x?.toDouble()?.div(frame.imageWidth),
                    decision.end?.y?.toDouble()?.div(frame.imageHeight), decision.durationMs, text.label, text.context, text.safety)
                // Reuse the production format validator. This does not call blockedReason or grant authority;
                // dispatchVisual must still apply its live labels, user mode, permits and VisualPixels gate.
                val action = try {
                    JSONObject().put("kind", "visual_gesture").put("screen_id", frame.screenId)
                        .put("gesture", VisualGesture.parse(gesture.json()).json()).put("visual_frame", frame.json())
                } catch (_: Exception) { return stop("gesture_invalid") }
                pendingToken = decision.token; pendingCommand = null
                LocalVisualMotorDecision.Ready(decision.token, decision.stepIndex, action)
            }
            is VisualAnchorDecision.Waiting -> LocalVisualMotorDecision.Waiting(decision.reason)
            is VisualAnchorDecision.Stopped -> stop(decision.reason)
            VisualAnchorDecision.Complete -> LocalVisualMotorDecision.Complete
        }
    }

    /** Bind only to the command the host actually queued for this candidate, after its policy decision. */
    @Synchronized fun bindCommand(token: String, commandId: String): Boolean {
        if (stopped != null || token != pendingToken || pendingCommand != null || commandId.length !in 1..128) return false
        pendingCommand = commandId; return true
    }

    /** Queue acceptance is insufficient; pass true only for the confirmed terminal dispatch result. */
    @Synchronized fun acknowledge(commandId: String, confirmedDispatched: Boolean, completedAtMs: Long): Boolean {
        if (stopped != null || commandId != pendingCommand) return false
        val token = pendingToken ?: return false
        val acknowledged = plan.acknowledge(token, confirmedDispatched, completedAtMs)
        pendingToken = null; pendingCommand = null
        return acknowledged
    }

    @Synchronized fun cancel() { stop("cancelled") }
    private fun stop(reason: String): LocalVisualMotorDecision.Stopped {
        stopped = reason; plan.cancel(); pendingToken = null; pendingCommand = null
        return LocalVisualMotorDecision.Stopped(reason)
    }

    companion object {
        const val TOOL_NAME = "execute_visual_plan"

        /** All bounds/targets use integer pixels in visual_frame.image_width/image_height. */
        fun prepare(proposal: JSONObject, capture: JSONObject, nowMs: Long,
            decoder: (ByteArray) -> VisualAnchorImage? = ::decodePng): LocalVisualMotorPreparation {
            return try {
                require(proposal.toString().length <= 24000)
                keys(proposal, setOf("capture_id", "coordinate_space", "anchors", "steps", "ttl_ms", "max_frame_age_ms"))
                require(proposal.optString("coordinate_space") == "image_pixels")
                val (frame, pixels) = readCapture(capture, decoder)
                require(proposal.getString("capture_id") == frame.captureId && nowMs in frame.capturedAt..frame.expiresAt)
                val anchors = proposal.getJSONArray("anchors")
                require(anchors.length() in 1..16)
                val specs = (0 until anchors.length()).map { index ->
                    val anchor = anchors.getJSONObject(index)
                    keys(anchor, setOf("id", "bounds", "x", "y", "search_radius_px", "context_padding_px"))
                    val bounds = anchor.getJSONArray("bounds"); require(bounds.length() == 4)
                    VisualAnchorSpec(text(anchor, "id", 128), VisualAnchorRect(int(bounds.get(0)), int(bounds.get(1)), int(bounds.get(2)), int(bounds.get(3))),
                        int(anchor.get("x")), int(anchor.get("y")), optionalInt(anchor, "search_radius_px", 24), optionalInt(anchor, "context_padding_px", 8))
                }
                val jsonSteps = proposal.getJSONArray("steps"); require(jsonSteps.length() in 1..8)
                val descriptions = arrayListOf<Description>()
                val steps = (0 until jsonSteps.length()).map { index ->
                    val item = jsonSteps.getJSONObject(index)
                    keys(item, setOf("kind", "start_anchor_id", "end_anchor_id", "duration_ms", "required_anchor_ids", "wait_timeout_ms", "label", "screen_context", "safety"))
                    val safety = text(item, "safety", 20); require(safety in setOf("safe", "uncertain", "payment", "verification", "sensitive"))
                    descriptions += Description(text(item, "label", 240), text(item, "screen_context", 1600), safety)
                    val guards = item.optJSONArray("required_anchor_ids") ?: JSONArray()
                    require(guards.length() <= 16)
                    VisualAnchorStep(text(item, "kind", 20), optionalText(item, "start_anchor_id"), optionalText(item, "end_anchor_id"),
                        int(item.get("duration_ms")).toLong(), (0 until guards.length()).map { guard ->
                            val value = guards.get(guard); require(value is String && value.length in 1..128); value
                        }, optionalInt(item, "wait_timeout_ms", 0).toLong())
                }
                val ttl = optionalInt(proposal, "ttl_ms", 15000).toLong()
                val age = optionalInt(proposal, "max_frame_age_ms", 1500).toLong()
                when (val prepared = VisualAnchorPlan.prepare(frame.anchorFrame(), pixels, specs, steps, nowMs, ttl, age, frame.expiresAt)) {
                    is VisualAnchorPlanPreparation.Prepared -> LocalVisualMotorPreparation.Prepared(LocalVisualMotor(prepared.plan, frame, descriptions, decoder))
                    is VisualAnchorPlanPreparation.Rejected -> LocalVisualMotorPreparation.Rejected(prepared.reason)
                }
            } catch (_: Exception) { LocalVisualMotorPreparation.Rejected("invalid_visual_plan") }
        }

        /** Provider function tool schema. Risk classification is untrusted input passed to host policy. */
        fun tool(frame: VisualFrame): JSONObject {
            fun string() = JSONObject().put("type", "string")
            fun integer(min: Int, max: Int) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
            fun obj(props: JSONObject, required: List<String>) = JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required)).put("additionalProperties", false)
            val anchor = obj(JSONObject().put("id", string()).put("bounds", JSONObject().put("type", "array").put("items", integer(0, maxOf(frame.imageWidth, frame.imageHeight))).put("minItems", 4).put("maxItems", 4))
                .put("x", integer(0, frame.imageWidth - 1)).put("y", integer(0, frame.imageHeight - 1)).put("search_radius_px", integer(0, 96)).put("context_padding_px", integer(0, 32)), listOf("id", "bounds", "x", "y"))
            val step = obj(JSONObject().put("kind", string().put("enum", JSONArray(listOf("tap", "long_press", "swipe", "wait_visible"))))
                .put("start_anchor_id", string()).put("end_anchor_id", string()).put("duration_ms", integer(0, 2000))
                .put("required_anchor_ids", JSONObject().put("type", "array").put("items", string()).put("maxItems", 16))
                .put("wait_timeout_ms", integer(0, 45000)).put("label", string()).put("screen_context", string())
                .put("safety", string().put("enum", JSONArray(listOf("safe", "payment", "verification", "sensitive", "uncertain")))),
                listOf("kind", "duration_ms", "label", "screen_context", "safety"))
            val parameters = obj(JSONObject().put("capture_id", string().put("enum", JSONArray(listOf(frame.captureId))))
                .put("coordinate_space", string().put("enum", JSONArray(listOf("image_pixels"))))
                .put("anchors", JSONObject().put("type", "array").put("items", anchor).put("minItems", 1).put("maxItems", 16))
                .put("steps", JSONObject().put("type", "array").put("items", step).put("minItems", 1).put("maxItems", 8))
                .put("ttl_ms", integer(1, 45000)).put("max_frame_age_ms", integer(1, 5000)), listOf("capture_id", "coordinate_space", "anchors", "steps"))
            return JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL_NAME)
                .put("description", "在当前同一画面规划最多8步短程视觉操作。锚点bounds为当前截图整数像素矩形[左,上,右,下)，宽高12..128，必须包含独特稳定纹理；禁止用空白地块/重复图标作锚点。目标x,y可在锚框外最近边缘128像素内的空地，表示你依据当前画面明确规划的固定偏移，不能越出截图；模板移动时目标同步移动。每步从新截图局部定位，图标消失/遮挡/页面变化会停止重新规划。tap40..200ms、long_press500..2000ms、swipe150..2000ms；摆放/朝向须拆两步swipe，部署格可引用附近不会被干员遮挡的稳定地标。wait_visible仅等待required_anchor_ids可见，duration_ms=0。旧页面的下一页按钮不能编造锚点。宿主仍逐步检查权限，不能授权支付。")
                .put("parameters", parameters))
        }

        private fun VisualFrame.anchorFrame() = VisualAnchorFrame(captureId, sha256, packageName, imageWidth, imageHeight, rotation, capturedAt)
        private fun readCapture(capture: JSONObject, decoder: (ByteArray) -> VisualAnchorImage?): Pair<VisualFrame, VisualAnchorImage> {
            require(capture.optString("mime_type", "image/png") == "image/png")
            val encoded = capture.getString("image_base64"); require(encoded.length in 1..5 * 1024 * 1024)
            val frame = VisualFrame.parse(capture.getJSONObject("visual_frame"))
            require(frame.imageWidth.toLong() * frame.imageHeight <= 8 * 1024 * 1024)
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size >= 45 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
            val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actualSha == frame.sha256.lowercase())
            val pixels = requireNotNull(decoder(bytes)); require(pixels.width == frame.imageWidth && pixels.height == frame.imageHeight)
            return frame to pixels
        }
        private fun decodePng(bytes: ByteArray): VisualAnchorImage? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096 || options.outWidth.toLong() * options.outHeight > 8 * 1024 * 1024) return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return try {
                val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                VisualAnchorImage(bitmap.width, bitmap.height, pixels)
            } finally { bitmap.recycle() }
        }
        private fun keys(value: JSONObject, allowed: Set<String>) { require(value.keys().asSequence().all { it in allowed }) }
        private fun text(value: JSONObject, key: String, max: Int): String = (value.get(key) as? String)?.also { require(it.length in 1..max && it.isNotBlank()) } ?: error("invalid_text")
        private fun optionalText(value: JSONObject, key: String): String? = if (!value.has(key)) null else text(value, key, 128)
        private fun int(value: Any): Int = when (value) {
            is Int -> value
            is Long -> value.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
            else -> error("integer_required")
        }
        private fun optionalInt(value: JSONObject, key: String, default: Int): Int = if (value.has(key)) int(value.get(key)) else default
    }
}
