package dev.doppel.developer

import android.content.Context
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.AndroidWebResearch
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Explicit public-network QA. No model, credentials, task submission or screen actions. */
class KnowledgeLiveTest {
    @Test fun publicWebAndBundledSkillHaveRealReadableProvenance() {
        assumeTrue("Opt in with -e knowledge_live true", InstrumentationRegistry.getArguments().getString("knowledge_live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = Gateway(context)
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val settingsBefore = gateway.prefs.all.toMap()
        val consentBefore = consent.all.toMap()
        val runsFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val runsBefore = readRuns(runsFile)
        val evidence = JSONObject().put("schema", 1).put("started_at_ms", System.currentTimeMillis())
            .put("ok", false).put("model_calls_requested", 0).put("screen_actions_requested", 0)
        val web = AndroidWebResearch()
        var stage = "preconditions"
        try {
            assertTrue("Existing direct mode is required; this test does not change settings", gateway.isDirectMode())
            assertTrue("Existing accepted notices are required", FirstUseConsent.isAccepted(context))
            val persistedRuns = JSONArray(runsBefore?.toString(Charsets.UTF_8) ?: "[]")
            for (index in 0 until persistedRuns.length()) {
                assertTrue("Pause existing tasks before isolated knowledge QA",
                    persistedRuns.getJSONObject(index).optString("status") in setOf("paused", "completed", "failed", "cancelled"))
            }
            evidence.put("tracked_runs", persistedRuns.length())

            stage = "skill_catalogue"
            val catalogue = gateway.request("GET", "/skills").getJSONArray("items")
            val skill = (0 until catalogue.length()).map { catalogue.getJSONObject(it) }
                .single { it.optString("name") == "arknights" && it.optString("source") == "bundled" }
            assertFalse(skill.getBoolean("trusted"))
            assertFalse("The catalogue must retain progressive disclosure", skill.has("instructions"))
            val revision = skill.getString("revision")
            assertTrue(revision.matches(Regex("[0-9a-f]{64}")))
            assertTrue(skill.getString("included_source_version").isNotBlank())

            stage = "skill_body"
            val loaded = gateway.request("GET", "/skills/arknights")
            assertTrue(loaded.getBoolean("found"))
            assertFalse(loaded.getBoolean("trusted"))
            assertEquals(revision, loaded.getString("revision"))
            assertTrue(loaded.getString("instructions").isNotBlank())
            val resources = loaded.getJSONArray("resources")
            assertTrue((0 until resources.length()).any { resources.getString(it) == "references/sources.md" })

            stage = "skill_resource"
            val source = gateway.request("GET", "/skills/arknights/resources?path=references%2Fsources.md")
            assertTrue(source.getBoolean("found"))
            assertFalse(source.getBoolean("trusted"))
            assertEquals(revision, source.getString("revision"))
            val sourceText = source.getString("content")
            assertTrue("Bundled source must contain public provenance", sourceText.contains("https://"))
            val bundledSource = context.assets.open("skills/arknights/references/sources.md").use { it.readBytes() }
            assertEquals("The resource must retain the bundled source's exact content", sha256(bundledSource), sha256(sourceText.toByteArray()))
            evidence.put("skill", JSONObject().put("name", "arknights").put("revision", revision)
                .put("included_source_version", skill.getString("included_source_version"))
                .put("resource", "references/sources.md").put("resource_sha256", sha256(sourceText.toByteArray()))
                .put("source_excerpt", sourceText.take(1800)).put("trusted", false))

            stage = "public_search"
            FirstUseConsent.requireAccepted(context)
            val search = web.search("明日方舟 1-7 代理 理智")
            evidence.put("search", search)

            stage = "public_read"
            FirstUseConsent.requireAccepted(context)
            val read = web.read("https://www.gamersky.com/handbooksy/201904/1178657.shtml")
            val article = read.optString("text")
            val recordedRead = JSONObject(read.toString()).apply {
                remove("text"); put("text_chars", article.length); put("excerpt", article.take(1600))
                if (article.isNotBlank()) put("text_sha256", sha256(article.toByteArray()))
            }
            evidence.put("read", recordedRead)

            // Collect both independent network outcomes before asserting, so a search
            // provider outage does not hide whether ordinary public reading works.
            stage = "search_validation"
            assertWebReference(search)
            assertPublicUrl(search.getString("source_url"))
            assertTrue(search.getString("provider").isNotBlank())
            val results = search.getJSONArray("results")
            assertTrue("Real search must return at least one result without assuming ranking", results.length() in 1..6)
            for (index in 0 until results.length()) {
                val item = results.getJSONObject(index)
                assertPublicUrl(item.getString("url"))
                assertTrue(item.getString("title").isNotBlank())
                assertTrue(item.getString("source").isNotBlank())
            }
            stage = "read_validation"
            assertWebReference(read)
            assertPublicUrl(read.getString("url"))
            assertTrue(read.getString("source").isNotBlank())
            assertTrue(read.getString("title").isNotBlank())
            assertTrue("Known public article should contain readable Arknights content", article.length >= 100 && article.contains("明日方舟"))
            evidence.put("ok", true)
        } catch (failure: Throwable) {
            // Never copy exception messages, settings, run contents or credentials to evidence.
            evidence.put("failure_stage", stage).put("failure_type", failure.javaClass.simpleName)
            throw failure
        } finally {
            web.cancel()
            val settingsUnchanged = settingsBefore == gateway.prefs.all
            val consentUnchanged = consentBefore == consent.all
            val runsAfter = readRuns(runsFile)
            val runsUnchanged = if (runsBefore == null) runsAfter == null else runsAfter != null && runsBefore.contentEquals(runsAfter)
            evidence.put("finished_at_ms", System.currentTimeMillis()).put("settings_unchanged", settingsUnchanged)
                .put("consent_unchanged", consentUnchanged).put("run_state_unchanged", runsUnchanged)
            if (!settingsUnchanged || !consentUnchanged || !runsUnchanged) evidence.put("ok", false).put("failure_stage", "read_only_verification")
            writeEvidence(File(context.filesDir, "knowledge-live.json"), evidence)
            assertTrue("Knowledge QA must not change settings or consent", settingsUnchanged && consentUnchanged)
            assertTrue("Knowledge reads must preserve task state and provider call counters", runsUnchanged)
        }
    }

    private fun assertWebReference(value: JSONObject) {
        assertTrue("Public web request failed: " + value.optJSONObject("error")?.optString("code", "unknown"), value.optBoolean("ok"))
        assertTrue(value.getBoolean("untrusted"))
        assertEquals("reference_only", value.getString("content_role"))
    }

    private fun assertPublicUrl(value: String) {
        val uri = URI(value)
        assertEquals("https", uri.scheme)
        assertTrue(uri.host?.isNotBlank() == true)
        assertNull(uri.userInfo)
    }

    private fun readRuns(file: File): ByteArray? {
        if (!file.exists()) return null
        check(file.length() <= 2 * 1024 * 1024) { "Task store exceeds the documented bound" }
        return file.readBytes()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun writeEvidence(file: File, result: JSONObject) {
        val bytes = result.toString(2).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 32768) { "Public knowledge evidence exceeds limit" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
}
