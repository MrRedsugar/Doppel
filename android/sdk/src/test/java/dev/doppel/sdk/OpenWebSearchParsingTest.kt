package dev.doppel.sdk

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class OpenWebSearchParsingTest {
    private fun parse(html: String, provider: String = "bing") = OpenWebSearchParsing.search(
        WebResearchPage("https://www.example.com/search".toHttpUrl(), html.toByteArray()), provider)
    private fun wrap(url: String) = "https://www.bing.com/ck/a?u=a1" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())
    private fun bingCard(url: String, title: String = "公开资料", snippet: String = "参考正文") =
        "<li class='b_algo'><h2><a href='$url'>$title</a></h2><div class='b_caption'><p>$snippet</p></div></li>"

    @Test fun bingUsesPublishedSelectorsUnwrapsSourcesAndBoundsResults() {
        val target = "https://developer.android.com/reference?utm_source=search&mode=window"
        val html = "<ol id='b_results'>" + bingCard(wrap(target), "标题".repeat(100), "摘要".repeat(400)) +
            bingCard(target, "重复") + "<li class='b_ad'><div class='b_algo'><h2><a href='https://advertiser.example.com/'>广告</a></h2></div></li>" +
            (1..9).joinToString("") { bingCard("https://docs.example.com/$it") } + "</ol>"
        val results = parse(html)
        assertEquals(6, results.length())
        val first = results.getJSONObject(0)
        assertEquals("https://developer.android.com/reference?mode=window", first.getString("url"))
        assertEquals("developer.android.com", first.getString("source"))
        assertEquals(160, first.getString("title").length)
        assertEquals(400, first.getString("snippet").length)
        assertFalse(results.toString().contains("advertiser"))
        assertFalse(results.toString().contains("重复"))
    }

    @Test fun bingPreservesAlternateCardsAndLinkFallback() {
        val alternative = parse("<div id='b_topw'><li class='b_ans'><div class='b_tpcn'><span class='tptt'>接口说明</span></div>" +
            "<a class='tilk' redirecturl='https://guide.example.com/window'></a><div class='b_snippet'>实际正文</div></li></div>")
        assertEquals("接口说明", alternative.getJSONObject(0).getString("title"))
        assertEquals("实际正文", alternative.getJSONObject(0).getString("snippet"))
        val fallback = parse("<div id='b_results'><li><a href='https://guide.example.com/fallback'>后备正文</a></li></div>")
        assertEquals("后备正文", fallback.getJSONObject(0).getString("title"))
        assertEquals("Result from guide.example.com", fallback.getJSONObject(0).getString("snippet"))
    }

    @Test fun bingRejectsPrivateMalformedAndExcessivelyWrappedTargets() {
        var nested = "https://guide.example.com/deep"
        repeat(5) { nested = wrap(nested) }
        val unsafe = listOf(wrap("https://127.0.0.1/secret"), wrap("http://public.example.com/"),
            "https://www.bing.com/ck/a?u=a1invalid%", "/ck/a?u=a1invalid", nested,
            "https://user:pass@guide.example.com/", "https://www.bing.com/search?q=another")
        assertEquals(0, parse(unsafe.joinToString("") { bingCard(it) }).length())
        val unrelatedHost = "https://notbing.com/ck/a?u=ordinary"
        assertEquals(unrelatedHost, parse(bingCard(unrelatedHost)).getJSONObject(0).getString("url"))
    }

    @Test fun sogouUsesPublishedContainersAndAllWrappedUrlParameters() {
        val target = "https://guide.example.com/intro"
        val encoded = URLEncoder.encode(target, "UTF-8")
        val html = "<main id='main'>" + listOf("url", "u", "link").joinToString("") {
            "<div class='vrwrap'><h3><a href='/link?$it=$encoded'>原始教程</a></h3><div class='str_info'>操作说明</div><cite>公开来源</cite></div>"
        } + "<div class='rb'><h2><a href='https://guide.example.com/security'>人机验证原理</a></h2><p>介绍安全验证的文章</p></div></main>" +
            "<div class='vrwrap'><h3><a href='https://unrelated.example.com/'>容器之外</a></h3></div>"
        val results = parse(html, "sogou")
        assertEquals(2, results.length())
        assertEquals(target, results.getJSONObject(0).getString("url"))
        assertEquals("操作说明", results.getJSONObject(0).getString("snippet"))
        assertEquals("公开来源", results.getJSONObject(0).getString("source"))
        assertEquals("人机验证原理", results.getJSONObject(1).getString("title"))
    }

    @Test fun emptyPagesStayEmptyAndSogouCannotPublishPrivateTargets() {
        assertEquals(0, parse("<title>安全验证</title><p>请确认</p>").length())
        assertEquals(0, parse("<title>搜索</title><p>无搜索结果</p>", "sogou").length())
        val html = "<div id='results'><div class='vrwrap'><h3><a href='/link?url=https%3A%2F%2F192.168.1.1%2F'>私网</a></h3></div></div>"
        assertEquals(0, parse(html, "sogou").length())
    }
}
