package dev.doppel.developer

import android.app.Instrumentation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import dev.doppel.sdk.DoppelAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Refresh an already-enabled production service after instrumentation restarted its process.
 * Require agreement from settings, the local instance, AccessibilityManager and
 * ActivityManager. Its connection index can retain orphan ConnectionRecords after
 * unbinding; these are ignored only after the authoritative states remain clear.
 */
internal object AccessibilityServiceTestBinding {
    fun rebindAlreadyEnabled(inst: Instrumentation, progress: (JSONObject) -> Unit = {}): JSONObject {
        val context = inst.targetContext
        val automation = inst.getUiAutomation(1)
        val own = ComponentName(context, DoppelAccessibilityService::class.java)
        val original = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        val enabled = Settings.Secure.getInt(context.contentResolver, "accessibility_enabled", 0)
        require(original.matches(Regex("[A-Za-z0-9_.$/:]+")))
        val entries = original.split(':').filter { it.isNotBlank() }
        check(entries.any { ComponentName.unflattenFromString(it) == own }) { "Production accessibility must already be granted" }
        check(enabled == 1) { "Accessibility must already be enabled" }
        val others = entries.filter { ComponentName.unflattenFromString(it) != own }.joinToString(":")
        val report = JSONObject().put("component", own.flattenToString()).put("detach_probes", JSONArray())
        val started = SystemClock.elapsedRealtime()
        val folder = File(context.getExternalFilesDir(null), "accessibility-rebind-verification").apply { check(mkdirs() || isDirectory) }
        val evidence = AtomicFile(File(folder, "rebind-${System.currentTimeMillis()}-${UUID.randomUUID()}.json"))
        report.put("evidence_file", evidence.baseFile.absolutePath).put("stage", "initial")
        fun publish() {
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            val output = evidence.startWrite()
            try { output.write(bytes); evidence.finishWrite(output) }
            catch (error: Throwable) { evidence.failWrite(output); throw error }
            progress(report)
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { String(it.readBytes(), Charsets.UTF_8) }
        fun managerState(): String = shell("dumpsys accessibility").lineSequence()
            .dropWhile { !it.contains("Bound services:") }
            .takeWhile { !it.contains("Client list info:") }
            .joinToString("\n")
        fun records(): JSONObject {
            val dump = shell("dumpsys activity services ${own.flattenToString()}")
            check(dump.contains("ACTIVITY MANAGER SERVICES") && !dump.contains("Permission Denial")) {
                "Cannot verify ActivityManager unbinding"
            }
            var live=0
            var dead=0
            var connections=0
            var deadIndent:Int?=null
            val liveLines = JSONArray()
            for(line in dump.lineSequence()) {
                if(line.isBlank()) continue
                val indent=line.indexOfFirst {!it.isWhitespace()}
                if(deadIndent?.let {indent<=it}==true) deadIndent=null
                val connection=Regex("ConnectionRecord\\{[^}]*\\}").find(line)
                if(connection!=null && Regex("\\bDEAD\\b").containsMatchIn(connection.value)) {
                    dead++;deadIndent=indent;continue
                }
                // ServiceRecord always means live, including if a dump format
                // unexpectedly prints one inside a dead connection block.
                val before = live
                live+=Regex("ServiceRecord\\{").findAll(line).count()
                val lineConnections = Regex("ConnectionRecord\\{").findAll(line).count()
                connections += lineConnections
                live += lineConnections
                if(deadIndent==null) live+=Regex("AppBindRecord\\{").findAll(line).count()
                if (live > before) liveLines.put(line.trim())
            }
            return JSONObject().put("live",live).put("dead_connections",dead).put("live_lines", liveLines).put("raw_dump", dump)
                .put("service_records", Regex("ServiceRecord\\{").findAll(dump).count()).put("connections", connections)
        }
        val initial=records()
        report.put("initial_binding_records",initial.getInt("live")).put("initial_dead_connections",initial.getInt("dead_connections"))
            .put("initial_activity_dump", initial.getString("raw_dump")).put("initial_accessibility_manager", managerState())
        publish()
        android.util.Log.i("DoppelRebind", "Evidence: ${evidence.baseFile.absolutePath}")
        try {
            report.put("stage", "disable_requested")
            if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $others")
            val deadline = SystemClock.elapsedRealtime() + 4000
            var removed = false
            var absentSince: Long? = null
            do {
                val bindings=records()
                val count=bindings.getInt("live")
                val localAbsent = DoppelAccessibilityService.instance == null
                val setting = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
                val settingRemoved = setting.orEmpty().split(':').none { ComponentName.unflattenFromString(it) == own }
                val manager = managerState()
                val activeManager = manager.substringBefore("Crashed services:", "")
                val managerClear = listOf("Bound services:", "Enabled services:", "Binding services:").all { activeManager.contains(it) } &&
                    listOf(own.flattenToString(), own.flattenToShortString()).none { activeManager.contains(it) }
                val authoritativeClear = localAbsent && settingRemoved && managerClear && bindings.getInt("service_records") == 0
                val now = SystemClock.elapsedRealtime()
                if (authoritativeClear) {
                    if (absentSince == null) absentSince = now
                    // Confirm agreement over successive system queries, including
                    // when there was no local Service before the setting write.
                    removed = now - requireNotNull(absentSince) >= 150
                } else absentSince = null
                val orphanIgnored = removed && bindings.getInt("connections") > 0
                report.getJSONArray("detach_probes").put(JSONObject().put("elapsed_ms", now - started)
                    .put("system_binding_records", count).put("dead_connection_records",bindings.getInt("dead_connections"))
                    .put("service_records", bindings.getInt("service_records")).put("connection_records", bindings.getInt("connections"))
                    .put("orphan_ignored", orphanIgnored).put("authoritative_states_clear", authoritativeClear)
                    .put("setting_removed", settingRemoved).put("accessibility_manager_clear", managerClear)
                    .put("local_instance_absent", localAbsent).put("live_lines", bindings.getJSONArray("live_lines"))
                    .put("enabled_services_setting", setting ?: JSONObject.NULL)
                    .put("accessibility_enabled_setting", Settings.Secure.getInt(context.contentResolver, "accessibility_enabled", 0))
                    .put("accessibility_manager", manager))
                report.put("stage", "waiting_for_unbind").put("latest_activity_dump", bindings.getString("raw_dump"))
                report.put("orphan_ignored", orphanIgnored)
                if (removed) break
                publish()
                Thread.sleep(40)
            } while (SystemClock.elapsedRealtime() < deadline)
            report.put("removal_acknowledged", removed).put("removed_ms", SystemClock.elapsedRealtime() - started)
            publish()
            check(removed) { "Production accessibility unbinding was not acknowledged by all authoritative states" }
        } catch (error: Throwable) {
            report.put("failure_type", error.javaClass.simpleName).put("failure_message", error.message?.take(300) ?: JSONObject.NULL)
            throw error
        } finally {
            report.put("stage", "restore_settings")
            // Preserve the exact original list, order and other services on every exit.
            shell("settings put secure enabled_accessibility_services $original")
            val listRestored=original==Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services")
            report.put("enabled_services_unchanged",listRestored)
            // Android 14 can clear the global switch when the last service is
            // temporarily removed. Restore its original value after the list.
            if(listRestored) shell("settings put secure accessibility_enabled $enabled")
            report.put("accessibility_switch_restored",Settings.Secure.getInt(context.contentResolver,"accessibility_enabled",0)==enabled)
            publish()
        }
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (DoppelAccessibilityService.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        report.put("rebound", DoppelAccessibilityService.instance != null).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        report.put("stage", "rebound_check").put("final_accessibility_manager", managerState())
        publish()
        check(report.getBoolean("enabled_services_unchanged"))
        check(Settings.Secure.getInt(context.contentResolver, "accessibility_enabled", 0) == enabled)
        check(DoppelAccessibilityService.instance != null) { "Production accessibility did not bind after acknowledged removal" }
        return report
    }
}
