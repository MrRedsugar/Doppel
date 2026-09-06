package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.WindowManager
import android.view.View
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.accessibility.AccessibilityWindowInfo

class DoppelAccessibilityService : AccessibilityService(), ObservationProvider, ActionExecutor {
    companion object { @Volatile var instance: DoppelAccessibilityService? = null }
    private val refs = mutableMapOf<String, AccessibilityNodeInfo>()
    private val targetHistory = TargetHistory()
    private var latestSnapshot: TargetScreenSnapshot? = null
    @Volatile private var navigationGeneration = 0
    private var touchGuard: View? = null
    @Volatile var guardVisible = false
        private set
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { setTouchGuard(false); targetHistory.clear(); instance = null; super.onDestroy() }
    override fun onInterrupt() { DeviceWorkerService.instance?.pause() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) navigationGeneration++
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) { navigationGeneration++; targetHistory.clear(); DeviceWorkerService.instance?.pause() }
    }
    private fun activeRoot(): AccessibilityNodeInfo? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
            ?: rootInActiveWindow
    fun foregroundPackage(): String = activeRoot()?.packageName?.toString().orEmpty()
    @Synchronized fun clearObservationHistory() { targetHistory.clear(); refs.clear(); latestSnapshot = null }
    fun setTouchGuard(enabled: Boolean) {
        mainHandler.post {
            val manager = getSystemService(WindowManager::class.java)
            if (!enabled) {
                touchGuard?.let { try { manager.removeView(it) } catch (_: Exception) {} }
                touchGuard = null; guardVisible = false
            } else if (touchGuard == null) {
                val guard = View(this).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) { navigationGeneration++; targetHistory.clear(); DeviceWorkerService.instance?.pause(); setTouchGuard(false) }
                        true
                    }
                }
                val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
                try { manager.addView(guard, params); touchGuard = guard; guardVisible = true } catch (_: Exception) { guardVisible = false }
            }
        }
    }
    @Synchronized override fun observe(): JSONObject {
        refs.clear()
        val generation = navigationGeneration
        val root = activeRoot() ?: throw IllegalStateException("无法读取当前屏幕")
        val nodes = JSONArray()
        val targetNodes = mutableListOf<TargetNodeSnapshot>()
        var complete = true
        var characters = 0
        fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (!node.isVisibleToUser) return
            if (depth > 30 || nodes.length() >= 300 || characters >= 24000) { complete = false; return }
            val rect = Rect(); node.getBoundsInScreen(rect)
            val text = if (node.isPassword) "" else node.text?.toString().orEmpty().take(400)
            val description = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().take(400)
            characters += text.length + description.length
            val id = "n" + path
            refs[id] = node
            targetNodes.add(TargetNodeSnapshot(id, listOf(rect.left, rect.top, rect.right, rect.bottom), text, description,
                if (node.isPassword) "" else node.hintText?.toString().orEmpty().take(400), node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), node.isClickable, node.isEditable, node.isEnabled, node.isPassword, node.isScrollable, node.childCount))
            nodes.put(JSONObject().put("id", id).put("text", text).put("description", description)
                .put("role", if (node.isEditable) "input" else if (node.isClickable) "button" else node.className?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                .put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
                .put("scrollable", node.isScrollable).put("password", node.isPassword).put("resource_id", node.viewIdResourceName.orEmpty()))
            if (node.childCount > 300) complete = false
            for (i in 0 until minOf(node.childCount, 300)) node.getChild(i)?.let { walk(it, "${path}_$i", depth + 1) }
        }
        walk(root, "0", 0)
        val metrics = resources.displayMetrics
        val pkg = root.packageName?.toString().orEmpty()
        val screenId = Policy.hash("$pkg|${root.windowId}|$generation|${metrics.widthPixels}|${metrics.heightPixels}|$nodes")
        val snapshot = TargetScreenSnapshot(screenId, pkg, root.windowId, generation, metrics.widthPixels, metrics.heightPixels,
            android.os.SystemClock.elapsedRealtime(), targetNodes.toList(), complete && generation == navigationGeneration)
        latestSnapshot = snapshot; targetHistory.remember(snapshot)
        return JSONObject().put("screen_id", screenId)
            .put("package_name", pkg).put("width", metrics.widthPixels).put("height", metrics.heightPixels)
            .put("nodes", nodes).put("captured_at", System.currentTimeMillis())
    }
    @Synchronized override fun execute(command: JSONObject): JSONObject {
        fun result(status: String, message: String = "", observation: JSONObject? = null, data: JSONObject = JSONObject()) =
            JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
                .put("status", status).put("message", message).put("observation", observation ?: JSONObject.NULL).put("data", data)
        try {
            val kind = command.getString("kind")
            if (kind == "screenshot") return screenshot(command)
            if (kind == "observe") {
                val observation = observe()
                if (command.optBoolean("include_screenshot")) {
                    val shot = screenshot(command)
                    val after = observe()
                    if (after.getString("screen_id") != observation.getString("screen_id")) return result("stale", "截图期间页面发生变化，请重新观察", after)
                    shot.put("observation", after)
                    return shot
                }
                val apps = JSONArray()
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.take(250).forEach {
                    apps.put(JSONObject().put("package_name", it.activityInfo.packageName).put("label", it.loadLabel(packageManager).toString().take(120)))
                }
                return result("ok", observation = observation, data = JSONObject().put("apps", apps))
            }
            if (kind == "wait") { Thread.sleep(command.optLong("duration_ms", 500).coerceIn(0, 5000)); return result("ok", observation = observe()) }
            if (kind in setOf("tap", "type", "scroll")) {
                val fresh = observe()
                val reference = command.optString("target")
                val resolved = if (reference.isNotEmpty() && reference != "null") reference else if (kind == "scroll") refs.entries.firstOrNull { it.value.isScrollable }?.key.orEmpty() else ""
                val node = refs[resolved]
                fun labels(targetNode: AccessibilityNodeInfo, depth: Int = 0): String {
                    if (depth > 4 || targetNode.isPassword) return ""
                    return "${targetNode.text?.toString().orEmpty()} ${targetNode.contentDescription?.toString().orEmpty()} " +
                        (0 until minOf(targetNode.childCount, 30)).joinToString(" ") { targetNode.getChild(it)?.let { child -> labels(child, depth + 1) }.orEmpty() }.take(4000)
                }
                val target = node?.let { Target(if (kind == "scroll") "" else labels(it), it.isPassword, it.isEnabled) }
                val snapshot = latestSnapshot
                if (snapshot == null || snapshot.navigationGeneration != navigationGeneration) return result("stale", "页面导航已变化，请重新观察", fresh)
                val expected = command.optString("screen_id")
                val stable = expected != snapshot.screenId && targetHistory.revalidates(expected, snapshot, resolved, kind)
                val verdict = Policy.validate(kind, if (stable) snapshot.screenId else expected, snapshot.screenId, target)
                if (verdict != "ok") return result(verdict, if (verdict == "stale") "页面或目标已变化，请重新观察" else "支付或敏感输入必须由用户接管", fresh)
                // Child labels and ancestor labels also count: an unlabelled icon cannot bypass a payment button.
                var ancestor = node
                repeat(4) {
                    if (ancestor != null && (ancestor!!.isPassword || (kind != "scroll" && Policy.sensitive("${ancestor!!.text?.toString().orEmpty()} ${ancestor!!.contentDescription?.toString().orEmpty()}")))) return result("blocked", "敏感页面请人工接管", fresh)
                    ancestor = ancestor?.parent
                }
                val nodes = fresh.getJSONArray("nodes")
                val paymentContext = (0 until nodes.length()).any { Regex("收银台|支付密码|确认付款|确认支付|cashier|checkout|payment", RegexOption.IGNORE_CASE).containsMatchIn(nodes.getJSONObject(it).optString("text") + nodes.getJSONObject(it).optString("description")) }
                if (kind != "scroll" && paymentContext && Regex("确认|确定|继续|提交|完成|confirm|continue|submit|done", RegexOption.IGNORE_CASE).containsMatchIn(target?.label.orEmpty()))
                    return result("blocked", "支付页面确认操作请人工接管", fresh)
                // A revalidated target is single-use across device actions; all current safety checks still apply.
                targetHistory.clear()
                val acted = when(kind) {
                    "tap" -> node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "type" -> {
                        if (!node!!.isEditable || command.optString("text").length > 8000) return result("blocked", "输入目标无效")
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.optString("text")) })
                    }
                    else -> {
                        val direction = command.optString("direction")
                        val action = when(direction) { "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; else -> return result("blocked", "滚动方向无效") }
                        node!!.performAction(action)
                    }
                }
                return result(if (acted) "ok" else "error", if (acted) "系统已接受操作，请检查后续屏幕" else "控件未执行操作", settledObservation(), JSONObject().put("target_revalidated", stable))
            }
            targetHistory.clear()
            when(kind) {
                "back" -> return result(if (performGlobalAction(GLOBAL_ACTION_BACK)) "ok" else "error", observation = settledObservation())
                "home" -> return result(if (performGlobalAction(GLOBAL_ACTION_HOME)) "ok" else "error", observation = settledObservation())
                "launch" -> {
                    val intent = packageManager.getLaunchIntentForPackage(command.getString("package_name")) ?: return result("error", "应用未安装或不可启动")
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return result("ok", "启动请求已提交", settledObservation())
                }
                "open_document" -> {
                    val uri = android.net.Uri.parse(command.optString("uri"))
                    val receiver = command.optString("package_name").takeIf { it.isNotBlank() && it != "null" }
                    if (uri.scheme == "doppel-document") { Gateway(this).openDocument(uri.toString(), receiver); return result("ok", "已请求打开文档副本", settledObservation()) }
                    if (uri.scheme != "content" || contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return result("blocked", "文档需要用户通过系统选择器授权")
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (receiver != null) { intent.setPackage(receiver); if (intent.resolveActivity(packageManager) == null) return result("error", "指定应用不可打开此文档") }
                    startActivity(intent)
                    return result("ok", "已请求打开授权文档", settledObservation())
                }
            }
            return result("blocked", "不支持的命令")
        } catch (_: Exception) { return result("error", "设备操作失败，请重新观察并检查权限") }
    }
    private fun settledObservation(): JSONObject? {
        var previous: JSONObject? = null
        var matches = 0
        repeat(6) {
            Thread.sleep(250)
            val current = try { observe() } catch (_: Exception) { null }
            if (current != null && current.optString("screen_id") == previous?.optString("screen_id")) matches++ else matches = 0
            previous = current
            if (matches >= 2) return current
        }
        return previous
    }
    private fun screenshot(command: JSONObject): JSONObject {
        val result = JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id")).put("status", "error").put("message", "此系统不支持无障碍截图（需要 Android 11）").put("data", JSONObject())
        if (Build.VERSION.SDK_INT < 30) return result
        val latch = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(value: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(value.hardwareBuffer, value.colorSpace)
                    if (bitmap != null) {
                        val scale = 1080f / maxOf(bitmap.width, bitmap.height)
                        val output = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                        val stream = ByteArrayOutputStream(); output.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        result.put("status", "ok").put("message", "").put("data", JSONObject().put("image_base64", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)).put("mime_type", "image/png"))
                        if (output !== bitmap) output.recycle()
                        bitmap.recycle()
                    }
                } finally { value.hardwareBuffer.close(); latch.countDown() }
            }
            override fun onFailure(errorCode: Int) { result.put("message", "截图不可用，可能为受保护页面或权限限制"); latch.countDown() }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) result.put("status", "error").put("message", "截图超时")
        return result
    }
}
