package dev.doppel.sdk

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference

/** Opens the real system bouncer; no credential is accepted through intents or views. */
class AutomaticUnlockWakeActivity : Activity() {
    private var resumed = false
    private var requested = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!AutomaticUnlockSession.active || !AutomaticUnlockSession.isUnlocking) { finish(); return }
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        // Keep the authorized touch shield visible here; hiding overlays belongs only to handoff authentication.
    }

    override fun onResume() { super.onResume(); resumed = true; requestWhenVisible() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestWhenVisible()
    }

    private fun requestWhenVisible() {
        val decor = window.decorView
        if (requested || !resumed || isFinishing || !hasWindowFocus() ||
            !decor.isAttachedToWindow || !decor.isShown || decor.windowVisibility != View.VISIBLE) return
        if (!AutomaticUnlockSession.active || !AutomaticUnlockSession.isUnlocking) { finish(); return }
        requested = true
        Log.i("DoppelAutoUnlock", "wake_window_ready")
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) { failed("wake_keyguard_missing"); return }
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { Log.i("DoppelAutoUnlock", "wake_dismiss_success"); finish() }
                override fun onDismissCancelled() { failed("wake_dismiss_cancel") }
                override fun onDismissError() { failed("wake_dismiss_error") }
            })
        } catch (_: Exception) { failed("wake_dismiss_exception") }
    }

    private fun failed(phase: String) {
        Log.i("DoppelAutoUnlock", phase)
        if (!isFinishing && AutomaticUnlockSession.active && AutomaticUnlockSession.isUnlocking)
            AutomaticUnlockSession.fail("系统未能打开解锁界面")
        finish()
    }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: WeakReference<AutomaticUnlockWakeActivity>? = null
        fun finishCurrent() { current?.get()?.let { activity -> activity.runOnUiThread { activity.finish() } } }
    }
}
