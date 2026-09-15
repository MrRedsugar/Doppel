package dev.doppel.sdk

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit human evidence collection. It never replays or infers an action. */
@Deprecated("Application learning is retired; no current app entry or automatic event collection.")
object DemonstrationSession {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)
    private var service: DoppelAccessibilityService? = null
    private var app = ""
    @Volatile private var run: JSONObject? = null
    private var overlay: View? = null
    private var caption: TextView? = null
    private var finishing = false
    val active get() = run != null
    fun status(): JSONObject = synchronized(this) {
        JSONObject().put("active", active).put("package_name", app).put("steps", run?.optJSONArray("events")?.length() ?: 0)
            .put("screenshots", run?.optJSONArray("screenshots")?.length() ?: 0).put("settling", capturing.get()).put("reason", "")
    }
    fun start(host: DoppelAccessibilityService, packageName: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        FirstUseConsent.requireAccepted(host); check(!active) { "已有示范正在进行" }
        check(DeviceWorkerService.instance?.isPaused != false) { "请先暂停任务，再开始示范" }
        require(packageName != host.packageName && AppLearning(host).installedIdentity(packageName) != null)
        service = host; app = packageName; finishing = false
        run = JSONObject().put("source_id", "demo-${UUID.randomUUID()}").put("origin", "human_demonstration")
            .put("app", AppLearning(host).installedIdentity(app)).put("started_at_ms", System.currentTimeMillis())
            .put("events", JSONArray()).put("screenshots", JSONArray()).put("capture_gaps", JSONArray())
            .put("limitations", "截图为离散观察；无障碍事件不等于完整原始触摸轨迹。游戏画布、多指、输入内容和连续手势可能缺失，不得补造动作。")
        try { showOverlay(host); handler.postDelayed(sample, 1000); handler.postDelayed(timeout, 10 * 60 * 1000L) }
        catch (failure: Exception) { cancel(); throw failure }
    }
    private val timeout = Runnable { if (active) finishAndOpen() }
    private val sample = object : Runnable {
        override fun run() { if (active && !finishing) { capture(); handler.postDelayed(this, 2000) } }
    }
    fun event(event: AccessibilityEvent?) {
        val session = run ?: return; val host = service ?: return
        if (!FirstUseConsent.isAccepted(host)) { cancel(); return }
        if (finishing || event == null || event.packageName?.toString() == host.packageName) return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "observed_click"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "observed_long_press"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "observed_scroll"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text_changed_content_not_recorded"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_changed"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "touch_started_path_unavailable"
            else -> return
        }
        synchronized(this) {
            val events = session.getJSONArray("events"); if (events.length() >= 120) return
            val source = event.source
            val label = if (source?.isPassword == true || source?.isEditable == true || kind.startsWith("text_changed")) "" else
                LearningTrace.label(source?.text?.toString().orEmpty().ifBlank { source?.contentDescription?.toString().orEmpty() })
            events.put(JSONObject().put("id", "ev-${UUID.randomUUID()}").put("kind", kind).put("at_ms", System.currentTimeMillis())
                .put("package_name", event.packageName?.toString().orEmpty()).put("label", label))
        }
        update()
    }
    private fun capture() {
        val host = service ?: return; val session = run ?: return
        if (!capturing.compareAndSet(false, true)) return
        io.execute {
            try {
                if (run !== session || !FirstUseConsent.isAccepted(host)) return@execute
                val result = host.execute(JSONObject().put("id", "demo-shot-${UUID.randomUUID()}").put("run_id", session.getString("source_id")).put("kind", "screenshot"))
                synchronized(this) {
                    if (run !== session) return@synchronized
                    val data = result.optJSONObject("data")
                    if (result.optString("status") == "ok" && !data?.optString("image_base64").isNullOrBlank()) {
                        val shots = session.getJSONArray("screenshots")
                        // Retain the starting screen and the latest eleven views, so a long
                        // demonstration does not silently lose all evidence after its first minute.
                        if (shots.length() >= 12) {
                            val removed = shots.getJSONObject(1).getString("id"); shots.remove(1)
                            val events = session.getJSONArray("events")
                            for (i in events.length() - 1 downTo 0) if (events.getJSONObject(i).optString("id") == removed) events.remove(i)
                            session.put("earlier_screenshots_omitted", true)
                        }
                        val total = (0 until shots.length()).sumOf { shots.getJSONObject(it).getString("image_base64").length.toLong() }
                        if (total + data!!.getString("image_base64").length <= 16 * 1024 * 1024) {
                            val id = "shot-${UUID.randomUUID()}"
                            shots.put(JSONObject(data.toString()).put("id", id))
                            session.getJSONArray("events").put(JSONObject().put("id", id).put("kind", "screenshot").put("at_ms", System.currentTimeMillis()).put("frame", data.optJSONObject("visual_frame")))
                        }
                    } else if (session.getJSONArray("capture_gaps").length() < 24) session.getJSONArray("capture_gaps").put(JSONObject().put("at_ms", System.currentTimeMillis()).put("status", result.optString("status", "unavailable")))
                }
            } catch (_: Exception) { synchronized(this) { if (run === session && session.getJSONArray("capture_gaps").length() < 24) session.getJSONArray("capture_gaps").put("screenshot_unavailable") } }
            finally { capturing.set(false); handler.post { update() } }
        }
    }
    private fun update() { caption?.text = if (finishing) "正在结束示范…" else "示范中 · ${status().optInt("screenshots")} 张观察" }
    /** Only pending evidence is persisted. The user must still state the goal, review and save. */
    fun finish(): JSONObject {
        check(Looper.myLooper() == Looper.getMainLooper())
        val host = requireNotNull(service); val session = requireNotNull(run)
        FirstUseConsent.requireAccepted(host)
        val evidence = synchronized(this) { JSONObject(session.toString()).put("ended_at_ms", System.currentTimeMillis()) }
        require(evidence.getJSONArray("events").length() > 0) { "尚未采集到观察，请操作后再结束" }
        AppLearning(host).savePendingEvidence(evidence); cancel()
        return JSONObject().put("status", "draft_pending").put("title", "待填写示范目标")
    }
    private fun finishAndOpen() {
        val host = service ?: return; if (finishing) return
        capture()
        finishing = true; handler.removeCallbacks(sample); update()
        io.execute { handler.post {
            if (service !== host || !active) return@post
            try { finish(); host.startActivity(Intent(host, LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("review_demo", true)) }
            catch (failure: Exception) { finishing = false; Toast.makeText(host, failure.message ?: "示范未保存", Toast.LENGTH_LONG).show(); update() }
        } }
    }
    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { cancel() }; return }
        handler.removeCallbacks(sample); handler.removeCallbacks(timeout)
        overlay?.let { view -> runCatching { service?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) } }
        overlay = null; caption = null; run = null; service = null; app = ""; finishing = false
    }
    internal fun failed() { update() }
    private fun showOverlay(host: DoppelAccessibilityService) {
        val row = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; background = UiTheme.glass(host); setPadding(UiTheme.dp(host, 16), UiTheme.dp(host, 6), UiTheme.dp(host, 8), UiTheme.dp(host, 6)) }
        caption = UiTheme.text(host, "示范中", 14f, UiTheme.ink, true); row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        fun action(label: String, block: () -> Unit) = UiTheme.text(host, label, 14f, UiTheme.blue, true).apply {
            gravity = Gravity.CENTER; minWidth = UiTheme.dp(host, 64); minHeight = UiTheme.dp(host, 48); contentDescription = "演示$label"; setOnClickListener { block() }
        }
        row.addView(action("结束") { finishAndOpen() }); row.addView(action("取消") { cancel() })
        val width = minOf(UiTheme.dp(host, 342), host.resources.displayMetrics.widthPixels - UiTheme.dp(host, 32)).coerceAtLeast(1)
        val params = WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = UiTheme.dp(host, 24) }
        host.getSystemService(WindowManager::class.java).addView(row, params); overlay = row
    }
}
