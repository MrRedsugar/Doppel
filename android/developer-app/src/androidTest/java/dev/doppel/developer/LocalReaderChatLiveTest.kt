@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.Gateway
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in end-to-end chat with the configured model; no phone task or durable memory is created. */
class LocalReaderChatLiveTest {
    @Test fun configuredModelReadsWebsiteAndSeesScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_chat_live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Gateway(context).isConnected())
        val started = System.nanoTime()
        val result = DirectRuntime.get(context).request("POST", "/conversation/intent", JSONObject()
            .put("message", "请查看 https://example.com/ 的正文，再使用网页截图核对页面顶部英文大标题，只回复标题和来源网址。不要创建手机任务，也不要保存长期记忆。")
            .put("history", JSONArray()).put("device_available", false))
        val report = JSONObject().put("result", result).put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
        File(context.getExternalFilesDir(null), "local-reader-chat-live.json").writeText(report.toString(2))
        assertEquals("conversation", result.getString("intent"))
        assertTrue(result.getString("reply").contains("Example Domain", ignoreCase = true))
        assertTrue(result.getString("reply").contains("example.com"))
        val calls = result.getJSONObject("reference_diagnostics").getJSONArray("calls")
        val operations = (0 until calls.length()).map { calls.getJSONObject(it) }
        assertTrue(operations.any { it.optString("tool") == "read_web" && it.optString("operation") == "read" && !it.optBoolean("error") })
        assertTrue(operations.any { it.optString("tool") == "read_web" && it.optString("operation") == "screenshot" && !it.optBoolean("error") })
        assertFalse(report.toString().contains("base64"))
    }
}
