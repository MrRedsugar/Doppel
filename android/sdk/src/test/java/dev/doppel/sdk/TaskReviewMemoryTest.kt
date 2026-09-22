package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TaskReviewMemoryTest {
    private fun run(): JSONObject = JSONObject().put("id", "run-1").put("goal", "登录并查看余额")
        .put("status", "failed").put("events", JSONArray().put(JSONObject().put("message", "点击保存失败，password=secret123"))
            .put(JSONObject().put("message", "已返回桌面").put("created_at", 123L)))
        .put("task_state", JSONObject().put("phase", "登录").put("facts", JSONArray().put("登录页已打开")))

    @Test fun reviewRedactsSecretsAndDoesNotPersist() {
        val file = Files.createTempFile("review-memory", ".json").toFile(); file.delete()
        val store = TaskReviewMemory(file)
        val review = store.review(run())
        assertTrue(review.toString().contains("[已隐藏]"))
        assertFalse(file.exists())
        assertEquals(0, store.list().getJSONArray("items").length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveRequiresConfirmation() {
        val file = Files.createTempFile("review-memory", ".json").toFile(); file.delete()
        TaskReviewMemory(file).save(run(), JSONObject().put("correction", "以后先查看页面").put("confirmed", false))
    }

    @Test fun saveAndDeleteOnlyConfirmedSemanticLesson() {
        val file = Files.createTempFile("review-memory", ".json").toFile(); file.delete()
        val store = TaskReviewMemory(file)
        val saved = store.save(run(), JSONObject().put("confirmed", true).put("when", "遇到保存失败").put("correction", "先核对当前页面，再重新定位保存按钮").put("avoid", "不要重复旧坐标"))
        assertTrue(saved.getBoolean("saved")); assertEquals(1, store.list().getJSONArray("items").length())
        assertTrue(store.delete(saved.getString("id")).getBoolean("deleted")); assertEquals(0, store.list().getJSONArray("items").length())
    }
    @Test fun reviewRetainsCoarseProgressWithTheSameSecretRedactionAndNoExtraFields() {
        val file = Files.createTempFile("review-progress", ".json").toFile(); file.delete()
        val run = run().apply { getJSONObject("task_state").put("progress", JSONObject()
            .put("plan", JSONArray().put("打开应用").put("输入 password=private-fixture").put("完成目标"))
            .put("completed", 1).put("total_known", true).put("image", "private-screen-payload")) }
        val progress = TaskReviewMemory(file).review(run).getJSONObject("task_state").getJSONObject("progress")
        assertEquals(3, progress.getJSONArray("plan").length())
        assertEquals(1, progress.getInt("completed")); assertTrue(progress.getBoolean("total_known"))
        assertTrue(progress.toString().contains("[已隐藏]"))
        assertFalse(progress.toString().contains("private-fixture")); assertFalse(progress.has("image"))
        assertFalse(file.exists())
    }

    @Test fun legacyRecordsRemainVisibleAndEditableWithoutDiscardingHistory() {
        val file = Files.createTempFile("memory-legacy", ".json").toFile()
        val old = JSONArray((0 until 70).map { JSONObject().put("id", "old-$it").put("correction", "原有记忆 $it").put("created_at", it.toLong()) })
        file.writeText(JSONObject().put("version", 1).put("items", old).toString())
        val store = TaskReviewMemory(file)
        val visible = store.listAll().getJSONArray("items")
        assertEquals(70, visible.length())
        assertEquals("原有记忆 69", visible.getJSONObject(0).getString("content"))
        assertEquals("global", visible.getJSONObject(0).getString("scope"))
        val updated = store.update("old-0", JSONObject().put("content", "修改原有记忆"), 1)
        assertEquals("修改原有记忆", updated.getString("correction"))
        assertEquals(2L, updated.getLong("revision"))
        assertEquals(0L, updated.getLong("created_at"))
        assertEquals(70, TaskReviewMemory(file).listAll().getJSONArray("items").length())
        assertTrue(store.delete("old-0", 2).getBoolean("deleted"))
    }

    @Test fun fullStoreAndInvalidContentPreserveExistingFile() {
        val file = Files.createTempFile("memory-full", ".json").toFile()
        val items = JSONArray((0 until 100).map { JSONObject().put("id", "old-$it").put("correction", "原有记忆 $it") })
        file.writeText(JSONObject().put("version", 1).put("items", items).toString())
        val store = TaskReviewMemory(file); val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) { store.create(JSONObject().put("content", "新增记忆")) }
        for (value in listOf("一", "字".repeat(1601), "password=fixture-only")) {
            assertThrows(IllegalArgumentException::class.java) { store.update("old-0", JSONObject().put("content", value)) }
        }
        assertEquals(before, file.readText())
        assertEquals(100, store.listAll().getJSONArray("items").length())
    }

    @Test fun batchIsAtomicAndRefusesStaleOrUnknownRecordIds() {
        val file = Files.createTempFile("memory-atomic", ".json").toFile(); file.delete()
        val store = TaskReviewMemory(file)
        val item = store.create(JSONObject().put("content", "优先保存草稿"))
        val id = item.getString("id")
        store.update(id, JSONObject().put("content", "用户后来更改为先预览"), 1)
        val before = file.readText()
        val create = JSONObject().put("op", "upsert").put("content", "以后先核对标题")
        for (bad in listOf(
            JSONObject().put("op", "upsert").put("id", "unknown").put("content", "不能创建自定编号"),
            JSONObject().put("op", "delete").put("id", id).put("expected_revision", 1),
            JSONObject().put("op", "upsert").put("id", id).put("content", "必须检查原版本")
        )) {
            assertThrows(IllegalArgumentException::class.java) { store.applyChanges(JSONArray().put(create).put(bad), "chat", "message") }
            assertEquals(before, file.readText())
        }
        val result = store.applyChanges(JSONArray().put(create).put(JSONObject().put("op", "delete").put("id", id).put("expected_revision", 2)), "chat", "message")
        assertEquals(1, result.getInt("saved")); assertEquals(1, result.getInt("deleted"))
        assertEquals(1, store.listAll().getJSONArray("items").length())
    }

    @Test fun replayCannotUndoUserEditsOrResurrectDeletedMemory() {
        val file = Files.createTempFile("memory-replay", ".json").toFile(); file.delete()
        val store = TaskReviewMemory(file)
        val changes = JSONArray().put(JSONObject().put("op", "upsert").put("content", "以后先核对收件人"))
        val result = store.applyChanges(changes, "chat", "message", "run-1", "给同事发送邮件")
        val item = result.getJSONArray("items").getJSONObject(0); val id = item.getString("id")
        assertEquals("message", item.getString("source_message_id")); assertEquals("run-1", item.getString("source_run_id"))
        store.update(id, JSONObject().put("content", "设置修改后以此条为准"), 1)
        assertTrue(TaskReviewMemory(file).applyChanges(changes, "chat", "message").getBoolean("duplicate"))
        assertEquals("设置修改后以此条为准", store.listAll().getJSONArray("items").getJSONObject(0).getString("content"))
        store.delete(id, 2)
        val replay = TaskReviewMemory(file).applyChanges(changes, "chat", "message")
        assertTrue(replay.getBoolean("duplicate")); assertEquals(0, replay.getInt("applied"))
        assertEquals(0, store.listAll().getJSONArray("items").length())
        val differentMessage = store.applyChanges(changes, "chat", "message-2")
        assertEquals(1, differentMessage.getInt("saved"))
        val sameText = store.applyChanges(changes, "chat", "message-3")
        assertEquals(0, sameText.getInt("saved")); assertEquals(1, store.listAll().getJSONArray("items").length())
    }

    @Test fun contextUsesExactScopeAndBoundsAllIncludedTextWhileSettingsKeepEverything() {
        val file = Files.createTempFile("memory-context", ".json").toFile(); file.delete()
        var time = 0L
        val store = TaskReviewMemory(file) { ++time }
        store.create(JSONObject().put("content", "全局偏好先看预览"))
        store.create(JSONObject().put("content", "其他应用不应泄露的记忆").put("package_name", "com.example.other"))
        assertEquals(1, store.context().getJSONArray("items").length())
        repeat(5) { store.create(JSONObject().put("content", "$it" + "长记忆\"\\".repeat(150)).put("package_name", "com.example.target")
            .put("goal", "目标".repeat(250)).put("when", "条件".repeat(250)).put("avoid", "避免".repeat(250))) }
        val context = store.context("com.example.target")
        assertTrue(context.toString().length <= 3200)
        assertTrue(context.getJSONArray("items").length() <= 4)
        val all = store.listAll().getJSONArray("items")
        val originals = (0 until all.length()).map { all.getJSONObject(it) }.associate { it.getString("id") to it.getString("content") }
        val included = context.getJSONArray("items")
        repeat(included.length()) { assertEquals(originals[included.getJSONObject(it).getString("id")], included.getJSONObject(it).getString("content")) }
        assertFalse(context.toString().contains("其他应用不应泄露的记忆"))
        assertEquals(7, store.listAll().getJSONArray("items").length())
        assertTrue(store.listAll().getJSONArray("items").getJSONObject(0).getString("content").length > 700)
        assertEquals(1, store.context("com.example").getJSONArray("items").length())
    }

    @Test fun responseReceiptCommitsWithMemoryAndEvictionDoesNotRepeatChanges() {
        val file = Files.createTempFile("memory-receipt", ".json").toFile(); file.delete()
        val store = TaskReviewMemory(file)
        val changes = JSONArray().put(JSONObject().put("op", "upsert").put("content", "记住只使用指定应用"))
        val response = JSONObject().put("intent", "conversation").put("reply", "好的，以后按这个要求操作。").put("confidence", 0.98)
            .put("raw_payload", "must-not-persist")
        store.applyChanges(changes, "chat", "first", response = response)
        val receipt = TaskReviewMemory(file).responseFor("chat", "first")!!
        assertEquals(response.getString("reply"), receipt.getString("reply"))
        assertEquals(0.98, receipt.getDouble("confidence"), 0.0)
        assertEquals(1, receipt.getJSONObject("memory_result").getInt("saved"))
        assertFalse(file.readText().contains("must-not-persist"))
        repeat(32) { store.applyChanges(JSONArray(), "chat", "next-$it", response = response) }
        assertNull(store.responseFor("chat", "first"))
        assertNotNull(store.responseFor("chat", "next-31"))
        val id = store.listAll().getJSONArray("items").getJSONObject(0).getString("id")
        store.delete(id)
        assertTrue(store.applyChanges(changes, "chat", "first", response = response).getBoolean("duplicate"))
        assertEquals(0, store.listAll().getJSONArray("items").length())
        val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.applyChanges(changes, "chat", "invalid", response = JSONObject().put("reply", "长".repeat(12001)))
        }
        assertEquals(before, file.readText()); assertNull(store.responseFor("chat", "invalid"))
    }
}
