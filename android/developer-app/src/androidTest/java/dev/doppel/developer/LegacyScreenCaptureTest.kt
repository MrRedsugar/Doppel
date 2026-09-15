package dev.doppel.developer

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AppearanceActivity
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.LegacyScreenCaptureActivity
import dev.doppel.sdk.LegacyScreenCaptureService
import dev.doppel.sdk.LoginAssist
import dev.doppel.sdk.LoginSettingsActivity
import dev.doppel.sdk.VoiceActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in device evidence. UiAutomation confirms fixture setup; capture acceptance uses the app source. */
class LegacyScreenCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun capturesFreshOwnScreensAndHonorsSessionBoundaries() {
        assumeTrue("Requires legacy_capture=true", InstrumentationRegistry.getArguments().getString("legacy_capture") == "true")
        assumeTrue("Legacy capture covers Android 8 through 10", Build.VERSION.SDK_INT in 26..29)
        val context = instrumentation.targetContext
        val started = SystemClock.elapsedRealtime()
        val evidence = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("image_source", "LegacyScreenCaptureService")
        val frames = JSONArray(); evidence.put("frames", frames)
        val windows = mutableListOf<Activity>()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "legacy-capture-qa").apply { isDaemon = true } }
        val savedServices = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
        val savedEnabled = Settings.Secure.getString(context.contentResolver, "accessibility_enabled")
        val consentPrefs = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val savedConsent = consentPrefs.all.toMap()
        var automation: UiAutomation? = null
        var settingsTouched = false
        var consentTouched = false
        var ownsSession = false
        var stage = "idle_preflight"
        var failure: Throwable? = null
        try {
            assertNull("Run only when the device worker is stopped", DeviceWorkerService.instance)
            assertFalse("Run only without unfinished local tasks", DirectRuntime.get(context).hasUnfinishedRun())
            assertTrue("Run only without an active task pointer", context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
            assertFalse("Do not replace an existing screen sharing session", LegacyScreenCaptureService.isReady)
            consentTouched = true
            check(FirstUseConsent.accept(context)); check(FirstUseConsent.finishGuide(context))
            ownsSession = true
            val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation = ui
            val own = ComponentName(context, DoppelAccessibilityService::class.java)
            val services = savedServices.orEmpty().split(':').filter { it.isNotBlank() && it != "null" }
            stage = "accessibility_setup"
            settingsTouched = true
            if (services.none { ComponentName.unflattenFromString(it) == own }) {
                writeSecureSetting(ui, "enabled_accessibility_services", (services + own.flattenToString()).joinToString(":"))
            }
            writeSecureSetting(ui, "accessibility_enabled", "1")
            awaitCondition(8000, "Doppel accessibility must bind") { DoppelAccessibilityService.instance != null }

            stage = "screen_capture_authorization"
            LegacyCaptureConsent.authorize(instrumentation, verifyDenial = true)
            evidence.put("denial_kept_unready", true).put("consent_ready", true)

            stage = "fixture_setup"
            val fixtureActivity = open(AppearanceActivity::class.java).also(windows::add)
            val fixture = CaptureFixture(fixtureActivity)
            instrumentation.runOnMainSync { fixtureActivity.setContentView(fixture) }
            val metrics = DisplayMetrics()
            fixtureActivity.windowManager.defaultDisplay.getRealMetrics(metrics)
            evidence.put("width", metrics.widthPixels).put("height", metrics.heightPixels).put("density_dpi", metrics.densityDpi)

            fun capture(cancel: () -> Boolean = { false }): Bitmap {
                val abandoned = AtomicBoolean(false)
                val future = executor.submit<Bitmap> {
                    val bitmap = LegacyScreenCaptureService.capture(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) {
                        abandoned.get() || Thread.currentThread().isInterrupted || cancel()
                    }
                    if (abandoned.get()) { bitmap.recycle(); throw CancellationException("Capture caller already left") }
                    bitmap
                }
                try { return future.get(8, TimeUnit.SECONDS) }
                catch (error: ExecutionException) { throw error.cause ?: error }
                catch (error: TimeoutException) {
                    abandoned.set(true)
                    if (!future.cancel(true)) runCatching { future.get(0, TimeUnit.MILLISECONDS).recycle() }
                    throw AssertionError("App capture exceeded its bounded wait", error)
                }
            }
            fun checkFrame(number: Int, color: Int, redraw: Boolean = true) {
                if (redraw) showFixture(fixture, number, color)
                assertTrue("Only the test fixture may own the foreground", fixtureActivity.hasWindowFocus())
                val bounds = Rect()
                instrumentation.runOnMainSync {
                    val origin = IntArray(2); fixture.getLocationOnScreen(origin)
                    bounds.set(origin[0], origin[1], origin[0] + fixture.width, origin[1] + fixture.height)
                }
                // onDraw/Choreographer do not prove the new Activity surface has been presented.
                // Establish that input before measuring capture; never replace the service bitmap.
                if (redraw) {
                    val setupAt = SystemClock.elapsedRealtime()
                    val setupFuture = executor.submit<Int> {
                        var matched = 0
                        val deadline = SystemClock.elapsedRealtime() + 4000
                        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                            val image = ui.takeScreenshot()
                            try {
                                matched = 0
                                if (image != null && bounds.right <= image.width && bounds.bottom <= image.height) {
                                    for (y in 0 until 8) for (x in 0 until 8) {
                                        val pixel = image.getPixel(bounds.left + bounds.width() * (16 + x) / 40,
                                            bounds.top + bounds.height() * (24 + y) / 40)
                                        if (kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 20 &&
                                            kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 20 &&
                                            kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 20) matched++
                                    }
                                }
                            } finally { image?.recycle() }
                            if (matched >= 60) break
                            Thread.sleep(50)
                        }
                        matched
                    }
                    try {
                        val matched = setupFuture.get(5, TimeUnit.SECONDS)
                        evidence.put("fixture_setup_$number", JSONObject().put("source", "UiAutomation_setup_only")
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - setupAt).put("matching_samples", matched))
                        assertTrue("The fixture must be presented before testing app capture", matched >= 60)
                    } finally { if (!setupFuture.isDone) setupFuture.cancel(true) }
                }
                val at = SystemClock.elapsedRealtime()
                val bitmap = capture()
                val captureMs = SystemClock.elapsedRealtime() - at
                try {
                    fun rgb(pixel: Int) = JSONArray(listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
                    // One immediate control image distinguishes the displayed surface from the app's mirror.
                    // It never replaces bitmap or participates in any acceptance assertion below.
                    val controlStartedAt = SystemClock.elapsedRealtime()
                    val controlDiagnostic = JSONObject().put("source", "UiAutomation.takeScreenshot_diagnostic_only")
                        .put("after_service_capture_ms", controlStartedAt - at - captureMs)
                    val controlAbandoned = AtomicBoolean(false)
                    val controlImage = AtomicReference<Bitmap?>()
                    val controlFuture = executor.submit {
                        controlImage.set(ui.takeScreenshot())
                        if (controlAbandoned.get()) controlImage.getAndSet(null)?.recycle()
                    }
                    var control: Bitmap? = null
                    try {
                        controlFuture.get(5, TimeUnit.SECONDS)
                        control = controlImage.getAndSet(null)
                        controlDiagnostic.put("capture_ms", SystemClock.elapsedRealtime() - controlStartedAt).put("available", control != null)
                        control?.let { image ->
                            controlDiagnostic.put("width", image.width).put("height", image.height)
                                .put("center_rgb", rgb(image.getPixel(image.width / 2, image.height / 2)))
                            File(context.filesDir, "legacy-capture-control-$number.png").outputStream().use {
                                controlDiagnostic.put("png_saved", image.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                    } catch (error: Exception) {
                        controlDiagnostic.put("failure_class", (if (error is ExecutionException) error.cause ?: error else error).javaClass.simpleName)
                    } finally {
                        control?.recycle()
                        controlAbandoned.set(true)
                        controlFuture.cancel(true)
                        controlImage.getAndSet(null)?.recycle()
                    }
                    controlDiagnostic.put("window_context_after_service_ms", SystemClock.elapsedRealtime() - at - captureMs)
                    instrumentation.runOnMainSync {
                        controlDiagnostic.put("activity_decor_display_id", fixtureActivity.window.decorView.display?.displayId ?: -1)
                            .put("activity_window_manager_display_id", fixtureActivity.windowManager.defaultDisplay.displayId)
                            .put("fixture_view_display_id", fixture.display?.displayId ?: -1)
                            .put("activity_has_window_focus", fixtureActivity.hasWindowFocus())
                    }
                    runCatching {
                        val currentRoot = ui.rootInActiveWindow
                        try { controlDiagnostic.put("ui_automation_root_package", currentRoot?.packageName?.toString() ?: JSONObject.NULL) }
                        finally { currentRoot?.recycle() }
                    }.onFailure { controlDiagnostic.put("root_read_failure_class", it.javaClass.simpleName) }
                    runCatching { DoppelAccessibilityService.instance?.foregroundPackage() }
                        .onSuccess { controlDiagnostic.put("accessibility_foreground_package", it ?: JSONObject.NULL) }
                        .onFailure { controlDiagnostic.put("foreground_read_failure_class", it.javaClass.simpleName) }
                    File(context.filesDir, "legacy-capture-$number.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val sampleRgb = JSONObject()
                    for ((name, point) in listOf("center" to (2 to 2), "quarter_top_left" to (1 to 1),
                        "quarter_top_right" to (3 to 1), "quarter_bottom_left" to (1 to 3), "quarter_bottom_right" to (3 to 3))) {
                        sampleRgb.put(name, rgb(bitmap.getPixel(bitmap.width * point.first / 4, bitmap.height * point.second / 4)))
                    }
                    val frameEvidence = JSONObject().put("sequence", number).put("width", bitmap.width).put("height", bitmap.height)
                        .put("expected_rgb", rgb(color)).put("sample_rgb", sampleRgb)
                        .put("capture_ms", captureMs).put("control_diagnostic", controlDiagnostic).put("fixture_redrawn", redraw)
                    frames.put(frameEvidence)
                    assertTrue("The fixture must remain foreground throughout capture", fixtureActivity.hasWindowFocus())
                    assertEquals(metrics.widthPixels, bitmap.width); assertEquals(metrics.heightPixels, bitmap.height)
                    check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
                    var nonBlack = 0
                    for (y in 0 until 32) for (x in 0 until 32) {
                        if (bitmap.getPixel(x * bitmap.width / 32, y * bitmap.height / 32) and 0x00ffffff != 0) nonBlack++
                    }
                    assertTrue("Actual app capture must contain visible pixels", nonBlack > 0)
                    var matched = 0
                    for (y in 0 until 8) for (x in 0 until 8) {
                        val pixel = bitmap.getPixel(bounds.left + bounds.width() * (16 + x) / 40, bounds.top + bounds.height() * (24 + y) / 40)
                        if (kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 20 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 20 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 20) matched++
                    }
                    frameEvidence.put("sampled_nonblack", nonBlack).put("matching_fixture_samples", matched).put("sample_count", 64)
                    if (matched == 0) {
                        try {
                            frameEvidence.put("mirror_probe", LegacyMirrorProbe.collect(context, ui, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) { pulse ->
                                instrumentation.runOnMainSync {
                                    fixture.sequence = 100 + pulse
                                    fixture.fill = if (pulse % 2 == 0) Color.rgb(212, 43, 68) else Color.rgb(35, 78, 216)
                                    fixture.invalidate()
                                }
                            })
                        } finally {
                            instrumentation.runOnMainSync { fixture.sequence = number; fixture.fill = color; fixture.invalidate() }
                        }
                    }
                    assertTrue("A new request must contain the new fixture color, not an earlier frame", matched >= 60)
                } finally { bitmap.recycle() }
            }
            stage = "first_frame"; checkFrame(1, Color.rgb(212, 43, 68))
            stage = "static_frame_without_redraw"; checkFrame(4, Color.rgb(212, 43, 68), redraw = false)
            stage = "fresh_second_frame"; checkFrame(2, Color.rgb(35, 78, 216))
            stage = "cancel_request"
            val cancelChecks = AtomicInteger()
            val cancelAt = SystemClock.elapsedRealtime()
            val cancelled = runCatching { capture { cancelChecks.incrementAndGet(); true } }
            cancelled.getOrNull()?.recycle()
            assertTrue("The app must reject a cancelled read", cancelled.exceptionOrNull() is CancellationException)
            assertTrue("The app must consult the cancellation signal", cancelChecks.get() > 0)
            evidence.put("cancel_checks", cancelChecks.get()).put("cancel_ms", SystemClock.elapsedRealtime() - cancelAt)
                .put("cancel_scope", "already_cancelled_request")
            stage = "fresh_frame_after_cancel"; checkFrame(3, Color.rgb(33, 176, 102))

            stage = "open_voice_task"
            val voice = open(VoiceActivity::class.java).also(windows::add)
            awaitCondition(5000, "The real voice entry must own a separate focused root task") {
                var ready = false
                instrumentation.runOnMainSync { ready = voice.hasWindowFocus() && voice.isTaskRoot }
                ready
            }
            val voiceTaskId = voice.taskId
            assertTrue("Voice entry must use its independent task", voiceTaskId != fixtureActivity.taskId)
            evidence.put("voice_task_id", voiceTaskId).put("voice_was_focused_task_root", true)
            stage = "remove_voice_task"
            instrumentation.runOnMainSync { voice.finishAndRemoveTask() }
            awaitCondition(5000, "The voice task must disappear and the original fixture regain focus") {
                val removed = context.getSystemService(ActivityManager::class.java).appTasks.none { it.taskInfo.id == voiceTaskId }
                var fixtureFocused = false
                instrumentation.runOnMainSync { fixtureFocused = fixtureActivity.hasWindowFocus() && voice.isDestroyed }
                removed && fixtureFocused
            }
            evidence.put("voice_task_removed", true).put("capture_ready_after_voice_task_removed", LegacyScreenCaptureService.isReady)
            stage = "capture_after_voice_task_removed"
            checkFrame(5, Color.rgb(33, 176, 102), redraw = false)
            evidence.put("capture_survived_voice_task_removal", true)

            stage = "login_privacy_gate"
            val login = open(LoginSettingsActivity::class.java).also(windows::add)
            awaitCondition(3000, "Login privacy state must be visible") { LoginAssist.settingsVisible && login.hasWindowFocus() }
            assertTrue(login.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val privacyFuture = executor.submit<JSONObject> {
                DoppelAccessibilityService.instance!!.execute(JSONObject().put("id", "legacy-capture-privacy")
                    .put("run_id", "legacy-capture-qa").put("kind", "screenshot"))
            }
            val privacy = try { privacyFuture.get(8, TimeUnit.SECONDS) } finally { if (!privacyFuture.isDone) privacyFuture.cancel(true) }
            assertEquals("blocked", privacy.optString("status"))
            assertEquals("login", privacy.optJSONObject("data")?.optString("human_takeover"))
            assertFalse(privacy.optJSONObject("data")?.has("image_base64") == true)
            assertFalse(privacy.optJSONObject("data")?.has("visual_frame") == true)
            evidence.put("login_screenshot_blocked", true)
            instrumentation.runOnMainSync { login.finish() }
            awaitCondition(3000, "Login window must close") { !LoginAssist.settingsVisible }

            stage = "withdraw_preview_consent"
            check(FirstUseConsent.revoke(context))
            awaitCondition(5000, "Withdrawing consent must invalidate the capture session") { !LegacyScreenCaptureService.isReady }
            val withdrawn = runCatching { capture() }
            withdrawn.getOrNull()?.recycle()
            assertTrue("Capture after consent withdrawal must fail as unavailable, not time out",
                withdrawn.exceptionOrNull().let { it is IllegalStateException || it is SecurityException })
            evidence.put("consent_withdrawal_cleared_ready", true).put("capture_after_withdrawal_rejected", true)

            stage = "reaccept_without_system_grant"
            check(FirstUseConsent.accept(context))
            assertFalse("Accepting preview notices cannot restore an old screen sharing grant", LegacyScreenCaptureService.isReady)
            val ungranted = runCatching { capture() }
            ungranted.getOrNull()?.recycle()
            assertTrue("Capture after reaccepting notices still requires a new system grant",
                ungranted.exceptionOrNull().let { it is IllegalStateException || it is SecurityException })
            assertFalse("Rejected capture cannot resurrect the withdrawn session", LegacyScreenCaptureService.isReady)
            evidence.put("reaccept_kept_unready", true).put("capture_after_reaccept_rejected", true)

            stage = "fresh_system_grant_after_withdrawal"
            LegacyCaptureConsent.authorize(instrumentation)
            assertTrue("A fresh system authorization must restore capture readiness", LegacyScreenCaptureService.isReady)
            evidence.put("fresh_system_grant_ready", true)

            stage = "stop_session"
            context.stopService(Intent(context, LegacyScreenCaptureService::class.java))
            awaitCondition(5000, "Stopping the service must revoke session readiness") { !LegacyScreenCaptureService.isReady }
            val rejected = runCatching { capture() }
            rejected.getOrNull()?.recycle()
            assertTrue("Capture after stop must fail as unavailable, not time out", rejected.exceptionOrNull().let { it is IllegalStateException || it is SecurityException })
            assertFalse("Rejected capture must not recreate a session", LegacyScreenCaptureService.isReady)
            evidence.put("stop_cleared_ready", true).put("capture_after_stop_rejected", true)
        } catch (error: Throwable) { failure = error }
        finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    evidence.put("cleanup_failure_class", error.javaClass.simpleName)
                    if (failure == null) failure = error
                }
            }
            if (ownsSession) cleanup {
                context.stopService(Intent(context, LegacyScreenCaptureService::class.java))
                awaitCondition(5000, "Cleanup must stop the test capture session") { !LegacyScreenCaptureService.isReady }
            }
            executor.shutdownNow()
            cleanup { instrumentation.runOnMainSync { windows.asReversed().filter { !it.isDestroyed && !it.isFinishing }.forEach { it.finish() } } }
            if (settingsTouched) cleanup {
                val ui = checkNotNull(automation)
                writeSecureSetting(ui, "enabled_accessibility_services", savedServices)
                writeSecureSetting(ui, "accessibility_enabled", savedEnabled)
                evidence.put("accessibility_settings_restored", true)
            }
            if (consentTouched) cleanup {
                restorePreferences(consentPrefs, savedConsent)
                evidence.put("consent_preferences_restored", true)
            }
            evidence.put("outcome", if (failure == null) "passed" else "failed").put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            failure?.let { evidence.put("failure_stage", stage).put("failure_class", it.javaClass.simpleName) }
            File(context.filesDir, "legacy-capture-evidence.json").writeText(evidence.toString(2))
        }
        failure?.let { throw AssertionError("Legacy capture failed at $stage; inspect legacy-capture-evidence.json", it) }
    }

    private fun open(type: Class<out Activity>): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun restorePreferences(prefs: SharedPreferences, before: Map<String, *>) {
        val editor = prefs.edit().clear()
        before.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported stored preference type")
        } }
        check(editor.commit())
        check(prefs.all == before) { "Original consent preferences were not restored" }
    }

    private fun showFixture(fixture: CaptureFixture, number: Int, color: Int) {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            fixture.sequence = number; fixture.fill = color; fixture.contentDescription = "Legacy capture fixture $number"
            fixture.afterDraw = { fixture.postOnAnimation { fixture.postOnAnimation { drawn.countDown() } } }
            fixture.invalidate()
        }
        assertTrue("The new fixture must actually draw", drawn.await(3, TimeUnit.SECONDS))
    }
    private fun writeSecureSetting(ui: UiAutomation, name: String, value: String?) {
        check(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
        if (Build.VERSION.SDK_INT >= 29) {
            ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try { check(Settings.Secure.putString(instrumentation.targetContext.contentResolver, name, value)) }
            finally { ui.dropShellPermissionIdentity() }
        } else {
            check(value.isNullOrEmpty() || value.matches(Regex("""[\p{L}\p{N}_./:$-]+""")))
            val command = if (value.isNullOrEmpty()) "settings delete secure $name" else "settings put secure $name $value"
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { input ->
                val buffer = ByteArray(1024); while (input.read(buffer) != -1) { /* Read to EOF before checking state. */ }
            }
        }
        val actual = Settings.Secure.getString(instrumentation.targetContext.contentResolver, name)
        check(actual == value || value.isNullOrEmpty() && actual.isNullOrEmpty()) { "Secure setting was not applied" }
    }
    private class CaptureFixture(context: Context) : View(context) {
        var fill = Color.BLACK
        var sequence = 0
        var afterDraw: (() -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = resources.displayMetrics.density * 24 }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(fill)
            canvas.drawText("Doppel capture $sequence", width * .12f, height * .24f, paint)
            afterDraw?.also { afterDraw = null }?.invoke()
        }
    }
}

/** Shared by opt-in instrumentation tests. Consent is never reconstructed from a saved result Intent. */
internal object LegacyCaptureConsent {
    fun authorize(instrumentation: Instrumentation, verifyDenial: Boolean = false) {
        check(Build.VERSION.SDK_INT in 26..29)
        if (LegacyScreenCaptureService.isReady) {
            check(!verifyDenial) { "Denial verification needs a fresh session" }
            return
        }
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, LegacyScreenCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            if (verifyDenial) {
                clickAuthorize(instrumentation, activity)
                clickSystemConsent(ui, allow = false)
                awaitCondition(5000, "Rejected consent must return to the explanation page") { authorizeVisible(instrumentation, activity) }
                assertFalse("Denied consent cannot grant capture", LegacyScreenCaptureService.isReady)
            }
            clickAuthorize(instrumentation, activity)
            clickSystemConsent(ui, allow = true)
            awaitCondition(7000, "Accepted consent must create a live capture session") { LegacyScreenCaptureService.isReady }
        } finally {
            instrumentation.runOnMainSync { if (!activity.isDestroyed && !activity.isFinishing) activity.finish() }
        }
    }
    private fun authorizeVisible(instrumentation: Instrumentation, activity: Activity): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = !activity.isDestroyed && !activity.isFinishing && activity.hasWindowFocus() &&
                descendants(activity.window.decorView).any { it.tag == "legacy_capture_authorize" && it.isShown && it.isEnabled }
        }
        return visible
    }
    private fun clickAuthorize(instrumentation: Instrumentation, activity: Activity) {
        awaitCondition(5000, "The screen sharing explanation button must be visible") { authorizeVisible(instrumentation, activity) }
        instrumentation.runOnMainSync {
            val control = descendants(activity.window.decorView).single { it.tag == "legacy_capture_authorize" && it.isShown && it.isEnabled }
            assertEquals("允许屏幕共享", (control as TextView).text.toString())
            assertTrue(control.performClick())
        }
    }
    private fun clickSystemConsent(ui: UiAutomation, allow: Boolean) {
        val packages = setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "android")
        val labels = if (allow) setOf("立即开始", "开始录制", "开始", "允许", "Start now", "Start", "Allow") else setOf("取消", "拒绝", "不允许", "Cancel", "Deny", "Don't allow")
        val buttonId = if (allow) "android:id/button1" else "android:id/button2"
        awaitCondition(15000, "System screen sharing consent controls were not identifiable; inspect the visible permission dialog") {
            val root = ui.rootInActiveWindow ?: return@awaitCondition false
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                nodes.add(node)
                if (depth >= 12) return
                for (index in 0 until minOf(node.childCount, 80)) {
                    if (nodes.size >= 400) break
                    node.getChild(index)?.let { visit(it, depth + 1) }
                }
            }
            try {
                visit(root, 0)
                val text = nodes.mapNotNull { it.text?.toString() }.joinToString(" ")
                val expectedDialog = root.packageName?.toString() in packages && text.contains("Doppel", ignoreCase = true) &&
                    (text.contains("屏幕") || text.contains("screen", ignoreCase = true))
                if (!expectedDialog) false else nodes.firstOrNull {
                    it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        (it.text?.toString() in labels || it.viewIdResourceName == buttonId)
                }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            } finally { nodes.forEach { it.recycle() } }
        }
    }
}

private fun awaitCondition(timeoutMs: Long, reason: String, ready: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    do { if (ready()) return; Thread.sleep(50) } while (SystemClock.elapsedRealtime() < deadline)
    throw AssertionError(reason)
}
private fun descendants(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
}
