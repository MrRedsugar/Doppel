package dev.doppel.sdk

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.ImageButton
import org.json.JSONObject
import java.util.concurrent.Executors

class DeviceWorkerService : Service(), DeviceWorker {
    companion object {
        @Volatile var instance: DeviceWorkerService? = null
        @Volatile var state = "未连接"
        const val PAUSE = "dev.doppel.PAUSE"
    }
    @Volatile private var alive = true
    @Volatile private var paused = true
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var gateway: Gateway
    private var overlay: ImageButton? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this; gateway = Gateway(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("device", "任务执行", NotificationManager.IMPORTANCE_LOW))
        startForeground(21, notification("已暂停"))
        showOverlay()
        executor.execute { loop() }
    }
    private fun notification(message: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)!!
        return Notification.Builder(this, "device").setContentTitle("Doppel · $message")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "暂停", PendingIntent.getService(this, 1, Intent(this, DeviceWorkerService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)).build()).build()
    }
    private fun update(message: String) { state = message; getSystemService(NotificationManager::class.java).notify(21, notification(message)) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) pause() else resume()
        return START_NOT_STICKY
    }
    override fun pause() {
        paused = true; DoppelAccessibilityService.instance?.setTouchGuard(false); update("已暂停")
        val runId = gateway.prefs.getString("active_run", "").orEmpty()
        if (runId.isNotEmpty()) Thread { try { gateway.request("POST", "/runs/$runId/pause", JSONObject()) } catch (_: Exception) {} }.start()
    }
    override fun resume() { paused = false; update("等待任务") }
    override fun cancel() { paused = true; DoppelAccessibilityService.instance?.setTouchGuard(false); update("已停止") }
    private fun loop() {
        val results = try { ResultStore(this) } catch (_: Exception) { update("命令存储不可用，已停止"); return }
        val ledger = results.ledger
        var cleanupAt = 0L
        while (alive) {
            val device = gateway.prefs.getString("device_id", "").orEmpty()
            if (device.isNotEmpty() && System.currentTimeMillis() - cleanupAt > 30000) {
                cleanupAt = System.currentTimeMillis()
                try {
                    val cleanups = gateway.request("GET", "/devices/$device/data-cleanup").optJSONArray("items")
                    if (cleanups != null) for (i in 0 until cleanups.length()) {
                        val item = cleanups.getJSONObject(i); val ids = item.optJSONArray("command_ids")
                        results.erase(item.getString("run_id"), if (ids == null) emptySet() else (0 until ids.length()).map { ids.getString(it) }.toSet())
                        DoppelAccessibilityService.instance?.clearObservationHistory()
                        gateway.clearDocumentCache()
                        gateway.request("POST", "/devices/$device/data-cleanup/${item.getString("id")}/ack", JSONObject())
                    }
                } catch (_: Exception) { /* Offline deletion stays in the server outbox until a confirmed local purge. */ }
            }
            if (paused) { Thread.sleep(300); continue }
            try {
                if (device.isEmpty()) { paused = true; update("请先绑定设备"); continue }
                val activeRun = gateway.prefs.getString("active_run", "").orEmpty()
                if (activeRun.isNotBlank()) {
                    val active = gateway.request("GET", "/runs/$activeRun")
                    val running = active.optString("status") == "running"
                    val service = DoppelAccessibilityService.instance
                    val outsideClient = try { service?.foregroundPackage() != packageName } catch (_: Exception) { false }
                    service?.setTouchGuard(running && outsideClient && gateway.prefs.getBoolean("touch_pause", true))
                    if (active.optString("status") == "paused") { cancel(); continue }
                    if (active.optString("status") in setOf("cancelled", "completed", "failed")) {
                        gateway.prefs.edit().remove("active_run").apply()
                        service?.setTouchGuard(false)
                        update("等待任务")
                    }
                }
                val command = gateway.request("GET", "/devices/$device/commands?timeout=1").optJSONObject("command") ?: continue
                val id = command.getString("id")
                val cached = ledger.cached(id)
                if (cached != null) {
                    val value = JSONObject(cached)
                    if (value.optJSONObject("data")?.optBoolean("acknowledged") == true) {
                        try { gateway.request("POST", "/devices/$device/results", value) } finally { paused = true; update("已确认命令被重复派发，已暂停") }
                    } else {
                        gateway.request("POST", "/devices/$device/results", value)
                        if (!results.acknowledge(id)) { paused = true; update("本机结果清理失败，已暂停") }
                    }
                    continue
                }
                val runId = command.getString("run_id")
                val run = gateway.request("GET", "/runs/$runId")
                if (paused || !alive || run.optString("status") != "running") continue
                gateway.prefs.edit().putString("active_run", runId).apply()
                val uncertain = JSONObject().put("command_id", id).put("run_id", runId).put("status", "error").put("message", "执行曾中断，结果不确定；禁止自动重放").put("data", JSONObject())
                if (!ledger.claim(id, uncertain.toString())) { paused = true; update("命令记录失败，已暂停"); continue }
                val result = if (paused || !alive) uncertain.put("status", "cancelled").put("message", "派发前已暂停") else {
                    update("正在执行")
                    DoppelAccessibilityService.instance?.execute(command) ?: uncertain.put("message", "无障碍服务未启用")
                }
                if (!ledger.finish(id, result.toString())) { paused = true; update("结果保存失败，已暂停"); continue }
                gateway.request("POST", "/devices/$device/results", result)
                if (!results.acknowledge(id)) { paused = true; update("本机结果清理失败，已暂停"); continue }
                update(if (paused) "已暂停" else "等待任务")
            } catch (_: Exception) { DoppelAccessibilityService.instance?.setTouchGuard(false); update(if (paused) "已暂停" else "连接中断，等待重连"); Thread.sleep(2000) }
        }
        results.close()
    }
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val manager = getSystemService(WindowManager::class.java)
        val size = (52 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.LEFT; params.x = 0; params.y = 240
        val button = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now); contentDescription = "暂停任务或长按说话"; setBackgroundColor(Color.rgb(206, 237, 226))
        }
        var startX = 0f; var startY = 0f; var initialX = 0; var initialY = 0; var dragged = false; var spoken = false
        val speak = Runnable {
            if (!dragged) {
                spoken = true; pause()
                startActivity(Intent(this, VoiceActivity::class.java).putExtra("auto_listen", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        button.setOnTouchListener { _, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; initialX = params.x; initialY = params.y; dragged = false; spoken = false; handler.postDelayed(speak, 600) }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - startX) + kotlin.math.abs(event.rawY - startY) > 16) { dragged = true; handler.removeCallbacks(speak) }
                    if (dragged) { params.x = (initialX + event.rawX - startX).toInt(); params.y = (initialY + event.rawY - startY).toInt().coerceIn(0, resources.displayMetrics.heightPixels - size); manager.updateViewLayout(button, params) }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(speak)
                    params.x = if (params.x + size / 2 < resources.displayMetrics.widthPixels / 2) 0 else resources.displayMetrics.widthPixels - size
                    manager.updateViewLayout(button, params)
                    if (!dragged && !spoken) { pause(); startActivity(packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(speak)
            }
            true
        }
        try { manager.addView(button, params); overlay = button } catch (_: Exception) { update("悬浮窗不可用") }
    }
    override fun onDestroy() {
        alive = false; paused = true; DoppelAccessibilityService.instance?.setTouchGuard(false); instance = null; handler.removeCallbacksAndMessages(null)
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        executor.shutdown(); super.onDestroy()
    }
}
