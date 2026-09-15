package dev.doppel.developer

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.GuiGroundingSettingsActivity
import dev.doppel.sdk.ShellBridgeClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in developer backend preparation; GUI setup uses settings controls, never a target-app result. */
class PlannedControlSetupTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun texts(a:Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun click(a:Activity,label:String) { i.runOnMainSync { texts(a).single{it.text.toString()==label}.performClick() } }
    @Test fun configureOptionalLocalBackends() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("planned_setup")=="true")
        check(args.getString("emulatorOnly")=="true" && Build.MODEL in setOf("LDY-ANO0","LDY_ANO0"))
        check(DirectMode.isEnabled(context) && !DirectRuntime.get(context).hasUnfinishedRun())
        var activity:Activity?=null
        try {
            if(args.getString("shell")=="true") {
                val state=ShellBridgeClient.get(context).activate()
                assertTrue(state.optBoolean("enabled") && state.optBoolean("connected") && state.optInt("uid")==2000)
            }
            if(args.getString("gui")=="true") {
                val tokenFile=File(context.filesDir,"gui-test-token.txt")
                val token=tokenFile.readText().trim();check(token.matches(Regex("[0-9a-f]{64}")))
                val a=i.startActivitySync(Intent(context,GuiGroundingSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity=a
                i.runOnMainSync { val fields=views(a.window.decorView).filterIsInstance<EditText>();check(fields.size==2)
                    fields[0].setText("http://127.0.0.1:8791");fields[1].setText(token) }
                activity=a
                click(a,"保存并启用")
                i.runOnMainSync {assertTrue(texts(a).any{it.text.toString()=="已启用 · 下次任务生效"})}
                check(tokenFile.delete())
            }
        } finally {i.runOnMainSync{activity?.finish()}}
    }
}
