package dev.doppel.sdk

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

/** Indeterminate task/listening state, never a fabricated progress or sound level. */
class UiActivitySignal(context: Context, private val form: Form = Form.ORBIT) : View(context) {
    enum class Form { ORBIT, LINE, VOICE }
    var active = true
        set(value) { if (field != value) { field = value; updateMotion(); invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var motionReady = false
    private var observing = false
    private val clock = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val settings = object : ContentObserver(clock) {
        override fun onChange(selfChange: Boolean) { updateMotion(); invalidate() }
    }
    private val powerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateMotion(); invalidate() }
    }

    init { motionReady = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(UiTheme.spectrumCyan, UiTheme.spectrumBlue, UiTheme.spectrumPink), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        paint.alpha = if (active) 230 else 100
        paint.strokeCap = Paint.Cap.ROUND
        when (form) {
            Form.ORBIT -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
                val edge = 2f * density
                val bounds = RectF(edge, edge, width - edge, height - edge)
                canvas.drawArc(bounds, phase * 360f - 90f, 240f, false, paint)
                paint.alpha = 50; canvas.drawArc(bounds, phase * 360f + 160f, 90f, false, paint)
            }
            Form.LINE -> {
                paint.style = Paint.Style.FILL
                paint.alpha = 40; canvas.drawRoundRect(0f, height * 0.3f, width.toFloat(), height * 0.7f, height.toFloat(), height.toFloat(), paint)
                paint.alpha = if (active) 200 else 90
                val segment = width * 0.3f
                val left = (width - segment) * (0.5f + 0.5f * sin(phase * 2f * PI).toFloat())
                canvas.drawRoundRect(left, height * 0.15f, left + segment, height * 0.85f, height.toFloat(), height.toFloat(), paint)
            }
            Form.VOICE -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * density
                val count = 17
                val step = minOf(8f * density, width / (count + 1f))
                val start = (width - (count - 1) * step) / 2f
                repeat(count) { index ->
                    val envelope = sin((index + 1f) / (count + 1f) * PI).toFloat()
                    val wave = if (active) (0.3f + 0.7f * kotlin.math.abs(sin(phase * 2f * PI + index * 0.55f)).toFloat()) else 0.22f
                    val extent = (height * 0.36f * envelope * wave).coerceAtLeast(density)
                    canvas.drawLine(start + index * step, height / 2f - extent, start + index * step, height / 2f + extent, paint)
                }
            }
        }
    }

    private fun updateMotion() {
        if (!motionReady) return
        val shouldRun = active && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled() && power.isInteractive && !power.isPowerSaveMode
        if (shouldRun && animator == null) animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (form == Form.VOICE) 1400 else 2000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; if (!ValueAnimator.areAnimatorsEnabled() || power.isPowerSaveMode || !power.isInteractive) updateMotion() else invalidate() }
            start()
        } else if (!shouldRun) { animator?.cancel(); animator = null }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settings)
        ContextCompat.registerReceiver(context, powerChanges, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        observing = true; updateMotion()
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        if (observing) { context.contentResolver.unregisterContentObserver(settings); context.unregisterReceiver(powerChanges); observing = false }
        clock.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateMotion() }
}
