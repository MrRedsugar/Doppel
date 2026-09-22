@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.DoppelAccessibilityService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.SplitTaskEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real host dispatch and Android touch; scripted planner replies and local-fill receipt, no provider calls. */
class LoginVerificationDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val base get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val fixture = "dev.doppel.testapp"

    @Test fun hostRequiresLiveLoginPermitAndRevocationStopsTheNextRealGesture() {
        assertNull("Retain any running user task", DeviceWorkerService.instance)
        assertTrue(DirectMode.available(base))
        AccessibilityServiceTestBinding.rebindAlreadyEnabled(inst)
        val service = requireNotNull(DoppelAccessibilityService.instance)
        val field = DirectRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null) as? DirectRuntime
        assertTrue("Retain any unfinished local task", previous?.hasUnfinishedRun() != true)
        val preferences = base.getSharedPreferences("doppel", 0)
        val beforePreferences = preferences.all.toMap()
        val beforeFiles = fingerprint(base.noBackupFilesDir)
        val prefix = "qa-login-permit-${UUID.randomUUID()}-"
        val folder = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = folder
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
                .also { names.add(prefix + name) }
        }
        val reportFolder = File(base.getExternalFilesDir(null), "login-verification-gate").apply { mkdirs() }
        val report = JSONObject().put("model_provider_calls", 0).put("local_fill_receipt", "scripted")
            .put("scope", "Real host gate and fixture touch, not visual challenge-solving accuracy")
        var runtime: DirectRuntime? = null
        var executor: ExecutorService? = null
        var passed = false
        try {
            assertFalse(FirstUseConsent.isAccepted(isolated))
            assertTrue(preferences.edit().putBoolean("direct_mode", true).commit())
            runtime = DirectRuntime::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(isolated)
            executor = DirectRuntime::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(runtime) as ExecutorService
            val engine = DirectRuntime::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(runtime) as SplitTaskEngine
            field.set(null, runtime)
            base.startActivity(Intent().setClassName(fixture, "$fixture.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("登录验证门控测试"); find("挑战点击计数 0").recycle()

            fun screenshot(command: JSONObject): JSONObject {
                val result = service.execute(command)
                assertEquals(result.toString(), "ok", result.optString("status"))
                assertEquals(fixture, result.getJSONObject("observation").getString("package_name"))
                return result
            }
            fun point(frame: JSONObject): JSONArray {
                val node = find("记录测试点击")
                val bounds = Rect()
                try { node.getBoundsInScreen(bounds) } finally { node.recycle() }
                return JSONArray().put(JSONArray().put(bounds.centerX() * 1000.0 / frame.getInt("display_width"))
                    .put(bounds.centerY() * 1000.0 / frame.getInt("display_height")))
            }
            val noPermitShot = screenshot(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", "gate-no-permit")
                .put("kind", "screenshot").put("split_agent", true).put("mode", "full"))
            val initialFrame = noPermitShot.getJSONObject("data").getJSONObject("visual_frame")
            val blocked = service.execute(JSONObject().put("id", UUID.randomUUID().toString()).put("run_id", "gate-no-permit")
                .put("kind", "split_action").put("split_agent", true).put("mode", "full").put("source", initialFrame)
                .put("action", JSONObject().put("status", "located").put("action", "tap").put("target", "记录测试点击")
                    .put("points", point(initialFrame)).put("duration_ms", 60)))
            assertEquals("blocked", blocked.optString("status"))
            assertEquals("verification", blocked.getJSONObject("data").getString("human_takeover"))
            find("挑战点击计数 0").recycle()
            report.put("without_permit_blocked", true)

            val run = engine.create(JSONObject().put("device_id", DirectRuntime.DEVICE_ID).put("goal", "隔离登录验证手势门控")
                .put("mode", "full"))
            val id = run.getString("id")
            var frame = JSONObject()
            fun capture() {
                val result = screenshot(engine.poll().getJSONObject("command"))
                frame = result.getJSONObject("data").getJSONObject("visual_frame")
                assertTrue(engine.result(result).getBoolean("accepted"))
            }
            fun reply(decision: JSONObject, grounding: Boolean = false) {
                val work = requireNotNull(engine.takeWork())
                assertEquals(grounding, work.grounding)
                val envelope = if (grounding) JSONObject().put("result", decision)
                    else JSONObject().put("decision", decision).put("state", JSONObject.NULL)
                engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", envelope.toString())))))
            }
            capture()
            reply(JSONObject().put("kind", "login_username").put("target", "当前登录输入框").put("expected", "填写本机账号")
                .put("screen_context", "").put("package_name", fixture).put("credential_label", "isolated-fixture"))
            val localFill = engine.poll().getJSONObject("command")
            assertEquals("login_username", localFill.getString("kind"))
            engine.result(JSONObject().put("run_id", id).put("command_id", localFill.getString("id")).put("status", "ok")
                .put("data", JSONObject().put("action_state", "accepted")))
            capture()
            reply(JSONObject().put("kind", "login_verification").put("phase", "begin").put("package_name", fixture)
                .put("reason", "隔离已有账号登录验证测试"))
            fun queueTap(): JSONObject {
                reply(JSONObject().put("kind", "tap").put("target", "记录测试点击").put("expected", "测试计数增加一次")
                    .put("screen_context", "").put("request_login_code", JSONObject.NULL).apply {
                        if (run.getString("execution_mode") == "direct") put("points", point(frame)).put("duration_ms", 60)
                    })
                if (run.getString("execution_mode") != "direct") {
                    capture()
                    reply(JSONObject().put("status", "located").put("action", "tap").put("points", point(frame))
                        .put("duration_ms", 60).put("assessment", JSONObject().put("alignment", "consistent")), true)
                }
                return engine.poll().getJSONObject("command").also { assertTrue(it.optString("login_verification_permit").isNotBlank()) }
            }
            val allowed = service.execute(queueTap())
            assertEquals(allowed.toString(), "ok", allowed.optString("status"))
            assertEquals("accepted", allowed.getJSONObject("data").getString("action_state"))
            find("挑战点击计数 1").recycle()
            report.put("valid_permit_real_tap_confirmed", true)
            assertTrue(engine.result(allowed).getBoolean("accepted")); capture()
            val oldCommand = queueTap()
            engine.control(id, "pause", JSONObject())
            val revoked = service.execute(oldCommand)
            assertEquals(revoked.toString(), "blocked", revoked.optString("status"))
            assertEquals("verification", revoked.getJSONObject("data").getString("human_takeover"))
            find("挑战点击计数 1").recycle()
            report.put("revoked_permit_blocked_without_second_tap", true)
            automation.takeScreenshot()?.let { bitmap ->
                File(reportFolder, "gate-counter-one.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            passed = true
        } finally {
            service.stopActionFeedback()
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            if (runtime != null) { assertSame(runtime, field.get(null)); field.set(null, previous) }
            executor?.shutdownNow(); executor?.awaitTermination(5, TimeUnit.SECONDS)
            val editor = preferences.edit()
            if (beforePreferences.containsKey("direct_mode")) editor.putBoolean("direct_mode", beforePreferences["direct_mode"] as Boolean)
            else editor.remove("direct_mode")
            check(editor.commit())
            names.forEach { base.deleteSharedPreferences(it) }
            assertEquals(File(base.cacheDir.canonicalFile, prefix), folder.canonicalFile)
            assertTrue(folder.deleteRecursively())
            val preserved = beforePreferences == preferences.all && beforeFiles == fingerprint(base.noBackupFilesDir)
            File(reportFolder, "report.json").writeText(report.put("passed", passed && preserved).put("original_state_preserved", preserved).toString(2))
            assertTrue("Preserve all original task, model and credential state", preserved)
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else listOf(root) +
        (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    private fun find(text: String, visible: Boolean = true): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8000
        do {
            val all = nodes(automation.rootInActiveWindow)
            val result = all.firstOrNull { it.packageName?.toString() == fixture && it.text?.toString() == text && (!visible || it.isVisibleToUser) }
            all.filter { it !== result }.forEach { it.recycle() }
            if (result != null) return result
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Fixture control not visible: $text")
    }
    private fun click(text: String) {
        val node = find(text, false)
        try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { node.recycle() }
        inst.waitForIdleSync()
    }
    private fun fingerprint(folder: File) = folder.walkTopDown().filter(File::isFile).associate {
        it.relativeTo(folder).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
}
