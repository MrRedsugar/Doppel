package dev.doppel.sdk

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/** Android/Jsoup adaptation of Open-WebSearch, Apache-2.0.
 * Copyright 2025 Open-WebSearch MCP Server Contributors.
 * Source: https://github.com/Aas-ee/open-webSearch/tree/400678eac49521de8c3bb139f263c83d7300416e
 * src/engines/bing/parser.ts and src/engines/sogou/sogou.ts (result parsing only).
 * Modified: Kotlin/Jsoup, bounded results/redirect decoding and Doppel public-HTTPS validation;
 * transport and keyword challenge classification are intentionally not included.
 */
internal object OpenWebSearchParsing {
    private val whitespace = Regex("[\\s\\u00a0]+")
    private val bingSelectors = listOf(
        "#b_results > li.b_algo", "#b_results > li.b_ans",
        "#b_results > li:not(.b_ad):not(.b_pag):not(.b_msg)",
        "#b_topw > li.b_algo", "#b_topw > li.b_ans", ".b_algo", ".b_ans"
    )

    fun search(page: WebResearchPage, provider: String): JSONArray {
        val document = WebResearchParsing.document(page)
        return when (provider) {
            "bing" -> bing(document)
            "sogou" -> sogou(document)
            else -> throw IllegalArgumentException("Unsupported search provider")
        }
    }

    private fun bing(document: Document): JSONArray {
        val results = JSONArray()
        val seen = mutableSetOf<String>()
        fun add(card: Element, link: Element?, fallback: Boolean = false) {
            if (results.length() >= WebResearchLimits.MAX_RESULTS || link == null) return
            val raw = link.attr("href").ifBlank { link.attr("redirecturl") }.ifBlank { link.attr("data-h") }
            val url = bingUrl(raw) ?: return
            if (!seen.add(url.toString())) return
            val title = firstText(card, "h2 a", ".b_tpcn .tptt", ".b_title a", "a", "h2, h3, .b_title, .tptt")
                .ifBlank { "Result from ${url.host}" }
            val snippet = firstText(card, ".b_caption p", ".b_caption", ".b_snippet, .b_lineclamp2, .b_lineclamp3")
                .ifBlank { normalize(card.text()).replaceFirst(title, "").trim() }
                .ifBlank { if (fallback) "Result from ${url.host}" else "" }
            val source = firstText(card, ".b_tpcn", ".b_attribution cite", "cite").ifBlank { url.host }
            results.put(result(title, url, source, snippet.take(400)))
        }
        for (selector in bingSelectors) {
            for (card in document.select(selector)) {
                if (card.hasClass("b_ad") || card.closest(".b_ad") != null || card.hasClass("b_pag") || card.hasClass("b_msg")) continue
                add(card, card.selectFirst("h2 a, .b_title a, a.tilk, a[target=\"_blank\"]"))
                if (results.length() >= WebResearchLimits.MAX_RESULTS) return results
            }
        }
        if (results.length() == 0) {
            for (link in document.select("#b_results a[href], #b_topw a[href], .b_algo a[href], .b_ans a[href]")) {
                add(link.closest("li, .b_algo, .b_ans") ?: Element("div"), link, true)
                if (results.length() >= WebResearchLimits.MAX_RESULTS) break
            }
        }
        return results
    }

    private fun bingUrl(raw: String): HttpUrl? {
        var current = raw.trim()
        // Bound wrapped URLs independently of transport redirects; parsing never fetches their targets.
        repeat(5) { depth ->
            if (!validRaw(current)) return null
            current = when {
                current.startsWith("//") -> "https:$current"
                current.startsWith("/") -> {
                    if (listOf("/search", "/ck/a", "/newtabredir").any(current::startsWith)) return null
                    "https://cn.bing.com$current"
                }
                else -> current
            }
            val url = current.toHttpUrlOrNull() ?: return null
            val isBing = url.host == "bing.com" || url.host.endsWith(".bing.com")
            val path = url.encodedPath.lowercase()
            if (isBing && path.startsWith("/ck/a")) {
                if (depth == 4) return null
                val encoded = url.queryParameter("u")?.trim()?.removePrefix("a1") ?: return null
                current = runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8).trim() }.getOrNull() ?: return null
                if (!current.startsWith("https://") && !current.startsWith("http://")) return null
            } else {
                if (isBing && listOf("/search", "/newtabredir").any(path::startsWith)) return null
                val clean = url.newBuilder()
                listOf("utm_source", "utm_medium", "utm_campaign", "ref", "source").forEach(clean::removeAllQueryParameters)
                return publicUrl(clean.build().toString())
            }
        }
        return null
    }

    private fun sogou(document: Document): JSONArray {
        val results = JSONArray()
        val seen = mutableSetOf<String>()
        val selectors = "#main .vrwrap, #main .rb, #main .result, #results .vrwrap, .results .vrwrap, .results .rb"
        for (card in document.select(selectors)) {
            val link = card.selectFirst("h3 a[href], h2 a[href], .vr-title a[href], .pt a[href]") ?: continue
            val raw = link.attr("href").trim()
            if (!validRaw(raw)) continue
            val resolved = "https://www.sogou.com/web".toHttpUrlOrNull()!!.resolve(raw) ?: continue
            val target = listOf("url", "u", "link").firstNotNullOfOrNull { resolved.queryParameter(it)?.takeIf(String::isNotEmpty) }
            val url = publicUrl(if (target != null && (target.startsWith("https://", true) || target.startsWith("http://", true))) target else resolved.toString()) ?: continue
            val title = normalize(link.text())
            if (title.isEmpty() || !seen.add(url.toString())) continue
            val snippet = firstText(card, ".str_info, .ft, .text-layout, .fz-mid, p")
            val source = firstText(card, "cite, .citeurl, .g, .url").ifBlank { url.host }
            results.put(result(title, url, source, snippet))
            if (results.length() >= WebResearchLimits.MAX_RESULTS) break
        }
        return results
    }

    private fun validRaw(value: String) = value.isNotEmpty() && value.length <= 4096 && '\\' !in value && value.none { it.isISOControl() }
    private fun publicUrl(value: String) = runCatching { WebResearchUrls.parse(value) }.getOrNull()
    private fun normalize(value: String) = value.replace(whitespace, " ").trim()
    private fun firstText(element: Element, vararg selectors: String): String = selectors.firstNotNullOfOrNull {
        element.selectFirst(it)?.text()?.takeIf(String::isNotEmpty)
    }?.let(::normalize).orEmpty()
    private fun result(title: String, url: HttpUrl, source: String, snippet: String) = JSONObject()
        .put("title", title.take(160)).put("url", url.toString())
        .put("source", source.take(120)).put("snippet", snippet.take(450))
}
