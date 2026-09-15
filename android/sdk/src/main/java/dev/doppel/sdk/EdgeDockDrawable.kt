package dev.doppel.sdk

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** The entry owns this contour; other glass panels keep their existing geometry. */
internal class EdgeDockDrawable(private val density: Float) : Drawable() {
    private val contour = Path()
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rightEdge = true
    private var attachment = 1f
    private var palette: ThemePalette? = null

    fun setAttachment(onRight: Boolean, amount: Float) {
        val fraction = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        if (rightEdge == onRight && attachment == fraction) return
        rightEdge = onRight; attachment = fraction
        rebuildContour(); invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        rebuildContour()
        updateColors()
    }

    private fun rebuildContour() {
        contour.rewind()
        val shape = EdgeDockGeometry.create(bounds.width().toFloat(), bounds.height().toFloat(),
            8f * density, rightEdge, attachment) ?: return
        contour.moveTo(shape.start.x + bounds.left, shape.start.y + bounds.top)
        for (segment in shape.segments) when (segment) {
            is EdgeDockSegment.Line -> contour.lineTo(segment.end.x + bounds.left, segment.end.y + bounds.top)
            is EdgeDockSegment.Cubic -> contour.cubicTo(segment.control1.x + bounds.left, segment.control1.y + bounds.top,
                segment.control2.x + bounds.left, segment.control2.y + bounds.top,
                segment.end.x + bounds.left, segment.end.y + bounds.top)
        }
        contour.close()
    }

    private fun updateColors() {
        val current = ThemeController.palette
        palette = current
        face.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), intArrayOf(
                ThemePalette.blendColor(current.surface, current.spectrumCyan, 0.24f),
                ThemePalette.blendColor(current.surface, current.spectrumBlue, 0.10f),
                ThemePalette.blendColor(current.surface, current.spectrumPink, 0.20f)), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (palette !== ThemeController.palette) updateColors()
        canvas.drawPath(contour, face)
    }

    /** The caller saves/restores Canvas so state light and face have the exact same silhouette. */
    fun clipToContour(canvas: Canvas) { canvas.clipPath(contour) }
    fun drawContour(canvas: Canvas, paint: Paint) { canvas.drawPath(contour, paint) }

    // Concave outlines cannot cast a correct platform elevation shadow on API 28.
    override fun getOutline(outline: Outline) { outline.setEmpty() }
    override fun setAlpha(alpha: Int) { face.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { face.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Platform Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
