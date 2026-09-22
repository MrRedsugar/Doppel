package dev.doppel.developer

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.PcConnectionActivity
import dev.doppel.sdk.SdkCompanionService
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual phone page, including an invitation with no PC request yet. */
class CompanionSettingsDeviceTest {
    @Test fun pairingPageStartsServiceAndCancelsInvitationWhenLeaving() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNull("Do not interrupt an existing connection", SdkCompanionService.instance)
        val activity = instrumentation.startActivitySync(Intent(context, PcConnectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PcConnectionActivity
        fun text(label: String): TextView? {
            fun find(view: View): TextView? {
                if (view is TextView && view.text.toString() == label) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            var found: TextView? = null
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) found = find(activity.window.decorView)
            else instrumentation.runOnMainSync { found = find(activity.window.decorView) }
            return found
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(label, condition())
        }
        try {
            assertNotNull(text("允许 PC 连接"))
            instrumentation.runOnMainSync { requireNotNull(text("允许 PC 连接")).performClick() }
            await("Real service starts") { SdkCompanionService.instance?.phoneState()?.optBoolean("service_enabled") == true }
            await("Page offers pairing") { text("配对电脑") != null }
            instrumentation.runOnMainSync { requireNotNull(text("配对电脑")).performClick() }
            assertNotNull("Initial pending without request must show link", text("复制配对链接"))
            assertNull("No PC has requested access yet", text("允许连接"))
            assertNotNull(SdkCompanionService.instance?.phoneState()?.optJSONObject("pairing"))
            instrumentation.runOnMainSync { activity.finish() }
            await("Leaving clears unactivated invitation") { SdkCompanionService.instance?.phoneState()?.isNull("pairing") == true }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); SdkCompanionService.disable(context) }
        }
    }
}
