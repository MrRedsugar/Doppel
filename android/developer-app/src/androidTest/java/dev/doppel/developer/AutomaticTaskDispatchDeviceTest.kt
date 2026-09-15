@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerStore
import dev.doppel.sdk.AutoTriggerRule
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.GatewayHttpException
import dev.doppel.sdk.ScheduleManager
import dev.doppel.sdk.TaskControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in emulator-5554 host wiring test (-e automatic_dispatch_test true).
 * Real scheduler/trigger/session/worker, real fixture PIN 681429; localhost stubs replace all model/gateway work.
 * The host must refuse an existing worker before instrumentation restarts the app process.
 */
class AutomaticTaskDispatchDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val lock get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val recording get() = InstrumentationRegistry.getArguments().getString("recording") == "true"
    private fun demoStage(name: String) {
        if (recording) inst.sendStatus(0, Bundle().apply { putString("stream", "\nDEMO_STAGE $name\n") })
    }
    private fun singleton(name: String): Any = Class.forName("dev.doppel.sdk.$name").getField("INSTANCE").get(null)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods.single {
        it.name == name && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }.apply { isAccessible = true }.invoke(owner, *args)
    private fun unlocked() = power.isInteractive && !lock.isDeviceLocked && !lock.isKeyguardLocked
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .use { String(it.readBytes(), Charsets.UTF_8).trim() }
    private fun await(message: String, timeout: Long = 8000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(80) }
        assertTrue(message, condition())
    }
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }
    @Test fun runStatusDistinguishesMissingFromHttpFailureWithoutCreatingWork() {
        val prefix = "run-status-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        try {
            StubGateway("status-fixture", "Read-only status", 0, holdCompletion = true).use { server ->
                val gateway = Gateway(isolated)
                check(gateway.prefs.edit().putBoolean("direct_mode", false)
                    .putString("base_url", "http://127.0.0.1:${server.port}").putString("token", "status-fixture-token").commit())
                assertFalse("GET remains available without creating consent", FirstUseConsent.isAccepted(isolated))
                val path = "/runs/${server.runId}"
                assertEquals("running", gateway.runStatus(server.runId))
                for (code in listOf(401, 403, 404, 500)) {
                    server.runResponseCode = code
                    val directError = assertThrows(GatewayHttpException::class.java) { gateway.request("GET", path) }
                    assertEquals(code, directError.statusCode)
                    assertFalse(directError.message.orEmpty().contains("Synthetic body"))
                    if (code == 404) assertEquals("missing", gateway.runStatus(server.runId))
                    else assertEquals(code, assertThrows(GatewayHttpException::class.java) { gateway.runStatus(server.runId) }.statusCode)
                }
                server.runResponseCode = 200
                server.controlStatus = "paused"
                assertEquals("paused", gateway.runStatus(server.runId))
                server.controlStatus = "missing"
                assertThrows(IllegalStateException::class.java) { gateway.runStatus(server.runId) }
                assertEquals(11, server.requests.size)
                assertTrue(server.requests.all { it == "GET /v1$path" })
                assertTrue(server.creates.isEmpty())
                assertEquals(0, server.commandPolls.get())
                assertFalse(FirstUseConsent.isAccepted(isolated))
            }
        } finally {
            context.deleteSharedPreferences(prefix + "doppel")
            context.deleteSharedPreferences(prefix + "doppel_consent")
        }
    }

    @Test fun missingLocalStatusDoesNotRequestGatewayOrAdvanceStoredTasks() {
        assertNull("Do not interrupt an executing worker", DeviceWorkerService.instance)
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        assertFalse("Do not migrate an existing connection", prefs.getBoolean("artemis_mode", false))
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        assertTrue(prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
        assertTrue(DirectMode.available(context))
        val runsFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val beforeRuns = runsFile.takeIf { it.exists() }?.readBytes()
        val runs = JSONArray(beforeRuns?.toString(Charsets.UTF_8) ?: "[]")
        repeat(runs.length()) { assertTrue("Leave unfinished task records alone",
            runs.getJSONObject(it).getString("status") in setOf("completed", "failed", "cancelled")) }
        val runtime = DirectRuntime.get(context)
        assertFalse(runtime.hasUnfinishedRun())
        val beforePrefs = prefs.all.toMap()
        val missingId = UUID.randomUUID().toString()
        try {
            StubGateway("status-fixture", "Must not be requested", 0).use { server ->
                check(prefs.edit().putBoolean("direct_mode", true).putString("device_id", DirectRuntime.DEVICE_ID)
                    .putString("base_url", "http://127.0.0.1:${server.port}").commit())
                val gateway = Gateway(context)
                repeat(3) { assertEquals("missing", gateway.runStatus(missingId)) }
                repeat(runs.length()) {
                    val run = runs.getJSONObject(it)
                    assertEquals(run.getString("status"), gateway.runStatus(run.getString("id")))
                }
                assertTrue("Local lookup must not fall back to HTTP", server.requests.isEmpty())
                assertFalse(runtime.hasUnfinishedRun())
                assertArrayEquals(beforeRuns, runsFile.takeIf { it.exists() }?.readBytes())
                check(prefs.edit().putBoolean("direct_mode", false).commit())
                assertThrows(IllegalStateException::class.java) { runtime.statusOrNull(missingId) }
            }
        } finally {
            restore(prefs, beforePrefs)
            assertEquals(beforePrefs, prefs.all)
            assertArrayEquals(beforeRuns, runsFile.takeIf { it.exists() }?.readBytes())
        }
    }
    @Test fun scheduleReadinessReusesListedRunButChecksAnOmittedActiveRun() {
        val gateway = Gateway(context)
        val prefs = gateway.prefs
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        assertNull(DeviceWorkerService.instance)
        assertTrue(prefs.getString("active_run", "").isNullOrBlank())
        assertFalse(prefs.getBoolean("artemis_mode", false))
        assertFalse(call(singleton("AutomaticUnlockSession"), "getActive") as Boolean)
        val before = prefs.all.toMap(); val beforeConsent = consent.all.toMap()
        var fixtureRunId = ""
        try {
            assertTrue(FirstUseConsent.accept(context))
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            val manager = ScheduleManager.get(context)
            StubGateway("list-fixture", "Read-only readiness", 0).use { server ->
                fixtureRunId = server.runId
                check(prefs.edit().putBoolean("direct_mode", false).putString("device_id", "list-fixture")
                    .putString("base_url", "http://127.0.0.1:${server.port}").putString("token", "list-fixture-token")
                    .putString("active_run", server.runId).commit())
                assertNull(call(singleton("AutomaticTaskNotice"), "localBlockReason", context, false))
                val job = JSONObject().put("id", "read-only-list-fixture").put("device_id", "list-fixture")
                    .put("binding", call(manager, "binding")).put("goal", "Read-only readiness")
                    .put("mode", "ask").put("allowed_packages", JSONArray()).put("next_due_ms", 1)
                for (listed in listOf(true, false)) for (status in listOf("completed", "failed", "cancelled", "paused")) {
                    server.listedRun = listed; server.controlStatus = status; server.requests.clear()
                    val result = call(manager, "ready", job, false, true, false)
                    assertEquals(if (status == "paused") "device_busy" else null, result)
                    assertEquals(if (listed) listOf("GET /v1/runs") else
                        listOf("GET /v1/runs", "GET /v1/runs/${server.runId}"), server.requests.toList())
                    assertTrue(server.creates.isEmpty()); assertEquals(0, server.commandPolls.get())
                }
            }
        } finally {
            try {
                val conflict = singleton("AutomaticTaskConflict")
                @Suppress("UNCHECKED_CAST")
                val pending = conflict.javaClass.getDeclaredField("pending").apply { isAccessible = true }
                    .get(conflict) as MutableMap<String, Any>
                val tokens = synchronized(pending) {
                    pending.filterValues { request -> request.javaClass.getDeclaredField("expectedRunId")
                        .apply { isAccessible = true }.get(request) == fixtureRunId }.keys.toList()
                        .also { it.forEach(pending::remove) }
                }
                val notifications = context.getSystemService(NotificationManager::class.java)
                tokens.forEach { notifications.cancel(it, 1) }
            } finally {
                restore(prefs, before); restore(consent, beforeConsent)
                assertEquals(before, prefs.all); assertEquals(beforeConsent, consent.all)
                assertNull(DeviceWorkerService.instance)
            }
        }
    }
    private fun drain(owner: Any, field: String) {
        val executor = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as ExecutorService
        executor.submit {}.get(6, TimeUnit.SECONDS)
    }
    private fun tick(manager: ScheduleManager) {
        val finished = CountDownLatch(1)
        manager.tick { finished.countDown() }
        assertTrue("Production scheduler check must finish", finished.await(8, TimeUnit.SECONDS))
    }
    private fun lockScreen() {
        var accepted = false
        inst.runOnMainSync { accepted = requireNotNull(DoppelAccessibilityService.instance)
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        assertTrue(accepted)
        await("Actual system keyguard must lock") { lock.isDeviceLocked && lock.isKeyguardLocked }
    }
    private fun systemNodes() = nodesForPackage("com.android.systemui")
    private fun nodesForPackage(packageName: String): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (result.size >= 500) return
            if (node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::walk) }
        }
        automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == packageName }.forEach(::walk)
        return result
    }
    private fun typeLocalPassword(value: String) {
        for (digit in value) {
            assertTrue(nodesForPackage(context.packageName).single { it.contentDescription?.toString() == "数字 $digit" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
    private fun clickLocalButton(label: String) {
        assertTrue(nodesForPackage(context.packageName).single { it.text?.toString() == label }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun unlockFixture() {
        if (unlocked()) return
        val previous = automation.serviceInfo
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            var awakeSince = 0L
            var lastWake = 0L
            await("The display must finish waking before the cleanup swipe") {
                val now = SystemClock.elapsedRealtime()
                val awake = power.isInteractive && context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
                if (awake) {
                    if (awakeSince == 0L) awakeSince = now
                    now - awakeSince >= 200
                } else {
                    awakeSince = 0L
                    // Retry only wake while a previously requested screen-off transition settles.
                    if (now - lastWake >= 400) { shell("input keyevent 224"); lastWake = now }
                    false
                }
            }
            val size = context.resources.displayMetrics
            if (systemNodes().none { it.viewIdResourceName == "com.android.systemui:id/key6" })
                shell("input swipe ${size.widthPixels / 2} ${size.heightPixels * 4 / 5} ${size.widthPixels / 2} ${size.heightPixels / 4} 350")
            await("Public fixture PIN keypad must be visible") { systemNodes().any { it.viewIdResourceName == "com.android.systemui:id/key6" } }
            for (digit in "681429") {
                val button = systemNodes().single { it.viewIdResourceName == "com.android.systemui:id/key$digit" }
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(100)
            }
            systemNodes().firstOrNull { it.viewIdResourceName == "com.android.systemui:id/key_enter" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("Fixture cleanup must really unlock, keeping the existing system PIN") { unlocked() }
        } finally { automation.serviceInfo = previous }
    }

    @Test fun runningTransitionDuringValidityCheckIsAcceptedButStaleGenerationIsRejected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automatic_dispatch_test") == "true")
        assertNull(DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertTrue(AutoTriggerStore(context).list().none { it.enabled })
        val jobs = ScheduleManager.get(context).request("GET", "/schedules").getJSONArray("items")
        assertFalse((0 until jobs.length()).any { jobs.getJSONObject(it).optBoolean("enabled") })
        val session = singleton("AutomaticUnlockSession")
        val slot = session.javaClass.getDeclaredField("session").apply { isAccessible = true }
        assertNull(slot.get(session))
        val ownerType = Class.forName("dev.doppel.sdk.AutomaticUnlockSession\$Session")
        val phase = ownerType.getDeclaredField("phase").apply { isAccessible = true }
        fun state(name: String) = phase.type.enumConstants.single { (it as Enum<*>).name == name }
        var owner: Any? = null
        val invalidate = AtomicBoolean(false)
        val valid: () -> Boolean = {
            // Deterministically reproduce an occurrence advancing while its saved-state read waits.
            phase.set(owner, state("RUNNING"))
            if (invalidate.get()) TaskControl.invalidate()
            false
        }
        owner = ownerType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(context, "dispatch-validity-regression", valid, {})
        val live = session.javaClass.getDeclaredMethod("live", ownerType).apply { isAccessible = true }
        try {
            slot.set(session, owner); phase.set(owner, state("READY"))
            assertEquals("RUNNING must survive an outdated occurrence check", true, live.invoke(session, owner))
            phase.set(owner, state("READY")); invalidate.set(true)
            assertEquals("A generation change must still invalidate the session", false, live.invoke(session, owner))
        } finally { slot.set(session, null) }
    }

    @Test fun scheduleUsesFifteenSecondNoticeAndCreatesOnlyOneIndependentRun() = verify("schedule", false)
    @Test fun controlTriggerUsesFifteenSecondNoticeAndCreatesOnlyOneIndependentRun() = verify("trigger", false)
    @Test fun lockedScheduleUnlocksDispatchesAndRelocksAfterWorkerReadsCompleted() = verify("schedule", true)
    @Test fun lockedControlTriggerUnlocksDispatchesAndRelocksAfterWorkerReadsCompleted() = verify("trigger", true)

    @Test fun unverifiedHandoffResumesSameWorkerAndTaskWithoutPauseOrCancel() = verify("schedule", true, handoffChecks = true)
    @Test fun authenticatedTakeoverResumesSameRunWithoutRelockingOwnersPhone() = verify("trigger", true, verifiedAction = "pause")
    @Test fun authenticatedEndClearsOnlyTheFinishedRunWithoutRelockingOwnersPhone() = verify("trigger", true, verifiedAction = "cancel")
    @Test fun blockedScheduleNotificationReplacesExactlyOneRunWithoutUnlockProtection() = verify("schedule", false, conflictMode = "continue")
    @Test fun blockedTriggerNotificationReplacesExactlyOneRunWithoutUnlockProtection() = verify("trigger", false, conflictMode = "continue")
    @Test fun outdatedAutomaticTaskNotificationCannotCancelANewerTask() = verify("schedule", false, conflictMode = "stale")
    @Test fun scheduleChangedDuringCancellationClearsOnlyTheCancelledPointerAndDoesNotDispatch() =
        verify("schedule", false, conflictMode = "source_changed")
    @Test fun triggerChangedDuringCreationKeepsTheCreatedRunPausedWithoutStartingWorker() =
        verify("trigger", false, raceMode = "source_changed_during_create")

    private fun verify(source: String, lockedStart: Boolean, handoffChecks: Boolean = false, verifiedAction: String? = null,
                       conflictMode: String? = null, raceMode: String? = null) {
        assumeTrue("Explicit public-PIN emulator fixture only", InstrumentationRegistry.getArguments().getString("automatic_dispatch_test") == "true")
        assertEquals(34, Build.VERSION.SDK_INT)
        assertEquals("Never run on a physical phone", "1", shell("getprop ro.boot.qemu"))
        assertFalse("Do not migrate a connection", context.getSharedPreferences("doppel", 0).getBoolean("artemis_mode", false))
        val gateway = Gateway(context)
        val prefs = gateway.prefs
        assertFalse("Do not migrate a connection", prefs.getBoolean("artemis_mode", false))
        assertNull("Retain an existing worker", DeviceWorkerService.instance)
        assertTrue("Retain an existing task", prefs.getString("active_run", "").isNullOrBlank())
        assertTrue("Host must unlock before each test", unlocked())
        if (lockedStart) assertTrue("Host must provision the public fixture PIN", lock.isDeviceSecure)
        val session = singleton("AutomaticUnlockSession")
        val credentials = singleton("AutomaticUnlockCredentials")
        assertEquals(false, call(session, "getActive"))
        val gate = call(singleton("TaskSubmissionGate"), "getCreating") as AtomicBoolean
        assertFalse(gate.get())
        assertTrue("Retain all existing trigger rules", AutoTriggerStore(context).list().none { it.enabled })
        val originalRules = AutoTriggerStore(context).list()
        val manager = ScheduleManager.get(context)
        val oldJobs = manager.request("GET", "/schedules").getJSONArray("items")
        assertFalse("Retain existing schedules", (0 until oldJobs.length()).any { oldJobs.getJSONObject(it).optBoolean("enabled") })
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val credentialFile = File(context.noBackupFilesDir, "automatic-unlock-v1.bin")
        val alias = "${context.packageName}.automatic-unlock.v1"
        assertFalse("Never overwrite an owner's encrypted credential", listOf("", ".new", ".bak").any { File(credentialFile.path + it).exists() })
        assertFalse("Never overwrite an owner's key", keyStore.containsAlias(alias))
        val names = listOf("doppel", "doppel_consent", "doppel_schedule_wakeup", "doppel_automatic_unlock_state")
        val saved = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        assertTrue("Retain pending recovery state", saved.getValue("doppel_automatic_unlock_state").isEmpty())
        val marker = UUID.randomUUID().toString()
        val goal = "Automatic dispatch fixture $marker"
        val device = "automatic-dispatch-fixture"
        val folder = File(context.getExternalFilesDir(null), "automatic-dispatch-verification/$marker").apply { check(mkdirs()) }
        val report = JSONObject().put("ok", false).put("source", source).put("locked_start", lockedStart)
            .put("scope", "production host wiring; localhost gateway and model responses are stubs").put("model_requests", 0)
        if (raceMode != null) report.put("race_mode", raceMode)
        if (recording) report.put("recording", true).put("stub_completion_delay_ms", 4000)
        var stage = "prepare"
        var id: String? = null
        var sessionKey: String? = null
        var fixtureRule: AutoTriggerRule? = null
        val raceApplied = AtomicBoolean(false)
        var ownsCredential = false
        var startedAt = 0L
        val server = StubGateway(device, goal, if (recording) 4000 else 0, holdCompletion = handoffChecks || verifiedAction != null || raceMode != null,
            blockedByExisting = conflictMode != null)
        var passed = false
        val oldAutomationInfo = automation.serviceInfo
        fun save() { File(folder, "result.json").writeText(report.put("stage", stage).toString(2)) }
        save()
        try {
            if (handoffChecks || verifiedAction != null) automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:${server.port}")
                .putString("token", "local-fixture-no-external-access").putString("device_id", device)
                .putString("conversation_tail", "unchanged-conversation-fixture").putBoolean("completion_speech", false).commit())
            check(FirstUseConsent.accept(context))
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNotNull(DoppelAccessibilityService.instance)
            assertNull("Host must grant the app's required permissions before testing automatic dispatch",
                call(singleton("AutomaticTaskNotice"), "localBlockReason", context, false))
            if (lockedStart || conflictMode != null || raceMode != null) {
                val kind = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials\$Kind").enumConstants.single { (it as Enum<*>).name == "PIN" }
                val pin = "681429".toCharArray(); ownsCredential = true
                try { call(credentials, "save", context, kind, pin) } finally { pin.fill('\u0000') }
            }
            if (conflictMode != null) check(prefs.edit().putString("active_run", server.previousRunId).commit())
            if (source == "schedule") {
                val job = manager.request("POST", "/schedules", JSONObject().put("device_id", device).put("goal", goal).put("mode", "ask")
                    .put("rule", JSONObject().put("kind", "once").put("timezone", "Asia/Shanghai").put("at_ms", System.currentTimeMillis() + 1600)))
                id = job.getString("id")
                sessionKey = call(singleton("SchedulePromptOverlay"), "key", job) as String
                if (lockedStart) {
                    lockScreen()
                    demoStage("locked_before_automatic_dispatch")
                    if (recording) Thread.sleep(1500)
                }
                Thread.sleep(1750)
                startedAt = SystemClock.elapsedRealtime(); tick(manager)
            } else {
                if (lockedStart) {
                    lockScreen()
                    demoStage("locked_before_automatic_dispatch")
                    if (recording) Thread.sleep(1500)
                }
                startedAt = SystemClock.elapsedRealtime()
                if (raceMode == "source_changed_during_create") {
                    val pkg = "dev.doppel.absent.fixture${marker.replace("-", "")}"
                    val rule = AutoTriggerRule(id = UUID.randomUUID().toString(), packageName = pkg,
                        matchResourceId = "$pkg:id/fixture_control", action = "task", taskGoal = goal, enabled = true)
                    fixtureRule = rule
                    AutoTriggerStore(context).save(rule)
                    server.onBeforeCreateResponse = {
                        AutoTriggerStore(context).save(rule.copy(enabled = false, taskGoal = "Changed fixture task $marker"))
                        raceApplied.set(true)
                    }
                    val onAdmission: (Boolean) -> Unit = {}
                    assertEquals(true, call(singleton("AutoTriggerTaskLauncher"), "launch", context, goal,
                        onAdmission, "Fixture control", rule.id, rule.json().toString(), true))
                } else assertEquals(true, call(singleton("AutoTriggerTaskLauncher"), "launch", context, goal))
            }
            stage = if (conflictMode != null) "blocked_notification" else if (lockedStart) "automatic_unlock_dispatch" else "visible_countdown"; save()
            var conflictClickedAt = 0L
            if (conflictMode != null) {
                val managerNotifications = context.getSystemService(NotificationManager::class.java)
                assertTrue("Host must allow notifications for this explicit fixture", managerNotifications.areNotificationsEnabled())
                fun action() = managerNotifications.activeNotifications.flatMap { it.notification.actions?.toList().orEmpty() }
                    .firstOrNull { it.title?.toString() == "继续执行" }
                await("Busy automatic task must expose a Continue notification action") { action() != null }
                assertEquals("Notification alone cannot end the current task", 0, server.previousCancelCount.get())
                assertEquals("Notification alone cannot create a task", 0, server.creates.size)
                val pending = requireNotNull(action()).actionIntent
                if (conflictMode == "stale") check(prefs.edit().putString("active_run", "newer-owner-$marker").commit())
                if (conflictMode == "source_changed") {
                    val scheduleId = requireNotNull(id)
                    server.onBeforeCancelResponse = {
                        manager.request("PATCH", "/schedules/$scheduleId", JSONObject().put("enabled", false))
                        raceApplied.set(true)
                    }
                }
                conflictClickedAt = SystemClock.elapsedRealtime()
                pending.send()
                if (conflictMode == "source_changed") {
                    await("The deterministic response hook must disable the source after cancellation") { raceApplied.get() && server.previousCancelCount.get() == 1 }
                    await("Confirmed cancellation must clear its exact pointer even when the schedule changed") { prefs.getString("active_run", "").isNullOrBlank() }
                    drain(singleton("AutomaticTaskConflict"), "io"); drain(TaskControl, "io"); inst.waitForIdleSync()
                    assertEquals("Invalidated schedule must create no replacement", 0, server.creates.size)
                    assertEquals("Old task is cancelled only once", 1, server.previousCancelCount.get())
                    assertNull("Invalidated replacement must not start a worker", DeviceWorkerService.instance)
                    assertEquals(false, call(session, "getActive")); assertTrue(unlocked())
                    assertFalse(manager.request("GET", "/schedules/${requireNotNull(id)}").getBoolean("enabled"))
                    assertEquals("Conflict handling must not move the conversation", "unchanged-conversation-fixture", prefs.getString("conversation_tail", ""))
                    report.put("source_changed_during_cancel", true).put("cancelled_pointer_cleared", true)
                        .put("created_runs", 0).put("cancelled_runs", 1).put("unlocked_start_unprotected", true)
                    passed = true
                    return
                }
                if (conflictMode == "stale") {
                    Thread.sleep(2000)
                    assertEquals(0, server.previousCancelCount.get()); assertEquals(0, server.creates.size)
                    assertEquals("Never clear a newer task reference", "newer-owner-$marker", prefs.getString("active_run", ""))
                    assertTrue(unlocked()); assertEquals(false, call(session, "getActive"))
                    report.put("stale_notice_rejected", true).put("created_runs", 0).put("cancelled_runs", 0)
                    passed = true
                    return
                }
                // Re-deliver the same real PendingIntent. Consumption must survive both launches.
                pending.send()
                await("Explicit continue must start immediately without a second 15-second countdown", 10000) { server.creates.size == 1 }
                assertEquals("The old task must be cancelled exactly once", 1, server.previousCancelCount.get())
                assertTrue("Already-unlocked owner must retain unlocked access", unlocked())
                assertEquals("Configured auto-unlock must not protect an unlocked replacement", false, call(session, "getActive"))
                report.put("notification_continue_replaced_once", true).put("previous_cancel_requests", 1)
                    .put("replacement_delay_ms", server.createdAt - conflictClickedAt).put("unlocked_start_unprotected", true)
            }
            if (!lockedStart && conflictMode == null && raceMode == null) {
                Thread.sleep(12000)
                assertEquals("No run or model request before the full countdown", 0, server.creates.size)
            }
            await("Host must create the run exactly once", if (lockedStart) 30000 else 12000) { server.creates.size == 1 }
            demoStage("automatic_task_dispatched_local_stub")
            stage = "worker_completion"; save()
            assertEquals("conversation tail must not change", "unchanged-conversation-fixture", prefs.getString("conversation_tail", ""))
            val body = server.creates.single()
            assertEquals(source, body.getString("source"))
            assertFalse(body.getBoolean("conversation_enabled"))
            assertFalse(body.has("parent_run_id")); assertFalse(body.has("conversation_id"))
            assertEquals(goal, body.getString("goal"))
            if (raceMode == "source_changed_during_create") {
                await("Changed source during POST must pause the newly created run") { raceApplied.get() && server.controlStatus == "paused" }
                drain(singleton("AutoTriggerTaskLauncher"), "io"); inst.waitForIdleSync()
                assertEquals("Paused task must stay available in task records", server.runId, prefs.getString("active_run", ""))
                assertEquals("The stale creation still has exactly one retained run", 1, server.creates.size)
                assertNull("A changed trigger must not start the execution worker", DeviceWorkerService.instance)
                assertEquals("No device commands may be polled", 0, server.commandPolls.get())
                assertEquals("No replacement may execute to completion", 0, server.completionReads.get())
                assertEquals(false, call(session, "getActive")); assertTrue(unlocked())
                assertEquals("The retained remote record must be paused", "paused", gateway.request("GET", "/runs/${server.runId}").getString("status"))
                report.put("source_changed_during_create", true).put("created_runs", 1)
                    .put("created_run_retained_paused", true).put("worker_started", false).put("device_commands", 0)
                    .put("unlocked_start_unprotected", true)
                passed = true
                return
            }
            if (!lockedStart && conflictMode == null) assertTrue("Actual host countdown must last at least 15 seconds", server.createdAt - startedAt >= 15000)
            if (verifiedAction != null) {
                stage = "authenticated_$verifiedAction"; save()
                await("Real worker must own the protected run") {
                    DeviceWorkerService.instance != null && server.commandPolls.get() > 0 && prefs.getString("active_run", "") == server.runId
                }
                val generation = TaskControl.currentGeneration()
                inst.runOnMainSync { call(session, "beginHandoff", verifiedAction) }
                val title = if (verifiedAction == "pause") "验证后接管" else "验证后结束任务"
                val confirm = if (verifiedAction == "pause") "确认并接管" else "确认并结束"
                await("Local form must identify the requested action") {
                    call(session, "isAuthenticating") == true && nodesForPackage(context.packageName).any { it.text?.toString() == title } &&
                        nodesForPackage(context.packageName).any { it.text?.toString() == confirm && it.isClickable }
                }
                typeLocalPassword("681429"); clickLocalButton(confirm)
                await("Only verified input may send task control") {
                    server.controlStatus == if (verifiedAction == "pause") "paused" else "cancelled"
                }
                await("Successful owner verification must remove local protection") { call(session, "getActive") == false }
                assertTrue("Owner verification must not relock", unlocked())
                assertNotEquals("Verified control must invalidate old pending actions", generation, TaskControl.currentGeneration())
                assertEquals("Correct action must be sent exactly once", 1, synchronized(server.requests) {
                    server.requests.count { it == "POST /v1/runs/${server.runId}/$verifiedAction" }
                })
                if (verifiedAction == "pause") {
                    assertEquals("Takeover keeps the paused run available for explicit resume", server.runId, prefs.getString("active_run", ""))
                    server.allowCompletion = true
                    val resumed = CountDownLatch(1)
                    var resumeError: String? = null
                    TaskControl.request(context, server.runId, "resume") { run, error ->
                        resumeError = error
                        if (run != null) TaskControl.startWorker(context)
                        resumed.countDown()
                    }
                    assertTrue(resumed.await(8, TimeUnit.SECONDS)); assertNull(resumeError)
                    await("Resuming keeps the same task and worker can read completion") { server.completionReads.get() > 0 }
                } else {
                    await("Confirmed end must clear the stale active pointer") { prefs.getString("active_run", "").isNullOrBlank() }
                    assertTrue("End must retain a stopped worker", DeviceWorkerService.instance?.isPaused != false)
                }
                Thread.sleep(1300)
                assertTrue("Authenticated owner must keep unlocked access after control settles", unlocked())
                assertEquals("Handoff does not create a new task", 1, server.creates.size)
                report.put("verified_action", verifiedAction).put("owner_phone_not_relocked", true)
                    .put("verified_control_requests", 1).put("same_task_resume", verifiedAction == "pause")
            }
            if (handoffChecks) {
                stage = "unverified_handoff_same_worker"; save()
                await("The real worker must be polling this fixture run") {
                    DeviceWorkerService.instance != null && server.commandPolls.get() > 0 && prefs.getString("active_run", "") == server.runId
                }
                val worker = DeviceWorkerService.instance
                val generation = TaskControl.currentGeneration()
                fun begin() {
                    inst.runOnMainSync { call(session, "beginHandoff", "pause") }
                    await("Local handoff must finish laying out its visible PIN keypad") {
                        val controls = nodesForPackage(context.packageName)
                        call(session, "isAuthenticating") == true && (0..9).all { digit ->
                            controls.any { it.contentDescription?.toString() == "数字 $digit" && it.isEnabled && it.isClickable }
                        } && controls.any { it.text?.toString() == "确认并接管" && it.isEnabled && it.isClickable }
                    }
                    assertTrue("Unverified handoff must not lock the phone", unlocked())
                    assertEquals("Unverified handoff must preserve generation", generation, TaskControl.currentGeneration())
                }
                fun sameWorkerContinues(reason: String) {
                    await("$reason must close the local form") { call(session, "isAuthenticating") == false }
                    assertSame("$reason must retain the existing worker", worker, DeviceWorkerService.instance)
                    assertEquals("$reason must keep the current run", server.runId, prefs.getString("active_run", ""))
                    assertEquals("$reason must preserve generation", generation, TaskControl.currentGeneration())
                    assertTrue("$reason must keep the unlocked protected session", unlocked() && call(session, "getActive") == true)
                    val pollCount = server.commandPolls.get()
                    await("$reason must let the same worker continue polling commands") { server.commandPolls.get() > pollCount }
                    assertEquals("Handoff must not create a replacement task", 1, server.creates.size)
                    assertFalse("An unverified request must never send task control", synchronized(server.requests) {
                        server.requests.any { it == "POST /v1/runs/${server.runId}/pause" || it == "POST /v1/runs/${server.runId}/cancel" }
                    })
                }
                begin()
                await("Five idle seconds must automatically resume", 6500) { call(session, "isAuthenticating") == false }
                sameWorkerContinues("Idle timeout")
                Thread.sleep(10200)
                begin()
                repeat(3) { attempt ->
                    typeLocalPassword("681420")
                    clickLocalButton("确认并接管")
                    if (attempt < 2) await("The local form must show the remaining wrong-PIN attempts") {
                        nodesForPackage(context.packageName).any { it.text?.toString() == "密码错误，还可尝试 ${2 - attempt} 次" }
                    }
                }
                sameWorkerContinues("Wrong-password limit")
                Thread.sleep(10200)
                begin()
                clickLocalButton("继续任务")
                sameWorkerContinues("Continue-task button")
                report.put("unverified_handoff_preserved_worker_and_run", true)
                    .put("idle_timeout_resumed", true).put("wrong_password_limit_resumed", true)
                    .put("cancel_resumed", true).put("unverified_pause_cancel_requests", 0)
                server.issueObservation = true
                await("The same worker must execute and return one real observation after handoff dismissals", 12000) { server.observationReceipts.size == 1 }
                val receipt = server.observationReceipts.single()
                assertEquals(server.commandId, receipt.getString("command_id"))
                assertEquals(server.runId, receipt.getString("run_id"))
                assertEquals("The real device observation must succeed", "ok", receipt.getString("status"))
                assertTrue("The observation must contain a real device node tree", receipt.getBoolean("has_nodes"))
                assertSame(worker, DeviceWorkerService.instance)
                report.put("resumed_real_observe_command_ok", true).put("observe_command_results", 1)
                server.allowCompletion = true
                stage = "worker_completion_after_unverified_handoffs"; save()
            }
            if (verifiedAction != "cancel") await("A real worker must read the stub completion", 12000) {
                DeviceWorkerService.instance != null && server.completionReads.get() > 0 &&
                    (lockedStart || prefs.getString("last_result", "").orEmpty().contains(server.runId))
            }
            if (lockedStart && verifiedAction == null) {
                await("Completed scheduled task must relock the real phone and end its protected session", 10000) {
                    lock.isDeviceLocked && lock.isKeyguardLocked && call(session, "getActive") == false
                }
                demoStage("completed_stub_task_relocked")
                if (recording) Thread.sleep(1500)
            }
            await("Completion must release active_run so later automatic tasks are not blocked") {
                prefs.getString("active_run", "").isNullOrBlank() &&
                    (verifiedAction == "cancel" || prefs.getString("last_result", "").orEmpty().contains(server.runId))
            }
            Thread.sleep(1200)
            assertEquals("No duplicate task", 1, server.creates.size)
            if (handoffChecks) assertEquals("No duplicate observed action or result", 1, server.observationReceipts.size)
            if (id != null) {
                tick(manager)
                val history = manager.request("GET", "/schedules/$id").getJSONArray("history")
                assertEquals(1, history.length())
                assertEquals(server.runId, history.getJSONObject(0).getString("run_id"))
                assertEquals("completed", history.getJSONObject(0).getString("status"))
            }
            report.put("created_runs", 1).put("create_delay_ms", server.createdAt - startedAt)
                .put("worker_completion_reads", server.completionReads.get()).put("conversation_unchanged", true)
                .put("relocked_after_completion", lockedStart && verifiedAction == null && lock.isDeviceLocked && lock.isKeyguardLocked)
            passed = true
            demoStage("dispatch_demo_completed")
        } catch (error: Throwable) {
            report.put("failure_type", error.javaClass.simpleName).put("failure", error.message?.take(200))
            report.put("local_block", call(singleton("AutomaticTaskNotice"), "localBlockReason", context, false) ?: JSONObject.NULL)
                .put("interactive", power.isInteractive).put("device_locked", lock.isDeviceLocked)
            id?.let { report.put("schedule_state", manager.request("GET", "/schedules/$it")) }
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            var quiesced = false
            fun cleanup(work: () -> Unit) { try { work() } catch (error: Throwable) { if (cleanupFailure == null) cleanupFailure = error } }
            cleanup {
                val jobs = manager.request("GET", "/schedules").getJSONArray("items")
                (0 until jobs.length()).map { jobs.getJSONObject(it) }.filter { it.optString("goal") == goal && it.optString("device_id") == device }
                    .forEach { manager.request("DELETE", "/schedules/${it.getString("id")}") }
                drain(manager, "io")
            }
            cleanup { fixtureRule?.let { AutoTriggerStore(context).remove(it.id) } }
            cleanup {
                sessionKey?.let { if (call(session, "matches", it) == true) call(session, "dispatchFailed", it) }
                // Preflight established there was no session and no enabled automatic source except this fixture.
                if (lockedStart && source == "trigger" && call(session, "getActive") == true) call(session, "interrupted")
                context.stopService(Intent(context, DeviceWorkerService::class.java))
                await("Fixture worker must stop") { DeviceWorkerService.instance == null }
                await("Fixture unlock session must end", 10000) { call(session, "getActive") == false }
                drain(manager, "io"); drain(TaskControl, "io"); drain(session, "io")
                if (source == "trigger") drain(singleton("AutoTriggerTaskLauncher"), "io")
                if (conflictMode != null) drain(singleton("AutomaticTaskConflict"), "io")
                inst.waitForIdleSync()
                quiesced = DeviceWorkerService.instance == null && call(session, "getActive") == false && !gate.get()
                check(quiesced) { "Fixture execution must stop before restoring any real connection" }
            }
            cleanup {
                if (handoffChecks && quiesced) {
                    // Remove only this UUID fixture command after the worker has closed its ledger.
                    context.openOrCreateDatabase("command_results.db", Context.MODE_PRIVATE, null).use {
                        it.delete("results", "id=?", arrayOf(server.commandId))
                    }
                }
            }
            cleanup { if (ownsCredential) call(credentials, "clear", context) }
            cleanup { if (lockedStart && quiesced) unlockFixture() }
            cleanup {
                // A failing test must never point a surviving fixture worker at the owner's actual server.
                check(quiesced) { "Cleanup incomplete; retain the loopback connection for host recovery" }
                names.forEach { restore(context.getSharedPreferences(it, 0), saved.getValue(it)) }
            }
            cleanup { server.close() }
            cleanup { automation.serviceInfo = oldAutomationInfo }
            cleanup {
                assertEquals(oldJobs.toString(), manager.request("GET", "/schedules").getJSONArray("items").toString())
                assertEquals("Only the fixture's own control rule may be removed", originalRules, AutoTriggerStore(context).list())
                names.forEach { assertTrue("Restore $it exactly without printing credentials", saved.getValue(it) == context.getSharedPreferences(it, 0).all) }
                assertFalse(gate.get()); assertFalse(credentialFile.exists()); assertFalse(keyStore.containsAlias(alias))
                assertTrue(unlocked()); assertNull(DeviceWorkerService.instance)
            }
            report.put("request_types", JSONArray(synchronized(server.requests) { server.requests.toList() })).put("ok", passed && cleanupFailure == null)
                .put("fixture_credential_removed", !credentialFile.exists()).put("fixture_execution_stopped", quiesced)
                .put("connection_restored", quiesced && saved.getValue("doppel") == prefs.all)
            cleanupFailure?.let { report.put("cleanup_failure_type", it.javaClass.simpleName) }
            save(); cleanupFailure?.let { throw it }
        }
    }

    /** Only loopback HTTP; no credentials are written to evidence and no upstream request exists. */
    private class StubGateway(private val device: String, private val goal: String, private val completionDelayMs: Long,
                              holdCompletion: Boolean = false, private val blockedByExisting: Boolean = false) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val runId = UUID.randomUUID().toString()
        val previousRunId = UUID.randomUUID().toString()
        val previousCancelCount = AtomicInteger()
        val commandId = UUID.randomUUID().toString()
        @Volatile var issueObservation = false
        private var observationSent = false
        val observationReceipts = Collections.synchronizedList(ArrayList<JSONObject>())
        val creates = Collections.synchronizedList(ArrayList<JSONObject>())
        val requests = Collections.synchronizedList(ArrayList<String>())
        val completionReads = AtomicInteger()
        val commandPolls = AtomicInteger()
        @Volatile var allowCompletion = !holdCompletion
        @Volatile var controlStatus: String? = null
        @Volatile var runResponseCode = 200
        @Volatile var listedRun = false
        @Volatile var onBeforeCancelResponse: (() -> Unit)? = null
        @Volatile var onBeforeCreateResponse: (() -> Unit)? = null
        @Volatile var createdAt = 0L
        @Volatile private var open = true
        private fun currentStatus() = controlStatus ?: if (!allowCompletion || SystemClock.elapsedRealtime() - createdAt < completionDelayMs) "running" else "completed"
        private fun run(status: String) = JSONObject().put("id", runId).put("device_id", device).put("goal", goal)
            .put("status", status).put("message", "Local stub completion").put("events", JSONArray()).put("conversation_enabled", false)
        private fun previousRun() = JSONObject().put("id", previousRunId).put("device_id", device).put("goal", "Existing fixture task")
            .put("status", if (previousCancelCount.get() == 0) "paused" else "cancelled").put("events", JSONArray())
        private val thread = Thread({
            while (open) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String {
                        val value = StringBuilder()
                        while (value.length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) value.append(c.toChar()) }
                        return value.toString()
                    }
                    val first = line().split(' '); check(first.size >= 2)
                    val method = first[0]; val path = first[1].substringBefore('?')
                    var size = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    check(size in 0..1024 * 1024)
                    val bytes = ByteArray(size); var offset = 0
                    while (offset < size) { val n = input.read(bytes, offset, size - offset); check(n > 0); offset += n }
                    requests.add("$method $path")
                    val code = if (method == "GET" && path == "/v1/runs/$runId") runResponseCode else 200
                    val body = if (code != 200) JSONObject().put("detail", "Synthetic body mentions 404; only the HTTP status is authoritative") else when {
                        method == "POST" && path == "/v1/runs" -> {
                            creates.add(JSONObject(String(bytes, Charsets.UTF_8))); createdAt = SystemClock.elapsedRealtime()
                            onBeforeCreateResponse?.invoke(); run("running")
                        }
                        method == "GET" && path == "/v1/runs" -> JSONObject().put("items", JSONArray().apply {
                            if (blockedByExisting) put(previousRun())
                            if (creates.isNotEmpty() || listedRun) put(run(currentStatus()))
                        })
                        method == "GET" && path == "/v1/runs/$previousRunId" -> previousRun()
                        method == "POST" && path == "/v1/runs/$previousRunId/cancel" -> {
                            previousCancelCount.incrementAndGet(); onBeforeCancelResponse?.invoke(); previousRun()
                        }
                        path == "/v1/runs/$runId" -> {
                            val status = currentStatus()
                            if (status == "completed") completionReads.incrementAndGet()
                            run(status)
                        }
                        path == "/v1/runs/$runId/pause" -> { controlStatus = "paused"; run("paused") }
                        path == "/v1/runs/$runId/cancel" -> { controlStatus = "cancelled"; run("cancelled") }
                        path == "/v1/runs/$runId/resume" -> { controlStatus = null; run("running") }
                        path.endsWith("/commands") -> {
                            commandPolls.incrementAndGet(); Thread.sleep(150)
                            val command = if (issueObservation && !observationSent) {
                                observationSent = true
                                JSONObject().put("id", commandId).put("run_id", runId).put("kind", "observe")
                                    .put("split_agent", true).put("include_screenshot", false)
                            } else JSONObject.NULL
                            JSONObject().put("command", command)
                        }
                        method == "POST" && path.endsWith("/results") -> {
                            val result = JSONObject(String(bytes, Charsets.UTF_8))
                            // Keep only status evidence; never persist raw screenshots, text, or node data.
                            observationReceipts.add(JSONObject().put("command_id", result.getString("command_id"))
                                .put("run_id", result.getString("run_id")).put("status", result.getString("status"))
                                .put("has_nodes", result.optJSONObject("observation")?.optJSONArray("nodes") != null))
                            JSONObject()
                        }
                        path.endsWith("/data-cleanup") -> JSONObject().put("items", JSONArray())
                        else -> JSONObject()
                    }
                    val output = body.toString().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(output); flush()
                    }
                }
            } catch (_: Exception) { if (!open) break }
        }, "automatic-dispatch-local-stub").apply { isDaemon = true; start() }
        override fun close() { open = false; socket.close(); thread.join(1500) }
    }
}
