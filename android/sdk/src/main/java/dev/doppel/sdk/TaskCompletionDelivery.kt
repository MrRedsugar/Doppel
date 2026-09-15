package dev.doppel.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** Terminal delivery owns no Activity navigation and never changes the app underneath. */
internal class TaskCompletionDelivery(private val context: Context) {
    private val handler = androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val speech = CompletionSpeech(context)
    private var view: View? = null
    private var hidden = false
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    private val cleared = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_result" && !prefs.contains("last_result") && view !is PauseActionSheet) dismiss()
    }

    init {
        notifications.createNotificationChannel(NotificationChannel("results", "任务结果", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.createNotificationChannel(NotificationChannel("task_pauses", "任务暂停原因", NotificationManager.IMPORTANCE_DEFAULT))
        prefs.registerOnSharedPreferenceChangeListener(cleared)
    }

    fun deliver(run: JSONObject) = deliverIf(run) { true }
    fun deliverPause(run: JSONObject) = deliverPauseIf(run) { true }
    fun deliverPauseIf(run: JSONObject, current: () -> Boolean) {
        val info = PausePresentation.from(run) ?: return
        val id = run.optString("id")
        if (id.isBlank() || info.userInitiated) return
        val ticket = epoch.get()
        val key = id + ":" + java.security.MessageDigest.getInstance("SHA-256").digest(
            "$id:${run.optLong("updated_at")}:${info.reason}".toByteArray()).joinToString("") { "%02x".format(it) }
        handler.post {
            if (closed || epoch.get() != ticket || !current()) return@post
            val seen = prefs.getStringSet("delivered_pauses", emptySet()).orEmpty()
            if (key in seen) return@post
            if (!prefs.edit().putStringSet("delivered_pauses", (seen.toList().takeLast(63) + key).toSet()).commit()) return@post
            val open = Intent(context, TaskPanelActivity::class.java).putExtra("run_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(context, ("pause:$id").hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = TaskPresentation.withSourceLabel(run, info.category)
            try {
                notifications.notify(("pause:$id").hashCode(), Notification.Builder(context, "task_pauses")
                    .setSmallIcon(UiIcons.pause).setContentTitle(title).setContentText(info.surfaceReason.take(160))
                    .setStyle(Notification.BigTextStyle().bigText(info.surfaceDetail)).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(pending).setAutoCancel(true).build())
            } catch (_: SecurityException) { }
            showPause(info, run, current)
        }
    }
    fun revealPause(run: JSONObject, current: () -> Boolean): Boolean {
        val info = PausePresentation.from(run) ?: return false
        if (!Settings.canDrawOverlays(context) || closed || !current()) return false
        if (view !is PauseActionSheet) showPause(info, run, current)
        return view is PauseActionSheet
    }
    private fun showPause(info: PausePresentation, run: JSONObject, current: () -> Boolean) {
        removeView()
        if (!Settings.canDrawOverlays(context)) return
        val id = run.optString("id")
        var submitting = false
        lateinit var sheet: PauseActionSheet
        sheet = PauseActionSheet(context, info, TaskPresentation.withSourceLabel(run, if (info.userInitiated) "任务待续" else info.category), run) { action ->
            if (view === sheet && (!current() || prefs.getString("active_run", "") != id)) dismiss()
            else if (!submitting && view === sheet) {
                if (action == "resume" && !FirstUseConsent.isAccepted(context)) {
                    sheet.failed("请先在 Doppel 中确认使用条款后继续。")
                } else {
                    submitting = true; sheet.submitting(action)
                    TaskControl.requestKeepingPauseNotice(context, id, action) { run, error ->
                        if (!closed && view === sheet) {
                            submitting = false
                            if (!current() || prefs.getString("active_run", "") != id) dismiss()
                            else if (error != null || run == null) sheet.failed("未能确认操作，请检查连接后重试。任务进度已保留。")
                            else if (action == "cancel" && TaskPresentation.terminal(run.optString("status"))) {
                                DeviceWorkerService.instance?.acceptEndedRun(run)
                            } else if (action == "resume" && run.optString("status") == "running") {
                                try {
                                    if (!TaskControl.startWorker(context)) sheet.failed("操作已改变，请重新确认。")
                                } catch (_: Exception) { sheet.failed("暂时无法启动执行服务，请检查权限后重试。") }
                            } else sheet.failed("当前步骤仍需处理，请核对页面后再继续。")
                        }
                    }
                }
            }
        }
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(minOf(dp(460), metrics.widthPixels - dp(32)), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(16) }
        try {
            manager.addView(sheet, params); view = sheet
            if (android.animation.ValueAnimator.areAnimatorsEnabled() && !hidden) {
                sheet.alpha = 0f; sheet.translationY = dp(20).toFloat()
                sheet.animate().alpha(1f).translationY(0f).setDuration(220).start()
            } else sheet.alpha = if (hidden) 0f else 1f
        } catch (_: Exception) { view = null }
    }
    fun onConfigurationChanged() {
        val sheet = view as? PauseActionSheet ?: return
        val params = sheet.layoutParams as WindowManager.LayoutParams
        params.width = minOf(dp(460), context.resources.displayMetrics.widthPixels - dp(32))
        try { manager.updateViewLayout(sheet, params); sheet.requestLayout() } catch (_: Exception) { }
    }
    fun clearPause(id: String) {
        notifications.cancel(("pause:$id").hashCode())
        val seen = prefs.getStringSet("delivered_pauses", emptySet()).orEmpty()
        prefs.edit().putStringSet("delivered_pauses", seen.filterNot { it.startsWith("$id:") }.toSet()).apply()
        dismiss()
    }
    fun deliverIf(run: JSONObject, current: () -> Boolean) {
        val ticket = epoch.get()
        val id = run.optString("id")
        val baseResult = CompletionPresentation.from(run.optString("status"), run.optString("message")) ?: return
        val result = baseResult.copy(title = TaskPresentation.withSourceLabel(run, baseResult.title))
        if (id.isBlank()) return
        handler.post {
            if (closed || epoch.get() != ticket || !current()) return@post
            val seen = prefs.getStringSet("delivered_results", emptySet()).orEmpty()
            if (id in seen) return@post
            val recent = (seen.toList().takeLast(63) + id).toSet()
            // Persist before playing: process recovery must never replay a private spoken result.
            if (!prefs.edit().putStringSet("delivered_results", recent).putString("last_result", run.toString()).commit()) return@post
            val open = Intent(context, TaskPanelActivity::class.java).putExtra("run_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                notifications.notify(id.hashCode(), Notification.Builder(context, "results")
                    .setSmallIcon(R.drawable.doppel_ic_layers_2).setContentTitle(result.title)
                    .setContentText(result.text.take(160)).setStyle(Notification.BigTextStyle().bigText(result.text))
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(pending).build())
            } catch (_: SecurityException) { }
            speech.stop(); show(result)
            if (result.speak && prefs.getBoolean("completion_speech", true)) speech.speak(result.text)
        }
    }

    private fun show(result: CompletionPresentation) {
        removeView()
        if (!Settings.canDrawOverlays(context)) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.glass(context, 24)
            elevation = dp(12).toFloat()
            setPadding(dp(20), dp(12), dp(12), dp(20))
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(UiTheme.text(context, result.title, 14f, UiTheme.muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.icon(context, UiIcons.close, "关闭结果") { dismiss() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)
        val body = UiTheme.text(context, result.text, 17f, UiTheme.ink).apply { setTextIsSelectable(true) }
        val viewport = ScrollView(context).apply { isFillViewport = false; addView(body) }
        root.addView(viewport, LinearLayout.LayoutParams(-1, -2))
        val metrics = context.resources.displayMetrics
        val maxHeight = minOf(dp(310), metrics.heightPixels / 3)
        viewport.measure(View.MeasureSpec.makeMeasureSpec(minOf(dp(380), metrics.widthPixels - dp(72)), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        viewport.layoutParams.height = viewport.measuredHeight.coerceAtMost(maxHeight)
        val params = WindowManager.LayoutParams(minOf(dp(430), metrics.widthPixels - dp(32)), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(60) }
        try {
            manager.addView(root, params); view = root
            root.alpha = 0f; root.translationY = -dp(16).toFloat()
            root.animate().alpha(if (hidden) 0f else 1f).translationY(0f).setDuration(240).start()
        } catch (_: Exception) { view = null }
        // Short receipts disappear; answer-bearing summaries stay until dismissed or the next task.
        if (result.text.length <= 12) handler.postDelayed({ if (view === root) dismiss(false) }, 6500)
    }

    fun setCaptureHidden(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) { hidden = value; applyCaptureHidden() }
        else handler.post { hidden = value; applyCaptureHidden() }
    }
    private fun applyCaptureHidden(): Boolean {
        val sheet = view ?: return true
        sheet.animate().cancel(); sheet.alpha = if (hidden) 0f else 1f
        val params = sheet.layoutParams as? WindowManager.LayoutParams ?: return false
        params.alpha = if (hidden) 0f else 1f
        return try { manager.updateViewLayout(sheet, params); true } catch (_: Exception) { false }
    }
    fun hideBeforeCapture(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) { setCaptureHidden(true); return false }
        val ready = java.util.concurrent.CountDownLatch(1)
        handler.post {
            hidden = true
            if (!applyCaptureHidden()) return@post
            if (view == null) ready.countDown() else handler.postDelayed({ ready.countDown() }, 80)
        }
        return ready.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }
    fun dismiss(stopSpeech: Boolean = true) {
        epoch.incrementAndGet()
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { removeView(); if (stopSpeech) speech.stop() }; return }
        removeView()
        if (stopSpeech) speech.stop()
    }
    private fun removeView() {
        view?.let { it.animate().cancel(); try { manager.removeViewImmediate(it) } catch (_: Exception) {} }; view = null
    }
    fun close() { closed = true; prefs.unregisterOnSharedPreferenceChangeListener(cleared); handler.removeCallbacksAndMessages(null); dismiss(); speech.close() }
    private fun dp(value: Int) = UiTheme.dp(context, value)
}
