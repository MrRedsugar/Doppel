package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitOutputSchemaTest {
    private fun wrapped(value: JSONObject, role: String = "primary") = if (role == "grounding") JSONObject().put("result", value)
        else JSONObject().put("decision", value).put("state", JSONObject.NULL)
    private fun tap() = JSONObject().put("kind", "tap").put("target", "保存按钮").put("expected", "文件保存").put("screen_context", "编辑页")
        .put("request_login_code", JSONObject.NULL)
    private fun groundingTap() = JSONObject().put("status", "located").put("action", "tap").put("points", JSONArray("[[450,120]]"))
        .put("duration_ms", 60).put("assessment", JSONObject().put("alignment", "consistent"))
    private fun response(value: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop").put("message", JSONObject().put("content", value.toString()))))
    private fun replacementState() = JSONObject().put("phase", "navigating").put("remaining_steps", JSONArray().put("找到目标关卡"))
    private fun legacySwipeFormat(): JSONObject = SplitOutputSchema.format("primary").apply {
        getJSONObject("json_schema").put("name", "doppel_a_ab_v8")
        val branches = getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties").getJSONObject("decision").getJSONArray("anyOf")
        repeat(branches.length()) { index ->
            val branch = branches.getJSONObject(index)
            if (branch.getJSONObject("properties").has("start_hold_ms")) {
                branch.getJSONObject("properties").remove("start_hold_ms")
                val required = branch.getJSONArray("required")
                branch.put("required", JSONArray((0 until required.length()).map(required::getString).filterNot { it == "start_hold_ms" }))
            }
        }
    }

    @Test fun schemasAreStrictClosedObjectsAndActionSpecific() {
        for (format in listOf(SplitOutputSchema.format("primary"), SplitOutputSchema.format("primary", true), SplitOutputSchema.format("grounding", expectedAction = "swipe"))) {
            assertEquals("json_schema", format.getString("type"))
            assertTrue(format.getJSONObject("json_schema").getBoolean("strict"))
            fun visit(schema: JSONObject) {
                if (schema.optString("type") == "object") {
                    assertFalse(schema.getBoolean("additionalProperties"))
                    val required = schema.getJSONArray("required")
                    assertEquals(schema.getJSONObject("properties").keys().asSequence().toSet(), (0 until required.length()).map(required::getString).toSet())
                    schema.getJSONObject("properties").keys().forEach { visit(schema.getJSONObject("properties").getJSONObject(it)) }
                }
                schema.optJSONArray("anyOf")?.let { branches -> repeat(branches.length()) { visit(branches.getJSONObject(it)) } }
                schema.optJSONObject("items")?.let(::visit)
            }
            visit(format.getJSONObject("json_schema").getJSONObject("schema"))
        }
        val schema = SplitOutputSchema.format("grounding", expectedAction = "tap").toString()
        assertFalse(schema.contains("required_finger_direction"))
        assertFalse(schema.contains("strokes"))
    }

    @Test fun plannerActionRejectsMissingFieldsAndUnknownKeys() {
        val format = SplitOutputSchema.format("primary")
        SplitOutputSchema.validate(wrapped(tap()), format)
        for (field in listOf("kind", "target", "expected")) {
            assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().apply { remove(field) }), format) }
        }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().put("points", JSONArray("[[1,2]]"))), format) }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().put("expected", JSONObject.NULL)), format) }
        val missing = assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().apply { remove("expected") }), format) }
        assertTrue(missing.message.orEmpty().contains("expected"))
        assertFalse(missing.message.orEmpty().contains("保存按钮"))
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().put("action", "tap")), format) }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap().put("kind", "execute")), format) }
    }

    @Test fun directModeRequiresCoordinatesButAbDoesNot() {
        val format = SplitOutputSchema.format("primary", true)
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(tap()), format) }
        SplitOutputSchema.validate(wrapped(tap().put("points", JSONArray("[[10,20]]")).put("duration_ms", 60)), format)
    }

    @Test fun loginSmsRequestIsRequiredNullablePlannerTapMetadataAndNeverGrounderAuthority() {
        for (direct in listOf(false, true)) {
            val format = SplitOutputSchema.format("primary", direct)
            fun decision() = tap().apply { if (direct) put("points", JSONArray("[[10,20]]")).put("duration_ms", 60) }
            for (flag in listOf(true, false, JSONObject.NULL))
                SplitOutputSchema.validate(wrapped(decision().put("request_login_code", flag)), format)
            for (flag in listOf("true", 1, JSONObject()))
                assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(decision().put("request_login_code", flag)), format) }
            assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(decision().apply { remove("request_login_code") }), format) }
            val nonTap = JSONObject().put("kind", "ask_user").put("message", "请选择登录账户").put("request_login_code", true)
            assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(nonTap), format) }
        }
        val grounder = SplitOutputSchema.format("grounding", expectedAction = "tap")
        assertFalse(grounder.toString().contains("request_login_code"))
        assertThrows(SplitSchemaViolation::class.java) {
            SplitOutputSchema.validate(wrapped(groundingTap().put("request_login_code", true), "grounding"), grounder)
        }
    }

    @Test fun typedReasonBelongsOnlyToExplicitManualTakeover() {
        for (direct in listOf(false, true)) {
            val format = SplitOutputSchema.format("primary", direct)
            val decision = JSONObject().put("kind", "manual_takeover").put("message", "请选择登录方式")
            for (reason in SplitAgentProtocol.takeoverReasons + JSONObject.NULL)
                SplitOutputSchema.validate(wrapped(JSONObject(decision.toString()).put("reason", reason)), format)
            assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(decision), format) }
            for (reason in listOf("other", "登录", true, 1))
                assertThrows(SplitSchemaViolation::class.java) {
                    SplitOutputSchema.validate(wrapped(JSONObject(decision.toString()).put("reason", reason)), format)
                }
            assertThrows(SplitSchemaViolation::class.java) {
                SplitOutputSchema.validate(wrapped(JSONObject().put("kind", "ask_user").put("message", "选择哪个账户")
                    .put("reason", "login")), format)
            }
        }
    }

    @Test fun waitRequiresSpecificFreshEvidenceWithoutActionFields() {
        val decision = JSONObject().put("kind", "wait").put("duration_ms", 800).put("reason", "等待同步")
        val format = SplitOutputSchema.format("primary")
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(decision), format) }
        decision.put("wait_condition", "出现完成入口").put("evidence", "同步从40%推进到60%")
        SplitOutputSchema.validate(wrapped(decision), format)
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(decision.put("duration_ms", 0)), format) }
    }

    @Test fun refusalsDoNotRequireCoordinatesAssessmentOrDirection() {
        val format = SplitOutputSchema.format("grounding", expectedAction = "swipe")
        SplitOutputSchema.validate(wrapped(JSONObject().put("status", "not_found").put("reason", "目标不在图中"), "grounding"), format)
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(groundingTap(), "grounding"), format) }
    }

    @Test fun swipeDeclaresExtentAndGrounderCarriesOnlyCompactDirectionReview() {
        val decision = tap().apply { remove("request_login_code") }.put("kind", "swipe").put("swipe_extent", "small").put("scroll_goal", "inspect").put("boundary_reason", "")
            .put("gesture_semantics", "reveal_content").put("target_relative_direction", "right").put("intended_finger_direction", "left").put("start_hold_ms", 0)
        val format = SplitOutputSchema.format("primary")
        SplitOutputSchema.validate(wrapped(decision), format)
        for (key in listOf("swipe_extent", "scroll_goal", "target_relative_direction", "intended_finger_direction", "start_hold_ms")) {
            assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(JSONObject(decision.toString()).apply { remove(key) }), format) }
        }
        val result = JSONObject().put("status", "located").put("action", "swipe").put("points", JSONArray("[[600,500],[500,500]]")).put("duration_ms", 900)
            .put("assessment", JSONObject().put("alignment", "consistent").put("target_relative_direction", "right").put("required_finger_direction", "left").put("direction_corrected", false))
        val bFormat = SplitOutputSchema.format("grounding", expectedAction = "swipe")
        SplitOutputSchema.validate(wrapped(result, "grounding"), bFormat)
        result.getJSONObject("assessment").put("reason", "irrelevant success explanation")
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(result, "grounding"), bFormat) }
    }

    @Test fun objectDragUsesExistingDirectionFieldsInStrictSingleAndSequenceSchemas() {
        val decision = tap().apply { remove("request_login_code") }.put("kind", "swipe").put("swipe_extent", "small").put("scroll_goal", "inspect").put("boundary_reason", "")
            .put("gesture_semantics", "object_drag").put("target_relative_direction", "unknown").put("intended_finger_direction", "up").put("start_hold_ms", 800)
        for (direct in listOf(false, true)) {
            val candidate = JSONObject(decision.toString())
            if (direct) candidate.put("points", JSONArray("[[800,800],[400,400]]")).put("duration_ms", 900)
            SplitOutputSchema.validate(wrapped(candidate), SplitOutputSchema.format("primary", direct))
            for (invalid in listOf(-1, 3001, "800", 800.5, JSONObject.NULL)) {
                assertThrows(SplitSchemaViolation::class.java) {
                    SplitOutputSchema.validate(wrapped(JSONObject(candidate.toString()).put("start_hold_ms", invalid)), SplitOutputSchema.format("primary", direct))
                }
            }
        }
        val sequence = tap().apply { remove("request_login_code") }.put("kind", "swipe_sequence").put("swipe_extent", "small").put("scroll_goal", "inspect").put("boundary_reason", "")
            .put("gesture_contracts", JSONArray()
                .put(JSONObject().put("gesture_semantics", "object_drag").put("target_relative_direction", "unknown").put("intended_finger_direction", "up").put("start_hold_ms", 800))
                .put(JSONObject().put("gesture_semantics", "physical_gesture").put("target_relative_direction", "unknown").put("intended_finger_direction", "right").put("start_hold_ms", 0)))
        SplitOutputSchema.validate(wrapped(sequence), SplitOutputSchema.format("primary"))
        val withoutFinger = JSONObject(decision.toString()).apply { remove("intended_finger_direction") }
        assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(withoutFinger), SplitOutputSchema.format("primary")) }
        val unknownKind = JSONObject(decision.toString()).put("gesture_semantics", "arbitrary_drag")
        assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(unknownKind), SplitOutputSchema.format("primary")) }
    }

    @Test fun coordinatesAreNumericBoundedAndExpectedActionCannotChange() {
        val format = SplitOutputSchema.format("grounding", expectedAction = "tap")
        SplitOutputSchema.validate(wrapped(groundingTap(), "grounding"), format)
        for (points in listOf("[[1001,10]]", "[[\"450\",10]]", "[[1,2,3]]", "[]")) {
            assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(wrapped(groundingTap().put("points", JSONArray(points)), "grounding"), format) }
        }
    }

    @Test fun unwrappingNullStatePreservesNoUpdateAndDoesNotMutateResponse() {
        val value = wrapped(tap())
        val source = value.toString()
        val parsed = SplitOutputSchema.unwrap(value, "primary")
        assertEquals("execute", parsed.getString("kind")); assertFalse(parsed.has("state")); assertEquals(source, value.toString())
        assertEquals("tap", parsed.getString("action"))
        assertEquals("located", SplitOutputSchema.unwrap(wrapped(groundingTap(), "grounding"), "grounding").getString("status"))
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.unwrap(JSONObject().put("result", tap()), "primary") }
    }

    @Test fun originalLiveSwipeResponseMatchesSingleDiscriminatorV2AndMapsExplicitly() {
        // Exact content from regressions/map-ready-not-loading/response.json; no syntax or field repair.
        val raw = """{"decision":{"kind":"swipe","target":"当前关卡地图区域，手指由左向右小幅滑动，以露出左侧更早的关卡节点","expected":"画面内容向右移动，从而在屏幕左侧或中部暴露出编号更小的关卡（如1-7）","screen_context":"","swipe_extent":"small","scroll_goal":"inspect","boundary_reason":"","gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"},"state":null}"""
        val response = JSONObject(raw)
        val format = legacySwipeFormat()
        assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(response, SplitOutputSchema.format("primary")) }
        SplitOutputSchema.validate(response, format)
        val restored = SplitAgentProtocol.content(this.response(response), format, "primary")
        assertEquals(0, DirectionalGestureContract.parsePlanner("swipe", restored).single().startHoldMs)
        val result = SplitOutputSchema.unwrap(response, "primary")
        assertEquals("execute", result.getString("kind")); assertEquals("swipe", result.getString("action"))
        assertEquals("right", result.getString("intended_finger_direction")); assertFalse(result.has("state"))
        assertEquals("swipe", response.getJSONObject("decision").getString("kind"))
        assertFalse(response.getJSONObject("decision").has("action"))
        val unknown = JSONObject(raw).apply { getJSONObject("decision").put("kind", "swipe_invented") }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(unknown, format) }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.unwrap(unknown, "primary") }
    }

    @Test fun originalBatchTwoMediumBrowseIsRejectedAsNewModelCommand() {
        // Exact unedited content from batch2/regressions/map-ready-not-loading/response.json.
        val raw = """{"state":{"phase":"navigating","facts":["当前在主线章节地图界面，右下角显示 EPISODE 01。","地图上可见的关卡节点有 1-9, 1-10, 1-11, 1-12。","任务目标是找到第一章1-7关卡。"],"completed_steps":["进入主线章节地图界面 (EPISODE 01)。"],"remaining_steps":["向左滑动地图以寻找1-7关卡节点。","点击1-7关卡节点。"],"failed_routes":[]},"decision":{"kind":"swipe","target":"游戏地图中间区域，从右向左滑动","expected":"地图内容向右移动，露出左侧更早期的关卡（如 1-7）","screen_context":"","swipe_extent":"medium","scroll_goal":"inspect","boundary_reason":"","gesture_semantics":"reveal_content","target_relative_direction":"left","intended_finger_direction":"right"}}"""
        val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", raw))))
        val rejection = assertThrows(IllegalArgumentException::class.java) {
            SplitAgentProtocol.content(response, legacySwipeFormat(), "primary")
        }
        assertTrue(rejection.message.orEmpty().contains("swipe_extent"))
        assertEquals(raw, response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))

        // New commands must also explicitly supply the newly added hold field.
        for (direct in listOf(false, true)) {
            val permitted = JSONObject(raw)
            permitted.getJSONObject("decision").put("swipe_extent", "small").put("start_hold_ms", 0)
            if (direct) permitted.getJSONObject("decision").put("points", JSONArray("[[450,500],[550,500]]")).put("duration_ms", 800)
            SplitOutputSchema.validate(SplitOutputSchema.normalizeAdditiveState(permitted), SplitOutputSchema.format("primary", direct))
            permitted.getJSONObject("decision").put("swipe_extent", "medium")
            assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.validate(permitted, SplitOutputSchema.format("primary", direct)) }
        }
    }

    @Test fun completionAndKnowledgeBranchesWorkWithoutNullActionPadding() {
        val values = listOf(JSONObject().put("kind", "finish").put("status", "completed").put("message", "已完成"),
            JSONObject().put("kind", "ask_user").put("message", "选择哪个账户"),
            JSONObject().put("kind", "search_web").put("query", "操作教程"),
            JSONObject().put("kind", "read_web").put("url", "https://example.test/help")
                .put("operation", "read").put("query", "").put("offset", 0).put("limit", 4000))
        for (value in values) SplitOutputSchema.validate(wrapped(value), SplitOutputSchema.format("primary"))
    }

    @Test fun sharedActionFieldsKeepEachKindAndRejectUnrelatedFields() {
        for (direct in listOf(false, true)) {
            val format = SplitOutputSchema.format("primary", direct)
            for (kind in listOf("enter", "back", "home", "recents", "notifications", "quick_settings", "system_screenshot", "paste")) {
                val value = JSONObject().put("kind", kind).put("target", "当前界面").put("expected", "界面变化").put("screen_context", "")
                assertEquals(kind, SplitAgentProtocol.content(response(wrapped(value)), format, "primary").getString("action"))
                for (field in listOf("package_name", "text", "request_login_code", "points", "percent")) {
                    assertThrows(SplitSchemaViolation::class.java) {
                        SplitOutputSchema.validate(wrapped(JSONObject(value.toString()).put(field, JSONObject.NULL)), format)
                    }
                }
            }
            for (kind in listOf("login_username", "login_password")) {
                val value = JSONObject().put("kind", kind).put("target", "输入框").put("expected", "已填写").put("screen_context", "")
                    .put("package_name", "dev.example.app").put("credential_label", "本机账户")
                SplitOutputSchema.validate(wrapped(value), format)
                assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(value.put("kind", "launch")), format) }
            }
            for (kind in listOf("load_skill", "read_skill_resource")) {
                val value = JSONObject().put("kind", kind).put("name", "app")
                if (kind == "read_skill_resource") value.put("path", "references/help.md")
                assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(value), format) }
            }
        }
    }

    @Test fun nullableStateKeepsItsContractAndReportsTheObjectBranchFailure() {
        val format = SplitOutputSchema.format("primary")
        val state = JSONObject().put("phase", "private-phase")
            .put("facts", JSONArray()).put("completed_steps", JSONArray()).put("remaining_steps", JSONArray()).put("failed_routes", JSONArray()).put("progress", JSONObject.NULL)
        SplitOutputSchema.validate(wrapped(tap()).put("state", state), format)
        SplitOutputSchema.validate(wrapped(tap()), format)
        state.remove("facts"); state.remove("failed_routes")
        val error = assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(tap()).put("state", state), format) }
        val diagnostic = error.diagnostic()
        assertEquals("$.state", diagnostic.getString("path"))
        assertEquals("object", diagnostic.getString("schema_branch"))
        assertEquals(setOf("facts", "failed_routes"), error.missingFields.toSet())
        assertFalse(error.message.orEmpty().contains("private-phase"))
        assertFalse(diagnostic.toString().contains("private-phase"))
        state.put("facts", "private-fact").put("failed_routes", JSONArray())
        val typed = assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(tap()).put("state", state), format) }
        assertEquals("$.state.facts", typed.path)
        assertEquals("type", typed.code)
        assertEquals("object", typed.branch)
    }

    @Test fun matchedDecisionBranchReportsMissingAndExtraKeysWithoutValues() {
        val decision = tap().apply { remove("expected") }.put("action", "private-action").put("reason", "private-reason")
        val error = assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(wrapped(decision), SplitOutputSchema.format("primary")) }
        assertEquals("$.decision", error.path)
        assertEquals("kind=tap", error.branch)
        assertEquals(listOf("expected"), error.missingFields)
        assertEquals(listOf("action", "reason"), error.extraFields)
        assertFalse(error.diagnostic().toString().contains("private-"))
        assertFalse(error.message.orEmpty().contains("private-"))
    }

    @Test fun absentAdditiveStateCollectionsDoNotDiscardActionOrExistingMemory() {
        val raw = wrapped(tap()).put("state", replacementState())
        val original = raw.toString()
        var diagnostic: JSONObject? = null
        val result = SplitAgentProtocol.content(response(raw), SplitOutputSchema.format("primary"), "primary") { diagnostic = it }
        assertEquals("tap", result.getString("action"))
        assertEquals(original, diagnostic!!.toString())
        assertEquals(original, raw.toString())
        val update = result.getJSONObject("state")
        for (key in listOf("facts", "completed_steps", "failed_routes")) assertEquals(0, update.getJSONArray(key).length())
        val run = JSONObject().put("goal", "进入目标关卡").put("task_state", JSONObject()
            .put("facts", JSONArray().put("当前在终端"))
            .put("completed_steps", JSONArray().put("打开游戏"))
            .put("failed_routes", JSONArray().put("活动页没有目标"))
            .put("remaining_steps", JSONArray().put("旧计划")))
        SplitTaskState.merge(run, update)
        val state = run.getJSONObject("task_state")
        assertEquals("当前在终端", state.getJSONArray("facts").getString(0))
        assertEquals("打开游戏", state.getJSONArray("completed_steps").getString(0))
        assertEquals("活动页没有目标", state.getJSONArray("failed_routes").getString(0))
        assertEquals("找到目标关卡", state.getJSONArray("remaining_steps").getString(0))
        // The requested wire contract remains strict; recovery is local and restricted.
        assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(raw, SplitOutputSchema.format("primary")) }
    }

    @Test fun missingReplacementPlanAndPhaseAreNeverDefaulted() {
        for (key in listOf("remaining_steps", "phase")) {
            val raw = wrapped(tap()).put("state", replacementState().apply { remove(key) })
            val normalized = SplitOutputSchema.normalizeAdditiveState(raw)
            assertFalse(normalized.getJSONObject("state").has(key))
            val error = assertThrows(SplitSchemaViolation::class.java) {
                SplitAgentProtocol.content(response(raw), SplitOutputSchema.format("primary"), "primary")
            }
            assertEquals(listOf(key), error.missingFields)
            val run = JSONObject().put("goal", "任务").put("task_state", JSONObject()
                .put("phase", "原阶段").put("remaining_steps", JSONArray().put("尚未执行的计划")))
            // Even if used as an update elsewhere, normalization cannot invent a clear operation.
            SplitTaskState.merge(run, normalized.getJSONObject("state"))
            if (key == "remaining_steps") assertEquals("尚未执行的计划", run.getJSONObject("task_state").getJSONArray(key).getString(0))
            else assertEquals("原阶段", run.getJSONObject("task_state").getString(key))
        }
    }

    @Test fun stateDefaultsDoNotCoerceExplicitInvalidValuesOrDropUnknownFields() {
        for (key in listOf("facts", "completed_steps", "failed_routes")) {
            for (invalid in listOf(JSONObject.NULL, "not-an-array", 7, JSONArray().put(42))) {
                val raw = wrapped(tap()).put("state", replacementState().put(key, invalid))
                assertThrows(SplitSchemaViolation::class.java) {
                    SplitAgentProtocol.content(response(raw), SplitOutputSchema.format("primary"), "primary")
                }
            }
        }
        val unknown = wrapped(tap()).put("state", replacementState().put("invented", true))
        val error = assertThrows(SplitSchemaViolation::class.java) {
            SplitAgentProtocol.content(response(unknown), SplitOutputSchema.format("primary"), "primary")
        }
        assertEquals(listOf("invented"), error.extraFields)
        for (invalid in listOf("partial-state", JSONArray())) {
            assertThrows(SplitSchemaViolation::class.java) {
                SplitAgentProtocol.content(response(wrapped(tap()).put("state", invalid)), SplitOutputSchema.format("primary"), "primary")
            }
        }
    }

    @Test fun stateRecoveryNeverWeakensActionCoordinatesOrGrounderContract() {
        val malformedA = listOf(
            tap().apply { remove("expected") } to false,
            tap() to true,
            tap().put("points", JSONArray("[[1001,120]]")).put("duration_ms", 60) to true
        )
        for ((decision, direct) in malformedA) {
            assertThrows(SplitSchemaViolation::class.java) {
                SplitAgentProtocol.content(response(wrapped(decision).put("state", replacementState())), SplitOutputSchema.format("primary", direct), "primary")
            }
        }
        for (key in listOf("points", "duration_ms", "assessment")) {
            val raw = wrapped(groundingTap().apply { remove(key) }, "grounding")
            assertThrows(SplitSchemaViolation::class.java) {
                SplitAgentProtocol.content(response(raw), SplitOutputSchema.format("grounding", expectedAction = "tap"), "grounding")
            }
        }
        val extraState = wrapped(groundingTap(), "grounding").put("state", replacementState())
        assertThrows(SplitSchemaViolation::class.java) {
            SplitAgentProtocol.content(response(extraState), SplitOutputSchema.format("grounding", expectedAction = "tap"), "grounding")
        }
    }

    @Test fun progressIsAnOptionalUpdateInsideAStrictBoundedObject() {
        val format = SplitOutputSchema.format("primary")
        val progress = JSONObject().put("plan", JSONArray().put("打开应用").put("找到目标").put("完成目标"))
            .put("completed", 1).put("total_known", true)
        val raw = wrapped(tap()).put("state", replacementState().put("progress", progress))
        val parsed = SplitAgentProtocol.content(response(raw), format, "primary")
        assertEquals(progress.toString(), parsed.getJSONObject("state").getJSONObject("progress").toString())
        val absent = wrapped(tap()).put("state", replacementState())
        assertTrue(SplitAgentProtocol.content(response(absent), format, "primary").getJSONObject("state").isNull("progress"))
        assertFalse(absent.getJSONObject("state").has("progress"))
        for (invalid in listOf("not-progress", JSONArray(),
            JSONObject(progress.toString()).put("completed", "1"), JSONObject(progress.toString()).put("completed", 6),
            JSONObject(progress.toString()).put("total_known", "true"), JSONObject(progress.toString()).put("extra", true),
            JSONObject(progress.toString()).put("plan", JSONArray((1..6).map { "阶段 $it" })),
            JSONObject(progress.toString()).put("plan", JSONArray().put("过".repeat(61))),
            JSONObject(progress.toString()).put("plan", JSONArray().put(1)))) {
            assertThrows(SplitSchemaViolation::class.java) {
                SplitAgentProtocol.content(response(wrapped(tap()).put("state", replacementState().put("progress", invalid))), format, "primary")
            }
        }
        val reset = JSONObject().put("plan", JSONArray()).put("completed", 0).put("total_known", false)
        assertEquals(reset.toString(), SplitAgentProtocol.content(response(wrapped(tap()).put("state", replacementState().put("progress", reset))),
            format, "primary").getJSONObject("state").getJSONObject("progress").toString())
    }

    @Test fun recordedV2SchemaAndRepliesCanStillReplayWithoutInventingProgress() {
        val legacy = SplitOutputSchema.format("primary")
        legacy.getJSONObject("json_schema").put("name", "doppel_a_ab_v2")
        val stateSchema = legacy.getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties")
            .getJSONObject("state").getJSONArray("anyOf").getJSONObject(0)
        stateSchema.getJSONObject("properties").remove("progress")
        stateSchema.put("required", JSONArray(listOf("phase", "facts", "completed_steps", "remaining_steps", "failed_routes")))
        val raw = wrapped(tap()).put("state", replacementState().put("facts", JSONArray())
            .put("completed_steps", JSONArray()).put("failed_routes", JSONArray()))
        SplitOutputSchema.validate(raw, legacy)
        val parsed = SplitAgentProtocol.content(response(raw), legacy, "primary")
        assertEquals("tap", parsed.getString("action"))
        assertFalse(parsed.getJSONObject("state").has("progress"))
        assertThrows(SplitSchemaViolation::class.java) {
            SplitOutputSchema.validate(JSONObject(raw.toString()).apply { getJSONObject("state").put("progress", JSONObject.NULL) }, legacy)
        }
    }
}
