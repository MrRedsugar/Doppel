@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Paid real UI entry + actual provider/worker. No fixture observations or fabricated actions. */
class ContinuousConversationLiveTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(1) }
    private val gateway get() = Gateway(context)
    private fun shell(command: String) = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun waitUntil(ms: Long, test: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+ms
        while(!test() && SystemClock.elapsedRealtime()<end) Thread.sleep(100)
        assertTrue("Bounded live condition was not reached",test())
    }
    @Test fun realConversationAndVisualEntry() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("conversation_live")=="true")
        assertTrue(gateway.isDirectMode()); assertTrue(FirstUseConsent.isAccepted(context))
        assertFalse("Existing user run must be preserved",DirectRuntime.get(context).hasUnfinishedRun())
        val folder=File(context.filesDir,"continuous-live").apply { mkdirs() }
        val report=JSONObject().put("started_at",System.currentTimeMillis()).put("runs",JSONArray())
        val own="${context.packageName}/dev.doppel.sdk.DoppelAccessibilityService"
        val before=shell("settings get secure enabled_accessibility_services")
        val others=before.split(':').filter { it.isNotBlank() && it!="null" && it!=own }
        val baseline=gateway.request("GET","/runs").getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        var activity: Activity?=null
        try {
            shell("settings put secure enabled_accessibility_services '${others.joinToString(":")}'")
            Thread.sleep(600)
            shell("settings put secure enabled_accessibility_services '${(others+own).joinToString(":")}'")
            waitUntil(8000) { DoppelAccessibilityService.instance!=null }
            val goals=if(args.getString("case")=="game") listOf("打开明日方舟，进到游戏首页，看看现在有多少理智")
                else listOf("打开设置，告诉我手机型号", "刚才手机是什么型号？再打开时钟")
            var previous: String?=null
            for((index,goal) in goals.withIndex()) {
                activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                inst.waitForIdleSync()
                inst.runOnMainSync {
                    val views=all(activity!!.window.decorView)
                    views.filterIsInstance<EditText>().first { it.isShown && it.contentDescription=="任务输入" }.setText(goal)
                    assertTrue(views.first { it.isShown && it.contentDescription=="开始任务" }.performClick())
                }
                var run: JSONObject?=null
                waitUntil(10000) {
                    val a=gateway.request("GET","/runs").getJSONArray("items")
                    run=(0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") !in baseline && it.optString("goal")==goal }
                    run!=null
                }
                val id=run!!.getString("id"); val start=SystemClock.elapsedRealtime()
                while(SystemClock.elapsedRealtime()-start<90000) {
                    run=gateway.request("GET","/runs/$id")
                    File(folder,"latest.json").writeText(run.toString())
                    if(run!!.optString("status")!="running" || run!!.optInt("calls")>=25) break
                    Thread.sleep(200)
                }
                run!!.put("events",gateway.request("GET","/runs/$id/events").getJSONArray("items"))
                report.getJSONArray("runs").put(run)
                File(folder,"report.json").writeText(report.toString(2))
                automation.takeScreenshot()?.let { b -> File(folder,"case-$index.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
                assertEquals("Actual task did not complete: ${run!!.optString("message")}","completed",run!!.optString("status"))
                if(previous!=null) assertEquals(previous,run!!.optString("parent_run_id"))
                previous=id
            }
            activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Thread.sleep(3500)
            automation.takeScreenshot()?.let { b -> File(folder,"conversation.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
            if(goals.size>1) {
                inst.runOnMainSync {
                    assertTrue(all(activity!!.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString()==goals.first() })
                }
            }
            report.put("passed",true)
        } finally {
            val a=gateway.request("GET","/runs").getJSONArray("items")
            for(i in 0 until a.length()) {
                val run=a.getJSONObject(i)
                if(run.getString("id") !in baseline && !TaskPresentation.terminal(run.optString("status")))
                    gateway.request("POST","/runs/${run.getString("id")}/cancel")
            }
            inst.runOnMainSync { activity?.finish(); context.stopService(Intent(context,DeviceWorkerService::class.java)) }
            report.put("finished_at",System.currentTimeMillis()); File(folder,"report.json").writeText(report.toString(2))
            shell("settings put secure enabled_accessibility_services '$before'")
        }
    }
}
