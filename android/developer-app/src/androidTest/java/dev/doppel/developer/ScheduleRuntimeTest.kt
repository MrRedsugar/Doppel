package dev.doppel.developer

import android.app.job.JobScheduler
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.ScheduleEngine
import dev.doppel.sdk.ScheduleManager
import dev.doppel.sdk.SchedulePort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Real Android storage/JobScheduler wiring, no network, provider call, or phone action. */
class ScheduleRuntimeTest {
    @Test fun onceCrudPersistsSystemJobAndPendingSubmissionBlocksDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = Gateway(context)
        val manager = ScheduleManager.get(context)
        val cleanupId = InstrumentationRegistry.getArguments().getString("cleanup_schedule_id").orEmpty()
        if (cleanupId.isNotBlank()) {
            require(runCatching { UUID.fromString(cleanupId).toString() == cleanupId }.getOrDefault(false)) { "Cleanup needs an exact UUID" }
            val existing = manager.request("GET", "/schedules").getJSONArray("items")
            val fixture = (0 until existing.length()).map { existing.getJSONObject(it) }.singleOrNull { it.optString("id") == cleanupId }
            if (fixture != null) {
                assertEquals("Only the known failed fixture may be cleaned", "Synthetic schedule persistence test", fixture.getString("goal"))
                assertEquals("schedule-fixture", fixture.getString("device_id"))
                assertEquals("Fixture with execution history must be reviewed manually", 0, fixture.getJSONArray("history").length())
                manager.request("DELETE", "/schedules/$cleanupId")
            }
        }
        val beforeJobs = manager.request("GET", "/schedules").getJSONArray("items")
        val beforeIds = (0 until beforeJobs.length()).map { beforeJobs.getJSONObject(it).getString("id") }.toSet()
        val fixtureGoal = "Synthetic schedule persistence test ${UUID.randomUUID()}"
        for (i in 0 until beforeJobs.length()) assertFalse("Finish existing schedule testing first", beforeJobs.getJSONObject(i).getBoolean("enabled"))
        assertNull("The test must not stop an existing worker", DeviceWorkerService.instance)
        val prefs = gateway.prefs
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val before = prefs.all.toMap(); val consentBefore = consent.all.toMap()
        val gateClass = Class.forName("dev.doppel.sdk.TaskSubmissionGate")
        val gateOwner = gateClass.getField("INSTANCE").get(null)
        val gate = gateClass.declaredMethods.single { it.name.startsWith("getCreating") && it.parameterCount == 0 }
            .invoke(gateOwner) as AtomicBoolean
        assertTrue("Another creation must be left untouched", gate.compareAndSet(false, true))
        var id: String? = null
        try {
            // Unroutable test-only connection: CRUD is local, and the owned submission gate
            // is checked before readiness can query any /runs endpoint.
            check(prefs.edit().putBoolean("direct_mode", false).putString("base_url", "http://127.0.0.1:9")
                .putString("token", "schedule-fixture-no-network").putString("device_id", "schedule-fixture").commit())
            check(FirstUseConsent.accept(context))
            val created = manager.request("POST", "/schedules", JSONObject().put("device_id", "schedule-fixture")
                .put("goal", fixtureGoal).put("mode", "ask")
                .put("rule", JSONObject().put("kind", "once").put("timezone", "Asia/Shanghai").put("at_ms", System.currentTimeMillis() + 120000)))
            id = created.getString("id")
            assertEquals("A saved plan must separately report successful system scheduling", "scheduled", created.getJSONObject("background_wakeup").getString("status"))
            val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.single { it.service.className == "dev.doppel.sdk.ScheduleJobService" }
            assertTrue("System timing must survive reboot", job.isPersisted)
            val persisted = File(context.noBackupFilesDir, "schedules-v1.json")
            assertTrue("Actual Android schedule file must exist", persisted.isFile)
            assertTrue(JSONObject(persisted.readText()).getJSONArray("items").toString().contains(id))
            verifyJobRefreshes(context, manager, id, job.id)
            manager.request("PATCH", "/schedules/$id", JSONObject().put("rule", JSONObject().put("kind", "once")
                .put("timezone", "Asia/Shanghai").put("at_ms", System.currentTimeMillis() + 1500)))
            Thread.sleep(1700)
            val done = CountDownLatch(1)
            manager.tick { done.countDown() }
            assertTrue("Scheduler check should finish", done.await(15, TimeUnit.SECONDS))
            val blocked = manager.request("GET", "/schedules/$id")
            assertEquals("device_busy", blocked.getString("waiting_reason"))
            assertEquals("No runtime task may be created", 0, blocked.getJSONArray("history").length())
            val disabled = manager.request("PATCH", "/schedules/$id", JSONObject().put("enabled", false))
            assertFalse(disabled.getBoolean("enabled"))
            assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(job.id))
            manager.request("DELETE", "/schedules/$id"); id = null
            assertEquals(beforeJobs.length(), manager.request("GET", "/schedules").getJSONArray("items").length())
            assertNull(DeviceWorkerService.instance)
        } finally {
            try {
                // A persisted create can precede a lost return value. Find only this
                // invocation's UUID-marked fixture, never blanket-delete new schedules.
                val current = manager.request("GET", "/schedules").getJSONArray("items")
                val own = (0 until current.length()).map { current.getJSONObject(it) }.filter {
                    it.optString("id") !in beforeIds && it.optString("goal") == fixtureGoal &&
                        it.optString("device_id") == "schedule-fixture" && it.getJSONArray("history").length() == 0
                }
                own.forEach { manager.request("DELETE", "/schedules/${it.getString("id")}") }
            }
            finally {
                try { restore(prefs, before) }
                finally { try { restore(consent, consentBefore) } finally { gate.set(false); manager.arm() } }
            }
        }
        assertTrue("Gateway configuration must be restored without printing credentials", before == prefs.all)
        assertTrue("Consent must be restored", consentBefore == consent.all)
    }

    @Test fun pausedRunGateRemainsBlockedAfterPersistentEngineRestart() {
        var now = 100000L
        var disk: String? = null
        var creates = 0
        val initial = ScheduleEngine(null, { disk = it }, { now }, { "fixture" })
        val id = initial.create(JSONObject().put("device_id", "fixture").put("goal", "Synthetic paused gate")
            .put("rule", JSONObject().put("kind", "once").put("at_ms", now + 1000))).getString("id")
        val port = object : SchedulePort {
            override fun readiness(job: JSONObject) = "device_busy" // Host reports existing paused task.
            override fun create(job: JSONObject): String { creates++; error("Must not preempt a paused task") }
            override fun start(runId: String) { error("Must not start a worker") }
            override fun status(runId: String): String? = null
        }
        now += 1000
        val restarted = ScheduleEngine(disk, { disk = it }, { now }, { "fixture" })
        restarted.tick(port)
        assertEquals("device_busy", restarted.get(id).getString("waiting_reason"))
        now += 300001; restarted.tick(port)
        assertEquals(0, creates)
        assertEquals("missed", restarted.get(id).getJSONArray("history").getJSONObject(0).getString("status"))
    }

    /** The caller holds the submission gate and all fixture times remain in the future. */
    private fun verifyJobRefreshes(context: Context, manager: ScheduleManager, id: String, jobId: Int) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val io = manager.javaClass.getDeclaredField("io").apply { isAccessible = true }.get(manager) as ExecutorService
        fun pending() = requireNotNull(scheduler.getPendingJob(jobId)) { "The next system wakeup was lost" }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(message, condition())
        }
        fun drain() { io.submit {}.get(15, TimeUnit.SECONDS); inst.waitForIdleSync() }
        fun runSystemJob() {
            val output = shell("cmd jobscheduler run -f ${context.packageName} $jobId")
            assertTrue("The real JobService must be started: $output", output.contains("Running job"))
        }
        val original = pending()
        assertEquals(manager.request("GET", "/schedules/$id").getLong("next_due_ms"), original.extras.getLong("due_ms"))
        val statusPrefs = context.getSharedPreferences("doppel_schedule_wakeup", Context.MODE_PRIVATE)
        val originalStatus = statusPrefs.getString("state", null)
        Thread.sleep(40)
        repeat(5) { manager.arm(false) }
        assertEquals("Unchanged jobs keep their original latency instead of being registered again",
            original.minLatencyMillis, pending().minLatencyMillis)
        assertEquals("Unchanged diagnostic state must not be rewritten", originalStatus, statusPrefs.getString("state", null))

        val changedDue = System.currentTimeMillis() + 180000
        manager.request("PATCH", "/schedules/$id", JSONObject().put("rule", JSONObject().put("kind", "once")
            .put("timezone", "Asia/Shanghai").put("at_ms", changedDue)))
        assertEquals(changedDue, pending().extras.getLong("due_ms"))
        val beforeForce = pending().minLatencyMillis
        Thread.sleep(40)
        manager.arm() // The original public no-argument entry remains an explicit forced refresh.
        assertTrue("Explicit refresh must recalculate relative time", pending().minLatencyMillis < beforeForce)
        scheduler.cancel(jobId)
        assertNull(scheduler.getPendingJob(jobId))
        manager.arm(false)
        assertEquals("A missing system job must be restored even with the same due time", changedDue, pending().extras.getLong("due_ms"))

        val beforeConsumption = pending().minLatencyMillis
        runSystemJob()
        await("Consuming the OS job must register the next wakeup with the same due time") {
            scheduler.getPendingJob(jobId)?.minLatencyMillis?.let { it < beforeConsumption } == true
        }
        drain()
        await("The old job must finish without deleting its replacement") {
            !shell("cmd jobscheduler get-job-state ${context.packageName} $jobId").contains("active")
        }
        assertEquals(changedDue, pending().extras.getLong("due_ms"))

        // Occupy the real check queue and its CAS flag before Android delivers onStartJob.
        val ticking = manager.javaClass.getDeclaredField("ticking").apply { isAccessible = true }.get(manager) as AtomicBoolean
        assertTrue("Do not take ownership of another scheduler check", ticking.compareAndSet(false, true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        io.execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val beforeBusyConsumption = pending().minLatencyMillis
            runSystemJob()
            await("A consumed job must wait for queued rearming when another check owns the CAS") {
                shell("cmd jobscheduler get-job-state ${context.packageName} $jobId").contains("active")
            }
            inst.waitForIdleSync()
            assertEquals(beforeBusyConsumption, pending().minLatencyMillis)
            ticking.set(false); release.countDown()
            await("The CAS-busy path must also preserve the next wakeup") {
                scheduler.getPendingJob(jobId)?.minLatencyMillis?.let { it < beforeBusyConsumption } == true
            }
            drain()
            await("Late completion of the busy job cannot remove the newly registered job") {
                !shell("cmd jobscheduler get-job-state ${context.packageName} $jobId").contains("active")
            }
            assertEquals(changedDue, pending().extras.getLong("due_ms"))
            assertEquals(0, manager.request("GET", "/schedules/$id").getJSONArray("history").length())
            assertNull(DeviceWorkerService.instance)
        } finally { ticking.set(false); release.countDown(); drain() }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(editor.commit())
    }
}
