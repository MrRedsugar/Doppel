package dev.doppel.developer

import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import org.json.JSONArray
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Isolates a stale terminal pointer, never an unfinished task or a task's stored record. */
internal class TerminalTaskPointerTestRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("doppel", 0)
            val original = prefs.getString("active_run", "").orEmpty()
            if (original.isBlank()) { base.evaluate(); return }
            check(DeviceWorkerService.instance == null) { "Retain any existing worker" }
            val file = File(context.noBackupFilesDir, "direct-runs-v1.json")
            val before = file.readBytes()
            val rows = JSONArray(String(before, Charsets.UTF_8))
            val terminal = setOf("completed", "failed", "cancelled")
            check((0 until rows.length()).all { rows.getJSONObject(it).optString("status") in terminal }) {
                "Retain every unfinished task"
            }
            check((0 until rows.length()).any {
                val row = rows.getJSONObject(it)
                row.optString("id") == original && row.optString("status") in terminal
            }) { "Only a locally confirmed terminal task pointer may be isolated" }
            check(prefs.edit().remove("active_run").commit())
            var failure: Throwable? = null
            try { base.evaluate() } catch (error: Throwable) { failure = error; throw error }
            finally {
                try {
                    check(DeviceWorkerService.instance == null) { "Fixture worker must stop before restoring the owner's task pointer" }
                    check(prefs.getString("active_run", "").isNullOrBlank()) { "Do not replace a surviving fixture or newer task pointer" }
                    check(before.contentEquals(file.readBytes())) { "The fixture must preserve every original task record" }
                    check(prefs.edit().putString("active_run", original).commit())
                    check(prefs.getString("active_run", "") == original)
                } catch (cleanup: Throwable) { if (failure != null) failure!!.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }
}
