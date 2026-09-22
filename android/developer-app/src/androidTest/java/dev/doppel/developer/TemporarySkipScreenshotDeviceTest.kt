@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.InputDevice
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Opt-in real API 34 full-display captures with temporary owned-layer exclusion. Fixed fixture commands, no model requests or saved tasks.
 * Run with -e temporary_skip_screenshot true. Uses the device's currently selected input method.
 * The host grants/restores this test host's notification permission for the notification test.
 */
class TemporarySkipScreenshotDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    } }
    private val fixture = "dev.doppel.testapp"
    private lateinit var service: DoppelAccessibilityService
    private lateinit var folder: File
    private lateinit var report: JSONObject
    private var companion: CompanionOverlay? = null
    private var session = ""

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        inst.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 7000
        do { if (predicate()) return; SystemClock.sleep(70) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, predicate())
    }
    private fun collect(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::collect).orEmpty() }
    private fun <T> nodes(block: (List<AccessibilityNodeInfo>) -> T): T {
        val windows = ui.windows
        val all = try { windows.flatMap { it.root?.let(::collect).orEmpty() } }
        finally { windows.forEach { it.recycle() } }
        return try { block(all) } finally { all.forEach { it.recycle() } }
    }
    private fun has(text: String) = nodes { all -> all.any { it.isVisibleToUser && it.text?.toString() == text } }
    private fun click(text: String) {
        await("Fixture control must exist: $text") { has(text) }
        assertTrue(nodes { all -> all.first { it.isVisibleToUser && it.isClickable && it.text?.toString() == text }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK) })
    }
    private fun bounds(text: String): Rect = nodes { all -> Rect().also(all.first {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }::getBoundsInScreen) }
    private fun state(): JSONObject? = nodes { all -> all.firstOrNull {
        it.packageName?.toString() == fixture && it.contentDescription?.startsWith("popup-result:") == true
    }?.contentDescription?.toString()?.substringAfter("popup-result:")?.let(::JSONObject) }
    private fun foregroundId(): Int? {
        val windows = service.windows
        return try { windows.firstOrNull { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }?.id }
        finally { windows.forEach { it.recycle() } }
    }
    private fun windowIds(): Set<Int> {
        val windows = service.windows
        return try { windows.map { it.id }.toSet() } finally { windows.forEach { it.recycle() } }
    }
    private fun open(activity: String = "PopupFixtureActivity", secure: Boolean = false, password: Boolean = false) {
        val previousCreation = if (activity == "PopupFixtureActivity") state()?.optString("creation") else null
        context.startActivity(Intent().setClassName(fixture, "$fixture.$activity")
            .putExtra("session", session).putExtra("capture_controls", true)
            .putExtra("capture_secure", secure)
            .putExtra("capture_password", password)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("A fresh fixture must reach the foreground") { if (activity == "PopupFixtureActivity")
            state()?.let { it.optString("session") == session && it.optString("creation") != previousCreation } == true
            else has("交互验收") }
        SystemClock.sleep(400)
    }
    private fun request(kind: String) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("run_id", "skip-screenshot-$session").put("kind", kind).put("mode", "full").put("split_agent", true)
    private fun png(name: String, bitmap: Bitmap) = File(folder, "$name.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    private fun bitmap(shot: JSONObject): Bitmap {
        val bytes = Base64.decode(shot.getJSONObject("data").getString("image_base64"), Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    private fun raw(name: String): Bitmap {
        SystemClock.sleep(350)
        val pending = CompletableFuture<Bitmap>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, ForkJoinPool.commonPool(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                var hardware: Bitmap? = null
                var pixels: Bitmap? = null
                try {
                    hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    pixels = requireNotNull(hardware?.copy(Bitmap.Config.ARGB_8888, false))
                    if (pending.complete(pixels)) pixels = null
                } catch (error: Throwable) { pending.completeExceptionally(error) }
                finally { pixels?.recycle(); hardware?.recycle(); result.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode: Int) { pending.completeExceptionally(IllegalStateException("raw_capture_$errorCode")) }
        })
        val image = try { pending.get(5, TimeUnit.SECONDS) } catch (error: TimeoutException) {
            if (pending.completeExceptionally(error)) throw error else pending.get()
        }
        return image.also { png(name, it) }
    }
    private fun ownViews(): List<View> {
        val own = requireNotNull(companion)
        @Suppress("UNCHECKED_CAST")
        return listOf(field(own, "root") as View) + (field(own, "edgeWindows") as List<View>).toList()
    }
    private fun showCompanion() {
        companion = main { CompanionOverlay(context) {}.also { it.show() } }
        requireNotNull(companion).display(JSONObject().put("id", "skip-screenshot-fixture").put("status", "running")
            .put("message", "正在验证原生窗口截图"), "正在思考")
        await("Companion and four edge windows must be attached") {
            main { ownViews().size == 5 && ownViews().all { it.isAttachedToWindow } }
        }
        SystemClock.sleep(300)
    }
    private fun settle() {
        var signature = main { field(service, "windowSignature").toString() }
        var stable = SystemClock.elapsedRealtime()
        val until = stable + 4000
        do {
            SystemClock.sleep(70)
            val next = main { field(service, "windowSignature").toString() }
            if (next != signature) { signature = next; stable = SystemClock.elapsedRealtime() }
            else if (SystemClock.elapsedRealtime() - stable >= 500) return
        } while (SystemClock.elapsedRealtime() < until)
        fail("Static fixture windows did not settle")
    }
    private fun capture(name: String): JSONObject {
        settle()
        val handler = Handler(Looper.getMainLooper())
        var samples = 0; var altered = 0
        val sample = object : Runnable {
            override fun run() {
                samples++
                if (companion != null && (field(requireNotNull(companion), "captureHidden") == true || ownViews().any {
                    !it.isAttachedToWindow || it.visibility != View.VISIBLE || it.alpha != 1f ||
                        (it.layoutParams as WindowManager.LayoutParams).alpha != 1f
                })) altered++
                handler.postDelayed(this, 16)
            }
        }
        main { sample.run() }
        val shot = try { service.execute(request("screenshot")) }
        finally { main { handler.removeCallbacks(sample) } }
        report.put(name, JSONObject(shot.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            .put("${name}_overlay_samples", samples).put("${name}_overlay_altered", altered)
        File(folder, "report.json").writeText(report.toString(2))
        assertEquals(shot.optString("message"), "ok", shot.optString("status"))
        val data = shot.getJSONObject("data")
        assertEquals("accessibility_skip_screenshot", data.getString("capture_backend"))
        assertFalse(data.getBoolean("overlay_cleanup_performed"))
        assertFalse("The native route must not perform inverse pixel reconstruction", data.has("overlay_reconstruction"))
        assertFalse("Full-display capture must not use window composition", data.has("native_window_capture"))
        assertTrue(samples > 0)
        assertEquals("Capture must leave the companion and edge windows opaque and visible", 0, altered)
        val frame = data.getJSONObject("visual_frame")
        val truth = requireNotNull(ui.takeScreenshot())
        try {
            assertEquals(truth.width, frame.getInt("display_width"))
            assertEquals(truth.height, frame.getInt("display_height"))
        } finally { truth.recycle() }
        val image = bitmap(shot)
        try {
            png(name, image)
            assertEquals(frame.getInt("image_width"), image.width)
            assertEquals(frame.getInt("image_height"), image.height)
            assertEquals(frame.getInt("display_width").toDouble() / frame.getInt("display_height"),
                image.width.toDouble() / image.height, 1.0 / image.height)
        } finally { image.recycle() }
        return shot
    }
    private fun colorPixels(shot: JSONObject, color: Int): Int {
        val image = bitmap(shot)
        return try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            pixels.count { (it and 0x00ffffff) == color }
        } finally { image.recycle() }
    }
    private fun compare(name: String, reference: Bitmap, shot: JSONObject, region: Rect) {
        val actual = bitmap(shot)
        try { comparePixels(name, reference, actual, region) } finally { actual.recycle() }
    }
    private fun comparePixels(name: String, reference: Bitmap, actual: Bitmap, region: Rect, maxMean: Double = 4.0): Double {
        val scaled = Bitmap.createScaledBitmap(reference, actual.width, actual.height, true)
        try {
            val area = Rect(region.left * actual.width / reference.width, region.top * actual.height / reference.height,
                region.right * actual.width / reference.width, region.bottom * actual.height / reference.height)
            assertTrue(area.intersect(0, 0, actual.width, actual.height))
            var total = 0L; var pixels = 0
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                val expected = scaled.getPixel(x, y); val seen = actual.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) total += kotlin.math.abs((expected shr shift and 255) - (seen shr shift and 255))
                pixels++
            }
            val mean = total.toDouble() / (pixels * 3)
            report.put(name, JSONObject().put("pixels", pixels).put("mean_rgb_error", mean))
            assertTrue("$name must retain actual current pixels (mean RGB error $mean)", mean <= maxMean)
            return mean
        } finally { if (scaled !== reference) scaled.recycle() }
    }
    private fun viewBounds(view: View): Rect {
        val position = IntArray(2); view.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    private fun rawShowsAllOverlays(reference: Bitmap, label: String) {
        val actual = raw(label)
        try {
            val areas = main { ownViews().map(::viewBounds) }
            assertEquals(5, areas.size)
            for ((index, area) in areas.withIndex()) {
                val difference = comparePixels("${label}_layer_$index", reference, actual, area, Double.POSITIVE_INFINITY)
                assertTrue("Raw capture must show restored owned layer $index", difference > .05)
            }
        } finally { actual.recycle() }
    }
    private fun tap(shot: JSONObject, area: Rect): JSONObject {
        val frame = shot.getJSONObject("data").getJSONObject("visual_frame")
        return request("split_action").put("source", frame).put("action", JSONObject().put("status", "located")
            .put("action", "tap").put("target", "底层操作").put("duration_ms", 100)
            .put("points", JSONArray().put(JSONArray(listOf(area.exactCenterX() * 1000.0 / frame.getInt("display_width"),
                area.exactCenterY() * 1000.0 / frame.getInt("display_height"))))))
    }
    private fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("direct-runs-v1.json", "schedules-v1.json", "model-providers-v1.bin", "credential-vault-v1.bin").forEach {
            val file = File(context.noBackupFilesDir, it); if (file.isFile) digest.update(file.readBytes())
        }
        digest.update(context.getSharedPreferences("doppel_auto_triggers", 0).getString("rules", "").orEmpty().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun scenario(name: String, work: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("temporary_skip_screenshot") == "true")
        assertTrue(Build.VERSION.SDK_INT >= 34); ui
        assertNull(DeviceWorkerService.instance)
        assertFalse(DirectRuntime.get(context).hasUnfinishedRun())
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(Settings.canDrawOverlays(context))
        assertTrue("Do not trigger configured work during fixtures", AutoTriggerStore(context).list().none { it.enabled })
        val scheduleFile = File(context.noBackupFilesDir, "schedules-v1.json")
        val schedules = if (scheduleFile.isFile) JSONObject(scheduleFile.readText()).getJSONArray("items") else JSONArray()
        assertFalse("Do not race an enabled scheduled task", (0 until schedules.length()).any { schedules.getJSONObject(it).optBoolean("enabled") })
        if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        service = requireNotNull(DoppelAccessibilityService.instance)
        assertFalse(service.guardVisible)
        val before = digest()
        val prefs = context.getSharedPreferences("doppel", 0)
        val saved = listOf("companion_y", "companion_right_edge").associateWith { prefs.all[it] }
        session = UUID.randomUUID().toString()
        folder = File(context.getExternalFilesDir(null), "temporary-skip-screenshot/$name-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        report = JSONObject().put("passed", false).put("model_calls", 0).put("persisted_tasks_created", 0).put("test", name)
        try { work(); report.put("passed", true) }
        catch (error: Throwable) {
            report.put("error", error.javaClass.simpleName).put("message", error.message.orEmpty().take(900))
            if (error is AssumptionViolatedException) report.put("skipped", true)
            throw error
        } finally {
            main { companion?.close(); companion = null; service.stopActionFeedback() }
            prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
                is Int -> putInt(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val unchanged = before == digest() && saved.all { prefs.all[it.key] == it.value }
            report.put("user_data_and_overlay_preferences_unchanged", unchanged)
            if (!unchanged) report.put("passed", false)
            File(folder, "report.json").writeText(report.toString(2))
            assertTrue("Preserve tasks, rules, credentials, model configuration and overlay preferences", unchanged)
        }
    }

    @Test fun fullDisplayOmitsOpaqueCompanionAndFourEdgesThenRestoresRawPixels() = scenario("ordinary-dialog") {
        open()
        val reference = raw("page-clean")
        try {
            showCompanion()
            rawShowsAllOverlays(reference, "raw-before-capture")
            val base = capture("base")
            val baseId = requireNotNull(foregroundId())
            assertTrue(colorPixels(base, 0x1e285a) > 500)
            main { ownViews().map(::viewBounds) }.forEachIndexed { index, bounds -> compare("excluded_owned_layer_$index", reference, base, bounds) }
            rawShowsAllOverlays(reference, "raw-restored-after-capture")
            main { companion?.close(); companion = null }
            click("打开普通弹窗")
            await("The dialog must be visible") { has("关闭弹窗") }
            await("The service must observe the dialog as its focused window") { foregroundId()?.let { it != baseId } == true }
            val dialogId = requireNotNull(foregroundId())
            settle()
            val dialogReference = raw("dialog-clean")
            try {
                showCompanion()
                val dialog = capture("dialog")
                assertNotEquals(baseId, dialogId)
                assertEquals(dialogId, foregroundId())
                compare("dialog_and_real_dimmed_background", dialogReference, dialog,
                    Rect(6, 6, dialogReference.width - 6, dialogReference.height - 6))
                assertTrue("Foreground current green panel must remain visible", colorPixels(dialog, 0x1f6d53) > 100)
            } finally { dialogReference.recycle() }
            click("关闭弹窗")
            var closedState: JSONObject? = null
            await("The fixture must report exactly one completed dialog close") {
                state()?.takeIf { it.optString("session") == session && it.optInt("closed") == 1 }
                    ?.also { closedState = it } != null
            }
            assertEquals(0, requireNotNull(closedState).getInt("background_clicks"))
        } finally { reference.recycle() }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun permissionFlags(pkg: String = fixture): Set<String> = shell("dumpsys package $pkg").lineSequence()
        .first { it.trimStart().startsWith("${Manifest.permission.POST_NOTIFICATIONS}: granted=") }
        .substringAfter("flags=[").substringBefore(']').split('|').map(String::trim).filter(String::isNotEmpty).toSet()
    private fun denyFixturePermission() {
        if (nodes { all -> all.firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }) return
        // Test-only manual input for this known disposable prompt when its node root is unavailable.
        for (key in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER)) {
            ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, key), true)
            ui.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, key), true)
        }
    }
    private fun permissionScenario(password: Boolean = false, work: (Int, Int) -> Unit) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = context.packageManager.checkPermission(permission, fixture) == PackageManager.PERMISSION_GRANTED
        val flags = permissionFlags()
        assertTrue(flags.none { it in setOf("POLICY_FIXED", "SYSTEM_FIXED") })
        var prompt = false
        try {
            if (granted) ui.revokeRuntimePermission(fixture, permission)
            shell("pm clear-permission-flags --user current $fixture $permission user-set user-fixed")
            open(password = password)
            val base = capture("before-permission")
            val baseId = requireNotNull(foregroundId())
            assertTrue(colorPixels(base, 0x1e285a) > 500)
            if (password) {
                val data = base.getJSONObject("data")
                assertTrue("The fixture's visible password must be recognized before the prompt", data.getInt("privacy_mask_count") > 0)
                val area = bounds("截图测试密码")
                assertTrue(nodes { all -> all.any { it.contentDescription?.toString() == "截图测试密码" && it.isPassword && it.isVisibleToUser } })
                val visible = raw("password-visible-before-mask")
                try {
                    assertTrue("The password field must be fully on the real display", area.left >= 0 && area.top >= 0 &&
                        area.right <= visible.width && area.bottom <= visible.height && area.width() > 4 && area.height() > 4)
                    var fieldPixels = 0; var passwordGlyphPixels = 0
                    for (y in area.top + 2 until area.bottom - 2) for (x in area.left + 2 until area.right - 2) {
                        val pixel = visible.getPixel(x, y)
                        if ((pixel and 0x00ffffff) == 0x104e38) fieldPixels++
                        if (Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) passwordGlyphPixels++
                    }
                    report.put("raw_password_field_pixels", fieldPixels).put("raw_password_glyph_pixels", passwordGlyphPixels)
                    assertTrue("The real EditText background must be visibly rendered above the page", fieldPixels > 100)
                    assertTrue("The real password characters must be visible before local masking", passwordGlyphPixels > 10)
                } finally { visible.recycle() }
                val frame = data.getJSONObject("visual_frame")
                val image = bitmap(base)
                try {
                    val left = area.left * image.width / frame.getInt("display_width") + 2
                    val right = area.right * image.width / frame.getInt("display_width") - 2
                    val top = area.top * image.height / frame.getInt("display_height") + 2
                    val bottom = area.bottom * image.height / frame.getInt("display_height") - 2
                    assertTrue("Password pixels must be within the captured viewport", left >= 0 && top >= 0 &&
                        right <= image.width && bottom <= image.height && left < right && top < bottom)
                    var masked = true
                    for (y in top until bottom) for (x in left until right)
                        if (image.getPixel(x, y) != 0xff333333.toInt()) masked = false
                    assertTrue("The entire exported password interior must be locally masked", masked)
                    report.put("visible_password_mask_verified", true)
                } finally { image.recycle() }
            }
            click("请求通知权限"); prompt = true
            await("The OS permission window must become foreground") { foregroundId()?.let { it != baseId } == true }
            SystemClock.sleep(800)
            work(baseId, requireNotNull(foregroundId()))
        } finally {
            main { companion?.close(); companion = null }
            try { if (prompt) denyFixturePermission() }
            finally {
                if (granted) ui.grantRuntimePermission(fixture, permission) else ui.revokeRuntimePermission(fixture, permission)
                shell("pm clear-permission-flags --user current $fixture $permission user-set user-fixed")
                val userFlags = flags.intersect(setOf("USER_SET", "USER_FIXED"))
                if (userFlags.isNotEmpty()) shell("pm set-permission-flags --user current $fixture $permission ${userFlags.joinToString(" ") { it.lowercase().replace('_', '-') }}")
                assertEquals(granted, context.packageManager.checkPermission(permission, fixture) == PackageManager.PERMISSION_GRANTED)
                assertEquals(userFlags, permissionFlags().intersect(setOf("USER_SET", "USER_FIXED")))
                report.put("permission_and_flags_restored", true)
            }
        }
    }

    private fun verifyCurrentPermissionPixels(label: String, promptId: Int) {
        val reference = raw("$label-clean")
        try {
            showCompanion()
            val shot = capture(label)
            assertEquals(promptId, foregroundId())
            compare("${label}_full_current_page", reference, shot, Rect(6, 6, reference.width - 6, reference.height - 6))
            val actual = bitmap(shot)
            try {
                var rose = 0
                for (y in actual.height * 3 / 4 until actual.height * 9 / 10) for (x in actual.width / 4 until actual.width * 3 / 4) {
                    val pixel = actual.getPixel(x, y)
                    val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
                    if (red > 25 && red > blue * 1.4 && blue > green * 1.4) rose++
                }
                report.put("${label}_changed_background_pixels", rose)
                assertTrue("The covered app changed from blue to rose; old captures cannot satisfy this", rose > 500)
            } finally { actual.recycle() }
        } finally { reference.recycle() }
    }
    @Test fun nativePermissionRetainsRealTimeBackgroundAndSystemDimming() = scenario("native-permission") {
        permissionScenario { baseId, promptId ->
            verifyCurrentPermissionPixels("permission", promptId)
            report.put("covered_app_live_update_proven", true).put("base_listed_after_permission", baseId in windowIds())
        }
    }

    @Test fun unreadableSensitiveBackdropBlocksInsteadOfReusingItsOldMask() = scenario("sensitive-backdrop") {
        permissionScenario(password = true) { baseId, promptId ->
            val windows = service.windows
            val backdropReadable = try {
                val root = windows.firstOrNull { it.id == baseId }?.root
                try { root?.isVisibleToUser == true } finally { root?.recycle() }
            } finally { windows.forEach { it.recycle() } }
            report.put("backdrop_tree_readable_after_permission", backdropReadable)
            assumeTrue("This device must hide the covered password window's tree to exercise fail-closed capture", !backdropReadable)
            assertEquals(promptId, foregroundId())
            showCompanion(); settle()
            val receipt = service.execute(request("screenshot"))
            val data = receipt.optJSONObject("data") ?: JSONObject()
            report.put("sensitive_backdrop_receipt", JSONObject(receipt.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            assertEquals("An unreadable known-sensitive background must not be captured with stale password bounds", "blocked", receipt.optString("status"))
            assertEquals("login", data.optString("human_takeover"))
            assertFalse("No password or partially masked image may leave the device", data.has("image_base64"))
            assertEquals(promptId, foregroundId())
            report.put("stale_password_mask_not_used", true)
        }
    }

    @Test fun coldServiceCapturesNativePermissionAndCurrentBackgroundWithoutSavedId() = scenario("cold-permission") {
        permissionScenario { baseId, promptId ->
            val previous = service
            report.put("service_rebind", AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst))
            service = requireNotNull(DoppelAccessibilityService.instance)
            assertNotSame("The screenshot path must start with a new service instance", previous, service)
            assertEquals("Cold restart must not dismiss the existing permission window", promptId, foregroundId())
            report.put("base_listed_after_rebind", baseId in windowIds())
            verifyCurrentPermissionPixels("cold-permission", promptId)
        }
    }

    @Test fun recreatedActivityUsesCurrentPixelsAndRejectsOldFrame() = scenario("recreated-window") {
        open(); showCompanion()
        val before = capture("before-recreate")
        val oldId = requireNotNull(foregroundId())
        val oldAction = tap(before, bounds("底层操作"))
        val oldCreation = requireNotNull(state()).getString("creation")
        click("重建测试窗口")
        await("Activity must actually recreate") { state()?.optString("creation")?.let { it != oldCreation } == true }
        await("New activity must receive a different window ID") { foregroundId()?.let { it != oldId } == true }
        val shot = capture("after-recreate")
        val newId = requireNotNull(foregroundId())
        assertNotEquals(oldId, newId)
        assertNotEquals(before.getJSONObject("data").getJSONObject("visual_frame").getString("screen_id"),
            shot.getJSONObject("data").getJSONObject("visual_frame").getString("screen_id"))
        val receipt = service.execute(oldAction)
        report.put("old_window_action", receipt)
        assertEquals("stale", receipt.optString("status"))
        assertEquals("not_dispatched", receipt.getJSONObject("data").getString("action_state"))
        assertEquals(0, requireNotNull(state()).getInt("background_clicks"))
        assertTrue(colorPixels(shot, 0x1e285a) > 500)
    }

    @Test fun popupAfterCaptureRejectsOldActionWithoutDispatch() = scenario("stale-action") {
        open(); showCompanion()
        val source = capture("before-popup")
        val oldAction = tap(source, bounds("底层操作"))
        click("延迟显示弹窗")
        await("The popup must appear after the source capture") { has("关闭弹窗") }
        val receipt = service.execute(oldAction)
        report.put("stale_receipt", receipt)
        assertEquals("stale", receipt.optString("status"))
        val data = receipt.getJSONObject("data")
        assertEquals("not_dispatched", data.getString("action_state"))
        assertEquals(0, data.getInt("completed_strokes"))
        assertTrue(data.getString("reason_code") in setOf("source_navigation_changed", "gesture_context_changed"))
        assertEquals(0, requireNotNull(state()).getInt("background_clicks"))
        assertEquals(0, requireNotNull(state()).getInt("closed"))
        assertTrue(has("关闭弹窗"))
        capture("current-popup")
        click("关闭弹窗")
    }

    @Test fun delayedOwnBackgroundWindowEventKeepsCapturedActionValid() = scenario("delayed-own-overlay-event") {
        open()
        // This standalone fixture overlay has no DeviceWorker touch handoff. Keep it away
        // from the target so this regression isolates event identity, not overlay input.
        context.getSharedPreferences("doppel", 0).edit().putInt("companion_y", 96).commit()
        showCompanion()
        val source = capture("before-delayed-event")
        val frame = source.getJSONObject("data").getJSONObject("visual_frame")
        val foreground = requireNotNull(foregroundId())
        val target = bounds("底层操作")
        val overlayBounds = main { ownViews().map(::viewBounds) }
        report.put("intended_display_point", JSONArray(listOf(target.centerX(), target.centerY())))
            .put("own_overlay_bounds", JSONArray(overlayBounds.map { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }))
        assertTrue("The standalone fixture overlays must not intercept the intended tap",
            overlayBounds.none { it.contains(target.centerX(), target.centerY()) })
        val action = tap(source, target)
        assertEquals(fixture, frame.getString("package_name"))
        assertEquals(0, requireNotNull(state()).getInt("background_clicks"))
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = context.packageName
            className = View::class.java.name
            eventTime = SystemClock.uptimeMillis()
        }
        try {
            assertEquals("Reproduce an event whose own overlay is not in the window list", -1, event.windowId)
            assertFalse(event.windowId in windowIds())
            main {
                val generation = field(service, "navigationGeneration")
                service.onAccessibilityEvent(event)
                assertEquals("A late event from our nonforeground window must not advance navigation", generation,
                    field(service, "navigationGeneration"))
            }
        } finally { event.recycle() }
        assertEquals("The real fixture must remain foreground", foreground, foregroundId())
        val afterEvent = service.observe()
        assertEquals("The captured screen identity must survive the irrelevant own-window event",
            frame.getString("screen_id"), afterEvent.getString("screen_id"))
        val receipt = service.execute(action)
        report.put("source_screen_id", frame.getString("screen_id")).put("after_event_screen_id", afterEvent.getString("screen_id"))
            .put("delayed_event_scope", "deterministic injected own-package TYPE_WINDOW_STATE_CHANGED with no listed window; real production gesture")
            .put("action_receipt", receipt)
        assertEquals(receipt.optString("message"), "ok", receipt.optString("status"))
        assertEquals("accepted", receipt.getJSONObject("data").getString("action_state"))
        var clicked: JSONObject? = null
        await("The actual background button must receive exactly one click") {
            state()?.also { report.put("last_fixture_state", it) }
                ?.takeIf { it.optString("session") == session && it.optInt("background_clicks") == 1 }
                ?.also { clicked = it } != null
        }
        assertEquals(1, requireNotNull(clicked).getInt("background_clicks"))
        assertEquals(0, requireNotNull(clicked).getInt("closed"))
        assertEquals(foreground, foregroundId())
        report.put("actual_button_clicks", 1).put("unrelated_own_event_did_not_invalidate_capture", true)
    }

    @Test fun actualInputMethodPixelsSurviveOpaqueCompanionOverlap() = scenario("keyboard") {
        val selected = shell("settings get secure default_input_method").trim()
        val enabled = shell("settings get secure enabled_input_methods").trim()
        assertTrue("The device must have a currently selected input method", selected.isNotBlank() && selected != "null")
        report.put("input_method_selected", selected).put("input_method_scope", "actual currently selected OS input method; no test replacement")
        open("InteractionFixtureActivity"); click("模拟登录")
        val input = bounds("搜索关键词")
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, input.exactCenterX(), input.exactCenterY(), 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; assertTrue(ui.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
        val keyboard = Rect()
        var keyboardId = -1
        await("The actual input method must be visible") {
            val windows = ui.windows
            try { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
                keyboardId = it.id; it.getBoundsInScreen(keyboard); keyboard.width() > 0 && keyboard.height() > 0
            } == true } finally { windows.forEach { it.recycle() } }
        }
        SystemClock.sleep(500)
        val reference = raw("keyboard-clean")
        try {
            context.getSharedPreferences("doppel", 0).edit().putInt("companion_y", keyboard.top + 16).commit()
            showCompanion()
            val overlap = Rect(main { requireNotNull(companion).bounds() }!!)
            assertTrue("Fixture companion must overlap real keyboard pixels", overlap.intersect(keyboard))
            val shot = capture("keyboard")
            assertTrue("The input method must remain a real OS window", keyboardId in windowIds())
            compare("keyboard_under_companion", reference, shot, overlap)
            compare("keyboard_interior", reference, shot, Rect(keyboard).apply { inset(25, 25) })
        } finally {
            reference.recycle()
            assertEquals(selected, shell("settings get secure default_input_method").trim())
            assertEquals(enabled, shell("settings get secure enabled_input_methods").trim())
            report.put("input_method_configuration_unchanged", true)
        }
    }

    private fun interruptedCaptureRestoresLayers(interrupted: Boolean) {
        open()
        val reference = raw("cleanup-clean")
        try {
            showCompanion()
            val expected = if (interrupted) InterruptedException("fixture") else IllegalStateException("fixture")
            var reachedCapture = false
            try {
                TemporaryScreenshotExclusion.capture<Unit> {
                    reachedCapture = true
                    val excluded = raw("inside-exclusion")
                    try { main { ownViews().map(::viewBounds) }.forEachIndexed { index, bounds ->
                        comparePixels("during_exclusion_layer_$index", reference, excluded, bounds)
                    } } finally { excluded.recycle() }
                    if (interrupted) Thread.currentThread().interrupt()
                    throw expected
                }
                fail("The capture failure must propagate")
            } catch (error: Throwable) {
                assertSame("The original callback failure must survive cleanup", expected, error)
                assertTrue(reachedCapture)
                assertTrue("Flag restoration must not add a suppressed cleanup error", error.suppressed.isEmpty())
                if (interrupted) assertTrue("Cleanup must restore the caller's cancellation flag", Thread.currentThread().isInterrupted)
            } finally { if (interrupted) Thread.interrupted() }
            rawShowsAllOverlays(reference, "raw-after-cleanup")
            capture("production-after-cleanup")
            rawShowsAllOverlays(reference, "raw-after-next-production-capture")
            report.put("scope", "production exclusion helper lifecycle; injected callback failure; no user task cancelled")
                .put("original_callback_failure_preserved", true).put("raw_layers_restored", true)
        } finally { reference.recycle() }
    }
    @Test fun callbackExceptionRestoresAllOwnedLayersBeforeNextCapture() = scenario("exception-cleanup") {
        interruptedCaptureRestoresLayers(false)
    }
    @Test fun interruptedCallbackRestoresAllOwnedLayersAndPreservesCancellation() = scenario("cancellation-cleanup") {
        interruptedCaptureRestoresLayers(true)
    }

    @Test fun transientOwnedOverlayIsDetectedAfterItIsRemovedAndOriginalLayersAreRestored() = scenario("transient-overlay") {
        open()
        val reference = raw("transient-clean")
        val manager = service.getSystemService(WindowManager::class.java)
        var transient: View? = null
        try {
            showCompanion()
            val originals = main { ownViews() }
            val area = Rect(reference.width / 3, reference.height / 2, reference.width * 2 / 3, reference.height * 5 / 8)
            var callbackCompleted = false
            try {
                TemporaryScreenshotExclusion.capture {
                    try {
                        main {
                            val view = View(service).apply {
                                setBackgroundColor(Color.RED)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            }
                            transient = view
                            TemporaryScreenshotExclusion.addView(manager, view, WindowManager.LayoutParams(
                                area.width(), area.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE).apply {
                                gravity = Gravity.TOP or Gravity.LEFT; x = area.left; y = area.top; setFitInsetsTypes(0)
                            })
                        }
                        await("The transient owned overlay must really attach") { main { transient?.isAttachedToWindow == true } }
                        val during = raw("transient-layer-during-capture")
                        try {
                            assertEquals("A layer created during capture must really render in that capture", Color.RED,
                                during.getPixel(area.centerX(), area.centerY()))
                        } finally { during.recycle() }
                    } finally {
                        main { transient?.takeIf { it.isAttachedToWindow }?.let(manager::removeViewImmediate) }
                    }
                    assertFalse(main { requireNotNull(transient).isAttachedToWindow })
                    assertEquals("The original owned views must be identical at both capture endpoints", originals, main { ownViews() })
                    callbackCompleted = true
                }
                fail("An add/remove between endpoint snapshots must invalidate the capture")
            } catch (changed: TemporaryScreenshotExclusion.Changed) {
                assertTrue("The temporary layer must have been removed before the final snapshot", callbackCompleted)
                assertEquals("owned_overlay_changed", changed.message)
                assertTrue("Restoring the original layers must succeed", changed.suppressed.isEmpty())
                report.put("transient_layer_rendered_and_removed", true).put("capture_rejection", changed.message)
            }
            rawShowsAllOverlays(reference, "raw-after-transient-rejection")
            capture("production-after-transient-rejection")
            rawShowsAllOverlays(reference, "raw-after-transient-recovery")
        } finally {
            main { transient?.takeIf { it.isAttachedToWindow }?.let(manager::removeViewImmediate) }
            reference.recycle()
        }
    }

    @Test fun secureWindowExportsNoProtectedPixelsAndDoesNotLeaveOverlaysExcluded() = scenario("secure-window") {
        open(); showCompanion()
        assertTrue("The fixture must render real blue pixels before protection", colorPixels(capture("before-secure"), 0x1e285a) > 500)
        try {
            open(secure = true); settle()
            val shot = service.execute(request("screenshot"))
            report.put("secure_receipt", JSONObject(shot.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            val data = shot.optJSONObject("data") ?: JSONObject()
            when (shot.optString("status")) {
                "blocked" -> {
                    assertEquals("Protection must be attributed to the secure window", "capture_secure_window", data.optString("reason_code"))
                    assertFalse(data.has("image_base64"))
                    report.put("secure_mode", "native_rejection")
                }
                "ok" -> {
                    assertEquals("accessibility_skip_screenshot", data.getString("capture_backend"))
                    val image = bitmap(shot)
                    try {
                        png("secure-redacted", image)
                        var black = 0; var total = 0
                        for (y in image.height * 3 / 4 until image.height * 9 / 10) for (x in image.width / 4 until image.width * 3 / 4) {
                            val value = image.getPixel(x, y)
                            if (Color.red(value) <= 16 && Color.green(value) <= 16 && Color.blue(value) <= 16) black++
                            total++
                        }
                        val fraction = black.toDouble() / total
                        report.put("protected_region_black_fraction", fraction).put("secure_mode", "system_redaction")
                        assertTrue("System must black out the previously proven app content", fraction >= .99)
                    } finally { image.recycle() }
                }
                else -> fail("Unexpected secure-window result: ${shot.optString("status")}")
            }
        } finally { open() }
        main { companion?.close(); companion = null }
        val clean = raw("after-secure-clean")
        try {
            showCompanion()
            capture("after-secure-production")
            rawShowsAllOverlays(clean, "after-secure-raw-restored")
        } finally { clean.recycle() }
    }

    @Test fun lockedOrSleepingDeviceBlocksCaptureWithoutReusingEarlierPixels() = scenario("keyguard") {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeTrue("Never lock a device protected by an unknown user credential", !keyguard.isDeviceSecure)
        val power = context.getSystemService(PowerManager::class.java)
        assertTrue("The disposable fixture must initially be awake", power.isInteractive)
        report.put("keyguard_initially_locked", keyguard.isKeyguardLocked).put("device_secure", keyguard.isDeviceSecure)
        open(); showCompanion(); capture("before-lock")
        try {
            assertTrue(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
            val until = SystemClock.elapsedRealtime() + 2000
            while (!keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            report.put("keyguard_after_lock", keyguard.isKeyguardLocked).put("screen_interactive_after_lock", power.isInteractive)
                .put("scope", if (keyguard.isKeyguardLocked) "keyguard" else "screen_off_only")
            assertTrue("LOCK_SCREEN must actually lock or turn off the fixture", keyguard.isKeyguardLocked || !power.isInteractive)
            val receipt = service.execute(request("screenshot"))
            val data = receipt.optJSONObject("data") ?: JSONObject()
            report.put("keyguard_receipt", JSONObject(receipt.toString()).apply { optJSONObject("data")?.remove("image_base64") })
            assertEquals("blocked", receipt.optString("status"))
            assertEquals("device_locked", data.optString("reason_code"))
            assertFalse("A locked device must not deliver earlier or current pixels", data.has("image_base64"))
            assertFalse("A lock must stop capture before any native window composition", data.has("native_window_capture"))
            assertFalse(data.has("overlay_reconstruction"))
        } finally {
            // Only dismiss the already verified insecure fixture; never configure or enter a PIN.
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            await("Restore the originally unlocked, awake device") { power.isInteractive && !keyguard.isKeyguardLocked }
            report.put("original_unlocked_state_restored", true)
        }
    }

    @Test fun notificationShadeRetainsActualTestMessage() = scenario("notification-shade") {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        assumeTrue("Host must grant and later restore its own POST_NOTIFICATIONS permission",
            context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
        val flags = permissionFlags(context.packageName)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "qa-native-capture-${UUID.randomUUID()}"
        val notificationId = 94652
        val title = "Native window fixture"
        val body = "静态窗口截图测试消息"
        try {
            manager.createNotificationChannel(NotificationChannel(channel, "截图验证", NotificationManager.IMPORTANCE_LOW))
            manager.notify(channel, notificationId, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true).build())
            open(); capture("before-shade")
            assertTrue(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            await("The real notification shade must display the disposable message") { has(body) }
            SystemClock.sleep(500)
            val region = bounds(body).apply { union(bounds(title)); inset(-3, -3) }
            val reference = raw("shade-clean")
            try {
                showCompanion()
                val shot = capture("notification-shade")
                compare("actual_notification_text", reference, shot, region)
            } finally { reference.recycle() }
        } finally {
            main { companion?.close(); companion = null }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            manager.cancel(channel, notificationId); manager.deleteNotificationChannel(channel)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission(permission, context.packageName))
            assertEquals(flags, permissionFlags(context.packageName))
            assertTrue(manager.activeNotifications.none { it.tag == channel && it.id == notificationId })
            report.put("notification_removed_and_permission_unchanged", true)
        }
    }
}
