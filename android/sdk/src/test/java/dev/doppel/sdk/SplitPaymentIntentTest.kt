package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitPaymentIntentTest {
    private class Fixture(val direct: Boolean, mode: String = "full", val authorized: Boolean = true) {
        val engine = SplitTaskEngine(null, {}, { 1000L }, enhancementEnabled = { !direct })
        val id = engine.create(JSONObject().put("goal", "从历史订单再次购买餐品")
            .put("device_id", "direct-this-phone").put("mode", mode)).getString("id")
        init { screenshot() }
        fun screenshot() {
            val command = engine.poll().getJSONObject("command")
            assertEquals("screenshot", command.getString("kind"))
            engine.result(JSONObject().put("command_id", command.getString("id")).put("run_id", id).put("status", "ok")
                .put("observation", JSONObject().put("screen_id", "screen").put("package_name", "dev.shop")
                    .apply { if (authorized) put("payment_consent_id", "local-grant") })
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                    .put("capture_id", "frame").put("display_width", 1080).put("display_height", 1920).put("rotation", 0))))
        }
        fun decision(action: String) = JSONObject().put("kind", "execute").put("action", action)
            .put("target", if (action == "pay") "确认当前订单付款按钮" else "待付款等状态图标右侧的全部订单入口")
            .put("expected", if (action == "pay") "提交本订单付款并显示结果" else "进入历史订单列表")
            .apply { if (direct) put("points", JSONArray("[[908,429]]")).put("duration_ms", 60) }
        fun accept(action: String) = engine.accept(engine.takeWork()!!, SplitTestReply.response(decision(action)))
        fun ground(): SplitTaskEngine.Work {
            screenshot()
            return engine.takeWork()!!.also { assertTrue(it.grounding) }
        }
        fun located(work: SplitTaskEngine.Work) = engine.accept(work, SplitTestReply.response(JSONObject()
            .put("status", "located").put("action", "tap").put("points", JSONArray("[[908,429]]"))
            .put("assessment", JSONObject().put("alignment", "consistent"))))
        fun finishGrounding() { if (!direct) located(ground()) }
    }

    @Test fun ordinaryNavigationDoesNotGainPaymentAuthorityFromTargetWordsOrAnEnabledSwitch() {
        for (direct in listOf(false, true)) for (authorized in listOf(false, true)) {
            val fixture = Fixture(direct, "assist", authorized)
            fixture.accept("tap"); fixture.finishGrounding()
            val command = fixture.engine.poll().getJSONObject("command")
            assertEquals("tap", command.getJSONObject("semantic_intent").getString("action"))
            assertEquals("tap", command.getJSONObject("action").getString("action"))
            assertFalse(command.has("payment_consent_id"))
            assertFalse(command.has("safety"))
            assertEquals("assist", command.getString("mode"))
            assertEquals("running", fixture.engine.statusOrNull(fixture.id))
        }
    }

    @Test fun actualPayRetainsPlannerMeaningButGrounderGetsOnlyTapGeometry() {
        for (direct in listOf(false, true)) {
            val fixture = Fixture(direct)
            fixture.accept("pay")
            if (!direct) {
                val work = fixture.ground()
                val instruction = JSONObject(work.payload.getJSONArray("messages").getJSONObject(1)
                    .getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals(setOf("action", "target", "expected"), instruction.keys().asSequence().toSet())
                assertEquals("tap", instruction.getString("action"))
                assertFalse(work.payload.toString().contains("payment_consent_id"))
                assertFalse(work.payload.toString().contains("payment_allowed"))
                fixture.located(work)
            }
            val command = fixture.engine.poll().getJSONObject("command")
            assertEquals("pay", command.getJSONObject("semantic_intent").getString("action"))
            assertEquals("tap", command.getJSONObject("action").getString("action"))
            assertEquals("full", command.getString("mode"))
            assertEquals("local-grant", command.getString("payment_consent_id"))
        }
    }

    @Test fun payWithoutFullAndLocalConsentPausesBeforeGroundingOrOrdinaryApproval() {
        for (direct in listOf(false, true)) for (mode in listOf("ask", "assist", "full")) for (authorized in listOf(false, true)) {
            if (mode == "full" && authorized) continue
            val fixture = Fixture(direct, mode, authorized)
            fixture.accept("pay")
            assertEquals("paused", fixture.engine.statusOrNull(fixture.id))
            val pending = fixture.engine.get(fixture.id).getJSONObject("pending_request")
            assertEquals("payment", pending.getString("reason"))
            assertEquals("input", pending.getString("kind"))
            assertTrue(pending.getBoolean("manual_only"))
            assertTrue(fixture.engine.poll().isNull("command"))
            assertNull(fixture.engine.takeWork())
        }
    }

    @Test fun plannerOnlyReceivesOnePaymentBooleanAndAbDoesNotReceiveCoordinateGenerationRules() {
        for (direct in listOf(false, true)) for (authorized in listOf(false, true)) {
            val fixture = Fixture(direct, authorized = authorized)
            val work = fixture.engine.takeWork()!!
            val messages = work.payload.getJSONArray("messages")
            val context = JSONObject(messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                .getJSONObject(0).getString("text"))
            assertEquals(authorized, context.getBoolean("payment_allowed"))
            assertFalse(context.has("payment_policy"))
            assertFalse(context.toString().contains("local-grant"))
            val prompt = messages.getJSONObject(0).getString("content")
            assertEquals(direct, prompt.contains(SwipeDirectionPrompt.COORDINATES))
            assertTrue(prompt.contains("不能用tap代替pay"))
            assertFalse(prompt.contains("safety="))
        }
    }

    @Test fun paySchemaIsPrimaryOnlyAndDoesNotChangeOrdinaryActions() {
        for (direct in listOf(false, true)) {
            val decision = JSONObject().put("kind", "pay").put("target", "付款按钮")
                .put("expected", "提交付款").put("screen_context", "")
                .apply { if (direct) put("points", JSONArray("[[500,600]]")).put("duration_ms", 60) }
            val envelope = JSONObject().put("decision", decision).put("state", JSONObject.NULL)
            val schema = SplitOutputSchema.format("primary", direct)
            SplitOutputSchema.validate(envelope, schema)
            assertEquals("pay", SplitOutputSchema.unwrap(envelope, "primary").getString("action"))
            for (extra in listOf("safety", "payment_consent_id", "request_login_code")) {
                decision.put(extra, "invalid")
                assertThrows(SplitSchemaViolation::class.java) { SplitOutputSchema.validate(envelope, schema) }
                decision.remove(extra)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SplitOutputSchema.format("grounding", expectedAction = "pay") }
    }
}
