package dev.doppel.developer

import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.SdkCompanionService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Dedicated emulator only: temporarily disables Wi-Fi, restoring it in finally. */
class CompanionNetworkDeviceTest {
    @Test fun networkLossStopsServiceAndRequiresExplicitRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        fun wifiReady() = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                connectivity.getLinkProperties(network)?.linkAddresses?.isNotEmpty() == true
        }
        assertNull("Do not interrupt an existing connection", SdkCompanionService.instance)
        assertTrue("Use existing first-use consent", FirstUseConsent.isAccepted(context))
        assertFalse("Unlock test device normally", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertEquals("Test requires initially enabled Wi-Fi", "1", shell("settings get global wifi_on"))
        assertTrue("Wi-Fi must be connected", wifiReady())
        assertFalse("Test requires no alternative Ethernet", connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        })
        var activity: android.app.Activity? = null
        val evidence = JSONObject().put("kind", "android_wifi_lifecycle").put("started_at_ms", System.currentTimeMillis())
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { SdkCompanionService.enable(context) }
            await("Service ready") { SdkCompanionService.instance?.phoneState()?.optBoolean("discovery_ready") == true }
            requireNotNull(SdkCompanionService.instance).openPairing() // Keep a pending invitation, never print it.
            shell("svc wifi disable")
            await("Network loss closes foreground service") { SdkCompanionService.instance == null }
            evidence.put("network_loss_stopped_service", true)
            shell("svc wifi enable")
            await("Wi-Fi restored", ::wifiReady)
            SystemClock.sleep(3500)
            assertNull("Network return cannot restart service", SdkCompanionService.instance)
            evidence.put("no_automatic_restart", true)
            instrumentation.runOnMainSync { SdkCompanionService.enable(context) }
            await("Explicit restart ready") { SdkCompanionService.instance?.phoneState()?.optBoolean("discovery_ready") == true }
            assertTrue("Unfinished invitation invalidated", requireNotNull(SdkCompanionService.instance).phoneState().isNull("pairing"))
            evidence.put("pending_invitation_cleared", true).put("explicit_restart", true).put("passed", true)
        } finally {
            shell("svc wifi enable")
            instrumentation.runOnMainSync { SdkCompanionService.disable(context); activity?.finish() }
            evidence.put("finished_at_ms", System.currentTimeMillis())
            context.getExternalFilesDir(null)?.resolve("companion-network-verification.json")?.writeText(evidence.toString(2))
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(label, condition())
    }
}
