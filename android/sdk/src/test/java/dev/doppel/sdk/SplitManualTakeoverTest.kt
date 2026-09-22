package dev.doppel.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitManualTakeoverTest {
    @Test fun manualTakeoverSurvivesRestartCannotBeAnsweredAndResumesWithFreshCapture() {
        for (direct in listOf(false, true)) for (reason in listOf(null, "login")) {
            var saved = ""
            var engine = SplitTaskEngine(null, { saved = it }, { 1000L }, enhancementEnabled = { !direct })
            val id = engine.create(JSONObject().put("goal", "完成需要用户处理的手机操作")
                .put("device_id", "direct-this-phone").put("mode", "full")).getString("id")
            val capture = engine.poll().getJSONObject("command")
            engine.result(JSONObject().put("run_id", id).put("command_id", capture.getString("id")).put("status", "ok")
                .put("observation", JSONObject().put("screen_id", "screen").put("package_name", "dev.fixture"))
                .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject()
                    .put("capture_id", "frame").put("display_width", 1080).put("display_height", 1920))))
            engine.accept(engine.takeWork()!!, SplitTestReply.response(JSONObject().put("kind", "manual_takeover")
                .put("message", "请在手机上完成当前操作，然后点击继续").put("reason", reason ?: JSONObject.NULL)))
            val requestId = engine.get(id).getJSONObject("pending_request").getString("id")
            assertEquals("paused", engine.statusOrNull(id))
            assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))

            engine.control(id, "pause", JSONObject())
            engine = SplitTaskEngine(saved, { saved = it }, { 2000L }, enhancementEnabled = { !direct })
            val pending = engine.get(id).getJSONObject("pending_request")
            assertEquals(requestId, pending.getString("id"))
            assertTrue(pending.getBoolean("manual_only"))
            assertEquals(reason, pending.opt("reason"))
            assertEquals(reason == "login", PausePresentation.from(engine.get(id))!!.showLoginSettings)
            if (reason == "login") assertEquals("dev.fixture", pending.getString("package_name"))
            assertThrows(IllegalArgumentException::class.java) {
                engine.control(id, "answer", JSONObject().put("request_id", requestId).put("text", "继续"))
            }
            assertEquals("paused", engine.statusOrNull(id)); assertNull(engine.takeWork())
            engine.control(id, "resume", JSONObject())
            assertEquals("running", engine.statusOrNull(id))
            assertFalse(engine.get(id).has("pending_request"))
            val next = engine.poll().getJSONObject("command")
            assertEquals("screenshot", next.getString("kind"))
            assertNotEquals(capture.getString("id"), next.getString("id"))
            assertNull("No planning on the pre-takeover screenshot", engine.takeWork())
        }
    }

    @Test fun nativeLoginTakeoverReasonSurvivesEachResultRouteAndProcessRecovery() {
        for (direct in listOf(false, true)) for (route in listOf("screenshot", "list_apps", "read_notifications", "login_password")) {
            var saved = ""
            var engine = SplitTaskEngine(null, { saved = it }, { 1000L }, enhancementEnabled = { !direct })
            val id = engine.create(JSONObject().put("goal", "原生登录中断测试").put("device_id", "direct-this-phone")
                .put("mode", "full")).getString("id")
            if (route != "screenshot") {
                val capture = engine.poll().getJSONObject("command")
                engine.result(JSONObject().put("run_id", id).put("command_id", capture.getString("id")).put("status", "ok")
                    .put("observation", JSONObject().put("screen_id", "screen").put("package_name", "dev.fixture"))
                    .put("data", JSONObject().put("image_base64", "cGl4ZWxz").put("visual_frame", JSONObject().put("capture_id", "frame"))))
                val decision = when (route) {
                    "list_apps" -> JSONObject().put("kind", route).put("query", "fixture")
                    "read_notifications" -> JSONObject().put("kind", route).put("package_name", "")
                    else -> JSONObject().put("kind", "execute").put("action", route).put("package_name", "dev.fixture")
                        .put("credential_label", "测试账户").put("target", "当前密码框").put("expected", "完成本机填写")
                }
                engine.accept(engine.takeWork()!!, SplitTestReply.response(decision))
            }
            val command = engine.poll().getJSONObject("command")
            assertEquals(route, command.getString("kind"))
            engine.result(JSONObject().put("run_id", id).put("command_id", command.getString("id")).put("status", "blocked")
                .put("message", "需要你处理当前步骤")
                .put("observation", JSONObject().put("package_name", "dev.fixture"))
                .put("data", JSONObject().put("human_takeover", "login")))
            val request = engine.get(id).getJSONObject("pending_request")
            assertTrue(request.getBoolean("manual_only")); assertEquals("login", request.getString("reason"))
            assertEquals("dev.fixture", request.getString("package_name"))
            assertNull(engine.takeWork()); assertTrue(engine.poll().isNull("command"))
            engine = SplitTaskEngine(saved, { saved = it }, { 2000L })
            assertEquals(request.toString(), engine.get(id).getJSONObject("pending_request").toString())
            assertTrue(PausePresentation.from(engine.get(id))!!.showLoginSettings)
        }
    }
}
