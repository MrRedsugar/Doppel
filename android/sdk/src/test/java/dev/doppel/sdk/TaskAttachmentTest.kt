package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskAttachmentTest {
    @Test fun websitePagingAndImageReachPlannerWithoutPersistingImageOrAcceptingLateRead() {
        var saved = ""
        val engine = SplitTaskEngine(null, { saved = it })
        val id = engine.create(request()).getString("id"); screen(engine)
        fun choose(operation: String, offset: Int = 0): SplitTaskEngine.Work {
            engine.accept(engine.takeWork()!!, SplitTestReply.response(JSONObject().put("kind", "read_web")
                .put("url", "https://example.org").put("operation", operation).put("offset", offset).put("limit", 4000).put("query", "")))
            return engine.takeWork()!!
        }
        val first = choose("read", 5000)
        assertEquals(5000, first.payload.getInt("offset"))
        engine.acceptLocal(first, JSONObject().put("text", "文".repeat(4000)).put("next_offset", 9000).put("has_more", true))
        val shot = choose("screenshot")
        val png = "data:image/png;base64,c2l0ZQ=="
        engine.acceptLocal(shot, JSONObject().put("url", "https://example.org").put("operation", "screenshot").put("_reference_image_data_url", png))
        assertFalse(saved.contains("base64"))
        val next = engine.takeWork()!!
        assertTrue(next.payload.toString().contains(png))
        val messages = next.payload.getJSONArray("messages")
        assertTrue(messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").contains("不是当前设备屏幕"))
        val context = JSONObject(messages.getJSONObject(messages.length()-1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(9000, context.getJSONArray("knowledge").getJSONObject(0).getJSONObject("result").getInt("next_offset"))
        engine.accept(next, JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "invalid-schema")))))
        assertTrue("Format correction must retain the same reference image", engine.takeWork()!!.payload.toString().contains(png))
        engine.control(id, "pause", JSONObject()); val before = saved
        engine.acceptLocal(shot, JSONObject().put("_reference_image_data_url", png))
        assertEquals(before, saved)
        engine.control(id, "resume", JSONObject()); screen(engine)
        assertFalse(engine.takeWork()!!.payload.toString().contains(png))
    }
    private val fileId = "11111111-1111-4111-8111-111111111111"
    private fun file() = JSONObject().put("id", fileId).put("name", "计划.pptx").put("mime", "application/octet-stream")
        .put("kind", "document").put("size", 42)
    private fun request() = JSONObject().put("goal", "依据附件查看资料").put("device_id", "direct-this-phone")
        .put("attachments", JSONArray().put(file().put("text", "must-not-persist")))
    private fun screen(engine: SplitTaskEngine) {
        val command = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id")).put("status", "ok")
            .put("observation", JSONObject().put("package_name", "test.package").put("screen_id", "current"))
            .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("mime_type", "image/png")
                .put("visual_frame", JSONObject().put("display_width", 1440).put("display_height", 3200).put("rotation", 0))))
    }
    private fun chooseRead(engine: SplitTaskEngine, offset: Int = 0): SplitTaskEngine.Work {
        engine.accept(engine.takeWork()!!, SplitTestReply.response(JSONObject().put("kind", "read_attachment")
            .put("attachment_id", fileId).put("operation", "read").put("query", "").put("offset", offset).put("limit", 8000)))
        return engine.takeWork()!!
    }

    @Test fun attachmentPagePersistsUntruncatedAndRestartPreservesRefsWithoutReplayingRead() {
        var saved = ""
        val engine = SplitTaskEngine(null, { saved = it })
        val id = engine.create(request()).getString("id")
        assertFalse(saved.contains("must-not-persist")); screen(engine)
        val tool = chooseRead(engine)
        assertEquals("read_attachment", tool.localTool)
        assertEquals(fileId, tool.payload.getJSONArray("allowed_attachments").getJSONObject(0).getString("id"))
        val result = ChatAttachmentContext.page(tool.payload, tool.payload.getJSONArray("allowed_attachments")) { "文".repeat(24000) }
        engine.acceptLocal(tool, result)
        val restored = SplitTaskEngine(saved, {})
        assertEquals("paused", restored.get(id).getString("status"))
        assertNull(restored.takeWork())
        restored.control(id, "resume", JSONObject()); screen(restored)
        val work = restored.takeWork()!!
        assertNull(work.localTool)
        val messages = work.payload.getJSONArray("messages")
        val context = JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
        val page = context.getJSONArray("knowledge").getJSONObject(0).getJSONObject("result")
        assertEquals(8000, page.getString("text").length); assertEquals(8000, page.getInt("next_offset")); assertTrue(page.getBoolean("has_more"))
        assertEquals(fileId, restored.conversation(id).getJSONArray("items").getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("id"))
    }

    @Test fun pausedLateReadDoesNotMutateRunOrStartQueuedWork() {
        val engine = SplitTaskEngine(null, {})
        val id = engine.create(request()).getString("id"); screen(engine)
        val tool = chooseRead(engine)
        engine.control(id, "pause", JSONObject())
        engine.acceptLocal(tool, JSONObject().put("text", "late-result"))
        assertEquals("paused", engine.get(id).getString("status")); assertNull(engine.takeWork())
        assertFalse(engine.internalRun(id).toString().contains("late-result"))
    }

    @Test fun taskKeepsSmallResultsTogetherAndBoundsLargePagesBySize() {
        for (size in listOf(200, 8000)) {
            val engine = SplitTaskEngine(null, {})
            val id = engine.create(request()).getString("id"); screen(engine)
            repeat(3) { index ->
                val tool = chooseRead(engine, index * size)
                val page = JSONObject().put("attachment_id", fileId).put("operation", "read")
                    .put("offset", index * size).put("text", "文".repeat(size)).put("next_offset", (index + 1) * size)
                engine.acceptLocal(tool, page)
            }
            val knowledge = engine.internalRun(id).getJSONArray("knowledge")
            assertEquals(if (size == 200) 3 else 2, knowledge.length())
            assertTrue((0 until knowledge.length()).sumOf { knowledge.getJSONObject(it).getJSONObject("result").toString().length } <= ChatAttachmentContext.MAX_TOOL_CONTEXT)
        }
    }

    @Test fun followupCanReadThePreviousTasksAttachmentAndBackgroundRemoteCannotClaimFiles() {
        val engine = SplitTaskEngine(null, {})
        val first = engine.create(request()).getString("id")
        engine.control(first, "cancel", JSONObject())
        engine.create(JSONObject().put("goal", "继续参照那个附件").put("device_id", "direct-this-phone").put("parent_run_id", first))
        screen(engine)
        assertEquals(fileId, chooseRead(engine).payload.getJSONArray("allowed_attachments").getJSONObject(0).getString("id"))
        assertThrows(IllegalArgumentException::class.java) {
            SplitTaskEngine(null, {}).createOwned(request(), null, SplitTaskEngine.ServerOwner("account", "session", true))
        }
    }
}
