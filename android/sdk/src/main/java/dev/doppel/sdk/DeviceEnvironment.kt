package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

internal object DeviceEnvironment {
    /** Launcher-visible applications only; no broad package visibility permission is needed. */
    fun launchableApps(context: android.content.Context, query: String = ""): JSONObject {
        val manager = context.packageManager
        val filter = query.trim().take(120)
        val matches = manager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(manager).toString().take(120) }
            .filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
            .sortedWith(compareBy({ it.second }, { it.first }))
        return JSONObject().put("query", filter).put("total", matches.size).put("truncated", matches.size > 250)
            .put("apps", JSONArray(matches.take(250).map { (pkg, label) ->
                JSONObject().put("package_name", pkg).put("label", label)
            }))
    }
    val globalActions = linkedMapOf(
        "back" to AccessibilityService.GLOBAL_ACTION_BACK,
        "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        "system_screenshot" to AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
        "split_screen" to AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
    )
    fun actionLabel(kind: String) = when (kind) {
        "back" -> "返回上一页"
        "home" -> "返回桌面"
        "recents" -> "打开最近任务"
        "notifications" -> "打开通知栏"
        "quick_settings" -> "打开控制中心"
        "system_screenshot" -> "保存系统截图"
        "split_screen" -> "切换分屏"
        else -> "系统操作"
    }
    fun volume(context: android.content.Context, command: JSONObject, authorized: () -> Boolean): JSONObject {
        val args = SplitAgentProtocol.grounding(JSONObject(command.toString()).put("status", "located")
            .put("action", command.getString("kind")), command.getString("kind"))
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val stream = when (args.getString("stream")) {
            "ring" -> android.media.AudioManager.STREAM_RING
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            else -> android.media.AudioManager.STREAM_MUSIC
        }
        val before = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val target = if (args.getString("action") == "volume")
            (min + (max - min) * args.getInt("percent") / 100.0).let { kotlin.math.round(it).toInt() }
        else (before + if (args.getString("direction") == "up") 1 else -1).coerceIn(min, max)
        val data = JSONObject().put("stream", args.getString("stream")).put("before_level", before)
            .put("requested_level", target).put("max_level", max).put("min_level", min).put("action_state", "not_dispatched")
        fun result(status: String, message: String) = JSONObject().put("status", status).put("message", message).put("data", data)
        if (!authorized()) return result("cancelled", "音量操作已中断")
        if (audio.isVolumeFixed) return result("error", "当前设备固定音量，无法通过系统调节")
        try { audio.setStreamVolume(stream, target, android.media.AudioManager.FLAG_SHOW_UI) }
        catch (_: SecurityException) { return result("error", "系统未允许调整该音量，请通过快捷设置操作") }
        val after = audio.getStreamVolume(stream)
        data.put("after_level", after).put("action_state", if (after == target) "accepted" else "unconfirmed")
        return result(if (after == target) "ok" else "error", if (after == target) "系统音量已核对" else "系统未达到请求的音量，请检查勿扰模式或音量限制")
    }
    fun profile(service: AccessibilityService): JSONObject {
        val available = if (Build.VERSION.SDK_INT >= 30) service.systemActions.map { it.id }.toSet() else emptySet()
        return JSONObject().put("version", 1).put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("build", Build.DISPLAY.take(120)).put("density_dpi", service.resources.displayMetrics.densityDpi)
            .put("global_actions", JSONArray(globalActions.filterValues { Build.VERSION.SDK_INT < 30 || it in available }.keys.toList()))
            .put("window_strategy", "focused_visible_root; reobserve_after_system_action")
            .put("oem_window_gestures", "observe_visible_controls_or_request_user; no_assumed_coordinates")
    }
    fun layers(service: AccessibilityService): JSONArray = JSONArray().apply {
        service.windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.sortedByDescending { it.layer }.take(12).forEach { window ->
            val rect = Rect(); window.getBoundsInScreen(rect)
            put(JSONObject().put("id", window.id).put("layer", window.layer).put("type", window.type)
                .put("focused", window.isFocused).put("active", window.isActive)
                .put("package", window.root?.packageName?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
        }
    }
}
