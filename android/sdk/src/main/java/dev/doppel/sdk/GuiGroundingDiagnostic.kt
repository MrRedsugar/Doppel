package dev.doppel.sdk

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.hypot

/** Per-attempt provenance only. Verified source never means target hit, permission or dispatch. */
internal object GuiGroundingDiagnostic {
    private const val MAX_ENCODED = 6 * 1024 * 1024
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun prepared(request: JSONObject, requestedAt: Long): JSONObject {
        val result = JSONObject().put("status", "prepared").put("requested_at", requestedAt.coerceAtLeast(0))
            .put("kind", request.opt("kind").takeIf { it in setOf("tap", "long_press") } ?: "unknown")
            .put("planner_point_status", if (request.has("planner_point")) "invalid" else "unavailable")
            .put("proves_target_hit", false).put("proves_dispatch", false)
        try {
            val frame = VisualFrame.parse(request.getJSONObject("visual_frame"))
            require(digestPattern.matches(frame.sha256))
            result.put("capture_id", frame.captureId).put("image_sha256", frame.sha256)
                .put("screen_id", frame.screenId).put("package_name", frame.packageName)
                .put("width", frame.imageWidth).put("height", frame.imageHeight)
            request.optJSONObject("planner_point")?.let { point ->
                try { result.put("planner_point", checkedPlannerPoint(frame, point)).put("planner_point_status", "available") }
                catch (_: Exception) { /* Invalid optional planner metadata never invalidates a GUI response. */ }
            }
        } catch (_: Exception) { /* Invalid request metadata must not interrupt task execution. */ }
        return result
    }

    /** Always returns a NEW record: null/invalid responses cannot preserve an earlier successful point. */
    fun from(request: JSONObject, response: JSONObject?, requestedAt: Long, receivedAt: Long): JSONObject {
        val result = prepared(request, requestedAt).put("status", "error").put("received_at", receivedAt.coerceAtLeast(0))
        if (response == null) return result.put("error_code", "unavailable")
        if (response.has("transport_diagnostic")) {
            val failure = GuiGroundingFailure.sanitize(response.optJSONObject("transport_diagnostic"))
            return if (failure == null) result.put("error_code", "invalid_source_or_response")
            else result.put("error_code", failure.getString("code")).put("transport_diagnostic", failure)
        }
        try {
            require(requestedAt >= 0 && receivedAt >= requestedAt)
            require(request.opt("kind") in setOf("tap", "long_press"))
            val frame = VisualFrame.parse(request.getJSONObject("visual_frame"))
            val encoded = request.getString("image_base64")
            require(encoded.length in 1..MAX_ENCODED)
            val body = GuiGroundingProtocol.request(request)
            // Verify the actual request image too; matching a response to an unverified claimed hash is insufficient.
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size >= 45 && pngSignature.indices.all { bytes[it] == pngSignature[it] })
            val header = ByteBuffer.wrap(bytes)
            require(header.getInt(8) == 13 && header.getInt(12) == 0x49484452 &&
                header.getInt(16) == frame.imageWidth && header.getInt(20) == frame.imageHeight)
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == frame.sha256)
            val proposal = GuiGroundingProtocol.proposal(response, frame, request)
            // Commit the verified marker only after the same production protocol accepted source and point.
            result.put("status", if (proposal == null) "not_found" else "point").put("source_verified", true)
            result.put("request_target_chars", body.getString("target").length)
                .put("screen_context_truncated", JSONObject(body.getString("target")).getBoolean("screen_context_truncated"))
            if (proposal != null) {
                result.put("coordinate_space", "image_pixels").put("x", response.get("x")).put("y", response.get("y"))
                result.optJSONObject("planner_point")?.let { point ->
                    result.put("displacement", displacement(frame, point, response.getInt("x"), response.getInt("y")))
                }
            }
            for ((field, limit) in listOf("model" to 200, "revision" to 80)) {
                val value = response.opt(field) as? String ?: continue
                if (value.length in 1..limit && value.none { it.isISOControl() }) result.put(field, value)
            }
            (response.opt("latency_ms") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..60000.0 }
                ?.let { result.put("latency_ms", it) }
        } catch (_: Exception) {
            // Start again rather than keeping any partially written success or exception/body text.
            return prepared(request, requestedAt).put("status", "error").put("received_at", receivedAt.coerceAtLeast(0))
                .put("error_code", "invalid_source_or_response")
        }
        return result
    }

    /** Comparison only: a small distance cannot establish a hit, and a large one does not grant a fallback. */
    fun displacement(frame: VisualFrame, planner: JSONObject, guiX: Int, guiY: Int): JSONObject {
        val point = checkedPlannerPoint(frame, planner)
        require(guiX in 0 until frame.imageWidth && guiY in 0 until frame.imageHeight)
        val dx = guiX - point.getInt("x")
        val dy = guiY - point.getInt("y")
        val distance = hypot(dx.toDouble(), dy.toDouble())
        return JSONObject().put("coordinate_space", "image_pixels").put("dx", dx).put("dy", dy)
            .put("distance_px", distance).put("screen_diagonal_fraction", distance / hypot(frame.imageWidth.toDouble(), frame.imageHeight.toDouble()))
    }

    private fun checkedPlannerPoint(frame: VisualFrame, point: JSONObject): JSONObject {
        require(point.opt("capture_id") == frame.captureId && point.opt("coordinate_space") == "image_pixels")
        fun pixel(key: String, size: Int): Int {
            val value = point.opt(key)
            require(value is Int || value is Long)
            val number = (value as Number).toLong()
            require(number >= 0 && number < size)
            return number.toInt()
        }
        return JSONObject().put("capture_id", frame.captureId).put("coordinate_space", "image_pixels")
            .put("x", pixel("x", frame.imageWidth)).put("y", pixel("y", frame.imageHeight))
    }
}
