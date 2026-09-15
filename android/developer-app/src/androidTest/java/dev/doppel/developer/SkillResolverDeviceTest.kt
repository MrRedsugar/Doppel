package dev.doppel.developer

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AppLearning
import dev.doppel.sdk.DirectSkills
import dev.doppel.sdk.FirstUseConsent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit local component QA: no models, network, task submission, settings writes or UI input. */
class SkillResolverDeviceTest {
    @Test fun currentAndroidCanReadAndSelectBundledSkillReferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Opt in with -e skill_resolver_live true", InstrumentationRegistry.getArguments().getString("skill_resolver_live") == "true")
        val context = instrumentation.targetContext
        val runsFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val runsBefore = bytes(runsFile)
        val runs = JSONArray(runsBefore?.toString(Charsets.UTF_8) ?: "[]")
        repeat(runs.length()) { index ->
            assertTrue("Finish or pause tasks before component QA", runs.getJSONObject(index).optString("status") in setOf("paused", "completed", "failed", "cancelled"))
        }
        assertTrue("Use existing accepted notices", FirstUseConsent.isAccepted(context))
        val prefNames = listOf("doppel", "doppel_consent", "doppel_learning", "doppel_skill_switches")
        val prefsBefore = prefNames.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val dataBefore = dataFingerprint(context)
        val stages = JSONArray()
        val evidence = JSONObject().put("schema", 1).put("sdk_int", Build.VERSION.SDK_INT)
            .put("model_calls_requested", 0).put("screen_actions_requested", 0).put("network_calls_requested", 0).put("stages", stages)

        fun record(name: String, action: () -> JSONObject): JSONObject? {
            val entry = JSONObject().put("stage", name)
            stages.put(entry)
            return try { action().also { entry.put("ok", true).put("result", it) } }
            catch (failure: Throwable) {
                entry.put("ok", false).put("failure_type", failure.javaClass.name)
                    .put("cause_type", failure.cause?.javaClass?.name ?: JSONObject.NULL)
                    .put("stack_methods", JSONArray(failure.stackTrace.take(16).map { "${it.className}.${it.methodName}" }))
                null
            }
        }
        var catalogue: JSONObject? = null
        var selectedGame: JSONObject? = null
        var selectedWps: JSONObject? = null
        val selectionCases = mutableListOf<Triple<String, JSONObject?, List<Pair<String, String>>>>()
        try {
            // Diagnostic alternatives are independent, so one unsupported pattern does not hide the component failure.
            for ((label, pattern) in listOf("is_han" to "[\\p{IsHan}]+", "script_han" to "[\\p{sc=Han}]+",
                    "basic_han_range" to "[\\u3400-\\u4DBF\\u4E00-\\u9FFF]+")) {
                record("regex_$label") {
                    JSONObject().put("matches", JSONArray(Regex(pattern).findAll("明日方舟 TR-9 保存表格").map { it.value }.toList()))
                }
            }
            record("bundled_assets") {
                JSONObject().put("names", JSONArray(context.assets.list("skills").orEmpty().sorted()))
            }
            record("learned_catalogue") {
                val result = AppLearning(context).store.list()
                JSONObject().put("items_count", result.getJSONArray("items").length()).put("errors_count", result.getJSONArray("errors").length())
            }
            var skills: DirectSkills? = null
            record("direct_skills_init") { skills = DirectSkills(context); JSONObject().put("initialized", true) }
            if (skills != null) {
                val adapter = requireNotNull(skills)
                record("catalogue") {
                    val result = adapter.list().also { catalogue = it }
                    JSONObject().put("items", JSONArray((0 until result.getJSONArray("items").length()).map { index ->
                        val item = result.getJSONArray("items").getJSONObject(index)
                        JSONObject().put("source", item.optString("source")).put("name", if (item.optString("source") == "bundled") item.optString("name") else "private_skill")
                            .put("revision_present", item.optString("revision").isNotBlank())
                    })).put("errors_count", result.optJSONArray("errors")?.length() ?: 0)
                }
                for (name in listOf("arknights", "wps")) {
                    var revision: String? = null
                    var resourcePath: String? = null
                    assertNotNull("Required bundled read failed", record("read_$name") {
                        val body = adapter.read(name)
                        assertTrue("Bundled body must load", body.optBoolean("found"))
                        revision = body.getString("revision")
                        resourcePath = body.getJSONArray("resources").getString(0)
                        JSONObject().put("name", name).put("source", body.optString("source")).put("revision", revision)
                            .put("chars", body.optString("instructions").length).put("resource_count", body.optJSONArray("resources")?.length() ?: 0)
                    })
                    assertNotNull("Required bundled resource failed", record("resource_$name") {
                        val result = adapter.resource(name, requireNotNull(resourcePath), revision)
                        assertTrue("Bundled reference must load", result.optBoolean("found"))
                        JSONObject().put("found", true).put("chars", result.optString("content").length).put("revision", result.optString("revision"))
                    })
                }
                assertNotNull("Retired learning must remain unavailable", record("learning_removed") {
                    for (name in listOf("learned-component-check", "manual-component-check")) {
                        for (result in listOf(adapter.read(name), adapter.resource(name, "trace.json"))) {
                            assertFalse(result.optBoolean("found", true))
                            assertEquals("application_learning_removed", result.optString("reason"))
                        }
                    }
                    JSONObject().put("rejected_reads", 4)
                })
                record("search_catalogue") {
                    val result = adapter.list("明日方舟", 0, 20)
                    JSONObject().put("total", result.optInt("total", -1))
                }
                fun selected(result: JSONObject): JSONObject {
                    val items = result.optJSONArray("items") ?: JSONArray()
                    return JSONObject().put("found", result.optBoolean("found")).put("items", JSONArray((0 until items.length()).map { index ->
                        val item = items.getJSONObject(index)
                        JSONObject().put("name", if (item.optString("source") == "bundled") item.optString("name") else "private_skill")
                            .put("source", item.optString("source")).put("revision", item.optString("revision"))
                            .put("chars", item.optString("instructions").length).put("reference_kind", item.optString("reference_kind"))
                            .put("scope", item.optString("scope")).put("match_source", item.optJSONObject("match")?.optString("source"))
                            .put("grants_execution_authority", item.optBoolean("grants_execution_authority", true))
                            .put("trusted", item.optBoolean("trusted", true))
                    })).put("instruction_chars", result.optInt("instruction_chars", 0))
                }
                record("relevant_game") {
                    selected(adapter.relevant("打开明日方舟，进入 TR-9 准备界面，先别开始", "com.hypergryph.arknights.bilibili", "终端总览"))
                        .also { selectedGame = it }
                }
                record("relevant_wps") {
                    selected(adapter.relevant("用 WPS 新建表格，写入中文并命名保存", "cn.wps.moffice_eng", "空白工作簿"))
                        .also { selectedWps = it }
                }
                fun selectionCase(label: String, goal: String, foreground: String, screen: String, expected: List<Pair<String, String>>) {
                    val value = record(label) { selected(adapter.relevant(goal, foreground, screen)) }
                    selectionCases += Triple(label, value, expected)
                }
                selectionCase("task_game_from_wps", "打开明日方舟，进入 TR-9 准备界面", "cn.wps.moffice_eng", "WPS 表格工作簿", listOf("arknights" to "task_app"))
                selectionCase("task_game_from_launcher", "打开明日方舟，进入 TR-9 准备界面", "com.android.launcher3", "桌面", listOf("arknights" to "task_app"))
                selectionCase("task_wps_from_game", "用 WPS 新建表格并保存", "com.hypergryph.arknights.bilibili", "明日方舟 终端", listOf("wps" to "task_app"))
                selectionCase("task_wps_from_launcher", "用 WPS 新建表格并保存", "com.android.launcher3", "桌面", listOf("wps" to "task_app"))
                selectionCase("two_explicit_task_apps", "先用 WPS 保存表格，再打开明日方舟", "com.android.launcher3", "桌面", listOf("wps" to "task_app", "arknights" to "task_app"))
                selectionCase("unnamed_goal_from_launcher", "新建表格并保存", "com.android.launcher3", "设置", listOf("mobile-ui" to "generic"))
                selectionCase("unnamed_goal_from_wps", "新建表格并保存", "cn.wps.moffice_eng", "空白工作簿", listOf("wps" to "current_app"))
            }
        } finally {
            val prefsUnchanged = prefNames.all { prefsBefore[it] == context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
            val runsAfter = bytes(runsFile)
            val runsUnchanged = if (runsBefore == null) runsAfter == null else runsAfter != null && runsBefore.contentEquals(runsAfter)
            val dataUnchanged = dataBefore == dataFingerprint(context)
            evidence.put("settings_unchanged", prefsUnchanged).put("run_state_unchanged", runsUnchanged).put("skill_data_unchanged", dataUnchanged)
            File(context.filesDir, "skills-resolver-device.json").writeText(evidence.toString(2), Charsets.UTF_8)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "SKILL_RESOLVER_COMPONENT ${evidence}\n") })
            assertTrue("Read-only component QA must preserve settings, runs and skill data", prefsUnchanged && runsUnchanged && dataUnchanged)
        }
        assertNotNull("Full catalogue stage failed; inspect component evidence", catalogue)
        assertEquals("Every scope scenario must execute", 7, selectionCases.size)
        for ((label, result, expected) in selectionCases) {
            assertNotNull("$label stage failed; inspect component evidence", result)
            val value = requireNotNull(result)
            assertTrue("$label requires relevant reference", value.optBoolean("found"))
            val items = value.getJSONArray("items")
            assertTrue(items.length() in expected.size..2)
            for ((index, identity) in expected.withIndex()) {
                val item = items.getJSONObject(index)
                assertEquals("$label selects intended app", identity.first, item.getString("name"))
                assertEquals("$label identifies selection scope", identity.second, item.getString("scope"))
                assertEquals(if (identity.second == "task_app") "goal" else if (identity.second == "current_app") "foreground_package" else "goal_and_screen_terms",
                    item.getString("match_source"))
            }
            if (expected.size == 1 && items.length() == 2) assertEquals("generic", items.getJSONObject(1).getString("scope"))
            assertTrue(value.getInt("instruction_chars") <= 6500)
            repeat(items.length()) { index ->
                assertFalse(items.getJSONObject(index).getBoolean("trusted"))
                assertFalse(items.getJSONObject(index).getBoolean("grants_execution_authority"))
            }
        }
        for ((label, result, expected) in listOf(Triple("game", selectedGame, "arknights"), Triple("wps", selectedWps, "wps"))) {
            assertNotNull("$label relevant stage failed; inspect component evidence", result)
            val value = requireNotNull(result)
            assertTrue("$label must have applicable knowledge", value.optBoolean("found"))
            val items = value.getJSONArray("items")
            assertTrue("$label must select its bundled package", (0 until items.length()).any { items.getJSONObject(it).optString("name") == expected })
            assertTrue(value.getInt("instruction_chars") <= 6500)
            repeat(items.length()) { index ->
                assertFalse(items.getJSONObject(index).getBoolean("trusted"))
                assertEquals("untrusted_knowledge", items.getJSONObject(index).getString("reference_kind"))
            }
        }
    }

    private fun bytes(file: File): ByteArray? = if (file.isFile) file.readBytes() else null
    private fun dataFingerprint(context: Context): Map<String, String> = listOf("direct-skills-v1", "learned-skills-v1").flatMap { name ->
        val root = File(context.noBackupFilesDir, name)
        if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.map { file ->
            "$name/${file.relativeTo(root).invariantSeparatorsPath}" to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toList()
    }.toMap()
}
