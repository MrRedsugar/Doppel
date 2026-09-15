package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.File

/** One user-started native setup session. No model, device worker, task or conversation is created. */
class PermissionSetupActivity : Activity() {
    private enum class Step(val title: String, val permission: String? = null) {
        ACCESSIBILITY("无障碍服务"), OVERLAY("悬浮窗"),
        MICROPHONE("麦克风", Manifest.permission.RECORD_AUDIO),
        NOTIFICATIONS("发送通知", Manifest.permission.POST_NOTIFICATIONS),
        CALENDAR("读取日历", Manifest.permission.READ_CALENDAR), NOTIFICATION_ACCESS("读取通知")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val steps = Step.entries
    private val results = linkedMapOf<Step, String>()
    private val dispatched = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("doppel", MODE_PRIVATE) }
    private val names by lazy {
        setOf(packageManager.getApplicationLabel(applicationInfo).toString()) +
            listOf(DoppelAccessibilityService::class.java, LoginNotificationService::class.java).map {
                packageManager.getServiceInfo(ComponentName(this, it), 0).loadLabel(packageManager).toString()
            }
    }
    private lateinit var summary: TextView
    private lateinit var detail: TextView
    private lateinit var skip: android.view.View
    private var active = false
    private var ownsGate = false
    private var resumed = false
    private var index = 0
    private var opened = false
    private var deadline = 0L
    private var lastDispatch = 0L
    private var generation = 0L
    private var initialPointer = ""
    private var worker: DeviceWorkerService? = null
    private var settingsPackage = ""
    private var returned = false
    private var manualRequired = false
    private var assistReads = 0
    private var lastAssistRead = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        render()
        if (intent.action == ACTION_STOP) { stop("设置已停止"); return }
        if (state != null) {
            detail.text = "上次设置已中断，尚未完成的权限请重新点击自动设置。已授权的权限会保留。"
            skip.visibility = android.view.View.GONE
            return
        }
        begin()
    }

    private fun render() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(UiTheme.background)
        }
        page.addView(UiTheme.text(this, "自动设置权限", 22f, bold = true))
        page.addView(UiTheme.text(this, "仅为 Doppel 开启无障碍、悬浮窗、麦克风、发送通知、读取日历和读取通知。首次启用无障碍及系统限制的步骤需你操作。设置完成后自动结束，不会留下任务或对话记录。", 14f, UiTheme.muted)
            .apply { setPadding(0, dp(12), 0, dp(20)) })
        summary = UiTheme.text(this, "准备设置", 17f, bold = true).also(page::addView)
        detail = UiTheme.text(this, "", 14f).also { it.setPadding(0, dp(12), 0, dp(16)); page.addView(it) }
        skip = UiTheme.command(this, "跳过这一项") { finishStep("已跳过，可稍后手动设置") }.also {
            page.addView(it, LinearLayout.LayoutParams(-1, dp(48)))
        }
        page.addView(UiTheme.command(this, "停止并返回") { stop("已停止，已授权的权限会保留"); finish() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        page.addView(UiTheme.text(this, "系统设置页可能隐藏悬浮窗。可按返回回到此页停止，或点击通知中的停止；每项最多等待 60 秒（首次无障碍 120 秒）。后台启动等厂商专属设置仍需在系统权限页手动开启。", 12f, UiTheme.muted)
            .apply { setPadding(0, dp(20), 0, 0) })
        val scroll = ScrollView(this).apply { addView(page) }
        UiTheme.window(this, scroll); setContentView(scroll)
    }

    private fun begin() {
        if (AutomaticUnlockSession.active || DeviceWorkerService.instance?.isPaused == false ||
            DemonstrationSession.active || AccessibilityControlPicker.active || !idleStoredTasks()) {
            stop("请先结束当前任务，再自动设置权限"); return
        }
        if (!TaskSubmissionGate.creating.compareAndSet(false, true)) {
            stop("当前正在创建任务或设置权限，请稍后再试"); return
        }
        ownsGate = true
        generation = TaskControl.currentGeneration()
        initialPointer = prefs.getString("active_run", "").orEmpty()
        worker = DeviceWorkerService.instance
        DirectMode.enterSettings(this)
        active = true
        if (!idleStoredTasks() || !current()) { stop("设备任务状态已改变，设置已停止"); return }
        handler.post(tick)
    }

    /** Read only; do not initialize DirectRuntime or alter a stale terminal pointer. */
    private fun idleStoredTasks(): Boolean = runCatching {
        val pointer = prefs.getString("active_run", "").orEmpty()
        val file = File(noBackupFilesDir, "direct-runs-v1.json")
        val rows = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        var terminalPointer = pointer.isBlank()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (!TaskPresentation.terminal(row.optString("status"))) return@runCatching false
            if (row.optString("id") == pointer) terminalPointer = true
        }
        terminalPointer
    }.getOrDefault(false)

    private fun current() = active && TaskControl.isCurrent(generation) &&
        prefs.getString("active_run", "").orEmpty() == initialPointer &&
        DeviceWorkerService.instance === worker && worker?.isPaused != false && !AutomaticUnlockSession.active

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (!current()) { stop("设备任务状态已改变，设置已停止"); return }
            val lock = getSystemService(KeyguardManager::class.java)
            if (!getSystemService(PowerManager::class.java).isInteractive || lock.isKeyguardLocked) {
                stop("屏幕已关闭或锁定，设置已停止；亮屏后可重新开始"); return
            }
            val step = steps.getOrNull(index) ?: run { complete(); return }
            if (granted(step)) {
                finishStep("已授权")
            } else if (!opened) {
                if (resumed) open(step)
                else if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
                    stop("无法返回权限设置页，已停止；请回到 Doppel 重新开始")
                }
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                finishStep("等待超时，请在系统权限页手动设置")
            } else if (step != Step.ACCESSIBILITY && !manualRequired) {
                assist(step)
            }
            if (active) handler.postDelayed(this, 450)
        }
    }

    private fun granted(step: Step): Boolean = when (step) {
        Step.ACCESSIBILITY -> DoppelAccessibilityService.instance != null
        Step.OVERLAY -> Settings.canDrawOverlays(this)
        Step.NOTIFICATIONS -> (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        Step.NOTIFICATION_ACCESS -> PermissionSetupTargets.notificationAccess(this)
        else -> granted(step.permission!!)
    }
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun open(step: Step) {
        if (!current()) return
        opened = true; returned = false; dispatched.clear()
        manualRequired = step == Step.ACCESSIBILITY; assistReads = 0; lastAssistRead = 0
        deadline = SystemClock.elapsedRealtime() + if (step == Step.ACCESSIBILITY) 120_000L else 60_000L
        lastDispatch = SystemClock.elapsedRealtime()
        summary.text = "${index + 1}/${steps.size} · ${step.title}"
        detail.text = if (step == Step.ACCESSIBILITY) "请手动启用 Doppel 屏幕操作。启用后会继续设置其余权限。"
            else "正在开启${step.title}。系统要求手动确认时，请按页面说明完成；也可返回跳过或停止。"
        publishNotice()
        try {
            val permission = step.permission?.takeUnless { step == Step.NOTIFICATIONS && Build.VERSION.SDK_INT < 33 }
            if (permission != null && !granted(permission)) {
                val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
                if (permission !in declared) { finishStep("当前版本未声明此权限，请更新应用"); return }
                requestPermissions(arrayOf(permission), REQUEST_PERMISSION)
                return
            }
            val target = when (step) {
                Step.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Step.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                Step.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                Step.NOTIFICATION_ACCESS -> notificationAccessIntent(this)
                else -> error("无可用的权限设置页")
            }
            var resolved = target.resolveActivity(packageManager)
            val intent = if (resolved != null) target else when (step) {
                Step.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Step.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                else -> throw IllegalStateException("此设备没有对应的系统设置入口")
            }
            resolved = intent.resolveActivity(packageManager)
            check(resolved != null && PermissionSetupTargets.isSystemPackage(this, resolved.packageName)) { "系统设置入口不可用" }
            settingsPackage = resolved.packageName
            if (current()) startActivity(intent)
        } catch (_: Exception) { finishStep("无法自动打开此权限，请在系统权限页手动设置") }
    }

    private fun assist(step: Step) {
        val now = SystemClock.elapsedRealtime()
        if (!current() || manualRequired || now - lastDispatch < 800 || now - lastAssistRead < 1_000) return
        lastAssistRead = now
        assistReads++
        val service = DoppelAccessibilityService.instance ?: run {
            if (assistReads >= 3) requireManual(step)
            return
        }
        val root = service.activeRoot() ?: run {
            if (assistReads >= 3) requireManual(step)
            return
        }
        val nodes = PermissionSetupTargets.nodes(root)
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName) return // The user returned to the progress/cancel page.
            val runtime = step.permission != null && !(step == Step.NOTIFICATIONS && Build.VERSION.SDK_INT < 33) && !granted(step.permission!!)
            val permissionController = pkg in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller")
            val trusted = PermissionSetupTargets.isSystemPackage(this, pkg) && if (runtime) permissionController else pkg == settingsPackage
            if (!trusted) {
                // A short window-transition grace avoids reacting to the launcher between Activities.
                if (SystemClock.elapsedRealtime() - lastDispatch > 3_000) stop("已离开权限设置，自动操作已停止")
                return
            }
            if (PermissionSetupTargets.restricted(nodes)) {
                requireManual(step); return
            }
            if (!PermissionSetupTargets.ownsPage(nodes, names)) return
            // A Settings package hosts many unrelated switches. Bind each click to the
            // permission page we opened; an unknown OEM page remains a manual step.
            val purpose = when (step) {
                Step.OVERLAY -> listOf("显示在其他应用", "在其他应用上层", "悬浮窗", "Display over other apps", "Appear on top")
                Step.NOTIFICATIONS -> listOf("发送通知", "允许通知", "所有通知", "All notifications", "Allow notifications", "Send notifications")
                Step.NOTIFICATION_ACCESS -> listOf("通知使用权", "通知访问", "使用通知", "读取通知", "Notification access", "Read all notifications")
                Step.MICROPHONE -> listOf("麦克风", "录音", "录制音频", "录制声音", "microphone", "record audio")
                Step.CALENDAR -> listOf("日历", "calendar")
                else -> emptyList()
            }
            if (!PermissionSetupTargets.pageContains(nodes, purpose)) return
            if (step == Step.NOTIFICATIONS && PermissionSetupTargets.pageContains(nodes,
                    listOf("通知使用权", "通知访问", "读取通知", "Notification access", "Read all notifications"))) return
            val kind: String
            val candidate: AccessibilityNodeInfo?
            if (runtime) { kind = "runtime_allow"; candidate = PermissionSetupTargets.runtimeAllow(nodes) }
            else {
                val confirmation = PermissionSetupTargets.allowConfirmation(nodes)
                kind = if (confirmation != null) "confirmation" else "switch"
                candidate = confirmation ?: PermissionSetupTargets.specialSwitch(nodes)
            }
            fun stillCurrent(): Boolean {
                if (!current() || !root.refresh() || root.packageName?.toString() != pkg) return false
                val now = service.activeRoot() ?: return false
                return try { current() && now.windowId == root.windowId && now.packageName?.toString() == pkg }
                finally { @Suppress("DEPRECATION") now.recycle() }
            }
            if (candidate != null && kind !in dispatched && stillCurrent()) {
                dispatched += kind
                lastDispatch = SystemClock.elapsedRealtime()
                if (!PermissionSetupTargets.clickControl(candidate, ::stillCurrent)) {
                    requireManual(step)
                } else assistReads = 0
            } else if (!runtime && candidate == null && "app_row" !in dispatched) {
                if (PermissionSetupTargets.clickAppRow(nodes, names, ::stillCurrent)) {
                    dispatched += "app_row"; lastDispatch = SystemClock.elapsedRealtime(); assistReads = 0
                }
            }
        } finally {
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            if (current() && !manualRequired && assistReads >= 3) requireManual(step)
        }
    }

    private fun requireManual(step: Step) {
        if (!current() || manualRequired || steps.getOrNull(index) != step) return
        manualRequired = true
        summary.text = "${index + 1}/${steps.size} · ${step.title}需要手动确认"
        detail.text = "系统未提供可自动操作的授权控件。请在当前系统页面手动允许${step.title}；授权后会自动继续。也可返回此页跳过或停止。"
        android.widget.Toast.makeText(this, "请在系统页面手动允许${step.title}，授权后自动继续", android.widget.Toast.LENGTH_LONG).show()
        publishNotice()
    }

    private fun complete() {
        val missing = steps.count { !granted(it) }
        stop(if (missing == 0) "所列权限均已授权" else "已授权 ${steps.size - missing}/${steps.size} 项，${missing} 项需手动设置")
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code != REQUEST_PERMISSION || !active) return
        val step = steps.getOrNull(index) ?: return
        if (permissions.singleOrNull() != step.permission) return
        if (!granted(step)) finishStep("未授权或系统要求手动设置")
    }

    private fun finishStep(result: String) {
        if (!active) return
        val step = steps.getOrNull(index) ?: return
        results[step] = result
        index++; opened = false
        deadline = SystemClock.elapsedRealtime() + 15_000L
        updateResults()
        if (index >= steps.size) { complete(); returnToPage(); return }
        returnToPage()
    }

    private fun returnToPage() {
        if (resumed || returned) return
        returned = true
        runCatching { startActivity(Intent(this, PermissionSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            .onFailure { stop("请返回 Doppel 继续设置，或稍后重新开始") }
    }

    override fun onResume() { super.onResume(); resumed = true; returned = false }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_STOP) stop("已停止，已授权的权限会保留")
    }
    override fun onBackPressed() { stop("已停止，已授权的权限会保留"); super.onBackPressed() }
    override fun onDestroy() { release(); super.onDestroy() }

    private fun stop(reason: String) {
        release()
        summary.text = reason
        skip.visibility = android.view.View.GONE
        updateResults()
    }
    private fun release() {
        active = false; handler.removeCallbacksAndMessages(null)
        if (ownsGate) getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
        DirectMode.leaveSettings(this)
        if (ownsGate) { ownsGate = false; TaskSubmissionGate.creating.set(false) }
    }
    private fun updateResults() {
        detail.text = results.entries.joinToString("\n\n") { "${it.key.title}：${it.value}" }
    }
    private fun publishNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "权限设置进度", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun pending(action: String) = PendingIntent.getActivity(this, action.hashCode(),
            Intent(this, PermissionSetupActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        manager.notify(NOTICE_ID, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(if (manualRequired) "请在系统页手动确认权限" else "Doppel 正在设置权限").setContentText(summary.text).setOngoing(true)
            .setContentIntent(pending("dev.doppel.permission_progress"))
            .addAction(Notification.Action.Builder(null, "停止设置", pending(ACTION_STOP)).build()).build())
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)

    companion object {
        private const val REQUEST_PERMISSION = 71
        private const val NOTICE_ID = 4173
        private const val CHANNEL = "doppel_permission_setup"
        private const val ACTION_STOP = "dev.doppel.permission_setup_stop"
        internal fun notificationAccessIntent(context: android.content.Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, LoginNotificationService::class.java).flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
