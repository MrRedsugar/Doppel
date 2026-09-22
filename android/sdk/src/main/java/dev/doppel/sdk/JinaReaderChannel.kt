package dev.doppel.sdk

import okhttp3.HttpUrl
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64

/** Thin response adapter for jina-ai/reader's extraction core running on this phone.
 * Markdown envelope handling originated in the MIT Agent-Reach adaptation;
 * see assets/third_party/agent-reach/LICENSE. Rendering and extraction stay local.
 */
internal object JinaReaderChannel {
    fun read(url: HttpUrl, response: WebResearchPage): WebResearchParsing.Text {
        if (response.bytes.size > WebResearchLimits.MAX_BYTES)
            throw WebResearchFailure("response_too_large", "Jina Reader 返回的网页超过读取上限。")
        if (!response.contentType.substringBefore(';').trim().equals("text/plain", true))
            throw WebResearchFailure("reader_response_invalid", "网页阅读服务未返回可读正文，请稍后重试。")
        val raw = response.bytes.toString(Charsets.UTF_8).trim()
        if (raw.isBlank()) throw WebResearchFailure("empty_page", "网页阅读服务没有返回内容。")
        // Jina's original Markdown envelope (including source warnings) stays visible to the model.
        // The upstream keyword-based challenge classifier is deliberately not transplanted.
        val title = raw.lineSequence().firstOrNull { it.startsWith("Title: ") }
            ?.removePrefix("Title: ")?.trim()?.take(200).orEmpty().ifBlank { url.host }
        var end = minOf(raw.length, WebResearchLimits.MAX_DOCUMENT_CHARS)
        if (end < raw.length && end > 0 && Character.isHighSurrogate(raw[end - 1]) && Character.isLowSurrogate(raw[end])) end--
        return WebResearchParsing.Text(title, raw.substring(0, end), end < raw.length)
    }

    /** The data URL is transient: callers must remove it before history, diagnostics or persistence. */
    fun screenshot(url: HttpUrl, response: WebResearchPage): JSONObject {
        val bytes = response.bytes
        if (bytes.size > WebResearchLimits.MAX_BYTES)
            throw WebResearchFailure("response_too_large", "阅读服务返回的截图超过读取上限。")
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
        if (!response.contentType.substringBefore(';').trim().equals("image/png", true) || bytes.size < 33 ||
            !bytes.copyOfRange(0, 8).contentEquals(signature) || ByteBuffer.wrap(bytes, 8, 4).int != 13 ||
            !bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII)))
            throw WebResearchFailure("reader_response_invalid", "阅读服务未返回有效的 PNG 截图。")
        val width = ByteBuffer.wrap(bytes, 16, 4).int
        val height = ByteBuffer.wrap(bytes, 20, 4).int
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000)
            throw WebResearchFailure("reader_response_invalid", "网页截图尺寸无效或超过 1600 万像素。")
        return JSONObject().put("title", url.host).put("mime", "image/png").put("width", width).put("height", height)
            .put("image_bytes", bytes.size).put("reference_only", true)
            .put("_reference_image_data_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
    }
}
