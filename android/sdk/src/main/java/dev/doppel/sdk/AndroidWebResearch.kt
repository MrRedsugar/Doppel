package dev.doppel.sdk

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Public, unauthenticated web references. Call from a worker thread; never from the UI thread.
 * Returned text is untrusted reference data and must not grant device or tool permissions.
 * HTML providers are replaceable and have no availability guarantee. No browser is opened.
 */
class AndroidWebResearch internal constructor(private val transport: WebResearchTransport) {
    constructor() : this(PublicWebTransport())

    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val lifecycle = Any()

    /** Cancels only the current operation. Subsequent search/read calls can reuse this instance. */
    fun cancel() {
        synchronized(lifecycle) {
            generation.incrementAndGet()
            transport.cancel()
        }
    }

    @JvmOverloads
    fun search(query: String, isCurrent: () -> Boolean = { true }): JSONObject = operation(isCurrent) { scope ->
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 180 || clean.any { it.isISOControl() })
            fail("invalid_query", "搜索词需为 1–180 个字符，且不能含控制字符。")
        var lastFailure: WebResearchFailure? = null
        for (provider in listOf("360", "sogou")) {
            scope.check()
            val endpoint = if (provider == "360") "https://www.so.com/s" else "https://www.sogou.com/web"
            val url = WebResearchUrls.parse(endpoint).newBuilder()
                .addQueryParameter(if (provider == "360") "q" else "query", clean).build()
            try {
                val page = fetch(url, scope)
                val results = WebResearchParsing.search(page, provider)
                if (results.length() == 0) fail("no_search_results", "搜索页面未返回可用结果，请换用更具体的关键词。")
                return@operation success().put("query", clean).put("provider", provider)
                    .put("source_url", page.url.toString()).put("results", results)
            } catch (e: WebResearchFailure) {
                if (e.code in setOf("cancelled", "deadline_exceeded")) throw e
                lastFailure = e
            } catch (_: IOException) {
                scope.check()
                lastFailure = WebResearchFailure("network_unavailable", "暂时无法连接公开搜索，请稍后重试。")
            }
        }
        throw lastFailure ?: WebResearchFailure("no_search_results", "没有取得可用搜索结果。")
    }

    @JvmOverloads
    fun read(url: String, isCurrent: () -> Boolean = { true }): JSONObject = operation(isCurrent) { scope ->
        val page = fetch(WebResearchUrls.parse(url), scope)
        val content = WebResearchParsing.read(page)
        success().put("url", page.url.toString()).put("source", page.url.host)
            .put("title", content.title).put("text", content.text).put("truncated", content.truncated)
    }

    private fun fetch(initial: HttpUrl, scope: Scope): WebResearchPage {
        var url = initial
        val visited = mutableSetOf<String>()
        repeat(5) {
            scope.check()
            if (!visited.add(url.toString())) fail("redirect_loop", "网页出现循环跳转。")
            val page = transport.fetch(url, scope.deadline, scope::check)
            scope.check()
            if (page.bytes.size > WebResearchLimits.MAX_BYTES) fail("response_too_large", "网页体积超过读取上限。")
            val redirect = page.redirect ?: WebResearchParsing.metaRedirect(page)
            if (redirect == null) return page
            url = WebResearchUrls.resolve(page.url, redirect)
        }
        fail("too_many_redirects", "网页跳转次数超过上限。")
    }

    private fun operation(isCurrent: () -> Boolean, block: (Scope) -> JSONObject): JSONObject {
        val scope = synchronized(lifecycle) {
            if (!busy.compareAndSet(false, true)) return error("busy", "已有联网读取正在进行。")
            Scope(generation.get(), System.nanoTime() + TimeUnit.SECONDS.toNanos(28), isCurrent)
        }
        return try {
            scope.check()
            block(scope).also { scope.check() }
        } catch (e: WebResearchFailure) {
            error(e.code, e.message ?: "联网资料暂不可用。")
        } catch (_: IOException) {
            if (generation.get() != scope.generation) error("cancelled", "联网读取已取消。")
            else error("network_unavailable", "暂时无法读取公开网页，请稍后重试。")
        } catch (_: Exception) {
            if (generation.get() != scope.generation) error("cancelled", "联网读取已取消。")
            else error("read_failed", "网页无法解析为可读资料。")
        } finally {
            synchronized(lifecycle) { busy.set(false) }
        }
    }

    private inner class Scope(val generation: Long, val deadline: Long, val isCurrent: () -> Boolean) {
        fun check() {
            if (this@AndroidWebResearch.generation.get() != generation || Thread.currentThread().isInterrupted || !isCurrent())
                fail("cancelled", "联网读取已取消。")
            if (System.nanoTime() >= deadline) fail("deadline_exceeded", "联网读取超过时间上限。")
        }
    }

    private fun success() = JSONObject().put("ok", true).put("untrusted", true)
        .put("content_role", "reference_only")
    private fun error(code: String, message: String) = JSONObject().put("ok", false).put("untrusted", true)
        .put("recoverable", true).put("error", JSONObject().put("code", code).put("message", message))
}

internal object WebResearchLimits {
    const val MAX_BYTES = 1_048_576
    const val MAX_TEXT = 10_000
    const val MAX_RESULTS = 6
}

internal class WebResearchFailure(val code: String, message: String) : IOException(message)
private fun fail(code: String, message: String): Nothing = throw WebResearchFailure(code, message)

internal object WebResearchUrls {
    fun parse(raw: String): HttpUrl {
        if (raw.length > 4096 || raw.any { it.isWhitespace() || it.isISOControl() } || '\\' in raw)
            fail("unsafe_url", "仅能读取不带凭据的公开 HTTPS 网页。")
        val url = raw.toHttpUrlOrNull() ?: fail("unsafe_url", "网址格式无效。")
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty())
            fail("unsafe_url", "仅能读取不带凭据的公开 HTTPS 网页。")
        val host = url.host.lowercase().trimEnd('.')
        if (host.isEmpty() || ('.' !in host && ':' !in host) || '%' in host ||
            listOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "onion", "arpa")
                .any { host == it || host.endsWith(".$it") })
            fail("unsafe_url", "不能读取本机、局域网或保留网络地址。")
        if (':' in host || host.all { it.isDigit() || it == '.' }) {
            val address = try { InetAddress.getByName(host) } catch (_: Exception) {
                fail("unsafe_url", "IP 地址格式无效。")
            }
            if (!isPublic(address)) fail("unsafe_url", "不能读取本机、局域网或保留网络地址。")
        }
        return url.newBuilder().fragment(null).build()
    }

    fun resolve(base: HttpUrl, target: String): HttpUrl {
        if (target.any { it.isISOControl() } || '\\' in target)
            fail("unsafe_redirect", "网页跳转地址不安全。")
        val resolved = base.resolve(target) ?: fail("unsafe_redirect", "网页跳转地址无效。")
        return parse(resolved.toString())
    }

    /** Conservative global-unicast policy, including CGNAT, test nets, mapped and transition IPv6. */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val (a, second, third) = b
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && second in 64..127) || (a == 169 && second == 254) ||
                (a == 172 && second in 16..31) ||
                (a == 192 && (second == 168 || (second == 0 && third in listOf(0, 2)) || (second == 88 && third == 99))) ||
                (a == 198 && (second in 18..19 || (second == 51 && third == 100))) ||
                (a == 203 && second == 0 && third == 113))
        }
        if (b.size != 16 || b[0] !in 0x20..0x3f) return false
        if (b[0] == 0x20 && b[1] == 1 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) return false
        if (b[0] == 0x20 && b[1] == 2) return false // 6to4 embeds an IPv4 destination.
        if (b[0] == 0x3f && b[1] == 0xfe) return false // Former 6bone allocation, now reserved.
        if (b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10) return false // Documentation /20.
        return true
    }

    fun validateAnswers(answers: List<InetAddress>): List<InetAddress> {
        if (answers.isEmpty() || answers.any { !isPublic(it) })
            fail("unsafe_dns", "网页域名指向非公开地址，已停止读取。")
        return answers
    }
}

internal data class WebResearchPage(val url: HttpUrl, val bytes: ByteArray, val contentType: String = "text/html",
                                    val redirect: String? = null)
internal interface WebResearchTransport {
    fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage
    fun cancel()
}

/** No cookies, proxy, authenticators or transparent redirects. Validated DNS answers are the
 * exact answers consumed by OkHttp, rather than a separate preflight lookup vulnerable to rebinding.
 */
internal class PublicWebTransport(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) : WebResearchTransport {
    private val activeCall = AtomicReference<Call?>()
    private val activeDns = AtomicReference<Future<List<InetAddress>>?>()
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    override fun cancel() {
        activeCall.get()?.cancel()
        activeDns.get()?.cancel(true)
    }

    override fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage {
        WebResearchUrls.parse(url.toString())
        check()
        val dns = object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            check()
            val future = try { DNS_EXECUTOR.submit<List<InetAddress>> { resolve(hostname) } }
            catch (_: Exception) { fail("dns_busy", "域名解析繁忙，请稍后重试。") }
            activeDns.set(future)
            try {
                check()
                val remaining = minOf(TimeUnit.SECONDS.toNanos(4), deadline - System.nanoTime())
                if (remaining <= 0) fail("deadline_exceeded", "联网读取超过时间上限。")
                return WebResearchUrls.validateAnswers(future.get(remaining, TimeUnit.NANOSECONDS)).also { check() }
            } catch (_: TimeoutException) {
                fail("dns_timeout", "网页域名解析超时。")
            } finally {
                activeDns.compareAndSet(future, null)
                future.cancel(true)
            }
          }
        }
        val call = client.newBuilder().dns(dns).build().newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; DoppelResearch/0.1)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6").get().build())
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) fail("deadline_exceeded", "联网读取超过时间上限。")
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        activeCall.set(call)
        try {
            check()
            call.execute().use { response ->
                check()
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: fail("invalid_redirect", "网页跳转缺少目标地址。")
                    return WebResearchPage(url, byteArrayOf(), redirect = location)
                }
                if (!response.isSuccessful) fail("http_${response.code}", "网页服务返回 ${response.code}，暂时无法读取。")
                val body = response.body ?: fail("empty_page", "网页没有可读内容。")
                val type = response.header("Content-Type").orEmpty()
                WebResearchParsing.requireTextType(type)
                if (body.contentLength() > WebResearchLimits.MAX_BYTES) fail("response_too_large", "网页体积超过读取上限。")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > WebResearchLimits.MAX_BYTES)
                            fail("response_too_large", "网页体积超过读取上限。")
                        output.write(buffer, 0, count)
                    }
                }
                check()
                return WebResearchPage(url, output.toByteArray(), type)
            }
        } finally {
            activeCall.compareAndSet(call, null)
            call.cancel()
        }
    }

    companion object {
        // DNS implementations may ignore interrupts. Never allow unbounded threads or queued work.
        private val DNS_EXECUTOR = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { runnable -> Thread(runnable, "doppel-public-dns").apply { isDaemon = true } })
    }
}

internal object WebResearchParsing {
    data class Text(val title: String, val text: String, val truncated: Boolean)
    private val whitespace = Regex("[\\s\\u00a0]+")
    private val challenge = Regex("captcha|人机验证|安全验证|访问验证|verify (?:you are|that you)|just a moment", RegexOption.IGNORE_CASE)

    fun requireTextType(type: String) {
        val mime = type.substringBefore(';').trim().lowercase()
        if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain"))
            fail("unsupported_content", "仅支持 HTML 或纯文本网页，不能读取此文件类型。")
    }

    private fun document(page: WebResearchPage): Document {
        requireTextType(page.contentType)
        if (page.bytes.size > WebResearchLimits.MAX_BYTES) fail("response_too_large", "网页体积超过读取上限。")
        val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
            .find(page.contentType)?.groupValues?.get(1)?.let { runCatching { Charset.forName(it).name() }.getOrNull() }
        return Jsoup.parse(ByteArrayInputStream(page.bytes), charset, page.url.toString())
    }

    /** Only a declarative HTML redirect; script bodies are never interpreted or executed. */
    fun metaRedirect(page: WebResearchPage): String? {
        if (page.redirect != null || page.bytes.isEmpty() || !page.contentType.contains("html", true)) return null
        val doc = document(page)
        val meta = doc.select("meta[http-equiv]").firstOrNull { it.attr("http-equiv").equals("refresh", true) } ?: return null
        return Regex("^\\s*\\d+(?:\\.\\d+)?\\s*;\\s*url\\s*=\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
            .find(meta.attr("content"))?.groupValues?.get(1)?.trim()?.trim('\'', '"')
    }

    fun search(page: WebResearchPage, provider: String): JSONArray {
        val doc = document(page)
        rejectChallenge(doc)
        val headings = if (provider == "360") doc.select(".res-list h3.res-title a[href]")
            else doc.select(".vrwrap h3.vr-title a[href], .rb h3 a[href]")
        val seen = mutableSetOf<String>()
        val out = JSONArray()
        for (anchor in headings) {
            val container = anchor.parents().firstOrNull {
                it.hasClass("res-list") || it.hasClass("vrwrap") || it.hasClass("rb")
            } ?: continue
            // Prefer the explicitly published source URL over a search engine tracking redirect.
            val raw = anchor.attr("data-mdurl").ifBlank { anchor.attr("data-url") }
                .ifBlank { anchor.attr("href") }
            val url = runCatching { WebResearchUrls.resolve(page.url, raw) }.getOrNull() ?: continue
            if (url.host == "ai.so.com" || (url.host == page.url.host && url.encodedPath != "/link")) continue
            val title = compact(anchor.text(), 160)
            if (title.isBlank() || !seen.add(url.toString())) continue
            val excerpt = container.select(".res-desc, .res-list-summary, [id^=cacheresult_summary], .text-layout, .str-text, .fz-mid")
                .map { it.text() }.filter { it.isNotBlank() }.distinct().joinToString(" ")
                .ifBlank { container.clone().apply { select("h3, script, style, .r-sech").remove() }.text() }
            val source = container.select(".g-linkinfo-a, .citeLinkClass").firstOrNull()?.text().orEmpty()
            out.put(JSONObject().put("title", title).put("url", url.toString())
                .put("source", compact(source.ifBlank { url.host }, 120)).put("snippet", compact(excerpt, 450)))
            if (out.length() >= WebResearchLimits.MAX_RESULTS) break
        }
        return out
    }

    fun read(page: WebResearchPage): Text {
        requireTextType(page.contentType)
        if (page.bytes.size > WebResearchLimits.MAX_BYTES) fail("response_too_large", "网页体积超过读取上限。")
        if (page.contentType.substringBefore(';').trim().equals("text/plain", true)) {
            val charsetName = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(page.contentType)?.groupValues?.get(1)
            val charset = charsetName?.let { runCatching { Charset.forName(it.trim('\'', '"')) }.getOrNull() } ?: Charsets.UTF_8
            return boundedText(page.url.host, page.bytes.toString(charset))
        }
        val doc = document(page)
        rejectChallenge(doc)
        val title = compact(doc.title().ifBlank { doc.selectFirst("h1")?.text() ?: page.url.host }, 200)
        doc.select("script, style, noscript, nav, header, footer, form, button, input, select, textarea, iframe, svg, canvas, [hidden], [aria-hidden=true]").remove()
        val candidates = doc.select("article, main, [role=main], .article-content, .post-content, .entry-content, .Mid2L_con, #article, #content, .content")
        val root = candidates.maxByOrNull { element ->
            element.text().length - element.select("a").sumOf { it.text().length }
        }?.takeIf { it.text().length >= 80 } ?: doc.body()
        return boundedText(title, root.wholeText())
    }

    private fun boundedText(title: String, raw: String): Text {
        val text = raw.lineSequence().map { compact(it, Int.MAX_VALUE) }.filter { it.isNotBlank() }
            .joinToString("\n").trim()
        if (text.length < 80) fail("insufficient_content", "该页面正文不足，可能需要登录、脚本加载或更换来源。")
        return Text(title, text.take(WebResearchLimits.MAX_TEXT), text.length > WebResearchLimits.MAX_TEXT)
    }

    private fun rejectChallenge(doc: Document) {
        if (challenge.containsMatchIn(doc.title()) ||
            (doc.body().text().length < 2000 && challenge.containsMatchIn(doc.body().text())))
            fail("verification_required", "来源要求访问验证，无法自动读取，请换用公开来源。")
    }

    private fun compact(text: String, limit: Int) = text.replace(whitespace, " ").trim().take(limit)
}
