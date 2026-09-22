package dev.doppel.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.util.concurrent.Future
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Public, unauthenticated web references. Call from a worker thread; never from the UI thread.
 * Returned text is untrusted reference data and must not grant device or tool permissions.
 * Search parsing is adapted from Apache-2.0 open-webSearch. No browser is opened.
 */
class AndroidWebResearch internal constructor(private val transport: WebResearchTransport, private val reader: WebPageReader? = null) {
    constructor() : this(PublicWebTransport())
    constructor(context: Context) : this(PublicWebTransport(debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0), AndroidPageReader(context))

    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val lifecycle = Any()
    // One reader belongs to one chat request or task runtime; retain only a few bounded snapshots.
    private var pageGeneration = 0L
    private val pages = object : LinkedHashMap<String, WebResearchParsing.Text>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebResearchParsing.Text>?) = size > 4
    }

    /** Cancels only the current operation. Subsequent search/read calls can reuse this instance. */
    fun cancel() {
        synchronized(lifecycle) {
            generation.incrementAndGet()
            transport.cancel()
            reader?.cancel()
        }
    }

    @JvmOverloads
    fun search(query: String, isCurrent: () -> Boolean = { true }): JSONObject = operation(isCurrent) { scope ->
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 180 || clean.any { it.isISOControl() })
            fail("invalid_query", "搜索词需为 1–180 个字符，且不能含控制字符。")
        var lastFailure: WebResearchFailure? = null
        for (provider in listOf("bing", "sogou")) {
            scope.check()
            val endpoint = if (provider == "bing") "https://cn.bing.com/search" else "https://www.sogou.com/web"
            val url = WebResearchUrls.parse(endpoint).newBuilder()
                .addQueryParameter(if (provider == "bing") "q" else "query", clean).build()
            try {
                val page = fetch(url, scope)
                val results = OpenWebSearchParsing.search(page, provider)
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
    fun read(url: String, isCurrent: () -> Boolean = { true }): JSONObject =
        readPage(JSONObject().put("url", url).put("limit", WebResearchLimits.MAX_TEXT).put("refresh", true), isCurrent)

    /** Pagination is over a stable local snapshot, not another download per excerpt. */
    fun readPage(args: JSONObject, isCurrent: () -> Boolean = { true }): JSONObject = operation(isCurrent) { scope ->
        val url = WebResearchUrls.parse(args.optString("url"))
        val operation = args.optString("operation", "read")
        if (operation !in setOf("info", "search", "read", "screenshot")) fail("invalid_operation", "网页读取操作无效。")
        val pageReader = reader ?: fail("reader_unavailable", "本机网页阅读需要 Android 应用上下文。")
        fun readerResponse(format: String): WebResearchPage {
            scope.check()
            val response = pageReader.read(url, format, scope.deadline, scope::check)
            scope.check()
            if (response.bytes.size > WebResearchLimits.MAX_BYTES) fail("response_too_large", "阅读服务返回的内容超过读取上限。")
            if (response.redirect != null) fail("reader_response_invalid", "本机阅读器未完成网页加载。")
            if (response.url != url) fail("reader_response_invalid", "本机阅读器响应来源不匹配。")
            return response
        }
        if (operation == "screenshot") {
            val result = JinaReaderChannel.screenshot(url, readerResponse("screenshot"))
            return@operation result.put("ok", true).put("untrusted", true).put("content_role", "reference_only")
                .put("url", url.toString()).put("source", url.host).put("provider", "jina-ai/reader-local").put("operation", operation)
        }
        fun integer(name: String, default: Int, min: Int, max: Int): Int {
            if (!args.has(name)) return default
            val value = args.opt(name)
            if (value !is Number || !value.toDouble().isFinite() || value.toDouble() != value.toLong().toDouble() || value.toLong() !in min.toLong()..max.toLong())
                fail("invalid_range", "网页读取范围无效。")
            return value.toInt()
        }
        val requestedOffset = integer("offset", 0, 0, Int.MAX_VALUE)
        val limit = integer("limit", 4000, 1, WebResearchLimits.MAX_TEXT)
        val query = args.optString("query")
        if (operation == "search" && (query.isBlank() || query.length > 100)) fail("invalid_query", "网页搜索词需为 1 至 100 字。")
        if (pageGeneration != scope.generation) { pages.clear(); pageGeneration = scope.generation }
        if (args.optBoolean("refresh")) pages.remove(url.toString())
        if (requestedOffset > 0 && !pages.containsKey(url.toString()))
            fail("snapshot_expired", "原网页快照已失效，不能沿用旧读取位置；请从 offset=0 重新读取或搜索定位。")
        val content = pages[url.toString()] ?: run {
            JinaReaderChannel.read(url, readerResponse("markdown")).also { pages[url.toString()] = it }
        }
        val text = content.text
        if (requestedOffset > text.length) fail("invalid_range", "读取位置超过网页正文长度。")
        val offset = characterStart(text, requestedOffset)
        val result = success().put("url", url.toString()).put("source", url.host).put("title", content.title)
            .put("provider", "jina-ai/reader-local")
            .put("operation", operation).put("total_chars", text.length).put("source_truncated", content.truncated)
        when (operation) {
            "info" -> result
            "search" -> {
                val hits = JSONArray()
                var next = offset
                while (hits.length() < minOf(limit, 10)) {
                    val found = text.indexOf(query, next, ignoreCase = true)
                    if (found < 0) { next = text.length; break }
                    val start = characterStart(text, maxOf(0, found - 160))
                    val end = characterEnd(text, minOf(text.length, found + query.length + 160))
                    hits.put(JSONObject().put("offset", found).put("snippet_offset", start).put("snippet", text.substring(start, end)))
                    next = characterEnd(text, found + query.length)
                }
                result.put("hits", hits).put("offset", offset).put("next_offset", next)
                    .put("has_more", next < text.length && text.indexOf(query, next, ignoreCase = true) >= 0)
            }
            else -> {
                var end = characterStart(text, minOf(text.length.toLong(), offset.toLong() + limit).toInt())
                if (end == offset && offset < text.length) end = minOf(text.length, offset + 2)
                result.put("text", text.substring(offset, end)).put("offset", offset).put("next_offset", end)
                    .put("has_more", end < text.length).put("truncated", end < text.length || content.truncated)
            }
        }
    }

    private fun characterStart(text: String, at: Int): Int = if (at > 0 && at < text.length &&
        Character.isHighSurrogate(text[at - 1]) && Character.isLowSurrogate(text[at])) at - 1 else at
    private fun characterEnd(text: String, at: Int): Int = if (characterStart(text, at) != at) at + 1 else at

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
    const val MAX_BYTES = 5 * 1_048_576
    const val MAX_PAGE_BYTES = 24 * 1_048_576
    const val MAX_TEXT = 10_000
    const val MAX_RESULTS = 6
    const val MAX_DOCUMENT_CHARS = 200_000
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
                                    val redirect: String? = null, val headers: Map<String, String> = emptyMap(), val status: Int = 200)
internal interface WebPageReader {
    fun read(url: HttpUrl, format: String, deadline: Long, check: () -> Unit): WebResearchPage
    fun cancel()
}
internal interface WebResearchTransport {
    fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage
    fun cancel()
}

/** No cookies, proxy, authenticators or transparent redirects. Validated DNS answers are the
 * exact answers consumed by OkHttp, rather than a separate preflight lookup vulnerable to rebinding.
 */
internal class PublicWebTransport(
    private val debug: Boolean = false,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) : WebResearchTransport {
    private val activeCall = AtomicReference<Call?>()
    private val activeDns = AtomicReference<Future<List<InetAddress>>?>()
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .apply { if (debug) eventListenerFactory {
            object : EventListener() {
                private val started = System.nanoTime()
                private fun record(call: Call, event: String, error: IOException? = null) {
                    android.util.Log.i("DoppelReader", "Network host=${call.request().url.host} event=$event" +
                        " elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}" +
                        (error?.let { " error=${it.javaClass.simpleName}" } ?: ""))
                }
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = record(call, "connect_start")
                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = record(call, "connect_failed", ioe)
                override fun callFailed(call: Call, ioe: IOException) = record(call, "call_failed", ioe)
            }
        } }
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    override fun cancel() {
        activeCall.get()?.cancel()
        activeDns.get()?.cancel(true)
    }

    override fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage = fetch(url, deadline, check, false)

    /** WebView resource loading uses the same public-address transport, with bounded binary bodies. */
    fun fetchResource(url: HttpUrl, deadline: Long, check: () -> Unit): WebResearchPage = fetch(url, deadline, check, true)

    fun fetchResource(url: HttpUrl, deadline: Long, check: () -> Unit, method: String,
                      headers: Map<String, String>, body: ByteArray?, consumeBytes: (Int) -> Unit = {}): WebResearchPage =
        fetch(url, deadline, check, true, method, headers, body, consumeBytes)

    private fun fetch(url: HttpUrl, deadline: Long, check: () -> Unit, resource: Boolean, method: String = "GET",
                      headers: Map<String, String> = emptyMap(), requestBody: ByteArray? = null,
                      consumeBytes: (Int) -> Unit = {}): WebResearchPage {
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
        val call = client.newBuilder().dns(dns)
            // Let OkHttp try another validated address for safe reads; never replay page POSTs.
            .retryOnConnectionFailure(method in setOf("GET", "HEAD", "OPTIONS"))
            .build().newCall(resourceRequest(url, method, headers, requestBody).newBuilder()
            .apply { if (headers.keys.none { it.equals("accept", true) })
                header("Accept", if (resource) "*/*" else "text/html,application/xhtml+xml,text/plain;q=0.8") }
            .build())
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
                    return WebResearchPage(url, byteArrayOf(), redirect = location, status = response.code)
                }
                // Page scripts must receive HTTP failures as responses, as native fetch/XHR do.
                if (!resource && !response.isSuccessful) fail("http_${response.code}", "网页服务返回 ${response.code}，暂时无法读取。")
                val body = response.body ?: fail("empty_page", "网页没有可读内容。")
                val type = response.header("Content-Type").orEmpty()
                if (!resource) WebResearchParsing.requireTextType(type)
                if (body.contentLength() > WebResearchLimits.MAX_BYTES) fail("response_too_large", "网页体积超过读取上限。")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count == -1) break
                        consumeBytes(count)
                        if (output.size() + count > WebResearchLimits.MAX_BYTES)
                            fail("response_too_large", "网页体积超过读取上限。")
                        output.write(buffer, 0, count)
                    }
                }
                check()
                val headers = response.headers.names().filterNot {
                    it.lowercase() in setOf("cookie", "set-cookie", "set-cookie2", "authorization", "proxy-authorization", "www-authenticate", "proxy-authenticate",
                        "content-encoding", "content-length", "transfer-encoding")
                }.associateWith { response.header(it).orEmpty() }
                return WebResearchPage(url, output.toByteArray(), type, headers = headers, status = response.code)
            }
        } finally {
            activeCall.compareAndSet(call, null)
            call.cancel()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        /** Website scripts receive only this bounded public-network capability, never app credentials. */
        internal fun resourceRequest(url: HttpUrl, method: String, headers: Map<String, String>, body: ByteArray?): Request {
            WebResearchUrls.parse(url.toString())
            if (method !in setOf("GET", "HEAD", "POST", "OPTIONS")) fail("unsupported_request", "网页请求方法不受支持。")
            if ((body?.size ?: 0) > 128 * 1024 || headers.size > 32 || headers.entries.sumOf { it.key.length + it.value.length } > 16384)
                fail("request_too_large", "网页请求超过大小上限。")
            val allowed = headers.filterKeys { it.lowercase() !in setOf("cookie", "cookie2", "authorization", "proxy-authorization",
                "host", "connection", "content-length", "transfer-encoding", "accept-encoding", "upgrade", "trailer", "te") }
            val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; DoppelResearch/0.1)")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            allowed.forEach { (key, value) -> builder.header(key, value) }
            val mime = allowed.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaTypeOrNull()
            return builder.method(method, if (method == "POST") (body ?: byteArrayOf()).toRequestBody(mime) else null).build()
        }
        // DNS implementations may ignore interrupts. Never allow unbounded threads or queued work.
        private val DNS_EXECUTOR = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
            { runnable -> Thread(runnable, "doppel-public-dns").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
}

internal object WebResearchParsing {
    data class Text(val title: String, val text: String, val truncated: Boolean)

    fun requireTextType(type: String) {
        val mime = type.substringBefore(';').trim().lowercase()
        if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain"))
            fail("unsupported_content", "仅支持 HTML 或纯文本网页，不能读取此文件类型。")
    }

    fun document(page: WebResearchPage): Document {
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

}
