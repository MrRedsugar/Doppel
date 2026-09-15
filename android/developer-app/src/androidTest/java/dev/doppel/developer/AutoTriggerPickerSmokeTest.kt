package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AutoTriggerSettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emulator smoke test for the user-facing automatic-trigger picker entry. */
class AutoTriggerPickerSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun settingsExposeAccessibilityPickerEntry() {
        val activity = instrumentation.startActivitySync(
            Intent(context, AutoTriggerSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("picker entry missing", labels.contains("从当前应用选择控件"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
