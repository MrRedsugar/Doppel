package dev.doppel.developer

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Executor/capture regression on installed OpenCalc; never a substitute for autonomous acceptance. */
class SplitActionTimingDeviceTest {
    @Test fun completedTapWaitsBeforeFreshScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("action_timing")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val changedPixels=InstrumentationRegistry.getArguments().getString("changed_pixels")=="true"
        val report=JSONObject().put("test_type","production executor regression; not autonomous model acceptance")
            .put("source_pixel_fault_injection",changedPixels)
        val rows=JSONArray();report.put("actions",rows)
        val folder=File(context.filesDir,"action-timing-evidence").apply {mkdirs()}
        fun save()=File(folder,"report.json").writeText(report.toString(2))
        try {
            val existing=Gateway(context).request("GET","/runs").getJSONArray("items")
            check((0 until existing.length()).none {existing.getJSONObject(it).optString("status")=="running"}) {"Preserve running user task"}
            check(FirstUseConsent.accept(context));FirstUseConsent.finishGuide(context)
            val automation=ins.getUiAutomation(1)
            fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()}
            val own=ComponentName(context,DoppelAccessibilityService::class.java)
            val services=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty().split(':')
                .filter {it.isNotBlank() && it!="null" && ComponentName.unflattenFromString(it)!=own}
            if(services.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            val detached=SystemClock.elapsedRealtime()+2000
            while(DoppelAccessibilityService.instance!=null && SystemClock.elapsedRealtime()<detached) Thread.sleep(40)
            shell("settings put secure enabled_accessibility_services ${(services+own.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            val deadline=SystemClock.elapsedRealtime()+8000
            while(DoppelAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
            val service=requireNotNull(DoppelAccessibilityService.instance)
            if(Build.VERSION.SDK_INT<30 && !LegacyScreenCaptureService.isReady) LegacyCaptureConsent.authorize(ins)
            val pkg="com.darkempire78.opencalculator"
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1500)
            fun command(kind:String)=JSONObject().put("id",UUID.randomUUID().toString()).put("run_id","qa-action-timing")
                .put("kind",kind).put("split_agent",true).put("mode","full")
            var previousCaptureAt = 0L
            fun screenshot(name:String):JSONObject {
                // This fixture captures twice between gestures; respect Android's screenshot rate limit.
                val spacing = 350L - (SystemClock.elapsedRealtime() - previousCaptureAt)
                if (spacing > 0) Thread.sleep(spacing)
                val result=service.execute(command("screenshot"))
                previousCaptureAt = SystemClock.elapsedRealtime()
                assertEquals(result.toString(),"ok",result.optString("status"))
                val data=result.getJSONObject("data")
                File(folder,"$name.png").writeBytes(Base64.decode(data.getString("image_base64"),Base64.DEFAULT))
                return result
            }
            val cases=listOf("tap" to listOf("7"),"double_tap" to listOf("8"),
                "double_tap" to listOf("1","2"),"long_press" to listOf("9"))
            for((index,case) in cases.withIndex()) {
                val (kind,digits)=case
                val before=screenshot("before-$index")
                assertEquals(pkg,before.getJSONObject("observation").getString("package_name"))
                val nodes=before.getJSONObject("observation").getJSONArray("nodes")
                val frame=before.getJSONObject("data").getJSONObject("visual_frame")
                val points=JSONArray()
                for(digit in digits) {
                    val target=(0 until nodes.length()).map {nodes.getJSONObject(it)}.first {it.optString("text")==digit && it.optBoolean("clickable")}
                    val bounds=target.getJSONArray("bounds")
                    val x=(bounds.getDouble(0)+bounds.getDouble(2))/2/frame.getDouble("display_width")*1000
                    val y=(bounds.getDouble(1)+bounds.getDouble(3))/2/frame.getDouble("display_height")*1000
                    points.put(JSONArray(listOf(x,y)))
                }
                val action=JSONObject().put("status","located").put("action",kind).put("target","计算器数字 ${digits.joinToString()}")
                    .put("points",points)
                if(kind=="long_press") action.put("duration_ms",600)
                if(changedPixels) invalidateOnlyPixelEvidence(service,frame.getString("capture_id"))
                val receipt=service.execute(command("split_action").put("action",action).put("source",frame))
                val row=JSONObject().put("action",kind).put("digits",JSONArray(digits)).put("receipt",receipt);rows.put(row);save()
                assertEquals(receipt.toString(),"ok",receipt.optString("status"))
                val after=screenshot("after-$index")
                val data=receipt.getJSONObject("data")
                val gap=after.getJSONObject("data").getJSONObject("visual_frame").getLong("captured_at_elapsed_ms")-data.getLong("action_completed_at_elapsed_ms")
                row.put("capture_after_completion_ms",gap).put("after_frame",after.getJSONObject("data").getJSONObject("visual_frame"));save()
                assertTrue("Screenshot must follow 500ms settling",gap>=500)
                assertTrue(data.getLong("post_action_delay_ms")>=500)
                val verification=data.getJSONObject("visual_verification")
                assertFalse(verification.getBoolean("performed"))
                assertFalse(verification.has("matches"))
                assertFalse(verification.has("verification_capture_id"))
                assertEquals("action_pixel_check_disabled",verification.getString("reason"))
                assertEquals("accepted",data.getString("action_state"))
                assertEquals(if(kind=="double_tap") 2 else 1,data.getInt("completed_strokes"))
            }
            // Coordinate-space and source identity checks still protect against an unrelated screen.
            val frame=screenshot("before-invalid-source").getJSONObject("data").getJSONObject("visual_frame")
            val rotated=JSONObject(frame.toString()).put("rotation",(frame.getInt("rotation")+1)%4)
            val invalid=service.execute(command("split_action").put("source",rotated).put("action",
                JSONObject("""{"status":"located","action":"tap","target":"invalid source regression","points":[[500,500]]}""")))
            report.put("rotation_mismatch",invalid);save()
            assertEquals("stale",invalid.getString("status"))
            assertEquals("not_dispatched",invalid.getJSONObject("data").getString("action_state"))
            assertEquals("source_geometry_or_app_changed",invalid.getJSONObject("data").getString("reason_code"))
            report.put("passed",true)
        } catch(failure:Throwable) {
            report.put("passed",false).put("failure",failure.toString().take(1500));throw failure
        } finally {save()}
    }

    /** Test-only fault injection: same source identity/geometry, changed pixel buffer. No app UI is fabricated. */
    private fun invalidateOnlyPixelEvidence(service:DoppelAccessibilityService,id:String) {
        fun field(instance:Any,name:String):Any=instance.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(instance)!!
        val store=field(service,"visualCaptures")
        val captures=field(store,"captures") as Map<*,*>
        val capture=requireNotNull(captures[id])
        val pixels=field(field(capture,"pixels"),"pixels") as IntArray
        assertTrue(pixels.isNotEmpty())
        pixels.fill(0xff112233.toInt())
    }
}
