package dev.doppel.sdk

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.*
import android.webkit.*
import okhttp3.HttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only website sandbox, deliberately separate from the device-control process. */
class ReaderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(4)
    private val jobs = mutableMapOf<String, Page>()
    private val extractor by lazy { assets.open("third_party/jina-reader/reader-core.js").bufferedReader().use { it.readText() } }
    private val networkScript by lazy { assets.open("third_party/jina-reader/reader-network.js").bufferedReader().use { it.readText() } }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getString("id").orEmpty()
        when (message.what) {
            CANCEL -> jobs.remove(id)?.close()
            READ -> {
                val reply = message.replyTo
                if (reply != null) try {
                    require(id.length == 36 && id !in jobs && jobs.size < 2) { "网页阅读器繁忙，请稍后再试。" }
                    val url = WebResearchUrls.parse(message.data.getString("url").orEmpty())
                    val format = message.data.getString("format").orEmpty()
                    require(format in setOf("markdown", "screenshot"))
                    val deadline = minOf(message.data.getLong("deadline"), System.nanoTime() + TimeUnit.SECONDS.toNanos(28))
                    Page(id, url, format, deadline, reply).also { jobs[id] = it; it.start() }
                } catch (error: Exception) { send(reply, id, error = error) }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28 && !profileInitialized) {
            WebView.setDataDirectorySuffix("public-reader"); profileInitialized = true
        }
        // No browser logins; the only page bridge provides bounded public HTTP requests.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }
    override fun onBind(intent: Intent) = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { jobs.values.toList().forEach { it.close() }; jobs.clear(); return false }
    override fun onDestroy() { jobs.values.toList().forEach { it.close() }; jobs.clear(); workers.shutdownNow(); super.onDestroy() }

    private fun send(reply: Messenger, id: String, bytes: ByteArray? = null, type: String = "text/plain", error: Exception? = null) {
        runCatching { reply.send(Message.obtain(null, READ).apply { data = Bundle().apply {
            putString("id", id)
            if (error != null) {
                putString("error", (error as? WebResearchFailure)?.code ?: "read_failed")
                putString("message", error.message?.take(200) ?: "网页读取失败。")
            } else { putByteArray("bytes", bytes); putString("type", type) }
        } }) }
    }

    private inner class Page(val id: String, val source: HttpUrl, val format: String, val deadline: Long, val reply: Messenger) {
        @Volatile private var closed = false
        @Volatile private var view: WebView? = null
        private val transports = ConcurrentHashMap.newKeySet<PublicWebTransport>()
        private val bytesRead = AtomicInteger()
        private val requests = AtomicInteger()
        private val loading = AtomicInteger()
        private var target = source
        private var started = 0L
        private var lastChange = 0L
        private var lastSignature = ""
        @Volatile private var navigations = 0
        private val resourceFailures = AtomicInteger()
        private val timeout = Runnable { finish(error = WebResearchFailure("deadline_exceeded", "网页加载超时，未取得可核对的内容。")) }
        fun check() {
            if (closed) throw WebResearchFailure("cancelled", "网页读取已取消。")
            if (System.nanoTime() >= deadline) throw WebResearchFailure("deadline_exceeded", "网页加载超时。")
        }
        fun close() {
            closed = true; main.removeCallbacksAndMessages(this)
            transports.forEach { it.cancel() }; transports.clear()
            view?.let { it.stopLoading(); it.destroy() }; view = null
        }
        fun finish(bytes: ByteArray? = null, type: String = "text/plain", error: Exception? = null) {
            if (closed) return
            jobs.remove(id); close(); send(reply, id, bytes, type, error)
        }
        fun start() {
            main.postAtTime(timeout, this, SystemClock.uptimeMillis() + TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
            load(source)
        }
        private fun fetch(initial: HttpUrl, navigation: Int, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null): WebResearchPage {
            fun current() {
                check()
                if (navigation != navigations) throw WebResearchFailure("cancelled", "网页已跳转。")
                if (bytesRead.get() > WebResearchLimits.MAX_PAGE_BYTES) throw WebResearchFailure("resource_limit", "网页资源超过读取上限。")
            }
            current()
            if (requests.incrementAndGet() > 180) throw WebResearchFailure("resource_limit", "网页资源数量超过读取上限。")
            val transport = PublicWebTransport(debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            transports.add(transport); loading.incrementAndGet()
            try {
                var url = initial
                var currentMethod = method
                var currentBody = body
                repeat(6) {
                    current()
                    val page = transport.fetchResource(url, deadline, ::current, currentMethod, headers, currentBody) { count ->
                        bytesRead.addAndGet(count)
                        current()
                    }
                    if (page.redirect == null) return page
                    url = WebResearchUrls.resolve(url, page.redirect)
                    if (page.status == 303 || currentMethod == "POST" && page.status in setOf(301, 302)) {
                        currentMethod = "GET"; currentBody = null
                    }
                }
                throw WebResearchFailure("too_many_redirects", "网页跳转次数过多。")
            } finally { transports.remove(transport); transport.cancel(); loading.decrementAndGet() }
        }
        private fun load(url: HttpUrl) {
            if (++navigations > 5) { finish(error = WebResearchFailure("too_many_redirects", "网页跳转次数过多。")); return }
            val navigation = navigations
            transports.forEach { it.cancel() }
            view?.let { it.stopLoading(); it.destroy() }; view = null
            workers.execute {
                try {
                    val page = fetch(url, navigation)
                    if (page.status !in 200..299) throw WebResearchFailure("http_${page.status}", "网页服务返回 ${page.status}，未取得正文。")
                    val mime = page.contentType.substringBefore(';').lowercase()
                    if (mime !in setOf("text/html", "application/xhtml+xml")) {
                        if (format == "screenshot") throw WebResearchFailure("unsupported_content", "此文件不是可截取的网页。")
                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this@ReaderService)
                        val file = File.createTempFile("reader-", ".document", cacheDir)
                        val text = try { file.writeBytes(page.bytes); ChatDocumentText.extract(file, page.url.pathSegments.last(), mime) } finally { file.delete() }
                        check()
                        main.post { if (!closed && navigation == navigations) textResult(page.url, page.url.pathSegments.last(), text, text.length > WebResearchLimits.MAX_DOCUMENT_CHARS) }
                        return@execute
                    }
                    // Network loads all go through the validated transport. CSP also blocks channels
                    // without WebView request interception: sockets, workers, forms and nested frames.
                    val document = WebResearchParsing.document(page)
                    document.select("meta[http-equiv=refresh]").remove()
                    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
                        "default-src https: data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src https:; frame-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri https:")
                    document.head().select("meta[http-equiv=Content-Security-Policy]").first()?.after(
                        org.jsoup.nodes.Element("script").appendChild(org.jsoup.nodes.DataNode(networkScript)))
                    val html = document.outerHtml()
                    main.post { if (!closed && navigation == navigations) render(page.url, html) }
                } catch (error: Exception) { main.post { if (navigation == navigations) finish(error = error) } }
            }
        }
        @Suppress("SetJavaScriptEnabled", "DEPRECATION")
        private fun render(url: HttpUrl, html: String) {
            view?.destroy(); target = url
            val web = WebView(this@ReaderService); view = web
            val navigation = navigations
            web.addJavascriptInterface(object {
                @JavascriptInterface fun request(raw: String): String = try {
                    check()
                    if (raw.length > 256 * 1024) throw WebResearchFailure("request_too_large", "网页请求超过大小上限。")
                    val input = JSONObject(raw)
                    val suppliedHeaders = input.optJSONObject("headers") ?: JSONObject()
                    val headers = suppliedHeaders.keys().asSequence()
                        .filterNot { it.equals("Origin", true) || it.equals("Referer", true) }
                        .associateWith { suppliedHeaders.getString(it) }.toMutableMap()
                    val requestUrl = WebResearchUrls.parse(input.getString("url"))
                    val origin = "${target.scheme}://${target.host}"
                    headers["Origin"] = origin
                    headers["Referer"] = if (requestUrl.host == target.host) target.toString() else "$origin/"
                    val body = java.util.Base64.getDecoder().decode(input.optString("body"))
                    val response = fetch(requestUrl, navigation, input.getString("method"), headers, body)
                    JSONObject().put("status", response.status).put("headers", JSONObject(response.headers))
                        .put("body", java.util.Base64.getEncoder().encodeToString(response.bytes)).toString()
                } catch (error: Exception) {
                    resourceFailures.incrementAndGet()
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        android.util.Log.w("DoppelReader", "Page network failed (${(error as? WebResearchFailure)?.code ?: error.javaClass.simpleName})")
                    JSONObject().put("error", (error as? WebResearchFailure)?.code ?: "network_unavailable").toString()
                }
            }, "DoppelReaderNetwork")
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true; cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
            }
            web.webViewClient = object : WebViewClient() {
                private val initialDocument = AtomicBoolean(true)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.method == "GET") try { load(WebResearchUrls.parse(request.url.toString())) }
                    catch (error: Exception) { finish(error = error) }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    return try {
                        check()
                        if (request.isForMainFrame && request.url.toString() == url.toString() && initialDocument.compareAndSet(true, false))
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                        if (request.method != "GET") throw WebResearchFailure("unsupported_request", "只读网页不提交表单。")
                        val page = fetch(WebResearchUrls.parse(request.url.toString()), navigation)
                        val type = page.contentType.substringBefore(';')
                        val encoding = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(page.contentType)?.groupValues?.get(1) ?: "UTF-8"
                        if (page.status !in 200..299) resourceFailures.incrementAndGet()
                        WebResourceResponse(type, encoding, page.status, "HTTP ${page.status}", page.headers, ByteArrayInputStream(page.bytes))
                    } catch (error: Exception) {
                        resourceFailures.incrementAndGet()
                        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                            android.util.Log.w("DoppelReader", "Resource load failed (${(error as? WebResearchFailure)?.code ?: error.javaClass.simpleName}): ${request.url.host}")
                        WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && this@Page.view === view)
                        finish(error = WebResearchFailure("page_load_failed", "网页主页面加载失败（${error.errorCode}），未返回错误页作为正文。"))
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && this@Page.view === view)
                        finish(error = WebResearchFailure("page_load_failed", "网页主页面返回 ${response.statusCode}，未取得正文。"))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    finish(error = WebResearchFailure("renderer_exited", "网页渲染进程已退出，请重试。")); return true
                }
            }
            web.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY))
            web.layout(0, 0, 900, 1200)
            started = SystemClock.uptimeMillis(); lastChange = started; lastSignature = ""
            // Supply the already validated document under its real HTTPS origin. loadData's
            // synthetic data: navigation is not an external URL and cannot enter the HTTP path.
            web.loadUrl(url.toString())
            poll(web)
        }
        private fun poll(web: WebView) {
            main.postAtTime({
                if (!closed && view === web) web.evaluateJavascript("JSON.stringify([document.readyState,document.body?document.body.innerText.length:0,document.getElementsByTagName('*').length])") { signature ->
                    if (closed || view !== web) return@evaluateJavascript
                    val now = SystemClock.uptimeMillis()
                    if (signature != lastSignature || loading.get() != 0) { lastSignature = signature; lastChange = now }
                    if (now - started >= 2500 && now - lastChange >= 1000 || deadline - System.nanoTime() < TimeUnit.SECONDS.toNanos(3)) extract(web)
                    else poll(web)
                }
            }, this, SystemClock.uptimeMillis() + 400)
        }
        private fun extract(web: WebView) {
            if (format == "screenshot") {
                try {
                    var bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
                    web.draw(Canvas(bitmap))
                    var bytes: ByteArray
                    do {
                        bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
                        if (bytes.size <= 450_000) break
                        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                        bitmap.recycle(); bitmap = scaled
                    } while (bitmap.width > 100)
                    bitmap.recycle()
                    if (bytes.size > 450_000) throw WebResearchFailure("response_too_large", "网页截图超过读取上限。")
                    finish(bytes, "image/png")
                } catch (error: Exception) { finish(error = error) }
                return
            }
            val options = JSONObject().put("url", target.toString()).put("maxChars", WebResearchLimits.MAX_DOCUMENT_CHARS)
            web.evaluateJavascript("(function(){ try { $extractor; return JSON.stringify(DoppelReader.extract($options)); } catch (e) { return JSON.stringify({error:String(e.message||e).slice(0,160)}); } })()") { encoded ->
                if (!closed && view === web) try {
                    val result = JSONObject(JSONTokener(encoded).nextValue() as String)
                    if (result.has("error")) throw WebResearchFailure("extraction_failed", result.getString("error"))
                    textResult(target, result.optString("title"), result.getString("text"), result.optBoolean("truncated"))
                } catch (error: Exception) { finish(error = WebResearchFailure("extraction_failed", "网页正文提取失败：${error.message?.take(100)}")) }
            }
        }
        private fun textResult(url: HttpUrl, title: String, text: String, truncated: Boolean) {
            var end = minOf(text.length, WebResearchLimits.MAX_DOCUMENT_CHARS)
            if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            if (text.isBlank()) { finish(error = WebResearchFailure("empty_page", "页面没有可提取的正文。")); return }
            val result = "Title: ${title.take(300).replace('\n', ' ')}\n\nURL Source: $url\n" +
                (if (resourceFailures.get() > 0) "\nWarning: ${resourceFailures.get()} 个网页资源未成功加载，内容可能不完整。\n" else "") +
                (if (truncated) "\nWarning: 本机正文达到提取上限，未返回完整内容。\n" else "") + "\nMarkdown Content:\n${text.take(end)}"
            finish(result.toByteArray(Charsets.UTF_8))
        }
    }
    companion object {
        internal const val READ = 1; internal const val CANCEL = 2
        private var profileInitialized = false
    }
}
