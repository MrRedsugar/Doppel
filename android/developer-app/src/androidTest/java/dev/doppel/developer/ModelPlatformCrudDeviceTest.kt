@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Current native settings UI, plus its production probe handler targeting only our temporary provider.
 * The local server simulates authentication/vision errors. No real provider is contacted or reselected.
 */
class ModelPlatformCrudDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val providers get() = ModelProviders(context)
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun views(): List<View> = WindowInspector.getGlobalWindowViews().asReversed().filter { it.isAttachedToWindow && it.isShown }.flatMap(::all)
    private fun <T> main(block: () -> T): T { var result: Result<T>? = null; inst.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition()) { if (SystemClock.elapsedRealtime() > end) fail(label); SystemClock.sleep(80) }
    }
    private fun shown(text: String) = main { views().filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(text) } }
    private fun expect(text: String) = await("Expected current UI: $text") { shown(text) }
    private fun click(label: String, description: Boolean = false) {
        await("Missing enabled control: $label") { main { views().any { it.isShown && it.isEnabled &&
            if (description) it.contentDescription?.toString() == label else it is TextView && it.text.toString() == label } } }
        main {
            val target = views().first { it.isShown && it.isEnabled &&
                if (description) it.contentDescription?.toString() == label else it is TextView && it.text.toString() == label }
            target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
            var clickable: View? = target
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent as? View
            assertTrue("The actual UI listener must handle $label", clickable?.performClick() == true)
        }
        inst.waitForIdleSync()
    }
    private fun fill(hint: String, text: String) {
        main { views().filterIsInstance<EditText>().first { it.isShown && it.hint?.toString() == hint }.setText(text) }
        inst.waitForIdleSync()
    }
    private fun fingerprint(id: String): String = if (providers.hasCredentials(id)) providers.requestTarget(id, "").fingerprint else "unconfigured"
    private fun fingerprints() = providers.list().associate { it.id to fingerprint(it.id) }
    private fun launch(): Activity = inst.startActivitySync(Intent(context, ModelSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).also { activity ->
        expect("模型连接"); assertTrue(main { activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
    }
    private fun finish(activity: Activity?) {
        if (activity == null) return
        main { activity.finish() }; inst.waitForIdleSync()
        val executor = ModelSettingsActivity::class.java.getDeclaredField("io").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
        assertTrue("Settings writes must finish before cleanup", executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test fun temporaryPlatformCrudAndFailureStatesPreserveExistingSelectionsAndCredentials() {
        assertTrue(android.os.Build.VERSION.SDK_INT >= 29)
        assertTrue(FirstUseConsent.isAccepted(context))
        assertNull("The worker must be stopped before opening protected settings", DeviceWorkerService.instance)
        assertFalse("Do not alter settings around an unfinished task", DirectRuntime.get(context).hasUnfinishedRun())
        val original = providers.list(); val routing = providers.routing(); val before = fingerprints()
        assertTrue("Leave room for exactly one temporary provider", original.size < 30)
        val label = "QA 临时平台 ${UUID.randomUUID().toString().take(8)}"
        val edited = "$label 已编辑"
        val stages = JSONArray(); val fixture = LocalProvider()
        var activity: Activity? = null; var ownId: String? = null; var passed = false
        fun checkpoint(name: String) {
            assertEquals("Never change either selected model", routing, providers.routing())
            for ((id, expected) in before) assertEquals("Original provider fingerprint changed", expected, fingerprint(id))
            stages.put(name)
        }
        fun selectTemporaryForDiscovery() {
            click("选择默认模型"); click("选择平台", description = true); click(edited)
            fill("输入模型名称", "fixture-nonvision")
        }
        try {
            activity = launch()
            click("添加平台"); click("自定义兼容平台")
            fill("平台名称", label); fill("API 地址", "http://remote.invalid/v1"); fill("API Key", "sk-qa-wrong")
            click("保存平台"); expect("远程 API 必须使用 HTTPS")
            assertEquals(original.size, providers.list().size)
            fill("API 地址", fixture.url + "/chat/completions")
            fill("例如 {\"X-Tenant\":\"名称\"}", "{\"Host\":\"forbidden\"}")
            click("保存平台"); expect("附加请求头名称无效")
            assertEquals(original.size, providers.list().size)
            fill("例如 {\"X-Tenant\":\"名称\"}", "{\"X-Fixture\":\"qa-only\"}")
            click("保存平台"); expect("平台已保存")
            ownId = providers.list().single { it.name == label }.id
            val id = ownId!!
            assertEquals(fixture.url, providers.list().single { it.id == id }.baseUrl)
            assertTrue(providers.hasCredentials(id)); assertEquals(listOf("X-Fixture"), providers.headerNames(id))
            checkpoint("invalid-url-and-header-rejected-valid-platform-saved")

            val ownFingerprint = fingerprint(id)
            click(label); fill("平台名称", edited)
            click("保存平台"); expect("平台已保存")
            assertEquals("Blank credential fields must retain only our saved synthetic credentials", ownFingerprint, fingerprint(id))
            finish(activity); activity = launch(); expect(edited)
            assertEquals(ownFingerprint, fingerprint(id)); checkpoint("edit-blank-auth-and-reopen-preserved")

            click(edited); fill("API 地址", "https://different.fixture.invalid/v1")
            click("保存平台"); expect("API 域名已更换，请重新输入")
            assertEquals(fixture.url, providers.list().single { it.id == id }.baseUrl)
            click("取消")
            selectTemporaryForDiscovery(); click("获取平台模型列表"); expect("平台认证失败")
            click("取消"); checkpoint("changed-host-and-invalid-auth-rejected")

            click(edited); fill("新的 API Key（留空保留）", "sk-qa-local-only")
            click("保存平台"); expect("平台已保存")
            assertNotEquals(ownFingerprint, fingerprint(id))
            selectTemporaryForDiscovery(); click("获取平台模型列表"); expect("平台模型列表")
            click("fixture-nonvision"); click("取消")
            checkpoint("model-discovery-without-saving-selection")

            // The visible probe buttons target selected models. Exercise that same handler with our
            // temporary selection, so this capability failure test never changes a user's routing.
            val probe = ModelSettingsActivity::class.java.getDeclaredMethod("probe", ModelSelection::class.java, String::class.java).apply { isAccessible = true }
            main { probe.invoke(activity, ModelSelection(id, "fixture-nonvision"), "grounding") }
            expect("平台明确表示此模型不支持图片")
            assertEquals(ModelVision.UNSUPPORTED, providers.vision(id, "fixture-nonvision"))
            finish(activity); activity = launch()
            assertEquals(ModelVision.UNSUPPORTED, providers.vision(id, "fixture-nonvision"))
            checkpoint("nonvisual-model-is-not-ready-and-rejection-persists")
            click(edited); click("删除"); click("删除平台"); expect("平台已删除")
            assertEquals(original, providers.list()); checkpoint("temporary-platform-deleted")
            assertEquals(3, fixture.requests.get()); passed = true
        } finally {
            finish(activity); fixture.close()
            providers.list().filter { it.id !in before.keys && it.name.startsWith(label) }.forEach { providers.deleteProvider(it.id) }
            val preserved = providers.list() == original && providers.routing() == routing && fingerprints() == before
            val folder = File(context.getExternalFilesDir(null), "full-feature/model-platform-crud").apply { mkdirs() }
            File(folder, "report.json").writeText(JSONObject().put("passed", passed && preserved).put("original_configuration_preserved", preserved)
                .put("local_http_requests", fixture.requests.get()).put("external_requests", 0).put("stages", stages)
                .put("vision_test", "production UI handler with a temporary selection; selected models unchanged").toString(2))
            assertTrue("Delete only the temporary provider; preserve all existing selections and fingerprints", preserved)
        }
    }

    private class LocalProvider : AutoCloseable {
        val requests = AtomicInteger()
        private val running = AtomicBoolean(true)
        private val server = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
        val url = "http://127.0.0.1:${server.localPort}/v1"
        private val executor = Executors.newSingleThreadExecutor()
        private val result = executor.submit {
            while (running.get()) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    catch (failure: java.io.IOException) { if (!running.get()) break else throw failure }
                socket.use {
                    socket.soTimeout = 10000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val next = input.read(); check(next >= 0 && header.length < 32768); header.append(next.toChar()) }
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    check(length <= 1_000_000); repeat(length) { check(input.read() >= 0) }
                    requests.incrementAndGet()
                    val authorized = header.contains("Authorization: Bearer sk-qa-local-only", ignoreCase = true)
                    val models = header.startsWith("GET /v1/models ")
                    val status = if (!authorized) 401 else if (models) 200 else 400
                    val body = when (status) {
                        401 -> "{\"error\":{\"code\":\"invalid_api_key\"}}"
                        200 -> "{\"data\":[{\"id\":\"fixture-nonvision\"}]}"
                        else -> "{\"error\":{\"message\":\"image input is not supported\"}}"
                    }.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                }
            }
        }
        override fun close() {
            running.set(false); server.close(); executor.shutdown()
            result.get(12, TimeUnit.SECONDS); assertTrue(executor.awaitTermination(12, TimeUnit.SECONDS))
        }
    }
}
