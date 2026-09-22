@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.ConversationIntent
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.ModelApi
import dev.doppel.sdk.ModelProviders
import dev.doppel.sdk.ModelVision
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in: three real primary calls with synthetic input; only normal usage accounting and a QA report are written. */
class ChatMemorySemanticLiveTest {
    @Test fun primaryDistinguishesReviewCorrectionAndImmediateExecution() {
        assumeTrue("Opt in with -e chat_memory_live true",
            InstrumentationRegistry.getArguments().getString("chat_memory_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Use the existing configured developer app", "dev.doppel.developer", context.packageName)
        assertTrue("Requires the existing accepted consent", FirstUseConsent.isAccepted(context))
        val providers = ModelProviders(context)
        val selected = providers.resolve("primary")
        assertEquals("Requires an already verified primary model", ModelVision.VERIFIED, selected.vision)
        assertTrue("Requires existing primary credentials", providers.hasCredentials(selected.provider.id))

        val packageName = "cn.wps.moffice_eng"
        val visible = JSONObject().put("items", JSONArray().put(JSONObject()
            .put("id", "semantic-fixture-memory").put("revision", 2).put("scope", "global")
            .put("content", "解释问题时先给出结论，再补充简短原因。")))
        val evidence = JSONObject().put("run_id", "semantic-fixture-run")
            .put("goal", "在 WPS 创建表格并保存为练习清单").put("status", "failed").put("package_name", packageName)
            .put("timeline", JSONArray()
                .put(JSONObject().put("message", "已创建空白表格并打开保存对话框。"))
                .put(JSONObject().put("message", "点击保存时文件名输入框仍为空。"))
                .put(JSONObject().put("message", "应用提示文件名不能为空，任务停止，文件未保存。")))
            .put("task_state", JSONObject().put("phase", "failed").put("remaining_steps", JSONArray().put("填写文件名并保存")).toString())
        val history = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "帮我在 WPS 新建表格，保存为练习清单。"))
            .put(JSONObject().put("role", "assistant").put("content", "任务未完成。记录显示保存时文件名为空，应用拒绝保存。"))
        val scenarios = listOf(
            "review_question" to "刚才为什么没有保存成功？先根据记录解释原因。",
            "natural_correction" to "刚才做法不对。以后在 WPS 保存表格，先确认文件名已填写，再点保存；看到保存成功的提示后再退出。",
            "execute_now" to "现在执行：打开 WPS，新建一个空白表格并停在编辑页。"
        )
        assertFalse("The correction must not rely on a remember keyword", scenarios[1].second.contains("记住"))
        val cases = JSONArray()
        val report = JSONObject().put("model", selected.selection.model).put("passed", false).put("cases", cases)
            .put("input_source", "synthetic task evidence, synthetic history and fixed synthetic memory")
            .put("model_requests_attempted", 0).put("model_connections", 0)
        val model = ModelApi(context)
        val started = SystemClock.elapsedRealtime()
        try {
            for ((name, message) in scenarios) {
                val result = JSONObject().put("case", name).put("input", message).put("passed", false)
                cases.put(result)
                val caseStarted = SystemClock.elapsedRealtime()
                var stage = "prepare"
                try {
                    val messages = JSONArray(history.toString()).put(JSONObject().put("role", "user").put("content", message))
                    val prompt = ConversationIntent.request(messages)
                    prompt.getJSONObject(0).put("content", ConversationIntent.SYSTEM_PROMPT + ConversationIntent.CHAT_MEMORY_PROMPT)
                    val input = JSONObject(prompt.getJSONObject(1).getString("content"))
                        .put("task_evidence", evidence).put("long_term_memory", visible)
                    prompt.getJSONObject(1).put("content", input.toString())
                    stage = "model_call"
                    report.put("model_requests_attempted", report.getInt("model_requests_attempted") + 1)
                    val response = model.complete(JSONObject().put("_doppel_role", "primary").put("stream", false)
                        .put("max_completion_tokens", 1400).put("messages", prompt)) {
                        report.put("model_connections", report.getInt("model_connections") + 1)
                    }
                    result.put("model", response.optString("model").ifBlank { selected.selection.model })
                    val usage = response.optJSONObject("usage")
                    result.put("input_tokens", usage?.opt("prompt_tokens") ?: usage?.opt("input_tokens") ?: JSONObject.NULL)
                        .put("output_tokens", usage?.opt("completion_tokens") ?: usage?.opt("output_tokens") ?: JSONObject.NULL)
                    stage = "parse"
                    val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    val raw = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                    // Export only the expected answer fields, never raw transport metadata or provider errors.
                    val answer = JSONObject()
                    for (key in listOf("intent", "confidence", "task_goal", "title", "question", "reply")) {
                        if (raw.has(key)) answer.put(key, raw.get(key))
                    }
                    val changes = raw.getJSONArray("memory_changes")
                    answer.put("memory_changes", JSONArray((0 until changes.length()).map { index ->
                        val change = changes.getJSONObject(index)
                        JSONObject().apply {
                            for (key in listOf("op", "id", "expected_revision", "content", "scope", "package_name")) {
                                if (change.has(key)) put(key, change.get(key))
                            }
                        }
                    }))
                    result.put("structured_reply", answer)
                    stage = "assert_semantics"
                    val decision = ConversationIntent.parse(content)
                    if (name == "execute_now") {
                        assertEquals("An explicit immediate operation must route to task", ConversationIntent.Kind.TASK, decision.kind)
                        assertTrue("The explicit task must be confident enough to execute", decision.canStartTask())
                        assertEquals("A one-off task must not save memory", 0, changes.length())
                    } else {
                        assertEquals("Review and correction must stay in ordinary chat", ConversationIntent.Kind.CONVERSATION, decision.kind)
                        assertTrue("Ordinary chat must answer the user", raw.optString("reply").isNotBlank())
                        if (name == "review_question") assertEquals("A review question must not create memory", 0, changes.length())
                        else {
                            assertTrue("A reusable correction must produce only upserts", changes.length() in 1..4 && (0 until changes.length()).all {
                                changes.getJSONObject(it).optString("op") == "upsert" && changes.getJSONObject(it).optString("content").isNotBlank()
                            })
                            ConversationIntent.memoryChanges(raw, decision, visible, packageName)
                        }
                    }
                    result.put("passed", true)
                } catch (failure: Throwable) {
                    result.put("failure_stage", stage).put("failure_type", failure.javaClass.simpleName)
                } finally {
                    result.put("elapsed_ms", SystemClock.elapsedRealtime() - caseStarted)
                }
            }
            report.put("passed", report.getInt("model_requests_attempted") == 3 && report.getInt("model_connections") == 3 &&
                (0 until cases.length()).all { cases.getJSONObject(it).optBoolean("passed") })
        } finally {
            report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(context.filesDir, "chat-memory-semantic-live.json").writeText(report.toString(2), Charsets.UTF_8)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CHAT_MEMORY_SEMANTIC_LIVE $report\n") })
        }
        assertTrue("See chat-memory-semantic-live.json for the three sanitized semantic results", report.getBoolean("passed"))
    }
}
