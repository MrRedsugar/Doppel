package dev.doppel.sdk

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ChatDocumentTextTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initializeAndroidPoi() { ChatDocumentText.format("init.txt", "text/plain") }

    @Test fun readsWordParagraphsAndTableCells() {
        val file = temporary.newFile("sample.docx")
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("任务目标：整理出差计划")
            val row = doc.createTable(1, 2).getRow(0)
            row.getCell(0).text = "上海"; row.getCell(1).text = "3 天"
            file.outputStream().use { doc.write(it) }
        }
        val text = extract(file)
        assertTrue(text.contains("任务目标：整理出差计划"))
        assertTrue(text.contains("上海")); assertTrue(text.contains("3 天"))
    }

    @Test fun slidesUseActualPresentationOrder() {
        val file = temporary.newFile("slides.pptx")
        XMLSlideShow().use { doc ->
            doc.createSlide().createTextBox().text = "第二页结论"
            val first = doc.createSlide(); first.createTextBox().text = "第一页目标"
            doc.setSlideOrder(first, 0)
            file.outputStream().use { doc.write(it) }
        }
        val text = extract(file)
        assertTrue(text.contains("第一页目标")); assertTrue(text.contains("第二页结论"))
        assertTrue(text.indexOf("第一页目标") < text.indexOf("第二页结论"))
    }

    @Test fun readsLegacyAndModernExcelWithDisplayedNumberFormats() {
        for (modern in listOf(false, true)) {
            val file = temporary.newFile(if (modern) "budget.xlsx" else "budget.xls")
            (if (modern) XSSFWorkbook() else HSSFWorkbook()).use { book ->
                val row = book.createSheet("预算").createRow(0)
                row.createCell(0).setCellValue("交通费")
                row.createCell(1).apply {
                    setCellValue(2846.5)
                    cellStyle = book.createCellStyle().apply { dataFormat = book.createDataFormat().getFormat("#,##0.00") }
                }
                row.createCell(2).cellFormula = "B1*2"
                file.outputStream().use { book.write(it) }
            }
            val text = extract(file)
            assertTrue(text.contains("预算")); assertTrue(text.contains("交通费")); assertTrue(text, text.contains("2,846.50"))
            assertTrue(text, text.contains("B1*2")); assertTrue(text.contains("未重新计算"))
        }
    }

    @Test fun readsRealLegacyWordAndPowerPoint() {
        val samples = mapOf("simple.doc" to "Word 97", "sample2.ppt" to "Title of the first slide")
        for ((name, expected) in samples) {
            val file = temporary.newFile(name)
            javaClass.getResourceAsStream("/attachments/$name")!!.use { source -> file.outputStream().use { source.copyTo(it) } }
            assertTrue(extract(file).contains(expected))
        }
    }

    @Test fun rejectsExternalEntitiesCorruptionAndOversizedTextWithoutPartialResult() {
        val original = temporary.newFile("original.docx")
        XWPFDocument().use { doc -> doc.createParagraph().createRun().setText("PLACEHOLDER"); original.outputStream().use { doc.write(it) } }
        val secret = temporary.newFile("outside.txt").apply { writeText("SECRET_DO_NOT_READ") }
        val malicious = temporary.newFile("entity.docx")
        ZipFile(original).use { source -> ZipOutputStream(malicious.outputStream()).use { dest ->
            for (entry in source.entries()) {
                var bytes = source.getInputStream(entry).use { it.readBytes() }
                if (entry.name == "word/document.xml") {
                    val xml = bytes.toString(Charsets.UTF_8).replace("?>", "?><!DOCTYPE root [<!ENTITY steal SYSTEM \"${secret.toURI()}\">]>")
                        .replace("PLACEHOLDER", "&steal;")
                    bytes = xml.toByteArray()
                }
                dest.putNextEntry(ZipEntry(entry.name)); dest.write(bytes); dest.closeEntry()
            }
        } }
        expectFailure { extract(malicious) }
        val tooLong = temporary.newFile("long.txt").apply { writeText("字".repeat(ChatDocumentText.MAX_TEXT_CHARS + 1)) }
        expectFailure { extract(tooLong) }
        val corrupted = temporary.newFile("bad.docx").apply { writeText("not a zip") }
        expectFailure { extract(corrupted) }
        val invalidText = temporary.newFile("binary.txt").apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        expectFailure { extract(invalidText) }
    }

    @Test fun unicodeHtmlAndLongLocalTextPreserveContent() {
        val utf16 = temporary.newFile("unicode.txt").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "您好，世界".toByteArray(Charsets.UTF_16LE)) }
        assertEquals("您好，世界", extract(utf16))
        val html = temporary.newFile("page.html").apply { writeText("<html><body><p>hello</p><p>世界</p><script>bad()</script></body></html>") }
        val text = extract(html)
        assertTrue(text.contains("hello")); assertTrue(text.contains("世界")); assertFalse(text.contains("bad()"))
        val contents = "附件内容仅按需读取。\n".repeat(12_000)
        val file = temporary.newFile("long-readable.txt").apply { writeText(contents) }
        assertTrue(contents.length > 60_000); assertEquals(contents.trim(), extract(file))
    }

    @Test fun rejectsZipInflationBeforeParsingAndEmptyFiles() {
        val bomb = temporary.newFile("oversized.docx")
        ZipOutputStream(bomb.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val zeros = ByteArray(1024 * 1024); repeat(81) { zip.write(zeros) }; zip.closeEntry()
        }
        assertTrue(bomb.length() < 1024 * 1024); expectFailure { extract(bomb) }
        val slides = temporary.newFile("empty.pptx")
        XMLSlideShow().use { doc -> doc.createSlide(); slides.outputStream().use { doc.write(it) } }
        expectFailure { extract(slides) }
        val sheets = temporary.newFile("empty.xlsx")
        XSSFWorkbook().use { book -> book.createSheet("Empty"); sheets.outputStream().use { book.write(it) } }
        expectFailure { extract(sheets) }
        expectFailure { extract(temporary.newFile("empty.txt")) }
    }

    private fun extract(file: File) = ChatDocumentText.extract(file, file.name, "")
    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (expected: Exception) { assertNotNull(expected.message) }
    }
}
