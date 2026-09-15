package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AccessibilityControlPicker
import dev.doppel.sdk.AutoTriggerSettingsActivity
import dev.doppel.sdk.DoppelAccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device flow for the non-exported settings activity's control-picker entry.
 *
 * The activity is started from instrumentation (same application UID), so the
 * production manifest can keep exported=false. The picker must then leave the
 * settings screen and put its "读取控件" affordance on the launcher.
 */
class AutoTriggerPickerFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else emptyList()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { String(it.readBytes(), Charsets.UTF_8) }

    private fun foregroundPackage(): String {
        val dump = shell("dumpsys window windows")
        val match = Regex("mCurrentFocus=Window\\{[^}]* u0 ([^/} ]+)").find(dump)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun launcherVisible(): Boolean {
        // Android 14 may leave mCurrentFocus empty while an accessibility
        // overlay is the input target. The accessibility window list still
        // reports the launcher application that is underneath that overlay.
        if (foregroundPackage() == "com.android.launcher3") return true
        // Window focus can belong to the accessibility overlay (or briefly be
        // null during the HOME transition). ActivityTaskManager is authoritative
        // for the resumed task in that interval.
        val activities = shell("dumpsys activity activities")
        if (Regex("mResumedActivity:.*com\\.android\\.launcher3/").containsMatchIn(activities) ||
            Regex("mFocusedApp=.*com\\.android\\.launcher3/").containsMatchIn(activities)) return true
        return instrumentation.uiAutomation.windows.any { window ->
            runCatching {
                (window.isActive || window.isFocused) &&
                    window.root?.packageName?.toString() == "com.android.launcher3"
            }
                .getOrDefault(false)
        }
    }

    private fun await(message: String, timeoutMs: Long = 6000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue(message, predicate())
    }

    @Test
    fun pickerEntryStartsOverlayAndReturnsToLauncher() {
        // This is a real-device flow and requires the production service grant.
        // Rebind only our service if instrumentation restarted its process.
        if (DoppelAccessibilityService.instance == null) {
            AccessibilityServiceTestBinding.rebindAlreadyEnabled(instrumentation)
        }
        assertTrue("Doppel accessibility service is not bound", DoppelAccessibilityService.instance != null)

        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, AutoTriggerSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val entry = requireNotNull(
                views(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .firstOrNull { it.text?.toString() == "从当前应用选择控件" }
            ) { "Picker entry is missing from automatic-trigger settings" }

            instrumentation.runOnMainSync { assertTrue("Picker entry is disabled", entry.isEnabled); entry.performClick() }

            // begin() intentionally performs HOME before adding its accessibility
            // overlay. Waiting for launcher focus proves this is the production
            // route rather than merely constructing the settings UI.
            await("Picker did not return to launcher", 8000) { launcherVisible() }
        } finally {
            AccessibilityControlPicker.stop()
            activity?.let { owner -> instrumentation.runOnMainSync { owner.finish() } }
            // Give WindowManager one turn to remove the accessibility overlay.
            SystemClock.sleep(200)
        }
    }
}
