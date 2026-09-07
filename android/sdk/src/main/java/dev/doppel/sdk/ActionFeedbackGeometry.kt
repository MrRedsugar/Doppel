package dev.doppel.sdk

data class FeedbackPoint(val x: Float, val y: Float)

data class ActionFeedbackGeometry(
    val kind: String,
    val bounds: List<Int>,
    val center: FeedbackPoint,
    val start: FeedbackPoint? = null,
    val end: FeedbackPoint? = null
) {
    companion object {
        fun create(kind: String, bounds: List<Int>, width: Int, height: Int, direction: String = ""): ActionFeedbackGeometry? {
            if (bounds.size != 4 || width <= 0 || height <= 0) return null
            val left = bounds[0].coerceIn(0, width); val top = bounds[1].coerceIn(0, height)
            val right = bounds[2].coerceIn(0, width); val bottom = bounds[3].coerceIn(0, height)
            if (left >= right || top >= bottom) return null
            val center = FeedbackPoint((left + right) / 2f, (top + bottom) / 2f)
            val visible = listOf(left, top, right, bottom)
            if (kind != "scroll") return ActionFeedbackGeometry(kind, visible, center)
            val dx = (right - left) * 0.28f; val dy = (bottom - top) * 0.28f
            val offset = when (direction) {
                "up" -> FeedbackPoint(0f, -dy)
                "down" -> FeedbackPoint(0f, dy)
                "left" -> FeedbackPoint(-dx, 0f)
                "right" -> FeedbackPoint(dx, 0f)
                else -> return null
            }
            return ActionFeedbackGeometry(kind, visible, center,
                FeedbackPoint(center.x - offset.x, center.y - offset.y),
                FeedbackPoint(center.x + offset.x, center.y + offset.y))
        }
    }
}
