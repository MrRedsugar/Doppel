package dev.doppel.sdk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Separate translucent fill and perimeter preserve the window's real backdrop blur. */
class UiGlassDrawable(private val radius: Float, private val edgeWidth: Float) : Drawable() {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = edgeWidth }
    private val outline = RectF()
    private var palette: ThemePalette? = null
    override fun onBoundsChange(bounds: Rect) {
        outline.set(bounds); outline.inset(edgeWidth / 2f, edgeWidth / 2f)
        updateColors()
    }
    private fun updateColors() {
        val current = ThemeController.palette
        palette = current
        fun tint(accent: Int, amount: Float, alpha: Int): Int {
            val color = ThemePalette.blendColor(current.surface, accent, amount)
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
        face.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(tint(current.spectrumBlue, 0.025f, 242), tint(current.surface, 0f, 232), tint(current.spectrumPink, 0.025f, 240)), null, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(ThemePalette.blendColor(current.line, current.spectrumBlue, 0.18f), current.line, ThemePalette.blendColor(current.line, current.spectrumPink, 0.14f)), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        if (palette !== ThemeController.palette) updateColors()
        canvas.drawRoundRect(outline, radius, radius, face); canvas.drawRoundRect(outline, radius, radius, edge)
    }
    override fun getOutline(value: Outline) { value.setRoundRect(bounds, radius) }
    override fun setAlpha(alpha: Int) { face.alpha = alpha; edge.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { face.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Platform Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
