@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.LoginAssist
import dev.doppel.sdk.LoginNotificationService
import dev.doppel.sdk.LoginProfile
import dev.doppel.sdk.TaskControl
import dev.doppel.sdk.TaskSubmissionGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real listener + native input + production worker/ledger. Loopback serves only a disposable run;
 * fixture notifications are NOT SMS and use the debug build's explicit test-app source.
 */
class NativeLoginCodeDeviceTest {
    @get:org.junit.Rule val terminalTaskPointer: org.junit.rules.TestRule = TerminalTaskPointerTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val fixture = "dev.doppel.testapp"
    private val secrets = listOf("19900000013", "246810", "135790", "975318", "864209")
    private val service get() = requireNotNull(DoppelAccessibilityService.instance)

    @Test fun realWorkerUsesOnlyFreshRunBoundCodeAndMasksNativeInput() {
        assertNull("Never replace an existing worker", DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        assertFalse("Never interrupt an existing login session", LoginAssist.sensitiveSessionActive())
        assertFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(android.provider.Settings.canDrawOverlays(context))
        assertTrue("Do not migrate the owner's gateway", !context.getSharedPreferences("doppel", 0).getBoolean("artemis_mode", false))
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        context.packageManager.getPackageInfo(fixture, 0)
        assertTrue("Other task creation must finish first", TaskSubmissionGate.creating.compareAndSet(false, true))
        try {
        assertNull("No worker may appear while acquiring the fixture lease", DeviceWorkerService.instance)
        assertTrue(context.getSharedPreferences("doppel", 0).getString("active_run", "").isNullOrBlank())
        val names = listOf("doppel", "doppel_login", "doppel_consent")
        val saved = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val beforeFiles = protectedFiles()
        val alias = "${context.packageName}.login.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hadAlias = keyStore.containsAlias(alias)
        val hadPost = Build.VERSION.SDK_INT < 33 || context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, fixture) == PackageManager.PERMISSION_GRANTED
        val folder = File(context.getExternalFilesDir(null), "native-login-code/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val checks = JSONObject()
        val report = JSONObject().put("passed", false).put("model_requests", 0).put("fixture_is_sms", false)
            .put("scope", "Real Android NotificationListener, production Worker command dispatch and native login fields; localhost run only")
            .put("checks", checks)
        val gateway = LocalGateway()
        var listener: AutoCloseable? = null
        var ownedWorker: DeviceWorkerService? = null
        var workerRequested = false
        var fixtureOpened = false
        var passed = false
        var mainFailure: Throwable? = null
        var stage = "prepare"
        val commandEvidence = JSONArray()
        report.put("native_receipts", commandEvidence)
        try {
            listener = NotificationReadTestBinding.connect(inst)
            if (!hadPost) automation.grantRuntimePermission(fixture, Manifest.permission.POST_NOTIFICATIONS)
            val login = LoginAssist(context)
            // The encrypted original is held only in test memory and restored after the worker stops.
            login.clearAll()
            login.save(LoginProfile(fixture, secrets[0], "DoppelFixture", true))
            context.startActivity(Intent().setClassName(fixture, "$fixture.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("验证码通知验证")
            fixtureOpened = true
            click("清理测试通知")
            postAndAwait("发送旧测试验证码", "135790")
            SystemClock.sleep(150) // The pre-session notification must have an earlier real postTime.

            val prefs = context.getSharedPreferences("doppel", 0)
            check(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:${gateway.port}")
                .putString("token", "native-login-local-fixture").putString("device_id", gateway.device)
                .putString("active_run", gateway.runId).putBoolean("touch_pause", false).putBoolean("completion_speech", false).commit())
            check(FirstUseConsent.accept(context))
            workerRequested = true
            assertTrue(TaskControl.startWorker(context))
            await("The fixture worker service must start") { DeviceWorkerService.instance != null }
            ownedWorker = DeviceWorkerService.instance
            await("The actual worker must bind this running loopback task") {
                DeviceWorkerService.instance?.allowsCredentialInput(gateway.runId) == true
            }
            fun command(kind: String): JSONObject {
                stage = "command_$kind"
                assertTrue("Use the same live worker for every native action", ownedWorker === DeviceWorkerService.instance)
                val field = find { it.isEditable && it.contentDescription?.toString() ==
                    if (kind == "login_phone") "测试手机号输入框" else "测试验证码输入框" }
                try { field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
                finally { field.recycle() }
                inst.waitForIdleSync(); SystemClock.sleep(400)
                val observation = service.observe()
                assertEquals(fixture, observation.optString("package_name"))
                assertPrivateFree(observation)
                val id = UUID.randomUUID().toString()
                gateway.commands.put(JSONObject().put("id", id).put("run_id", gateway.runId).put("kind", kind)
                    .put("split_agent", true).put("package_name", fixture).put("screen_id", observation.getString("screen_id")))
                await("Worker must acknowledge $kind through the loopback result endpoint", 15_000) { gateway.receipts.containsKey(id) }
                return gateway.receipts.getValue(id).also { receipt ->
                    commandEvidence.put(JSONObject().put("kind", kind).put("status", receipt.optString("status"))
                        .put("message", sanitized(receipt.optString("message")))
                        .put("action_state", receipt.optJSONObject("data")?.optString("action_state").orEmpty()))
                    assertPrivateFree(receipt)
                }
            }
            fun state() = login.taskStatus(fixture, gateway.runId).also(::assertPrivateFree)
            val phone = command("login_phone")
            assertEquals("Phone must be filled through the actual worker gate", "ok", phone.optString("status"))
            assertTrue("Native phone field must contain only the fixture phone", fieldValue("测试手机号输入框") == secrets[0])
            checks.put("real_worker_phone_input", true)
            assertEquals("waiting", state().getString("code_state"))
            assertEquals("inactive", login.taskStatus(fixture, "different-fixture-run").getString("code_state"))
            val old = command("login_code")
            assertEquals("ok", old.optString("status"))
            assertEquals("waiting_for_code", old.getJSONObject("data").getString("action_state"))
            assertTrue(fieldValue("测试验证码输入框").isBlank())
            checks.put("real_worker_phone_input", true).put("old_code_rejected", true).put("other_run_inactive", true)

            stage = "wrong_service_notification"
            postAndAwait("发送错误服务验证码", "975318", confirmRejection = true)
            assertEquals("Wrong service marker cannot become ready", "waiting", state().getString("code_state"))
            val wrong = command("login_code")
            assertEquals("waiting_for_code", wrong.getJSONObject("data").getString("action_state"))
            postAndAwait("发送支付测试验证码", "864209", confirmRejection = true)
            assertEquals("Payment notification is not a login code", "waiting", state().getString("code_state"))
            checks.put("wrong_service_rejected", true).put("payment_code_rejected", true)
                .put("rejected_real_notifications_replayed_through_listener_for_deterministic_assertion", true)

            stage = "fresh_notification"
            postAndAwait("发送登录测试验证码", "246810")
            await("The real notification callback must mark the fresh session code ready") { state().optBoolean("code_ready") }
            assertTrue("Readiness observations must not consume the code", state().optBoolean("code_ready"))
            val filled = command("login_code")
            assertEquals("ok", filled.optString("status"))
            assertTrue("Native OTP input must equal the fixture code", fieldValue("测试验证码输入框") == secrets[1])
            assertEquals("consumed", state().getString("code_state"))
            assertFalse(state().getBoolean("code_ready"))
            click("验证登录测试输入"); expect("登录测试输入匹配")
            stage = "masked_screenshot"
            captureMasked(folder)
            val repeated = command("login_code")
            assertEquals("error", repeated.optString("status"))
            assertEquals("not_dispatched", repeated.getJSONObject("data").getString("action_state"))
            assertTrue("A consumed code must not alter the native field", fieldValue("测试验证码输入框") == secrets[1])
            assertTrue("Waiting/error result must not pause a live task", ownedWorker?.isPaused == false)
            checks.put("fresh_notification_ready", true).put("native_otp_verified_by_fixture", true)
                .put("one_use_enforced", true).put("receipts_and_observations_redacted", true).put("local_screenshot_fields_masked", true)
            assertEquals("No paid model or task creation endpoint exists in this test", 0, gateway.unexpectedRequests)
            passed = true
        } catch (error: Throwable) {
            mainFailure = error
            report.put("main_failure_type", error.javaClass.simpleName).put("main_failure_message", sanitized(error.message.orEmpty()))
                .put("failure_stage", stage)
            runCatching { File(folder, "report.json").writeText(report.toString(2)) }.onFailure { error.addSuppressed(it) }
            throw error
        } finally {
            var failure: Throwable? = null
            fun cleanup(work: () -> Unit) { try { work() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            var quiesced = false
            cleanup {
                val current = DeviceWorkerService.instance
                check(current == null || workerRequested && (ownedWorker == null || current === ownedWorker)) {
                    "Do not stop a worker that this fixture did not start"
                }
                if (workerRequested) context.stopService(Intent(context, DeviceWorkerService::class.java))
                await("Only the fixture worker must finish before restoring the owner's real connection") { DeviceWorkerService.instance == null }
                ownedWorker?.let { worker ->
                    val executor = DeviceWorkerService::class.java.getDeclaredField("executor").apply { isAccessible = true }
                        .get(worker) as java.util.concurrent.ExecutorService
                    assertTrue("Wait for the loopback worker to close its ledger", executor.awaitTermination(8, TimeUnit.SECONDS))
                }
                quiesced = true
            }
            cleanup { if (fixtureOpened) { click("清理测试通知"); click("返回场景") } }
            cleanup { LoginAssist.clearSession(); listener?.close() }
            cleanup { if (!hadPost) automation.revokeRuntimePermission(fixture, Manifest.permission.POST_NOTIFICATIONS) }
            cleanup {
                check(quiesced) { "Keep loopback credentials if worker cleanup did not finish" }
                if (gateway.commandIds.isNotEmpty()) context.openOrCreateDatabase("command_results.db", Context.MODE_PRIVATE, null).use { database ->
                    gateway.commandIds.forEach { database.delete("results", "id=?", arrayOf(it)) }
                }
                names.forEach { restore(context.getSharedPreferences(it, 0), saved.getValue(it)) }
                if (!hadAlias && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
            cleanup { gateway.close() }
            val preserved = names.all { saved.getValue(it) == context.getSharedPreferences(it, 0).all } && beforeFiles == protectedFiles()
            report.put("passed", passed && failure == null && preserved).put("original_state_preserved", preserved)
                .put("worker_quiesced", quiesced).put("native_command_count", gateway.commandIds.size)
            failure?.let { report.put("cleanup_error_type", it.javaClass.simpleName).put("cleanup_error_message", sanitized(it.message.orEmpty())) }
            cleanup { assertTrue("Preserve all owner credentials, model setup and task/chat history exactly", preserved) }
            cleanup { File(folder, "report.json").writeText(report.toString(2)) }
            cleanup { inst.sendStatus(0, Bundle().apply { putString("native_login_code_report", folder.absolutePath) }) }
            failure?.let { cleanupError -> if (mainFailure != null) mainFailure!!.addSuppressed(cleanupError) else throw cleanupError }
        }
        } finally { TaskSubmissionGate.creating.set(false) }
    }

    private fun sanitized(message: String): String {
        var safe = message
        secrets.forEach { safe = safe.replace(it, "[private]") }
        return safe.replace(Regex("(?i)(?:sk-|bearer\\s+)[A-Za-z0-9._-]+"), "[credential]")
            .replace(Regex("[A-Za-z0-9+/=_-]{40,}"), "[opaque]")
            .replace(Regex("(?<![A-Za-z])[0-9]{4,}(?![A-Za-z])"), "[digits]").take(400)
    }

    private fun assertPrivateFree(value: JSONObject) {
        val text = value.toString()
        assertTrue("Observation/receipt may not expose a phone or verification code", secrets.none(text::contains))
    }
    private fun postAndAwait(label: String, code: String, confirmRejection: Boolean = false) {
        val before = System.currentTimeMillis()
        click(label)
        var received: android.service.notification.StatusBarNotification? = null
        await("The actual listener must see the newly posted fixture notification") {
            received = LoginNotificationService.connected?.activeNotifications?.firstOrNull { it.packageName == fixture && it.postTime >= before &&
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains(code) == true }
            received != null
        }
        // The accepted-code case must arrive through Android's asynchronous callback unaided.
        // For negative cases also replay the actual posted object, so "not ready" cannot be
        // a false pass merely because the listener callback had not run yet.
        if (confirmRejection) inst.runOnMainSync { requireNotNull(LoginNotificationService.connected).onNotificationPosted(requireNotNull(received)) }
    }
    private fun fieldValue(description: String): String {
        val field = find { it.contentDescription?.toString() == description && it.isEditable }
        return try { if (field.isShowingHintText) "" else field.text?.toString().orEmpty() } finally { field.recycle() }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("The native login fixture must expose its expected control") {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun collect(node: AccessibilityNodeInfo) {
                nodes += node
                if (nodes.size >= 300) return
                for (i in 0 until node.childCount) { if (nodes.size >= 300) break; node.getChild(i)?.let(::collect) }
            }
            service.activeRoot()?.let(::collect)
            found = nodes.firstOrNull { it.packageName?.toString() == fixture && predicate(it) }
            nodes.filter { it !== found }.forEach { it.recycle() }
            found != null
        }
        return requireNotNull(found)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = find { it.text?.toString() == label }
        while (node != null) {
            val current = node
            current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (current.isClickable) {
                try { assertTrue("Fixture button must accept native click", current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { current.recycle() }
                inst.waitForIdleSync(); return
            }
            node = current.parent; current.recycle()
        }
        error("Fixture control is not clickable")
    }
    private fun expect(label: String) { find { it.text?.toString() == label }.recycle() }
    private fun await(message: String, millis: Long = 8_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + millis
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, condition())
    }
    private fun captureMasked(folder: File) {
        val phone = find { it.isEditable && it.contentDescription?.toString() == "测试手机号输入框" }
        try { phone.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } finally { phone.recycle() }
        inst.waitForIdleSync(); SystemClock.sleep(500)
        val observation = service.observe(); assertPrivateFree(observation)
        val capture = service.execute(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", "native-login-mask-fixture")
            .put("kind", "screenshot").put("split_agent", true).put("mode", "full"))
        assertEquals("Login screenshot should retain the normal observation loop", "ok", capture.optString("status"))
        val data = capture.getJSONObject("data")
        assertTrue(data.optInt("privacy_mask_count") >= 2)
        val bytes = android.util.Base64.decode(data.getString("image_base64"), android.util.Base64.NO_WRAP)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val frame = data.getJSONObject("visual_frame")
            for (label in listOf("测试手机号输入框", "测试验证码输入框")) {
                val field = find { it.isEditable && it.contentDescription?.toString() == label }
                val bounds = Rect()
                try { assertTrue("Both fields must actually be visible in the captured viewport", field.isVisibleToUser); field.getBoundsInScreen(bounds) }
                finally { field.recycle() }
                val left = (bounds.left.toLong() * bitmap.width / frame.getInt("display_width")).toInt() + 2
                val right = (bounds.right.toLong() * bitmap.width / frame.getInt("display_width")).toInt() - 2
                val top = (bounds.top.toLong() * bitmap.height / frame.getInt("display_height")).toInt() + 2
                val bottom = (bounds.bottom.toLong() * bitmap.height / frame.getInt("display_height")).toInt() - 2
                assertTrue("A clipped/off-screen field must not pass by sampling a clamped edge pixel",
                    left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height && right > left && bottom > top)
                var covered = true
                for (y in top until bottom) for (x in left until right) if (bitmap.getPixel(x, y) != 0xff333333.toInt()) covered = false
                assertTrue("The entire visible input interior must be masked, including every text pixel", covered)
            }
            File(folder, "masked-login-fields.png").writeBytes(bytes)
        } finally { bitmap.recycle() }
    }
    private fun protectedFiles() = listOf("credential-vault-v1.bin", "model-providers-v1.bin", "automatic-unlock-v1.bin", "direct-runs-v1.json")
        .associateWith { name -> File(context.noBackupFilesDir, name).let { if (it.exists()) MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() else null } }
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }

    /** Finite loopback worker API only; receipts never leave this process or get written to the report. */
    private class LocalGateway : AutoCloseable {
        val device = "native-login-fixture"
        val runId = UUID.randomUUID().toString()
        val commands = LinkedBlockingQueue<JSONObject>()
        val receipts = ConcurrentHashMap<String, JSONObject>()
        val commandIds = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        @Volatile var unexpectedRequests = 0
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        @Volatile private var open = true
        private val thread = Thread({
            while (open) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String {
                        val value = StringBuilder()
                        while (value.length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) value.append(c.toChar()) }
                        return value.toString()
                    }
                    val first = line().split(' '); check(first.size >= 2)
                    val method = first[0]; val path = first[1].substringBefore('?')
                    var size = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    check(size in 0..4 * 1024 * 1024)
                    val bytes = ByteArray(size); var offset = 0
                    while (offset < size) { val n = input.read(bytes, offset, size - offset); check(n > 0); offset += n }
                    val response = when {
                        method == "GET" && path == "/v1/runs/$runId" -> JSONObject().put("id", runId).put("device_id", device)
                            .put("goal", "本机登录验证码隔离测试").put("status", "running").put("conversation_enabled", false).put("events", JSONArray())
                        method == "GET" && path == "/v1/devices/$device/commands" -> {
                            val next = commands.poll(150, TimeUnit.MILLISECONDS)
                            next?.let { commandIds += it.getString("id") }
                            JSONObject().put("command", next ?: JSONObject.NULL)
                        }
                        method == "POST" && path == "/v1/devices/$device/results" -> {
                            val receipt = JSONObject(String(bytes, Charsets.UTF_8))
                            receipts[receipt.getString("command_id")] = receipt
                            JSONObject()
                        }
                        method == "GET" && path == "/v1/devices/$device/data-cleanup" -> JSONObject().put("items", JSONArray())
                        else -> { unexpectedRequests++; JSONObject() }
                    }
                    val output = response.toString().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 Fixture\r\nContent-Type: application/json\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(output); flush()
                    }
                }
            } catch (_: Exception) { if (!open) break }
        }, "native-login-loopback").apply { isDaemon = true; start() }
        override fun close() { open = false; socket.close(); thread.join(1500) }
    }
}
