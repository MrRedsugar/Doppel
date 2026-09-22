@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package dev.doppel.developer

import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AndroidWebResearch
import dev.doppel.sdk.PublicWebTransport
import dev.doppel.sdk.ReaderService
import dev.doppel.sdk.SplitTaskEngine
import dev.doppel.sdk.WebResearchFailure
import dev.doppel.sdk.WebResearchUrls
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in public-network acceptance of the production reader. No model calls or settings edits. */
class LocalReaderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val huawei = "https://developer.huawei.com/consumer/cn/doc/design-guides/corner-radius-parameter-0000002556468705"

    @Test fun publicTextScreenshotCancellationAndServiceRecreation() {
        assumeTrue("Opt in with -e local_reader_live true", InstrumentationRegistry.getArguments().getString("local_reader_live") == "true")
        val taskFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val taskBefore = if (taskFile.isFile) taskFile.readBytes() else null
        val runs = SplitTaskEngine.readPersistedRuns(taskBefore?.toString(Charsets.UTF_8) ?: "[]")
        for (index in 0 until runs.length()) assertTrue("Reader QA requires idle or paused tasks",
            runs.getJSONObject(index).optString("status") in setOf("paused", "completed", "failed", "cancelled"))
        val evidenceDir = File(context.getExternalFilesDir(null), "local-reader-device-test").apply { check(isDirectory || mkdirs()) }
        for (name in listOf("results.json", "example-com.md", "huawei-corner-radius.md", "public-dummy-pdf.md",
            "example-org-screenshot-source.md", "example-org.png", "after-http-error.md", "after-cancel.md", "service-cycle-1.md", "service-cycle-2.md", "service-cycle-3.md"))
            File(evidenceDir, name).takeIf { it.isFile }?.let { check(it.delete()) { "Cannot replace previous Reader QA evidence" } }
        val evidence = JSONObject().put("started_at_ms", System.currentTimeMillis()).put("model_calls_requested", 0)
            .put("public_dns_answers", publicDnsAnswers())
        val stages = JSONArray(); evidence.put("stages", stages)
        val failures = mutableListOf<String>()
        val selectedStages = InstrumentationRegistry.getArguments().getString("reader_stage").orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()
        fun stage(name: String, block: (JSONObject) -> Unit) {
            if (selectedStages.isNotEmpty() && name !in selectedStages) return
            val record = JSONObject().put("stage", name)
            val started = System.nanoTime()
            try { block(record); record.put("ok", true) }
            catch (failure: Throwable) {
                failures += name
                record.put("ok", false).put("failure_type", failure.javaClass.simpleName)
                    .put("failure", failure.message?.take(600).orEmpty())
            } finally { record.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); stages.put(record) }
        }
        fun read(web: AndroidWebResearch, url: String, record: JSONObject, name: String): String {
            val result = web.readPage(JSONObject().put("url", url).put("limit", 10000).put("refresh", true))
            val text = result.optString("text")
            File(evidenceDir, "$name.md").writeText(text)
            record.put("result", JSONObject(result.toString()).apply { remove("text") })
                .put("text_chars", text.length).put("text_sha256", sha256(text.toByteArray()))
            assertReference(result)
            return text
        }
        val web = AndroidWebResearch(context)
        try {
            stage("example_com_text") { record ->
                val text = read(web, "https://example.com/", record, "example-com")
                assertTrue("Example Domain body must be extracted", isExampleBody(text))
            }
            stage("huawei_dynamic_article") { record ->
                val text = read(web, huawei, record, "huawei-corner-radius")
                val body = text.substringAfter("Markdown Content:", "")
                assertTrue("Huawei article body, not just a navigation label, must contain corner-radius guidance",
                    body.length > 300 && Regex("圆角").findAll(body).count() >= 2 &&
                        body.lineSequence().any { it.contains("圆角") && it.count { char -> char in '\u4e00'..'\u9fff' } >= 30 } &&
                        Regex("圆角[\\s\\S]{0,180}(半径|组件|卡片|容器|视觉|层级|参数|一致)|(?:半径|组件|卡片|容器|视觉|层级|参数|一致)[\\s\\S]{0,180}圆角").containsMatchIn(body))
            }
            stage("public_pdf_text") { record ->
                val text = read(web, "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf", record, "public-dummy-pdf")
                assertTrue("Public PDF must pass through the local document parser", text.contains("Dummy PDF file"))
            }
            stage("http_error_status_and_recovery") { record ->
                val missing = "https://example.com/doppel-reader-missing-reference"
                val transport = PublicWebTransport()
                try {
                    val response = transport.fetchResource(WebResearchUrls.parse(missing),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(20), {})
                    record.put("resource_status", response.status).put("resource_body_bytes", response.bytes.size)
                    assertEquals("Page scripts must receive the original HTTP status", 404, response.status)
                    assertTrue("HTTP error response body must remain available to page scripts", response.bytes.isNotEmpty())
                    var consumed = 0
                    val page = transport.fetchResource(WebResearchUrls.parse("https://example.com/"), System.nanoTime() + TimeUnit.SECONDS.toNanos(20),
                        {}, "GET", emptyMap(), null) { consumed += it }
                    record.put("counted_body_bytes", consumed).put("example_body_bytes", page.bytes.size)
                    assertEquals(200, page.status)
                    assertTrue(page.bytes.toString(Charsets.UTF_8).contains("Example Domain"))
                    assertEquals("Every response byte must consume the shared page budget", page.bytes.size, consumed)
                    val interrupted = assertThrows(WebResearchFailure::class.java) {
                        transport.fetchResource(WebResearchUrls.parse("https://example.com/"), System.nanoTime() + TimeUnit.SECONDS.toNanos(20),
                            {}, "GET", emptyMap(), null) { throw WebResearchFailure("resource_limit", "Test byte budget reached") }
                    }
                    record.put("budget_interruption_code", interrupted.code)
                    assertEquals("resource_limit", interrupted.code)
                } finally { transport.cancel() }
                val rejected = web.read(missing)
                record.put("reader_result", rejected)
                assertEquals("http_404", rejected.optJSONObject("error")?.optString("code"))
                assertFalse("HTTP error pages must not become reference text", rejected.optBoolean("ok"))
                assertFalse(rejected.has("text"))
                val recovery = JSONObject(); record.put("recovery", recovery)
                assertTrue("Reader must recover after an HTTP error",
                    isExampleBody(read(web, "https://example.com/", recovery, "after-http-error")))
            }
            stage("example_org_screenshot") { record ->
                val pageCheck = JSONObject(); record.put("page_check", pageCheck)
                val textVerified = isExampleBody(read(web, "https://example.org/", pageCheck, "example-org-screenshot-source"))
                val result = web.readPage(JSONObject().put("url", "https://example.org/").put("operation", "screenshot"))
                val data = result.remove("_reference_image_data_url")?.toString().orEmpty()
                record.put("result", result)
                assertReference(result)
                assertTrue("Reader must return PNG bytes", data.startsWith("data:image/png;base64,"))
                val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
                File(evidenceDir, "example-org.png").writeBytes(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("Reader screenshot must decode", bitmap)
                try {
                    assertEquals(result.getInt("width"), bitmap.width); assertEquals(result.getInt("height"), bitmap.height)
                    val colors = hashSetOf<Int>()
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) colors.add(bitmap.getPixel(x, y))
                    record.put("sampled_colors", colors.size).put("png_sha256", sha256(bytes))
                    assertTrue("Reader screenshot must contain rendered content, not a blank surface", colors.size > 8)
                    assertTrue("Screenshot source must also yield the known page body, not a WebView error page", textVerified)
                } finally { bitmap.recycle() }
            }
            stage("cancel_then_reuse") { record ->
                val executor = Executors.newSingleThreadExecutor()
                val started = CountDownLatch(1)
                try {
                    val pending = executor.submit<JSONObject> { started.countDown(); web.read(huawei) }
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    Thread.sleep(200)
                    web.cancel()
                    val cancelled = pending.get(5, TimeUnit.SECONDS)
                    record.put("cancelled_result", cancelled)
                    assertEquals("cancelled", cancelled.optJSONObject("error")?.optString("code"))
                    assertTrue(isExampleBody(read(web, "https://example.org/", record, "after-cancel")))
                } finally { web.cancel(); executor.shutdownNow() }
            }
            stage("service_recreated_three_times") { record ->
                val cycles = JSONArray(); record.put("cycles", cycles)
                var completed = 0
                repeat(3) { index ->
                    awaitReaderStopped()
                    val reader = AndroidWebResearch(context)
                    val cycle = JSONObject().put("cycle", index + 1); cycles.put(cycle)
                    try {
                        assertTrue(isExampleBody(read(reader, "https://example.com/", cycle, "service-cycle-${index + 1}")))
                        cycle.put("ok", true); completed++
                    } catch (failure: Throwable) {
                        cycle.put("ok", false).put("failure", failure.message?.take(300).orEmpty())
                    } finally { reader.cancel() }
                }
                awaitReaderStopped()
                assertEquals("All three recreated Reader services must return the real page", 3, completed)
            }
            stage("private_urls_rejected") { record ->
                val cases = JSONArray(); record.put("cases", cases)
                for (url in listOf("https://127.0.0.1/", "https://192.168.1.1/", "https://169.254.169.254/", "http://example.org/")) {
                    val result = web.read(url); cases.put(JSONObject().put("url", url).put("result", result))
                    assertEquals("unsafe_url", result.optJSONObject("error")?.optString("code"))
                }
            }
        } finally {
            web.cancel()
            val taskAfter = if (taskFile.isFile) taskFile.readBytes() else null
            val unchanged = if (taskBefore == null) taskAfter == null else taskAfter != null && taskBefore.contentEquals(taskAfter)
            if (!unchanged) failures += "task_state_changed"
            evidence.put("finished_at_ms", System.currentTimeMillis()).put("task_state_unchanged", unchanged)
                .put("ok", failures.isEmpty()).put("failed_stages", JSONArray(failures))
            File(evidenceDir, "results.json").writeText(evidence.toString(2))
        }
        assertTrue("Local Reader failed stages: ${failures.joinToString()}; inspect external-files/local-reader-device-test/results.json", failures.isEmpty())
    }

    @Test fun cancellingOneReaderDoesNotCancelAnIndependentReader() {
        assumeTrue("Opt in with -e local_reader_live true", InstrumentationRegistry.getArguments().getString("local_reader_live") == "true")
        val taskFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val taskBefore = if (taskFile.isFile) taskFile.readBytes() else null
        val runs = SplitTaskEngine.readPersistedRuns(taskBefore?.toString(Charsets.UTF_8) ?: "[]")
        for (index in 0 until runs.length()) assertTrue("Reader QA requires idle or paused tasks",
            runs.getJSONObject(index).optString("status") in setOf("paused", "completed", "failed", "cancelled"))
        val first = AndroidWebResearch(context)
        val second = AndroidWebResearch(context)
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(2)
        val evidence = JSONObject().put("started_at_ms", System.currentTimeMillis()).put("model_calls_requested", 0).put("ok", false)
            .put("public_dns_answers", publicDnsAnswers())
        try {
            val a = executor.submit<JSONObject> { started.countDown(); first.read("https://example.com/") }
            val b = executor.submit<JSONObject> { started.countDown(); second.read("https://example.org/") }
            assertTrue(started.await(3, TimeUnit.SECONDS)); Thread.sleep(100)
            first.cancel()
            val cancelled = a.get(5, TimeUnit.SECONDS)
            val retained = b.get(30, TimeUnit.SECONDS)
            evidence.put("cancelled_result", cancelled).put("independent_result", retained)
            assertEquals("cancelled", cancelled.optJSONObject("error")?.optString("code"))
            assertReference(retained)
            assertTrue("Other reader must retain its own service request", isExampleBody(retained.getString("text")))
            evidence.put("ok", true)
        } finally {
            first.cancel(); second.cancel(); executor.shutdownNow()
            val taskAfter = if (taskFile.isFile) taskFile.readBytes() else null
            val unchanged = if (taskBefore == null) taskAfter == null else taskAfter != null && taskBefore.contentEquals(taskAfter)
            evidence.put("finished_at_ms", System.currentTimeMillis()).put("task_state_unchanged", unchanged)
            File(context.getExternalFilesDir(null), "local-reader-concurrency.json").writeText(evidence.toString(2))
            assertTrue("Concurrent reader tests must not alter task state", unchanged)
        }
    }

    private fun assertReference(result: JSONObject) {
        assertTrue("Reader request failed: ${result.optJSONObject("error")?.optString("code")}", result.optBoolean("ok"))
        assertTrue(result.getBoolean("untrusted"))
        assertEquals("reference_only", result.getString("content_role"))
        assertEquals("jina-ai/reader-local", result.getString("provider"))
    }

    private fun isExampleBody(text: String) = text.contains("Example Domain") &&
        (text.contains("documentation examples") || text.contains("illustrative examples"))

    private fun publicDnsAnswers() = JSONObject().apply {
        for (host in listOf("example.com", "example.org")) {
            try { put(host, JSONArray(InetAddress.getAllByName(host).map { it.hostAddress })) }
            catch (error: Exception) { put(host, JSONObject().put("error", error.javaClass.simpleName)) }
        }
    }

    private fun awaitReaderStopped() {
        instrumentation.waitForIdleSync()
        context.stopService(Intent(context, ReaderService::class.java))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (context.getSystemService(ActivityManager::class.java).getRunningServices(50).any { it.service.className == ReaderService::class.java.name }) {
            assertTrue("Reader service must stop after the last request unbinds", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
