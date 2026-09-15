package dev.doppel.developer

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AccessibilityControlPicker
import dev.doppel.sdk.AutoTriggerSettingsActivity
import dev.doppel.sdk.ControlPickerSnapshotActivity
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real capture/selection, plus deterministic layer and Canvas regressions. No rule save or model request. */
class ControlPickerSnapshotDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setSnapshot(value: Any?) = AccessibilityControlPicker::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.set(null, value)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: T? = null; inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(50) }
        assertTrue(message, condition())
    }
    private fun button(owner: Activity, title: String): TextView = main {
        views(owner.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == title }
    }
    private fun click(owner: Activity, title: String) { main { assertTrue(button(owner, title).performClick()) } }
    private fun clickNode(label: String) {
        await("Missing clickable control: $label") {
            fun visit(node: AccessibilityNodeInfo): Boolean {
                if (node.text?.toString() == label && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                    try { if (visit(child)) return true } finally { child.recycle() }
                }
                return false
            }
            val windows = automation.windows
            try { windows.any { window -> window.root?.let { root -> try { visit(root) } finally { root.recycle() } } == true } }
            finally { windows.forEach { it.recycle() } }
        }
    }
    private fun ready(owner: Activity) = await("Snapshot controls not ready") {
        main { views(owner.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "分层 1/3" && it.visibility == View.VISIBLE } }
    }
    private fun fixtureReady() {
        var previous = ""
        var stableSince = SystemClock.elapsedRealtime()
        await("Fixture window and selected control must finish their launch transition") {
            var signature = ""
            val windows = automation.windows
            try {
                for (window in windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }) {
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() != "dev.doppel.testapp") continue
                        fun find(node: AccessibilityNodeInfo): Rect? {
                            if (node.isVisibleToUser && node.text?.toString() == "浅层同ID目标") return Rect().also(node::getBoundsInScreen)
                            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                                try { find(child)?.let { return it } } finally { child.recycle() }
                            }
                            return null
                        }
                        val target = find(root) ?: continue
                        if (!target.isEmpty) signature = "${window.id}:${Rect().also(window::getBoundsInScreen)}:$target"
                    } finally { root.recycle() }
                }
            } finally { windows.forEach { it.recycle() } }
            if (signature.isBlank() || signature != previous) { previous = signature; stableSince = SystemClock.elapsedRealtime(); false }
            else SystemClock.elapsedRealtime() - stableSince >= 500
        }
    }
    private fun captureFailure(label: String): String = main {
        val launcher = field(AccessibilityControlPicker, "launcher") as? TextView
        "$label; active=${AccessibilityControlPicker.active}, launcher_attached=${launcher?.isAttachedToWindow}, launcher_state=${launcher?.text}, pending=${field(AccessibilityControlPicker, "pending") != null}"
    }
    private fun preview(owner: Activity): View = main { views(owner.window.decorView).first { it.contentDescription == "冻结的应用截图" } }
    private fun tapSource(owner: Activity, sourceX: Float, sourceY: Float) {
        main {
            val image = preview(owner)
            val capture = field(owner, "frozen")!!
            val bitmap = field(capture, "bitmap") as Bitmap
            val dw = field(capture, "displayWidth") as Int; val dh = field(capture, "displayHeight") as Int
            val scale = minOf(image.width.toFloat() / bitmap.width, image.height.toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            val x = (image.width - w) / 2 + sourceX * w / dw
            val y = (image.height - h) / 2 + sourceY * h / dh
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 30 else 0, action, x, y, 0)
                try { assertTrue(image.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }
    }
    private fun requireSafeEntry() {
        assertTrue("Consent must already be accepted", FirstUseConsent.isAccepted(context))
        assertFalse("Never replace an existing picker session", AccessibilityControlPicker.active)
        assertTrue("Never interrupt a running worker", DeviceWorkerService.instance?.isPaused != false)
    }

    @Test fun realCaptureReselectAndConfirmOnlyPrefillsTaskEditor() {
        requireSafeEntry()
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        requireNotNull(DoppelAccessibilityService.instance)
        val prefs = context.getSharedPreferences("doppel", 0)
        val beforePointer = prefs.getString("active_run", null)
        val beforeRules = context.getSharedPreferences("doppel_auto_triggers", 0).all
        val runs = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val beforeRuns = runs.takeIf { it.isFile }?.readText()
        val originalAutomationFlags = automation.serviceInfo.flags
        var editor: Activity? = null; var chooser: Activity? = null
        var monitor = inst.addMonitor(ControlPickerSnapshotActivity::class.java.name, null, false)
        val editorMonitor = inst.addMonitor(AutoTriggerSettingsActivity::class.java.name, null, false)
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            editor = inst.startActivitySync(Intent(context, AutoTriggerSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click(editor, "从当前应用选择控件")
            await("Picker session missing") { AccessibilityControlPicker.active }
            val noticeType = Class.forName("dev.doppel.sdk.AutomaticTaskNotice")
            val notice = noticeType.getField("INSTANCE").get(null)
            assertEquals("Picking controls must block automatic task admission", "device_busy",
                noticeType.getMethod("localBlockReason", android.content.Context::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(notice, context, false))
            Thread.sleep(350)
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            clickNode("控件扫描验证")
            fixtureReady()
            clickNode("读取控件")
            val firstChooser = inst.waitForMonitorWithTimeout(monitor, 15000) ?: error(captureFailure("Capture did not return to the snapshot Activity"))
            chooser = firstChooser
            ready(firstChooser)
            val frozen = main { field(firstChooser, "frozen")!! }
            assertEquals("dev.doppel.testapp", field(frozen, "packageName"))
            val bitmap = field(frozen, "bitmap") as Bitmap
            assertTrue(bitmap.width > 100 && bitmap.height > 100)
            @Suppress("UNCHECKED_CAST") val nodes = field(frozen, "nodes") as List<Any>
            assertTrue("Only ID-backed controls may be selected", nodes.all { (field(it, "id") as String).isNotBlank() })
            assertFalse("No-ID description target must be ignored", nodes.any { field(it, "text") == "无ID描述目标" })
            val leaf = nodes.first { field(it, "text") == "浅层同ID目标" }
            val bounds = field(leaf, "bounds") as Rect
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            Thread.sleep(500)
            assertSame("Snapshot must stay frozen while the target app is in the background", frozen, main { field(firstChooser, "frozen") })
            assertEquals(pixel, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            click(firstChooser, "分层 1/3"); click(firstChooser, "分层 2/3"); click(firstChooser, "分层 3/3")
            assertTrue(button(firstChooser, "分层 1/3").isShown)
            tapSource(firstChooser, bounds.exactCenterX(), bounds.exactCenterY())
            assertTrue(button(firstChooser, "浅层同ID目标").isShown)
            click(firstChooser, "取消选择")
            assertFalse(button(firstChooser, "确认").isEnabled)
            click(firstChooser, "重新选择")
            await("The first chooser must finish before registering the next capture") { firstChooser.isDestroyed }
            await("Reselect did not restore floating capture button") { main { field(AccessibilityControlPicker, "launcher") is View } }
            assertTrue(AccessibilityControlPicker.active)
            // Bring the fixture's existing interaction screen forward; reselect itself uses the app launcher intent.
            context.startActivity(Intent().setClassName("dev.doppel.testapp", "dev.doppel.testapp.InteractionFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            clickNode("控件扫描验证")
            fixtureReady()
            // The first monitor was removed by waitForMonitorWithTimeout. Register only
            // after its activity is destroyed, so an old resume cannot match this capture.
            monitor = inst.addMonitor(ControlPickerSnapshotActivity::class.java.name, null, false)
            clickNode("读取控件")
            val secondChooser = inst.waitForMonitorWithTimeout(monitor, 15000) ?: error(captureFailure("Second capture missing"))
            chooser = secondChooser
            ready(secondChooser)
            val second = main { field(secondChooser, "frozen")!! }
            @Suppress("UNCHECKED_CAST") val secondNodes = field(second, "nodes") as List<Any>
            val picked = secondNodes.first { field(it, "text") == "浅层同ID目标" }
            val pickedBounds = field(picked, "bounds") as Rect
            tapSource(secondChooser, pickedBounds.exactCenterX(), pickedBounds.exactCenterY())
            click(secondChooser, "确认")
            await("Confirmed picker must release the capture session") { !AccessibilityControlPicker.active }
            await("Task editor was not opened") { editorMonitor.lastActivity != null && editorMonitor.lastActivity !== chooser }
            editor = editorMonitor.lastActivity
            await("Task content must be editable without a task being submitted") {
                fun hasGoal(node: AccessibilityNodeInfo): Boolean {
                    if (node.isEditable && node.hintText?.toString() == "触发后执行的任务内容") return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { if (hasGoal(child)) return true } finally { child.recycle() } }
                    return false
                }
                automation.rootInActiveWindow?.let { root -> try { hasGoal(root) } finally { root.recycle() } } == true
            }
        } finally {
            main { AccessibilityControlPicker.stop(); chooser?.finish(); editor?.finish() }
            inst.removeMonitor(monitor); inst.removeMonitor(editorMonitor)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAutomationFlags }
            assertEquals(beforePointer, prefs.getString("active_run", null))
            assertEquals(beforeRules, context.getSharedPreferences("doppel_auto_triggers", 0).all)
            assertEquals(beforeRuns, runs.takeIf { it.isFile }?.readText())
        }
    }

    private fun node(id: String, bounds: Rect, parent: Int?): Any {
        val type = Class.forName("dev.doppel.sdk.AccessibilityControlPicker\$Node")
        return type.declaredConstructors.single { it.parameterTypes.size == 4 }.apply { isAccessible = true }.newInstance(id, id, bounds, parent)
    }
    private fun capture(nodes: List<Any>): Any {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val type = Class.forName("dev.doppel.sdk.AccessibilityControlPicker\$Snapshot")
        return type.declaredConstructors.single { it.parameterTypes.size == 6 }.apply { isAccessible = true }
            .newInstance(UUID.randomUUID().toString(), "dev.doppel.testapp", bitmap, 100, 100, nodes)
    }
    @Suppress("UNCHECKED_CAST")
    private fun layers(snapshot: Any) = snapshot.javaClass.getMethod("getLayers").invoke(snapshot) as List<List<Int>>
    private fun open(snapshot: Any): Activity {
        main { setSnapshot(snapshot) }
        return inst.startActivitySync(Intent(context, ControlPickerSnapshotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("picker_token", field(snapshot, "token") as String).putExtra("picker_package", "dev.doppel.testapp")).also { ready(it) }
    }

    @Test fun unevenBranchesStopAtThreeLayersAndCancelRestoresSelectedLayer() {
        requireSafeEntry()
        val small = Rect(10, 10, 25, 25); val large = Rect(0, 0, 40, 40); val right = Rect(60, 0, 100, 100)
        val capture = capture(listOf(node("short-parent", large, null), node("short-leaf", small, 0),
            node("deep-root", right, null), node("deep-4", right, 2), node("deep-3", right, 3), node("deep-2", right, 4), node("deep-leaf", right, 5)))
        assertEquals(listOf(listOf(1, 6), listOf(0, 5), listOf(4)), layers(capture))
        val owner = open(capture)
        try {
            tapSource(owner, 15f, 15f)
            assertTrue(button(owner, "short-leaf").isShown)
            tapSource(owner, 15f, 15f)
            assertTrue(button(owner, "short-parent").isShown)
            assertTrue(button(owner, "分层 2/3").isShown)
            click(owner, "取消选择")
            assertTrue(button(owner, "分层 1/3").isShown)
            assertFalse(button(owner, "确认").isEnabled)
            click(owner, "分层 1/3"); click(owner, "分层 2/3"); click(owner, "分层 3/3")
            assertTrue(button(owner, "分层 1/3").isShown)
        } finally { main { owner.finish(); AccessibilityControlPicker.stop() } }
    }

    @Test fun overlappingSiblingsHaveUniformHighlightBrightness() {
        requireSafeEntry()
        val capture = capture(listOf(node("left", Rect(10, 10, 70, 70), null), node("right", Rect(40, 10, 90, 70), null)))
        val owner = open(capture)
        try {
            main {
                val image = preview(owner)
                val rendered = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                image.draw(Canvas(rendered))
                try {
                    val scale = minOf(image.width, image.height) / 100f
                    val left = (image.width - scale * 100) / 2; val top = (image.height - scale * 100) / 2
                    fun pixel(x: Float) = rendered.getPixel((left + x * scale).toInt(), (top + 40 * scale).toInt())
                    assertEquals("Overlap must not be brighter than singly covered pixels", pixel(30f), pixel(60f))
                    assertNotEquals("Highlight must actually be rendered", Color.BLACK, pixel(30f))
                } finally { rendered.recycle() }
            }
        } finally { main { owner.finish(); AccessibilityControlPicker.stop() } }
    }
}
