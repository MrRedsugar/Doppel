package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitLoginVerificationTest {
    private class Fixture(val direct: Boolean) {
        val pkg = "dev.fixture.login"
        var saved = ""
        var engine = newEngine(null)
        val id = engine.create(JSONObject().put("goal", "登录已有账号").put("device_id", "direct-this-phone")
            .put("mode", "full")).getString("id")

        private fun newEngine(state: String?) = SplitTaskEngine(state, { saved = it }, { 1000L }, enhancementEnabled = { !direct })
        init { screen() }
        fun restore() { engine = newEngine(saved) }
        fun work() = engine.takeWork()!!
        fun decide(body: JSONObject, work: SplitTaskEngine.Work = work()) = engine.accept(work, SplitTestReply.response(body))
        fun command() = engine.poll().getJSONObject("command")
        fun result(data: JSONObject = JSONObject().put("action_state", "accepted"), status: String = "ok") {
            val command = command()
            engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", id)
                .put("status", status).put("data", data))
        }
        fun screen(app: String = pkg) {
            val command = command()
            assertEquals("screenshot", command.getString("kind"))
            engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", id).put("status", "ok")
                .put("observation", JSONObject().put("screen_id", "login-screen").put("package_name", app))
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                    .put("capture_id", command.getString("id")).put("package_name", app)
                    .put("display_width", 1080).put("display_height", 1920).put("rotation", 0))))
        }
        fun fill(kind: String = "login_password", data: JSONObject = JSONObject().put("action_state", "accepted"), status: String = "ok") {
            decide(JSONObject().put("kind", "execute").put("action", kind).put("target", "当前登录输入框")
                .put("expected", "本机填写登录资料").put("package_name", pkg).apply {
                    if (kind in setOf("login_username", "login_password")) put("credential_label", "Fixture account")
                    if (kind == "login_code") put("code_candidate_id", JSONObject.NULL)
                })
            assertEquals(kind, command().getString("kind"))
            result(data, status); screen()
        }
        fun verification(phase: String, app: String = pkg) = JSONObject().put("kind", "login_verification")
            .put("phase", phase).put("package_name", app).put("reason", "本次已有账号登录挑战")
        fun tap(work: SplitTaskEngine.Work = work()): JSONObject {
            decide(JSONObject().put("kind", "execute").put("action", "tap").put("target", "当前挑战中的目标")
                .put("expected", "目标被选中").apply {
                    if (direct) put("points", JSONArray("[[500,600]]")).put("duration_ms", 60)
                }, work)
            if (!direct) {
                screen()
                val grounding = this.work()
                assertTrue(grounding.grounding)
                decide(JSONObject().put("status", "located").put("action", "tap").put("points", JSONArray("[[500,600]]"))
                    .put("duration_ms", 60).put("assessment", JSONObject().put("alignment", "consistent")), grounding)
            }
            return command().also { assertEquals("split_action", it.getString("kind")) }
        }
        fun assertManual() {
            assertEquals("paused", engine.statusOrNull(id))
            assertTrue(engine.get(id).getJSONObject("pending_request").getBoolean("manual_only"))
            assertTrue(engine.poll().isNull("command")); assertNull(engine.takeWork())
        }
    }

    private fun context(work: SplitTaskEngine.Work): JSONObject {
        val messages = work.payload.getJSONArray("messages")
        return JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun eligibilityRequiresAnAcceptedLocalAccountOrPhoneFillInBothModes() {
        for (direct in listOf(false, true)) for (kind in listOf("login_username", "login_password", "login_phone", "login_code")) {
            for ((status, data) in listOf("ok" to JSONObject().put("action_state", "accepted"), "ok" to JSONObject(),
                "error" to JSONObject().put("action_state", "accepted"))) {
                val f = Fixture(direct)
                f.fill(kind, data, status)
                val work = f.work()
                val eligible = kind != "login_code" && status == "ok" && data.optString("action_state") == "accepted"
                assertEquals(eligible, context(work).getJSONObject("login_verification").getBoolean("can_begin"))
                f.decide(f.verification("begin"), work)
                if (eligible) assertEquals("running", f.engine.statusOrNull(f.id)) else f.assertManual()
            }
        }
    }

    @Test fun permitsStayLocalAreBoundToCurrentRunAndAppAndEndWithTheAttempt() {
        for (direct in listOf(false, true)) {
            val f = Fixture(direct)
            f.fill(); f.decide(f.verification("begin"))
            val work = f.work()
            assertTrue(context(work).getJSONObject("login_verification").getBoolean("active"))
            val command = f.tap(work)
            val permit = command.getString("login_verification_permit")
            assertTrue(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
            assertFalse(f.engine.allowsLoginVerification("other-run", f.pkg, permit))
            assertFalse(f.engine.allowsLoginVerification(f.id, "other.app", permit))
            assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, "invented"))
            assertFalse(f.engine.get(f.id).has(LoginVerification.KEY))
            assertFalse(f.engine.get(f.id).toString().contains(permit))
            assertFalse(f.engine.events(f.id).toString().contains(permit))
            f.result(); f.screen()
            val after = f.work()
            assertFalse(after.payload.toString().contains(permit))
            assertFalse(context(after).getJSONObject("login_verification").has("permit"))
            f.decide(f.verification("passed"), after)
            assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
            f.screen()
            assertEquals("passed", context(f.work()).getJSONObject("login_verification").getString("state"))
        }
    }

    @Test fun threeFailuresRequireManualTakeoverAndRefillOrRestartCannotReplenishAttempts() {
        val f = Fixture(true)
        repeat(3) { attempt ->
            f.fill(if (attempt == 1) "login_phone" else "login_password")
            f.decide(f.verification("begin"))
            val work = f.work()
            assertEquals(attempt + 1, context(work).getJSONObject("login_verification").getInt("attempts"))
            f.decide(f.verification("failed"), work)
            if (attempt < 2) f.screen() else f.assertManual()
        }
        f.restore(); f.assertManual()
        f.engine.control(f.id, "resume", JSONObject()); f.screen()
        f.fill()
        val work = f.work()
        assertEquals(3, context(work).getJSONObject("login_verification").getInt("attempts"))
        assertFalse(context(work).getJSONObject("login_verification").getBoolean("can_begin"))
        f.decide(f.verification("begin"), work); f.assertManual()
    }

    @Test fun unprovenOrDifferentAppCannotBeginAndPauseRevokesInFlightPermission() {
        val unproven = Fixture(true)
        unproven.decide(unproven.verification("begin")); unproven.assertManual()
        val otherApp = Fixture(true)
        otherApp.fill(); otherApp.decide(otherApp.verification("begin", "other.app")); otherApp.assertManual()

        val f = Fixture(true)
        f.fill(); f.decide(f.verification("begin"))
        val old = f.tap()
        val permit = old.getString("login_verification_permit")
        f.engine.control(f.id, "pause", JSONObject())
        assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
        f.restore()
        assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
        f.engine.control(f.id, "resume", JSONObject())
        assertEquals("screenshot", f.command().getString("kind"))
        assertNotEquals(old.getString("id"), f.command().getString("id"))
        f.screen()
        val work = f.work()
        val state = context(work).getJSONObject("login_verification")
        assertEquals(1, state.getInt("attempts")); assertFalse(state.getBoolean("active"))
        assertFalse(work.payload.toString().contains(permit))
        assertFalse(f.engine.get(f.id).toString().contains(permit))
    }

    @Test fun navigationAndObservedAppChangesRevokePermissionEvenAfterReturning() {
        for (direct in listOf(false, true)) for (transition in listOf("launch", "back", "home", "recents", "notifications", "quick_settings", "app_change")) {
            val f = Fixture(direct)
            if (transition == "launch") {
                f.decide(JSONObject().put("kind", "list_apps").put("query", "Fixture"))
                f.result(JSONObject().put("apps", JSONArray().put(JSONObject().put("package_name", "other.app").put("label", "Fixture"))))
            }
            f.fill(); f.decide(f.verification("begin"))
            val permit = f.tap().getString("login_verification_permit")
            f.result()
            if (transition == "app_change") {
                f.screen("other.app")
                assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
                f.decide(JSONObject().put("kind", "wait").put("duration_ms", 100))
            } else {
                f.screen()
                f.decide(JSONObject().put("kind", "execute").put("action", transition).put("target", "离开当前登录挑战")
                    .put("expected", "显示导航后的页面").apply { if (transition == "launch") put("package_name", "other.app") })
                assertEquals(transition, f.command().getString("kind"))
                assertFalse(f.command().has("login_verification_permit"))
                assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
            }
            f.result(); f.screen()
            val work = f.work()
            val state = context(work).getJSONObject("login_verification")
            assertEquals("interrupted", state.getString("state"))
            assertEquals(1, state.getInt("attempts"))
            assertFalse(state.getBoolean("active"))
            assertFalse(f.tap(work).has("login_verification_permit"))
            assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, permit))
        }
    }

    @Test fun successfulProofCannotBeReusedAndFreshFillPreservesTheAttemptBudget() {
        for (direct in listOf(false, true)) {
            val f = Fixture(direct)
            f.fill(); f.decide(f.verification("begin"))
            val oldPermit = f.tap().getString("login_verification_permit")
            f.result(); f.screen(); f.decide(f.verification("passed")); f.screen()
            val passed = f.work()
            val state = context(passed).getJSONObject("login_verification")
            assertEquals("passed", state.getString("state"))
            assertFalse(state.getBoolean("can_begin"))
            f.decide(f.verification("begin"), passed); f.assertManual()
            f.engine.control(f.id, "resume", JSONObject()); f.screen(); f.fill()
            val available = f.work()
            assertTrue(context(available).getJSONObject("login_verification").getBoolean("can_begin"))
            assertEquals(1, context(available).getJSONObject("login_verification").getInt("attempts"))
            f.decide(f.verification("begin"), available)
            val retry = f.work()
            assertEquals(2, context(retry).getJSONObject("login_verification").getInt("attempts"))
            val newPermit = f.tap(retry).getString("login_verification_permit")
            assertNotEquals(oldPermit, newPermit)
            assertFalse(f.engine.allowsLoginVerification(f.id, f.pkg, oldPermit))
            assertTrue(f.engine.allowsLoginVerification(f.id, f.pkg, newPermit))
        }
    }
}
