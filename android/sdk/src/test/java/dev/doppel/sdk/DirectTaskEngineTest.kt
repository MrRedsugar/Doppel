package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectTaskEngineTest {
    private var stored = ""
    private var clock = 1000L
    private var consent: String? = null
    private fun engine(previous: String? = null) = DirectTaskEngine(previous, { stored = it }, { clock }, { consent })
    private fun create(engine: DirectTaskEngine, mode: String = "assist") = engine.create(JSONObject().put("goal", "完成测试任务").put("device_id", DirectRuntime.DEVICE_ID).put("mode", mode)).getString("id")
    private fun screen(id: String = "screen-a", label: String = "下一页", checkable: Boolean = false) = JSONObject()
        .put("screen_id", id).put("package_name", "dev.fixture").put("width", 1080).put("height", 2400)
        .put("payment_consent_id", consent ?: JSONObject.NULL)
        .put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("text", label).put("enabled", true).put("clickable", true)
            .put("password", false).put("checkable", checkable).put("checked", false).put("bounds", JSONArray(listOf(0, 0, 100, 100)))))
    private fun observe(engine: DirectTaskEngine, observation: JSONObject = screen(), data: JSONObject = JSONObject()) {
        val command = engine.poll().getJSONObject("command")
        assertEquals("observe", command.getString("kind"))
        engine.result(JSONObject().put("run_id", command.getString("run_id")).put("command_id", command.getString("id"))
            .put("status", "ok").put("observation", observation).put("data", data))
    }
    private fun reply(tool: String, args: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", tool).put("arguments", args.toString())))))))
    private fun tap(engine: DirectTaskEngine, extra: JSONObject = JSONObject()) {
        val work = engine.takeWork()!!
        engine.accept(work, reply("action", extra.put("kind", "tap").put("target", "n1")))
    }
    private fun resultFor(command: JSONObject, status: String, observation: JSONObject? = null, data: JSONObject = JSONObject()) = JSONObject()
        .put("run_id", command.getString("run_id")).put("command_id", command.getString("id")).put("status", status)
        .put("observation", observation ?: JSONObject.NULL).put("data", data)
    private fun tool(work: DirectTaskEngine.Work, name: String): JSONObject? {
        val tools = work.payload.getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }.firstOrNull { it.getString("name") == name }
    }
    private fun properties(work: DirectTaskEngine.Work, name: String) = requireNotNull(tool(work, name)).getJSONObject("parameters").getJSONObject("properties")
    private fun actionProperties(work: DirectTaskEngine.Work) = properties(work, "action")
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    @Test fun `planner target enum contains only current enabled actionable non password node ids`() {
        val engine = engine(); create(engine)
        val nodes = JSONArray()
        listOf("clickable", "long_clickable", "editable", "scrollable").forEachIndexed { index, capability ->
            nodes.put(JSONObject().put("id", "n0_$index").put("enabled", true).put(capability, true))
        }
        nodes.put(JSONObject().put("id", "n1").put("enabled", false).put("clickable", true))
            .put(JSONObject().put("id", "n2").put("enabled", true).put("editable", true).put("password", true))
            .put(JSONObject().put("id", "n3").put("enabled", true).put("text", "Static text"))
            .put(JSONObject().put("id", "n4").put("clickable", true))
            .put(JSONObject().put("id", " ").put("enabled", true).put("clickable", true))
            .put(JSONObject().put("id", 123).put("enabled", true).put("clickable", true))
            .put(JSONObject().put("id", JSONObject.NULL).put("enabled", true).put("clickable", true))
            .put(JSONObject().put("id", "n" + "1".repeat(120)).put("enabled", true).put("clickable", true))
            .put(JSONObject().put("enabled", true).put("clickable", true))
            .put(JSONObject().put("id", "n0_0").put("enabled", true).put("clickable", true))
        observe(engine, screen().put("nodes", nodes), JSONObject().put("scroll_directions", JSONObject().put("n0_3", JSONArray(listOf("up")))))
        val properties = actionProperties(engine.takeWork()!!)
        assertEquals(listOf("n0_0", "n0_1", "n0_2", "n0_3"), strings(properties.getJSONObject("target").getJSONArray("enum")))
        assertEquals("string", properties.getJSONObject("target").getString("type"))
    }
    @Test fun `target enum excludes nodes beyond the compact screen node limit`() {
        val engine = engine(); create(engine)
        val nodes = JSONArray((0 until 225).map { index -> JSONObject().put("id", "n$index").put("text", "Button $index").put("enabled", true).put("clickable", true) })
        observe(engine, screen().put("nodes", nodes))
        val work = engine.takeWork()!!
        val targets = strings(actionProperties(work).getJSONObject("target").getJSONArray("enum"))
        assertEquals((0 until 220).map { "n$it" }, targets)
        val text = work.payload.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(text.contains("n219: Button 219")); assertFalse(text.contains("n220: Button 220"))
    }
    @Test fun `target enum and displayed rows share the compact screen character limit`() {
        val engine = engine(); create(engine)
        val nodes = JSONArray((0 until 130).map { index -> JSONObject().put("id", "n$index").put("text", "x".repeat(220)).put("enabled", true).put("clickable", true) })
        observe(engine, screen().put("nodes", nodes))
        val work = engine.takeWork()!!
        val targets = strings(actionProperties(work).getJSONObject("target").getJSONArray("enum"))
        assertTrue(targets.isNotEmpty()); assertTrue(targets.size < 130)
        val lines = work.payload.getJSONArray("messages").getJSONObject(1).getString("content").lines()
        for (index in 0 until 130) assertEquals("n$index", lines.any { it.startsWith("n$index: ") }, targets.contains("n$index"))
    }
    @Test fun `fresh observation replaces target enum without changing previous request snapshot`() {
        val engine = engine(); create(engine); observe(engine)
        val first = engine.takeWork()!!
        assertEquals(listOf("n1"), strings(actionProperties(first).getJSONObject("target").getJSONArray("enum")))
        engine.accept(first, reply("navigate", JSONObject().put("kind", "observe")))
        val refreshed = screen("screen-b")
        refreshed.getJSONArray("nodes").getJSONObject(0).put("id", "n2")
        observe(engine, refreshed)
        val next = engine.takeWork()!!
        assertEquals(listOf("n2"), strings(actionProperties(next).getJSONObject("target").getJSONArray("enum")))
        assertEquals(listOf("n1"), strings(actionProperties(first).getJSONObject("target").getJSONArray("enum")))
    }
    @Test fun `screen without actionable nodes omits target and node actions while keeping inspection and navigation`() {
        val engine = engine(); val id = create(engine)
        observe(engine, screen().put("nodes", JSONArray().put(JSONObject().put("id", "n1").put("enabled", true).put("text", "Canvas"))))
        val work = engine.takeWork()!!
        assertNull(tool(work, "action")); assertNull(tool(work, "launch"))
        val navigation = properties(work, "navigate")
        assertFalse(navigation.has("target"))
        val kinds = strings(navigation.getJSONObject("kind").getJSONArray("enum"))
        assertEquals(setOf("observe", "wait", "back", "home", "recents", "notifications", "quick_settings", "split_screen"), kinds.toSet())
        val tools = work.payload.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue(names.containsAll(listOf("inspect_screen", "ask_user", "finish")))
        engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `empty observation never emits an empty target enum or removes global actions`() {
        val engine = engine(); create(engine); observe(engine, screen().put("nodes", JSONArray()))
        val work = engine.takeWork()!!
        assertNull(tool(work, "action")); assertNull(tool(work, "launch"))
        val navigation = properties(work, "navigate")
        assertFalse(navigation.has("target"))
        val kinds = strings(navigation.getJSONObject("kind").getJSONArray("enum"))
        assertFalse(kinds.contains("tap")); assertTrue(kinds.containsAll(listOf("observe", "back", "home")))
    }
    @Test fun `node tool requires target while navigation has no node or package arguments`() {
        val engine = engine(); create(engine); observe(engine)
        val work = engine.takeWork()!!
        val action = requireNotNull(tool(work, "action")).getJSONObject("parameters")
        assertEquals(setOf("kind", "target"), strings(action.getJSONArray("required")).toSet())
        assertEquals(setOf("tap"), strings(actionProperties(work).getJSONObject("kind").getJSONArray("enum")).toSet())
        assertFalse(actionProperties(work).has("package_name")); assertFalse(actionProperties(work).has("duration_ms"))
        val navigation = properties(work, "navigate")
        assertFalse(navigation.has("target")); assertFalse(navigation.has("package_name"))
        assertFalse(strings(navigation.getJSONObject("kind").getJSONArray("enum")).contains("launch"))
    }
    @Test fun `launch schema uses only reported package names and requires package argument`() {
        val engine = engine(); create(engine)
        val apps = JSONArray().put(JSONObject().put("package_name", "dev.fixture").put("label", "com.invented.from.label"))
            .put(JSONObject().put("package_name", "com.android.settings"))
            .put(JSONObject().put("package_name", "dev.fixture"))
            .put(JSONObject().put("package_name", ""))
            .put(JSONObject().put("package_name", "not a package"))
            .put(JSONObject().put("package_name", 123))
            .put(JSONObject().put("label", "com.label.only"))
        observe(engine, screen(), JSONObject().put("apps", apps))
        val work = engine.takeWork()!!
        val launch = requireNotNull(tool(work, "launch")).getJSONObject("parameters")
        assertEquals(listOf("package_name"), strings(launch.getJSONArray("required")))
        assertEquals(listOf("dev.fixture", "com.android.settings"), strings(properties(work, "launch").getJSONObject("package_name").getJSONArray("enum")))
        assertFalse(properties(work, "launch").has("target")); assertFalse(properties(work, "launch").has("kind"))
        engine.accept(work, reply("launch", JSONObject().put("package_name", "com.android.settings")))
        val command = engine.poll().getJSONObject("command")
        assertEquals("launch", command.getString("kind")); assertEquals("com.android.settings", command.getString("package_name"))
        assertFalse(command.has("target"))
    }
    @Test fun `navigation dispatches through existing host without a target`() {
        for (kind in listOf("observe", "wait", "back", "home", "recents", "notifications", "quick_settings", "split_screen")) {
            val engine = engine(); create(engine, "full"); observe(engine)
            engine.accept(engine.takeWork()!!, reply("navigate", JSONObject().put("kind", kind)))
            val command = engine.poll().getJSONObject("command")
            assertEquals(kind, command.getString("kind")); assertFalse(command.has("target"))
            if (kind == "wait") assertEquals(1000, command.getInt("duration_ms"))
        }
    }
    @Test fun `node free screen still offers launch when a real launchable package is reported`() {
        val engine = engine(); create(engine)
        observe(engine, screen().put("nodes", JSONArray()), JSONObject().put("apps", JSONArray().put(JSONObject().put("package_name", "dev.fixture"))))
        val work = engine.takeWork()!!
        assertNull(tool(work, "action")); assertNotNull(tool(work, "navigate"))
        assertEquals(listOf("dev.fixture"), strings(properties(work, "launch").getJSONObject("package_name").getJSONArray("enum")))
    }
    @Test fun `tool kind mismatch missing target and invented launch are rejected without retry`() {
        val invalid = listOf(
            "action" to JSONObject().put("kind", "tap"),
            "action" to JSONObject().put("kind", "launch").put("package_name", "dev.fixture"),
            "navigate" to JSONObject().put("kind", "tap").put("target", "n1"),
            "navigate" to JSONObject().put("kind", "launch").put("package_name", "dev.fixture"),
            "launch" to JSONObject(),
            "launch" to JSONObject().put("package_name", "dev.invented")
        )
        for ((name, args) in invalid) {
            val engine = engine(); val id = create(engine)
            observe(engine, screen(), JSONObject().put("apps", JSONArray().put(JSONObject().put("package_name", "dev.fixture"))))
            engine.accept(engine.takeWork()!!, reply(name, args))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
            assertNull(engine.takeWork()); assertEquals(1, engine.get(id).getInt("calls"))
        }
    }
    @Test fun `typed run produces a single device command and final summary`() {
        val engine = engine(); val id = create(engine); observe(engine)
        tap(engine)
        val command = engine.poll().getJSONObject("command")
        assertEquals("tap", command.getString("kind")); assertEquals("screen-a", command.getString("screen_id"))
        assertEquals(command.getString("id"), engine.poll().getJSONObject("command").getString("id"))
        engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "ok").put("observation", screen("screen-b")))
        engine.accept(engine.takeWork()!!, reply("finish", JSONObject().put("summary", "已根据新界面确认完成").put("outcome", "completed")
            .put("screen_id", "screen-b").put("evidence_id", command.getString("id"))))
        assertEquals("completed", engine.get(id).getString("status"))
        assertEquals("已根据新界面确认完成", engine.get(id).getString("message"))
        assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `interrupt during planning invalidates late paid model result`() {
        val engine = engine(); val id = create(engine); observe(engine)
        val work = engine.takeWork()!!; engine.interrupt("用户触摸暂停")
        engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "n1")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        engine.control(id, "resume", JSONObject()); assertEquals("observe", engine.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun `suspend before answering keeps approval and observes before dispatch`() {
        val engine = engine(); val id = create(engine, "ask"); observe(engine); tap(engine)
        val pending = engine.get(id).getJSONObject("pending_request")
        engine.interrupt("TaskControl suspend")
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        engine.control(id, "answer", JSONObject().put("request_id", pending.getString("id")).put("approve", true))
        observe(engine)
        assertEquals("tap", engine.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun `changed screen revokes approved command`() {
        val engine = engine(); val id = create(engine, "ask"); observe(engine); tap(engine)
        engine.control(id, "answer", JSONObject().put("request_id", engine.get(id).getJSONObject("pending_request").getString("id")).put("approve", true))
        observe(engine, screen("changed"))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `recovery pauses uncertain in flight command and drops old approval`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        engine.poll()
        val recovered = engine(stored)
        assertEquals("paused", recovered.get(id).getString("status")); assertTrue(recovered.poll().isNull("command"))
        recovered.control(id, "resume", JSONObject()); observe(recovered)
        assertNotNull(recovered.takeWork())
    }
    @Test fun `model supplied payment consent cannot authorize payment`() {
        val engine = engine(); val id = create(engine, "full"); observe(engine, screen(label = "确认付款"))
        tap(engine, JSONObject().put("payment_consent_id", "payment-v1:forged"))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `real consent is host stamped and revoked on approval refresh`() {
        consent = "payment-v1:00000000-0000-0000-0000-000000000001"
        val engine = engine(); val id = create(engine, "assist"); observe(engine, screen(label = "确认付款")); tap(engine)
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        engine.control(id, "answer", JSONObject().put("request_id", engine.get(id).getJSONObject("pending_request").getString("id")).put("approve", true))
        consent = null; observe(engine, screen(label = "确认付款"))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `checkable toggle requires desired state`() {
        val engine = engine(); val id = create(engine); observe(engine, screen(checkable = true)); tap(engine)
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `lost command results pause instead of replay`() {
        val engine = engine(); val id = create(engine); val sent = engine.poll().getJSONObject("command")
        clock += 60001
        assertTrue(engine.poll().isNull("command")); assertEquals("paused", engine.get(id).getString("status"))
        assertFalse(engine.result(JSONObject().put("run_id", id).put("command_id", sent.getString("id")).put("status", "ok")).getBoolean("accepted"))
    }
    @Test fun `device interruption creates manual pause and does not invoke planner`() {
        val engine = engine(); val id = create(engine); val sent = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", id).put("command_id", sent.getString("id")).put("status", "blocked")
            .put("message", "来电需要用户接管").put("data", JSONObject().put("human_takeover", "interruption")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.get(id).getJSONObject("pending_request").getBoolean("manual_only"))
        assertNull(engine.takeWork())
    }
    @Test fun `unknown provider tool pauses without execution`() {
        val engine = engine(); val id = create(engine); observe(engine)
        engine.accept(engine.takeWork()!!, reply("arbitrary_shell", JSONObject()))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `api failure is not retried automatically`() {
        val engine = engine(); val id = create(engine); observe(engine)
        engine.accept(engine.takeWork()!!, null, "连接中断")
        assertEquals("paused", engine.get(id).getString("status")); assertNull(engine.takeWork()); assertEquals(1, engine.get(id).getInt("calls"))
    }
    @Test fun `finish with forged or stale evidence cannot complete`() {
        val engine = engine(); val id = create(engine); observe(engine)
        engine.accept(engine.takeWork()!!, reply("finish", JSONObject().put("summary", "完成").put("outcome", "completed").put("screen_id", "screen-a").put("evidence_id", "forged")))
        assertEquals("paused", engine.get(id).getString("status"))
        engine.control(id, "resume", JSONObject()); observe(engine, screen("new-screen"))
        engine.accept(engine.takeWork()!!, reply("finish", JSONObject().put("summary", "完成").put("outcome", "completed").put("screen_id", "screen-a").put("evidence_id", "old")))
        assertEquals("paused", engine.get(id).getString("status"))
    }
    @Test fun `ask mode launch approval binds current screen then executes after refresh`() {
        val engine = engine(); val id = create(engine, "ask"); val observed = engine.poll().getJSONObject("command")
        engine.result(JSONObject().put("run_id", id).put("command_id", observed.getString("id")).put("status", "ok").put("observation", screen())
            .put("data", JSONObject().put("apps", JSONArray().put(JSONObject().put("package_name", "dev.fixture").put("label", "Fixture")))))
        engine.accept(engine.takeWork()!!, reply("launch", JSONObject().put("package_name", "dev.fixture")))
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        engine.control(id, "answer", JSONObject().put("request_id", engine.get(id).getJSONObject("pending_request").getString("id")).put("approve", true))
        observe(engine)
        assertEquals("launch", engine.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun `vision only runs on returned screenshot and feeds compact analysis back to planner`() {
        val engine = engine(); val id = create(engine); observe(engine)
        engine.accept(engine.takeWork()!!, reply("inspect_screen", JSONObject().put("question", "描述图标与界面")))
        val shot = engine.poll().getJSONObject("command")
        assertTrue(shot.getBoolean("include_screenshot"))
        engine.result(JSONObject().put("run_id", id).put("command_id", shot.getString("id")).put("status", "ok").put("observation", screen())
            .put("data", JSONObject().put("image_base64", "aW1hZ2U=").put("mime_type", "image/png")))
        val work = engine.takeWork()!!
        assertTrue(work.vision); assertEquals("mimo-v2.5", work.payload.getString("model"))
        assertFalse(work.payload.has("tools"))
        engine.accept(work, JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", "屏幕可见下一页按钮")))))
        val next = engine.takeWork()!!
        assertEquals("mimo-v2.5-pro", next.payload.getString("model"))
        assertTrue(next.payload.toString().contains("屏幕可见下一页按钮"))
        assertFalse(stored.contains("aW1hZ2U="))
    }
    @Test fun `stale action requests fresh screen and new planning without replaying old mutation`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        val oldTap = engine.poll().getJSONObject("command")
        engine.result(resultFor(oldTap, "stale", screen("untrusted-stale-result")))
        assertEquals("running", engine.get(id).getString("status"))
        assertEquals(1, engine.get(id).getInt("consecutive_stale"))
        val refresh = engine.poll().getJSONObject("command")
        assertEquals("observe", refresh.getString("kind")); assertNotEquals(oldTap.getString("id"), refresh.getString("id"))
        assertFalse(refresh.has("target")); assertNull(engine.takeWork())
        assertFalse(engine.result(resultFor(oldTap, "ok", screen())).getBoolean("accepted"))
        observe(engine, screen("fresh-screen")); assertEquals(1, engine.get(id).getInt("consecutive_stale"))
        tap(engine)
        val newTap = engine.poll().getJSONObject("command")
        assertNotEquals(oldTap.getString("id"), newTap.getString("id")); assertEquals("fresh-screen", newTap.getString("screen_id"))
        engine.result(resultFor(newTap, "ok", screen("after-tap")))
        assertEquals(0, engine.get(id).getInt("consecutive_stale")); assertEquals(1, engine.get(id).getInt("successful_mutations"))
    }
    @Test fun `third stale result pauses and successful observations do not reset the cap`() {
        val engine = engine(); val id = create(engine); observe(engine)
        repeat(3) { index ->
            tap(engine); engine.result(resultFor(engine.poll().getJSONObject("command"), "stale"))
            assertEquals(index + 1, engine.get(id).getInt("consecutive_stale"))
            if (index < 2) { assertEquals("running", engine.get(id).getString("status")); observe(engine) }
        }
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
    }
    @Test fun `stale recovery respects cancellation and rejects old completion evidence`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        val old = engine.poll().getJSONObject("command"); engine.result(resultFor(old, "stale"))
        val refresh = engine.poll().getJSONObject("command"); engine.control(id, "cancel", JSONObject())
        assertFalse(engine.result(resultFor(refresh, "ok", screen())).getBoolean("accepted"))
        assertEquals("cancelled", engine.get(id).getString("status")); assertNull(engine.takeWork())
        val next = create(engine); observe(engine); tap(engine)
        val stale = engine.poll().getJSONObject("command"); engine.result(resultFor(stale, "stale")); observe(engine, screen("fresh"))
        engine.accept(engine.takeWork()!!, reply("finish", JSONObject().put("outcome", "completed").put("summary", "Done")
            .put("screen_id", "screen-a").put("evidence_id", stale.getString("id"))))
        assertEquals("paused", engine.get(next).getString("status"))
    }
    @Test fun `stale recovery cannot preserve a prior approval`() {
        val engine = engine(); val id = create(engine, "ask"); observe(engine); tap(engine)
        val request = engine.get(id).getJSONObject("pending_request").getString("id")
        engine.control(id, "answer", JSONObject().put("request_id", request).put("approve", true)); observe(engine)
        val approvedTap = engine.poll().getJSONObject("command"); engine.result(resultFor(approvedTap, "stale")); observe(engine)
        assertTrue(engine.poll().isNull("command")); tap(engine)
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        assertNotEquals(request, engine.get(id).getJSONObject("pending_request").getString("id"))
    }
    @Test fun `stale replan checks revoked consent instead of reusing old payment command`() {
        consent = "payment-v1:00000000-0000-0000-0000-000000000001"
        val engine = engine(); val id = create(engine, "full"); observe(engine, screen(label = "确认付款")); tap(engine)
        val payment = engine.poll().getJSONObject("command"); assertEquals(consent, payment.getString("payment_consent_id"))
        engine.result(resultFor(payment, "stale")); consent = null; observe(engine, screen(label = "确认付款")); tap(engine)
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `device errors and manual takeover never enter stale recovery`() {
        for (status in listOf("error", "blocked", "cancelled")) {
            val engine = engine(); val id = create(engine); observe(engine); tap(engine)
            engine.result(resultFor(engine.poll().getJSONObject("command"), status))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        }
        val engine = engine(); val id = create(engine)
        engine.result(resultFor(engine.poll().getJSONObject("command"), "stale", data = JSONObject().put("human_takeover", "payment")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.get(id).getJSONObject("pending_request").getBoolean("manual_only"))
    }
    @Test fun `failed device scroll preserves execution capabilities and action ids without replay or private fields`() {
        val engine = engine(); val id = create(engine)
        val observation = screen(label = "private fixture label")
        observation.getJSONArray("nodes").getJSONObject(0).put("scrollable", true)
        observe(engine, observation, JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf("down")))))
        engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down")))
        val command = engine.poll().getJSONObject("command")
        val execution = JSONObject().put("node_present", true).put("enabled", true).put("clickable", false)
            .put("long_clickable", false).put("editable", false).put("scrollable", true).put("password", false)
            .put("requested_action_id", 4096).put("requested_action_advertised", false)
            .put("action_ids", JSONArray(listOf(16, 16908346))).put("text", "private execution label")
            .put("provider_response", "private-provider-response-fixture")
        engine.result(resultFor(command, "error", data = JSONObject().put("action_diagnostic", execution).put("private", "private result text"))
            .put("message", "Control rejected action"))
        val run = engine.get(id); val diagnostic = run.getJSONObject("device_diagnostic")
        assertEquals("scroll", diagnostic.getString("kind")); assertEquals("down", diagnostic.getString("direction"))
        assertEquals("error", diagnostic.getString("status")); assertEquals("execution", diagnostic.getString("source"))
        assertEquals("n1", diagnostic.getString("target")); assertTrue(diagnostic.getBoolean("scrollable"))
        assertFalse(diagnostic.getBoolean("clickable")); assertFalse(diagnostic.getBoolean("requested_action_advertised"))
        assertEquals(4096, diagnostic.getInt("requested_action_id")); assertEquals(16908346, diagnostic.getJSONArray("action_ids").getInt(1))
        assertFalse(diagnostic.toString().contains("private")); assertFalse(stored.contains("private execution label"))
        assertFalse(stored.contains("private-provider-response-fixture")); assertFalse(stored.contains("private result text"))
        assertEquals("Control rejected action", run.getString("message")); assertEquals("paused", run.getString("status"))
        assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork()); assertEquals(0, run.getInt("successful_mutations"))
    }
    @Test fun `reported scroll directions appear in planning and permit exactly the declared operation`() {
        for (direction in listOf("up", "down", "left", "right")) {
            val engine = engine(); create(engine)
            val observation = screen(label = "Scroll area")
            observation.getJSONArray("nodes").getJSONObject(0).put("clickable", false).put("scrollable", true)
            observe(engine, observation, JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf(direction, direction)))))
            val work = engine.takeWork()!!
            val context = work.payload.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(context.contains("[滚动]")); assertTrue(context.contains("scroll_directions=[$direction]"))
            assertEquals(listOf("n1"), strings(actionProperties(work).getJSONObject("target").getJSONArray("enum")))
            assertFalse(observation.getJSONArray("nodes").getJSONObject(0).has("scroll_directions"))
            engine.accept(work, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", direction)))
            val command = engine.poll().getJSONObject("command")
            assertEquals("scroll", command.getString("kind")); assertEquals(direction, command.getString("direction"))
        }
    }
    @Test fun `down scroll is rejected before execution when only up is declared`() {
        val engine = engine(); val id = create(engine)
        val observation = screen().apply { getJSONArray("nodes").getJSONObject(0).put("scrollable", true) }
        observe(engine, observation, JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf("up")))))
        engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        assertNull(engine.takeWork()); assertEquals(0, engine.get(id).getInt("successful_mutations"))
        engine.control(id, "resume", JSONObject())
        assertEquals("observe", engine.poll().getJSONObject("command").getString("kind"))
    }
    @Test fun `missing malformed or forged node directions never advertise or permit scrolling`() {
        val invalid = listOf(
            JSONObject(),
            JSONObject().put("scroll_directions", "down"),
            JSONObject().put("scroll_directions", JSONObject().put("n1", "down")),
            JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf("down", "unknown")))),
            JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray().put("down").put(1))),
            JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(List(5) { "down" }))),
            JSONObject().put("scroll_directions", JSONObject().put("n999", JSONArray(listOf("down"))))
        )
        for (data in invalid) {
            val engine = engine(); val id = create(engine)
            val observation = screen(label = "Scroll area")
            observation.getJSONArray("nodes").getJSONObject(0).put("clickable", false).put("scrollable", true)
                .put("scroll_directions", JSONArray(listOf("down")))
            observe(engine, observation, data)
            val work = engine.takeWork()!!
            val context = work.payload.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(context.contains("scroll_directions=unknown")); assertFalse(context.contains("[滚动]"))
            assertNull(tool(work, "action"))
            engine.accept(work, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down")))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun `empty direction declaration is known boundary and is never advertised as scrollable action`() {
        val engine = engine(); val id = create(engine)
        val observation = screen(label = "Boundary")
        observation.getJSONArray("nodes").getJSONObject(0).put("clickable", false).put("scrollable", true)
        observe(engine, observation, JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray())))
        val work = engine.takeWork()!!
        assertTrue(work.payload.getJSONArray("messages").getJSONObject(1).getString("content").contains("scroll_directions=[]"))
        assertNull(tool(work, "action"))
        engine.accept(work, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "up")))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
    @Test fun `boundary no op refreshes directions without counting a mutation or reusing old permissions`() {
        for (nextMetadata in listOf(JSONObject(), JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf("up")))))) {
            val engine = engine(); val id = create(engine)
            val observation = screen().apply { getJSONArray("nodes").getJSONObject(0).put("scrollable", true) }
            observe(engine, observation, JSONObject().put("scroll_directions", JSONObject().put("n1", JSONArray(listOf("down")))))
            engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down")))
            val command = engine.poll().getJSONObject("command")
            val fresh = screen("after-boundary").apply { getJSONArray("nodes").getJSONObject(0).put("scrollable", true) }
            engine.result(resultFor(command, "ok", fresh, nextMetadata.put("no_op", true).put("action_state", "scroll_boundary")))
            assertEquals("running", engine.get(id).getString("status")); assertEquals(0, engine.get(id).getInt("successful_mutations"))
            val work = engine.takeWork()!!
            val context = work.payload.getJSONArray("messages").getJSONObject(1).getString("content")
            assertFalse(context.contains("scroll_directions=[down]"))
            engine.accept(work, reply("action", JSONObject().put("kind", "scroll").put("target", "n1").put("direction", "down")))
            assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
        }
    }
    @Test fun `device diagnostic accepts only bounded integer action ids and real booleans`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        val ids = JSONArray().put("private action label").put("4096").put(1.5).put(-1).put(0).put(Int.MAX_VALUE.toLong() + 1)
        for (index in 1..40) ids.put(index)
        val execution = JSONObject().put("node_present", true).put("enabled", "private boolean").put("clickable", true)
            .put("requested_action_id", "16908346").put("requested_action_advertised", "true").put("action_ids", ids)
        engine.result(resultFor(engine.poll().getJSONObject("command"), "private result status", data = JSONObject().put("action_diagnostic", execution)))
        val diagnostic = engine.get(id).getJSONObject("device_diagnostic")
        assertEquals("unknown", diagnostic.getString("status")); assertFalse(diagnostic.has("enabled"))
        assertFalse(diagnostic.has("requested_action_id")); assertFalse(diagnostic.has("requested_action_advertised"))
        assertFalse(diagnostic.toString().contains("private")); assertEquals(32, diagnostic.getJSONArray("action_ids").length())
        assertEquals(1, diagnostic.getJSONArray("action_ids").getInt(0)); assertEquals(32, diagnostic.getJSONArray("action_ids").getInt(31))
    }
    @Test fun `missing execution metadata is explicitly historical and disappears after a successful fresh observation`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        val after = screen("screen-after")
        after.getJSONArray("nodes").getJSONObject(0).put("enabled", false).put("clickable", false)
        engine.result(resultFor(engine.poll().getJSONObject("command"), "error", after))
        val diagnostic = engine.get(id).getJSONObject("device_diagnostic")
        assertEquals("planning_observation", diagnostic.getString("source")); assertTrue(diagnostic.getBoolean("clickable"))
        assertTrue(diagnostic.getBoolean("enabled")); assertFalse(diagnostic.has("action_ids")); assertFalse(diagnostic.has("requested_action_id"))
        engine.control(id, "resume", JSONObject()); observe(engine, screen("fresh"))
        assertFalse(engine.get(id).has("device_diagnostic")); assertEquals("running", engine.get(id).getString("status"))
    }
    @Test fun `no op mutations do not erase stale history or claim actual interaction`() {
        val engine = engine(); val id = create(engine); observe(engine); tap(engine)
        engine.result(resultFor(engine.poll().getJSONObject("command"), "stale")); observe(engine); tap(engine)
        engine.result(resultFor(engine.poll().getJSONObject("command"), "ok", screen(), JSONObject().put("no_op", true)))
        assertEquals(1, engine.get(id).getInt("consecutive_stale")); assertEquals(0, engine.get(id).getInt("successful_mutations"))
    }
    @Test fun `unknown node diagnostic identifies missing reference without copying screen labels`() {
        val engine = engine(); val id = create(engine); observe(engine, screen(label = "private fixture label"))
        engine.accept(engine.takeWork()!!, reply("action", JSONObject().put("kind", "tap").put("target", "n999")))
        val message = engine.get(id).getString("message")
        assertTrue(message.contains("\"target\":\"n999\"")); assertTrue(message.contains("\"reference_found\":false"))
        assertTrue(message.contains("\"screen_matches\":true")); assertFalse(message.contains("private fixture label"))
        assertTrue(engine.poll().isNull("command")); assertEquals(0, engine.get(id).getInt("consecutive_stale"))
    }
    @Test fun `disabled node is marked in compact input and identified in validation diagnostic`() {
        val engine = engine(); val id = create(engine)
        val observation = screen(label = "private disabled label")
        observation.getJSONArray("nodes").getJSONObject(0).put("enabled", false)
        observe(engine, observation)
        val work = engine.takeWork()!!
        assertTrue(work.payload.toString().contains("enabled=false"))
        assertTrue(work.payload.toString().contains("private disabled label [] enabled=false"))
        engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "n1")))
        val message = engine.get(id).getString("message")
        assertTrue(message.contains("\"reference_found\":true")); assertTrue(message.contains("\"target_enabled\":false"))
        assertFalse(message.contains("private disabled label")); assertEquals("paused", engine.get(id).getString("status"))
    }
    @Test fun `invalid reference text is never reflected into host diagnostic`() {
        val engine = engine(); val id = create(engine); observe(engine)
        val work = engine.takeWork()!!
        assertEquals(listOf("n1"), strings(actionProperties(work).getJSONObject("target").getJSONArray("enum")))
        engine.accept(work, reply("action", JSONObject().put("kind", "tap").put("target", "sk-private-provider-value")))
        val message = engine.get(id).getString("message")
        assertFalse(message.contains("sk-private-provider-value")); assertTrue(message.contains("<non-node-reference>"))
        assertEquals("paused", engine.get(id).getString("status")); assertTrue(engine.poll().isNull("command"))
    }
}
