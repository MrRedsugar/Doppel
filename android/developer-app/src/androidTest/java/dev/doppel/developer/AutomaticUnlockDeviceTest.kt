@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.TaskControl
import dev.doppel.sdk.SdkCompanionService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.companion.CompanionEndpoint
import dev.doppel.sdk.companion.CompanionLanPresence
import dev.doppel.sdk.companion.CompanionPairings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.KeyStore
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in Session component test on emulator-5554 with the public fixture PIN 681429.
 * Host must check for a worker before starting instrumentation, which restarts the app process.
 * Run with -e automatic_unlock_test true. No run is bound/submitted and no model is called.
 * System PIN is deliberately retained for the host's final fixture cleanup.
 */
class AutomaticUnlockDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val lock get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val prefs get() = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
    private val session by lazy { singleton("AutomaticUnlockSession") }
    private val shield by lazy { singleton("AutomaticRunShield") }
    private val credentials by lazy { singleton("AutomaticUnlockCredentials") }
    private val recording get() = InstrumentationRegistry.getArguments().getString("recording") == "true"
    private var manualPinEntries = 0
    private val systemInputDiagnostics = JSONArray()

    private fun demoStage(name: String) {
        if (recording) inst.sendStatus(0, Bundle().apply { putString("stream", "\nDEMO_STAGE $name\n") })
    }

    private fun singleton(name: String): Any = Class.forName("dev.doppel.sdk.$name").getField("INSTANCE").get(null)
    private fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val method = owner.javaClass.declaredMethods.single {
            it.name == name && it.parameterCount == args.size && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        return try { method.invoke(owner, *args) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun active() = call(session, "getActive") as Boolean
    private fun authenticating() = call(session, "isAuthenticating") as Boolean
    private fun shieldVisible(): Boolean {
        var visible = false
        inst.runOnMainSync { visible = call(shield, "getVisible") as Boolean }
        return visible
    }
    private fun unlocked() = power.isInteractive && !lock.isDeviceLocked && !lock.isKeyguardLocked
    private fun noTask() {
        assertNull("This component test must never start a worker", DeviceWorkerService.instance)
        assertTrue("An existing task must be left untouched", prefs.getString("active_run", "").isNullOrBlank())
    }
    private fun await(message: String, timeout: Long = 8000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            noTask()
            if (condition()) return
            Thread.sleep(80)
        }
        assertTrue(message, condition())
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .use { String(it.readBytes(), Charsets.UTF_8).trim() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }.forEach(pending::add)
        while (pending.isNotEmpty() && found.size < 1000) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return found
    }
    private fun inject(action: Int, down: Long, bounds: Rect): Boolean {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { return automation.injectInputEvent(event, true) }
        finally { event.recycle() }
    }
    private fun holdTakeover(millis: Long, finishCaptureDuringHandoff: Boolean = false, startGestureWhileHeld: Boolean = false, action: String = "pause"): Boolean {
        var button: AccessibilityNodeInfo? = null
        await("The normal shield must restore touch handling and expose one takeover control", 4000) {
            var restored = false
            inst.runOnMainSync {
                val passing = shield.javaClass.getDeclaredField("passing").apply { isAccessible = true }.getBoolean(shield)
                restored = call(shield, "getVisible") == true && !passing && call(shield, "isAuthenticating") == false
            }
            if (restored) button = nodes().filter {
                it.packageName?.toString() == context.packageName && it.text?.toString() == (if (action == "cancel") "停止" else "接管") && it.isEnabled
            }.singleOrNull()
            restored && button != null
        }
        val bounds = Rect().also(requireNotNull(button)::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        assertTrue("Takeover touch DOWN must be delivered", inject(MotionEvent.ACTION_DOWN, down, bounds))
        var released = false
        var capture: AutoCloseable? = null
        val operationReady = CountDownLatch(1)
        val operationRelease = CountDownLatch(1)
        val operationClosed = CountDownLatch(1)
        val operationFailure = AtomicReference<Throwable?>()
        var operationThread: Thread? = null
        val gestureFinished = CountDownLatch(1)
        val gestureStarted = CountDownLatch(1)
        val gestureCalls = AtomicInteger()
        var gestureThread: Thread? = null
        val generation = TaskControl.currentGeneration()
        try {
            if (startGestureWhileHeld) {
                gestureThread = Thread({
                    try {
                        val operation = call(session, "deviceOperation", { TaskControl.isCurrent(generation) }) as? AutoCloseable
                        requireNotNull(operation).use {
                            gestureStarted.countDown()
                            val gesture = call(session, "gesturePass") as? AutoCloseable
                            assertNotNull("Holding must defer, not reject the queued gesture", gesture)
                            requireNotNull(gesture).use { gestureCalls.incrementAndGet() }
                        }
                    } catch (failure: Throwable) { operationFailure.set(failure) }
                    finally { gestureStarted.countDown(); gestureFinished.countDown() }
                }, "handoff-queued-gesture-test").apply { start() }
                assertTrue("The queued gesture must begin during the hold", gestureStarted.await(2, TimeUnit.SECONDS))
            }
            if (finishCaptureDuringHandoff) {
                operationThread = Thread({
                    try {
                        val operation = call(session, "deviceOperation", { true }) as? AutoCloseable
                        assertNotNull("A real production observation/action lease must exist", operation)
                        requireNotNull(operation).use {
                            operationReady.countDown()
                            assertTrue("The fixture must release its in-flight operation", operationRelease.await(10, TimeUnit.SECONDS))
                        }
                    } catch (failure: Throwable) { operationFailure.set(failure) }
                    finally { operationReady.countDown(); operationClosed.countDown() }
                }, "handoff-in-flight-operation-test").apply { start() }
                assertTrue("The background operation must acquire its production lease", operationReady.await(3, TimeUnit.SECONDS))
                operationFailure.get()?.let { throw it }
                capture = call(session, "capturePass") as? AutoCloseable
                assertNotNull("A real in-flight screenshot lease must exist", capture)
            }
            Thread.sleep(millis)
            if (startGestureWhileHeld) {
                assertEquals("No new gesture may pass while the user holds the button", 0, gestureCalls.get())
                assertEquals("Holding must retain the original task generation", generation, TaskControl.currentGeneration())
                if (millis >= 3000) awaitLocalAuthentication(generation)
                else assertTrue("The hold must display live progress", nodes().any { it.text?.toString()?.contains("/ 3 秒") == true })
            }
            if (finishCaptureDuringHandoff) {
                assertFalse("An in-flight device operation must delay the password form", authenticating())
                assertTrue("Pending handoff must keep its session", active() && unlocked())
                assertEquals("Pending handoff must not cancel the active operation", generation, TaskControl.currentGeneration())
            }
        } finally {
            released = inject(MotionEvent.ACTION_UP, down, bounds)
            // A completed long hold opens the local password form after the current capture finishes.
            if (millis < 3000) assertTrue("Short-hold UP must be delivered", released)
            if (startGestureWhileHeld) {
                if (authenticating()) inst.runOnMainSync { call(session, "authenticationCancelled") }
                assertTrue("The same queued gesture must resume after release/decline", gestureFinished.await(4, TimeUnit.SECONDS))
                gestureThread?.join(500)
                operationFailure.get()?.let { throw it }
                assertEquals("The queued gesture must receive exactly one lease", 1, gestureCalls.get())
            }
            // Finish the background operation on its owning thread; only now may the form open.
            try { capture?.close() } finally {
                operationRelease.countDown()
                if (operationThread != null) {
                    assertTrue("The background operation must close its lease", operationClosed.await(3, TimeUnit.SECONDS))
                    operationThread?.join(500)
                    operationFailure.get()?.let { throw it }
                }
            }
        }
        return released
    }
    private fun localPasswordField(): android.widget.EditText = shield.javaClass
        .getDeclaredField("authenticationInput").apply { isAccessible = true }.get(shield) as android.widget.EditText
    private fun typeLocalPassword(value: String) {
        for (digit in value) {
            val button = nodes().single {
                it.packageName?.toString() == context.packageName && it.contentDescription?.toString() == "数字 $digit"
            }
            assertTrue("The local PIN keypad must accept a real click", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        var length = 0
        inst.runOnMainSync { length = localPasswordField().text.length }
        assertTrue("Real local input must append the public fixture digits", length >= value.length)
        assertLocalPasswordRedacted()
    }
    private fun assertLocalPasswordRedacted() {
        val visible = nodes()
        assertFalse("Accessibility must never expose the correct or incorrect fixture PIN as node text", visible.any {
            val text = it.text?.toString().orEmpty()
            text.contains("681429") || text.contains("681420")
        })
        assertTrue("Hidden password metadata may be present, but its text must stay empty or masked", visible.filter {
            it.packageName?.toString() == context.packageName && it.isPassword
        }.all { node ->
            node.text.isNullOrBlank() || node.text!!.all { it == '\u2022' || it == '*' || it == '\u25cf' }
        })
    }
    private fun submitLocalPassword(value: String) {
        typeLocalPassword(value)
        assertTrue(nodes().single { it.packageName?.toString() == context.packageName && it.text?.toString() in setOf("确认并接管", "确认并结束") }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun awaitLocalAuthentication(generation: Long, action: String = "pause") {
        await("Long hold must open the app's local password form") {
            authenticating() && nodes().any { it.packageName?.toString() == context.packageName && it.text?.toString() ==
                (if (action == "cancel") "验证后结束任务" else "验证后接管") }
        }
        assertTrue("An unverified handoff must not relock the phone", unlocked())
        assertTrue("The protection window remains installed while authenticating", shieldVisible())
        assertEquals("Unverified handoff must retain the current task generation", generation, TaskControl.currentGeneration())
        assertNull("AI screenshots must not capture the password form", call(session, "capturePass"))
        assertNull("AI gestures must not enter the password form", call(session, "gesturePass"))
        assertLocalPasswordRedacted()
    }
    /** A focused field in a background window is not the current native credential form. */
    private fun systemAuthenticationNodes(): List<AccessibilityNodeInfo> {
        val windows = automation.windows
        try {
            val front = windows.filter { (it.isFocused || it.isActive) && it.type in setOf(
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) }
                .sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.layer })
            val candidates = nodes().filter { it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") }
            return front.firstNotNullOfOrNull { window ->
                candidates.filter { it.windowId == window.id }.takeIf { page -> page.any {
                    it.isPassword && it.isEditable && it.isEnabled || it.viewIdResourceName == "com.android.systemui:id/key6"
                } }
            }.orEmpty()
        } finally { windows.forEach { it.recycle() } }
    }
    private fun systemPinField() = systemAuthenticationNodes().singleOrNull { it.isPassword && it.isEditable && it.isEnabled }
    private fun keypadReady(): Boolean {
        val page = systemAuthenticationNodes()
        return ('0'..'9').all { digit -> page.count {
            it.viewIdResourceName == "com.android.systemui:id/key$digit" && it.isClickable && it.isEnabled
        } == 1 }
    }
    private fun systemInputDiagnostic(stage: String): JSONObject = JSONObject().put("stage", stage)
        .put("device_locked", lock.isDeviceLocked).put("keyguard_locked", lock.isKeyguardLocked).put("interactive", power.isInteractive)
        .put("windows", JSONArray(automation.windows.map { window ->
            val root = window.root
            try { JSONObject().put("id", window.id).put("type", window.type).put("focused", window.isFocused)
                .put("active", window.isActive).put("package", root?.packageName?.toString())
                .put("bounds", Rect().also(window::getBoundsInScreen).toShortString()) }
            finally { root?.recycle(); window.recycle() }
        })).put("fields", JSONArray(nodes().filter {
            it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") && it.isPassword
        }.map { JSONObject().put("id", it.viewIdResourceName).put("window_id", it.windowId)
            .put("package", it.packageName?.toString()).put("focused", it.isFocused).put("editable", it.isEditable)
            .put("length", it.text?.length ?: 0) }))

    /** Only real system widgets receive this known fixture PIN; no locksettings verify/dismiss. */
    private fun enterSystemPin() {
        await("A real system PIN form must be visible") { keypadReady() || systemPinField() != null }
        var previous = ""
        var stableSince = SystemClock.elapsedRealtime()
        await("The native credential window must settle before fixture input") {
            val page = systemAuthenticationNodes()
            val anchor = page.firstOrNull { it.viewIdResourceName == "com.android.systemui:id/key6" }
                ?: page.singleOrNull { it.isPassword && it.isEditable && it.isEnabled }
            val signature = anchor?.let { "${it.packageName}:${it.windowId}:${it.viewIdResourceName}:${Rect().also(it::getBoundsInScreen)}" }.orEmpty()
            if (signature.isBlank() || signature != previous) { previous = signature; stableSince = SystemClock.elapsedRealtime(); false }
            else SystemClock.elapsedRealtime() - stableSince >= 500
        }
        systemInputDiagnostics.put(systemInputDiagnostic("before_input"))
        manualPinEntries++
        if (keypadReady()) {
            for (digit in "681429") {
                val key = systemAuthenticationNodes().single { it.viewIdResourceName == "com.android.systemui:id/key$digit" }
                assertTrue("System PIN digit must accept a normal click", key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(120)
            }
            systemAuthenticationNodes().firstOrNull { it.viewIdResourceName == "com.android.systemui:id/key_enter" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val field = requireNotNull(systemPinField())
            assertTrue("Do not append to a partial credential", field.text.isNullOrEmpty())
            if (!field.isFocused) {
                val bounds = Rect().also(field::getBoundsInScreen)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            }
            await("The same foreground native credential field must have input focus") {
                systemPinField()?.let { it.isFocused && it.windowId == field.windowId && it.viewIdResourceName == field.viewIdResourceName } == true
            }
            shell("input text 681429")
            systemInputDiagnostics.put(systemInputDiagnostic("after_text_input"))
            await("The real credential form must contain all six fixture digits") { systemPinField()?.text?.length == 6 }
            shell("input keyevent 66")
        }
    }
    private fun unlockNormally() {
        if (unlocked()) return
        var awakeSince = 0L
        var lastWake = 0L
        await("The display must finish waking before a lockscreen swipe") {
            val now = SystemClock.elapsedRealtime()
            val awake = power.isInteractive && context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
            if (awake) {
                if (awakeSince == 0L) awakeSince = now
                now - awakeSince >= 200
            } else {
                awakeSince = 0L
                // LOCK_SCREEN can finish its screen-off transition after keyguard already reports locked.
                if (now - lastWake >= 400) { shell("input keyevent 224"); lastWake = now }
                false
            }
        }
        val screen = context.resources.displayMetrics
        if (!keypadReady() && systemPinField() == null)
            shell("input swipe ${screen.widthPixels / 2} ${screen.heightPixels * 4 / 5} ${screen.widthPixels / 2} ${screen.heightPixels / 4} 350")
        enterSystemPin()
        await("The known fixture PIN must unlock the real keyguard") { unlocked() }
    }
    private fun lockScreen() {
        var sent = false
        inst.runOnMainSync {
            sent = requireNotNull(DoppelAccessibilityService.instance).performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
        assertTrue("Real LOCK_SCREEN action must succeed", sent)
        await("Both real keyguard lock states must be set") { lock.isDeviceLocked && lock.isKeyguardLocked }
    }

    private fun verifyShieldGesturePass(key: String, report: JSONObject) {
        val service = requireNotNull(DoppelAccessibilityService.instance)
        val fixture = "dev.doppel.testapp"
        fun fixtureNode(text: String) = nodes().firstOrNull {
            it.packageName?.toString() == fixture && it.text?.toString() == text &&
                automation.windows.any { window -> window.id == it.windowId && window.isFocused }
        }
        fun requireTarget(bounds: Rect) {
            assertTrue("Only our unlocked fixture session may receive input", call(session, "matches", key) == true && unlocked())
            assertEquals("The focused target must retain its bounds", bounds, Rect().also(requireNotNull(fixtureNode("点击目标"))::getBoundsInScreen))
        }
        call(session, "dispatching", key) // RUNNING with an empty runId never sends a task-control request.
        val clicks = AtomicInteger()
        val previousTimeout = automation.serviceInfo.notificationTimeout
        var lease: AutoCloseable? = null
        var failure: Throwable? = null
        try {
            context.startActivity(Intent().setClassName(fixture, "$fixture.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            await("The stateless native gesture fixture must open") { fixtureNode("手势反馈")?.isClickable == true }
            assertTrue(requireNotNull(fixtureNode("手势反馈")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Native click target and its initial feedback must be visible") {
                fixtureNode("点击目标")?.isClickable == true && fixtureNode("等待操作") != null
            }
            // The launch transition scales/translates accessibility bounds even after nodes appear.
            // Settle this stateless fixture before recording the gesture's immutable target.
            var target = Rect()
            var targetWindow = -1
            var stableSince = SystemClock.elapsedRealtime()
            await("The fixture target must finish its launch layout before any gesture") {
                val node = fixtureNode("点击目标")
                if (node == null || !node.refresh()) {
                    stableSince = SystemClock.elapsedRealtime(); false
                } else {
                    val current = Rect().also(node::getBoundsInScreen)
                    if (current.isEmpty || current != target || node.windowId != targetWindow) {
                        target = current; targetWindow = node.windowId; stableSince = SystemClock.elapsedRealtime(); false
                    } else SystemClock.elapsedRealtime() - stableSince >= 500
                }
            }
            automation.serviceInfo = automation.serviceInfo.apply { notificationTimeout = 0 }
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName?.toString() == fixture &&
                    event.text.any { it.toString() == "点击目标" }) clicks.incrementAndGet()
            }
            // Instrumentation runs off-main; production waits for its real input-window update.
            lease = call(session, "gesturePass") as? AutoCloseable
            assertNotNull("The production session must grant a gesture lease", lease)
            requireTarget(target)
            val completed = AtomicBoolean()
            val callback = CountDownLatch(1)
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
                Path().apply { moveTo(target.exactCenterX(), target.exactCenterY()) }, 0, 80)).build()
            var submitted = false
            inst.runOnMainSync {
                submitted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) { completed.set(true); callback.countDown() }
                    override fun onCancelled(description: GestureDescription?) { callback.countDown() }
                }, null)
            }
            assertTrue("The real Android gesture must be submitted", submitted)
            assertTrue("The real Android gesture must complete", callback.await(3, TimeUnit.SECONDS) && completed.get())
            await("Exactly one native button click must reach the covered app") {
                clicks.get() == 1 && fixtureNode("点击成功") != null
            }
            requireNotNull(lease).close(); lease = null
            inst.runOnMainSync { assertTrue(call(shield, "getVisible") as Boolean) }
            Thread.sleep(150)
            requireTarget(target)
            val down = SystemClock.uptimeMillis()
            assertTrue("The normal user probe DOWN must be delivered", inject(MotionEvent.ACTION_DOWN, down, target))
            try { Thread.sleep(80) }
            finally { assertTrue("The normal user probe UP must be delivered", inject(MotionEvent.ACTION_UP, down, target)) }
            Thread.sleep(400)
            assertEquals("Restored shield must block a second native button click", 1, clicks.get())
            assertNotNull("The native button's feedback must remain unchanged", fixtureNode("点击成功"))
            assertTrue("Gesture completion must retain the active shield", active() && shieldVisible() && unlocked())
            noTask()
            report.put("gesture_lease_real_dispatch_completed", true).put("fixture_click_count", clicks.get())
                .put("shield_blocked_user_tap_after_lease", true)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupError = runCatching {
                try { lease?.close() }
                finally {
                    automation.setOnAccessibilityEventListener(null)
                    automation.serviceInfo = automation.serviceInfo.apply { notificationTimeout = previousTimeout }
                    // This fixture has no stored state; close only our foreground instance normally.
                    if (automation.windows.firstOrNull { it.isFocused }?.root?.packageName?.toString() == fixture)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }.exceptionOrNull()
            cleanupError?.let {
                if (failure != null) failure!!.addSuppressed(it) else throw it
            }
        }
    }

    /** Real keyguard and long holds; the LAN lease alone is injected, not claimed as a physical LAN test. */
    @Test fun lanOptInHandoffAndPasswordFallback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lan_handoff_test") == "true")
        assertTrue("Use a disposable app ID, never user history", context.packageName == "dev.doppel.lanqa")
        assertEquals(34, Build.VERSION.SDK_INT)
        assertEquals("1", shell("getprop ro.boot.qemu"))
        noTask(); assertFalse(active()); assertTrue(unlocked() && lock.isDeviceSecure)
        assertNull(SdkCompanionService.instance)
        assertFalse(call(credentials, "hasSaved", context) as Boolean)
        val state = context.getSharedPreferences("doppel_automatic_unlock_state", Context.MODE_PRIVATE)
        assertTrue(state.all.isEmpty())
        val report = JSONObject().put("ok", false).put("model_requests", 0).put("submitted_tasks", 0)
            .put("scope", "real keyguard, production settings/session/shield; injected authenticated presence lease, not physical LAN")
        val folder = File(context.getExternalFilesDir(null), "lan-handoff-verification").apply { mkdirs() }
        val oldInfo = automation.serviceInfo
        var activity: android.app.Activity? = null
        var pairs: CompanionPairings? = null
        var pairId: String? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNotNull(DoppelAccessibilityService.instance)
            FirstUseConsent.accept(context)
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            inst.runOnMainSync { SdkCompanionService.enable(context) }
            await("LAN service must listen on the emulator network", 15000) { SdkCompanionService.instance?.phoneState()?.optBoolean("service_enabled") == true }
            val service = requireNotNull(SdkCompanionService.instance)
            val endpoint = service.javaClass.getDeclaredField("endpoint").apply { isAccessible = true }.get(service) as CompanionEndpoint
            val pairings = endpoint.javaClass.getDeclaredField("pairs").apply { isAccessible = true }.get(endpoint) as CompanionPairings
            pairs = pairings
            assertEquals(0, pairings.phoneState().getJSONArray("pairs").length())
            val invitation = pairings.openWindow()
            val request = pairings.request(invitation.getString("pairing_id"), invitation.getString("secret"), "isolated-LAN-handoff-fixture")
            val requestId = request.getString("pairing_request_id")
            pairings.decide(requestId, true, setOf("state"))
            val granted = pairings.poll(requestId, request.getString("poll_token")).body
            val bearer = granted.getString("bearer")
            pairId = pairings.activate(requestId, bearer).getString("pair_id")
            val auth = pairings.authenticate(bearer)
            val presence = endpoint.javaClass.getDeclaredField("presence").apply { isAccessible = true }.get(endpoint) as CompanionLanPresence
            val kind = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials\$Kind").enumConstants.single { (it as Enum<*>).name == "PIN" }
            val pin = "681429".toCharArray()
            try { call(credentials, "save", context, kind, pin) } finally { pin.fill('\u0000') }
            assertEquals(false, call(credentials, "isLanHandoffEnabled", context))
            fun runCase(name: String, enabled: Boolean, nearby: Boolean, bypass: Boolean, action: String = "pause", expire: Boolean = false, revoke: Boolean = false) {
                call(credentials, "setLanHandoffEnabled", context, enabled)
                presence.clear()
                lockScreen()
                val key = "lan-$name-${UUID.randomUUID()}"
                val ready = AtomicInteger()
                assertEquals(true, call(session, "prepare", context, key, { true }, { ready.incrementAndGet(); Unit }))
                await("$name: actual automatic unlock must complete", 22000) { ready.get() == 1 }
                call(session, "dispatching", key)
                shell("input keyevent 3")
                if (nearby) presence.update(auth, true)
                if (revoke) pairings.revoke(requireNotNull(pairId))
                if (expire) Thread.sleep(9000) // Expires during the subsequent 3-second hold, before action drain.
                val generation = TaskControl.currentGeneration()
                if (bypass) {
                    holdTakeover(120)
                    assertTrue("A short hold must not take over even with nearby PC", active() && !authenticating())
                    assertEquals(generation, TaskControl.currentGeneration())
                }
                holdTakeover(3200, finishCaptureDuringHandoff = true, action = action)
                if (bypass) {
                    await("$name: nearby handoff must release shield without password") { !active() }
                    assertFalse(authenticating()); assertFalse(shieldVisible())
                    assertNotEquals(generation, TaskControl.currentGeneration())
                } else {
                    awaitLocalAuthentication(generation, action)
                    assertTrue("$name: fallback must retain the real session", active() && unlocked())
                    submitLocalPassword("681429")
                    await("$name: owner password must still hand off") { !active() }
                }
                Thread.sleep(1300)
                assertTrue("$name: takeover must not relock", unlocked())
                noTask()
                report.put(name, true)
                File(folder, "result.json").writeText(report.toString(2))
            }
            runCase("default_off_requires_password", false, true, false)
            runCase("enabled_without_presence_requires_password", true, false, false)
            runCase("nearby_takeover_after_operation_drain", true, true, true)
            runCase("lease_expired_during_hold_requires_password", true, true, false, expire = true)
            runCase("stop_still_requires_password", true, true, false, action = "cancel")
            runCase("revoked_pair_requires_password", true, true, false, revoke = true)
            report.put("ok", true)
        } finally {
            if (active()) { call(session, "interrupted"); await("Clean up only this isolated session") { !active() } }
            unlockNormally()
            call(credentials, "clear", context)
            pairId?.let { pairs?.revoke(it) }
            inst.runOnMainSync { SdkCompanionService.disable(context); activity?.finish() }
            automation.serviceInfo = oldInfo
            File(folder, "result.json").writeText(report.toString(2))
        }
    }

    @Test fun realPinUnlockShieldAuthenticatedTakeoverAndFailureRelock() {
        assumeTrue("Public-PIN emulator fixture is opt-in", InstrumentationRegistry.getArguments().getString("automatic_unlock_test") == "true")
        assertEquals("Only the Android 14 emulator fixture is supported", 34, Build.VERSION.SDK_INT)
        assertEquals("Never type the fixture PIN on a physical phone", "1", shell("getprop ro.boot.qemu"))
        noTask()
        assertFalse("Never trigger a connection migration", prefs.getBoolean("artemis_mode", false))
        assertTrue("The host must leave the PIN fixture unlocked", unlocked() && lock.isDeviceSecure)
        assertFalse("Do not overwrite an existing automatic session", active())
        val unlockState = context.getSharedPreferences("doppel_automatic_unlock_state", Context.MODE_PRIVATE)
        assertTrue("Retain any pending recovery state", unlockState.all.isEmpty())
        val gate = call(singleton("TaskSubmissionGate"), "getCreating") as AtomicBoolean
        assertFalse("Do not take an existing submission gate", gate.get())
        val triggerPrefs = context.getSharedPreferences("doppel_auto_triggers", Context.MODE_PRIVATE)
        val rulesBefore = triggerPrefs.getString("rules", null)
        val rules = JSONArray(rulesBefore ?: "[]")
        assertFalse("Do not execute or disable existing trigger rules", (0 until rules.length()).any { rules.getJSONObject(it).getBoolean("enabled") })
        val schedules = File(context.noBackupFilesDir, "schedules-v1.json")
        val schedulesBefore = schedules.takeIf { it.exists() }?.readText()
        val jobs = schedulesBefore?.let { JSONObject(it).getJSONArray("items") } ?: JSONArray()
        assertFalse("Do not execute or disable existing schedules", (0 until jobs.length()).any { jobs.getJSONObject(it).getBoolean("enabled") })
        val credentialFile = File(context.noBackupFilesDir, "automatic-unlock-v1.bin")
        assertFalse("Retain even an unreadable pre-existing credential", listOf("", ".bak", ".new").any { File(credentialFile.path + it).exists() })
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("Retain an existing credential key", store.containsAlias("${context.packageName}.automatic-unlock.v1"))
        val runFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val runsBefore = runFile.takeIf { it.exists() }?.readText()
        val feedbackBefore = if (prefs.contains("action_feedback")) prefs.getBoolean("action_feedback", true) else null
        assertFalse("Rebinding must not change a disabled feedback preference", feedbackBefore == false)
        val oldInfo = automation.serviceInfo
        val key = "unlock-verification-${UUID.randomUUID()}"
        val secondKey = "$key-relock"
        val folder = File(context.getExternalFilesDir(null), "automatic-unlock-verification/$key").apply { check(mkdirs()) }
        val report = JSONObject().put("ok", false).put("scope", if (recording)
            "recording demo subset: real system PIN and production Session; no model task"
            else "real system PIN and production Session; no model task")
            .put("submitted_tasks", 0).put("model_requests", 0)
        var stage = "rebind"
        var ownsCredential = false
        var passed = false
        var primaryFailure: Throwable? = null
        fun assertion(error: Throwable): String? = (error as? AssertionError)?.message?.replace("681429", "[public fixture PIN]")?.take(400)
        fun save() { File(folder, "result.json").writeText(report.put("stage", stage).toString(2)) }
        fun shot(name: String) {
            assertTrue("Never capture a system credential or locked screen", unlocked() && !authenticating())
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        fun prepare(sessionKey: String, count: AtomicInteger) {
            assertTrue("Session.prepare must accept the isolated PIN fixture", call(session, "prepare", context, sessionKey, { true }, { count.incrementAndGet(); Unit }) as Boolean)
            await("Production input must unlock before READY", 22000) { count.get() == 1 }
            assertTrue(unlocked())
            assertTrue(call(session, "approved", sessionKey) as Boolean)
            assertTrue(shieldVisible())
            noTask()
        }
        fun verifyUnchanged() {
            noTask()
            assertTrue("Existing rules must remain unchanged", rulesBefore == triggerPrefs.getString("rules", null))
            assertTrue("Existing schedules must remain unchanged", schedulesBefore == schedules.takeIf { it.exists() }?.readText())
            assertTrue("Existing task records must remain unchanged", runsBefore == runFile.takeIf { it.exists() }?.readText())
            report.put("user_tasks_and_rules_unchanged", true)
        }
        save()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            assertNotNull(DoppelAccessibilityService.instance)
            noTask()
            stage = "save_fixture_credential"; save()
            val kind = Class.forName("dev.doppel.sdk.AutomaticUnlockCredentials\$Kind").enumConstants.single { (it as Enum<*>).name == "PIN" }
            val pin = "681429".toCharArray()
            ownsCredential = true
            try { call(credentials, "save", context, kind, pin) } finally { pin.fill('\u0000') }
            assertEquals("An already-unlocked manual task must never create a protected session", false,
                call(session, "prepare", context, key, { true }, { error("Unlocked device must use the normal task path") }))
            assertFalse(active()); assertFalse(shieldVisible()); assertTrue(unlockState.all.isEmpty()); assertFalse(gate.get())
            report.put("already_unlocked_origin_rejected", true)
            if (!recording) {
                stage = "owner_unlock_returns_to_notice"; save()
                // Delay only the local-input executor, reproducing the owner unlocking before it types.
                val unlockIo = session.javaClass.getDeclaredField("io").apply { isAccessible = true }
                    .get(session) as java.util.concurrent.ExecutorService
                val workerQueued = CountDownLatch(1)
                val allowInput = CountDownLatch(1)
                unlockIo.execute { workerQueued.countDown(); allowInput.await(20, TimeUnit.SECONDS) }
                assertTrue(workerQueued.await(2, TimeUnit.SECONDS))
                val prematureReady = AtomicInteger()
                val noticeAgain = AtomicInteger()
                val clearedBeforeNotice = AtomicBoolean()
                try {
                    lockScreen()
                    assertEquals(true, call(session, "prepare", context, key, { true },
                        { prematureReady.incrementAndGet(); Unit }, {
                            clearedBeforeNotice.set(!active() && call(shield, "getVisible") == false && !gate.get())
                            noticeAgain.incrementAndGet(); Unit
                        }))
                    enterSystemPin()
                    await("The fixture owner must finish unlocking before automatic input") { unlocked() }
                } finally { allowInput.countDown() }
                await("Self-unlock must return this occurrence to its ordinary notice callback") { noticeAgain.get() == 1 }
                assertTrue("The normal notice callback must run after clearing protection", clearedBeforeNotice.get())
                assertEquals("Self-unlock must not approve automatic dispatch", 0, prematureReady.get())
                assertTrue(unlockState.all.isEmpty())
                assertEquals("Self-unlock must retain the configured credential", true, call(credentials, "isEnabled", context))
                report.put("owner_unlock_returned_to_notice_once", true)
            }
            stage = "automatic_unlock"; save()
            lockScreen()
            demoStage("locked_before_automatic_unlock")
            if (recording) Thread.sleep(1500)
            val ready = AtomicInteger()
            prepare(key, ready)
            demoStage("automatic_unlock_ready")
            report.put("real_automatic_unlock", true)
            shell("input keyevent 3")
            await("The shield must remain visible over Home") { shieldVisible() && nodes().any { it.text?.toString() == "接管" } }
            if (recording) Thread.sleep(2000)
            demoStage("home_shield_visible")
            shot("01-home-shield")
            val generation = TaskControl.currentGeneration()
            stage = "short_hold"; save()
            demoStage("short_hold_probe")
            holdTakeover(120)
            holdTakeover(1800)
            Thread.sleep(1500)
            assertTrue("Short presses must keep the session protected", active() && shieldVisible() && !authenticating())
            assertEquals("Short presses must not stop the session", generation, TaskControl.currentGeneration())
            report.put("home_kept_shield", true).put("short_hold_ignored", true)
            if (recording) Thread.sleep(1000)
            demoStage("long_hold_starting")
            stage = "long_hold_authentication"; save()
            call(session, "dispatching", key)
            if (!recording) {
                holdTakeover(1800, startGestureWhileHeld = true)
                assertTrue("A deferred gesture must preserve the protected session", active() && unlocked() && shieldVisible())
                assertFalse("A released short hold must not later open authentication", authenticating())
                assertEquals("A deferred gesture must not cancel or pause the task", generation, TaskControl.currentGeneration())
                holdTakeover(3200, startGestureWhileHeld = true)
                assertTrue("A declined long hold must resume the protected session", active() && unlocked() && shieldVisible())
                assertEquals(generation, TaskControl.currentGeneration())
                report.put("short_hold_deferred_gesture_exactly_once", true).put("long_hold_parked_gesture_no_deadlock", true)
                // Fresh unbound fixture avoids extending the production 30-second READY watchdog.
                call(session, "dispatchFailed", key)
                await("The isolated hold session must relock") { !active() && lock.isDeviceLocked }
                unlockNormally(); lockScreen(); prepare(key, AtomicInteger())
                call(session, "dispatching", key)
            }
            val authenticationGeneration = TaskControl.currentGeneration()
            report.put("long_hold_release_delivered", holdTakeover(3200, finishCaptureDuringHandoff = !recording))
            awaitLocalAuthentication(authenticationGeneration)
            demoStage("local_authentication_visible")
            if (recording) Thread.sleep(1500)
            noTask()
            if (!recording) {
                stage = "local_idle_timeout_and_input_reset"; save()
                assertTrue("Idle deadline must be visible", nodes().any { it.text?.toString()?.matches(Regex("[1-5] 秒无操作后继续任务")) == true })
                typeLocalPassword("6")
                Thread.sleep(3000)
                typeLocalPassword("8")
                Thread.sleep(3000)
                assertTrue("Recent input must reset the five-second idle deadline", authenticating())
                assertEquals(authenticationGeneration, TaskControl.currentGeneration())
                await("Five seconds without input must resume the same session", 3500) { !authenticating() }
                assertTrue("Timeout must retain the same unlocked, shielded session", call(session, "matches", key) == true && unlocked() && shieldVisible())
                assertEquals("Timeout must not stop the task", authenticationGeneration, TaskControl.currentGeneration())
                report.put("local_idle_timeout_resumed", true).put("input_reset_idle_deadline", true)
                // Separate unbound component sessions keep the production READY watchdog intact.
                call(session, "dispatchFailed", key)
                await("Fixture session ends with real relock") { !active() && lock.isDeviceLocked }
                unlockNormally(); lockScreen(); prepare(secondKey, AtomicInteger())
                call(session, "dispatching", secondKey)
                val wrongGeneration = TaskControl.currentGeneration()
                holdTakeover(3200)
                awaitLocalAuthentication(wrongGeneration)
                stage = "local_wrong_password_limit"; save()
                repeat(3) { attempt ->
                    submitLocalPassword("681420")
                    if (attempt < 2) {
                        await("Wrong password feedback must be visible") { nodes().any { it.text?.toString() == "密码错误，还可尝试 ${2 - attempt} 次" } }
                        assertTrue("A wrong password must not give up the shield", authenticating() && shieldVisible() && unlocked())
                    }
                }
                await("Three wrong passwords must close the form and resume") { !authenticating() }
                assertTrue("Wrong-password limit must retain the same session", call(session, "matches", secondKey) == true && unlocked() && shieldVisible())
                assertEquals("Wrong passwords must not send task control", wrongGeneration, TaskControl.currentGeneration())
                val declinedAt = SystemClock.elapsedRealtime()
                holdTakeover(3200)
                assertFalse("Cooldown must ignore another long hold", authenticating())
                Thread.sleep((declinedAt + 10500 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                holdTakeover(3200)
                awaitLocalAuthentication(wrongGeneration)
                report.put("three_wrong_passwords_resumed", true).put("handoff_cooldown_blocked_reentry", true)
            }
            stage = "local_authenticated_takeover"; save()
            val beforeSuccess = TaskControl.currentGeneration()
            val entriesBeforeHandoff = manualPinEntries
            demoStage("test_enters_owner_password_locally")
            submitLocalPassword("681429")
            await("Correct local password must complete handoff without keyguard") { !active() && unlocked() }
            assertFalse(shieldVisible())
            assertNotEquals("Only authenticated handoff may stop the task generation", beforeSuccess, TaskControl.currentGeneration())
            assertEquals("READY must be delivered once", 1, ready.get())
            assertEquals("Handoff must never enter any system PIN screen", entriesBeforeHandoff, manualPinEntries)
            report.put("long_hold_required_local_auth", true).put("authenticated_takeover", true)
                .put("handoff_system_pin_entries", 0).put("handoff_never_relocked", true)
            if (!recording) report.put("operation_delayed_authentication_until_closed", true)
            demoStage("authenticated_takeover")
            if (recording) {
                Thread.sleep(2000)
                verifyUnchanged()
                stage = "recording_demo_completed"
                report.put("ok", true).put("recording_demo_completed", true)
                demoStage(stage)
                passed = true
                return
            }
            shot("02-after-takeover")
            stage = "dispatch_failure_relock"; save()
            lockScreen()
            prepare(secondKey, AtomicInteger())
            stage = "shield_gesture_pass"; save()
            verifyShieldGesturePass(secondKey, report)
            stage = "dispatch_failure_relock"; save()
            call(session, "dispatchFailed", secondKey)
            await("Dispatch failure must relock the real device and clear the session") {
                !active() && lock.isDeviceLocked && lock.isKeyguardLocked && !shieldVisible()
            }
            report.put("dispatch_failure_relocked", true)
            unlockNormally()
            stage = "wrong_credential_stops_without_retry"; save()
            val wrong = "681420".toCharArray()
            try { call(credentials, "save", context, kind, wrong) } finally { wrong.fill('\u0000') }
            val failedCiphertext = credentialFile.readBytes()
            lockScreen()
            val wrongReady = AtomicInteger()
            assertEquals(true, call(session, "prepare", context, secondKey, { true }, { wrongReady.incrementAndGet(); Unit }))
            await("A failed credential attempt must stop and disable automatic unlocking", 22000) {
                !active() && call(credentials, "isEnabled", context) == false && lock.isDeviceLocked && lock.isKeyguardLocked
            }
            assertEquals("Incorrect credential must never dispatch", 0, wrongReady.get())
            assertEquals("Next trigger must not retry the failed password", false,
                call(session, "prepare", context, secondKey, { true }, { wrongReady.incrementAndGet(); Unit }))
            assertFalse(shieldVisible())
            assertEquals(true, call(credentials, "hasSaved", context))
            assertEquals(true, call(credentials, "isSuspended", context))
            assertArrayEquals("Failure must retain the exact encrypted password", failedCiphertext, credentialFile.readBytes())
            assertNull("Background callers must not read a suspended password", call(credentials, "read", context))
            assertTrue("Retaining ciphertext must also retain its decryption key", store.containsAlias("${context.packageName}.automatic-unlock.v1"))
            assertTrue("A locked device cannot re-enable the saved password", runCatching { call(credentials, "reenable", context) }.isFailure)
            assertEquals(false, call(credentials, "isEnabled", context))
            report.put("wrong_credential_disabled_without_retry", true).put("failed_password_ciphertext_and_key_retained", true)
            unlockNormally()
            for (reason in listOf("interrupted", "watchdog", "queued_completion")) {
                stage = "${reason}_live_attempt"; save()
                val interruptedPin = "681429".toCharArray()
                try { call(credentials, "save", context, kind, interruptedPin) } finally { interruptedPin.fill('\u0000') }
                val interruptedCiphertext = credentialFile.readBytes()
                val unlockIo = session.javaClass.getDeclaredField("io").apply { isAccessible = true }.get(session) as java.util.concurrent.ExecutorService
                val ioBlocked = CountDownLatch(1)
                val releaseIo = CountDownLatch(1)
                unlockIo.execute { ioBlocked.countDown(); releaseIo.await(20, TimeUnit.SECONDS) }
                assertTrue(ioBlocked.await(2, TimeUnit.SECONDS))
                try {
                    lockScreen()
                    val interruptedReady = AtomicInteger()
                    val valid = AtomicBoolean(true)
                    assertEquals(true, call(session, "prepare", context, secondKey, { valid.get() }, { interruptedReady.incrementAndGet(); Unit }))
                    await("Preparation must show its shield before interruption") { shieldVisible() }
                    if (reason == "queued_completion") {
                        val mainBlocked = CountDownLatch(1)
                        val releaseMain = CountDownLatch(1)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            mainBlocked.countDown(); releaseMain.await(5, TimeUnit.SECONDS)
                        }
                        assertTrue(mainBlocked.await(2, TimeUnit.SECONDS))
                        try {
                            call(session, "interrupted")
                            val owner = session.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(session)
                            val live = session.javaClass.getDeclaredMethod("live", owner.javaClass).apply { isAccessible = true }
                            val confirm = session.javaClass.getDeclaredMethod("confirmAttempt", owner.javaClass).apply { isAccessible = true }
                            assertEquals("Cancellation must become visible before main-thread cleanup", false, live.invoke(session, owner))
                            assertEquals("An already queued completion cannot confirm after cancellation", false, confirm.invoke(session, owner))
                            assertTrue("Cancellation must retain evidence until cleanup", unlockState.getBoolean("attempt", false))
                        } finally { releaseMain.countDown() }
                    } else if (reason == "interrupted") call(session, "interrupted") else {
                        valid.set(false)
                        val watchdog = session.javaClass.getDeclaredField("watchdog").apply { isAccessible = true }.get(session) as Runnable
                        inst.runOnMainSync { watchdog.run() }
                    }
                    await("An unconfirmed unlock must persistently suspend after $reason") {
                        !active() && lock.isDeviceLocked && call(credentials, "isSuspended", context) == true
                    }
                    assertEquals(0, interruptedReady.get())
                    assertArrayEquals(interruptedCiphertext, credentialFile.readBytes())
                    assertEquals(false, call(session, "prepare", context, secondKey, { true }, { error("An interrupted attempt must never retry") }))
                } finally { releaseIo.countDown() }
                report.put("${reason}_live_attempt_retained_and_suspended", true)
                unlockNormally()
            }
            stage = "interrupted_attempt_recovery"; save()
            val recoveryPin = "681429".toCharArray()
            try { call(credentials, "save", context, kind, recoveryPin) } finally { recoveryPin.fill('\u0000') }
            val recoveryCiphertext = credentialFile.readBytes()
            lockScreen()
            assertTrue(unlockState.edit().putBoolean("attempt", true).putBoolean("protected", true).commit())
            assertEquals("A scheduler arriving before recovery must reject the old uncertain attempt", false,
                call(session, "prepare", context, secondKey, { true }, { error("Old uncertain input must never replay") }))
            assertEquals(true, call(credentials, "isSuspended", context))
            assertFalse(active())
            inst.runOnMainSync { call(session, "recover", requireNotNull(DoppelAccessibilityService.instance)) }
            await("Recovery must disable the interrupted credential and really relock") {
                call(credentials, "isEnabled", context) == false && lock.isDeviceLocked && lock.isKeyguardLocked
            }
            inst.runOnMainSync { call(session, "recover", requireNotNull(DoppelAccessibilityService.instance)) }
            assertFalse("Confirmed recovery may clear its attempt marker", unlockState.getBoolean("attempt", false))
            assertFalse("Confirmed recovery may clear its protected marker", unlockState.getBoolean("protected", false))
            assertArrayEquals("Recovery must preserve the saved password", recoveryCiphertext, credentialFile.readBytes())
            assertEquals(true, call(credentials, "isSuspended", context))
            assertEquals(false, call(credentials, "isEnabled", context))
            report.put("recovery_entry_relocked_and_disabled_credential", true)
                .put("prepare_before_recovery_rejected", true).put("recovery_kept_ciphertext_and_suspension", true)
            unlockNormally()
            verifyUnchanged()
            passed = true
        } catch (error: Throwable) {
            primaryFailure = error
            report.put("failure_type", error.javaClass.simpleName).put("failure_stage", stage)
                .put("failure_assertion", assertion(error) ?: JSONObject.NULL)
            report.put("session_active", active()).put("authenticating", authenticating())
                .put("device_locked", lock.isDeviceLocked).put("keyguard_locked", lock.isKeyguardLocked)
            report.put("system_input_diagnostics", systemInputDiagnostics)
            runCatching { systemInputDiagnostic("failure") }.getOrNull()?.let { report.put("native_window_diagnostic", it) }
            report.put("system_fields", JSONArray(nodes().filter {
                it.packageName?.toString() in setOf("com.android.systemui", "com.android.settings") && it.isPassword
            }.map { JSONObject().put("id", it.viewIdResourceName).put("window_id", it.windowId)
                .put("focused", it.isFocused).put("editable", it.isEditable).put("length", it.text?.length ?: 0) }))
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(work: () -> Unit) {
                try { work() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup {
                if (call(session, "matches", key) == true || call(session, "matches", secondKey) == true) {
                    if (authenticating()) inst.runOnMainSync { call(session, "authenticationCancelled") }
                    call(session, "dispatchFailed", if (call(session, "matches", key) == true) key else secondKey)
                    await("Cleanup must finish this test's session") { !active() }
                }
            }
            cleanup { if (ownsCredential) call(credentials, "clear", context) }
            cleanup { if (ownsCredential) unlockNormally() }
            cleanup {
                check(prefs.edit().apply {
                    if (feedbackBefore == null) remove("action_feedback") else putBoolean("action_feedback", feedbackBefore)
                }.commit())
            }
            cleanup { automation.serviceInfo = oldInfo }
            cleanup {
                assertFalse("Remove the test's encrypted credential", credentialFile.exists())
                assertFalse("Remove the test's credential key", store.containsAlias("${context.packageName}.automatic-unlock.v1"))
                assertFalse("Release the test's submission gate", gate.get())
                assertTrue("Clear the completed session's recovery state", unlockState.all.isEmpty())
                assertTrue("Keep the host's system PIN and unlocked screen", lock.isDeviceSecure && unlocked())
                noTask()
            }
            report.put("fixture_credential_removed", !credentialFile.exists())
                .put("system_pin_retained", lock.isDeviceSecure).put("device_unlocked_after_cleanup", unlocked())
                .put("ok", passed && cleanupFailure == null)
            cleanupFailure?.let {
                report.put("cleanup_failure_type", it.javaClass.simpleName)
                    .put("cleanup_failure_assertion", assertion(it) ?: JSONObject.NULL)
            }
            cleanup { save() }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }
}
