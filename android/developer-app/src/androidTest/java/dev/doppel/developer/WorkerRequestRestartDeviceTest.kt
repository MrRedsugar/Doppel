@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")
package dev.doppel.developer

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Host: seed(A/B) -> wait for worker_restart_ready -> force-stop host -> verify -> cleanup.
 * Real provider transport, DirectRuntime, Worker, ledger, screenshots and target MotionEvents.
 * Fixed localhost replies only. All owner backups stay in noBackupFilesDir, never external/logs.
 * Seed deliberately stays alive for at most 45 seconds after READY; killing instrumentation is expected.
 */
class WorkerRequestRestartDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val ui by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val root get() = File(context.noBackupFilesDir, "qa-worker-request-restart-v1")
    private val manifest get() = File(root, "manifest.json")
    private val fixture = "dev.doppel.testapp"
    private val alias get() = "${context.packageName}.model.providers.v1"
    private val prefNames = listOf("doppel", "doppel_consent", "doppel_gui_grounding", "command_results", "doppel_schedule_wakeup")
    private val privatePaths = listOf("direct-runs-v1.json", "direct-runs-v1.json.bak", "direct-runs-v1.json.new",
        "model-providers-v1.bin", "model-providers-v1.bin.bak", "model-providers-v1.bin.new",
        "model-usage-v1.json", "model-usage-v1.json.part", "direct-screenshots-v1")
    private val databasePaths = listOf("command_results.db", "command_results.db-wal", "command_results.db-shm", "command_results.db-journal")
    private fun runtimeField() = DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private fun meta() = JSONObject(manifest.readText())
    private fun atomic(file: File, body: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(body.toString(2).toByteArray()); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }
    private fun await(message: String, ms: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        do { if (condition()) return; SystemClock.sleep(80) } while (SystemClock.elapsedRealtime() < until)
        assertTrue(message, condition())
    }
    private fun evidence(stage: String, body: JSONObject) {
        val role = meta().getString("role")
        atomic(File(context.getExternalFilesDir(null), "worker-request-restart/$role/$stage.json"),
            body.put("stage", stage).put("role", role).put("pid", Process.myPid()).put("paid_requests", 0))
    }
    private fun requireOptIn() {
        assumeTrue("Use -e worker_restart true -e emulatorOnly true", args.getString("worker_restart") == "true")
        assertEquals("true", args.getString("emulatorOnly"))
        val emulator = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("getprop ro.boot.qemu"))
            .use { String(it.readBytes()).trim() }
        assertTrue("This destructive process test is emulator-only", emulator == "1" || android.os.Build.MODEL.contains("sdk"))
    }

    @Test fun seed() {
        requireOptIn()
        val role = requireNotNull(args.getString("worker_restart_role")).also { require(it in setOf("A", "B")) }
        assertFalse("Recover the previous private fixture before starting another", manifest.exists())
        assertNull("Start a fresh host process before seed", runtimeField().get(null))
        assertNull("Never replace an existing Worker", DeviceWorkerService.instance)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        assertTrue(Settings.canDrawOverlays(context)); assertTrue(DirectMode.available(context))
        val prefs = context.getSharedPreferences("doppel", 0)
        assertFalse(prefs.getBoolean("artemis_mode", false))
        assertTrue(prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
        assertFalse(TaskSubmissionGate.creating.get())
        val rows = File(context.noBackupFilesDir, "direct-runs-v1.json").takeIf(File::exists)?.readText()?.let { dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(it) } ?: JSONArray()
        val terminal = setOf("completed", "failed", "cancelled")
        repeat(rows.length()) { assertTrue("Leave all unfinished tasks untouched", rows.getJSONObject(it).optString("status") in terminal) }
        val active = prefs.getString("active_run", "").orEmpty()
        assertTrue("Only a known terminal pointer may be temporarily isolated", active.isBlank() ||
            (0 until rows.length()).any { rows.getJSONObject(it).optString("id") == active })
        assertTrue("Disable automatic rules before isolated acceptance", AutoTriggerStore(context).list().none { it.enabled })
        val schedules = File(context.noBackupFilesDir, "schedules-v1.json").takeIf(File::exists)?.readText()?.let(::JSONObject)?.getJSONArray("items") ?: JSONArray()
        repeat(schedules.length()) {
            val job = schedules.getJSONObject(it)
            assertFalse("Do not race an enabled schedule", job.optBoolean("enabled"))
            val history = job.optJSONArray("history") ?: JSONArray()
            repeat(history.length()) { assertFalse("Do not rewrite an interrupted schedule", history.getJSONObject(it).optString("status") == "dispatching") }
        }
        val session = UUID.randomUUID().toString()
        backup(JSONObject().put("role", role).put("seed_pid", Process.myPid()).put("session", session))
        var server: LocalModel? = null
        try {
            // Isolate the complete run/archive/usage/ledger namespace after the durable backup exists.
            paths().forEach { (_, file) -> eraseChecked(file) }
            check(prefs.edit().putBoolean("direct_mode", true).putString("device_id", DirectRuntime.DEVICE_ID)
                .remove("active_run").putBoolean("touch_pause", false).putBoolean("completion_speech", false).commit())
            check(context.getSharedPreferences("doppel_gui_grounding", 0).edit().putBoolean("enabled", false).commit())
            check(FirstUseConsent.accept(context))
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            openFixture(session)
            assertEquals(0, state(session).getInt("taps"))
            server = LocalModel(role, recovering = false)
            configure(server.port)
            val gateway = Gateway(context)
            val run = gateway.request("POST", "/runs", JSONObject().put("device_id", DirectRuntime.DEVICE_ID)
                .put("goal", "本地请求中断验收：点击独立手势区域").put("mode", "full").put("conversation_enabled", false))
            val id = run.getString("id")
            atomic(manifest, meta().put("run_id", id))
            check(prefs.edit().putString("active_run", id).commit())
            assertTrue(TaskControl.startWorker(context))
            assertTrue("The second selected model request must reach localhost", server.blocked.await(30, TimeUnit.SECONDS))
            server.failure?.let { throw AssertionError("Local fixed responder failed", it) }
            await("The real target must have received exactly one completed tap") { state(session).optInt("taps") == 1 }
            val stored = storedRun(id)
            assertEquals("running", stored.getString("status"))
            assertEquals("ab", stored.getString("execution_mode"))
            assertEquals(1, state(session).getInt("down")); assertEquals(1, state(session).getInt("up"))
            assertTrue("Worker must really be alive while the model socket is pending", DeviceWorkerService.instance?.isPaused == false)
            assertEquals(2, server.primary)
            assertEquals(if (role == "A") 1 else 2, server.grounding)
            val commandIds = ledgerIds()
            assertTrue(commandIds.isNotEmpty())
            atomic(manifest, meta().put("ready", true).put("old_command_ids", JSONArray(commandIds))
                .put("primary_requests", server.primary).put("grounding_requests", server.grounding))
            evidence("seed", JSONObject().put("ready", true).put("run_id", id).put("target_taps", 1)
                .put("worker_live", true).put("primary_requests", server.primary).put("grounding_requests", server.grounding))
            inst.sendStatus(0, Bundle().apply { putString("worker_restart_ready", role); putInt("worker_restart_pid", Process.myPid()) })
            // The host must force-stop NOW, while this exact request still has no response.
            SystemClock.sleep(45000)
            fail("Host did not force-stop within 45 seconds after worker_restart_ready")
        } finally {
            // Not reached on process death. Timeout/setup failure still restores the private backup.
            server?.close()
            restore()
        }
    }

    @Test fun verify() {
        requireOptIn()
        val m = meta()
        assertTrue("Seed did not reach an in-flight request", m.optBoolean("ready"))
        assertNotEquals("A real process death is required", m.getInt("seed_pid"), Process.myPid())
        assertNull(DeviceWorkerService.instance)
        assertNull("Verify must begin before runtime reconstruction", runtimeField().get(null))
        val report = JSONObject().put("passed", false).put("seed_pid", m.getInt("seed_pid"))
        var server: LocalModel? = null
        try {
            val id = m.getString("run_id"); val session = m.getString("session")
            assertEquals("running", storedRun(id).getString("status"))
            if (DoppelAccessibilityService.instance == null) AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
            // Do not recreate/reset the target: its surviving event count detects replay.
            await("The same independent target session must survive host force-stop") { runCatching { state(session).optInt("taps") == 1 }.getOrDefault(false) }
            val gateway = Gateway(context)
            val restored = gateway.request("GET", "/runs/$id")
            assertEquals("paused", restored.getString("status"))
            assertFalse(restored.has("pending_command")); assertFalse(restored.has("pending_request"))
            repeat(3) {
                assertTrue(gateway.request("GET", "/devices/${DirectRuntime.DEVICE_ID}/commands").isNull("command"))
                assertEquals(1, state(session).getInt("taps"))
            }
            assertTrue("Reading paused state cannot submit commands", ledgerIds().toSet() == strings(m.getJSONArray("old_command_ids")).toSet())
            report.put("cold_start_status", "paused").put("replayed_before_resume", false)
            server = LocalModel(m.getString("role"), recovering = true)
            configure(server.port)
            gateway.request("POST", "/runs/$id/resume", JSONObject())
            val first = gateway.request("GET", "/devices/${DirectRuntime.DEVICE_ID}/commands").getJSONObject("command")
            assertEquals("screenshot", first.getString("kind"))
            assertFalse(strings(m.getJSONArray("old_command_ids")).contains(first.getString("id")))
            assertTrue(TaskControl.startWorker(context))
            await("The real Worker must capture afresh and complete through the new A response", 20000) {
                gateway.runStatus(id) == "completed"
            }
            server.failure?.let { throw AssertionError("Recovery responder failed", it) }
            assertEquals(1, server.primary); assertEquals(0, server.grounding)
            assertTrue("Resumed A must receive an actual image", server.sawImage)
            val archives = gateway.request("GET", "/runs/$id/screenshots").getJSONArray("items")
            assertTrue("Worker must archive the exact first resumed screenshot", (0 until archives.length()).any {
                archives.getJSONObject(it).optString("command_id") == first.getString("id")
            })
            assertTrue(ledgerIds().contains(first.getString("id")))
            val final = state(session)
            assertEquals(1, final.getInt("taps")); assertEquals(1, final.getInt("down")); assertEquals(1, final.getInt("up"))
            assertEquals(0, final.getInt("cancel"))
            report.put("passed", true).put("first_resumed_command", "screenshot").put("fresh_image_received", true)
                .put("target_taps_after_resume", 1).put("old_action_replayed", false).put("pending_action_executed", false)
                .put("recovery_A_requests", server.primary).put("recovery_B_requests", server.grounding)
        } catch (failure: Throwable) {
            report.put("failure_type", failure.javaClass.simpleName)
            throw failure
        } finally {
            server?.close()
            try { restore(); report.put("private_backup_restored", true) }
            finally { evidence("verify", report) }
        }
    }

    /** Safe to retry after any failed stage. Backup is retained until this explicit final cleanup. */
    @Test fun cleanup() {
        requireOptIn()
        if (!manifest.exists()) return
        restore()
        evidence("cleanup", JSONObject().put("restored", true))
        val checked = root.canonicalFile
        check(checked == File(context.noBackupFilesDir.canonicalFile, "qa-worker-request-restart-v1"))
        check(checked.deleteRecursively())
    }

    private fun configure(port: Int) {
        val providers = ModelProviders(context)
        val provider = ModelProvider("worker-restart-local", "隔离进程恢复验证", "http://127.0.0.1:$port/v1")
        providers.saveProvider(provider, "synthetic-local-only", emptyMap())
        for (model in listOf("fixture-primary", "fixture-grounding")) {
            val target = providers.requestTarget(provider.id, model)
            providers.recordVision(provider.id, model, ModelVision.VERIFIED, target.fingerprint)
        }
        providers.saveRouting(ModelRouting(ModelSelection(provider.id, "fixture-primary"), true, ModelSelection(provider.id, "fixture-grounding")))
        assertTrue(providers.isReady())
    }
    private fun openFixture(session: String) {
        context.startActivity(Intent().setClassName(fixture, "$fixture.GestureEffectsFixtureActivity")
            .putExtra("mode", "events").putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Fresh gesture fixture must be visible") { runCatching { state(session).getString("session") == session }.getOrDefault(false) }
        SystemClock.sleep(400)
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun state(session: String): JSONObject {
        val all = nodes(ui.rootInActiveWindow)
        return try {
            val marker = all.single { it.packageName?.toString() == fixture && it.contentDescription?.startsWith("gesture-result:") == true }
            JSONObject(marker.contentDescription.toString().removePrefix("gesture-result:")).also { assertEquals(session, it.getString("session")) }
        } finally { all.forEach { it.recycle() } }
    }
    private fun storedRun(id: String): JSONObject = dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(File(context.noBackupFilesDir, "direct-runs-v1.json").readText()).let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    }
    private fun ledgerIds(): List<String> = context.openOrCreateDatabase("command_results.db", 0, null).use { db ->
        db.rawQuery("SELECT id FROM results ORDER BY id", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
    private fun strings(values: JSONArray) = (0 until values.length()).map(values::getString)

    private fun paths(): List<Pair<String, File>> = privatePaths.map { "private/$it" to File(context.noBackupFilesDir, it) } +
        databasePaths.map { "database/$it" to context.getDatabasePath(it) }
    private fun eraseChecked(file: File) {
        val safe = paths().any { it.second.canonicalFile == file.canonicalFile }
        check(safe); check(!file.exists() || file.deleteRecursively())
    }
    private fun copyChecked(source: File, destination: File) {
        check(!java.nio.file.Files.isSymbolicLink(source.toPath()))
        if (source.isDirectory) {
            check(destination.isDirectory || destination.mkdirs())
            source.listFiles().orEmpty().forEach { copyChecked(it, File(destination, it.name)) }
        } else {
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            check(source.readBytes().contentEquals(destination.readBytes()))
        }
    }
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun visit(current: File, relative: String) {
            check(current.exists() && !java.nio.file.Files.isSymbolicLink(current.toPath()))
            digest.update("${if (current.isDirectory) "D" else "F"}:$relative\u0000".toByteArray())
            if (current.isDirectory) current.listFiles().orEmpty().sortedBy { it.name }.forEach { visit(it, "$relative/${it.name}") }
            else current.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
        }
        visit(file, "")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun prefSnapshot(prefs: SharedPreferences) = JSONObject().apply {
        prefs.all.forEach { (key, value) -> put(key, JSONObject().put("type", when (value) {
            is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> error("Unsupported preference")
        }).put("value", if (value is Set<*>) JSONArray(value.toList()) else value)) }
    }
    private fun restorePrefs(prefs: SharedPreferences, saved: JSONObject) {
        val edit = prefs.edit().clear()
        saved.keys().forEach { key -> val row = saved.getJSONObject(key); when (row.getString("type")) {
            "string" -> edit.putString(key, row.getString("value")); "boolean" -> edit.putBoolean(key, row.getBoolean("value"))
            "int" -> edit.putInt(key, row.getInt("value")); "long" -> edit.putLong(key, row.getLong("value"))
            "float" -> edit.putFloat(key, row.getDouble("value").toFloat()); "set" -> edit.putStringSet(key, strings(row.getJSONArray("value")).toSet())
        } }
        check(edit.commit())
        val after = prefSnapshot(prefs)
        check(saved.keys().asSequence().toSet() == after.keys().asSequence().toSet())
        saved.keys().forEach { key ->
            val a = saved.getJSONObject(key); val b = after.getJSONObject(key)
            check(a.getString("type") == b.getString("type"))
            check(when (a.getString("type")) {
                "set" -> strings(a.getJSONArray("value")).toSet() == strings(b.getJSONArray("value")).toSet()
                "float" -> a.getDouble("value").toFloat() == b.getDouble("value").toFloat()
                else -> a.get("value").toString() == b.get("value").toString()
            })
        }
    }
    private fun backup(info: JSONObject) {
        check(!root.exists() || root.listFiles().orEmpty().isEmpty())
        check(root.isDirectory || root.mkdirs())
        val preferences = JSONObject()
        prefNames.forEach { preferences.put(it, prefSnapshot(context.getSharedPreferences(it, 0))) }
        atomic(File(root, "preferences.json"), preferences)
        val files = JSONArray()
        paths().forEach { (name, source) ->
            val row = JSONObject().put("name", name).put("present", source.exists())
            files.put(row)
            if (source.exists()) {
                val hash = fingerprint(source); val backup = File(root, "backup/$name")
                copyChecked(source, backup); check(hash == fingerprint(backup)); row.put("sha256", hash)
            }
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        atomic(manifest, info.put("files", files).put("preferences_sha256", fingerprint(File(root, "preferences.json")))
            .put("had_model_alias", store.containsAlias(alias)).put("restored", false))
    }
    private fun restore() {
        if (!manifest.exists()) return
        val m = meta()
        if (m.optBoolean("restored")) return
        // Validate the entire backup before replacing even one live file. A missing/corrupt backup
        // leaves the loopback fixture intact so recovery can be investigated without losing either copy.
        val prefFile = File(root, "preferences.json")
        check(fingerprint(prefFile) == m.getString("preferences_sha256"))
        val preferences = JSONObject(prefFile.readText())
        prefNames.forEach { preferences.getJSONObject(it) }
        val listed = m.getJSONArray("files")
        paths().forEach { (name, _) ->
            val row = (0 until listed.length()).map(listed::getJSONObject).single { it.getString("name") == name }
            if (row.getBoolean("present")) check(fingerprint(File(root, "backup/$name")) == row.getString("sha256"))
        }
        val worker = DeviceWorkerService.instance
        val id = m.optString("run_id")
        val active = context.getSharedPreferences("doppel", 0).getString("active_run", "").orEmpty()
        val previousActive = preferences.getJSONObject("doppel").optJSONObject("active_run")?.optString("value").orEmpty()
        check(active.isBlank() || active == id || active == previousActive) { "Leave a newer active task untouched" }
        if (worker != null) {
            val liveRows = dev.doppel.sdk.SplitTaskEngine.readPersistedRuns(File(context.noBackupFilesDir, "direct-runs-v1.json").readText())
            check(id.isNotBlank() && liveRows.length() == 1 && liveRows.getJSONObject(0).optString("id") == id &&
                (active.isBlank() || active == id)) { "Do not stop a Worker not owned by this staged fixture" }
        }
        val runtime = runtimeField().get(null) as? DirectRuntime
        if (runtime != null && id.isNotBlank()) runCatching { runtime.request("POST", "/runs/$id/cancel", JSONObject()) }
        inst.runOnMainSync { context.stopService(Intent(context, DeviceWorkerService::class.java)) }
        await("Fixture Worker must stop before restoring real model settings") { DeviceWorkerService.instance == null }
        worker?.let {
            val executor = DeviceWorkerService::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(it) as ExecutorService
            check(executor.awaitTermination(8, TimeUnit.SECONDS))
        }
        if (runtime != null) {
            DirectRuntime.interrupt(context)
            val executor = DirectRuntime::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(runtime) as ExecutorService
            executor.shutdownNow(); check(executor.awaitTermination(8, TimeUnit.SECONDS))
            runtimeField().set(null, null)
        }
        // Worker tick may still be finishing a read-only disabled-schedule pass.
        val schedule = ScheduleManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)
        if (schedule != null) {
            val io = ScheduleManager::class.java.getDeclaredField("io").apply { isAccessible = true }.get(schedule) as ExecutorService
            io.submit {}.get(8, TimeUnit.SECONDS)
        }
        paths().forEach { (name, destination) ->
            val row = (0 until listed.length()).map(listed::getJSONObject).single { it.getString("name") == name }
            eraseChecked(destination)
            if (row.getBoolean("present")) copyChecked(File(root, "backup/$name"), destination)
            else check(!destination.exists())
        }
        prefNames.forEach { restorePrefs(context.getSharedPreferences(it, 0), preferences.getJSONObject(it)) }
        if (!m.getBoolean("had_model_alias")) KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        atomic(manifest, m.put("restored", true))
    }

    private class LocalModel(private val role: String, private val recovering: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val blocked = CountDownLatch(1)
        private val release = CountDownLatch(1)
        @Volatile var primary = 0; private set
        @Volatile var grounding = 0; private set
        @Volatile var sawImage = false; private set
        @Volatile var failure: Throwable? = null; private set
        @Volatile private var running = true
        @Volatile private var current: Socket? = null
        private val thread = Thread({
            while (running) try {
                socket.accept().use { client ->
                    current = client; client.soTimeout = 5000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String = buildString {
                        while (length < 8192) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) append(c.toChar()) }
                    }
                    val first = line(); check(first.startsWith("POST /v1/chat/completions "))
                    var size = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                    }
                    check(size in 1..20 * 1024 * 1024)
                    val bytes = ByteArray(size); var offset = 0
                    while (offset < size) { val n = input.read(bytes, offset, size - offset); check(n > 0); offset += n }
                    val request = JSONObject(String(bytes, Charsets.UTF_8)); bytes.fill(0)
                    val model = request.getString("model")
                    val isA = model == "fixture-primary"
                    check(isA || model == "fixture-grounding")
                    if (isA) primary++ else grounding++
                    sawImage = hasActualPng(request) || sawImage
                    if (!recovering && (role == "A" && isA && primary == 2 || role == "B" && !isA && grounding == 2)) {
                        blocked.countDown(); release.await(55, TimeUnit.SECONDS)
                        check(!running) { "Host did not terminate the pending request" }; return@use
                    }
                    check(if (recovering) isA && primary == 1 else primary <= 2 && grounding <= 1)
                    val envelope = if (recovering) JSONObject().put("decision", JSONObject().put("kind", "finish").put("status", "completed")
                        .put("message", "已重新观察，保持已完成的一次点击，不重复操作")).put("state", JSONObject.NULL)
                    else if (isA) JSONObject().put("decision", JSONObject().put("kind", "tap").put("target", "点击独立手势区域中心一次")
                        .put("expected", "触摸计数增加一次").put("screen_context", "独立手势验证页面").put("request_login_code", JSONObject.NULL)).put("state", JSONObject.NULL)
                    else JSONObject().put("result", JSONObject().put("status", "located").put("action", "tap")
                        .put("assessment", JSONObject().put("alignment", "consistent")).put("points", JSONArray().put(JSONArray(listOf(500, 500)))).put("duration_ms", 100))
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                        .put("message", JSONObject().put("role", "assistant").put("content", envelope.toString()))))
                        .put("usage", JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0)).toString().toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            } catch (error: Throwable) { if (running) { failure = error; blocked.countDown() }; break }
        }, "worker-restart-local-model").apply { isDaemon = true; start() }
        private fun hasActualPng(request: JSONObject): Boolean {
            var images = 0
            val messages = request.getJSONArray("messages")
            repeat(messages.length()) { index ->
                val content = messages.getJSONObject(index).optJSONArray("content") ?: return@repeat
                for (part in 0 until content.length()) {
                    val item = content.getJSONObject(part)
                    if (item.optString("type") != "image_url") continue
                    // Read the JSON string value, not serialized JSON (which may escape '/').
                    val url = item.getJSONObject("image_url").getString("url")
                    val prefix = "data:image/png;base64,"
                    check(url.startsWith(prefix))
                    val bytes = Base64.decode(url.removePrefix(prefix), Base64.DEFAULT)
                    check(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(
                        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { check(bitmap.width > 0 && bitmap.height > 0); images++ }
                    finally { bitmap.recycle(); bytes.fill(0) }
                }
            }
            return images > 0
        }
        override fun close() { running = false; release.countDown(); runCatching { current?.close() }; socket.close(); thread.join(1500) }
    }
}
