package dev.doppel.sdk

internal data class EdgeDockPoint(val x: Float, val y: Float)

internal sealed interface EdgeDockSegment {
    val end: EdgeDockPoint
    data class Line(override val end: EdgeDockPoint) : EdgeDockSegment
    data class Cubic(val control1: EdgeDockPoint, val control2: EdgeDockPoint, override val end: EdgeDockPoint) : EdgeDockSegment
}

internal data class EdgeDockContour(val start: EdgeDockPoint, val segments: List<EdgeDockSegment>)

/** A capsule grows concave shoulders inside its existing window as it reaches either edge. */
internal object EdgeDockGeometry {
    private const val CIRCLE_CONTROL = 0.55228475f

    fun create(width: Float, height: Float, joinRadius: Float, rightEdge: Boolean, attachment: Float): EdgeDockContour? {
        if (!width.isFinite() || !height.isFinite() || !joinRadius.isFinite() || !attachment.isFinite() ||
            width <= 0f || height <= 0f) return null
        val amount = attachment.coerceIn(0f, 1f)
        val baseRadius = minOf(width, height) / 2f
        val join = joinRadius.coerceIn(0f, minOf(width / 4f, height / 4f))
        val inset = join * amount
        val outerRadius = minOf(width / 2f, height / 2f - inset)
        val sideTop = baseRadius * (1f - amount)
        val shoulderWidth = baseRadius * (1f - amount) + join * amount
        val shoulderControlY = baseRadius * (1f - CIRCLE_CONTROL) * (1f - amount) + join * CIRCLE_CONTROL * amount
        val outerControl = outerRadius * (1f - CIRCLE_CONTROL)
        fun point(x: Float, y: Float) = EdgeDockPoint(if (rightEdge) x else width - x, y)
        val start = point(width, sideTop)
        return EdgeDockContour(start, listOf(
            EdgeDockSegment.Cubic(point(width, shoulderControlY),
                point(width - shoulderWidth * (1f - CIRCLE_CONTROL), inset), point(width - shoulderWidth, inset)),
            EdgeDockSegment.Line(point(outerRadius, inset)),
            EdgeDockSegment.Cubic(point(outerControl, inset), point(0f, inset + outerControl), point(0f, inset + outerRadius)),
            EdgeDockSegment.Line(point(0f, height - inset - outerRadius)),
            EdgeDockSegment.Cubic(point(0f, height - inset - outerControl), point(outerControl, height - inset),
                point(outerRadius, height - inset)),
            EdgeDockSegment.Line(point(width - shoulderWidth, height - inset)),
            EdgeDockSegment.Cubic(point(width - shoulderWidth * (1f - CIRCLE_CONTROL), height - inset),
                point(width, height - shoulderControlY), point(width, height - sideTop)),
            EdgeDockSegment.Line(start)
        ))
    }

    fun attachment(x: Float, width: Float, screenWidth: Float, rightEdge: Boolean, distance: Float): Float {
        if (!x.isFinite() || !width.isFinite() || !screenWidth.isFinite() || !distance.isFinite() ||
            width <= 0f || screenWidth <= 0f || distance <= 0f) return 0f
        val gap = if (rightEdge) screenWidth - width - x else x
        return (1f - gap.coerceAtLeast(0f) / distance).coerceIn(0f, 1f)
    }
}
