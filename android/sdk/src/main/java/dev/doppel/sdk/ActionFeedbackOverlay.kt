package dev.doppel.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.RectF
import android.animation.ValueAnimator
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.sqrt

internal class ActionFeedbackOverlay(private val context: Context) {
    // Capture cleanup must run even behind a stalled ViewRoot sync barrier.
    private val handler = androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val generation = AtomicLong()
    private val captureCleanup = java.util.concurrent.atomic.AtomicReference<TouchHandoffDiagnostic?>()
    private var view: FeedbackView? = null
    private var messageView: View? = null
    @Volatile var visible = false
        private set

    fun begin(geometry: ActionFeedbackGeometry): Long {
        val token = generation.incrementAndGet()
        handler.post {
            if (generation.get() != token) return@post
            if(!removeView()) return@post
            val indicator = FeedbackView(context, geometry)
            val params = WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            try { TemporaryScreenshotExclusion.addView(manager, indicator, params); view = indicator; visible = true } catch (_: Exception) { visible = false }
            handler.postDelayed({ if (generation.get() == token) removeView() }, maxOf(1100,geometry.durationMs+400))
        }
        return token
    }

    fun finish(token: Long, accepted: Boolean) {
        handler.postDelayed({
            if (generation.get() == token) view?.let { it.accepted = accepted; it.invalidate() }
        }, 120)
    }

    fun message(text: String) {
        val token = generation.incrementAndGet()
        handler.post {
            if (generation.get() != token) return@post
            if(!removeView()) return@post
            val toast = UiTheme.text(context, text, 14f, UiTheme.ink, true).apply {
                background = UiTheme.glass(context, 22)
                setPadding(UiTheme.dp(context, 20), UiTheme.dp(context, 14), UiTheme.dp(context, 20), UiTheme.dp(context, 14))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = UiTheme.dp(context, 88)
            }
            try {
                TemporaryScreenshotExclusion.addView(manager, toast, params); messageView = toast; visible = true
                if (ValueAnimator.areAnimatorsEnabled()) { toast.alpha = 0f; toast.translationY = UiTheme.dp(context, 8).toFloat(); toast.animate().alpha(1f).translationY(0f).setDuration(160).start() }
            } catch (_: Exception) { visible = false }
            handler.postDelayed({ if (generation.get() == token) removeView() }, 1800)
        }
    }

    fun clear() {
        val token = generation.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) removeView() else handler.post { if (generation.get() == token) removeView() }
    }

    fun clearBeforeScreenshot(): Boolean {
        val token=generation.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) { removeView(); return false }
        val diagnostic=TouchHandoffDiagnostic(android.os.SystemClock::elapsedRealtime,1000)
        captureCleanup.set(diagnostic)
        val gate=TouchHandoffGate(timeoutMs=1000)
        val abandoned=java.util.concurrent.atomic.AtomicBoolean(false)
        val posted=handler.post {
            diagnostic.mark("main_entered")
            if(abandoned.get() || generation.get()!=token) {diagnostic.finish("stale_host");gate.reject();return@post}
            diagnostic.mark("layout_started")
            if(!removeView()) {diagnostic.finish("layout_failed");gate.reject();return@post}
            diagnostic.mark("layout_applied");diagnostic.mark("main_acknowledged")
            // The removed surfaces are detached now. Pay the compositor allowance
            // on this caller, rather than behind another full-screen main draw.
            gate.applied()
        }
        if(!posted) {diagnostic.finish("queue_rejected");return false}
        val result=try {gate.await({!abandoned.get() && generation.get()==token}) {diagnostic.mark("compositor_wait_started")}}
            catch (_:InterruptedException) {diagnostic.finish("interrupted");Thread.currentThread().interrupt();TouchHandoffGate.Result.CANCELLED}
        abandoned.set(true)
        if(result==TouchHandoffGate.Result.READY) {
            diagnostic.mark("compositor_wait_finished");diagnostic.mark("ready");diagnostic.finish("ready")
        } else diagnostic.finish(if(result==TouchHandoffGate.Result.TIMED_OUT) "timeout" else "stale_host")
        android.util.Log.i("DoppelFeedbackCleanup",diagnostic.snapshot().toString())
        return result==TouchHandoffGate.Result.READY
    }
    internal fun captureCleanupDiagnostic():org.json.JSONObject=captureCleanup.get()?.snapshot()?:org.json.JSONObject()

    private fun removeView():Boolean {
        view?.let { current ->
            try { manager.removeViewImmediate(current) } catch (_: Exception) {}
            if(!current.isAttachedToWindow) view=null
        }
        messageView?.let { current ->
            current.animate().cancel();try { manager.removeViewImmediate(current) } catch (_: Exception) {}
            if(!current.isAttachedToWindow) messageView=null
        }
        visible=view!=null || messageView!=null
        return !visible
    }

    private class FeedbackView(context: Context, private val geometry: ActionFeedbackGeometry) : View(context) {
        var accepted: Boolean? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val startedAt = android.os.SystemClock.uptimeMillis()
        private val ring = RectF()
        private val origin = IntArray(2)
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false; contentDescription = null
            setWillNotDraw(false)
        }
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(origin)
            canvas.save(); canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
            val bounds = geometry.bounds
            // A small icon still gets a full-size halo; only the screen clips feedback.
            val accent = when (accepted) { true -> Color.rgb(48, 161, 151); false -> Color.rgb(207, 85, 113); null -> Color.rgb(105, 132, 231) }
            paint.strokeCap = Paint.Cap.ROUND
            val center = geometry.center
            val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
            val motion = ValueAnimator.areAnimatorsEnabled()
            val phase = if (motion) elapsed.coerceIn(0, 650) / 650f else 0.6f
            val radius = FeedbackMotion.radius(density, minOf(width, height))
            val fade = if (motion) (1f - ((elapsed - maxOf(780,geometry.durationMs+100)).coerceAtLeast(0) / 300f)).coerceIn(0f, 1f) else 1f
            paint.shader = LinearGradient(bounds[0].toFloat(), bounds[1].toFloat(), bounds[2].toFloat(), bounds[3].toFloat(),
                intArrayOf(Color.rgb(63, 155, 244), Color.rgb(75, 202, 210), Color.rgb(231, 137, 188)), null, Shader.TileMode.CLAMP)
            paint.style = Paint.Style.STROKE
            val start = geometry.start; val end = geometry.end
            if (start != null && end != null) {
                val travel = if (motion) (elapsed.toFloat()/geometry.durationMs.coerceAtLeast(1)).coerceIn(0f,1f) else 1f
                val points=geometry.path.takeIf {it.size>1} ?: listOf(start,end)
                val lengths=points.zipWithNext {a,b -> kotlin.math.hypot(b.x-a.x,b.y-a.y)}
                var remaining=lengths.sum()*travel
                val trail=android.graphics.Path().apply {moveTo(start.x,start.y)}
                var cursor=start
                for(index in lengths.indices) {
                    val a=points[index];val b=points[index+1];val length=lengths[index]
                    val part=if(length>0) (remaining/length).coerceIn(0f,1f) else 1f
                    cursor=FeedbackPoint(a.x+(b.x-a.x)*part,a.y+(b.y-a.y)*part)
                    trail.lineTo(cursor.x,cursor.y);remaining-=length
                    if(remaining<=0) break
                }
                val cursorX=cursor.x;val cursorY=cursor.y
                paint.strokeWidth = 10f * density; paint.alpha = (26 * fade).toInt()
                canvas.drawPath(trail, paint)
                paint.strokeWidth = 2.4f * density; paint.alpha = (220 * fade).toInt()
                canvas.drawPath(trail, paint)
                val dx = end.x - start.x; val dy = end.y - start.y
                val length = sqrt(dx * dx + dy * dy)
                val tip = min(9f * density, length / 4f)
                val ux = if (length > 0) dx / length else 0f; val uy = if (length > 0) dy / length else 0f
                canvas.drawLine(end.x, end.y, end.x - ux * tip - uy * tip / 2, end.y - uy * tip + ux * tip / 2, paint)
                canvas.drawLine(end.x, end.y, end.x - ux * tip + uy * tip / 2, end.y - uy * tip - ux * tip / 2, paint)
                canvas.drawCircle(start.x, start.y, min(radius / 3, 4f * density), paint)
                paint.style = Paint.Style.FILL; paint.alpha = (35 * fade).toInt()
                canvas.drawCircle(cursorX, cursorY, radius * 0.7f, paint)
                paint.shader = null; paint.color = Color.WHITE; paint.alpha = (250 * fade).toInt()
                canvas.drawCircle(cursorX, cursorY, 8f * density, paint)
                paint.color = accent
                canvas.drawCircle(cursorX, cursorY, 4f * density, paint)
            } else {
                val pulseRadius = radius * if (motion) FeedbackMotion.pressScale(elapsed, geometry.kind == "long_press") else 1f
                paint.style = Paint.Style.FILL; paint.alpha = (26 * fade).toInt()
                canvas.drawCircle(center.x, center.y, pulseRadius, paint)
                // Dark and light layers preserve the exact target over both dark games and white pages.
                paint.shader = null; paint.color = Color.rgb(36, 45, 70)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.6f * density; paint.alpha = (45 * fade).toInt()
                canvas.drawCircle(center.x, center.y, pulseRadius, paint)
                paint.color = accent; paint.strokeWidth = 2.1f * density; paint.alpha = (235 * fade).toInt()
                canvas.drawCircle(center.x, center.y, pulseRadius, paint)
                paint.strokeWidth = 5f * density; paint.alpha = (18 * fade).toInt()
                canvas.drawCircle(center.x, center.y, pulseRadius + 4 * density, paint)
                if (geometry.kind == "long_press") {
                    ring.set(center.x - radius * 0.64f, center.y - radius * 0.64f, center.x + radius * 0.64f, center.y + radius * 0.64f)
                    paint.alpha = (240 * fade).toInt(); paint.strokeWidth = 2.2f * density
                    canvas.drawArc(ring, -90f, 360f * phase, false, paint)
                }
                paint.shader = null
                if (accepted == false) {
                    paint.color = accent; paint.style = Paint.Style.STROKE
                    canvas.drawLine(center.x - radius / 2, center.y - radius / 2, center.x + radius / 2, center.y + radius / 2, paint)
                    canvas.drawLine(center.x + radius / 2, center.y - radius / 2, center.x - radius / 2, center.y + radius / 2, paint)
                }
            }
            // Keep the exact validated target legible while the surrounding accent moves.
            if (start == null) {
                paint.shader = null; paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = (250 * fade).toInt()
                canvas.drawCircle(center.x, center.y, 7f * density, paint)
                paint.color = accent
                canvas.drawCircle(center.x, center.y, 3.5f * density, paint)
            }
            canvas.restore()
            if (motion && elapsed < 1100) postInvalidateOnAnimation()
        }
    }
}
