package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatAttachmentContextTest {
    @Test fun websiteImageIsMultimodalForOneReplyAndNeverIncludedInDiagnostics() {
        var calls = 0
        val png = "data:image/png;base64," + "AAAA".repeat(20000)
        val answer = ChatAttachmentContext.chat(prompt(), JSONArray(), { error("unused") }, webTool = { args ->
            if (args.optString("operation") == "screenshot") JSONObject().put("url", "https://example.org")
                .put("_reference_image_data_url", png).put("image_bytes", 60000)
            else JSONObject().put("text", "页面标题")
        }) { messages ->
            when (calls++) {
                0 -> response(JSONObject().put("intent", "read_web").put("operation", "screenshot").put("url", "https://example.org"))
                1 -> {
                    val image = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                    assertTrue(image.getJSONObject(0).getString("text").contains("不是当前设备屏幕"))
                    assertEquals(png, image.getJSONObject(1).getJSONObject("image_url").getString("url"))
                    val metadata = JSONObject(messages.getJSONObject(messages.length() - 2).getString("content"))
                    assertFalse(metadata.getJSONObject("result").has("_reference_image_data_url"))
                    response(JSONObject().put("intent", "read_web").put("url", "https://example.org"))
                }
                else -> {
                    assertFalse(messages.toString().contains("base64"))
                    response(JSONObject().put("intent", "conversation").put("reply", "已核对截图与正文"))
                }
            }
        }
        assertEquals(3, calls)
        assertFalse(answer.toString().contains("base64"))
        assertEquals("screenshot", answer.getJSONObject("_doppel_reference_reads").getJSONArray("calls").getJSONObject(0).getString("operation"))
    }
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun file(kind: String = "document") = JSONObject().put("id", id).put("name", "报告.docx")
        .put("kind", kind).put("mime", "application/octet-stream").put("size", 30)
    private fun response(value: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("message", JSONObject().put("content", value.toString()))))
    private fun prompt() = JSONArray().put(JSONObject().put("role", "system").put("content", "router"))
        .put(JSONObject().put("role", "user").put("content", "当前消息"))
    private fun read(offset: Int = 0, limit: Int = 6) = JSONObject().put("intent", "read_attachment")
        .put("attachment_id", id).put("offset", offset).put("limit", limit)

    @Test fun documentIsNotReadUntilTheModelChoosesTheToolAndOnlyRequestedPageReturns() {
        var reads = 0; var calls = 0
        val refs = JSONArray().put(file())
        ChatAttachmentContext.chat(prompt(), refs, { reads++; "abcdefghijkl" }) { messages ->
            if (calls++ == 0) {
                assertEquals(0, reads); assertFalse(messages.toString().contains("abcdefghijkl"))
                response(read(3, 4))
            } else {
                assertEquals(1, reads)
                val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONObject("result")
                assertEquals("defg", result.getString("text")); assertEquals(7, result.getInt("next_offset"))
                assertEquals(12, result.getInt("total_chars")); assertTrue(result.getBoolean("has_more"))
                response(JSONObject().put("intent", "conversation").put("reply", "答复"))
            }
        }
        assertEquals(2, calls)
        ChatAttachmentContext.chat(prompt(), refs, { error("Unrequested read") }) {
            response(JSONObject().put("intent", "conversation"))
        }
    }

    @Test fun unauthorizedFilesAndInvalidPagesNeverReachTheReader() {
        for (args in listOf(read().put("attachment_id", "22222222-2222-4222-8222-222222222222"),
            read(-1), read(limit = 8001), read().put("offset", 0.5))) {
            assertThrows(IllegalArgumentException::class.java) { ChatAttachmentContext.page(args, JSONArray().put(file())) { error("Should not read") } }
        }
    }

    @Test fun imagesAreSeparatedFromCurrentScreenAndNeverPersistedIntoMetadata() {
        val original = JSONObject().put("messages", prompt()).put("_doppel_attachments", JSONArray().put(file("image")))
        val payload = ChatAttachmentContext.withImages(original, JSONArray().put(file("image"))) { "data:image/jpeg;base64,IMAGE" }
        assertFalse(payload.has("_doppel_attachments")); assertFalse(original.toString().contains("base64"))
        val messages = payload.getJSONArray("messages")
        assertTrue(messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").contains("不是当前设备屏幕"))
        assertEquals("当前消息", messages.getJSONObject(2).getString("content"))
        assertFalse(ChatAttachmentContext.metadata(JSONArray().put(file().put("text", "SECRET").put("path", "/secret"))).toString().contains("SECRET"))
    }

    @Test fun chatAndTaskHistoryKeepOnlyReferencesAndFollowupsRetainOlderFile() {
        val chats = JSONArray().put(JSONObject().put("role", "user").put("content", "附件").put("after_run_id", "")
            .put("attachments", JSONArray().put(file())))
        repeat(20) { chats.put(JSONObject().put("role", "user").put("content", "追问$it").put("after_run_id", "")) }
        val context = ConversationChatContext.merge(JSONArray(), chats)
        assertEquals(12, context.length())
        assertEquals(id, ChatAttachmentContext.select(JSONArray(), context).getJSONArray("items").getJSONObject(0).getString("id"))
        val run = JSONObject().put("id", "run").put("goal", "参照附件").put("attachments", JSONArray().put(file()))
        assertEquals(id, ConversationHistory.thread("run") { run }.getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("id"))
    }

    @Test fun errorsReturnToTheModelAndRepeatedReadsHaveABudget() {
        var calls = 0
        ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { error("加密文件无法读取") }) { messages ->
            if (calls++ == 0) response(read()) else {
                assertTrue(messages.toString().contains("加密文件无法读取")); response(JSONObject().put("intent", "conversation"))
            }
        }
        var reads = 0
        val exhausted = ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { reads++; "abcdefgh" }) {
            response(read().put("context_summary", "此前已读取一小段，仍待核对"))
        }
        assertEquals(ChatAttachmentContext.MAX_READS, reads)
        val answer = JSONObject(exhausted.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals("conversation", answer.getString("intent")); assertTrue(answer.getString("reply").contains("尚未完成最终核对"))
        assertTrue(answer.getString("reply").contains("仍待核对")); assertEquals(0, answer.getJSONArray("memory_changes").length())
        assertTrue(exhausted.getJSONObject("_doppel_attachment_reads").getBoolean("incomplete"))
    }

    @Test fun searchFindsLateRelevantTextLiterallyAndReturnsSmallSnippetsThenRangeReads() {
        val text = "无关内容".repeat(25000) + "费用[含税] 42元" + "其他内容".repeat(100)
        val args = read(limit = 10).put("operation", "search").put("query", "费用[含税]")
        val result = ChatAttachmentContext.page(args, JSONArray().put(file())) { text }
        assertEquals(1, result.getJSONArray("hits").length())
        val hit = result.getJSONArray("hits").getJSONObject(0)
        assertEquals(100000, hit.getInt("offset")); assertTrue(hit.getString("snippet").contains("42元"))
        assertTrue(result.toString().length < 1000); assertFalse(result.getBoolean("has_more"))
        val info = ChatAttachmentContext.page(read().put("operation", "info"), JSONArray().put(file())) { text }
        assertEquals(text.length, info.getInt("total_chars")); assertFalse(info.has("text"))
    }

    @Test fun chatPrunesOldPagesButKeepsCumulativeFactsWithoutAnExtraModelRequest() {
        var calls = 0
        ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { "abcdef".repeat(8000) }) { messages ->
            if (calls < 4) {
                if (calls >= 3) {
                    assertEquals(7, messages.length())
                    assertTrue(messages.getJSONObject(2).getString("content").contains("第一段事实已确认"))
                    assertEquals(2, (0 until messages.length()).count { messages.getJSONObject(it).optString("content").contains("\"tool\":\"read_attachment\"") })
                }
                response(read(calls++ * 8000, 8000).put("context_summary", "第一段事实已确认；累计到第${calls}段"))
            } else {
                calls++; response(JSONObject().put("intent", "conversation"))
            }
        }
        assertEquals(5, calls)
    }
    @Test fun exhaustedBudgetGetsOneFinalAnswerRequestAndDiagnosticsExcludeSourceText() {
        var calls = 0
        val completed = ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { "sensitive-source-value" }) { messages ->
            if (calls++ < ChatAttachmentContext.MAX_READS) response(read()) else {
                val instruction = messages.getJSONObject(messages.length() - 1)
                assertEquals("system", instruction.getString("role"))
                assertTrue(instruction.getString("content").contains("remaining_reads=0"))
                response(JSONObject().put("intent", "conversation").put("reply", "只核对了已读取的一段内容"))
            }
        }
        assertEquals(ChatAttachmentContext.MAX_READS + 1, calls)
        val diagnostics = completed.getJSONObject("_doppel_attachment_reads")
        assertFalse(diagnostics.getBoolean("incomplete")); assertTrue(diagnostics.getBoolean("budget_exhausted"))
        assertEquals(ChatAttachmentContext.MAX_READS, diagnostics.getJSONArray("calls").length())
        assertFalse(diagnostics.toString().contains("sensitive-source-value")); assertFalse(diagnostics.toString().contains("报告"))
        var attempts = 0
        val failedFinal = ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { "abcdefgh" }) {
            if (attempts++ == ChatAttachmentContext.MAX_READS) throw java.io.IOException("transport unavailable")
            response(read())
        }
        assertTrue(failedFinal.getJSONObject("_doppel_attachment_reads").getBoolean("incomplete"))
    }

    @Test fun smallResultsStayTogetherInsteadOfBeingDroppedAfterTwoReads() {
        var calls = 0
        ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { "abcdefghijkl" }) { messages ->
            if (calls < 3) response(read(calls++ * 4, 4)) else {
                assertEquals(9, messages.length())
                val results = (4 until messages.length() step 2).map { JSONObject(messages.getJSONObject(it).getString("content")).getJSONObject("result").getString("text") }
                assertEquals(listOf("abcd", "efgh", "ijkl"), results)
                response(JSONObject().put("intent", "conversation").put("reply", "三个片段都已保留"))
            }
        }
    }

    @Test fun currentFilesAreDistinguishedFromHistoryAndNoAttachmentImagePathDoesNotClone() {
        val old = file().put("id", "22222222-2222-4222-8222-222222222222")
        val selected = ChatAttachmentContext.select(JSONArray().put(file()), JSONArray().put(JSONObject().put("attachments", JSONArray().put(old))))
        assertEquals(id, selected.getJSONArray("current_ids").getString(0))
        assertEquals(old.getString("id"), selected.getJSONArray("historical_ids").getString(0))
        val payload = JSONObject().put("messages", prompt())
        assertSame(payload, ChatAttachmentContext.withImages(payload, JSONArray()) { error("No image read") })
    }

    @Test fun unicodePagesAndSearchSnippetsKeepCompleteCharactersAndAlwaysAdvance() {
        val refs = JSONArray().put(file())
        val text = "a\uD83D\uDE00b\uD83D\uDE01c"
        for (limit in listOf(1, 2, 3)) {
            val recovered = StringBuilder()
            var offset = 0
            while (offset < text.length) {
                val page = ChatAttachmentContext.page(read(offset, limit), refs) { text }
                assertEquals(offset, page.getInt("offset"))
                recovered.append(page.getString("text"))
                val next = page.getInt("next_offset")
                assertTrue(next > offset); offset = next
            }
            assertEquals(text, recovered.toString())
        }
        val inside = ChatAttachmentContext.page(read(2, 1), refs) { text }
        assertEquals(1, inside.getInt("offset")); assertEquals("\uD83D\uDE00", inside.getString("text"))
        assertEquals(3, inside.getInt("next_offset"))
        val long = "x".repeat(7999) + "\uD83D\uDE00tail"
        val boundary = ChatAttachmentContext.page(read(0, 8000), refs) { long }
        assertEquals(7999, boundary.getString("text").length); assertEquals(7999, boundary.getInt("next_offset"))
        val searchable = "\uD83D\uDE00" + "x".repeat(159) + "needle" + "x".repeat(159) + "\uD83D\uDE01"
        val found = ChatAttachmentContext.page(read(limit = 10).put("operation", "search").put("query", "needle"), refs) { searchable }
        val snippet = found.getJSONArray("hits").getJSONObject(0)
        assertEquals(0, snippet.getInt("snippet_offset")); assertEquals(searchable, snippet.getString("snippet"))
    }

    @Test fun webReadsAreModelChosenAndCanMixWithFilesInTheSameBoundedConversation() {
        val webCalls = mutableListOf<String>()
        var files = 0
        var modelCalls = 0
        val url = "https://example.org/design"
        val answer = ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { files++; "附件中的圆角是12" }, webTool = { args ->
            webCalls += args.getString("intent")
            if (args.getString("intent") == "search_web") JSONObject().put("results", JSONArray()
                .put(JSONObject().put("url", url).put("snippet", "设计文档")))
            else JSONObject().put("url", url).put("text", "网页中的圆角是16").put("has_more", false)
        }) { messages ->
            when (modelCalls++) {
                0 -> {
                    assertEquals(0, files); assertTrue(webCalls.isEmpty())
                    response(JSONObject().put("intent", "search_web").put("query", "圆角设计"))
                }
                1 -> response(JSONObject().put("intent", "read_web").put("url", url))
                2 -> response(read(limit = 8))
                else -> {
                    assertTrue(messages.toString().contains("网页中的圆角是16"))
                    assertTrue(messages.toString().contains("附件中的圆角是12".take(8)))
                    val last = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                    assertEquals(ChatAttachmentContext.MAX_READS - 3, last.getInt("remaining_reads"))
                    response(JSONObject().put("intent", "conversation").put("reply", "对比结果，来源：$url"))
                }
            }
        }
        assertEquals(4, modelCalls); assertEquals(1, files)
        assertEquals(listOf("search_web", "read_web"), webCalls)
        assertEquals(3, answer.getJSONObject("_doppel_reference_reads").getJSONArray("calls").length())
        assertEquals(1, answer.getJSONObject("_doppel_attachment_reads").getJSONArray("calls").length())
        assertFalse(answer.getJSONObject("_doppel_reference_reads").toString().contains(url))

        val noTools = ChatAttachmentContext.chat(prompt(), JSONArray(), { error("no file read") }, webTool = { error("no web request") }) {
            response(JSONObject().put("intent", "conversation").put("reply", "只是讨论网址格式，不读取网站"))
        }
        assertFalse(noTools.has("_doppel_reference_reads"))
    }

    @Test fun websitesAndAttachmentsShareOneReadLimitAndCannotStartATaskAfterExhaustion() {
        var files = 0; var websites = 0; var calls = 0
        val result = ChatAttachmentContext.chat(prompt(), JSONArray().put(file()), { files++; "abcdefgh" }, webTool = {
            websites++; JSONObject().put("text", "网页正文")
        }) { messages ->
            if (calls++ < ChatAttachmentContext.MAX_READS) response(if (calls % 2 == 0) read()
                else JSONObject().put("intent", "read_web").put("url", "https://example.org/"))
            else {
                assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("remaining_reads=0"))
                response(JSONObject().put("intent", "task").put("task_goal", "不应执行"))
            }
        }
        assertEquals(ChatAttachmentContext.MAX_READS, files + websites)
        assertEquals(6, files); assertEquals(6, websites)
        assertTrue(result.getJSONObject("_doppel_reference_reads").getBoolean("incomplete"))
        val final = JSONObject(result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals("conversation", final.getString("intent")); assertEquals(0, final.getJSONArray("memory_changes").length())
    }

    @Test fun webErrorsReturnAsReferenceDataAndTheModelCanAnswerTruthfully() {
        for (throwFailure in listOf(false, true)) {
            var calls = 0
            val result = ChatAttachmentContext.chat(prompt(), JSONArray(), { error("no file read") }, webTool = {
                if (throwFailure) throw java.io.IOException("private-transport-diagnostic")
                JSONObject().put("ok", false).put("error", JSONObject().put("code", "insufficient_content").put("message", "正文不足"))
            }) { messages ->
                if (calls++ == 0) response(JSONObject().put("intent", "read_web").put("url", "https://example.org/"))
                else {
                    val tool = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                    assertEquals("read_web", tool.getString("tool")); assertTrue(tool.getBoolean("reference_only"))
                    assertTrue(tool.getJSONObject("result").has("error"))
                    assertFalse(tool.toString().contains("private-transport-diagnostic"))
                    response(JSONObject().put("intent", "conversation").put("reply", "目前无法读取正文"))
                }
            }
            assertEquals(2, calls)
            assertTrue(result.getJSONObject("_doppel_reference_reads").getJSONArray("calls").getJSONObject(0).getBoolean("error"))
            assertFalse(result.has("_doppel_attachment_reads"))
        }
    }

    @Test fun largeWebPagesShareSerializedBudgetAndEscapedOverflowCanRetryWithoutLosingItsOffset() {
        var calls = 0
        ChatAttachmentContext.chat(prompt(), JSONArray(), { error("no file read") }, webTool = { args ->
            val offset = args.getInt("offset")
            val limit = args.getInt("limit")
            JSONObject().put("text", (if (offset == 30000) "\\" else "x").repeat(limit))
                .put("offset", offset).put("next_offset", offset + limit)
        }) { messages ->
            val results = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
                .filter { it.startsWith("{\"tool\":") || it.contains("\"remaining_context_chars\"") }
            assertTrue(results.sumOf { it.length } <= ChatAttachmentContext.MAX_TOOL_CONTEXT)
            if (calls in 2..3) assertEquals(1, results.size)
            if (calls == 4) {
                val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONObject("result")
                assertTrue(result.getString("error").contains("减小 limit"))
                assertFalse(result.has("next_offset")); assertFalse(result.has("text"))
            }
            val step = calls++
            if (step < 5) response(JSONObject().put("intent", "read_web").put("url", "https://example.org/guide")
                .put("offset", if (step >= 3) 30000 else step * 10000).put("limit", if (step == 4) 4000 else 10000))
            else {
                val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONObject("result")
                assertEquals(30000, result.getInt("offset")); assertEquals(34000, result.getInt("next_offset"))
                assertEquals(4000, result.getString("text").length)
                response(JSONObject().put("intent", "conversation").put("reply", "已取得所需片段"))
            }
        }
        assertEquals(6, calls)
    }

}
