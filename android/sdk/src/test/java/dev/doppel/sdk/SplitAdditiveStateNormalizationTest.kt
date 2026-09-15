package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitAdditiveStateNormalizationTest {
    private val additive = listOf("facts", "completed_steps", "failed_routes")
    private fun tap() = JSONObject().put("kind", "tap").put("target", "保存按钮")
        .put("expected", "出现保存成功提示").put("screen_context", "编辑页面")
    private fun state() = JSONObject().put("phase", "编辑").put("facts", JSONArray())
        .put("completed_steps", JSONArray()).put("failed_routes", JSONArray()).put("remaining_steps", JSONArray().put("保存"))
    private fun wrapped(memory: Any, decision: JSONObject = tap()) = JSONObject().put("decision", decision).put("state", memory)
    private fun response(raw: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", raw.toString()))))
    private fun parse(raw: JSONObject, previous: JSONObject? = null, direct: Boolean = false,
                      onParsed: (JSONObject) -> Unit = {}) = SplitAgentProtocol.content(response(raw),
        SplitOutputSchema.format("primary", direct), "primary", previous, onParsed)

    @Test fun repeatedRetainedMemoryOverWireLimitBecomesOnlyNewAdditionsWithoutMutation() {
        val previous = state()
        val update = state()
        for (key in additive) {
            previous.put(key, JSONArray((1..40).map { "$key retained $it" }))
            update.put(key, JSONArray((1..40).map { "  $key retained $it  " })
                .put("$key new one").put(" $key new one ").put("$key new two"))
        }
        val raw = wrapped(update)
        val rawBefore = raw.toString()
        val previousBefore = previous.toString()
        var diagnostic: JSONObject? = null
        val parsed = parse(raw, previous) { diagnostic = it }
        for (key in additive) {
            val additions = parsed.getJSONObject("state").getJSONArray(key)
            assertEquals(JSONArray().put("$key new one").put("$key new two").toString(), additions.toString())
            assertEquals(43, diagnostic!!.getJSONObject("state").getJSONArray(key).length())
        }
        assertEquals("tap", parsed.getString("action"))
        assertEquals(rawBefore, raw.toString())
        assertEquals(rawBefore, diagnostic!!.toString())
        assertEquals(previousBefore, previous.toString())
        assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(raw, SplitOutputSchema.format("primary")) }
    }

    @Test fun duplicateNewStringsAreDeduplicatedWithoutPreviousStateAndOriginalTextIsKept() {
        val raw = wrapped(state().put("facts", JSONArray().put(" 新观察 ").put("新观察").put("另一观察")))
        val parsed = parse(raw).getJSONObject("state")
        assertEquals(JSONArray().put(" 新观察 ").put("另一观察").toString(), parsed.getJSONArray("facts").toString())
        assertEquals(3, raw.getJSONObject("state").getJSONArray("facts").length())
    }

    @Test fun thirtyOneActuallyNewItemsRemainOverLimitAndAreNeverTruncated() {
        for (key in additive) {
            val old = state().put(key, JSONArray().put("retained"))
            val raw = wrapped(state().put(key, JSONArray().put("retained").apply { repeat(31) { put("new $it") } }))
            val normalized = SplitOutputSchema.normalizeAdditiveState(raw, old)
            assertEquals(31, normalized.getJSONObject("state").getJSONArray(key).length())
            val error = assertThrows(SplitSchemaViolation::class.java) { parse(raw, old) }
            assertEquals("$.state.$key", error.path)
            assertEquals("array_length", error.code)
        }
    }

    @Test fun invalidAdditiveValuesAndMembersRemainInvalidDespiteDuplicates() {
        for (key in additive) {
            val old = state().put(key, JSONArray().put("retained").put(7))
            for (invalid in listOf(JSONObject.NULL, "not-an-array", 7, JSONArray().put("retained").put(7).put(7),
                JSONArray().put("retained").put(JSONObject.NULL), JSONArray().put("retained").put(""),
                JSONArray().put("retained").put("x".repeat(701)))) {
                val raw = wrapped(state().put(key, invalid))
                assertThrows(SplitSchemaViolation::class.java) { parse(raw, old) }
            }
        }
        for (invalid in listOf("wrong-state-type", JSONArray()))
            assertThrows(SplitSchemaViolation::class.java) { parse(wrapped(invalid), state()) }
    }

    @Test fun deduplicationDoesNotRepairActionCoordinatesOrCompletionStatus() {
        val old = state().put("facts", JSONArray().put("retained"))
        val memory = state().put("facts", JSONArray().apply { repeat(40) { put("retained") } })
        for ((decision, direct) in listOf(
            tap().apply { remove("expected") } to false,
            tap().put("points", JSONArray("[[1001,100]]")).put("duration_ms", 60) to true,
            JSONObject().put("kind", "finish").put("status", "invented").put("message", "done") to false
        )) assertThrows(SplitSchemaViolation::class.java) { parse(wrapped(memory, decision), old, direct) }
        val truncated = response(wrapped(memory)).apply { getJSONArray("choices").getJSONObject(0).put("finish_reason", "length") }
        assertThrows(IllegalArgumentException::class.java) {
            SplitAgentProtocol.content(truncated, SplitOutputSchema.format("primary"), "primary", old)
        }
    }

    @Test fun replacementPlansAndNullStateRetainTheirExactMeaning() {
        val old = state().put("facts", JSONArray().put("retained"))
        val plan = JSONArray().put("保存").put("保存").put("验证结果")
        val raw = wrapped(state().put("facts", JSONArray().put("retained")).put("remaining_steps", plan))
        val normalized = parse(raw, old).getJSONObject("state")
        assertEquals(plan.toString(), normalized.getJSONArray("remaining_steps").toString())
        assertEquals("编辑", normalized.getString("phase"))
        val tooMany = wrapped(state().put("remaining_steps", JSONArray().apply { repeat(31) { put("保存") } }))
        val error = assertThrows(SplitSchemaViolation::class.java) { parse(tooMany, old) }
        assertEquals("$.state.remaining_steps", error.path)
        assertFalse(parse(wrapped(JSONObject.NULL), old).has("state"))
        for (key in listOf("phase", "remaining_steps")) {
            val missing = wrapped(state().apply { remove(key) })
            assertThrows(SplitSchemaViolation::class.java) { parse(missing, old) }
        }
    }

    @Test fun missingAdditiveFieldsStillDefaultEmptyAndGrounderIsNotNormalized() {
        val partial = state().apply { additive.forEach { remove(it) } }
        val parsed = parse(wrapped(partial), state()).getJSONObject("state")
        additive.forEach { assertEquals(0, parsed.getJSONArray(it).length()) }
        val grounder = JSONObject().put("result", JSONObject().put("status", "not_found").put("reason", "没有目标"))
            .put("state", partial)
        assertThrows(SplitSchemaViolation::class.java) {
            SplitAgentProtocol.content(response(grounder), SplitOutputSchema.format("grounding", expectedAction = "tap"), "grounding", state())
        }
    }
}
