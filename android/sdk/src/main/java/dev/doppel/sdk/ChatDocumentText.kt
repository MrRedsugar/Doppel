package dev.doppel.sdk

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.apache.poi.extractor.ExtractorFactory
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.poifs.filesystem.FileMagic
import org.apache.poi.ss.extractor.ExcelExtractor
import java.io.File
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Only extracts document data. Never executes macros, follows links, or treats text as instructions. */
internal object ChatDocumentText {
    // Local extraction ceiling; model tools return only bounded ranges, never this entire cache.
    const val MAX_TEXT_CHARS = 2_000_000
    private const val MAX_XML_BYTES = 20 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 80L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 4096
    private val textExtensions = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "log", "html", "htm")
    private val officeExtensions = setOf("doc", "docx", "ppt", "pptx", "xls", "xlsx")

    init {
        // Upstream poi-on-android initialization; no native rendering or external conversion service.
        System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl")
        System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl")
        System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl")
        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
        ZipSecureFile.setMaxEntrySize(MAX_XML_BYTES.toLong())
        ZipSecureFile.setMaxFileCount(MAX_ZIP_ENTRIES.toLong())
        ZipSecureFile.setMaxTextSize(MAX_TEXT_CHARS.toLong())
    }

    fun format(name: String, mime: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in textExtensions || extension in officeExtensions || extension == "pdf") return extension
        return when (mime.substringBefore(';').lowercase()) {
            "application/msword" -> "doc"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/pdf" -> "pdf"
            "text/html" -> "html"
            "application/json" -> "json"
            "application/xml", "text/xml" -> "xml"
            else -> if (mime.startsWith("text/")) "txt" else throw IllegalArgumentException("暂不支持此文件格式，请使用 Word（DOCX）、PPT（PPTX）、Excel（XLSX）、PDF 或文本文件")
        }
    }

    fun extract(file: File, name: String, mime: String): String {
        val result = when (format(name, mime)) {
            in officeExtensions -> office(file)
            "pdf" -> pdf(file)
            "html", "htm" -> checked(Jsoup.parse(decodeText(file)).wholeText())
            else -> checked(decodeText(file))
        }.trim()
        require(result.isNotBlank()) { "文件没有可提取的文字；扫描版 PDF 或纯图片文档请改为发送图片" }
        return checked(result)
    }

    private fun pdf(file: File): String = PDDocument.load(file,
        MemoryUsageSetting.setupMixed(16L * 1024 * 1024, MAX_EXPANDED_BYTES).setTempDir(file.parentFile)).use { document ->
        require(!document.isEncrypted && document.currentAccessPermission.canExtractContent()) { "PDF 有密码或禁止提取文字，请解密后重试" }
        require(document.numberOfPages <= 300) { "PDF 超过 300 页，请拆分后发送" }
        val result = StringBuilder()
        val writer = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { appendChecked(result, String(cbuf, off, len)) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        PDFTextStripper().apply { sortByPosition = true }.writeText(document, writer)
        result.toString()
    }

    private fun decodeText(file: File): String {
        require(file.length() <= MAX_XML_BYTES) { "文本文件过大，请拆分后发送" }
        val data = file.readBytes()
        val (charset, offset) = when {
            data.size >= 3 && data[0] == 0xef.toByte() && data[1] == 0xbb.toByte() && data[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val decoded = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, data.size - offset)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("无法识别文本编码，请保存为 UTF-8 后重试", error)
        }
        require('\u0000' !in decoded) { "这不是可读取的文本文件，请检查文件格式" }
        return decoded
    }

    private fun office(file: File): String {
        require(file.length() in 1..MAX_XML_BYTES.toLong()) { "单个文件不能超过 20 MB" }
        val magic = FileMagic.valueOf(file)
        require(magic == FileMagic.OLE2 || magic == FileMagic.OOXML) { "文件不是有效的 Office 文档" }
        if (magic == FileMagic.OOXML) ZipFile(file).use { zip ->
            var total = 0L
            var count = 0
            val names = HashSet<String>()
            for (entry in zip.entries()) {
                require(++count <= MAX_ZIP_ENTRIES && names.add(entry.name)) { "文档包含过多或重复内容，请重新另存后重试" }
                require(entry.size >= 0 && entry.size <= MAX_XML_BYTES) { "文档单项内容过大或无效，请拆分文件" }
                total += entry.size
                require(total <= MAX_EXPANDED_BYTES) { "文档解压后过大，请拆分后发送" }
            }
        }
        // POI's streaming XLSX extractor skips formulas without cached results.
        ExtractorFactory.setThreadPrefersEventExtractors(false)
        return try {
            ExtractorFactory.createExtractor(file).use { extractor ->
                if (extractor is ExcelExtractor) {
                    // Sheet names alone must not make an empty workbook appear to contain data.
                    extractor.setFormulasNotResults(true)
                    extractor.setIncludeSheetNames(false)
                    require(checked(extractor.text).isNotBlank()) { "表格没有可提取的单元格内容" }
                    extractor.setIncludeSheetNames(true)
                }
                checked((if (extractor is ExcelExtractor) "[工作簿：公式为原公式，未重新计算]\n" else "") + extractor.text)
            }
        } finally {
            ExtractorFactory.removeThreadPrefersEventExtractorsSetting()
        }
    }

    private fun checked(value: String): String {
        require(value.length <= MAX_TEXT_CHARS) { "文件文字超过本机读取上限（200 万字符），请拆分文件；未截断内容" }
        return value
    }

    private fun appendChecked(out: StringBuilder, value: String) {
        require(out.length.toLong() + value.length <= MAX_TEXT_CHARS) { "文件文字超过本机读取上限（200 万字符），请拆分文件；未截断内容" }
        out.append(value)
    }
}
