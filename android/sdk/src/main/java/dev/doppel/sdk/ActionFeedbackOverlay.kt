package dev.doppel.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.sqrt

internal class ActionFeedbackOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val generation = AtomicLong()
    private var view: FeedbackView? = null
    @Volatile var visible = false
        private set

    fun begin(geometry: ActionFeedbackGeometry): Long {
        val token = generation.incrementAndGet()
        handler.post {
            if (generation.get() != token) return@post
            removeView()
            val indicator = FeedbackView(context, geometry)
            val params = WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
            try { manager.addView(indicator, params); view = indicator; visible = true } catch (_: Exception) { visible = false }
            handler.postDelayed({ if (generation.get() == token) removeView() }, 900)
        }
        return token
    }

    fun finish(token: Long, accepted: Boolean) {
        handler.postDelayed({
            if (generation.get() == token) view?.let { it.accepted = accepted; it.invalidate() }
        }, 120)
    }

    fun clear() {
        val token = generation.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) removeView() else handler.post { if (generation.get() == token) removeView() }
    }

    fun clearBeforeScreenshot(): Boolean {
        generation.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) { removeView(); return false }
        val removed = CountDownLatch(1)
        handler.post {
            removeView()
            // Wait for removal to reach a rendered frame before capturing the display.
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { removed.countDown() }
            }
        }
        return removed.await(1, TimeUnit.SECONDS)
    }

    private fun removeView() {
        view?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) {} }
        view = null; visible = false
    }

    private class FeedbackView(context: Context, private val geometry: ActionFeedbackGeometry) : View(context) {
        var accepted: Boolean? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val startedAt = android.os.SystemClock.uptimeMillis()
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false; contentDescription = null
            setWillNotDraw(false)
        }
        override fun onDraw(canvas: Canvas) {
            val origin = IntArray(2); getLocationOnScreen(origin)
            canvas.save(); canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
            val bounds = geometry.bounds
            canvas.clipRect(bounds[0], bounds[1], bounds[2], bounds[3])
            paint.color = when (accepted) { true -> Color.rgb(0, 153, 122); false -> Color.rgb(208, 55, 70); null -> Color.rgb(216, 159, 30) }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
            val center = geometry.center
            val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
            val ripple = 0.65f + 0.35f * (elapsed.coerceIn(0, 450) / 450f)
            val radius = min(13f * density, min(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 3f) * ripple
            val start = geometry.start; val end = geometry.end
            if (start != null && end != null) {
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                val dx = end.x - start.x; val dy = end.y - start.y
                val length = sqrt(dx * dx + dy * dy)
                val tip = min(9f * density, length / 4f)
                val ux = dx / length; val uy = dy / length
                canvas.drawLine(end.x, end.y, end.x - ux * tip - uy * tip / 2, end.y - uy * tip + ux * tip / 2, paint)
                canvas.drawLine(end.x, end.y, end.x - ux * tip + uy * tip / 2, end.y - uy * tip - ux * tip / 2, paint)
                canvas.drawCircle(start.x, start.y, min(radius / 3, 4f * density), paint)
            } else {
                canvas.drawCircle(center.x, center.y, radius, paint)
                if (geometry.kind == "long_press") canvas.drawCircle(center.x, center.y, radius * 0.6f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(center.x, center.y, min(radius / 4, 3f * density), paint)
                if (accepted == false) {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(center.x - radius / 2, center.y - radius / 2, center.x + radius / 2, center.y + radius / 2, paint)
                }
            }
            canvas.restore()
            if (elapsed < 900) postInvalidateOnAnimation()
        }
    }
}
