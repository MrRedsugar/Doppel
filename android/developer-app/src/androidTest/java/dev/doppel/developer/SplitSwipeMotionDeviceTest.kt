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

/** Replays recorded B paths on the installed game; executor regression, not autonomous task acceptance. */
class SplitSwipeMotionDeviceTest {
    @Test fun animatedGameAllowsSwipeAndSequence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("swipe_motion")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val folder=File(context.filesDir,"swipe-motion-evidence").apply {mkdirs()}
        val rows=JSONArray()
        val report=JSONObject().put("test_type","production executor replay on installed Arknights; not autonomous completion").put("actions",rows)
        fun save()=File(folder,"report.json").writeText(report.toString(2))
        try {
            val automation=ins.getUiAutomation(1)
            fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(s)).use {it.readBytes()}
            val own=ComponentName(context,DoppelAccessibilityService::class.java)
            val services=Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty().split(':')
                .filter {it.isNotBlank() && it!="null" && ComponentName.unflattenFromString(it)!=own}
            if(services.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            val detached=SystemClock.elapsedRealtime()+2000
            while(DoppelAccessibilityService.instance!=null && SystemClock.elapsedRealtime()<detached) Thread.sleep(40)
            shell("settings put secure enabled_accessibility_services ${(services+own.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            val bound=SystemClock.elapsedRealtime()+8000
            while(DoppelAccessibilityService.instance==null && SystemClock.elapsedRealtime()<bound) Thread.sleep(50)
            val service=requireNotNull(DoppelAccessibilityService.instance)
            if(Build.VERSION.SDK_INT<30 && !LegacyScreenCaptureService.isReady) LegacyCaptureConsent.authorize(ins)
            val pkg="com.hypergryph.arknights.bilibili"
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1500)
            fun command(kind:String)=JSONObject().put("id",UUID.randomUUID().toString()).put("run_id","qa-swipe-motion")
                .put("kind",kind).put("split_agent",true).put("mode","full")
            fun screenshot(name:String):JSONObject {
                val result=service.execute(command("screenshot"))
                assertEquals(result.toString(),"ok",result.optString("status"))
                File(folder,"$name.png").writeBytes(Base64.decode(result.getJSONObject("data").getString("image_base64"),Base64.DEFAULT))
                assertEquals(pkg,result.getJSONObject("observation").getString("package_name"))
                return result
            }
            for(kind in listOf("swipe","swipe_sequence")) {
                val before=screenshot("$kind-before")
                Thread.sleep(750)
                val moving=screenshot("$kind-moving")
                assertNotEquals("Real background must have changed between frames",
                    before.getJSONObject("data").getJSONObject("visual_frame").getString("sha256"),
                    moving.getJSONObject("data").getJSONObject("visual_frame").getString("sha256"))
                val action=if(kind=="swipe") JSONObject("""{"status":"located","action":"swipe","points":[[700,593],[400,593],[100,593]],"duration_ms":1500}""")
                    else JSONObject("""{"status":"located","action":"swipe_sequence","strokes":[{"points":[[700,593],[100,593]],"duration_ms":800},{"points":[[100,593],[700,593]],"duration_ms":800}],"interval_ms":100}""")
                action.put("target","回放章节选择页面已记录的横向滑动路径")
                val receipt=service.execute(command("split_action").put("source",before.getJSONObject("data").getJSONObject("visual_frame")).put("action",action))
                val row=JSONObject().put("action",kind).put("receipt",receipt);rows.put(row);save()
                assertEquals(receipt.toString(),"ok",receipt.optString("status"))
                val data=receipt.getJSONObject("data")
                assertEquals("accepted",data.getString("action_state"))
                assertEquals(if(kind=="swipe") 1 else 2,data.getInt("completed_strokes"))
                val verification=data.getJSONObject("visual_verification")
                assertFalse(verification.getBoolean("performed"))
                assertFalse(verification.has("verification_capture_id"))
                assertEquals("action_pixel_check_disabled",verification.getString("reason"))
                assertTrue(data.getLong("post_action_delay_ms")>=500)
                val after=screenshot("$kind-after")
                row.put("capture_after_completion_ms",after.getJSONObject("data").getJSONObject("visual_frame").getLong("captured_at_elapsed_ms")-data.getLong("action_completed_at_elapsed_ms"));save()
            }
            report.put("passed",true)
        } catch(failure:Throwable) {
            report.put("passed",false).put("failure",failure.toString().take(1500));throw failure
        } finally {save()}
    }
}
