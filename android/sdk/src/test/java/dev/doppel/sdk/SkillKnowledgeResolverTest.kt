package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillKnowledgeResolverTest {
    private fun bundle(name: String, packages: String = "", body: String = "Create spreadsheet and inspect the saved workbook.", references: Map<String, String> = emptyMap()): Pair<String, Map<String, ByteArray>> {
        val header = "---\nname: $name\ndescription: spreadsheet workbook editing\naliases: [表格, 新建, 保存]\nplatforms: [android]\n$packages\n---\n$body"
        return name to (mapOf("SKILL.md" to header.toByteArray()) + references.mapValues { it.value.toByteArray() })
    }
    private fun store(vararg bundles: Pair<String, Map<String, ByteArray>>) = DirectSkillStore(Files.createTempDirectory("skill-resolver-").toFile(), mapOf(*bundles))
    private fun resolver(store: DirectSkillStore, catalogue: () -> JSONObject = { store.list() }) = SkillKnowledgeResolver(catalogue,
        { name, revision, offset, limit -> store.read(name, revision, offset, limit) },
        { name, path, revision, offset, limit -> store.resource(name, path, revision, offset, limit) })

    @Test fun matchingKnowledgeIsCurrentAppPlusGenericAndAlwaysUntrusted() {
        val store = store(bundle("wps", "packages: [cn.wps.moffice_eng]"), bundle("game", "packages: [com.hypergryph.arknights]"), bundle("mobile-ui"))
        val result = resolver(store).relevant("新建表格并保存", "cn.wps.moffice_eng", "空白工作簿")
        val items = result.getJSONArray("items")
        assertTrue(result.getBoolean("found")); assertEquals(2, items.length())
        assertEquals("wps", items.getJSONObject(0).getString("name")); assertEquals("mobile-ui", items.getJSONObject(1).getString("name"))
        repeat(items.length()) { index ->
            val item = items.getJSONObject(index)
            assertFalse(item.getBoolean("trusted")); assertEquals("untrusted_knowledge", item.getString("reference_kind"))
            assertEquals(store.read(item.getString("name")).getString("revision"), item.getString("revision"))
        }
    }
    @Test fun unrelatedPackageOrGoalDoesNotForceAnAutomaticReference() {
        val resolver = resolver(store(bundle("wps", "packages: [cn.wps.moffice_eng]")))
        assertFalse(resolver.relevant("spreadsheet", "com.other.app", "spreadsheet").getBoolean("found"))
        assertFalse(resolver.relevant("weather forecast", "cn.wps.moffice_eng", "rainy sky").getBoolean("found"))
        assertFalse(resolver.relevant("spreadsheet", "", "").getBoolean("found"))
    }
    @Test fun staleCatalogueCannotInjectChangedInstructions() {
        val store = store(bundle("wps", "packages: [cn.wps.moffice_eng]"))
        val stale = store.list().apply { getJSONArray("items").getJSONObject(0).put("revision", "old") }
        assertFalse(resolver(store) { stale }.relevant("spreadsheet", "cn.wps.moffice_eng", "").getBoolean("found"))
    }
    @Test fun excerptsFindRelevantTextPastFiveThousandAndStayWithinBudget() {
        val filler = (1..100).joinToString("\n\n") { "Decorative background and historical introduction $it." }
        val guide = filler + "\n\n保存表格: inspect the visible saved filename and read back the workbook."
        assertTrue(guide.indexOf("保存表格") > 5000)
        val store = store(bundle("wps", "packages: [cn.wps.moffice_eng]", "表格资料见 references/workbook.md。\n\n" + filler,
            mapOf("references/workbook.md" to guide)), bundle("mobile-ui", body = "保存表格后应读回内容。\n\n" + filler))
        val result = resolver(store).relevant("保存表格", "cn.wps.moffice_eng", "workbook")
        val items = result.getJSONArray("items")
        val combined = (0 until items.length()).joinToString("") { items.getJSONObject(it).getString("instructions") }
        assertTrue(combined.contains("read back the workbook")); assertTrue(combined.length <= 6500)
        assertTrue(items.getJSONObject(0).getJSONArray("excerpts").toString().contains("references/workbook.md"))
    }
    @Test fun oneCandidatePerScopeAndNonAndroidPackagesAreExcluded() {
        val ios = bundle("ios", "packages: [cn.wps.moffice_eng]").let { (name, files) ->
            name to files.mapValues { (_, bytes) -> bytes.toString(Charsets.UTF_8).replace("platforms: [android]", "platforms: [ios]").toByteArray() }
        }
        val items = resolver(store(bundle("wps", "packages: [cn.wps.moffice_eng]"), bundle("wps-extra", "packages: [cn.wps.moffice_eng]"),
            bundle("generic-one"), bundle("generic-two"), ios)).relevant("spreadsheet", "cn.wps.moffice_eng", "").getJSONArray("items")
        assertEquals(2, items.length()); assertFalse(items.toString().contains("\"name\":\"ios\""))
    }
    @Test fun bundledTaskReferencesIncludeNavigationForTr9AndEditingForWps() {
        val assets = listOf(File("src/main/assets/skills"), File("android/sdk/src/main/assets/skills"))
            .first { it.isDirectory }
        val bundles = assets.listFiles()!!.filter { it.isDirectory }.associate { directory ->
            directory.name to directory.walkTopDown().filter { it.isFile }.associate { file ->
                file.relativeTo(directory).invariantSeparatorsPath to file.readBytes()
            }
        }
        val resolver = resolver(DirectSkillStore(Files.createTempDirectory("bundled-skill-resolver-").toFile(), bundles))
        val game = resolver.relevant("打开明日方舟，进入 TR-9 准备界面，先别开始", "com.hypergryph.arknights.bilibili", "终端总览")
        val gameItem = game.getJSONArray("items").getJSONObject(0)
        assertEquals("arknights", gameItem.getString("name"))
        assertTrue(gameItem.getJSONArray("excerpts").toString().contains("references/navigation.md"))
        // The reference must disambiguate the real chapter page rather than
        // retain the superseded assumption that this transition was never observed.
        assertTrue(gameItem.getString("instructions").contains("Main Story"))
        assertTrue(gameItem.getString("instructions").contains("曲谱"))
        assertTrue(gameItem.getString("instructions").contains("EP01"))
        assertTrue(gameItem.getString("instructions").contains("仍未验证"))
        val wps = resolver.relevant("用 WPS 新建表格，写入中文并命名保存", "cn.wps.moffice_eng", "空白工作簿")
        val wpsItem = wps.getJSONArray("items").getJSONObject(0)
        assertEquals("wps", wpsItem.getString("name"))
        assertTrue(wpsItem.getJSONArray("excerpts").toString().contains("references/cells-and-input.md"))
        assertTrue(wpsItem.getJSONArray("excerpts").toString().contains("references/save-and-verify.md"))
    }
    @Test fun explicitTaskAppWinsOverDifferentForegroundAndLauncher() {
        val store = store(bundle("arknights", "packages: [com.hypergryph.arknights]\napp_aliases: [明日方舟]"),
            bundle("wps", "packages: [cn.wps.moffice_eng]\napp_aliases: [WPS]"), bundle("mobile-ui"))
        val resolver = resolver(store)
        for (foreground in listOf("cn.wps.moffice_eng", "com.android.launcher3", "")) {
            val items = resolver.relevant("打开明日方舟", foreground, "WPS 表格工作簿").getJSONArray("items")
            val app = items.getJSONObject(0)
            assertEquals("arknights", app.getString("name")); assertEquals("task_app", app.getString("scope"))
            assertEquals("goal", app.getJSONObject("match").getString("source"))
            assertEquals("app_aliases", app.getJSONObject("match").getString("field"))
            assertEquals("明日方舟", app.getJSONObject("match").getString("alias"))
            assertFalse((0 until items.length()).any { items.getJSONObject(it).getString("name") == "wps" })
        }
        val wps = resolver.relevant("打开 WPS 新建表格", "com.hypergryph.arknights", "明日方舟 终端")
        assertEquals("wps", wps.getJSONArray("items").getJSONObject(0).getString("name"))
        assertEquals("task_app", wps.getJSONArray("items").getJSONObject(0).getString("scope"))
    }
    @Test fun topicAliasesDoNotInventTaskAppsAndUnnamedGoalsRetainForegroundScope() {
        val store = store(bundle("wps", "packages: [cn.wps.moffice_eng]\napp_aliases: [WPS]"), bundle("mobile-ui"))
        val resolver = resolver(store)
        val generic = resolver.relevant("新建表格并保存", "com.android.launcher3", "设置").getJSONArray("items")
        assertEquals(1, generic.length()); assertEquals("generic", generic.getJSONObject(0).getString("scope"))
        val current = resolver.relevant("新建表格并保存", "cn.wps.moffice_eng", "工作簿").getJSONArray("items").getJSONObject(0)
        assertEquals("current_app", current.getString("scope"))
        assertEquals("foreground_package", current.getJSONObject("match").getString("source"))
        assertFalse(resolver.relevant("检查 swpshelper", "com.android.launcher3", "").getBoolean("found"))
    }
    @Test fun twoExplicitTaskAppsUseMentionOrderAndShareTheTotalBudget() {
        val longBody = "保存表格。".repeat(2500)
        val store = store(bundle("arknights", "packages: [com.hypergryph.arknights]\napp_aliases: [明日方舟]", longBody),
            bundle("wps", "packages: [cn.wps.moffice_eng]\napp_aliases: [WPS]", longBody), bundle("mobile-ui", body = longBody))
        val items = resolver(store).relevant("先打开 WPS 保存表格，再打开明日方舟", "com.android.launcher3", "桌面").getJSONArray("items")
        assertEquals(2, items.length()); assertEquals("wps", items.getJSONObject(0).getString("name")); assertEquals("arknights", items.getJSONObject(1).getString("name"))
        assertTrue((0 until items.length()).all { items.getJSONObject(it).getString("scope") == "task_app" })
        assertTrue((0 until items.length()).sumOf { items.getJSONObject(it).getString("instructions").length } <= 6500)
    }
    @Test fun appAliasesCannotLiftLearnedKnowledgeAcrossPackagesOrAvailability() {
        val store = store(bundle("learned-example", "packages: [cn.wps.moffice_eng]\napp_aliases: [WPS]"))
        fun catalogue(availability: String) = store.list().apply { getJSONArray("items").getJSONObject(0)
            .put("source", "learned").put("availability", availability).put("app", JSONObject().put("package_name", "cn.wps.moffice_eng")) }
        assertFalse(resolver(store) { catalogue("available") }.relevant("打开 WPS 保存表格", "com.android.launcher3", "").getBoolean("found"))
        assertFalse(resolver(store) { catalogue("version_changed") }.relevant("打开 WPS 保存表格", "cn.wps.moffice_eng", "").getBoolean("found"))
    }
    @Test fun overlappingAliasDoesNotTurnOneMentionIntoTwoApps() {
        val store = store(bundle("arknights", "packages: [com.hypergryph.arknights]\napp_aliases: [明日方舟]"),
            bundle("other-ark", "packages: [com.example.other]\napp_aliases: [方舟]"))
        val items = resolver(store).relevant("打开明日方舟", "com.android.launcher3", "").getJSONArray("items")
        assertEquals(1, items.length()); assertEquals("arknights", items.getJSONObject(0).getString("name"))
    }
}
