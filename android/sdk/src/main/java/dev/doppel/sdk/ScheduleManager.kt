package dev.doppel.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android host adapter. Scheduled goals receive no extra execution or payment authority. */
class ScheduleManager private constructor(context: Context) {
    companion object {
        private const val JOB_ID = 8321
        @Volatile private var instance: ScheduleManager? = null
        internal fun conflictVersion(job: JSONObject): String = JSONObject().apply {
            for (key in listOf("id", "device_id", "binding", "enabled", "next_due_ms", "goal", "mode", "allowed_packages", "rule"))
                put(key, job.opt(key) ?: JSONObject.NULL)
        }.toString()
        fun get(context: Context): ScheduleManager = instance ?: synchronized(this) {
            instance ?: ScheduleManager(context.applicationContext).also { instance = it }
        }
    }
    private val context = context.applicationContext
    private val gateway = Gateway(this.context)
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "schedules-v1.json"))
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "doppel-schedules").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val ticking = AtomicBoolean(false)
    private var lastTick = 0L
    private val engine = ScheduleEngine(read(), ::write, binding = ::binding)
    private val wakeupPrefs = this.context.getSharedPreferences("doppel_schedule_wakeup", Context.MODE_PRIVATE)
    private val wakeup = ScheduleWakeupStatus(wakeupPrefs.getString("state", null), { state ->
        check(wakeupPrefs.edit().putString("state", state).commit()) { "System timing status was not saved" }
    })
    /** Manual UI action. No model or conversation path calls this entry point. */
    fun control(id: String, action: String) {
        io.execute {
            try { FirstUseConsent.requireAccepted(context); engine.control(id, action); engine.tick(port()); notifyWaiting(); arm(false) }
            catch (error: Exception) { main.post { android.widget.Toast.makeText(context, error.message?.take(120) ?: "计划已变化，请刷新", android.widget.Toast.LENGTH_LONG).show() } }
        }
    }
    internal fun isConflictCurrent(id: String, version: String): Boolean = runCatching {
        val job = engine.get(id)
        val due = job.optLong("next_due_ms")
        job.optBoolean("enabled") && !job.isNull("next_due_ms") && due <= System.currentTimeMillis() &&
            System.currentTimeMillis() - due <= ScheduleEngine.GRACE_MS && job.optString("binding") == binding() && conflictVersion(job) == version
    }.getOrDefault(false)

    /** Explicit notification choice. This path cannot auto-unlock a device that becomes locked. */
    internal fun continueConflict(id: String, version: String): Boolean {
        if (!isConflictCurrent(id, version) || AutomaticUnlockSession.active ||
            AutomaticTaskNotice.localBlockReason(context) != null || !gateway.prefs.getString("active_run", "").isNullOrBlank()) return false
        val ticket = TaskControl.currentGeneration()
        val connection = AutomaticTaskNotice.connectionStamp(context)
        io.execute {
            var approvedDue: Long? = null
            try {
                check(isConflictCurrent(id, version) && TaskControl.isCurrent(ticket) && connection == AutomaticTaskNotice.connectionStamp(context) &&
                    !AutomaticUnlockSession.active && AutomaticTaskNotice.localBlockReason(context) == null && gateway.prefs.getString("active_run", "").isNullOrBlank()) { "设备或计划已变化，自动任务未启动" }
                approvedDue = engine.control(id, "execute").getLong("next_due_ms")
                engine.tick(port(allowAutomaticUnlock = false), onlyId = id)
                // A failed immediate choice must not leave a stored approval that unlocks later.
                if (isConflictCurrent(id, version)) {
                    engine.clearManualDecision(id, approvedDue)
                    main.post { android.widget.Toast.makeText(context, "设备状态已变化，这次自动任务未启动", android.widget.Toast.LENGTH_LONG).show() }
                }
                notifyWaiting(); arm(false)
            } catch (error: Exception) {
                approvedDue?.let { due -> runCatching { engine.clearManualDecision(id, due) } }
                main.post { android.widget.Toast.makeText(context, error.message ?: "自动任务未启动", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        return true
    }
    private fun read(): String? {
        if (!file.baseFile.exists()) return null
        check(file.baseFile.length() <= 4 * 1024 * 1024) { "定时任务记录超过上限" }
        return file.openRead().use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
    private fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); check(bytes.size <= 4 * 1024 * 1024) { "定时任务记录空间不足，请清理历史计划" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun binding(): String {
        val prefs = gateway.prefs
        val identity = if (gateway.isDirectMode()) "direct:${context.packageName}" else
            "gateway:${prefs.getString("base_url", "")}:${prefs.getString("device_id", "")}:${prefs.getString("token", "")}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    /** Root Gateway routes /schedules here after FirstUseConsent.requireRequest. */
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val parts = path.substringBefore('?').trim('/').split('/')
        require(parts.firstOrNull() == "schedules" && parts.size in 1..2) { "定时任务地址无效" }
        val result = when {
            parts.size == 1 && method == "GET" -> engine.list()
            parts.size == 1 && method == "POST" -> {
                FirstUseConsent.requireAccepted(context)
                val payload = body ?: error("缺少计划内容")
                check(payload.optString("device_id") == gateway.prefs.getString("device_id", "")) { "只能为当前连接设备创建计划" }
                validateDirectScope(payload)
                check(gateway.isConnected()) { "请先连接设备" }; engine.create(payload)
            }
            parts.size == 2 && method == "GET" -> engine.get(parts[1])
            parts.size == 2 && method in setOf("PATCH", "PUT") -> {
                val payload = body ?: error("缺少修改内容")
                if (payload.optBoolean("enabled") || payload.keys().asSequence().any { it != "enabled" }) FirstUseConsent.requireAccepted(context)
                check(!payload.has("device_id") || payload.optString("device_id") == gateway.prefs.getString("device_id", "")) { "只能绑定当前连接设备" }
                if (payload.optBoolean("enabled") || payload.keys().asSequence().any { it != "enabled" }) {
                    val merged = engine.get(parts[1]); payload.keys().forEach { merged.put(it, payload.get(it)) }; validateDirectScope(merged)
                }
                engine.update(parts[1], payload)
            }
            parts.size == 2 && method == "DELETE" -> engine.delete(parts[1])
            else -> error("不支持的定时任务请求")
        }
        // The plan mutation is already committed. System wakeup failure is a separate
        // visible status, never a failed create that encourages resubmission.
        val background = if (method in setOf("POST", "PATCH", "PUT", "DELETE")) arm(false) else wakeup.current()
        return result.put("background_wakeup", background)
    }
    /** Nonblocking, throttled hook for the existing foreground worker loop. */
    fun tick(onComplete: (() -> Unit)? = null) = tick(false, onComplete)
    /** A running system Job is about to be consumed, even if a foreground check already owns the queue. */
    internal fun tickFromSystemJob(onComplete: () -> Unit) = tick(true, onComplete)
    private fun tick(forceRearm: Boolean, onComplete: (() -> Unit)?) {
        val elapsed = SystemClock.elapsedRealtime()
        if ((onComplete == null && elapsed - lastTick < 10000) || !ticking.compareAndSet(false, true)) {
            if (forceRearm) io.execute { finishTick(true, onComplete) }
            else onComplete?.let { main.post(it) }
            return
        }
        lastTick = elapsed
        io.execute {
            try { engine.tick(port()); notifyWaiting() }
            catch (_: Exception) { android.util.Log.w("DoppelSchedule", "Schedule check unavailable; retained state was not replayed") }
            finally { ticking.set(false); finishTick(forceRearm, onComplete) }
        }
    }
    private fun finishTick(forceRearm: Boolean, onComplete: (() -> Unit)?) {
        // Register the next Job before releasing the OS's current execution; stale Job callbacks
        // cannot remove its replacement, and onStopJob never interrupts the separate device task.
        runCatching { arm(forceRearm) }
        onComplete?.let { main.post(it) }
    }
    /** Persisted best-effort job; no exact alarm entitlement or background FGS exemption. */
    @JvmOverloads fun arm(force: Boolean = true): JSONObject {
        val result = wakeup.refresh {
            val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
            val due = engine.nextDue()
            val pending = scheduler.getPendingJob(JOB_ID)
            if (due == null) { if (pending != null) scheduler.cancel(JOB_ID); "idle" }
            else if (!force && pending?.extras?.getLong("due_ms", -1L) == due) "scheduled"
            else {
                val delay = (due - System.currentTimeMillis()).coerceIn(30000L, 24 * 3600000L)
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, ScheduleJobService::class.java))
                    .setExtras(PersistableBundle().apply { putLong("due_ms", due) })
                    .setMinimumLatency(delay).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()
                if (scheduler.schedule(job) == android.app.job.JobScheduler.RESULT_SUCCESS) "scheduled" else "declined"
            }
        }
        if (result.optString("status") == "waiting")
            android.util.Log.w("DoppelSchedule", "System wakeup unavailable; plan remains saved and foreground checks remain available")
        return result
    }
    private fun ready(job: JSONObject, ownGate: Boolean = false, manualDecision: Boolean = false, allowAutomaticUnlock: Boolean = true): String? {
        val unlockKey = SchedulePromptOverlay.key(job)
        fun busy(runId: String): String {
            if (runId.isNotBlank()) AutomaticTaskConflict.offerSchedule(context, runId, job)
            return "device_busy"
        }
        val active = gateway.prefs.getString("active_run", "").orEmpty()
        if (AutomaticUnlockSession.active && !AutomaticUnlockSession.matches(unlockKey)) return busy(active)
        if (AutomaticUnlockSession.matches(unlockKey) && AutomaticUnlockSession.isUnlocking) return "device_unlocking"
        val localBlock = AutomaticTaskNotice.localBlockReason(context, ownGate)
        if (localBlock != null && !(localBlock == "device_locked" && allowAutomaticUnlock && AutomaticUnlockCredentials.isEnabled(context))) return localBlock
        if (!gateway.isConnected() || job.optString("device_id") != gateway.prefs.getString("device_id", "")) return "connection_changed"
        if (job.optString("binding") != binding()) return "connection_changed"
        if (runCatching { validateDirectScope(job) }.isFailure) return "unsupported_direct_scope"
        if (gateway.isDirectMode() && DirectRuntime.get(context).hasUnfinishedRun()) return busy(active)
        val runs = gateway.request("GET", "/runs").getJSONArray("items")
        var activeStatus: String? = null
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.optString("id") == active) activeStatus = run.optString("status")
            if (run.optString("device_id") == job.getString("device_id") && run.optString("status") !in setOf("completed", "failed", "cancelled")) return busy(run.optString("id"))
        }
        // The gateway may return only its most recent runs; absence still requires a lookup.
        if (active.isNotBlank() && (activeStatus ?: gateway.request("GET", "/runs/$active").optString("status")) !in setOf("completed", "failed", "cancelled")) return busy(active)
        if (AutomaticUnlockSession.locked(context)) {
            val connection = AutomaticTaskNotice.connectionStamp(context)
            val started = AutomaticUnlockSession.prepare(context, unlockKey, valid = {
                val saved = runCatching { engine.get(job.getString("id")) }.getOrNull()
                saved?.optBoolean("enabled") == true && SchedulePromptOverlay.key(saved) == unlockKey &&
                    connection == AutomaticTaskNotice.connectionStamp(context)
            }, ready = { tick {} }, onAlreadyUnlocked = { tick {} })
            if (!started && !AutomaticUnlockSession.locked(context)) return if (manualDecision || ownGate) null else "schedule_countdown"
            return if (started) "device_unlocking" else "device_locked"
        }
        if (AutomaticUnlockSession.approved(unlockKey)) return null
        if (!manualDecision && !ownGate && !SchedulePromptOverlay.announced(job)) {
            return "schedule_countdown"
        }
        return null
    }
    private fun port(allowAutomaticUnlock: Boolean = true) = object : SchedulePort {
        private var ticket = 0L
        private var previousActive = ""
        private var ownsGate = false
        private var manualDecision = false
        private var connection = ""
        private var expectedJob: JSONObject? = null
        private var started = false
        override fun readiness(job: JSONObject): String? = try {
            manualDecision = job.has("manual_execute_due_ms") && job.optLong("manual_execute_due_ms") == job.optLong("next_due_ms")
            val reason = ready(job, manualDecision = manualDecision, allowAutomaticUnlock = allowAutomaticUnlock)
            if (reason == null) {
                ticket = AutomaticUnlockSession.generation(SchedulePromptOverlay.key(job)) ?: if (manualDecision) TaskControl.currentGeneration() else SchedulePromptOverlay.generation(job) ?: error("预告已取消")
                connection = AutomaticTaskNotice.connectionStamp(context)
                // ScheduleEngine advances next_due_ms before calling create(). Preserve the announced occurrence.
                expectedJob = JSONObject(job.toString())
            }
            reason
        } catch (_: Exception) { "device_offline" }
        override fun status(runId: String): String = gateway.runStatus(runId)
        override fun create(job: JSONObject): String {
            check(TaskSubmissionGate.creating.compareAndSet(false, true)) { "Another task is being created" }; ownsGate = true
            check(TaskControl.isCurrent(ticket) && connection == AutomaticTaskNotice.connectionStamp(context)) { "Automatic task context changed" }
            expectedJob?.let { AutomaticUnlockSession.dispatching(SchedulePromptOverlay.key(it)) }
            check(ready(expectedJob ?: error("Missing readiness check"), true, manualDecision, allowAutomaticUnlock) == null) { "Device readiness changed" }
            previousActive = gateway.prefs.getString("active_run", "").orEmpty()
            // Freeze an idle poller during creation so it cannot execute before publication.
            DeviceWorkerService.instance?.suspendLocally()
            check(TaskControl.isCurrent(ticket)) { "User took over before task creation" }
            check(job.optString("binding") == binding() && connection == AutomaticTaskNotice.connectionStamp(context) &&
                AutomaticTaskNotice.localBlockReason(context, true) == null && TaskControl.isCurrent(ticket)) { "Device changed before task creation" }
            val body = JSONObject().put("device_id", job.getString("device_id")).put("goal", job.getString("goal"))
                .put("mode", job.getString("mode")).put("allowed_packages", job.getJSONArray("allowed_packages"))
                // Schedules create executable task records only; they must
                // never appear in or advance the conversational thread.
                .put("conversation_enabled", false).put("source", "schedule")
            return gateway.request("POST", "/runs", body).getString("id")
        }
        override fun start(runId: String) {
            // A request may finish after the user locks the phone or changes the connection.
            if (connection != AutomaticTaskNotice.connectionStamp(context)) return
            synchronized(gateway.prefs) {
                check(gateway.prefs.getString("active_run", "").orEmpty() == previousActive) { "Active task changed" }
                check(gateway.prefs.edit().putString("active_run", runId).commit()) { "Task reference was not saved" }
            }
            expectedJob?.let { AutomaticUnlockSession.bindRun(SchedulePromptOverlay.key(it), runId) }
            if (!TaskControl.isCurrent(ticket) || AutomaticTaskNotice.localBlockReason(context, true) != null) {
                runCatching { gateway.request("POST", "/runs/$runId/pause") }
                return
            }
            try { check(TaskControl.startWorker(context, ticket)) { "User took over" }; started = true }
            catch (error: Exception) { runCatching { gateway.request("POST", "/runs/$runId/pause") }; throw error }
        }
        override fun finishDispatch() { release() }
        private fun release() {
            if (ownsGate) { ownsGate = false; TaskSubmissionGate.creating.set(false) }
            if (!started) expectedJob?.let { AutomaticUnlockSession.dispatchFailed(SchedulePromptOverlay.key(it)) }
        }
    }
    private fun validateDirectScope(payload: JSONObject) {
        if (!gateway.isDirectMode()) return
        require(payload.optString("goal").length <= 8000) { "本机直连任务目标最多 8000 字" }
        require((payload.optJSONArray("allowed_packages")?.length() ?: 0) == 0) { "本机直连尚不支持应用白名单，不能静默忽略此限制；请改用网关执行" }
    }
    private fun notifyWaiting() {
        val items = engine.list().getJSONArray("items")
        val prompt = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull {
            it.optBoolean("enabled") && it.optString("waiting_reason") in setOf("user_active", "schedule_countdown")
        }
        SchedulePromptOverlay.update(context, prompt)
        val waiting = (0 until items.length()).map { items.getJSONObject(it) }
            .any { it.optBoolean("enabled") && it.optString("waiting_reason") !in setOf("", "null", "device_busy") }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!waiting) { manager.cancel(JOB_ID); return }
        manager.createNotificationChannel(NotificationChannel("schedules", "定时任务", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, JOB_ID, Intent(context, ScheduleActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(JOB_ID, Notification.Builder(context, "schedules").setSmallIcon(UiIcons.history)
            .setContentTitle("定时任务正在等待").setContentText("请亮屏解锁，可选择执行、推迟或跳过。超时会记录错过原因。")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build())
    }
}
