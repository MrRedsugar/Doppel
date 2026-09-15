package dev.doppel.sdk

import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidWebResearchTest {
    private fun page(url: HttpUrl, html: String, type: String = "text/html") =
        WebResearchPage(url, html.toByteArray(), type)
    private fun error(result: JSONObject) = result.getJSONObject("error").getString("code")
    private fun article(text: String = "公开参考内容，部署时需要根据实际屏幕确认格子与朝向。".repeat(8)) =
        "<html><head><title>部署参考</title></head><body><article><h1>部署</h1><p>$text</p></article></body></html>"

    private class FakeTransport(val responder: (HttpUrl, Int) -> WebResearchPage) : WebResearchTransport {
        val visited = mutableListOf<HttpUrl>()
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

    @Test fun dangerousHttpRedirectIsRejectedBeforeNextRequest() {
        listOf("https://127.0.0.1/secret", "https://169.254.169.254/", "http://example.org/plain", "https://user:pass@example.org/")
            .forEach { destination ->
                val transport = FakeTransport { url, _ -> WebResearchPage(url, byteArrayOf(), redirect = destination) }
                val result = AndroidWebResearch(transport).read("https://example.org/start")
                assertFalse(result.getBoolean("ok"))
                assertEquals("unsafe_url", error(result))
                assertEquals(1, transport.visited.size)
            }
    }

    @Test fun metaRefreshResolvesToAttributedSourceWithoutExecutingScript() {
        val transport = FakeTransport { url, count ->
            if (count == 1) page(url, "<script>window.location='https://127.0.0.1/';</script>" +
                "<noscript><meta http-equiv='refresh' content=\"0;URL='https://guide.example.org/real'\"></noscript>")
            else page(url, article())
        }
        val result = AndroidWebResearch(transport).read("https://www.sogou.com/link?url=fixture")
        assertTrue(result.getBoolean("ok"))
        assertEquals("https://guide.example.org/real", result.getString("url"))
        assertEquals("guide.example.org", result.getString("source"))
        assertTrue(result.getBoolean("untrusted"))
        assertEquals("reference_only", result.getString("content_role"))
        assertEquals(2, transport.visited.size)
    }

    @Test fun privateMetaRefreshCannotReachAnotherHost() {
        val transport = FakeTransport { url, _ -> page(url, "<meta http-equiv='refresh' content='0;url=https://192.168.1.2/'>") }
        assertEquals("unsafe_url", error(AndroidWebResearch(transport).read("https://example.org/start")))
        assertEquals(1, transport.visited.size)
    }

    @Test fun loopingAndExcessiveRedirectsAreBounded() {
        val loop = FakeTransport { url, _ -> WebResearchPage(url, byteArrayOf(), redirect = "/start") }
        assertEquals("redirect_loop", error(AndroidWebResearch(loop).read("https://example.org/start")))
        assertEquals(1, loop.visited.size)
        val chain = FakeTransport { url, count -> WebResearchPage(url, byteArrayOf(), redirect = "/hop$count") }
        assertEquals("too_many_redirects", error(AndroidWebResearch(chain).read("https://example.org/start")))
        assertEquals(5, chain.visited.size)
    }

    @Test fun searchExtractsSmallAttributedResultsAndPrefersPublishedSourceUrl() {
        val resultsHtml = (0..9).joinToString("") {
            "<li class='res-list'><h3 class='res-title'><a href='/link?id=$it' data-mdurl='https://guide.example.org/$it'>" +
                "手机 WPS <em>排序</em> $it</a></h3><p class='res-desc'>选中数据列，打开工具和数据，选择升序。${"摘要".repeat(300)}</p>" +
                "<p class='g-linkinfo'><a class='g-linkinfo-a'>guide.example.org</a></p></li>"
        }
        val transport = FakeTransport { url, _ -> page(url, "<html><body><ul>$resultsHtml</ul></body></html>") }
        val result = AndroidWebResearch(transport).search("WPS 手机 排序 & 隐藏=true")
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
        assertEquals(450, first.getString("snippet").length)
        assertTrue(result.getBoolean("untrusted"))
    }

    @Test fun fallbackReadsSogouResultStructureAndDoesNotInventMissingSource() {
        val transport = FakeTransport { url, _ ->
            if (url.host == "www.so.com") throw IOException("unavailable")
            page(url, "<div class='vrwrap'><div class='struct201102'><h3 class='vr-title'>" +
                "<a href='/link?url=published'>明日方舟干员部署时如何选择方向朝向</a></h3>" +
                "<div class='fz-mid' id='cacheresult_summary_3'>拖动干员到格子后选择面朝方向。</div>" +
                "<a class='citeLinkClass'>百度经验 https://jingyan.baidu.com/a...</a></div></div>")
        }
        val result = AndroidWebResearch(transport).search("明日方舟 部署 方向")
        assertTrue(result.getBoolean("ok"))
        assertEquals("sogou", result.getString("provider"))
        val first = result.getJSONArray("results").getJSONObject(0)
        assertEquals("https://www.sogou.com/link?url=published", first.getString("url"))
        assertTrue(first.getString("source").contains("百度经验"))
        assertEquals("拖动干员到格子后选择面朝方向。", first.getString("snippet"))
    }

    @Test fun privateSearchDestinationsAndDuplicateResultsAreExcluded() {
        val body = listOf("https://127.0.0.1/", "http://example.org/", "https://ai.so.com/generated", "https://guide.example.org/a", "https://guide.example.org/a")
            .joinToString("") { "<li class='res-list'><h3 class='res-title'><a href='$it'>参考</a></h3><p class='res-desc'>页面摘要</p></li>" }
        val transport = FakeTransport { url, _ -> page(url, body) }
        assertEquals(1, AndroidWebResearch(transport).search("参考操作").getJSONArray("results").length())
    }

    @Test fun emptySearchAndVerificationPagesReturnTruthfulRecoverableErrors() {
        val empty = FakeTransport { url, _ -> page(url, "<html><title>搜索</title><body>没有找到结果</body></html>") }
        val result = AndroidWebResearch(empty).search("不存在的操作")
        assertEquals("no_search_results", error(result))
        assertTrue(result.getBoolean("recoverable"))
        assertFalse(result.has("results"))
        val blocked = FakeTransport { url, _ -> page(url, "<html><title>安全验证</title><body>请先进行人机验证</body></html>") }
        assertEquals("verification_required", error(AndroidWebResearch(blocked).read("https://example.org/")))
    }

    @Test fun readRemovesNavigationAndScriptAndBoundsArticleText() {
        val transport = FakeTransport { url, _ -> page(url,
            "<html><title>操作教程</title><body><nav>导航噪声</nav><script>fetch('https://127.0.0.1/')</script>" +
                "<article><p>${"正确操作参考。".repeat(2000)}</p></article><footer>页脚噪声</footer></body></html>") }
        val result = AndroidWebResearch(transport).read("https://example.org/guide")
        assertTrue(result.getBoolean("ok"))
        assertEquals(10000, result.getString("text").length)
        assertTrue(result.getBoolean("truncated"))
        assertFalse(result.getString("text").contains("噪声"))
        assertFalse(result.getString("text").contains("fetch"))
    }

    @Test fun binaryOversizedAndEmptyBodyAreNotReturnedAsUsefulText() {
        val binary = FakeTransport { url, _ -> page(url, "fake PDF", "application/pdf") }
        assertEquals("unsupported_content", error(AndroidWebResearch(binary).read("https://example.org/file")))
        val huge = FakeTransport { url, _ -> WebResearchPage(url, ByteArray(WebResearchLimits.MAX_BYTES + 1)) }
        assertEquals("response_too_large", error(AndroidWebResearch(huge).read("https://example.org/huge")))
        val small = FakeTransport { url, _ -> page(url, "<article>请登录</article>") }
        assertEquals("insufficient_content", error(AndroidWebResearch(small).read("https://example.org/login")))
    }

    @Test fun malformedQueryDoesNotTriggerNetwork() {
        val transport = FakeTransport { _, _ -> throw AssertionError("must not fetch") }
        val web = AndroidWebResearch(transport)
        listOf("", "  ", "x".repeat(181), "query\ncredentials").forEach {
            assertEquals("invalid_query", error(web.search(it)))
        }
        assertTrue(transport.visited.isEmpty())
    }

    @Test fun staleRuntimeWorkCannotStartTransportAndTheInstanceRemainsReusable() {
        val transport = FakeTransport { url, _ -> page(url, article()) }
        val web = AndroidWebResearch(transport)
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
        val result = AndroidWebResearch(transport).read("https://example.org/first") { current }
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
                return page(url, article())
            }
            override fun cancel() { released.countDown() }
        }
        val web = AndroidWebResearch(transport)
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
        val web = AndroidWebResearch(transport)
        try {
            val pending = executor.submit<JSONObject> { web.read("https://guide.example.org/") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            web.cancel()
            assertEquals("cancelled", error(pending.get(3, TimeUnit.SECONDS)))
            assertTrue(interrupted.await(3, TimeUnit.SECONDS))
        } finally { web.cancel(); executor.shutdownNow() }
    }
}
