package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Stage state is a model assessment; changing it must never manufacture host success. */
class SessionTaskPlanTest {
    private fun stage(id: String, objective: String = "打开终端", exit: String = "看到关卡列表") = JSONObject()
        .put("id", id).put("objective", objective).put("exit_condition", exit)
    private fun plan(revision: Any = 1, vararg ids: String) = JSONObject()
        .put("revision", revision).put("reason", "按页面阶段执行")
        .put("stages", JSONArray().apply { for (id in ids.ifEmpty { arrayOf("terminal", "prepare") }) put(stage(id)) })
    private fun progress(id: String = "terminal", status: Any = "continue", screen: Any = "screen-1", evidence: Any = "evidence-1") = JSONObject()
        .put("stage_id", id).put("status", status).put("observation", "当前仍在终端界面，需要进入第一章")
        .put("screen_id", screen).put("evidence_id", evidence)
    private fun assess(run: JSONObject, value: JSONObject) = SessionTaskPlan.assess(run, value, "screen-1", "evidence-1")
    private fun state(run: JSONObject) = run.getJSONObject("session_task_plan")
    private fun rejectAtomic(run: JSONObject, operation: () -> Unit) {
        val before = run.toString()
        try { operation(); fail("Invalid stage input was accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("Rejected input mutated persisted task state", before, run.toString())
    }

    @Test fun noPlanCannotAuthorizeAnActionOrClaimCompletion() {
        val run = JSONObject().put("status", "running")
        assertFalse(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        assertTrue(SessionTaskPlan.context(run).contains("set_task_plan"))
        assertFalse(run.has("session_task_plan"))
    }

    @Test fun initialPlanIsReadyAndDoesNotChangeHostTaskStatus() {
        val run = JSONObject().put("status", "running").put("permission", "request_approval")
        val args = plan()
        SessionTaskPlan.submit(run, args)
        args.getJSONArray("stages").getJSONObject(0).put("objective", "mutated caller")
        assertTrue(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        assertEquals("running", run.getString("status"))
        assertEquals("request_approval", run.getString("permission"))
        assertTrue(SessionTaskPlan.context(run).contains("terminal"))
        assertTrue(SessionTaskPlan.context(run).contains("看到关卡列表"))
        assertFalse(SessionTaskPlan.context(run).contains("mutated caller"))
    }

    @Test fun achievedAdvancesExactlyOneStageWithModelSourceEvidence() {
        val run = JSONObject().put("status", "running")
        SessionTaskPlan.submit(run, plan())
        assess(run, progress(status = "achieved"))
        assertTrue(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        val record = state(run).getJSONArray("assessments").getJSONObject(0)
        assertEquals("MODEL", record.getString("source"))
        assertFalse(record.getBoolean("host_verified"))
        assertEquals("screen-1", record.getString("screen_id"))
        assertEquals("evidence-1", record.getString("evidence_id"))
        rejectAtomic(run) { assess(run, progress(status = "achieved")) }
        assess(run, progress("prepare", "achieved"))
        assertTrue(SessionTaskPlan.complete(run))
        assertFalse(SessionTaskPlan.ready(run))
        assertEquals("running", run.getString("status"))
        assertFalse(run.has("success"))
        rejectAtomic(run) { assess(run, progress("prepare", "achieved")) }
    }

    @Test fun continueRetainsStageAndReviseDisablesActionsUntilNextRevision() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        assess(run, progress())
        assertTrue(SessionTaskPlan.ready(run))
        assess(run, progress(status = "revise").put("observation", "终端已经选中，重复点击不会进入关卡"))
        assertFalse(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        assertTrue(SessionTaskPlan.context(run).contains("终端已经选中"))
        rejectAtomic(run) { assess(run, progress(status = "achieved")) }
        SessionTaskPlan.submit(run, plan(2, "chapter", "prepare"))
        assertTrue(SessionTaskPlan.ready(run))
        assertEquals(2, state(run).getInt("revision"))
        assertEquals(1, state(run).getJSONArray("archive").length())
        assertTrue(state(run).getJSONArray("archive").toString().contains("终端已经选中"))
        rejectAtomic(run) { assess(run, progress(status = "achieved")) }
    }

    @Test fun revisionDoesNotCarryOldCompletionIntoReplacementStages() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        assess(run, progress(status = "achieved"))
        SessionTaskPlan.submit(run, plan(2, "terminal"))
        assertTrue(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        assertTrue(state(run).getJSONArray("archive").toString().contains("terminal"))
        assess(run, progress(status = "achieved"))
        assertTrue(SessionTaskPlan.complete(run))
    }

    @Test fun rejectsSkippedRepeatedStringAndFractionalRevisionsAtomically() {
        val run = JSONObject()
        for (value in listOf<Any>(0, 2, "1", 1.0, true, JSONObject.NULL, Long.MAX_VALUE))
            rejectAtomic(run) { SessionTaskPlan.submit(run, plan(value)) }
        SessionTaskPlan.submit(run, plan())
        for (value in listOf<Any>(1, 3, "2", 2.5))
            rejectAtomic(run) { SessionTaskPlan.submit(run, plan(value)) }
    }

    @Test fun rejectsEmptyDuplicateMalformedOrTooManyStageIdsAtomically() {
        val run = JSONObject()
        for (id in listOf("", " ", "two words", "a".repeat(33), "1start", "任务", "ok\n"))
            rejectAtomic(run) { SessionTaskPlan.submit(run, plan(1, id)) }
        rejectAtomic(run) { SessionTaskPlan.submit(run, plan(1, "same", "same")) }
        rejectAtomic(run) { SessionTaskPlan.submit(run, plan().put("stages", JSONArray())) }
        rejectAtomic(run) { SessionTaskPlan.submit(run, plan(1, *(1..9).map { "stage$it" }.toTypedArray())) }
        SessionTaskPlan.submit(run, plan(1, "phase_1", "phase-2"))
        assertTrue(SessionTaskPlan.ready(run))
    }

    @Test fun rejectsInvalidStagePlanTypesTextAndUnexpectedPermissions() {
        val run = JSONObject()
        val bad = listOf(
            plan().put("reason", " "), plan().put("reason", 42), plan().put("reason", "a".repeat(1001)),
            plan().put("stages", "array"), plan().put("stages", JSONArray().put("stage")),
            plan().put("permission", "full_access"), plan().put("stages", JSONArray().put(stage("one").put("x", 123))),
            plan().put("stages", JSONArray().put(stage("one").put("objective", JSONObject.NULL))),
            plan().put("stages", JSONArray().put(stage("one").put("objective", "a".repeat(501)))),
            plan().put("stages", JSONArray().put(stage("one").put("exit_condition", " "))),
            plan().put("stages", JSONArray().put(stage("one").put("exit_condition", false)))
        )
        for (args in bad) rejectAtomic(run) { SessionTaskPlan.submit(run, args) }
    }

    @Test fun staleFabricatedOrMissingSourceCannotAdvanceStage() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        for (value in listOf(progress("prepare", "achieved"), progress(status = "achieved", screen = "screen-old"),
            progress(status = "achieved", evidence = "evidence-fake"), progress(screen = 123), progress(evidence = JSONObject.NULL)))
            rejectAtomic(run) { assess(run, value) }
        for (hostScreen in listOf<String?>(null, "", " "))
            rejectAtomic(run) { SessionTaskPlan.assess(run, progress(status = "achieved"), hostScreen, "evidence-1") }
        for (hostEvidence in listOf<String?>(null, "", " "))
            rejectAtomic(run) { SessionTaskPlan.assess(run, progress(status = "achieved"), "screen-1", hostEvidence) }
        assertTrue(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
    }

    @Test fun progressRejectsInvalidStatusObservationAndExtraFieldsBeforeAnyMutation() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        for (value in listOf(progress(status = "done"), progress(status = 1), progress(status = "achieved").put("observation", " "),
            progress(status = "revise").put("observation", false), progress().put("observation", "a".repeat(1025)),
            progress().put("evidence_id", "e".repeat(257)), progress().put("permission", "full_access")))
            rejectAtomic(run) { assess(run, value) }
    }

    @Test fun malformedLaterStageDoesNotPartiallyReplaceExistingPlan() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        assess(run, progress())
        val replacement = plan(2).put("stages", JSONArray().put(stage("valid")).put(stage("invalid").put("exit_condition", 10)))
        rejectAtomic(run) { SessionTaskPlan.submit(run, replacement) }
        assess(run, progress(status = "achieved"))
        assertTrue(SessionTaskPlan.ready(run))
    }

    @Test fun stateSurvivesJsonRoundTripAndRemainsBoundedAcrossRevisions() {
        var run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        assess(run, progress(status = "achieved"))
        run = JSONObject(run.toString())
        assertTrue(SessionTaskPlan.ready(run))
        assess(run, progress("prepare", "achieved"))
        assertTrue(SessionTaskPlan.complete(JSONObject(run.toString())))
        for (revision in 2..60) {
            SessionTaskPlan.submit(run, plan(revision, "terminal"))
            repeat(40) { assess(run, progress().put("observation", "a".repeat(1024))) }
        }
        assertTrue(state(run).getJSONArray("archive").length() <= 4)
        assertTrue(state(run).getJSONArray("assessments").length() <= 24)
        assertTrue(run.toString().toByteArray(Charsets.UTF_8).size < 256 * 1024)
        assertTrue(SessionTaskPlan.ready(JSONObject(run.toString())))
    }

    @Test fun corruptPersistedPhaseFailsClosedWithoutInventingSuccess() {
        val run = JSONObject()
        SessionTaskPlan.submit(run, plan())
        state(run).put("active_index", 8)
        assertFalse(SessionTaskPlan.ready(run))
        assertFalse(SessionTaskPlan.complete(run))
        rejectAtomic(run) { assess(run, progress(status = "achieved")) }
    }

    @Test fun toolSchemaRequiresBoundedStagesAndDecoratorDoesNotAlterOriginals() {
        val taskPlanTool = SessionTaskPlan.tool()
        val nativeTool = JSONObject("""{"type":"function","function":{"name":"act","parameters":{"type":"object","properties":{"kind":{"type":"string"}},"required":["kind"],"additionalProperties":false}}}""")
        val original = JSONArray().put(nativeTool).put(taskPlanTool)
        val before = original.toString()
        val decorated = SessionTaskPlan.decorate(original)
        assertEquals(before, original.toString())
        val params = decorated.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals(listOf("kind", "task_progress"), (0 until params.getJSONArray("required").length()).map { params.getJSONArray("required").getString(it) })
        val progressSchema = params.getJSONObject("properties").getJSONObject("task_progress")
        assertEquals(5, progressSchema.getJSONArray("required").length())
        assertFalse(progressSchema.getBoolean("additionalProperties"))
        assertEquals(taskPlanTool.toString(), decorated.getJSONObject(1).toString())
        params.getJSONObject("properties").getJSONObject("kind").put("description", "copy-only")
        assertEquals(before, original.toString())
        val planParams = taskPlanTool.getJSONObject("function").getJSONObject("parameters")
        assertEquals("set_task_plan", taskPlanTool.getJSONObject("function").getString("name"))
        assertEquals(8, planParams.getJSONObject("properties").getJSONObject("stages").getInt("maxItems"))
        assertFalse(planParams.getJSONObject("properties").has("task_progress"))
    }

    @Test fun repeatedDecorationHasExactlyOneProgressRequirement() {
        val input = JSONArray().put(JSONObject("""{"type":"function","function":{"name":"observe_screen","parameters":{"type":"object","properties":{}}}}"""))
        val decorated = SessionTaskPlan.decorate(SessionTaskPlan.decorate(input))
        assertEquals(1, decorated.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONArray("required").length())
    }
}
