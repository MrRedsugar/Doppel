package dev.doppel.sdk

import org.json.JSONObject

/** Current-image model coordinates only. The device wire remains a normalized VisualGesture. */
internal object DirectGroundingPixels {
    private val keys = setOf("capture_id", "x", "y", "end_x", "end_y", "duration_ms", "label", "screen_context", "safety")

    fun proposalSchema(toolName: String, frame: VisualFrame): JSONObject {
        val schema = VisualGesture.proposalSchema(toolName, frame.captureId)
        val properties = schema.getJSONObject("properties")
        for ((key, size) in dimensions(frame)) if (properties.has(key)) {
            properties.put(key, JSONObject().put("type", "integer").put("minimum", 0).put("maximum", size - 1))
        }
        return schema
    }

    fun parseProposal(toolName: String, value: JSONObject, frame: VisualFrame): VisualGesture {
        if (toolName !in VisualGesture.proposalToolNames) throw VisualValidationException(VisualValidationReason.KIND)
        if (value.keys().asSequence().any { it !in keys }) throw VisualValidationException(VisualValidationReason.UNKNOWN_FIELD)
        if (toolName != "propose_swipe" && (value.has("end_x") || value.has("end_y"))) {
            throw VisualValidationException(VisualValidationReason.TAP_ENDPOINT)
        }
        val coordinates = dimensions(frame).filterKeys { it in setOf("x", "y") || toolName == "propose_swipe" }
            .mapValues { (key, size) -> pixel(value, key, size).toDouble() / size }
        val normalized = JSONObject(value.toString())
        for ((key, coordinate) in coordinates) normalized.put(key, coordinate)
        // Keep shape, durations, safety classification and the host-owned device representation unchanged.
        return VisualGesture.parseProposal(toolName, normalized)
    }

    private fun dimensions(frame: VisualFrame) = linkedMapOf("x" to frame.imageWidth, "y" to frame.imageHeight,
        "end_x" to frame.imageWidth, "end_y" to frame.imageHeight)

    private fun pixel(value: JSONObject, key: String, size: Int): Long {
        val number = value.opt(key) as? Number ?: throw VisualValidationException(VisualValidationReason.COORDINATE_TYPE, key)
        if (!number.toDouble().isFinite()) throw VisualValidationException(VisualValidationReason.PIXEL_COORDINATE_RANGE, key)
        val integer = when (number) {
            is Int -> number.toLong()
            is Long -> number
            else -> throw VisualValidationException(VisualValidationReason.INTEGER, key)
        }
        if (integer < 0 || integer >= size) throw VisualValidationException(VisualValidationReason.PIXEL_COORDINATE_RANGE, key)
        return integer
    }
}
