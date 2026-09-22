package dev.doppel.sdk

import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidWebResearchTest {
    @org.junit.Test fun parallelPageResourcesWaitForBoundedDnsInsteadOfBeingRejectedAsBusy() {
        val workers = java.util.concurrent.Executors.newFixedThreadPool(3)
        val ready = java.util.concurrent.CountDownLatch(3)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val reads = (1..3).map {
                workers.submit<String> {
                    val transport = PublicWebTransport(resolve = {
                        Thread.sleep(150)
                        listOf(java.net.InetAddress.getByName("127.0.0.1"))
                    })
                    ready.countDown(); go.await()
                    try {
                        transport.fetchResource(WebResearchUrls.parse("https://example.com/"),
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)) {}
                        "unexpected_network_connection"
                    } catch (error: WebResearchFailure) { error.code }
                    finally { transport.cancel() }
                }
            }
            org.junit.Assert.assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS)); go.countDown()
            reads.forEach { org.junit.Assert.assertEquals("unsafe_dns", it.get(4, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { go.countDown(); workers.shutdownNow() }
    }
    private fun configured(transport: WebResearchTransport) = AndroidWebResearch(transport, object : WebPageReader {
        override fun read(url: HttpUrl, format: String, deadline: Long, check: () -> Unit): WebResearchPage {
            (transport as? FakeTransport)?.formats?.add(format)
            return transport.fetch(url, deadline, check)
        }
        override fun cancel() = Unit
    })
    private fun page(url: HttpUrl, html: String, type: String = "text/html") =
        WebResearchPage(url, html.toByteArray(), type)
    private fun error(result: JSONObject) = result.getJSONObject("error").getString("code")
    private fun reader(url: HttpUrl, text: String = "公开参考内容，部署时需要根据实际屏幕确认格子与朝向。".repeat(8)) =
        page(url, "Title: 部署参考\n\nURL Source: $url\n\nMarkdown Content:\n$text", "text/plain; charset=utf-8")
    private fun readArgs(url: String = "https://example.org/guide", operation: String = "read", offset: Int = 0, limit: Int = 4000) =
        JSONObject().put("url", url).put("operation", operation).put("offset", offset).put("limit", limit)

    private class FakeTransport(val responder: (HttpUrl, Int) -> WebResearchPage) : WebResearchTransport {
        val visited = mutableListOf<HttpUrl>()
        val formats = mutableListOf<String?>()
        var cancellations = 0
        override fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage {
            check()
            visited += url
            return responder(url, visited.size)
        }
        override fun cancel() { cancellations++ }
    }

    @Test fun onlyPublicHttpsUrlsWithoutCredentialsAreAccepted() {
        assertEquals("https://example.org/guide", WebResearchUrls.parse("https://example.org/guide#chapter").toString())
        listOf("http://example.org", "file:///etc/passwd", "https://user:secret@example.org", "https://example.org:444/",
            "https://localhost/", "https://local/", "https://server.internal/", "https://device.lan/", "https://x.local/",
            "https://example.org\\@127.0.0.1/", "https://example.org/\n", "https://2130706433/", "https://127.0.0.1/")
            .forEach { raw -> assertThrows(raw, WebResearchFailure::class.java) { WebResearchUrls.parse(raw) } }
    }

    @Test fun privateReservedAndTransitionAddressesAreBlocked() {
        listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.5", "192.88.99.1",
            "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
            "2001:db8::1", "2001::1", "2001:20::1", "2002:7f00:1::", "3ffe::1", "3fff::1")
            .forEach { assertFalse(it, WebResearchUrls.isPublic(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "1.1.1.1", "223.5.5.5", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888", "2606:4700:4700::1111")
            .forEach { assertTrue(it, WebResearchUrls.isPublic(InetAddress.getByName(it))) }
    }

    @Test fun onePrivateDnsAnswerRejectsTheEntireResolution() {
        val public = InetAddress.getByName("8.8.8.8")
        assertEquals(listOf(public), WebResearchUrls.validateAnswers(listOf(public)))
        val failure = assertThrows(WebResearchFailure::class.java) {
            WebResearchUrls.validateAnswers(listOf(public, InetAddress.getByName("192.168.0.1")))
        }
        assertEquals("unsafe_dns", failure.code)
        assertThrows(WebResearchFailure::class.java) { WebResearchUrls.validateAnswers(emptyList()) }
    }

    @Test fun everyReaderRedirectIsRejectedBeforeNextRequest() {
        listOf("https://127.0.0.1/secret", "https://169.254.169.254/", "http://example.org/plain", "https://user:pass@example.org/", "/same-origin")
            .forEach { destination ->
                val transport = FakeTransport { url, _ -> WebResearchPage(url, byteArrayOf(), redirect = destination) }
                val result = configured(transport).read("https://example.org/start")
                assertFalse(result.getBoolean("ok"))
                assertEquals("reader_response_invalid", error(result))
                assertEquals(1, transport.visited.size)
            }
    }

    @Test fun readerHtmlCannotExecuteScriptOrFollowMetaRefresh() {
        val transport = FakeTransport { url, count ->
            if (count == 1) page(url, "<script>window.location='https://127.0.0.1/';</script>" +
                "<noscript><meta http-equiv='refresh' content=\"0;URL='https://guide.example.org/real'\"></noscript>")
            else reader(url)
        }
        val result = configured(transport).read("https://www.sogou.com/link?url=fixture")
        assertEquals("reader_response_invalid", error(result))
        assertEquals("https://www.sogou.com/link?url=fixture", transport.visited[0].toString())
        assertTrue(result.getBoolean("untrusted"))
        assertEquals(1, transport.visited.size)
    }

    @Test fun privateMetaRefreshCannotReachAnotherHost() {
        val transport = FakeTransport { url, _ -> page(url, "<meta http-equiv='refresh' content='0;url=https://192.168.1.2/'>") }
        assertEquals("reader_response_invalid", error(configured(transport).read("https://example.org/start")))
        assertEquals(1, transport.visited.size)
    }

    @Test fun loopingAndExcessiveRedirectsAreBounded() {
        val loop = FakeTransport { url, _ -> WebResearchPage(url, byteArrayOf(), redirect = url.toString()) }
        assertEquals("redirect_loop", error(configured(loop).search("reference")))
        assertEquals(2, loop.visited.size)
        val chain = FakeTransport { url, count -> WebResearchPage(url, byteArrayOf(), redirect = "/hop$count") }
        assertEquals("too_many_redirects", error(configured(chain).search("reference")))
        assertEquals(10, chain.visited.size)
    }

    @Test fun searchReturnsBoundedReferencesFromTheReusedParser() {
        val resultsHtml = (0..9).joinToString("") {
            "<li class='b_algo'><h2><a href='https://guide.example.org/$it'>" +
                "手机 WPS <em>排序</em> $it</a></h2><div class='b_caption'><p>选中数据列，打开工具和数据，选择升序。${"摘要".repeat(300)}</p></div>" +
                "<cite>guide.example.org</cite></li>"
        }
        val transport = FakeTransport { url, _ -> page(url, "<html><body><ul>$resultsHtml</ul></body></html>") }
        val result = configured(transport).search("WPS 手机 排序 & 隐藏=true")
        assertTrue(result.getBoolean("ok"))
        assertEquals(1, transport.visited.size)
        assertEquals("WPS 手机 排序 & 隐藏=true", transport.visited[0].queryParameter("q"))
        assertNull(transport.visited[0].queryParameter("隐藏"))
        val entries = result.getJSONArray("results")
        assertEquals(6, entries.length())
        val first = entries.getJSONObject(0)
        assertEquals("手机 WPS 排序 0", first.getString("title"))
        assertEquals("https://guide.example.org/0", first.getString("url"))
        assertTrue(first.getString("snippet").startsWith("选中数据列"))
        assertEquals(400, first.getString("snippet").length)
        assertTrue(result.getBoolean("untrusted"))
    }

    @Test fun fallbackReadsSogouResultStructureAndDoesNotInventMissingSource() {
        val transport = FakeTransport { url, _ ->
            if (url.host == "cn.bing.com") throw IOException("unavailable")
            page(url, "<div id='main'><div class='vrwrap'><div class='struct201102'><h3 class='vr-title'>" +
                "<a href='/link?url=published'>明日方舟干员部署时如何选择方向朝向</a></h3>" +
                "<div class='fz-mid' id='cacheresult_summary_3'>拖动干员到格子后选择面朝方向。</div>" +
                "<cite>百度经验 https://jingyan.baidu.com/a...</cite></div></div></div>")
        }
        val result = configured(transport).search("明日方舟 部署 方向")
        assertTrue(result.getBoolean("ok"))
        assertEquals("sogou", result.getString("provider"))
        val first = result.getJSONArray("results").getJSONObject(0)
        assertEquals("https://www.sogou.com/link?url=published", first.getString("url"))
        assertTrue(first.getString("source").contains("百度经验"))
        assertEquals("拖动干员到格子后选择面朝方向。", first.getString("snippet"))
    }

    @Test fun privateSearchDestinationsAndDuplicateResultsAreExcluded() {
        val body = listOf("https://127.0.0.1/", "http://example.org/", "https://cn.bing.com/search?q=internal", "https://guide.example.org/a", "https://guide.example.org/a")
            .joinToString("") { "<li class='b_algo'><h2><a href='$it'>参考</a></h2><div class='b_caption'><p>页面摘要</p></div></li>" }
        val transport = FakeTransport { url, _ -> page(url, body) }
        assertEquals(1, configured(transport).search("参考操作").getJSONArray("results").length())
    }

    @Test fun emptySearchReturnsARecoverableErrorAndReaderWarningsRemainVisibleForModelJudgment() {
        val empty = FakeTransport { url, _ -> page(url, "<html><title>搜索</title><body>没有找到结果</body></html>") }
        val result = configured(empty).search("不存在的操作")
        assertEquals("no_search_results", error(result))
        assertTrue(result.getBoolean("recoverable"))
        assertFalse(result.has("results"))
        val blocked = FakeTransport { url, _ -> page(url, "<html><title>安全验证</title><body>请先进行人机验证</body></html>") }
        assertEquals("no_search_results", error(configured(blocked).search("参考")))
        val warning = "Warning: Target URL returned error 403: Forbidden\n\nMarkdown Content:\n安全验证：请先进行人机验证。CAPTCHA"
        val warned = FakeTransport { url, _ -> page(url, "Title: 安全验证\n\n$warning", "text/plain") }
        val reference = configured(warned).read("https://example.org/")
        assertTrue(reference.getBoolean("ok"))
        assertEquals("安全验证", reference.getString("title"))
        assertTrue(reference.getString("text").contains(warning))
        assertTrue(reference.getBoolean("untrusted"))
        assertEquals("reference_only", reference.getString("content_role"))
        assertFalse(reference.has("error"))
    }

    @Test fun readUsesConfiguredReaderAndBoundsItsMarkdownWithoutReplacingItsSource() {
        val transport = FakeTransport { url, _ -> reader(url, "# 正文\n\n${"正确操作参考。".repeat(2000)}") }
        val result = configured(transport).read("https://example.org/guide")
        assertTrue(result.getBoolean("ok"))
        assertEquals("https://example.org/guide", transport.visited.single().toString())
        assertEquals(listOf("markdown"), transport.formats)
        assertEquals("jina-ai/reader-local", result.getString("provider"))
        assertEquals("https://example.org/guide", result.getString("url"))
        assertEquals("部署参考", result.getString("title"))
        assertEquals(10000, result.getString("text").length)
        assertTrue(result.getBoolean("truncated"))
        assertTrue(result.getBoolean("has_more"))
        assertTrue(result.getString("text").contains("Markdown Content:\n# 正文"))
    }

    @Test fun binaryOversizedAndEmptyBodyAreNotReturnedAsUsefulText() {
        val binary = FakeTransport { url, _ -> page(url, "fake PDF", "application/pdf") }
        assertEquals("reader_response_invalid", error(configured(binary).read("https://example.org/file")))
        val huge = FakeTransport { url, _ -> WebResearchPage(url, ByteArray(WebResearchLimits.MAX_BYTES + 1)) }
        assertEquals("response_too_large", error(configured(huge).read("https://example.org/huge")))
        val empty = FakeTransport { url, _ -> page(url, "  ", "text/plain") }
        assertEquals("empty_page", error(configured(empty).read("https://example.org/login")))
        val html = FakeTransport { url, _ -> page(url, "<article>reader gateway failed</article>") }
        assertEquals("reader_response_invalid", error(configured(html).read("https://example.org/html")))
    }

    @Test fun malformedQueryDoesNotTriggerNetwork() {
        val transport = FakeTransport { _, _ -> throw AssertionError("must not fetch") }
        val web = configured(transport)
        listOf("", "  ", "x".repeat(181), "query\ncredentials").forEach {
            assertEquals("invalid_query", error(web.search(it)))
        }
        assertTrue(transport.visited.isEmpty())
    }

    @Test fun infoSearchAndRangeReadsReuseOneSnapshotAndCanReachTextAfterTheFirstPage() {
        val raw = "Title: 长文章\n\nMarkdown Content:\n" + "前文".repeat(6000) + "费用[含税] 42元\n" +
            "说明".repeat(200) + "费用[含税] 63元\n结束"
        val transport = FakeTransport { url, _ -> page(url, raw, "text/plain") }
        val web = configured(transport)
        val info = web.readPage(readArgs("https://example.org/guide#first", "info"))
        assertEquals(raw.length, info.getInt("total_chars")); assertFalse(info.has("text"))
        val first = web.readPage(JSONObject().put("url", "https://example.org/guide"))
        assertEquals(raw.take(4000), first.getString("text")); assertEquals(4000, first.getInt("next_offset"))
        assertTrue(first.getBoolean("has_more")); assertFalse(first.getBoolean("source_truncated"))
        val search = web.readPage(readArgs(operation = "search", limit = 1).put("query", "费用[含税]"))
        val found = search.getJSONArray("hits").getJSONObject(0)
        assertEquals(raw.indexOf("费用[含税]"), found.getInt("offset"))
        assertTrue(found.getString("snippet").contains("42元")); assertTrue(search.getBoolean("has_more"))
        val next = web.readPage(readArgs(operation = "search", offset = search.getInt("next_offset"), limit = 1).put("query", "费用[含税]"))
        assertEquals(raw.lastIndexOf("费用[含税]"), next.getJSONArray("hits").getJSONObject(0).getInt("offset"))
        assertFalse(next.getBoolean("has_more"))
        val excerpt = web.readPage(readArgs(offset = found.getInt("offset"), limit = 10))
        assertEquals("费用[含税] 42元", excerpt.getString("text"))
        val end = web.readPage(readArgs(offset = raw.length))
        assertEquals("", end.getString("text")); assertFalse(end.getBoolean("has_more"))
        assertEquals("invalid_range", error(web.readPage(readArgs(offset = raw.length + 1))))
        assertEquals("cancelled", error(web.readPage(readArgs()) { false }))
        assertEquals(1, transport.visited.size)
    }

    @Test fun invalidPaginationAndPrivateSourceUrlsCannotReachReaderTransport() {
        val transport = FakeTransport { _, _ -> throw AssertionError("invalid input must not fetch") }
        val web = configured(transport)
        for (args in listOf(readArgs(offset = -1), readArgs(limit = 0), readArgs(limit = 10001),
            readArgs().put("offset", 0.5), readArgs().put("limit", "4000")))
            assertEquals("invalid_range", error(web.readPage(args)))
        assertEquals("invalid_operation", error(web.readPage(readArgs(operation = "execute"))))
        for (query in listOf("", "   ", "x".repeat(101)))
            assertEquals("invalid_query", error(web.readPage(readArgs(operation = "search").put("query", query))))
        for (url in listOf("https://127.0.0.1/", "https://169.254.169.254/", "https://user:secret@example.org/", "file:///secret"))
            assertEquals("unsafe_url", error(web.readPage(readArgs(url))))
        assertTrue(transport.visited.isEmpty())
    }

    @Test fun taskReadsAlwaysRefreshAndCancellationInvalidatesChatSnapshots() {
        val transport = FakeTransport { url, count -> reader(url, "已读取快照 $count") }
        val web = configured(transport)
        assertTrue(web.readPage(readArgs()).getString("text").contains("快照 1"))
        assertTrue(web.readPage(readArgs()).getString("text").contains("快照 1"))
        assertTrue(web.read("https://example.org/guide").getString("text").contains("快照 2"))
        assertTrue(web.read("https://example.org/guide").getString("text").contains("快照 3"))
        assertTrue(web.readPage(readArgs()).getString("text").contains("快照 3"))
        web.cancel()
        assertEquals("snapshot_expired", error(web.readPage(readArgs(offset = 15))))
        assertEquals("Expired offsets must not silently fetch a different version", 3, transport.visited.size)
        assertTrue(web.readPage(readArgs()).getString("text").contains("快照 4"))
        assertTrue(web.readPage(readArgs()).getString("text").contains("快照 4"))
        assertEquals(4, transport.visited.size)
    }

    @Test fun websiteRequestBodyUsesPublicTransportWithoutForwardingCredentials() {
        val body = "{\"documentId\":123}".toByteArray()
        val request = PublicWebTransport.resourceRequest(WebResearchUrls.parse("https://example.org/api"), "POST",
            mapOf("Content-Type" to "application/json", "Authorization" to "secret", "Cookie" to "session=secret", "Host" to "localhost"), body)
        assertEquals("POST", request.method)
        assertNull(request.header("Authorization")); assertNull(request.header("Cookie")); assertNull(request.header("Host"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        assertArrayEquals(body, buffer.readByteArray())
        for (method in listOf("DELETE", "PUT", "CONNECT")) assertThrows(WebResearchFailure::class.java) {
            PublicWebTransport.resourceRequest(WebResearchUrls.parse("https://example.org/api"), method, emptyMap(), null)
        }
        assertThrows(WebResearchFailure::class.java) {
            PublicWebTransport.resourceRequest(WebResearchUrls.parse("https://example.org/api"), "POST", emptyMap(), ByteArray(128 * 1024 + 1))
        }
    }

    @Test fun unicodePagesAlwaysAdvanceWithoutSplittingCharactersAndSourceCapIsExplicit() {
        val raw = "Title: 表情\n\nMarkdown Content:\na\uD83D\uDE00b\uD83D\uDE01c"
        val transport = FakeTransport { url, _ -> page(url, raw, "text/plain") }
        val web = configured(transport)
        val recovered = StringBuilder()
        var offset = 0
        while (offset < raw.length) {
            val page = web.readPage(readArgs(offset = offset, limit = 1))
            recovered.append(page.getString("text"))
            val next = page.getInt("next_offset")
            assertTrue(next > offset); offset = next
        }
        assertEquals(raw, recovered.toString()); assertEquals(1, transport.visited.size)
        val emoji = raw.indexOf("\uD83D\uDE00")
        val inside = web.readPage(readArgs(offset = emoji + 1, limit = 1))
        assertEquals(emoji, inside.getInt("offset")); assertEquals("\uD83D\uDE00", inside.getString("text"))
        assertEquals(emoji + 2, inside.getInt("next_offset"))

        val prefix = "Title: 大文章\n\nMarkdown Content:\n"
        val large = prefix + "a".repeat(WebResearchLimits.MAX_DOCUMENT_CHARS - prefix.length - 1) + "\uD83D\uDE00tail"
        val capped = configured(FakeTransport { url, _ -> page(url, large, "text/plain") })
            .readPage(readArgs(operation = "info"))
        assertTrue(capped.getBoolean("source_truncated"))
        assertEquals(WebResearchLimits.MAX_DOCUMENT_CHARS - 1, capped.getInt("total_chars"))
    }

    @Test fun staleRuntimeWorkCannotStartTransportAndTheInstanceRemainsReusable() {
        val transport = FakeTransport { url, _ -> reader(url) }
        val web = configured(transport)
        assertEquals("cancelled", error(web.search("already paused") { false }))
        assertEquals("cancelled", error(web.read("https://example.org/stale") { false }))
        assertTrue("Stale work must not send a public request", transport.visited.isEmpty())
        assertTrue(web.read("https://example.org/current") { true }.getBoolean("ok"))
        assertEquals(1, transport.visited.size)
    }

    @Test fun runtimeInvalidationBetweenHopsPreventsAnotherRequest() {
        var current = true
        val transport = FakeTransport { url, _ ->
            current = false
            WebResearchPage(url, byteArrayOf(), redirect = "https://guide.example.org/next")
        }
        val result = configured(transport).read("https://example.org/first") { current }
        assertEquals("cancelled", error(result))
        assertEquals(1, transport.visited.size)
    }

    @Test fun busyCancellationAndLaterReusePreserveOperationIdentity() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val transport = object : WebResearchTransport {
            var calls = 0
            override fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage {
                calls++
                if (calls == 1) { started.countDown(); assertTrue(released.await(3, TimeUnit.SECONDS)); check() }
                return reader(url)
            }
            override fun cancel() { released.countDown() }
        }
        val web = configured(transport)
        try {
            val pending = executor.submit<JSONObject> { web.read("https://example.org/first") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertEquals("busy", error(web.read("https://example.org/second")))
            web.cancel()
            assertEquals("cancelled", error(pending.get(3, TimeUnit.SECONDS)))
            assertTrue(web.read("https://example.org/reused").getBoolean("ok"))
            assertEquals(2, transport.calls)
        } finally { web.cancel(); executor.shutdownNow() }
    }

    @Test fun cancellingDuringDnsInterruptsTheLookupWithoutOpeningASocket() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val transport = PublicWebTransport {
            started.countDown()
            try { CountDownLatch(1).await(); throw AssertionError("lookup cannot return an address") }
            finally { interrupted.countDown() }
        }
        val web = configured(transport)
        try {
            val pending = executor.submit<JSONObject> { web.read("https://guide.example.org/") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            web.cancel()
            assertEquals("cancelled", error(pending.get(3, TimeUnit.SECONDS)))
            assertTrue(interrupted.await(3, TimeUnit.SECONDS))
        } finally { web.cancel(); executor.shutdownNow() }
    }

    @Test fun absentAndroidReaderMakesNoReadRequestAndDoesNotDisableSearch() {
        val transport = FakeTransport { url, _ -> page(url,
            "<li class='b_algo'><h2><a href='https://example.org/a'>Reference</a></h2><p>Useful reference</p></li>") }
        val web = AndroidWebResearch(transport)
        assertEquals("reader_unavailable", error(web.readPage(readArgs())))
        assertEquals("reader_unavailable", error(web.readPage(readArgs(operation = "screenshot"))))
        assertTrue(transport.visited.isEmpty())
        assertTrue(web.search("reference").getBoolean("ok"))
        assertTrue(transport.formats.isEmpty())
    }

    @Test fun binaryResourcesKeepTheSamePrivateAddressAndDnsRestrictionsAsSearch() {
        var resolved = false
        val transport = PublicWebTransport { resolved = true; listOf(InetAddress.getByName("127.0.0.1")) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        for (url in listOf("http://127.0.0.1:3000", "https://192.168.1.1/", "https://169.254.169.254/"))
            assertEquals("unsafe_url", assertThrows(WebResearchFailure::class.java) {
                transport.fetchResource(url.toHttpUrlOrNull()!!, deadline, {})
            }.code)
        assertFalse(resolved)
        assertEquals("unsafe_dns", assertThrows(WebResearchFailure::class.java) {
            transport.fetchResource(WebResearchUrls.parse("https://example.org/image.png"), deadline, {})
        }.code)
        assertTrue(resolved)
    }

    @Test fun localPngReturnsOnlyTransientImageAndReferenceMetadata() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val transport = FakeTransport { url, _ -> WebResearchPage(url, png, "image/png") }
        val result = configured(transport).readPage(readArgs(operation = "screenshot"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("https://example.org/guide", result.getString("url"))
        assertEquals("example.org", result.getString("source"))
        assertEquals("image/png", result.getString("mime"))
        assertEquals(1, result.getInt("width")); assertEquals(1, result.getInt("height"))
        assertTrue(result.getBoolean("reference_only")); assertTrue(result.getBoolean("untrusted"))
        assertFalse(result.has("text"))
        assertArrayEquals(png, Base64.getDecoder().decode(result.getString("_reference_image_data_url").substringAfter(',')))
        assertEquals(listOf("screenshot"), transport.formats)
        assertEquals("https://example.org/guide", transport.visited.single().toString())
    }

    @Test fun malformedOversizedAndRedirectedScreenshotsCannotReturnAnImage() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val variants = listOf(png.copyOf(20), png.copyOf().also { it[0] = 0 }, png.copyOf().also { it[12] = 0 },
            png.copyOf().also { ByteBuffer.wrap(it, 16, 4).putInt(0) },
            png.copyOf().also { ByteBuffer.wrap(it, 16, 4).putInt(16_000_001) })
        for (bytes in variants) {
            val result = configured(FakeTransport { url, _ -> WebResearchPage(url, bytes, "image/png") })
                .readPage(readArgs(operation = "screenshot"))
            assertEquals("reader_response_invalid", error(result)); assertFalse(result.has("_reference_image_data_url"))
        }
        val huge = configured(FakeTransport { url, _ -> WebResearchPage(url, ByteArray(WebResearchLimits.MAX_BYTES + 1), "image/png") })
        assertEquals("response_too_large", error(huge.readPage(readArgs(operation = "screenshot"))))
        val redirect = FakeTransport { url, _ -> WebResearchPage(url, png, "image/png", "https://example.org/signed-image") }
        assertEquals("reader_response_invalid", error(configured(redirect).readPage(readArgs(operation = "screenshot"))))
        assertEquals(1, redirect.visited.size)
    }

    @Test fun onlyFourTextSnapshotsAreRetainedAndScreenshotsAreNeverCached() {
        val transport = FakeTransport { url, _ -> reader(url) }
        val web = configured(transport)
        for (index in 0..4) assertTrue(web.readPage(readArgs("https://example.org/$index")).getBoolean("ok"))
        web.readPage(readArgs("https://example.org/0"))
        assertEquals(6, transport.visited.size)
        web.readPage(readArgs(operation = "screenshot"))
        web.readPage(readArgs(operation = "screenshot"))
        assertEquals(8, transport.visited.size)
    }

    @Test fun cancelStopsTheLocalReaderAndDiscardsItsLateImage() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        val pageReader = object : WebPageReader {
            override fun read(url: HttpUrl, format: String, deadline: Long, check: () -> Unit): WebResearchPage {
                started.countDown(); assertTrue(stopped.await(3, TimeUnit.SECONDS))
                return WebResearchPage(url, png, "image/png")
            }
            override fun cancel() { stopped.countDown() }
        }
        val search = FakeTransport { _, _ -> throw AssertionError("local Reader must not use search transport") }
        val web = AndroidWebResearch(search, pageReader)
        try {
            val pending = executor.submit<JSONObject> { web.readPage(readArgs(operation = "screenshot")) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            web.cancel()
            val result = pending.get(3, TimeUnit.SECONDS)
            assertEquals("cancelled", error(result)); assertFalse(result.has("_reference_image_data_url"))
            assertEquals(1, search.cancellations)
        } finally { web.cancel(); executor.shutdownNow() }
    }
}
