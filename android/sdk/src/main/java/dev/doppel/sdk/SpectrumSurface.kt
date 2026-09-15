package dev.doppel.sdk

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/** Small local repaint area; no bitmap capture or software blur is used. */
internal class SpectrumSurface(context: Context, private val perimeter: Boolean = false) : LinearLayout(context) {
    internal var perimeterViewport:Rect?=null
    internal var perimeterDisplayWidth=0
    internal var perimeterDisplayHeight=0
    internal var perimeterFrameTime:Long?=null
    var state: AssistantVisualState = AssistantVisualState.IDLE
        set(value) { if (field != value) { field = value; shader = null; restartMotion() } }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val matrix = Matrix()
    private val border = Path()
    private val highlight = Path()
    private val measure = PathMeasure()
    private var shader: SweepGradient? = null
    private var motionReady = false
    private val clock = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val tick = Runnable { invalidate() }
    private val settings = object : ContentObserver(clock) {
        override fun onChange(selfChange: Boolean) = restartMotion()
    }
    private val powerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = restartMotion()
    }

    init { motionReady = true; setWillNotDraw(false) }
    private fun restartMotion() { clock.removeCallbacks(tick); invalidate() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settings)
        ContextCompat.registerReceiver(context, powerChanges, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        restartMotion()
    }
    override fun onDetachedFromWindow() {
        clock.removeCallbacksAndMessages(null)
        context.contentResolver.unregisterContentObserver(settings)
        context.unregisterReceiver(powerChanges)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (motionReady) restartMotion() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (motionReady) restartMotion() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); shader = null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewport=perimeterViewport.takeIf {perimeter}
        val drawWidth=if(viewport!=null) perimeterDisplayWidth else width
        val drawHeight=if(viewport!=null) perimeterDisplayHeight else height
        val canvasSave=canvas.save()
        if(viewport!=null) canvas.translate(-viewport.left.toFloat(),-viewport.top.toFloat())
        val motion = state.animated && ValueAnimator.areAnimatorsEnabled() && power.isInteractive && !power.isPowerSaveMode
        // Thinking has a slower, breathing sweep; execution keeps the tighter
        // orbit so the two active phases are distinguishable at a glance.
        val period = when (state) {
            AssistantVisualState.RUNNING -> 2600f
            AssistantVisualState.THINKING -> 4200f
            else -> 9000f
        }
        val phase = if (motion) ((perimeterFrameTime ?: SystemClock.uptimeMillis()) % period.toLong()) / period * 360f else 30f
        if (shader == null) {
        val colors = when (state) {
            AssistantVisualState.THINKING -> intArrayOf(0xff8f7cf5.toInt(), 0xffc7a6f4.toInt(), 0xff78c9e8.toInt(), 0xff8f7cf5.toInt())
            AssistantVisualState.PAUSED -> intArrayOf(0xffa6bbcb.toInt(), 0xffd7dee7.toInt(), 0xffacc9c6.toInt(), 0xffa6bbcb.toInt())
            AssistantVisualState.WAITING -> intArrayOf(0xffe3a957.toInt(), 0xfff5d9aa.toInt(), 0xffeda287.toInt(), 0xffe3a957.toInt())
            AssistantVisualState.FAILED, AssistantVisualState.OFFLINE -> intArrayOf(0xffd97485.toInt(), 0xffefd1d9.toInt(), 0xffbd8cb2.toInt(), 0xffd97485.toInt())
            AssistantVisualState.COMPLETE -> intArrayOf(0xff3ba790.toInt(), 0xff9ddcd1.toInt(), 0xff65bce3.toInt(), 0xff3ba790.toInt())
            else -> intArrayOf(0xff5d8fff.toInt(), 0xff74d8ed.toInt(), 0xffb3b2f5.toInt(), 0xfff0a0c6.toInt(), 0xff5d8fff.toInt())
        }
        shader = SweepGradient(drawWidth / 2f, drawHeight / 2f, colors, null)
        }
        matrix.setRotate(phase, drawWidth / 2f, drawHeight / 2f); shader!!.setLocalMatrix(matrix)
        paint.shader = shader; paint.style = Paint.Style.STROKE
        if (perimeter) {
            val core = FeedbackMotion.edgeWidth(density, minOf(drawWidth, drawHeight)) * 0.66f
            val inset = core / 2f + density
            frame.set(inset, inset, drawWidth - inset, drawHeight - inset)
            for (layer in 3 downTo 1) {
                paint.strokeWidth = core + layer * 2.4f * density
                paint.alpha = 5 + (3 - layer) * 5
                canvas.drawRoundRect(frame, 22 * density, 22 * density, paint)
            }
            paint.strokeWidth = core; paint.alpha = 160
            canvas.drawRoundRect(frame, 22 * density, 22 * density, paint)
            // A short light travels along the physical screen edge; the rest stays quiet.
            border.reset(); border.addRoundRect(frame, 22 * density, 22 * density, Path.Direction.CW)
            measure.setPath(border, false)
            val length = measure.length
            val start = phase / 360f * length
            val span = length * 0.16f
            highlight.reset(); measure.getSegment(start, minOf(length, start + span), highlight, true)
            if (start + span > length) measure.getSegment(0f, start + span - length, highlight, true)
            paint.strokeWidth = core * 0.7f; paint.strokeCap = Paint.Cap.ROUND; paint.alpha = 255
            canvas.drawPath(highlight, paint)
        } else {
            frame.set(2 * density, 2 * density, width - 2 * density, height - 2 * density)
            val shell = background as? EdgeDockDrawable
            val shellSave = canvas.save()
            shell?.clipToContour(canvas)
            fun drawShell() {
                if (shell != null) shell.drawContour(canvas, paint)
                else canvas.drawRoundRect(frame, height / 2f, height / 2f, paint)
            }
            paint.style = Paint.Style.FILL; paint.alpha = if (state == AssistantVisualState.IDLE) 32 else 42
            drawShell()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4 * density; paint.alpha = 26
            drawShell()
            paint.strokeWidth = 1.5f * density; paint.alpha = if (state == AssistantVisualState.IDLE) 180 else 220
            drawShell()
            canvas.restoreToCount(shellSave)
            if (state == AssistantVisualState.RUNNING || state == AssistantVisualState.THINKING) {
                val radius = 17 * density; val x = 29 * density; val y = height / 2f
                frame.set(x - radius, y - radius, x + radius, y + radius)
                paint.strokeWidth = (if (state == AssistantVisualState.THINKING) 2.2f else 1.8f) * density
                paint.strokeCap = Paint.Cap.ROUND
                val span = if (state == AssistantVisualState.THINKING) 72f else 100f
                val pulse = if (state == AssistantVisualState.THINKING)
                    (0.78f + 0.22f * kotlin.math.sin(phase * Math.PI / 180.0)).toFloat() else 1f
                paint.alpha = (255f * pulse).toInt().coerceIn(0, 255)
                canvas.drawArc(frame, phase, span, false, paint)
                paint.alpha = (if (state == AssistantVisualState.THINKING) 110 else 75)
                canvas.drawArc(frame, phase + if (state == AssistantVisualState.THINKING) 145f else 180f,
                    if (state == AssistantVisualState.THINKING) 42f else 65f, false, paint)
            }
        }
        paint.shader = null; paint.alpha = 255
        canvas.restoreToCount(canvasSave)
        if (perimeterFrameTime==null && motion && alpha > 0f && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE) {
            clock.removeCallbacks(tick)
            clock.postDelayed(tick, if (state == AssistantVisualState.RUNNING || state == AssistantVisualState.THINKING) 33 else 66)
        }
    }
}
