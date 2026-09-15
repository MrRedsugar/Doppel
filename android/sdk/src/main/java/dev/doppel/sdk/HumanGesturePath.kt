package dev.doppel.sdk

import android.graphics.Path
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.random.Random

/** Generates the original small, smooth arc for a gesture. */
internal object HumanGesturePath {
    private fun point(x: Float, y: Float) = PointF().apply { this.x = x; this.y = y }
    fun samples(start: PointF, end: PointF, width: Int, height: Int, seed: Long = System.nanoTime()): List<PointF> {
        require(width > 0 && height > 0)
        val right = (width - 1).toFloat(); val bottom = (height - 1).toFloat()
        require(listOf(start, end).all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..right && it.y in 0f..bottom })
        fun control(x: Float, y: Float) = point(x.coerceIn(0f, right), y.coerceIn(0f, bottom))
        val dx = end.x - start.x; val dy = end.y - start.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance < 1f) return listOf(point(start.x, start.y), point(end.x, end.y))
        val nx = -dy / distance; val ny = dx / distance; val random = Random(seed)
        val amplitude = (distance * (0.012f + random.nextFloat() * 0.018f)).coerceAtMost(22f)
        val bow = if (random.nextBoolean()) amplitude else -amplitude
        // A Bezier curve stays in its control points' convex hull. Keep the entire
        // path inside the display, including gestures whose endpoints lie on an edge.
        val c1 = control(start.x + dx * .32f + nx * bow, start.y + dy * .32f + ny * bow)
        val c2 = control(start.x + dx * .68f + nx * bow, start.y + dy * .68f + ny * bow)
        val count = (distance / 36f).toInt().coerceIn(12, 28)
        val result = ArrayList<PointF>(count + 1)
        result.add(point(start.x, start.y))
        for (i in 1 until count - 1) {
            val t = i.toFloat() / (count - 1).toFloat(); val u = 1f - t
            result.add(control(u*u*u*start.x + 3f*u*u*t*c1.x + 3f*u*t*t*c2.x + t*t*t*end.x, u*u*u*start.y + 3f*u*u*t*c1.y + 3f*u*t*t*c2.y + t*t*t*end.y))
        }
        result.add(point(end.x, end.y))
        return result
    }
    fun path(start: PointF, end: PointF, width: Int, height: Int): Path = Path().apply {
        val points = samples(start, end, width, height); moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) }
    }
}
