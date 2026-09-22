@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CloudAccountActivity
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.ModelProviders
import dev.doppel.sdk.ServerOperationStore
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.cloud.CloudAccountClient
import dev.doppel.sdk.cloud.CloudConnectionService
import dev.doppel.sdk.cloud.CloudPhoneConnection
import dev.doppel.sdk.cloud.CloudSessionStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in live fixture check. Credentials arrive in an app-private file, never instrumentation arguments/logs. */
class CloudPhoneLiveDeviceTest {
    @Test fun accountUiConnectsAndPhoneExecutesOneExternallySubmittedTask() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val credentialFile = File(app.filesDir, "cloud-relay-fixture-credentials.json")
        assertTrue("Copy the private synthetic fixture credentials before this explicit live test", credentialFile.isFile)
        val credentials = JSONObject(credentialFile.readText())
        credentialFile.delete()
        assertTrue("First-use consent is required", FirstUseConsent.isAccepted(app))
        assertTrue("Retain the user's configured local execution mode", DirectMode.isEnabled(app))
        assertTrue("Configure the actual device model before the paid live test", ModelProviders(app).isReady())
        assertFalse("Never replace an unfinished user task", DirectRuntime.get(app).hasUnfinishedRun())
        assertNull("Never replace an existing cloud account", CloudSessionStore(app).load())
        inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val activity = inst.startActivitySync(Intent(app, CloudAccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CloudAccountActivity
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        inst.runOnMainSync {
            val all = views(activity.window.decorView)
            fun field(tag: String) = all.filterIsInstance<EditText>().single { it.tag == tag }
            field("cloud_server").setText("http://127.0.0.1:18765")
            field("cloud_account").setText(credentials.getString("account_name"))
            field("cloud_password").setText(credentials.getString("password"))
            all.filterIsInstance<TextView>().single { it.text.toString() == "登录并连接" }.performClick()
        }
        fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return true; Thread.sleep(250) }
            return condition()
        }
        assertTrue("Account UI did not connect: ${CloudConnectionService.state}", waitUntil(25000) { CloudConnectionService.state == "online" })
        val session = CloudSessionStore(app).load()!!
        var testFailure: Throwable? = null
        try {
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
            assertTrue("Accessibility must be enabled for real device actions", waitUntil(10000) { DoppelAccessibilityService.instance != null })
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            inst.sendStatus(0, Bundle().apply { putString("stream", "PHONE_READY: real account UI login and WebSocket connected\n") })
            File(app.filesDir, "cloud-relay-live-ready.json").writeText(JSONObject().put("ready", true).put("session_id", session.sessionId).toString())
            var run: JSONObject? = null
            var stalledSince = 0L
            val finished = waitUntil(240000) {
                run = DirectRuntime.get(app).serverTask(session.accountId, session.sessionId)
                val status = run?.optString("status")
                if (status in setOf("paused", "awaiting_input", "awaiting_approval")) {
                    if (stalledSince == 0L) stalledSince = SystemClock.elapsedRealtime()
                } else stalledSince = 0L
                status in setOf("completed", "failed", "cancelled") ||
                    stalledSince > 0 && SystemClock.elapsedRealtime() - stalledSince > 15_000
            }
            val result = JSONObject().put("finished", finished).put("status", run?.optString("status") ?: JSONObject.NULL)
                .put("task_id", run?.optString("id") ?: JSONObject.NULL).put("calls", run?.optInt("calls") ?: 0)
                .put("execution_mode", run?.optString("execution_mode") ?: JSONObject.NULL)
                .put("foreground_package", DoppelAccessibilityService.instance?.foregroundPackage() ?: JSONObject.NULL)
                .put("prompt_tokens", run?.optLong("prompt_tokens") ?: 0).put("completion_tokens", run?.optLong("completion_tokens") ?: 0)
            File(app.filesDir, "cloud-relay-live-result.json").writeText(result.toString())
            assertTrue("No externally submitted task finished within the live test window", finished)
            assertEquals("The real local model task did not complete", "completed", run?.optString("status"))
            assertTrue("This must execute the real model path", (run?.optInt("calls") ?: 0) > 0)
            assertEquals("com.android.settings", DoppelAccessibilityService.instance?.foregroundPackage())
            val terminalAcknowledged = ServerOperationStore(app).use { ledger ->
                waitUntil(15000) {
                    val record = ledger.recordsForSession(session.accountId, session.sessionId)
                        .singleOrNull { it.optString("kind") == "create_task" && it.optString("task_id") == run?.optString("id") }
                    val receipt = record?.optJSONObject("latest_receipt")
                    receipt?.optString("phase") == "completed" && ledger.pendingReceipts(session.accountId, session.sessionId)
                        .none { it.optString("operation_id") == record.optString("operation_id") }
                }
            }
            result.put("terminal_receipt_acknowledged", terminalAcknowledged)
            File(app.filesDir, "cloud-relay-live-result.json").writeText(result.toString())
            assertTrue("Do not log out until the relay durably acknowledges the terminal receipt", terminalAcknowledged)
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            val cleanupReport = JSONObject()
            val cleanup = runCatching {
                cleanupReport.put("logout_confirmed", runCatching { CloudAccountClient(app).logout(session) }.isSuccess)
                CloudConnectionService.clear(app, session.sessionId)
                // signed_out can precede the delayed invalidation ACK; wait for the existing stop owner too.
                val connection = CloudConnectionService::class.java.getDeclaredField("sharedConnection")
                    .apply { isAccessible = true }.get(null) as CloudPhoneConnection
                var remaining = -1
                var cleared = false
                val settled = waitUntil(40000) {
                    remaining = DirectRuntime.get(app).serverRuns(session.accountId, session.sessionId)
                        .count { it.optString("status") !in setOf("completed", "failed", "cancelled") }
                    cleared = runCatching { CloudSessionStore(app).load() == null }.getOrDefault(false)
                    remaining == 0 && CloudConnectionService.state in setOf("signed_out", "connection_closed", "stop_unconfirmed", "storage_unavailable") &&
                        (!connection.isStopping || CloudConnectionService.state == "stop_unconfirmed")
                }
                cleanupReport.put("settled", settled).put("remaining_tasks", remaining).put("session_cleared", cleared)
                    .put("connection_state", CloudConnectionService.state).put("stop_pending", connection.isStopping)
                check(settled && cleared && !connection.isStopping && CloudConnectionService.state in setOf("signed_out", "connection_closed")) {
                    "Live test cleanup did not confirm task and connection stop"
                }
            }
            val failure = cleanup.exceptionOrNull()
            cleanupReport.put("ok", failure == null).put("error_type", failure?.javaClass?.simpleName ?: JSONObject.NULL)
            val evidence = runCatching { File(app.filesDir, "cloud-relay-live-cleanup.json").writeText(cleanupReport.toString()) }
            val teardown = runCatching {
                File(app.filesDir, "cloud-relay-live-ready.json").delete()
                inst.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            }
            val failures = listOfNotNull(failure, evidence.exceptionOrNull(), teardown.exceptionOrNull())
            failures.forEach { testFailure?.addSuppressed(it) }
            if (testFailure == null && failures.isNotEmpty()) {
                failures.drop(1).forEach(failures.first()::addSuppressed)
                throw failures.first()
            }
        }
    }
}
