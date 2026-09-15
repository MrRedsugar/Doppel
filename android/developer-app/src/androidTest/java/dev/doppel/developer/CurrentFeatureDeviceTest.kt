package dev.doppel.developer

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.PowerManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit opt-in only. Real microphone/overlay, demonstration and configured model; no injected replies. */
class CurrentFeatureDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation by lazy { inst.getUiAutomation(1).apply {
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    } }
    private var activity: Activity? = null
    private val folder by lazy {
        val label = args.getString("current_feature_label", "latest").orEmpty()
        require(label.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        File(context.getExternalFilesDir(null), "current-feature-device/$label").apply { check(mkdirs() || isDirectory) }
    }
    private fun await(message: String, timeout: Long = 12000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        assertTrue(message, predicate())
    }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).flatMap { root.getChild(it)?.let(::nodes).orEmpty() }
    private fun visibleNodes() = automation.windows.flatMap { it.root?.let(::nodes).orEmpty() }.filter { it.isVisibleToUser }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun click(label: String) {
        inst.runOnMainSync {
            activity?.window?.decorView?.let(::views)?.filterIsInstance<TextView>()
                ?.firstOrNull { it.text.toString() == label }?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        await("Actual UI control: $label") { visibleNodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label } }
        var node = visibleNodes().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); inst.waitForIdleSync()
    }
    private fun hasText(label: String): Boolean {
        var found = false
        inst.runOnMainSync { found = activity?.window?.decorView?.let(::views)?.filterIsInstance<TextView>()?.any { it.text.toString() == label } == true }
        return found
    }
    private fun setField(hint: String, value: String) {
        inst.runOnMainSync {
            val field = requireNotNull(activity).window.decorView.let(::views).filterIsInstance<EditText>().first { it.hint?.toString() == hint }
            field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true); field.setText(value)
        }
        inst.waitForIdleSync()
    }
    private fun shot(name: String) {
        // Accessibility announces windows before LDPlayer's compositor presents their frame.
        // This delay is only for QA screenshots; it is not agent screen-stability logic.
        Thread.sleep(1200)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private fun runIds(): Set<String> {
        val items = Gateway(context).request("GET", "/runs").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
    }
    private fun idle() {
        if(args.getString("current_feature_consent")=="true") {
            assertFalse("Never change setup during an unfinished task",DirectRuntime.get(context).hasUnfinishedRun())
            check(FirstUseConsent.accept(context));FirstUseConsent.finishGuide(context)
        }
        FirstUseConsent.requireAccepted(context)
        assertTrue("Use the already configured local runtime", DirectMode.isEnabled(context))
        assertFalse("Never interrupt existing tasks", DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse("An executing worker must be left alone", DeviceWorkerService.instance?.isPaused == false)
        assertFalse("Close the existing voice editor", VoiceActivity.isVisible)
        assertFalse("Preserve the current demonstration", DemonstrationSession.active)
        assertNull("Preserve pending user evidence", AppLearning(context).pendingEvidence())
        assertTrue("Preserve pending voice submission", Gateway(context).prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
        assertTrue(Settings.canDrawOverlays(context))
        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
        val lock = context.getSystemService(KeyguardManager::class.java)
        assertFalse(lock.isDeviceLocked || lock.isKeyguardLocked)
        val schedules = ScheduleManager.get(context).request("GET", "/schedules").getJSONArray("items")
        repeat(schedules.length()) { assertFalse("Disable QA schedules before an idle-only test", schedules.getJSONObject(it).getBoolean("enabled")) }
        // Instrumentation may kill the prior process; refresh only this app's binding.
        if(DoppelAccessibilityService.instance==null) {
            val own=ComponentName(context,DoppelAccessibilityService::class.java)
            val others=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty().split(':')
                .filter { entry ->
                    // Repair only our component from this helper's earlier literal-quote bug.
                    // Unknown or quoted other components are retained and validation fails before any write.
                    entry.isNotBlank() && entry!="null" && ComponentName.unflattenFromString(entry.trim { it.code==39 })!=own
                }
            fun shell(command:String) {ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()}}
            val retained=others.joinToString(":")
            require(retained.isEmpty() || retained.matches(Regex("[A-Za-z0-9_.$/:]+")))
            if(others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $retained")
            val enabled=(others+own.flattenToString()).joinToString(":")
            // UiAutomation executes argv directly; shell quoting would become part of the value.
            shell("settings put secure enabled_accessibility_services $enabled")
            assertEquals("Accessibility component values must not contain literal shell quotes",enabled,
                Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services"))
            shell("settings put secure accessibility_enabled 1")
            await("Own accessibility service rebinds without removing other components") {DoppelAccessibilityService.instance!=null}
        }
    }
    private fun pointer(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue("Real pointer injection", automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun swipe(x: Float, y1: Float, y2: Float) {
        val down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y1)
        try { repeat(12) { i -> Thread.sleep(30); pointer(down, MotionEvent.ACTION_MOVE, x, y1 + (y2-y1)*(i+1)/12) } }
        finally { pointer(down, MotionEvent.ACTION_UP, x, y2) }
    }
    private fun closeActivity() { activity?.let { owner -> inst.runOnMainSync { owner.finish() } }; activity = null }

    @Test fun actualOverlayHoldCancelAndOptionalMutedReleaseCreateNoTask() {
        assumeTrue(args.getString("current_feature_voice") == "true")
        idle()
        assertNull("Stop the idle companion first; this test owns exactly one production overlay", DeviceWorkerService.instance)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val audio = context.getSystemService(AudioManager::class.java)
        val testRelease = args.getString("current_feature_release") == "true"
        val previousMicrophoneMute = audio.isMicrophoneMute
        val prefs = Gateway(context).prefs
        val position = listOf("companion_y", "companion_right_edge").associateWith { prefs.all[it] }
        val before = runIds()
        val result = JSONObject().put("ok", false).put("spoken_asr_accuracy_tested", false)
            .put("input", "real_AudioRecord").put("muted_release_tested",testRelease)
        var overlay: CompanionOverlay? = null
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        try {
            if(testRelease) {
                audio.isMicrophoneMute = true
                assertTrue("The optional muted-release fixture requires a genuinely muted microphone", audio.isMicrophoneMute)
            }
            // Android Settings deliberately hides application overlays. Exercise a normal app surface.
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("The normal application surface must be foreground") {
                automation.rootInActiveWindow?.packageName?.toString() == "dev.doppel.testapp"
            }
            inst.runOnMainSync { overlay = CompanionOverlay(context) {}.also { it.show() } }
            for (cancel in if(testRelease) listOf(true, false) else listOf(true)) {
                // waitForMonitorWithTimeout removes its monitor, so each new Activity needs its own.
                monitor = inst.addMonitor(VoiceActivity::class.java.name, null, false)
                // A retained terminal task changes the entry label to “当前任务”; use the actual overlay bounds.
                var bounds: Rect? = null
                await("Visible production companion") {
                    inst.runOnMainSync { bounds = overlay?.bounds()?.let(::Rect) }
                    bounds?.isEmpty == false
                }
                val x = requireNotNull(bounds).exactCenterX(); val y = requireNotNull(bounds).exactCenterY(); val down = SystemClock.uptimeMillis()
                val started = System.currentTimeMillis()
                pointer(down, MotionEvent.ACTION_DOWN, x, y)
                var endX = x
                var endAction = MotionEvent.ACTION_CANCEL
                try {
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(requireNotNull(monitor), 6000)) { "Long hold did not open VoiceActivity" }
                    await("Actual AudioRecord must reach listening state") { hasText("正在听") }
                    Thread.sleep(700); shot(if(cancel) "voice-held-before-cancel" else "voice-held-before-release")
                    if(cancel) {
                        val width = context.resources.displayMetrics.widthPixels
                        endX = if(x > width/2f) width*0.2f else width*0.8f
                        pointer(down, MotionEvent.ACTION_MOVE, endX, y)
                        await("The real cancel handler must run before pointer release") { hasText("已取消") || !VoiceActivity.isVisible }
                    } else {
                        assertTrue("System microphone mute must remain set until release",audio.isMicrophoneMute)
                    }
                    endAction = MotionEvent.ACTION_UP
                } finally { pointer(down, endAction, endX, y) }
                await("Hold flow closes after cancellation or mute rejection", 12000) { !VoiceActivity.isVisible }
                assertEquals("Cancelled or muted input must not create any task", before, runIds())
                if(cancel) result.put("cancel_closed_without_task", true) else {
                    val file = File(context.filesDir, "speech-diagnostic.json")
                    await("A fresh terminal recording diagnostic is required") { runCatching {
                        val d=JSONObject(file.readText());d.getLong("at") >= started && d.optString("stage")=="failed"
                    }.getOrDefault(false) }
                    val diagnostic = JSONObject(file.readText())
                    assertTrue(diagnostic.getBoolean("capture_ready"))
                    assertEquals("MICROPHONE_MUTED", diagnostic.getString("failure_code"))
                    assertEquals("embedded_offline", diagnostic.getString("connection"))
                    assertEquals(0, diagnostic.getInt("preview_requests")); assertEquals(0, diagnostic.getInt("final_requests"))
                    result.put("muted_release_diagnostic", diagnostic).put("muted_release_without_task", true)
                }
                closeActivity()
            }
            result.put("ok", true)
        } finally {
            closeActivity(); monitor?.let(inst::removeMonitor)
            inst.runOnMainSync { overlay?.close() }
            if(testRelease) {
                audio.isMicrophoneMute = previousMicrophoneMute
                assertEquals("Restore the microphone state after the fixture", previousMicrophoneMute, audio.isMicrophoneMute)
            }
            val edit = prefs.edit()
            position.forEach { (key, value) -> when(value) { null -> edit.remove(key); is Int -> edit.putInt(key,value); is Boolean -> edit.putBoolean(key,value) } }
            check(edit.commit())
            File(folder, "voice.json").writeText(result.toString(2))
        }
    }

    @Test fun actualSettingsDemonstrationGeneratesReviewsSavesAndRemovesOwnSkill() {
        assumeTrue(args.getString("current_feature_demo") == "true")
        idle()
        if(Build.VERSION.SDK_INT in 26..29 && !LegacyScreenCaptureService.isReady) {
            assertEquals("Explicit opt-in is needed to grant this QA screen-sharing session","true",args.getString("current_feature_consent"))
            LegacyCaptureConsent.authorize(inst)
        }
        val learning = AppLearning(context); val beforeRuns = runIds()
        val uniqueTitle = "真实示教验收-" + UUID.randomUUID().toString().take(8)
        val goal = "$uniqueTitle：打开网络和互联网，再返回设置首页，不修改设置"
        val existing = learning.manual.list().getJSONArray("items")
        val beforeNames = (0 until existing.length()).map { existing.getJSONObject(it).getString("name") }.toSet()
        val result = JSONObject().put("ok",false).put("model_calls_requested",args.getString("current_feature_models") == "true")
            .put("input_actions","instrumentation accessibility click and global Back in actual Settings, not a human participant")
        var ownsDemo = false; var sourceId: String? = null
        try {
            activity = inst.startActivitySync(Intent(context,LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("手动示范一次")
            val settingsLabel = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                .first { it.activityInfo.packageName=="com.android.settings" }.loadLabel(context.packageManager).toString()
            for(attempt in 0..15) {
                if(visibleNodes().any {it.text?.toString()==settingsLabel}) break
                val scroll=visibleNodes().firstOrNull {it.isScrollable && it.packageName?.toString()==context.packageName}
                    ?: error("The application picker has no scrollable list")
                check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {"Settings is not in the app picker"}
                Thread.sleep(150)
            }
            shot("demonstration-app-picker")
            click(settingsLabel)
            await("Real demonstration is active") { DemonstrationSession.active }; ownsDemo = true
            // Read only the actual session's identity so failure cleanup cannot delete another draft.
            inst.runOnMainSync {
                val field=DemonstrationSession::class.java.getDeclaredField("run").apply { isAccessible=true }
                sourceId=(field.get(DemonstrationSession) as JSONObject).getString("source_id")
            }
            assertEquals("com.android.settings",DemonstrationSession.status().getString("package_name"))
            await("Actual Settings visible") { automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings" }
            await("At least one actual captured frame",20000) { DemonstrationSession.status().optInt("screenshots")>0 }
            // This tall display fits the whole Settings homepage, so scrolling is a no-op.
            // Navigate an actual read-only page and verify its content before recording Back.
            click("网络和互联网")
            await("Network detail page is visible") { visibleNodes().any { it.text?.toString()=="WLAN" } }
            Thread.sleep(2300); shot("settings-network")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await("Returned to Settings homepage") { visibleNodes().any { it.text?.toString()=="在设置中搜索" } }
            Thread.sleep(2300); shot("settings-demonstration")
            val review = inst.addMonitor(LearningActivity::class.java.name,null,false)
            try {
                click("演示结束")
                activity = requireNotNull(inst.waitForMonitorWithTimeout(review,20000)) { "Demonstration review did not open" }
            } finally { inst.removeMonitor(review) }
            val pending = requireNotNull(learning.pendingEvidence());sourceId=pending.getString("source_id");ownsDemo = false
            assertEquals("human_demonstration",pending.getString("origin"))
            assertTrue(pending.getJSONArray("screenshots").length()>0)
            val events=pending.getJSONArray("events")
            assertTrue("Record actual Settings navigation", (0 until events.length()).any {
                events.getJSONObject(it).optString("kind") in setOf("observed_click","window_changed") && events.getJSONObject(it).optString("package_name")=="com.android.settings"
            })
            setField("例如：在设置中开启深色模式",goal)
            setField("补充截图没记录到的步骤或结果（可选）","本次QA通过无障碍点击打开系统设置的网络和互联网页面，再通过系统返回回到设置首页，未修改任何设置。不是人工参与实验；轨迹只证明这些页面上的可见操作。")
            await("Review must be visibly foreground, not merely constructed") {
                automation.rootInActiveWindow?.packageName?.toString()==context.packageName &&
                    visibleNodes().any {it.text?.toString()=="刚才完成了什么任务？"}
            }
            automation.waitForIdle(300,3000)
            result.put("source_id",sourceId).put("observed_events",events.length())
                .put("captured_frames",pending.getJSONArray("screenshots").length())
            if(args.getString("current_feature_models") != "true") {
                shot("demonstration-review")
                assertEquals(beforeRuns,runIds())
                result.put("ok",true).put("model_generation_tested",false).put("saved_skill",false)
                    .put("coverage","actual_capture_and_review_only")
                return
            }
            click("生成 Skill 草稿")
            await("Configured real model returns editable draft",180000) { hasText("预览并编辑 Skill") }
            assertTrue(requireNotNull(learning.pendingEvidence()).has("generated_draft"))
            setField("标题",uniqueTitle);shot("demonstration-model-draft");click("保存 Skill")
            await("Own reviewed skill saved") {
                val items=learning.manual.list().getJSONArray("items")
                (0 until items.length()).any { items.getJSONObject(it).optString("title")==uniqueTitle && items.getJSONObject(it).optString("name") !in beforeNames }
            }
            assertNull(learning.pendingEvidence());assertEquals(beforeRuns,runIds())
            result.put("ok",true).put("source_id",sourceId).put("observed_events",events.length())
                .put("captured_frames",pending.getJSONArray("screenshots").length()).put("saved_and_cleanup_requested",true)
        } catch(error:Throwable) {
            result.put("failure_type",error.javaClass.simpleName)
            runCatching {shot("demonstration-failure")}
            throw error
        } finally {
            if(ownsDemo) inst.runOnMainSync { DemonstrationSession.cancel() }
            val pending=learning.pendingEvidence()
            if(pending!=null && ((sourceId!=null && pending.optString("source_id")==sourceId) || pending.optString("user_reported_goal")==goal)) learning.clearPendingEvidence()
            val items=learning.manual.list().getJSONArray("items")
            repeat(items.length()) { i -> val item=items.getJSONObject(i)
                if(item.optString("title")==uniqueTitle && item.optString("name") !in beforeNames) learning.manual.delete(item.getString("name"))
            }
            val remaining=learning.manual.list().getJSONArray("items")
            val ownRemoved=(0 until remaining.length()).none { remaining.getJSONObject(it).optString("title")==uniqueTitle && remaining.getJSONObject(it).optString("name") !in beforeNames }
            result.put("own_skill_removed",ownRemoved)
            closeActivity();File(folder,"demonstration.json").writeText(result.toString(2))
            assertTrue("Only the invocation-owned QA skill is removed",ownRemoved)
        }
    }
}
