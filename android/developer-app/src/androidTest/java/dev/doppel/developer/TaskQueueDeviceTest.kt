@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run each method in a fresh disposable dev.doppel.queueqa process, with -e queue_qa true.
 * The host grants accessibility/overlay and restores those global grants afterwards.
 * No credentials or task records from either installed user app are read or changed.
 * seedForForceStop emits queue_restart_ready; host must force-stop and run verifyAfterForceStop.
 */
class TaskQueueDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val prefs get() = context.getSharedPreferences("doppel", 0)
    private val gateway get() = Gateway(context)
    private val fixture = "dev.doppel.testapp"
    private val manifest get() = File(context.noBackupFilesDir, "queue-restart-qa.json")
    private val runtimeField get() = DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var automationFlags: Int? = null

    @Test fun idleComposerMustNotPauseQueue() {
        guard()
        LocalModel().use { model ->
            configure(model.port)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Task screen must be visible") { visibleText().contains("开始任务") }
            assertTrue(TaskControl.startWorker(context))
            await("The explicitly enabled floating service must be idle and unpaused") {
                DeviceWorkerService.instance?.isPaused == false && prefs.getString("active_run", "").isNullOrBlank()
            }
            assertFalse(prefs.getBoolean("queue_dispatch_paused", false))
            tapCompanion()
            await("The real floating entry must open keyboard task input") { VoiceActivity.keyboardVisible }
            saveScreenshot("idle-composer.png")
            assertFalse("Opening task input with no active task must not require 恢复队列调度",
                prefs.getBoolean("queue_dispatch_paused", false))
            assertTrue(prefs.getString("active_run", "").isNullOrBlank())
            assertEquals(0, model.total.get())
            evidence("idle-composer", JSONObject().put("passed", true).put("real_pointer_click", true)
                .put("queue_dispatch_paused", false).put("model_requests", 0))
        }
    }

    @Test fun firstUiSubmissionCancelAndIdleComposerDoNotPauseQueue() {
        guard()
        LocalModel(blockFirst = true, finishWithoutGesture = true).use { model ->
            configure(model.port)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = resumedActivity(MainActivity::class.java)
            assertNull("The first UI submission must start its own Worker", DeviceWorkerService.instance)
            enterGoal(activity, "QUEUEQA_A")
            tapControl(activity, "开始任务")
            assertTrue("The first UI submission must execute without a manual queue wakeup", model.blocked.await(25, TimeUnit.SECONDS))
            val first = prefs.getString("active_run", "").orEmpty()
            assertTrue(first.isNotBlank()); assertEquals("running", status(first))

            // Some devices omit this event. Inject it into the real callback explicitly,
            // separately from the real MotionEvent taps used for every UI control below.
            injectTouchStart()
            assertFalse("Disabled touch takeover must leave the running Worker active", DeviceWorkerService.instance!!.isPaused)
            assertEquals("running", status(first))
            check(prefs.edit().putBoolean("touch_pause", true).commit())
            injectTouchStart()
            await("Enabled touch takeover must pause the executing task") { status(first) == "paused" }
            assertTrue(DeviceWorkerService.instance!!.isPaused)
            tapControl(activity, "停止任务")
            model.discardBlocked = true; model.release.countDown()
            await("UI cancellation must clear the execution owner") {
                status(first) == "cancelled" && prefs.getString("active_run", "").isNullOrBlank()
            }

            tapControl(activity, "新任务")
            enterGoal(activity, "QUEUEQA_B")
            tapControl(activity, "开始任务")
            val second = awaitSelectedRun(first)
            await("The task created after cancellation must execute without 恢复队列调度", 25000) { status(second) == "completed" }
            await("Completion must leave an idle service ready for another task") {
                prefs.getString("active_run", "").isNullOrBlank() && DeviceWorkerService.instance?.isPaused == false
            }
            injectTouchStart()
            assertFalse("An idle touch must not pause queue scheduling", prefs.getBoolean("queue_dispatch_paused", false))
            tapCompanion()
            val composer = resumedActivity(VoiceActivity::class.java)
            assertFalse("Opening idle floating input must not pause queue scheduling", prefs.getBoolean("queue_dispatch_paused", false))
            enterGoal(composer, "QUEUEQA_C")
            tapControl(composer, "开始任务")
            val third = awaitSelectedRun(second)
            await("A task from the real idle floating input must execute automatically", 25000) { status(third) == "completed" }
            model.assertHealthy()
            assertEquals("cancelled", status(first)); assertTrue(queueIds().isEmpty())
            assertEquals(3, model.total.get()); assertEquals(2, model.routed.get())
            evidence("first-ui-submission", JSONObject().put("passed", true).put("real_pointer_clicks", true)
                .put("manual_queue_wakeups", 0).put("prestarted_worker", false).put("touch_start_events_injected", 3)
                .put("touch_disabled_kept_running", true).put("touch_enabled_paused_running", true)
                .put("idle_touch_and_composer_kept_queue_ready", true).put("model_requests", model.total.get())
                .put("first_status", status(first)).put("second_status", status(second)).put("third_status", status(third)))
        }
    }

    private fun injectTouchStart() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        try { inst.runOnMainSync { requireNotNull(DoppelAccessibilityService.instance).onAccessibilityEvent(event) } }
        finally { event.recycle() }
    }

    private fun awaitSelectedRun(previous: String): String {
        var selected = ""
        await("UI submission must select a new task") {
            selected = gateway.selectedConversationRun().orEmpty()
            selected.isNotBlank() && selected != previous
        }
        return selected
    }

    private fun resumedActivity(type: Class<out Activity>): Activity {
        var result: Activity? = null
        await("${type.simpleName} must be resumed") {
            inst.runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull(type::isInstance)
            }
            result != null
        }
        return requireNotNull(result)
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun enterGoal(activity: Activity, goal: String) {
        inst.runOnMainSync { views(activity.window.decorView).filterIsInstance<EditText>().first { it.isShown }.setText(goal) }
    }

    private fun tapControl(activity: Activity, label: String) {
        var target: View? = null
        await("The $label UI control must be available") {
            inst.runOnMainSync {
                target = views(activity.window.decorView).firstOrNull {
                    it.isShown && it.isEnabled && it.isClickable &&
                        (it.contentDescription?.toString() == label || (it is TextView && it.text.toString() == label))
                }
                target?.let { it.requestRectangleOnScreen(android.graphics.Rect(0, 0, it.width, it.height), true) }
            }
            target != null
        }
        inst.waitForIdleSync(); SystemClock.sleep(300)
        val bounds = android.graphics.Rect()
        inst.runOnMainSync {
            val view = requireNotNull(target)
            assertTrue("$label must be on screen", view.getGlobalVisibleRect(bounds))
            val position = IntArray(2); view.getLocationOnScreen(position)
            bounds.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapCompanion() {
        var bounds: android.graphics.Rect? = null
        await("The real floating entry must be visible") {
            inst.runOnMainSync {
                val worker = DeviceWorkerService.instance
                val overlay = worker?.let { DeviceWorkerService::class.java.getDeclaredField("overlay")
                    .apply { isAccessible = true }.get(it) as? CompanionOverlay }
                bounds = overlay?.bounds()
            }
            bounds?.isEmpty == false
        }
        tapAt(requireNotNull(bounds).exactCenterX(), requireNotNull(bounds).exactCenterY())
    }

    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("The real pointer event must be delivered", ui.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    @After fun restoreAutomationFlags() {
        automationFlags?.let { flags -> ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags } }
    }

    private fun guard(fresh: Boolean = true) {
        assertEquals("Disposable test package is mandatory", "dev.doppel.queueqa", context.packageName)
        assertEquals("Use explicit isolated test opt-in", "true", InstrumentationRegistry.getArguments().getString("queue_qa"))
        assertNull("Host must restart the isolated process before each method", DeviceWorkerService.instance)
        assertNull("No task runtime may precede test setup", runtimeField.get(null))
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(Settings.canDrawOverlays(context))
        assertFalse(TaskSubmissionGate.creating.get())
        if (fresh) {
            assertFalse("Finish the pending force-stop recovery first", manifest.exists())
            // The host clears only dev.doppel.queueqa between independent cases.
            assertFalse("Fresh disposable package required", File(context.noBackupFilesDir, "direct-runs-v1.json").exists())
            check(prefs.edit().putBoolean("direct_mode", true).putString("device_id", DirectRuntime.DEVICE_ID)
                .remove("active_run").putBoolean("queue_dispatch_paused", false).putBoolean("touch_pause", false)
                .putBoolean("completion_speech", false).commit())
            check(context.getSharedPreferences("doppel_gui_grounding", 0).edit().putBoolean("enabled", false).commit())
            check(FirstUseConsent.accept(context)); check(FirstUseConsent.finishGuide(context))
        }
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        automationFlags = ui.serviceInfo.flags
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        context.packageManager.getPackageInfo(fixture, 0)
    }

    @Test fun mixedQueueCancelPauseResumeAndRealEffects() {
        guard()
        val session = UUID.randomUUID().toString()
        openFixture(session)
        LocalModel(blockFirst = true).use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A")
            assertEquals("queued", a.getString("status"))
            assertTrue(TaskControl.wakeQueue(context))
            assertTrue("A must reach the real provider transport", model.blocked.await(25, TimeUnit.SECONDS))
            val aId = a.getString("id")
            assertEquals(aId, prefs.getString("active_run", ""))
            val ticket = TaskControl.currentGeneration()
            val b = create("QUEUEQA_B", "schedule")
            val c = create("QUEUEQA_C")
            assertEquals(listOf(aId, b.getString("id"), c.getString("id")), queueIds())
            assertEquals("running", status(aId))
            assertEquals("queued", b.getString("status")); assertEquals("queued", c.getString("status"))
            assertFalse(b.getBoolean("conversation_enabled"))
            assertEquals(1, model.total.get()); assertEquals(0, state(session).getInt("taps"))
            assertEquals("cancelled", control(b.getString("id"), "cancel").getString("status"))
            assertEquals("Cancelling B cannot invalidate A's pending operation", ticket, TaskControl.currentGeneration())
            assertEquals(aId, prefs.getString("active_run", ""))
            assertEquals("running", status(aId)); assertFalse(DeviceWorkerService.instance!!.isPaused)
            assertEquals("paused", control(aId, "pause").getString("status"))
            model.discardBlocked = true
            model.release.countDown()
            SystemClock.sleep(1400)
            assertEquals("queued", status(c.getString("id")))
            assertEquals(1, model.total.get()); assertEquals(0, state(session).getInt("taps"))
            assertEquals("running", control(aId, "resume").getString("status"))
            assertTrue(TaskControl.startWorker(context))
            await("A and then C must finish through real Worker gestures", 50000) {
                status(aId) == "completed" && status(c.getString("id")) == "completed"
            }
            await("Both real MotionEvents must reach the independent fixture") { state(session).optInt("taps") == 2 }
            model.assertHealthy()
            assertEquals(listOf("QUEUEQA_A", "QUEUEQA_C"), model.grounded.toList())
            assertEquals(2, state(session).getInt("down")); assertEquals(2, state(session).getInt("up"))
            assertEquals(0, state(session).getInt("cancel")); assertTrue(queueIds().isEmpty())
            val count = model.total.get(); SystemClock.sleep(1600)
            assertEquals("Completed/removed runs must not issue duplicate requests", count, model.total.get())
            evidence("mixed", JSONObject().put("passed", true).put("cancelled_queued_did_not_invalidate", true)
                .put("paused_head_blocked_next", true).put("gesture_order", JSONArray(model.grounded))
                .put("motion_events", state(session)).put("model_requests", model.total.get()))
        }
    }

    @Test fun runningCancelAdvancesAndDropsLateReply() {
        guard()
        val session = UUID.randomUUID().toString(); openFixture(session)
        LocalModel(blockFirst = true).use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A"); val aId = a.getString("id")
            assertTrue(TaskControl.wakeQueue(context)); assertTrue(model.blocked.await(25, TimeUnit.SECONDS))
            val b = create("QUEUEQA_B"); val bId = b.getString("id")
            assertEquals("running", status(aId)); assertEquals("queued", status(bId))
            val ticket = TaskControl.currentGeneration()
            assertEquals("cancelled", control(aId, "cancel").getString("status"))
            assertNotEquals("Cancelling an executing owner must invalidate its in-flight work", ticket, TaskControl.currentGeneration())
            model.discardBlocked = true; model.release.countDown()
            await("Cancellation must release B after dropping A's late response", 40000) { status(bId) == "completed" }
            model.assertHealthy(); assertEquals("cancelled", status(aId))
            assertEquals(listOf("QUEUEQA_B"), model.grounded.toList())
            assertEquals(1, state(session).getInt("taps")); assertEquals(1, state(session).getInt("down"))
            assertEquals(1, state(session).getInt("up")); assertTrue(queueIds().isEmpty())
            val count = model.total.get(); SystemClock.sleep(1500)
            assertEquals(count, model.total.get()); assertEquals(1, state(session).getInt("taps"))
            evidence("running-cancel", JSONObject().put("passed", true).put("late_reply_was_not_executed", true)
                .put("cancelled_state_retained", true).put("gesture_order", JSONArray(model.grounded)).put("motion_events", state(session)))
        }
    }

    @Test fun failedHeadAdvancesToNext() {
        guard()
        val session = UUID.randomUUID().toString(); openFixture(session)
        LocalModel(firstGoalFails = true).use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A"); val b = create("QUEUEQA_B")
            assertEquals(listOf(a.getString("id"), b.getString("id")), queueIds())
            assertTrue(TaskControl.wakeQueue(context))
            await("A's actual failed result must release B automatically", 40000) {
                status(a.getString("id")) == "failed" && status(b.getString("id")) == "completed"
            }
            model.assertHealthy(); assertEquals(listOf("QUEUEQA_B"), model.grounded.toList())
            assertEquals(1, state(session).getInt("taps")); assertEquals(1, state(session).getInt("up"))
            assertTrue(queueIds().isEmpty())
            evidence("failed-head", JSONObject().put("passed", true).put("first_status", "failed").put("second_status", "completed")
                .put("gesture_order", JSONArray(model.grounded)).put("motion_events", state(session)))
        }
    }

    @Test fun automaticHeadPreviewsOnlyAfterPredecessorAndExecutes() {
        guard()
        val source = InstrumentationRegistry.getArguments().getString("automatic_source") ?: "schedule"
        require(source in setOf("schedule", "trigger"))
        val session = UUID.randomUUID().toString(); openFixture(session)
        LocalModel(blockFirst = true).use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A")
            assertTrue(TaskControl.wakeQueue(context))
            assertTrue(model.blocked.await(25, TimeUnit.SECONDS))
            val b = create("QUEUEQA_B", source); val c = create("QUEUEQA_C")
            val key = "queued:${b.getString("id")}"
            SystemClock.sleep(1600)
            assertNull("Waiting automatic work cannot show a countdown over A", AutomaticTaskNotice.generation(key))
            assertFalse("Waiting automatic work cannot acquire unlock protection", AutomaticUnlockSession.active)
            assertEquals(1, model.total.get()); assertEquals("queued", status(b.getString("id")))
            assertEquals(a.getString("id"), prefs.getString("active_run", ""))
            model.release.countDown()
            await("Automatic head must show its real countdown after A completes", 25000) {
                AutomaticTaskNotice.generation(key) != null && allWindowText().contains("秒后开始自动任务")
            }
            val noticedAt = SystemClock.elapsedRealtime()
            assertEquals("completed", status(a.getString("id")))
            assertEquals("queued", status(b.getString("id"))); assertEquals("queued", status(c.getString("id")))
            assertEquals(1, state(session).getInt("taps"))
            saveScreenshot("$source-head-countdown.png")
            val callsAtNotice = model.total.get()
            val until = noticedAt + 12000
            while (SystemClock.elapsedRealtime() < until) {
                assertEquals("A full countdown must precede automatic execution", "queued", status(b.getString("id")))
                assertEquals("queued", status(c.getString("id"))); assertEquals(callsAtNotice, model.total.get())
                SystemClock.sleep(400)
            }
            await("The automatic task and following manual task must actually execute", 50000) {
                status(b.getString("id")) == "completed" && status(c.getString("id")) == "completed"
            }
            model.assertHealthy()
            assertEquals(listOf("QUEUEQA_A", "QUEUEQA_B", "QUEUEQA_C"), model.grounded.toList())
            assertEquals(3, state(session).getInt("taps")); assertEquals(3, state(session).getInt("up"))
            assertNull(AutomaticTaskNotice.generation(key)); assertFalse(AutomaticUnlockSession.active)
            assertTrue(queueIds().isEmpty())
            evidence("automatic-$source", JSONObject().put("passed", true).put("automatic_source", source)
                .put("no_notice_while_waiting", true).put("countdown_observed_ms", SystemClock.elapsedRealtime() - noticedAt)
                .put("no_model_during_first_12_seconds", true).put("gesture_order", JSONArray(model.grounded)).put("motion_events", state(session)))
        }
    }

    @Test fun uncertainStartPausesBeforeAnyActionUntilExplicitResume() {
        guard()
        val session = UUID.randomUUID().toString(); openFixture(session)
        LocalModel().use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A"); val b = create("QUEUEQA_B")
            val id = a.getString("id")
            assertEquals("running", gateway.request("POST", "/runs/$id/start", JSONObject()).getString("status"))
            val pendingKey = "queue_start_pending_${gateway.captureReviewConnection().scope}"
            check(prefs.edit().putString(pendingKey, id).remove("active_run").commit())
            assertTrue(TaskControl.wakeQueue(context))
            await("Unacknowledged promotion must be reconciled into paused state") { status(id) == "paused" }
            await("Dispatcher must bind the paused head without resuming it") { prefs.getString("active_run", "") == id }
            SystemClock.sleep(1500)
            assertEquals(0, model.total.get()); assertEquals(0, state(session).getInt("taps"))
            assertEquals("queued", status(b.getString("id")))
            assertTrue(DeviceWorkerService.instance!!.isPaused)
            assertTrue(prefs.getString(pendingKey, "").isNullOrBlank())
            assertEquals("running", control(id, "resume").getString("status")); assertTrue(TaskControl.startWorker(context))
            await("Explicit resume must complete A and release B", 50000) { status(id) == "completed" && status(b.getString("id")) == "completed" }
            model.assertHealthy(); assertEquals(listOf("QUEUEQA_A", "QUEUEQA_B"), model.grounded.toList())
            assertEquals(2, state(session).getInt("taps"))
            evidence("uncertain-start", JSONObject().put("passed", true).put("model_requests_before_resume", 0)
                .put("gesture_order", JSONArray(model.grounded)).put("motion_events", state(session)))
        }
    }

    @Test fun oneWorkerCompletionAlertsOnceWithUserAudibleOngoingChannel() {
        guard()
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(notifications.areNotificationsEnabled())
        TaskCompletionDelivery.registerChannels(context)
        val sound = android.net.Uri.parse("android.resource://${context.packageName}/raw/task_completed")
        notifications.createNotificationChannel(NotificationChannel("device", "任务执行", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(sound, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
        })
        // Only Settings can represent the user's channel choice. Toggle away and back so the
        // worker cannot downgrade this existing audible channel when registering LOW defaults.
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "device")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("System settings must show the execution channel") { visibleText().contains("任务执行") }
        assertTrue("Select the real system Silent option", clickSetting(setOf("Silent", "静音", "无声", "静默")))
        await("System channel must enter Silent") { notifications.getNotificationChannel("device").importance < NotificationManager.IMPORTANCE_DEFAULT }
        assertTrue("Restore the real system Alerting option", clickSetting(setOf("Default", "Alert", "Alerting", "默认", "提醒", "有声")))
        await("User must have explicitly restored an audible channel") { notifications.getNotificationChannel("device").importance >= NotificationManager.IMPORTANCE_DEFAULT }
        val session = UUID.randomUUID().toString(); openFixture(session)
        val deviceBefore = alertCount(21)
        LocalModel().use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A"); val id = a.getString("id")
            assertTrue(TaskControl.wakeQueue(context))
            await("The one real task must complete", 35000) { status(id) == "completed" }
            await("Real completion notification must be posted") { notifications.activeNotifications.any { it.id == id.hashCode() } }
            SystemClock.sleep(2000)
            model.assertHealthy(); assertEquals(1, state(session).getInt("taps"))
            assertTrue("The app must preserve the user's existing audible channel",
                notifications.getNotificationChannel("device").importance >= NotificationManager.IMPORTANCE_DEFAULT)
            assertEquals("Only the service's first notification may alert; terminal status updates must not beep again", 1, alertCount(21) - deviceBefore)
            assertEquals("The actual successful task must play its completion sound once", 1, alertCount(id.hashCode()))
            val ongoing = notifications.activeNotifications.single { it.id == 21 }
            assertTrue(ongoing.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            val completed = gateway.request("GET", "/runs/$id")
            val originalPost = notifications.activeNotifications.single { it.id == id.hashCode() }.postTime
            var repeatDelivery: TaskCompletionDelivery? = null
            try {
                inst.runOnMainSync { repeatDelivery = TaskCompletionDelivery(context) }
                repeat(3) { requireNotNull(repeatDelivery).deliver(completed) }
                inst.waitForIdleSync(); SystemClock.sleep(1000)
                assertEquals(originalPost, notifications.activeNotifications.single { it.id == id.hashCode() }.postTime)
                assertEquals(1, alertCount(id.hashCode()))
            } finally { inst.runOnMainSync { repeatDelivery?.close() } }
            evidence("completion-single-alert", JSONObject().put("passed", true).put("actual_worker_completed", true)
                .put("audible_device_channel_preserved", true).put("device_alerts", alertCount(21) - deviceBefore)
                .put("completion_alerts", alertCount(id.hashCode())).put("second_delivery_instance_deduplicated", true))
        }
    }

    @Test fun concurrentAdmissionQueuePauseAndUi() {
        guard()
        LocalModel().use { model ->
            configure(model.port)
            check(prefs.edit().putBoolean("queue_dispatch_paused", true).commit())
            val threads = Executors.newFixedThreadPool(6)
            val ready = CountDownLatch(6); val start = CountDownLatch(1)
            val futures = (0..5).map { index -> threads.submit<JSONObject> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                create("QUEUEQA_${('A'.code + index).toChar()}", if (index % 2 == 0) "trigger" else "user")
            } }
            val runs = try { assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown(); futures.map { it.get(12, TimeUnit.SECONDS) } }
                finally { threads.shutdownNow() }
            val ordered = runs.sortedBy { it.getLong("queue_sequence") }
            assertEquals(6, runs.map { it.getString("id") }.toSet().size)
            assertEquals(ordered.map { it.getString("id") }, queueIds())
            assertTrue(runs.all { it.getString("status") == "queued" })
            assertTrue(TaskControl.wakeQueue(context)); SystemClock.sleep(1500)
            assertEquals(0, model.total.get()); assertTrue(prefs.getString("active_run", "").isNullOrBlank())
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Queue UI must display all admitted tasks and its recovery action") {
                val text = visibleText()
                text.contains("任务队列 · 6") && text.contains("恢复队列调度") && text.contains("取消排队") && text.contains("自动任务")
            }
            saveScreenshot("concurrent-queue-ui.png")
            val first = ordered.first()
            val duplicate = create(first.getString("goal"), first.getString("source"))
            assertEquals("Stable request_id must deduplicate admission", first.getString("id"), duplicate.getString("id"))
            assertEquals(6, queueIds().size)
            // Remove the automatic rows, then prove the same dispatcher resumes a remaining manual head.
            ordered.filter { it.getString("source") != "user" }.forEach { control(it.getString("id"), "cancel") }
            val manual = ordered.filter { it.getString("source") == "user" }
            val session = UUID.randomUUID().toString(); openFixture(session)
            check(prefs.edit().putBoolean("queue_dispatch_paused", false).commit())
            assertTrue(TaskControl.wakeQueue(context))
            await("Resuming queue scheduling must drain manual rows in admission order", 60000) { manual.all { status(it.getString("id")) == "completed" } }
            model.assertHealthy()
            assertEquals(manual.map { it.getString("goal") }, model.grounded.toList())
            assertEquals(manual.size, state(session).getInt("taps")); assertTrue(queueIds().isEmpty())
            evidence("concurrent", JSONObject().put("passed", true).put("admitted", JSONArray(ordered.map { it.getString("goal") }))
                .put("executed", JSONArray(model.grounded)).put("queue_ui_verified", true).put("duplicate_was_deduplicated", true))
        }
    }

    @Test fun seedForForceStop() {
        guard()
        val session = UUID.randomUUID().toString(); openFixture(session)
        LocalModel(blockFirst = true).use { model ->
            configure(model.port)
            val a = create("QUEUEQA_A")
            assertTrue(TaskControl.wakeQueue(context))
            assertTrue("A must be pending before process death", model.blocked.await(25, TimeUnit.SECONDS))
            val b = create("QUEUEQA_B"); val c = create("QUEUEQA_C")
            val ids = listOf(a, b, c).map { it.getString("id") }
            assertEquals(ids, queueIds()); assertEquals(0, state(session).getInt("taps"))
            manifest.writeText(JSONObject().put("pid", Process.myPid()).put("ids", JSONArray(ids)).put("session", session).toString())
            evidence("seed", JSONObject().put("ready", true).put("ids", JSONArray(ids)).put("pid", Process.myPid()).put("motion_events", state(session)))
            inst.sendStatus(0, Bundle().apply { putString("queue_restart_ready", "true"); putInt("queue_restart_pid", Process.myPid()) })
            SystemClock.sleep(45000)
            fail("Host must force-stop dev.doppel.queueqa within 45 seconds of queue_restart_ready")
        }
    }

    @Test fun verifyAfterForceStop() {
        guard(fresh = false)
        val saved = JSONObject(manifest.readText())
        assertNotEquals("A real OS process termination is mandatory", saved.getInt("pid"), Process.myPid())
        val ids = strings(saved.getJSONArray("ids")); val session = saved.getString("session")
        assertEquals("paused", status(ids[0]))
        assertEquals(listOf("paused", "queued", "queued"), ids.map(::status))
        assertEquals(ids, queueIds())
        assertEquals(0, state(session).getInt("taps"))
        LocalModel().use { model ->
            configure(model.port)
            // Exercise a recovered head whose transient active pointer was lost independently.
            check(prefs.edit().remove("active_run").putBoolean("queue_dispatch_paused", true).commit())
            assertEquals("cancelled", control(ids[0], "cancel").getString("status"))
            assertEquals(listOf(ids[1], ids[2]), queueIds())
            SystemClock.sleep(1500)
            assertEquals(0, model.total.get()); assertEquals(0, state(session).getInt("taps"))
            check(prefs.edit().putBoolean("queue_dispatch_paused", false).commit())
            assertTrue(TaskControl.wakeQueue(context))
            await("Surviving queue must execute B then C exactly once", 50000) { ids.drop(1).all { status(it) == "completed" } }
            model.assertHealthy(); assertEquals(listOf("QUEUEQA_B", "QUEUEQA_C"), model.grounded.toList())
            assertEquals(2, state(session).getInt("taps")); assertEquals(2, state(session).getInt("down")); assertTrue(queueIds().isEmpty())
            evidence("recovery", JSONObject().put("passed", true).put("seed_pid", saved.getInt("pid")).put("recovered_pid", Process.myPid())
                .put("recovered_states", JSONArray(listOf("paused", "queued", "queued")))
                .put("missing_pointer_head_cancelled", true).put("gesture_order", JSONArray(model.grounded)).put("motion_events", state(session)))
            check(manifest.delete())
        }
    }

    private fun create(goal: String, source: String = "user"): JSONObject {
        val body = JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", goal).put("mode", "full")
            .put("source", source).put("request_id", "queue-qa:$goal").put("defer_start", true).put("conversation_enabled", source == "user")
        return if (source == "user") gateway.request("POST", "/runs", body) else gateway.createAutomaticRun(body)
    }
    private fun status(id: String) = gateway.runStatus(id)
    private fun queueIds() = gateway.request("GET", "/devices/${DirectRuntime.DEVICE_ID}/queue").getJSONArray("items").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
    }
    private fun control(id: String, action: String): JSONObject {
        val done = CountDownLatch(1); var result: JSONObject? = null; var error: String? = null
        inst.runOnMainSync { TaskControl.request(context, id, action) { r, e -> result = r; error = e; done.countDown() } }
        assertTrue("$action callback must finish", done.await(15, TimeUnit.SECONDS))
        assertNull("$action failed: $error", error)
        return requireNotNull(result)
    }
    private fun configure(port: Int) {
        val providers = ModelProviders(context)
        val provider = ModelProvider("queue-local", "队列隔离验证", "http://127.0.0.1:$port/v1")
        providers.saveProvider(provider, "synthetic-local-only", emptyMap())
        listOf("fixture-primary", "fixture-grounding").forEach { model ->
            providers.recordVision(provider.id, model, ModelVision.VERIFIED, providers.requestTarget(provider.id, model).fingerprint)
        }
        providers.saveRouting(ModelRouting(ModelSelection(provider.id, "fixture-primary"), true, ModelSelection(provider.id, "fixture-grounding")))
        assertTrue(providers.isReady())
    }
    private fun openFixture(session: String) {
        context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity").putExtra("mode", "events")
            .putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Independent fixture must be foreground") { runCatching { state(session).optString("session") == session }.getOrDefault(false) }
        SystemClock.sleep(400)
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun state(session: String): JSONObject {
        val all = nodes(ui.rootInActiveWindow)
        return try {
            val node = all.single { it.packageName?.toString() == fixture && it.contentDescription?.startsWith("gesture-result:") == true }
            JSONObject(node.contentDescription.toString().removePrefix("gesture-result:")).also { assertEquals(session, it.getString("session")) }
        } finally { all.forEach { it.recycle() } }
    }
    private fun visibleText(): String {
        val all = nodes(ui.rootInActiveWindow)
        return try { all.joinToString("\n") { "${it.text?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}" } }
        finally { all.forEach { it.recycle() } }
    }
    private fun allWindowText(): String {
        val windows = ui.windows
        val all = windows.flatMap { nodes(it.root) }
        return try { all.joinToString("\n") { "${it.text?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}" } }
        finally { all.forEach { it.recycle() }; windows.forEach { it.recycle() } }
    }
    private fun clickSetting(labels: Set<String>): Boolean {
        val all = nodes(ui.rootInActiveWindow)
        try {
            var node = all.firstOrNull { it.text?.toString() in labels } ?: return false
            while (!node.isClickable) node = node.parent ?: return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { all.forEach { it.recycle() } }
    }
    private fun alertCount(id: Int): Int = ParcelFileDescriptor.AutoCloseInputStream(
        ui.executeShellCommand("logcat -b events -d -v brief")).bufferedReader().use { reader ->
        reader.lineSequence().count { line ->
            if (!line.contains("notification_alert") || !line.contains("|${context.packageName}|$id|null|")) false
            else line.substringAfterLast(',').substringBefore(']').toIntOrNull() != null &&
                line.substringAfterLast('[').substringBefore(']').split(',').getOrNull(2) == "1"
        }
    }
    private fun saveScreenshot(name: String) {
        val bitmap = requireNotNull(ui.takeScreenshot())
        try { File(folder(), name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun folder() = File(context.getExternalFilesDir(null), "task-queue").apply { check(isDirectory || mkdirs()) }
    private fun evidence(name: String, result: JSONObject) { File(folder(), "$name.json").writeText(result.put("paid_requests", 0).toString(2)) }
    private fun strings(rows: JSONArray) = (0 until rows.length()).map(rows::getString)
    private fun await(message: String, ms: Long = 12000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < end)
        assertTrue(message, condition())
    }

    /** Fixed responses cross real HTTP, A/B parsing and Android execution. No external model URL. */
    private class LocalModel(private val blockFirst: Boolean = false, private val firstGoalFails: Boolean = false,
                             private val finishWithoutGesture: Boolean = false) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val blocked = CountDownLatch(1); val release = CountDownLatch(1); val total = AtomicInteger(); val routed = AtomicInteger()
        @Volatile var discardBlocked = false
        val grounded = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val primary = ConcurrentHashMap<String, AtomicInteger>()
        private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
        private val workers = Executors.newCachedThreadPool()
        @Volatile private var running = true
        @Volatile private var failure: Throwable? = null
        private val thread = Thread({
            while (running) try { val client = socket.accept(); clients.add(client); workers.execute { serve(client) } }
            catch (error: Exception) { if (running) failure = error }
        }, "queue-qa-local-model").apply { isDaemon = true; start() }
        private fun serve(client: Socket) {
            var intentionallyInterrupted = false
            try { client.use {
                val input = BufferedInputStream(client.getInputStream())
                fun line() = buildString { while (length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) append(c.toChar()) } }
                check(line().startsWith("POST /v1/chat/completions "))
                var length = 0
                while (true) { val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() }
                check(length in 1..20 * 1024 * 1024)
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val read = input.read(bytes, offset, length - offset); check(read > 0); offset += read }
                val request = JSONObject(String(bytes, Charsets.UTF_8)); bytes.fill(0)
                val raw = request.toString()
                val marker = Regex("QUEUEQA_[A-F]").find(raw)?.value ?: error("Fixture goal marker missing")
                val isA = request.getString("model") == "fixture-primary"
                check(isA || request.getString("model") == "fixture-grounding")
                val isIntent = request.getJSONArray("messages").optJSONObject(0)?.optString("content")
                    .orEmpty().contains("Doppel 的消息路由器")
                val number = if (isIntent) { routed.incrementAndGet(); 0 } else total.incrementAndGet()
                if (blockFirst && number == 1) {
                    intentionallyInterrupted = true; blocked.countDown()
                    check(release.await(55, TimeUnit.SECONDS)) { "Host did not interrupt the blocked request" }
                }
                val ordinal = if (isA && !isIntent && !(intentionallyInterrupted && discardBlocked)) primary.computeIfAbsent(marker) { AtomicInteger() }.incrementAndGet() else 1
                val decision = if (isIntent) {
                    JSONObject().put("intent", "task").put("confidence", 1.0).put("task_goal", marker)
                        .put("title", marker).put("question", "").put("reply", "")
                } else if (!isA) {
                    grounded.add(marker)
                    JSONObject().put("result", JSONObject().put("status", "located").put("action", "tap")
                        .put("assessment", JSONObject().put("alignment", "consistent"))
                        .put("points", JSONArray().put(JSONArray(listOf(500, 500)))).put("duration_ms", 100))
                } else JSONObject().put("decision", if (firstGoalFails && marker == "QUEUEQA_A")
                    JSONObject().put("kind", "finish").put("status", "failed").put("message", "QUEUEQA_A 固定失败结果，验证队列继续")
                    else if (ordinal == 1 && !finishWithoutGesture)
                    JSONObject().put("kind", "tap").put("target", "$marker 点击独立手势区域中心一次").put("expected", "触摸计数增加一次")
                        .put("screen_context", "独立手势验证页面").put("request_login_code", JSONObject.NULL)
                    else JSONObject().put("kind", "finish").put("status", "completed").put("message", "$marker 一次点击完成"))
                    .put("state", JSONObject.NULL)
                val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", decision.toString()))))
                    .put("usage", JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0)).toString().toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response); flush()
                }
            } } catch (error: Throwable) { if (running && !intentionallyInterrupted) failure = error }
            finally { clients.remove(client) }
        }
        fun assertHealthy() { failure?.let { throw AssertionError("Local deterministic responder failed", it) } }
        override fun close() {
            running = false; release.countDown(); socket.close()
            synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
            workers.shutdownNow(); thread.join(1000)
        }
    }
}
