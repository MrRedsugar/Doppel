package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A user-owned capture session. No model, device action, or rule is invoked by selecting a node. */
object AccessibilityControlPicker {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "doppel-control-picker").apply { isDaemon = true } }
    @Volatile private var service: AccessibilityService? = null
    @Volatile private var generation = 0L
    private var launcher: View? = null
    private var pending: Future<*>? = null
    private var snapshot: Snapshot? = null
    private var activity = WeakReference<ControlPickerSnapshotActivity>(null)
    val active: Boolean get() = service != null

    data class Selection(val packageName: String, val resourceId: String, val text: String, val bounds: Rect)
    internal data class Node(val id: String, val text: String, val bounds: Rect, val parent: Int?)
    internal data class Snapshot(val token: String, val packageName: String, val bitmap: Bitmap,
        val displayWidth: Int, val displayHeight: Int, val nodes: List<Node>) {
        // Walk up from each selectable leaf; exhausted branches disappear, never clamp high ancestors into layer 3.
        val layers: List<List<Int>> by lazy {
            val parents = nodes.mapNotNull { it.parent }.toSet()
            val leaves = nodes.indices.filter { it !in parents }
            val second = leaves.mapNotNull { nodes[it].parent }.distinct()
            listOf(leaves, second, second.mapNotNull { nodes[it].parent }.distinct())
        }
    }
    private data class FrozenTree(val packageName: String, val windowId: Int, val geometry: Triple<Int, Int, Int>, val nodes: List<Node>)

    fun begin(host: AccessibilityService) { main.post {
        val error = unavailable(host)
        if (error != null) { toast(host, error); return@post }
        stopNow()
        service = host
        if (!host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            toast(host, "无法返回桌面，请重试"); stopNow(); return@post
        }
        showLauncher(host)
    } }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() }
    }
    private fun stopNow() {
        generation++
        pending?.cancel(true); pending = null
        removeLauncher()
        snapshot = null
        activity.get()?.finish(); activity.clear()
        service = null
    }
    private fun unavailable(host: AccessibilityService): String? = when {
        !FirstUseConsent.isAccepted(host) -> FirstUseConsent.REQUIRED_MESSAGE
        AutomaticUnlockSession.active || AutomaticUnlockSession.locked(host) -> "请先解锁并接管设备，再选择控件"
        DeviceWorkerService.instance?.isPaused == false -> "请先暂停当前任务，再选择控件"
        else -> null
    }
    private fun toast(host: android.content.Context, message: String) { Toast.makeText(host, message, Toast.LENGTH_LONG).show() }
    private fun removeLauncher() {
        val view = launcher ?: return
        launcher = null
        runCatching { view.context.getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }
    private fun showLauncher(host: AccessibilityService, retry: Boolean = false) {
        removeLauncher()
        val button = TextView(host).apply {
            text = if (retry) "读取失败，点击重试" else "读取控件"
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setBackgroundColor(0xe6202631.toInt())
            setPadding(UiTheme.dp(host, 16), UiTheme.dp(host, 12), UiTheme.dp(host, 16), UiTheme.dp(host, 12))
            contentDescription = "读取控件，长按取消"
            setOnClickListener { capture() }
            setOnLongClickListener { stop(); toast(host, "已取消读取控件"); true }
        }
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END; x = UiTheme.dp(host, 16); y = UiTheme.dp(host, 110)
        }
        try { host.getSystemService(WindowManager::class.java).addView(button, params); launcher = button }
        catch (_: Exception) { toast(host, "无法显示读取控件按钮，请检查无障碍服务"); stopNow() }
    }
    private fun capture() {
        val host = service as? DoppelAccessibilityService ?: return
        unavailable(host)?.let { toast(host, it); stopNow(); return }
        val windowCapture = android.os.Build.VERSION.SDK_INT >= 34
        // Android 14 captures only the application window; keep its accessibility overlay visible.
        // Older display capture still needs the floating button removed before acquiring pixels.
        if (windowCapture) launcher?.isEnabled = false else removeLauncher()
        val owner = ++generation
        main.postDelayed({
            if (generation != owner || service !== host) return@postDelayed
            pending = io.submit {
                val result = runCatching {
                    check(unavailable(host) == null) { "设备当前不可读取，请暂停任务后重试" }
                    val frozen = freeze(host)
                    check(frozen.nodes.isNotEmpty()) { "当前页面没有带控件 ID 的可选控件，请更换页面" }
                    val receipt = host.execute(JSONObject().put("id", UUID.randomUUID().toString())
                        .put("run_id", "control-picker-$owner").put("kind", "screenshot").put("split_agent", true))
                    check(receipt.optString("status") == "ok") { receipt.optString("message").ifBlank { "截图未完成，请重试" } }
                    val data = receipt.getJSONObject("data")
                    val frame = data.getJSONObject("visual_frame")
                    check(frame.getString("package_name") == frozen.packageName &&
                        frame.getInt("display_width") == frozen.geometry.first && frame.getInt("display_height") == frozen.geometry.second &&
                        frame.getInt("rotation") == frozen.geometry.third && geometry(host) == frozen.geometry) { "截图期间页面方向发生变化，请重新读取" }
                    val current = targetRoot(host)
                    try { check(current.windowId == frozen.windowId && current.packageName?.toString() == frozen.packageName) { "截图期间切换了页面，请重新读取" } }
                    finally { current.recycle() }
                    check(unavailable(host) == null) { "设备状态已改变，请重新读取" }
                    val bytes = Base64.decode(data.getString("image_base64"), Base64.DEFAULT)
                    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "截图无法读取，请重试" }
                    Snapshot(UUID.randomUUID().toString(), frozen.packageName, bitmap, frozen.geometry.first, frozen.geometry.second, frozen.nodes)
                }
                main.post {
                    if (generation != owner || service !== host) return@post
                    pending = null
                    result.fold({ frozen ->
                        removeLauncher()
                        snapshot = frozen
                        try {
                            host.startActivity(Intent(host, ControlPickerSnapshotActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("picker_token", frozen.token)
                                .putExtra("picker_package", frozen.packageName))
                        } catch (_: Exception) { snapshot = null; toast(host, "无法打开控件快照，请重试"); showLauncher(host, true) }
                    }, { error ->
                        android.util.Log.w("DoppelControlPicker", "capture_failed ${error.javaClass.simpleName}: ${error.message}")
                        toast(host, error.message ?: "读取控件失败，请重试"); showLauncher(host, true)
                    })
                }
            }
        }, if (windowCapture) 0L else 120L)
    }

    @Suppress("DEPRECATION")
    private fun geometry(host: AccessibilityService): Triple<Int, Int, Int> {
        val display = host.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point(); display.getRealSize(size)
        return Triple(size.x, size.y, display.rotation)
    }
    private fun targetRoot(host: AccessibilityService): AccessibilityNodeInfo {
        val root = (host as? DoppelAccessibilityService)?.activeRoot() ?: host.rootInActiveWindow
        if (root != null && !root.packageName.isNullOrBlank() && root.packageName.toString() != host.packageName) return root
        root?.recycle()
        val windows = host.windows
        try {
            for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }.sortedByDescending { it.layer }) {
                val candidate = window.root ?: continue
                if (!candidate.packageName.isNullOrBlank() && candidate.packageName.toString() != host.packageName) return candidate
                candidate.recycle()
            }
        } finally { windows.forEach { it.recycle() } }
        error("请先打开需要设置的应用页面，再点击读取控件")
    }
    private fun freeze(host: AccessibilityService): FrozenTree {
        val root = targetRoot(host)
        try {
            val dimensions = geometry(host)
            val visible = Rect(0, 0, dimensions.first, dimensions.second)
            val pkg = root.packageName.toString()
            val nodes = ArrayList<Node>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
                check(!Thread.currentThread().isInterrupted) { "读取已取消" }
                check(++visited <= MAX_CONTROL_TREE_NODES && depth <= 64) { "页面控件过多，请缩小页面范围后重新读取" }
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val id = node.viewIdResourceName.orEmpty()
                var nextParent = parent
                if (node.isVisibleToUser && id.isNotBlank() && bounds.intersect(visible) &&
                    (node.packageName == null || node.packageName.toString() == pkg)) {
                    nextParent = nodes.size
                    nodes += Node(id, if (node.isPassword) "" else node.text?.toString().orEmpty().take(2000), Rect(bounds), parent)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                    try { visit(child, nextParent, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, null, 0)
            return FrozenTree(pkg, root.windowId, dimensions, nodes.toList())
        } finally { root.recycle() }
    }

    internal fun snapshot(token: String): Snapshot? = snapshot?.takeIf { it.token == token }
    internal fun attach(owner: ControlPickerSnapshotActivity) { activity = WeakReference(owner) }
    internal fun detach(owner: ControlPickerSnapshotActivity, preserve: Boolean) {
        if (activity.get() !== owner) return
        activity.clear()
        if (!preserve) stopNow()
    }
    internal fun confirm(owner: ControlPickerSnapshotActivity, token: String, index: Int) {
        val frozen = snapshot(token) ?: return
        val node = frozen.nodes.getOrNull(index) ?: return
        try {
            owner.startActivity(Intent(owner, AutoTriggerSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("picker_package", frozen.packageName)
                .putExtra("picker_resource_id", node.id).putExtra("picker_text", node.text)
                .putExtra("picker_bounds", intArrayOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
            stopNow(); owner.finish()
        } catch (_: Exception) { toast(owner, "无法打开任务设置，请重试") }
    }
    internal fun reselect(owner: ControlPickerSnapshotActivity, packageName: String) {
        val host = service ?: DoppelAccessibilityService.instance
        if (host == null) { toast(owner, "无障碍服务已断开，请重新启用后选择"); return }
        unavailable(host)?.let { toast(owner, it); return }
        val target = host.packageManager.getLaunchIntentForPackage(packageName)
        if (target == null) { toast(owner, "无法返回该应用，请关闭后重新选择"); return }
        try {
            host.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            generation++; snapshot = null; activity.clear(); service = host
            owner.finish(); showLauncher(host)
        } catch (_: Exception) { toast(owner, "无法返回该应用，请重试") }
    }
}
