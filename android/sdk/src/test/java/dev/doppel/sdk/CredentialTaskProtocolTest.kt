package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CredentialTaskProtocolTest {
    private fun screen(engine: SplitTaskEngine) {
        val command = engine.poll().getJSONObject("command")
        assertEquals("screenshot", command.getString("kind"))
        engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
            .put("status", "ok").put("observation", JSONObject().put("screen_id", "login-screen").put("package_name", "dev.fixture.login")
                .put("login_credentials", JSONArray().put(JSONObject().put("package_name", "dev.fixture.login").put("credential_label", "Fixture account"))))
            .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("mime_type", "image/png")
                .put("visual_frame", JSONObject().put("display_width", 1440).put("display_height", 3200).put("rotation", 0))))
    }

    private fun request(kind: String = "login_password") = JSONObject().put("kind", "execute").put("action", kind)
        .put("target", "已获得焦点的登录密码框").put("expected", "密码框显示已填入")
        .put("package_name", "dev.fixture.login").put("credential_label", "Fixture account")

    @Test fun bothVisualModesDispatchVaultFieldsLocallyAndContinueTheNormalObservationLoop() {
        for (enhanced in listOf(false, true)) for (kind in listOf("login_username", "login_password")) {
            val engine = SplitTaskEngine(null, {}, { 1000L }, enhancementEnabled = { enhanced })
            val id = engine.create(JSONObject().put("device_id", "direct-this-phone").put("goal", "登录已有账号").put("mode", "assist")).getString("id")
            screen(engine)
            val work = engine.takeWork()!!
            assertFalse(work.grounding)
            assertTrue(work.payload.toString().contains("Fixture account"))
            engine.accept(work, SplitTestReply.response(request(kind)))
            val command = engine.poll().getJSONObject("command")
            assertEquals(kind, command.getString("kind"))
            assertEquals("login-screen", command.getString("screen_id"))
            assertEquals("dev.fixture.login", command.getString("package_name"))
            assertEquals("Fixture account", command.getString("credential_label"))
            assertFalse(command.has("text")); assertFalse(command.has("points"))
            assertNull("No B request is needed for local native password input", engine.takeWork())
            assertEquals(1, engine.get(id).getInt("calls"))
            engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", id).put("status", "ok")
                .put("message", "本机填写已接受").put("data", JSONObject().put("action_state", "accepted")))
            assertEquals("screenshot", engine.poll().getJSONObject("command").getString("kind"))
            assertEquals("running", engine.get(id).getString("status"))
        }
    }

    @Test fun askModeStillRequiresApprovalAndStrictSchemaCannotCarryAPassword() {
        val engine = SplitTaskEngine(null, {}, { 1000L })
        val id = engine.create(JSONObject().put("device_id", "direct-this-phone").put("goal", "登录已有账号").put("mode", "ask")).getString("id")
        screen(engine)
        engine.accept(engine.takeWork()!!, SplitTestReply.response(request()))
        val pending = engine.get(id).getJSONObject("pending_request")
        assertEquals("approval", pending.getString("kind"))
        assertEquals("awaiting_approval", engine.get(id).getString("status"))
        assertTrue(engine.poll().isNull("command"))
        engine.control(id, "answer", JSONObject().put("request_id", pending.getString("id")).put("approve", true))
        screen(engine)
        engine.accept(engine.takeWork()!!, SplitTestReply.response(request()))
        assertEquals("login_password", engine.poll().getJSONObject("command").getString("kind"))
        for (direct in listOf(false, true)) for (kind in listOf("login_username", "login_password")) {
            val body = request(kind).apply { put("kind", kind); remove("action"); put("screen_context", ""); put("text", "must-not-be-carried") }
            assertThrows(SplitSchemaViolation::class.java) {
                SplitOutputSchema.validate(JSONObject().put("decision", body).put("state", JSONObject.NULL), SplitOutputSchema.format("primary", direct))
            }
        }
    }

    @Test fun passwordPolicyRequiresFreshEnabledTargetAndNeverPermitsPaymentCredentials() {
        val target = Target("登录密码", true, true)
        assertEquals("ok", Policy.validate("login_password", "s", "s", target))
        assertEquals("stale", Policy.validate("login_password", "old", "s", target))
        assertEquals("stale", Policy.validate("login_password", "s", "s", null))
        assertEquals("blocked", Policy.validate("login_password", "s", "s", target.copy(enabled = false)))
        assertEquals("blocked", Policy.validate("login_password", "s", "s", target.copy(label = "支付密码")))
        assertEquals("blocked", Policy.validate("type", "s", "s", target))
    }
}
