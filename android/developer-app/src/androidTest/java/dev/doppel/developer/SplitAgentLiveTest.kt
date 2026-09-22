package dev.doppel.developer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in tests against installed real applications. All task actions come from the production engine. */
class SplitAgentLiveTest {
    /** Independent A protocol check when another provider is unavailable; no device action or routing change. */
    @Test fun verifyPrimaryThinkingOnly() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("thinking_check")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val models=ModelProviders(context);val before=models.routing()
        val report=JSONObject().put("primary_only",true).put("task_started",false).put("game_input_injected",false)
            .put("model_routing",routingEvidence(before))
        try {
            probePrimarySchema(context,report)
            val wire=report.getJSONObject("strict_schema_probe").getJSONObject("wire")
            assertEquals("enabled",wire.getString("thinking"))
            assertEquals("low",wire.getString("reasoning_effort"))
            assertEquals("auto",wire.getString("tool_choice"))
            report.put("configuration_verified",true)
        } finally {
            report.put("model_routing_preserved",before==models.routing())
            File(context.filesDir,"split-thinking-check-latest.json").writeText(report.toString(2))
        }
    }

    /** Opt-in connection setup only: may end the last harness run, never unrelated tasks or game input. */
    @Test fun configureModelsOnly() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("configure_models_only")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val models=ModelProviders(context)
        val before=models.routing()
        val evidence=File(context.filesDir,"split-model-config-latest.json")
        val report=JSONObject().put("configure_only",true)
            .put("configuration_request_id",args.getString("configuration_request_id").orEmpty())
            .put("model_routing_before",routingEvidence(before)).put("task_started",false)
            .put("task_cancelled",false).put("previous_harness_run_cancelled",false)
            .put("unrelated_task_cancelled",false).put("game_input_injected",false)
        val started=SystemClock.elapsedRealtime()
        var stage="setup";var verified=false
        try {
            require(args.getString("primary_model")!=null || args.getString("primary_provider")!=null) {"Explicit model selection is required"}
            FirstUseConsent.requireAccepted(context)
            stage="previous_harness_cleanup"
            val previous=File(context.filesDir,"split-live-run-id.txt")
            if(previous.exists()) {
                val gateway=Gateway(context)
                val old=gateway.request("GET","/runs/${previous.readText()}")
                if(old.optString("status") !in setOf("completed","failed","cancelled")) {
                    gateway.request("POST","/runs/${old.getString("id")}/cancel",JSONObject())
                    check(gateway.request("GET","/runs/${old.getString("id")}").optString("status")=="cancelled")
                    report.put("task_cancelled",true).put("previous_harness_run_cancelled",true)
                }
            }
            // Changing a provider must not change the next request of an unrelated active/paused task.
            check(!DirectRuntime.get(context).hasUnfinishedRun()) {"Finish the existing task before changing model routing"}
            stage="primary_model_setting"
            applyPrimarySelection(context,models,args,report)
            report.put("model_routing",routingEvidence(models.routing()))
            stage="vision_probes"
            probeEnabledModels(context,models,report,force=true)
            assertTrue("All enabled models must have verified image support",models.isReady())
            stage="strict_schema_probe"
            probePrimarySchema(context,report)
            assertEquals(before.enhancement,models.routing().enhancement)
            assertEquals(before.enhancementEnabled,models.routing().enhancementEnabled)
            verified=true
            report.put("configuration_verified",true)
        } catch(failure:Throwable) {
            report.put("failure_stage",stage).put("failure_class",failure.javaClass.simpleName)
            // Never print credential-bearing exceptions or request bodies through instrumentation.
            throw AssertionError("Model configuration failed at $stage; see sanitized configuration evidence")
        } finally {
            if(!verified) {
                if(models.routing()!=before) models.saveRouting(before)
                report.put("routing_restored",models.routing()==before)
            }
            report.put("model_routing_after",routingEvidence(models.routing()))
                .put("elapsed_ms",SystemClock.elapsedRealtime()-started)
            evidence.writeText(report.toString(2))
            ins.sendStatus(0,Bundle().apply {putString("stream","\nEvidence: files/split-model-config-latest.json\n")})
        }
    }

    @Test fun configureAndRun() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("split_live")=="true" && args.getString("configure_models_only")!="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val goal=args.getString("goal") ?: "打开计算器，计算 125 乘以 8"
        val evidence=File(context.filesDir,"split-live-latest.json")
        val keyFile=File(context.filesDir,"qa-qwen-key.txt")
        val report=JSONObject().put("goal",goal).put("control","production SplitTaskEngine with actual model settings")
        val started=SystemClock.elapsedRealtime();var id:String?=null
        val gateway=Gateway(context)
        val unassisted=args.getString("unassisted")=="true"
        var modelRoutingBefore:ModelRouting?=null
        var modelSetupVerified=false
        report.put("unassisted",unassisted)
        var stage="setup"
        try {
            check(FirstUseConsent.accept(context));FirstUseConsent.finishGuide(context)
            val models=ModelProviders(context)
            // Preserve unrelated user tasks; only the previous opt-in harness run may be replaced.
            val previous=File(context.filesDir,"split-live-run-id.txt")
            if(previous.exists()) runCatching {
                val old=gateway.request("GET","/runs/${previous.readText()}")
                if(old.optString("status") !in setOf("completed","failed","cancelled")) gateway.request("POST","/runs/${old.getString("id")}/cancel",JSONObject())
            }
            check(!DirectRuntime.get(context).hasUnfinishedRun()) {"Unrelated unfinished task must be preserved"}
            if(keyFile.exists()) {
                val bytes=keyFile.readBytes()
                try { models.saveProvider(ModelProvider.qwen(),String(bytes,Charsets.UTF_8).trim(),emptyMap()) }
                finally {bytes.fill(0);check(keyFile.delete())}
            }
            if(args.getString("primary_model")!=null || args.getString("primary_provider")!=null) {
                stage="primary_model_setting"
                modelRoutingBefore=models.routing()
                applyPrimarySelection(context,models,args,report)
            }
            args.getString("enhancement")?.let { option ->
                require(option in setOf("true","false"))
                stage="settings_toggle"
                val wanted=option=="true"
                val settings=ins.startActivitySync(Intent(context,ModelSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    val toggle=descendants(settings.window.decorView).filterIsInstance<Switch>().single {it.text.toString()=="独立视觉增强"}
                    // CompoundButton toggles even when performClick returns false (no OnClickListener).
                    // Verify the saved routing and re-rendered checked state below.
                    if(toggle.isChecked!=wanted) {assertTrue(toggle.isEnabled);toggle.performClick()}
                }
                val deadline=SystemClock.elapsedRealtime()+6000
                var uiReady=false
                while(SystemClock.elapsedRealtime()<deadline) {
                    ins.runOnMainSync {
                        val nodes=descendants(settings.window.decorView).toList()
                        val toggle=nodes.filterIsInstance<Switch>().singleOrNull {it.text.toString()=="独立视觉增强"}
                        uiReady=toggle?.isChecked==wanted && toggle.isEnabled && (wanted || nodes.filterIsInstance<TextView>().any {it.text.toString()=="当前仅使用默认模型直接操作手机。"})
                    }
                    if(uiReady && models.routing().enhancementEnabled==wanted) break
                    Thread.sleep(50)
                }
                assertTrue("Actual settings UI must save the requested execution mode",uiReady)
                assertEquals(wanted,models.routing().enhancementEnabled)
                report.put("settings_toggle_verified",true)
                ins.runOnMainSync {settings.finish()};ins.waitForIdleSync()
            }
            val enhanced=models.routing().enhancementEnabled
            report.put("enhancement_enabled",enhanced)
            report.put("model_routing",routingEvidence(models.routing()))
            stage="vision_probes"
            probeEnabledModels(context,models,report)
            evidence.writeText(report.toString(2))
            assertTrue("All enabled models must have verified image support",models.isReady())
            modelSetupVerified=true
            stage="accessibility"
            AccessibilityServiceTestBinding.rebindAlreadyEnabled(ins) { diagnostic ->
                report.put("accessibility_binding",diagnostic);evidence.writeText(report.toString(2))
            }
            assertNotNull("Production accessibility service must bind",DoppelAccessibilityService.instance)
            assertTrue(Settings.canDrawOverlays(context))
            stage="capture_authorization"
            if(Build.VERSION.SDK_INT in 26..29 && !LegacyScreenCaptureService.isReady) LegacyCaptureConsent.authorize(ins)
            stage="direct_mode"
            if(!DirectMode.isEnabled(context)) DirectMode.configure(context,true)
            if(args.getString("fresh_conversation")=="true" || unassisted) {
                gateway.startNewConversation()
                report.put("fresh_conversation",true)
                check(gateway.selectedConversationRun().isNullOrEmpty())
            }
            gateway.prefs.edit().remove("active_run").putInt("mode_index",2).putBoolean("completion_speech",false)
                .putString("draft_goal","").putString("companion_draft","").commit()
            stage="task_entry"
            // Start where the user is, as a task entered through the floating
            // composer would. Do not spend two model calls reopening the game.
            report.put("forced_home_before_task",false)
            val voice=ins.startActivitySync(Intent(context,VoiceActivity::class.java).putExtra(VoiceActivity.EXTRA_OPEN_KEYBOARD,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ins.waitForIdleSync()
            var editor:EditText?=null;var button:Button?=null
            val ready=SystemClock.elapsedRealtime()+5000
            while(SystemClock.elapsedRealtime()<ready) {
                ins.runOnMainSync {
                    editor=descendants(voice.window.decorView).filterIsInstance<EditText>().firstOrNull {it.isShown && it.isEnabled}
                    button=descendants(voice.window.decorView).filterIsInstance<Button>().firstOrNull {it.isShown && it.isEnabled && it.text.toString()=="开始任务"}
                }
                if(editor!=null && button!=null) break
                Thread.sleep(50)
            }
            assertNotNull("Actual task editor",editor);assertNotNull("Actual submit control",button)
            val created=System.currentTimeMillis()
            ins.runOnMainSync {editor!!.setText(goal);check(button!!.performClick())}
            stage="execution"
            val limit=(args.getString("limit_seconds")?.toLongOrNull() ?: 240).coerceIn(30,900)*1000
            val deadline=SystemClock.elapsedRealtime()+limit
            while(SystemClock.elapsedRealtime()<deadline) {
                if(id==null) {
                    val runs=gateway.request("GET","/runs").getJSONArray("items")
                    id=(0 until runs.length()).map {runs.getJSONObject(it)}.firstOrNull {it.optString("goal")==goal && it.optLong("created_at")>=created}?.getString("id")
                    id?.let {previous.writeText(it)}
                }
                id?.let {
                    val run=gateway.request("GET","/runs/$it")
                    if(unassisted) {
                        check(run.optString("parent_run_id").isBlank()) {"Unassisted run must not inherit earlier tasks"}
                    }
                    report.put("run",run).put("elapsed_ms",SystemClock.elapsedRealtime()-started);evidence.writeText(report.toString(2))
                }
                if(report.optJSONObject("run")?.optString("status") in setOf("completed","failed","cancelled","paused","awaiting_approval","awaiting_input")) break
                Thread.sleep(400)
            }
        } catch(failure:Throwable) {
            report.put("failure_stage",stage).put("failure_class",failure.javaClass.simpleName)
                .put("failure_location",failure.stackTrace.firstOrNull {it.className.startsWith("dev.doppel")}?.toString())
            throw failure
        } finally {
            if(modelRoutingBefore!=null && !modelSetupVerified) {
                val models=ModelProviders(context)
                models.saveRouting(modelRoutingBefore!!)
                report.put("model_routing_restored",models.routing()==modelRoutingBefore)
            }
            id?.let {runCatching {
                report.put("events",gateway.request("GET","/runs/$it/events")).put("screenshots",gateway.request("GET","/runs/$it/screenshots"))
                if(gateway.request("GET","/runs/$it").optString("status")=="running") {
                    report.put("test_deadline_reached",true);gateway.request("POST","/runs/$it/pause",JSONObject())
                }
                report.put("run",gateway.request("GET","/runs/$it"))
            }}
            report.put("elapsed_ms",SystemClock.elapsedRealtime()-started);evidence.writeText(report.toString(2))
            if(keyFile.exists()) keyFile.delete()
            ins.sendStatus(0,Bundle().apply {putString("stream","\nEvidence: files/split-live-latest.json\n")})
        }
        assertEquals("Actual business result still requires screenshot review","completed",report.optJSONObject("run")?.optString("status"))
        val completed=report.getJSONObject("run")
        val enhanced=report.getBoolean("enhancement_enabled")
        assertEquals(if(enhanced) "ab" else "direct",completed.getString("execution_mode"))
        val groundingCalls=completed.optJSONObject("model_metrics")?.optJSONObject("grounding")?.optInt("calls")?:0
        if(!enhanced) assertEquals("Disabled enhancement must never request B",0,groundingCalls)
    }

    private fun applyPrimarySelection(context:android.content.Context,models:ModelProviders,args:Bundle,report:JSONObject) {
        val before=models.routing()
        val providerId=args.getString("primary_provider") ?: before.primary.providerId
        val model=args.getString("primary_model") ?: before.primary.model
        if(providerId!=before.primary.providerId) require(args.getString("primary_model")!=null) {"Changing provider requires an explicit model"}
        if(args.getString("primary_provider")=="deepseek") {
            val staged=File(context.filesDir,"qa-deepseek-key.txt")
            if(staged.exists()) {
                var bytes:ByteArray?=null
                try {
                    require(staged.length() in 1..4096) {"Staged credential length is invalid"}
                    val imported=staged.readBytes();bytes=imported
                    models.saveProvider(ModelProvider.deepseek(),String(imported,Charsets.UTF_8).trim(),emptyMap())
                    report.put("deepseek_credential_imported",true)
                } finally {
                    bytes?.fill(0)
                    check(staged.delete()) {"Staged credential cleanup failed"}
                }
            } else {
                models.saveProvider(ModelProvider.deepseek())
            }
        }
        require(models.list().any {it.id==providerId}) {"Selected provider must already be configured"}
        val credentialAvailable=models.hasCredentials(providerId)
        report.put("selected_provider_has_credentials",credentialAvailable)
        check(credentialAvailable) {"Selected provider requires credentials"}
        models.saveRouting(before.copy(primary=ModelSelection(providerId,model)))
        val after=models.routing()
        assertEquals(ModelSelection(providerId,model),after.primary)
        assertEquals(before.enhancement,after.enhancement)
        assertEquals(before.enhancementEnabled,after.enhancementEnabled)
        report.put("requested_primary_provider",providerId).put("requested_primary_model",model)
    }

    private fun routingEvidence(routing:ModelRouting)=JSONObject().apply {
        put("enhancement_enabled",routing.enhancementEnabled)
        for(role in listOf("primary","grounding")) {
            val selected=routing.select(role)
            put(role,JSONObject().put("provider",selected.providerId).put("model",selected.model))
        }
        put("saved_enhancement",JSONObject().put("provider",routing.enhancement.providerId).put("model",routing.enhancement.model))
    }

    private fun probeEnabledModels(context:android.content.Context,models:ModelProviders,report:JSONObject,force:Boolean=false) {
        val probes=JSONArray();report.put("probes",probes)
        val roles=if(models.routing().enhancementEnabled) listOf("primary","grounding") else listOf("primary")
        for(role in roles) {
            val selected=models.resolve(role)
            if(force || selected.vision!=ModelVision.VERIFIED) {
                val result=ModelApi(context).probeVision(selected.provider.id,selected.selection.model,role)
                probes.put(JSONObject().put("role",role).put("model",selected.selection.model)
                    .put("vision",result.vision.name).put("elapsed_ms",result.elapsedMs))
            }
        }
    }

    /** Exercises the full A schema and image transport once, without passing output to any executor. */
    private fun probePrimarySchema(context:android.content.Context,report:JSONObject) {
        fun sdk(name:String)=Class.forName("dev.doppel.sdk.$name",true,ModelApi::class.java.classLoader)
        val schemaType=sdk("SplitOutputSchema")
        val schemaObject=schemaType.getField("INSTANCE").get(null)
        val format=schemaType.getMethod("format",String::class.java,Boolean::class.javaPrimitiveType,String::class.java)
            .invoke(schemaObject,"primary",false,null) as JSONObject
        val imageType=sdk("ModelSyntheticImage")
        val challenge=imageType.getMethod("create").invoke(imageType.getField("INSTANCE").get(null))
        val imageUrl=challenge.javaClass.getMethod("getDataUrl").invoke(challenge) as String
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",
            "This is a JSON protocol connection check, not a phone task. Output exactly a JSON object with decision " +
            "{\"kind\":\"wait\",\"duration_ms\":100,\"reason\":\"connection check\",\"wait_condition\":\"check complete\",\"evidence\":\"synthetic image\"} " +
            "and sibling state:null. Follow the supplied JSON Schema. No action will be executed."))
            .put(JSONObject().put("role","user").put("content",JSONArray()
                .put(JSONObject().put("type","text").put("text","Return the requested JSON protocol check result."))
                .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",imageUrl)))))
        val payload=JSONObject().put("_doppel_role","primary").put("messages",messages)
            .put("max_completion_tokens",6500).put("response_format",format)
        val record=JSONObject().put("schema_valid",false).put("action_executed",false)
        report.put("strict_schema_probe",record)
        val started=SystemClock.elapsedRealtime()
        try {
            val response=ModelApi(context).complete(payload)
            response.optJSONObject("_doppel_request")?.let {record.put("wire",it)}
            response.optJSONObject("usage")?.let {usage ->
                val counts=JSONObject()
                for(name in listOf("prompt_tokens","completion_tokens","total_tokens")) {
                    (usage.opt(name) as? Number)?.takeIf {it.toLong()>=0}?.let {counts.put(name,it)}
                }
                record.put("usage",counts)
            }
            val protocol=sdk("SplitAgentProtocol")
            var parsedWire:JSONObject?=null
            val onParsed:(JSONObject)->Unit={parsedWire=it}
            val value=protocol.getMethod("content",JSONObject::class.java,JSONObject::class.java,String::class.java,
                JSONObject::class.java,kotlin.jvm.functions.Function1::class.java)
                .invoke(protocol.getField("INSTANCE").get(null),response,format,"primary",null,onParsed) as JSONObject
            check(value.getString("kind")=="wait" && value.getInt("duration_ms")==100 &&
                parsedWire?.has("state")==true && parsedWire?.isNull("state")==true) {"Protocol probe did not return the requested branch"}
            record.put("schema_valid",true)
        } finally {
            record.put("elapsed_ms",SystemClock.elapsedRealtime()-started)
        }
    }

    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view);if(view is ViewGroup) for(i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
