package dev.doppel.sdk

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearnedSkillStoreTest {
    private val root = Files.createTempDirectory("learned-skills-").toFile()
    private var app = JSONObject().put("package_name", "com.android.settings").put("version_code", "28").put("version_name", "9").put("system", "android-28").put("locale", "zh-CN")
    private fun store() = LearnedSkillStore(root) { app }
    @Test fun hostRootMayHaveAnAliasButStoredChildrenStayContained() {
        val alias = java.io.File(root, "../${root.name}")
        val aliased = LearnedSkillStore(alias) { app }
        val name = aliased.save(trace()).getString("name")
        assertTrue(store().read(name).getBoolean("found"))
        assertThrows(IllegalArgumentException::class.java) { aliased.delete("../outside") }
    }
    private fun trace(id: String = "run-a"): JSONObject {
        val step = JSONObject().put("kind", "tap").put("label", "显示")
            .put("evidence_id", "command-a").put("before", JSONObject().put("screen_id", "a").put("labels", JSONArray(listOf("设置"))))
            .put("after", JSONObject().put("screen_id", "b").put("labels", JSONArray(listOf("显示", "字体大小"))))
        return JSONObject().put("app", app).put("source_id", id).put("origin", "completed_task")
            .put("proves_business_success", false).put("steps", JSONArray().put(step))
    }
    @Test fun generatesStandardSkillAndReloadsWithoutAnyModelCall() {
        val item = store().save(trace()); val name = item.getString("name")
        val read = store().read(name)
        assertTrue(read.getBoolean("found")); assertFalse(read.getBoolean("trusted"))
        assertTrue(read.getString("instructions").contains("显示"))
        val target = DirectSkillStore(Files.createTempDirectory("learned-import-").toFile())
        val imported = target.importPackage(store().export(name), "zip")
        assertEquals(name, imported.getString("name")); assertTrue(target.read(name).getString("instructions").contains("重新"))
        assertNotNull(store().relevant("打开显示设置", "com.android.settings"))
        assertNull(store().relevant("查看蓝牙", "com.android.settings"))
        assertNull(store().relevant("打开显示设置", "other.app"))
    }
    @Test fun sameTaskIsIdempotentAndSeparateSuccessesIncreaseObservationCount() {
        val name = store().save(trace()).getString("name")
        store().save(trace()); assertEquals(1, store().read(name).getInt("observations"))
        store().save(trace("run-b")); assertEquals(2, store().read(name).getInt("observations"))
        assertEquals(1, store().list().getJSONArray("items").length())
    }
    @Test fun versionsDisableAndDeletionAreEnforcedByReadsAndRelevance() {
        val name = store().save(trace()).getString("name")
        store().setEnabled(name, false)
        assertFalse(store().read(name).getBoolean("found")); assertNull(store().relevant("打开显示设置", "com.android.settings"))
        store().setEnabled(name, true); app = JSONObject(app.toString()).put("version_code", "29")
        assertFalse(store().read(name).getBoolean("found")); assertEquals("version_changed", store().read(name, inspect = true).getString("availability"))
        store().delete(name); assertEquals(0, store().list().getJSONArray("items").length())
    }
    @Test fun unknownFieldsSensitiveLabelsAndPathTraversalAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { store().save(trace().put("approved", true)) }
        val secret = trace(); secret.getJSONArray("steps").getJSONObject(0).put("label", "验证码 123456")
        assertThrows(IllegalArgumentException::class.java) { store().save(secret) }
        assertThrows(IllegalArgumentException::class.java) { store().delete("../outside") }
        assertFalse(store().read("../outside").getBoolean("found"))
    }
    @Test fun semanticIdentityIsIndependentOfJsonKeyOrderAndRoutePagesRemainDistinct() {
        val normal = store().save(trace()).getString("name")
        val reordered = trace("run-b").put("app", JSONObject().apply {
            app.keys().asSequence().toList().reversed().forEach { put(it, app.get(it)) }
        })
        assertEquals(normal, store().save(reordered).getString("name"))
        val other = trace("run-c")
        other.getJSONArray("steps").getJSONObject(0).getJSONObject("after").put("labels", JSONArray(listOf("显示", "投屏")))
        assertNotEquals(normal, store().save(other).getString("name"))
        val revision = store().read(normal).getString("revision")
        store().save(trace("run-d"))
        assertFalse(store().read(normal, revision).getBoolean("found"))
    }
}
