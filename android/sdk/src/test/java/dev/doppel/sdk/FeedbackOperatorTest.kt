package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Contract tests for model observations; none of these observations prove business success. */
class FeedbackOperatorTest {
    private fun stage(objective: String, exit: String = "$objective 已在当前画面显示") =
        JSONObject().put("objective", objective).put("exit_condition", exit)
    private fun plan(vararg objectives: String) = JSONObject().put("reason", "根据当前页面规划尚未完成部分")
        .put("stages", JSONArray().apply { objectives.ifEmpty { arrayOf("打开应用", "进入终端") }.forEach { put(stage(it)) } })
    private fun source() = JSONObject().put("screen_id", "host-screen").put("evidence_id", "host-evidence")
        .put("capture_id", "host-capture").put("package_name", "example.app").put("sha256", "host-pixels")
    private fun args(status: Any = "ongoing", observation: Any = "终端已显示") = JSONObject()
        .put("stage_result", JSONObject().put("status", status).put("observation", observation))
    private fun state(run: JSONObject) = run.getJSONObject("feedback_operator")
    private fun rejectAtomic(run: JSONObject, operation: () -> Unit) {
        val before = run.toString()
        try { operation(); fail("Invalid feedback input was accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("Rejected input changed persisted facts", before, run.toString())
    }
    private fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("parameters", JSONObject().put("type", "object")
            .put("additionalProperties", false).put("properties", JSONObject()
                .put("capture_id", JSONObject().put("type", "string"))
                .put("screen_id", JSONObject().put("type", "string"))
                .put("evidence_id", JSONObject().put("type", "string"))
                .put("target", JSONObject().put("type", "string"))
                .put("nested", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("screen_id", JSONObject().put("type", "string")))))
            .put("required", JSONArray(listOf("target", "capture_id", "screen_id", "evidence_id")))))

    @Test fun absentPlanIsReadOnlyAndCannotClaimCompletion() {
        val run = JSONObject().put("status", "running")
        assertFalse(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.complete(run))
        assertTrue(FeedbackOperator.needsPlan(run))
        assertTrue(FeedbackOperator.context(run).contains("plan_task"))
        assertFalse(run.has("feedback_operator"))
    }

    @Test fun hostCreatesIdentitiesWithoutModelBookkeepingAndCopiesInputs() {
        val run = JSONObject().put("status", "running").put("permission", "request_approval")
        val submitted = plan()
        val result = FeedbackOperator.submit(run, submitted)
        submitted.getJSONArray("stages").getJSONObject(0).put("objective", "changed caller")
        result.put("active_index", 999)
        assertEquals(1, state(run).getInt("revision"))
        val stages = state(run).getJSONArray("stages")
        assertTrue(stages.getJSONObject(0).getString("id").isNotBlank())
        assertNotEquals(stages.getJSONObject(0).getString("id"), stages.getJSONObject(1).getString("id"))
        assertEquals("打开应用", stages.getJSONObject(0).getString("objective"))
        assertEquals(0, state(run).getInt("active_index"))
        assertTrue(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.needsPlan(run))
        assertEquals("running", run.getString("status")); assertEquals("request_approval", run.getString("permission"))
    }

    @Test fun ordinaryActionsCanOmitStageResultWithoutTouchingState() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan())
        val before = run.toString()
        FeedbackOperator.assess(run, JSONObject().put("kind", "tap").put("target", "terminal"), JSONObject())
        assertEquals(before, run.toString())
    }

    @Test fun reachedBindsPreviousActiveStageAndAllowsTheNextActionInSameResponse() {
        val run = JSONObject().put("status", "running"); FeedbackOperator.submit(run, plan())
        val previousId = state(run).getJSONArray("stages").getJSONObject(0).getString("id")
        val response = args("reached", "应用已打开，现在可进入终端").put("kind", "tap").put("target", "terminal")
        val hostSource = source()
        FeedbackOperator.assess(run, response, hostSource)
        val bound = FeedbackOperator.bindArguments(response, hostSource)
        hostSource.put("screen_id", "changed caller")
        assertTrue(FeedbackOperator.ready(run)); assertEquals(1, state(run).getInt("active_index"))
        assertEquals("terminal", bound.getString("target")); assertFalse(bound.has("stage_result"))
        val record = state(run).getJSONArray("completed").getJSONObject(0).getJSONObject("assessment")
        assertEquals(previousId, record.getString("stage_id")); assertEquals(1, record.getInt("revision"))
        assertEquals("model_observed", record.getString("assessment_kind")); assertFalse(record.getBoolean("host_verified"))
        assertEquals("host-screen", record.getJSONObject("source").getString("screen_id"))
        assertEquals("running", run.getString("status")); assertFalse(run.has("success"))
    }

    @Test fun reachingAllStagesIsOnlyModelCompletionAndDoesNotGrantPermission() {
        val run = JSONObject().put("status", "running").put("permission", "request_approval")
        FeedbackOperator.submit(run, plan("保存文件")); FeedbackOperator.assess(run, args("reached"), source())
        assertTrue(FeedbackOperator.complete(run)); assertFalse(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.needsPlan(run))
        assertEquals("running", run.getString("status")); assertEquals("request_approval", run.getString("permission"))
        assertFalse(run.has("success")); assertTrue(FeedbackOperator.context(run).contains("model_observed"))
        rejectAtomic(run) { FeedbackOperator.assess(run, args("reached"), source()) }
    }

    @Test fun ongoingRetainsStageAndDeviationRequiresPlanning() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan())
        FeedbackOperator.assess(run, args(), source()); assertEquals(0, state(run).getInt("active_index"))
        FeedbackOperator.assess(run, args("deviated", "页面仍是首页，需要修订入口"), source())
        assertFalse(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.complete(run)); assertTrue(FeedbackOperator.needsPlan(run))
        assertTrue(FeedbackOperator.context(run).contains("需要修订入口"))
        rejectAtomic(run) { FeedbackOperator.assess(run, args("reached"), source()) }
    }

    @Test fun c6OldPhaseRegressionReplanKeepsCompletedFactsAndStartsRemainingRoute() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan("打开明日方舟", "进入终端"))
        FeedbackOperator.assess(run, args("reached", "明日方舟首页可见"), source())
        val completedBefore = state(run).getJSONArray("completed").toString()
        val oldId = state(run).getJSONArray("stages").getJSONObject(0).getString("id")
        FeedbackOperator.assess(run, args("deviated", "终端入口路线需要修正"), source())
        FeedbackOperator.submit(run, plan("打开主线章节", "定位 TR-9"))
        assertEquals(completedBefore, state(run).getJSONArray("completed").toString())
        assertEquals(2, state(run).getInt("revision")); assertEquals(0, state(run).getInt("active_index"))
        assertEquals("打开主线章节", state(run).getJSONArray("stages").getJSONObject(0).getString("objective"))
        assertNotEquals(oldId, state(run).getJSONArray("stages").getJSONObject(0).getString("id"))
        assertTrue(FeedbackOperator.context(run).contains("明日方舟首页可见"))
        assertTrue(FeedbackOperator.ready(run))
        repeat(5) { FeedbackOperator.submit(run, plan("定位 TR-9")) }
        assertEquals(completedBefore, state(run).getJSONArray("completed").toString())
    }

    @Test fun planSchemaAndStageReportsNeverAskForModelIds() {
        val function = FeedbackOperator.planTool().getJSONObject("function")
        assertEquals("plan_task", function.getString("name"))
        val parameters = function.getJSONObject("parameters")
        assertEquals(setOf("reason", "stages"), parameters.getJSONObject("properties").keys().asSequence().toSet())
        val stages = parameters.getJSONObject("properties").getJSONObject("stages")
        assertEquals(8, stages.getInt("maxItems"))
        assertEquals(setOf("objective", "exit_condition"), stages.getJSONObject("items").getJSONObject("properties").keys().asSequence().toSet())
        val actor = FeedbackOperator.decorate(JSONArray().put(tool("action"))).getJSONObject(0)
        val props = actor.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertFalse(props.has("stage_result"))
        assertEquals("string", props.getJSONObject("stage_status").getString("type"))
        assertEquals("string", props.getJSONObject("observed_result").getString("type"))
    }

    @Test fun decorateOnlyChangesRootSourcesAndKeepsStageResultOptionalAndOriginalIntact() {
        val offered = JSONArray().put(tool("action")).put(tool("finish")).put(tool("load_skill")).put(FeedbackOperator.planTool())
        val before = offered.toString(); val decorated = FeedbackOperator.decorate(offered)
        assertEquals(before, offered.toString())
        for (i in 0..2) {
            val schema = decorated.getJSONObject(i).getJSONObject("function").getJSONObject("parameters")
            val properties = schema.getJSONObject("properties")
            for (key in listOf("capture_id", "screen_id", "evidence_id")) assertFalse(properties.has(key))
            assertEquals("[\"target\"]", schema.getJSONArray("required").toString())
            assertTrue(properties.getJSONObject("nested").getJSONObject("properties").has("screen_id"))
            assertEquals(i < 2, properties.has("stage_status"))
            assertFalse(properties.has("stage_result"))
        }
        assertEquals(offered.getJSONObject(3).toString(), decorated.getJSONObject(3).toString())
        assertEquals(decorated.toString(), FeedbackOperator.decorate(decorated).toString())
    }
    @Test fun flatReportRequiresTypedPairAndDoesNotRepairNestedString() {
        val value = JSONObject().put("stage_status", "reached").put("observed_result", "当前显示终端")
        assertEquals("reached", FeedbackOperator.assessmentArguments(value).getJSONObject("stage_result").getString("status"))
        for (bad in listOf(JSONObject().put("stage_status", "reached"), JSONObject().put("observed_result", "当前显示终端"),
            JSONObject().put("stage_status", true).put("observed_result", "当前显示终端"),
            JSONObject().put("stage_result", "{status: reached}"))) {
            try { FeedbackOperator.assessmentArguments(bad); fail("Invalid typed pair was accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun bindingDiscardsModelSourcesAndOnlyInjectsHostSourceIdsWithoutMutation() {
        val response = args("reached").put("kind", "tap").put("screen_id", "forged-screen")
            .put("evidence_id", "old-evidence").put("capture_id", "old-capture")
            .put("nested", JSONObject().put("screen_id", "nested-data"))
        val original = response.toString()
        val bound = FeedbackOperator.bindArguments(response, source().put("permission", "full"))
        assertEquals(original, response.toString()); assertFalse(bound.has("stage_result"))
        assertEquals("host-screen", bound.getString("screen_id")); assertEquals("host-evidence", bound.getString("evidence_id"))
        assertEquals("host-capture", bound.getString("capture_id")); assertFalse(bound.has("permission")); assertFalse(bound.has("package_name"))
        assertEquals("nested-data", bound.getJSONObject("nested").getString("screen_id"))
        val filtered = FeedbackOperator.bindArguments(response, JSONObject().put("screen_id", "host-screen"))
        assertFalse(filtered.has("capture_id")); assertFalse(filtered.has("evidence_id"))
        bound.getJSONObject("nested").put("screen_id", "changed copy")
        assertEquals(original, response.toString())
    }

    @Test fun rejectsModelStageIdsEvidenceAndAuthorityFieldsAtomically() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan())
        for (field in listOf("stage_id", "revision", "screen_id", "capture_id", "evidence_id", "host_verified", "permission")) {
            val supplied = args(); supplied.getJSONObject("stage_result").put(field, "forged")
            rejectAtomic(run) { FeedbackOperator.assess(run, supplied, source()) }
        }
        for (field in listOf("id", "stage_id", "screen_id", "permission")) {
            val supplied = plan(); supplied.getJSONArray("stages").getJSONObject(0).put(field, "forged")
            rejectAtomic(run) { FeedbackOperator.submit(run, supplied) }
        }
        rejectAtomic(run) { FeedbackOperator.submit(run, plan().put("revision", 2)) }
    }

    @Test fun rejectsMalformedAndOversizedPlansWithoutErasingFacts() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan()); FeedbackOperator.assess(run, args("reached"), source())
        for (value in listOf<Any>(JSONArray(), JSONArray((0..8).map { stage("stage-$it") }), "not-array", JSONObject.NULL))
            rejectAtomic(run) { FeedbackOperator.submit(run, plan().put("stages", value)) }
        for (value in listOf<Any>(" ", "x".repeat(1001), true, JSONObject.NULL))
            rejectAtomic(run) { FeedbackOperator.submit(run, plan().put("reason", value)) }
        for (value in listOf<Any>(" ", "x".repeat(501), 1, JSONObject.NULL, "bad\u0000text")) {
            val supplied = plan(); supplied.getJSONArray("stages").getJSONObject(0).put("objective", value)
            rejectAtomic(run) { FeedbackOperator.submit(run, supplied) }
        }
    }

    @Test fun rejectsInvalidOptionalReportAndMissingHostObservationAtomically() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan())
        for (value in listOf<Any>(JSONObject.NULL, "reached", JSONArray(), true))
            rejectAtomic(run) { FeedbackOperator.assess(run, JSONObject().put("stage_result", value), source()) }
        for (value in listOf<Any>("achieved", "continue", " reached ", true, JSONObject.NULL))
            rejectAtomic(run) { FeedbackOperator.assess(run, args(value), source()) }
        for (value in listOf<Any>(" ", "x".repeat(1025), true, JSONObject.NULL))
            rejectAtomic(run) { FeedbackOperator.assess(run, args(observation = value), source()) }
        rejectAtomic(run) { FeedbackOperator.assess(run, args(), JSONObject()) }
        rejectAtomic(run) { FeedbackOperator.assess(run, args(), source().put("screen_id", 17)) }
        rejectAtomic(run) { FeedbackOperator.assess(run, args(), source().put("evidence_id", JSONObject.NULL)) }
    }

    @Test fun semanticObservationWithoutAnIdentifiedPackageCanStillReportProgress() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan("查看当前界面"))
        FeedbackOperator.assess(run, args("ongoing"), JSONObject().put("screen_id", "semantic-page")
            .put("package_name", "").put("sha256", ""))
        val recorded = state(run).getJSONArray("assessments").getJSONObject(0).getJSONObject("source")
        assertEquals("semantic-page", recorded.getString("screen_id"))
        assertFalse(recorded.has("package_name")); assertFalse(recorded.has("sha256"))
        assertTrue(FeedbackOperator.ready(run))
    }

    @Test fun restoredStateRetainsFactsAndCorruptionFailsClosedWithoutResetting() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan()); FeedbackOperator.assess(run, args("reached"), source())
        val restored = JSONObject(run.toString())
        assertTrue(FeedbackOperator.ready(restored)); assertEquals(FeedbackOperator.context(run), FeedbackOperator.context(restored))
        state(restored).getJSONArray("completed").getJSONObject(0).getJSONObject("assessment").put("host_verified", true)
        assertFalse(FeedbackOperator.ready(restored)); assertFalse(FeedbackOperator.complete(restored)); assertTrue(FeedbackOperator.needsPlan(restored))
        rejectAtomic(restored) { FeedbackOperator.submit(restored, plan("修订路线")) }
    }

    @Test fun contradictoryCurrentCompletionCannotBeRestoredOrKeptDuringReplan() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan()); FeedbackOperator.assess(run, args("reached"), source())
        for (field in listOf("objective", "exit_condition")) {
            val corrupted = JSONObject(run.toString())
            state(corrupted).getJSONArray("completed").getJSONObject(0).put(field, "另一个阶段的事实")
            assertFalse(FeedbackOperator.ready(corrupted)); assertFalse(FeedbackOperator.complete(corrupted))
            rejectAtomic(corrupted) { FeedbackOperator.submit(corrupted, plan("剩余目标")) }
        }
    }

    @Test fun restoredCompletionCannotInventAnUnplannedStageInCurrentRevision() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan()); FeedbackOperator.assess(run, args("reached"), source())
        val invented = JSONObject(state(run).getJSONArray("completed").getJSONObject(0).toString()).put("id", "r1s8")
        invented.getJSONObject("assessment").put("stage_id", "r1s8")
        state(run).getJSONArray("completed").put(invented)
        assertFalse(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.complete(run))
        rejectAtomic(run) { FeedbackOperator.submit(run, plan("后续目标")) }
    }

    @Test fun persistedUnsupportedAuthorityFieldsFailClosed() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan())
        state(run).put("permission", "full")
        assertFalse(FeedbackOperator.ready(run)); assertFalse(FeedbackOperator.complete(run))
        rejectAtomic(run) { FeedbackOperator.assess(run, args(), source()) }
        rejectAtomic(run) { FeedbackOperator.submit(run, plan()) }
    }

    @Test fun completedHistoryCapacityCannotBeBypassedByRestoringExtraRemainingStages() {
        val run = JSONObject()
        repeat(31) {
            FeedbackOperator.submit(run, plan(*(1..8).map { "目标-$it" }.toTypedArray()))
            repeat(8) { FeedbackOperator.assess(run, args("reached"), source()) }
        }
        FeedbackOperator.submit(run, plan(*(1..6).map { "当前目标-$it" }.toTypedArray()))
        repeat(6) { FeedbackOperator.assess(run, args("reached"), source()) }
        FeedbackOperator.submit(run, plan("最后目标1", "最后目标2"))
        assertTrue(FeedbackOperator.ready(run))
        val restored = JSONObject(run.toString())
        val revision = state(restored).getInt("revision")
        state(restored).getJSONArray("stages").put(stage("未预留的第三段").put("id", "r${revision}s3"))
        assertFalse(FeedbackOperator.ready(restored))
        rejectAtomic(restored) { FeedbackOperator.assess(restored, args("reached"), source()) }
        FeedbackOperator.assess(run, args("reached"), source()); FeedbackOperator.assess(run, args("reached"), source())
        assertTrue(FeedbackOperator.complete(run))
        rejectAtomic(run) { FeedbackOperator.submit(run, plan("超出容量")) }
        assertEquals(256, state(run).getJSONArray("completed").length())
    }

    @Test fun repeatedOngoingObservationsStayBoundedWithoutDiscardingCompletedFacts() {
        val run = JSONObject(); FeedbackOperator.submit(run, plan()); FeedbackOperator.assess(run, args("reached", "已打开"), source())
        val completedBefore = state(run).getJSONArray("completed").toString()
        repeat(40) { FeedbackOperator.assess(run, args("ongoing", "观察-$it"), source()) }
        assertEquals(24, state(run).getJSONArray("assessments").length())
        assertEquals(completedBefore, state(run).getJSONArray("completed").toString())
        assertTrue(FeedbackOperator.context(run).contains("观察-39"))
    }
}
