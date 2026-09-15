package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelUsageLedgerTest {
    @Test fun importsOnlyKnownHistoryOnceThenTracksEveryResponseIndependentlyOfTaskRetention() {
        val root = Files.createTempDirectory("model-usage-test").toFile()
        try {
            val legacy = File(root, "direct-runs-v1.json")
            legacy.writeText(JSONArray().put(JSONObject().put("created_at", 1000L).put("prompt_tokens", 120).put("completion_tokens", 30).put("calls", 2)).toString())
            var now = 1_700_000_000_000L
            val ledger = ModelUsageLedger(root) { now }
            ledger.initialize()
            ledger.record(JSONObject().put("prompt_tokens", 80).put("completion_tokens", 20))
            assertTrue(legacy.delete())
            ledger.record(JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5))
            ledger.record(null)
            val restored = ModelUsageLedger(root) { now }.snapshot()
            assertEquals(210L, restored.getLong("lifetime_input_tokens"))
            assertEquals(55L, restored.getLong("lifetime_output_tokens"))
            assertEquals(5L, restored.getLong("lifetime_requests"))
            assertEquals(1L, restored.getLong("unknown_usage_requests"))
            assertEquals(120L, restored.getLong("historical_seed_input_tokens"))
            repeat(35) { now += 86_400_000L; ledger.record(JSONObject().put("prompt_tokens", 1).put("completion_tokens", 2)) }
            val afterRollover = ledger.snapshot()
            assertEquals(31, afterRollover.getJSONArray("daily").length())
            assertEquals(245L, afterRollover.getLong("lifetime_input_tokens"))
            assertEquals(125L, afterRollover.getLong("lifetime_output_tokens"))
            assertEquals(afterRollover.toString(), ModelUsageLedger(root) { now }.snapshot().toString())
            assertTrue(root.listFiles()!!.none { it.extension == "part" })
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptLedgerDoesNotSilentlyReimportOldHistoryAndIndependentInstancesSerializeWrites() {
        val root = Files.createTempDirectory("model-usage-concurrent").toFile()
        try {
            val threads = List(4) { Thread { repeat(12) { ModelUsageLedger(root).record(JSONObject().put("prompt_tokens", 2).put("completion_tokens", 1)) } } }
            threads.forEach(Thread::start); threads.forEach(Thread::join)
            val state = ModelUsageLedger(root).snapshot()
            assertEquals(96L, state.getLong("lifetime_input_tokens")); assertEquals(48L, state.getLong("lifetime_requests"))
            File(root, "model-usage-v1.json").writeText("broken")
            assertThrows(Exception::class.java) { ModelUsageLedger(root).snapshot() }
            assertEquals("broken", File(root, "model-usage-v1.json").readText())
        } finally { root.deleteRecursively() }
    }
}
