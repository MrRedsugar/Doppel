package dev.doppel.sdk

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import java.lang.ref.WeakReference
import kotlin.math.hypot

/** One palette for activities, dialogs and overlay views in this application process. */
object ThemeController {
    private var application: Application? = null
    private var foreground = WeakReference<Activity>(null)
    private var animator: ValueAnimator? = null
    private var resolvedDark = false
    internal var palette = ThemePalette.light; private set
    val isDark: Boolean get() = resolvedDark

    fun mode(context: Context): ThemeMode = ThemeMode.parse(context.getSharedPreferences("doppel_ui", 0).getString("appearance", null))
    fun initialize(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (application === app) return
        application = app
        resolvedDark = mode(app).isDark(systemDark(app.resources.configuration))
        palette = if (resolvedDark) ThemePalette.dark else ThemePalette.light
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity); refreshSystem(activity) }
            override fun onActivityPaused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
        })
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(configuration: Configuration) {
                if (mode(app) == ThemeMode.SYSTEM) change(foreground.get(), systemDark(configuration), null)
            }
            override fun onLowMemory() {
                animator?.cancel(); animator = null
                palette = if (resolvedDark) ThemePalette.dark else ThemePalette.light
                UiTheme.refreshBindings()
            }
        })
    }

    fun refreshSystem(context: Context) {
        initialize(context)
        val dark = mode(context).isDark(systemDark(context.resources.configuration))
        if (dark != resolvedDark) change(foreground.get(), dark, null)
    }

    fun select(activity: Activity, mode: ThemeMode, source: View? = null) {
        initialize(activity)
        activity.getSharedPreferences("doppel_ui", 0).edit().putString("appearance", mode.name).apply()
        change(activity, mode.isDark(systemDark(activity.resources.configuration)), source)
    }

    private fun systemDark(configuration: Configuration) = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun change(activity: Activity?, dark: Boolean, source: View?) {
        if (dark == resolvedDark && animator == null) return
        animator?.cancel(); animator = null
        val from = palette
        val target = if (dark) ThemePalette.dark else ThemePalette.light
        resolvedDark = dark
        val decor = activity?.window?.decorView as? ViewGroup
        val animate = decor?.isAttachedToWindow == true && decor.width > 0 && decor.height > 0 &&
            ValueAnimator.areAnimatorsEnabled() && activity?.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
        if (!animate) { palette = target; UiTheme.refreshBindings(); return }
        val host = requireNotNull(decor)
        val owner = requireNotNull(activity)
        val reveal = if ((owner.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0 && host.width.toLong() * host.height <= 5_000_000L) {
            try {
                val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                host.draw(Canvas(bitmap))
                val location = IntArray(2); host.getLocationOnScreen(location)
                val point = IntArray(2); source?.getLocationOnScreen(point)
                val x = if (source == null) host.width / 2f else (point[0] - location[0] + source.width / 2f).coerceIn(0f, host.width.toFloat())
                val y = if (source == null) host.height / 3f else (point[1] - location[1] + source.height / 2f).coerceIn(0f, host.height.toFloat())
                ThemeReveal(bitmap, x, y).also { it.setBounds(0, 0, host.width, host.height); host.overlay.add(it) }
            } catch (_: OutOfMemoryError) { null }
            catch (_: RuntimeException) { null }
        } else null
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                palette = from.blend(target, fraction)
                UiTheme.refreshBindings()
                reveal?.progress = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    reveal?.let { host.overlay.remove(it); it.release() }
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    private class ThemeReveal(private val image: Bitmap, private val x: Float, private val y: Float) : Drawable() {
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val aperture = Path()
        private val radius = hypot(maxOf(x, image.width - x).toDouble(), maxOf(y, image.height - y).toDouble()).toFloat()
        var progress = 0f
            set(value) { field = value; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            if (image.isRecycled) return
            aperture.rewind(); aperture.addCircle(x, y, radius * progress, Path.Direction.CW)
            val save = canvas.save(); canvas.clipOutPath(aperture)
            brush.alpha = (255 * (1f - progress * 0.65f)).toInt()
            canvas.drawBitmap(image, 0f, 0f, brush); canvas.restoreToCount(save)
        }
        fun release() { if (!image.isRecycled) image.recycle() }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Platform Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
