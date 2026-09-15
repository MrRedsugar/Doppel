package dev.doppel.sdk

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** One local countdown for automatic task sources; it never holds the submission gate. */
internal object AutomaticTaskNotice {
    private class Notice(val context: Context, val key: String, val valid: () -> Boolean,
                         val execute: () -> Unit, val cancelled: () -> Unit) {
        val generation = TaskControl.currentGeneration()
        val connection = connectionStamp(context)
        @Volatile var approved = false
        var shownAt = 0L
        var view: View? = null
        var manager: WindowManager? = null
        var message: TextView? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private val current = AtomicReference<Notice?>()

    /** No network, model or screenshot request. */
    fun localBlockReason(context: Context, ownGate: Boolean = false): String? {
        if (!FirstUseConsent.isAccepted(context) || !ReleaseIntegrity.isTrusted(context)) return "consent_required"
        if ((!ownGate && TaskSubmissionGate.creating.get()) || VoiceActivity.isVisible ||
            PaymentConsent.settingsVisible || DirectMode.settingsVisible || DemonstrationSession.active || AccessibilityControlPicker.active) return "device_busy"
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked || keyguard.isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive) return "device_locked"
        if (DoppelAccessibilityService.instance == null || !Settings.canDrawOverlays(context)) return "accessibility_unavailable"
        return null
    }

    /** Detect connection edits without decrypting model credentials every second. */
    fun connectionStamp(context: Context): String {
        val gateway = Gateway(context)
        val prefs = gateway.prefs
        val identity = if (gateway.isDirectMode()) {
            // ponytail: timestamp/size track local config edits; use a provider revision if same-millisecond rewrites matter.
            val models = File(context.noBackupFilesDir, "model-providers-v1.bin")
            "direct:${context.packageName}:${prefs.getString("device_id", "")}:${models.lastModified()}:${models.length()}"
        } else "gateway:${prefs.getString("base_url", "")}:${prefs.getString("device_id", "")}:${prefs.getString("token", "")}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun show(context: Context, key: String, title: String, goal: String, valid: () -> Boolean,
             onExecute: () -> Unit, onSkip: () -> Unit, onPostpone: (() -> Unit)? = null,
             onCancelled: () -> Unit = {}, onShown: () -> Unit = {}): Boolean {
        val notice = Notice(context.applicationContext, key, valid, onExecute, onCancelled)
        if (!current.compareAndSet(null, notice)) return false
        handler.post {
            if (!live(notice)) { cancel(notice); return@post }
            val host = DoppelAccessibilityService.instance ?: run { cancel(notice); return@post }
            val panel = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL; background = UiTheme.glass(host)
                setPadding(UiTheme.dp(host, 18), UiTheme.dp(host, 16), UiTheme.dp(host, 18), UiTheme.dp(host, 12))
            }
            panel.addView(UiTheme.text(host, title, 16f, UiTheme.ink, true))
            panel.addView(UiTheme.text(host, goal.take(100), 14f))
            notice.message = UiTheme.text(host, "", 12f, UiTheme.muted).also { panel.addView(it) }
            val row = LinearLayout(host)
            row.addView(UiTheme.command(host, "执行") { approve(notice) }, LinearLayout.LayoutParams(0, UiTheme.dp(host, 48), 1f))
            onPostpone?.let { action -> row.addView(UiTheme.command(host, "推迟 10 分钟") {
                if (remove(notice)) action()
            }, LinearLayout.LayoutParams(0, UiTheme.dp(host, 48), 1.6f)) }
            row.addView(UiTheme.command(host, "跳过") { if (remove(notice)) onSkip() }, LinearLayout.LayoutParams(0, UiTheme.dp(host, 48), 1f))
            panel.addView(row)
            try {
                notice.manager = host.getSystemService(WindowManager::class.java)
                val width = minOf(UiTheme.dp(host, 350), host.resources.displayMetrics.widthPixels - UiTheme.dp(host, 32)).coerceAtLeast(1)
                notice.manager!!.addView(panel, WindowManager.LayoutParams(width, -2,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = UiTheme.dp(host, 24) })
                notice.view = panel
                // addView returns before attachment; start the full countdown once the window is shown.
                panel.post {
                    if (current.get() !== notice) return@post
                    if (!live(notice) || !panel.isAttachedToWindow) { cancel(notice); return@post }
                    notice.shownAt = SystemClock.elapsedRealtime()
                    runCatching(onShown).onFailure { android.util.Log.w("DoppelAutomaticNotice", "shown_callback_failed") }
                    refresh.run()
                }
            } catch (error: Exception) { android.util.Log.w("DoppelAutomaticNotice", "show_failed ${error.javaClass.simpleName}"); cancel(notice) }
        }
        return true
    }

    fun approved(key: String): Boolean {
        val notice = current.get()?.takeIf { it.key == key && it.approved } ?: return false
        val valid = TaskControl.isCurrent(notice.generation) && connectionStamp(notice.context) == notice.connection
        if (!valid) dismiss(key)
        return valid
    }
    fun generation(key: String): Long? = current.get()?.takeIf { it.key == key }?.generation
    fun dismiss(key: String) {
        val action = Runnable { current.get()?.takeIf { it.key == key }?.let(::cancel) }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    private fun live(notice: Notice): Boolean = current.get() === notice && runCatching {
        TaskControl.isCurrent(notice.generation) && localBlockReason(notice.context) == null && notice.valid()
    }.getOrDefault(false)
    private val refresh = object : Runnable {
        override fun run() {
            val notice = current.get() ?: return
            if (!live(notice) || notice.view?.isAttachedToWindow != true) { cancel(notice); return }
            val remaining = (15000L - (SystemClock.elapsedRealtime() - notice.shownAt)).coerceAtLeast(0)
            notice.message?.text = "${(remaining + 999) / 1000} 秒后开始自动任务，可立即执行或跳过。"
            if (remaining == 0L) approve(notice) else handler.postDelayed(this, minOf(1000L, remaining))
        }
    }
    private fun approve(notice: Notice) {
        if (notice.approved) return
        if (!live(notice)) { cancel(notice); return }
        notice.approved = true
        hide(notice)
        // Reserve this source until its final readiness check finishes.
        try { notice.execute() } catch (_: Exception) { cancel(notice) }
    }
    private fun hide(notice: Notice) {
        handler.removeCallbacks(refresh)
        notice.view?.let { runCatching { notice.manager?.removeViewImmediate(it) } }
        notice.view = null; notice.message = null; notice.manager = null
    }
    private fun remove(notice: Notice): Boolean {
        if (!current.compareAndSet(notice, null)) return false
        hide(notice); return true
    }
    private fun cancel(notice: Notice) { if (remove(notice) && !notice.approved) notice.cancelled() }
}
