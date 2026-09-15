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
}
