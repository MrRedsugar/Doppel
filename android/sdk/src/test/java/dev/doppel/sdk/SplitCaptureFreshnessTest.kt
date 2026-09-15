package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitCaptureFreshnessTest {
    private val e = SplitTaskEngine(null, {}, { 1000L })
    private fun start(mode: String = "full"): String = e.create(JSONObject()
        .put("goal", "进入1-7").put("mode", mode).put("device_id", "direct-this-phone")).getString("id").also { screen("lobby") }
    private fun reply(json:String)=SplitTestReply.response(JSONObject(json))
    private fun propose() = e.accept(e.takeWork()!!, reply("""{"kind":"execute","action":"tap","target":"大厅右上方 TERMINAL 终端入口","expected":"终端总览"}"""))
    private fun screen(name: String) {
        val c = e.poll().getJSONObject("command")
        assertEquals("screenshot", c.getString("kind"))
        e.result(JSONObject().put("run_id", c.getString("run_id")).put("command_id", c.getString("id")).put("status", "ok")
            .put("data", JSONObject().put("image_base64", name).put("visual_frame", JSONObject().put("capture_id", name))))
    }
    private fun currentImage(w: SplitTaskEngine.Work): String {
        val messages = w.payload.getJSONArray("messages")
        val content = messages.getJSONObject(messages.length()-1).getJSONArray("content")
        return (0 until content.length()).map {content.getJSONObject(it)}.last {it.optString("type")=="image_url"}
            .getJSONObject("image_url").getString("url")
    }
    @Test fun latePlannerCannotSendLobbyImageToGrounderAndActionUsesNewCapture() {
        start();propose()
        assertNull("B must wait for a new image after A", e.takeWork())
        screen("terminal")
        val b = e.takeWork()!!;assertTrue(b.grounding)
        assertEquals("data:image/png;base64,terminal", currentImage(b))
        assertEquals(2, b.payload.getJSONArray("messages").length())
        e.accept(b, reply("""{"status":"located","action":"tap","points":[[781,166]],"assessment":{"alignment":"consistent","observed":"可见请求目标","reason":"与预期一致"}}"""))
        assertEquals("terminal", e.poll().getJSONObject("command").getJSONObject("source").getString("capture_id"))
    }
    @Test fun grounderRefusalReturnsFreshTerminalImageToA() {
        val id=start();propose();screen("terminal")
        e.accept(e.takeWork()!!,reply("""{"status":"not_found","reason":"已经不是大厅，没有大厅终端入口"}"""))
        val a=e.takeWork()!!;assertFalse(a.grounding)
        assertEquals("data:image/png;base64,terminal",currentImage(a))
        assertEquals("running",e.get(id).getString("status"))
        assertTrue(a.payload.toString().contains("已经不是大厅"))
    }
    @Test fun failedFreshCaptureCannotFallBackToOldImage() {
        start();propose()
        val c=e.poll().getJSONObject("command");assertEquals("screenshot",c.getString("kind"))
        e.result(JSONObject().put("run_id",c.getString("run_id")).put("command_id",c.getString("id")).put("status","error"))
        assertNull("Neither model may receive historical pixels while capture is being retried",e.takeWork())
        assertFalse(e.readyForWork());assertTrue(e.poll().isNull("command"))
    }
    @Test fun cancellationRejectsLateFreshScreenshot() {
        val id=start();propose()
        val c=e.poll().getJSONObject("command");assertEquals("screenshot",c.getString("kind"))
        e.control(id,"cancel",JSONObject())
        val r=e.result(JSONObject().put("run_id",id).put("command_id",c.getString("id")).put("status","ok")
            .put("data",JSONObject().put("image_base64","too-late")))
        assertFalse(r.getBoolean("accepted"));assertNull(e.takeWork());assertTrue(e.poll().isNull("command"))
    }
    @Test fun approvalReobservesAndStillRefreshesBeforeB() {
        val id=start("ask");propose()
        assertTrue(e.poll().isNull("command"))
        val pending=e.get(id).getJSONObject("pending_request")
        e.control(id,"answer",JSONObject().put("request_id",pending.getString("id")).put("approve",true))
        screen("approved-lobby");propose()
        assertNull(e.takeWork());screen("post-approval-terminal")
        assertEquals("data:image/png;base64,post-approval-terminal",currentImage(e.takeWork()!!))
    }

    @Test fun ordinaryPlannerKeepsOnlyOneDistinctPreviousImageAlongsideCurrent() {
        start()
        for(name in listOf("older","previous","current","current")) {
            propose();screen(name)
            e.accept(e.takeWork()!!,reply("""{"status":"not_found","reason":"当前图中没有目标，需要重新规划"}"""))
        }
        val messages=e.takeWork()!!.payload.getJSONArray("messages")
        val images=(0 until messages.length()).flatMap { index ->
            val content=messages.getJSONObject(index).optJSONArray("content") ?: JSONArray()
            (0 until content.length()).map {content.getJSONObject(it)}.filter {it.optString("type")=="image_url"}
                .map {it.getJSONObject("image_url").getString("url")}
        }
        assertEquals(listOf("data:image/png;base64,previous","data:image/png;base64,current"),images)
        assertTrue(messages.toString().contains("需要重新规划"))
    }
}
