@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.SdkCompanionService
import dev.doppel.sdk.companion.CompanionEndpoint
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/** Test APK only: real Windows peer owns TLS requests; secrets never enter instrumentation output. */
class CompanionPcInteropTest {
    @Test fun windowsPairSnapshotAndRevocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNull("Do not interrupt an existing connection", SdkCompanionService.instance)
        assertTrue("Complete normal first use before testing", FirstUseConsent.isAccepted(context))
        assertFalse("Unlock test device normally", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val invitation = context.cacheDir.resolve("pc-interop-invitation.txt")
        var activity: android.app.Activity? = null
        var pairId: String? = null
        var relay: ServerSocket? = null
        val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { SdkCompanionService.enable(context) }
            await("Service ready", 20_000) { SdkCompanionService.instance?.phoneState()?.optBoolean("discovery_ready") == true }
            val service = requireNotNull(SdkCompanionService.instance)
            val uri = service.openPairing()
            val payload = JSONObject(String(Base64.getUrlDecoder().decode(URI(uri).rawFragment), Charsets.UTF_8))
            val target = URI(payload.getString("endpoint"))
            val bridge = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            relay = bridge
            thread(isDaemon = true, name = "pc-test-relay") {
                while (!bridge.isClosed) {
                    val incoming = runCatching { bridge.accept() }.getOrNull() ?: break
                    sockets.add(incoming)
                    thread(isDaemon = true) {
                        runCatching {
                            val outgoing = Socket(target.host, target.port)
                            sockets.add(outgoing)
                            thread(isDaemon = true) { runCatching { incoming.getInputStream().copyTo(outgoing.getOutputStream()) }; runCatching { outgoing.shutdownOutput() } }
                            outgoing.use { incoming.use { outgoing.getInputStream().copyTo(incoming.getOutputStream()) } }
                        }
                    }
                }
            }
            invitation.writeText(JSONObject().put("invite", uri).put("relay_port", bridge.localPort).toString())
            await("Windows requested test pairing", 120_000) {
                service.phoneState().optJSONObject("pairing")?.let {
                    it.optString("client_name") == "Doppel-PC-interop-test" && it.optString("pairing_request_id").isNotBlank()
                } == true
            }
            invitation.delete()
            val request = service.phoneState().getJSONObject("pairing").getString("pairing_request_id")
            service.decidePairing(request, true, setOf("state"))
            await("Windows activated test pairing", 30_000) {
                val pairs = service.phoneState().getJSONArray("pairs")
                for (i in 0 until pairs.length()) {
                    val pair = pairs.getJSONObject(i)
                    if (pair.optString("client_name") == "Doppel-PC-interop-test") pairId = pair.getString("pair_id")
                }
                pairId != null
            }
            SystemClock.sleep(5_000)
            service.revokePair(requireNotNull(pairId))
            SystemClock.sleep(5_000) // Give the real Windows client time to observe 401.
        } finally {
            invitation.delete()
            runCatching { relay?.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            pairId?.let { runCatching { CompanionEndpoint.revokeLocalPair(context, it) } }
            SdkCompanionService.instance?.cancelPairing()
            instrumentation.runOnMainSync { SdkCompanionService.disable(context); activity?.finish() }
        }
    }
    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(label, condition())
    }
}
