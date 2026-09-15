package dev.doppel.sdk

import android.app.*
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.util.concurrent.Executors

class DeviceWorkerService : Service(), DeviceWorker {
    companion object {
        @Volatile var instance: DeviceWorkerService? = null
        @Volatile var state = "未连接"
        const val PAUSE = "dev.doppel.PAUSE"
        internal const val SUBMIT_VOICE = "dev.doppel.SUBMIT_VOICE"
    }
    @Volatile private var alive = true
    @Volatile private var paused = true
    @Volatile private var submittingVoice = false
    val isPaused: Boolean get() = paused
    internal fun allowsCredentialInput(runId: String): Boolean = runId.isNotBlank() && alive && !paused &&
        ::gateway.isInitialized && gateway.prefs.getString("active_run", "") == runId &&
        lastRun?.optString("id") == runId && lastRun?.optString("status") == "running"
    fun companionBounds(): android.graphics.Rect? = overlay?.bounds()
    internal fun beginCompanionGestureTouchPass(generation: Long, durationMs: Long, isCurrent: () -> Boolean): AutoCloseable? =
        overlay?.beginGestureTouchPass(generation, durationMs, isCurrent)
            ?: if (overlay == null && isCurrent()) AutoCloseable { } else null
    internal fun stopCompanionGestureTouchPass(invalidatedGeneration: Long) { overlay?.stopGestureTouchPass(invalidatedGeneration) }
    internal fun companionGestureTouchPassDiagnostic(): JSONObject = overlay?.gestureTouchPassDiagnostic() ?: JSONObject()
    private val screenshotCompanion = java.util.concurrent.atomic.AtomicReference<CompanionOverlay?>()
    private val screenshotOwner = ThreadLocal<CompanionOverlay?>()
    fun hideCompanionForScreenshot(): Boolean {
        if (::completion.isInitialized && !completion.hideBeforeCapture()) return false
        val current = overlay ?: return screenshotCompanion.get() == null
        if (!screenshotCompanion.compareAndSet(null, current)) return false
        screenshotOwner.set(current)
        val hidden = current.hideForScreenshot()
        if (!hidden) restoreCompanionAfterScreenshot()
        return hidden
    }
    fun restoreCompanionAfterScreenshot() {
        if (::completion.isInitialized) completion.setCaptureHidden(false)
        val captured = screenshotOwner.get() ?: return
        screenshotOwner.remove()
        if (screenshotCompanion.compareAndSet(captured, null)) captured.restoreAfterScreenshot()
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val submissionExecutor = Executors.newSingleThreadExecutor()
    private lateinit var gateway: Gateway
    private lateinit var completion: TaskCompletionDelivery
    @Volatile private var overlay: CompanionOverlay? = null
    private var overlayDensity = 0
    @Volatile var companionRevision = 0L; private set
    @Volatile private var lastRun: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?) = null
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::completion.isInitialized) completion.onConfigurationChanged()
        if (overlayDensity != newConfig.densityDpi && overlay != null) {
            closeCompanion(); createCompanion(); overlay?.display(lastRun, state)
        } else overlay?.onConfigurationChanged()
    }
    private fun createCompanion() {
        if (!alive || PaymentConsent.settingsVisible || overlay != null) return
        overlayDensity = resources.configuration.densityDpi
        overlay = CompanionOverlay(this) { pause() }.also {
            it.setEditorVisible(VoiceActivity.keyboardVisible)
            it.show()
        }
        // Publish only after installation so screenshot readers see this overlay with its revision.
        companionRevision++
    }
    private fun closeCompanion() {
        val previous = overlay ?: return
        overlay = null; companionRevision++
        previous.close()
    }
    internal fun voiceEditorVisibilityChanged() {
        fun reconcile() { if (alive) overlay?.setEditorVisible(VoiceActivity.keyboardVisible) }
        if (VoiceActivity.keyboardVisible && Looper.myLooper() == Looper.getMainLooper()) reconcile()
        else handler.post { reconcile() }
    }
    internal fun paymentSettingsVisibilityChanged() {
        fun reconcile() {
            if (!alive) return
            if (PaymentConsent.settingsVisible) {
                suspendLocally(); closeCompanion()
            } else {
                createCompanion(); overlay?.display(lastRun, state)
            }
        }
        // Defer restoration so an Activity recreation can register its replacement first.
        if (PaymentConsent.settingsVisible && Looper.myLooper() == Looper.getMainLooper()) reconcile()
        else handler.post { reconcile() }
    }
    override fun onCreate() {
        super.onCreate(); instance = this; gateway = Gateway(this); completion = TaskCompletionDelivery(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("device", "任务执行", NotificationManager.IMPORTANCE_LOW))
        startForeground(21, notification("已暂停"))
        if (!FirstUseConsent.isAccepted(this) || !ReleaseIntegrity.isTrusted(this)) { stopSelf(); return }
        UiTheme.init(this)
        createCompanion()
        executor.execute { loop() }
    }
    private fun notification(message: String): Notification {
        val open = Intent(this, TaskPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TaskPanelActivity.EXTRA_PAUSE_ON_OPEN, true)
        val pauseInfo = lastRun?.let { PausePresentation.from(PauseDetails.resolve(this, it, paused)) }
        val title = pauseInfo?.compact?.takeIf { it.isNotBlank() } ?: message.takeUnless { it == "已暂停" }
        val current = lastRun?.takeIf { it.optString("id").isNotBlank() && it.optString("id") == gateway.prefs.getString("active_run", "") }
        val labelled = current?.let { TaskPresentation.withSourceLabel(it, title.orEmpty()) } ?: title
        return Notification.Builder(this, "device").setContentTitle(if (labelled.isNullOrBlank()) "Doppel" else "Doppel · $labelled")
            .setContentText(pauseInfo?.surfaceReason?.take(160) ?: message.takeUnless { it == "已暂停" } ?: "等待任务")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setSmallIcon(if (pauseInfo != null) UiIcons.pause else R.drawable.doppel_ic_layers_2).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "暂停", PendingIntent.getService(this, 1, Intent(this, DeviceWorkerService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)).build()).build()
    }
    /** Publish a worker state to both the notification and the companion. Avoid repainting
     * the overlay on every long-poll tick when the state has not changed. */
    private fun update(message: String) {
        AutomaticUnlockSession.update(message)
        lastRun?.let { AutomaticUnlockSession.updateProgress(it, paused) }
        val changed = state != message
        state = message
        // Repeated thinking updates are deliberately quiet during long-polling;
        // other repeated states may carry a newly updated pause/result detail.
        if (changed || message != "正在思考") getSystemService(NotificationManager::class.java).notify(21, notification(message))
        overlay?.display(lastRun, message)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FirstUseConsent.isAccepted(this) || !ReleaseIntegrity.isTrusted(this)) {
            if (intent?.action == SUBMIT_VOICE) TaskSubmissionGate.creating.set(false)
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == PAUSE) pause()
        else if (intent?.action == SUBMIT_VOICE) submitVoice(intent)
        else if (intent?.hasExtra(TaskControl.EXTRA_GENERATION) != true || TaskControl.isCurrent(intent.getLongExtra(TaskControl.EXTRA_GENERATION, 0))) resume()
        return START_NOT_STICKY
    }
    fun interruptForSystem(reason: String) {
        if (paused) return
        stopWithReason(InterruptionPolicy.message(reason))
        val id = gateway.prefs.getString("active_run", "").orEmpty()
        if (id.isNotBlank()) TaskControl.request(this, id, "pause") { run, _ ->
            if (run != null && run.optString("status") == "paused" && gateway.prefs.getString("active_run", "") == id)
                showPausedRun(PauseDetails.resolve(this, run, true))
        }
    }
    private fun submitVoice(intent: Intent) {
        if (submittingVoice) return
        val goal = intent.getStringExtra("goal").orEmpty().trim()
        val ticket = intent.getLongExtra(TaskControl.EXTRA_GENERATION, 0)
        if (goal.isBlank() || !TaskControl.isCurrent(ticket)) {
            TaskSubmissionGate.creating.set(false); return
        }
        submittingVoice = true
        suspendLocally(); update("正在创建任务")
        submissionExecutor.execute {
            try {
                check(alive && TaskControl.isCurrent(ticket))
                check(gateway.prefs.getString("active_run", "").isNullOrBlank())
                val device = gateway.prefs.getString("device_id", "").orEmpty()
                check(device.isNotBlank())
                val mode = listOf("ask", "assist", "full")[gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)]
                val run = gateway.createConversationRun(JSONObject().put("device_id", device).put("goal", goal).put("mode", mode))
                TaskControl.reconcileCreatedRun(this, run, ticket) { reconciled, error ->
                    submittingVoice = false
                    TaskSubmissionGate.creating.set(false)
                    if (alive) {
                        if (reconciled != null) lastRun = reconciled
                        if (error == null && TaskControl.isCurrent(ticket)) resume()
                        else { suspendLocally(); if (error != null) update(error) }
                    }
                }
            } catch (_: Exception) {
                handler.post {
                    submittingVoice = false; TaskSubmissionGate.creating.set(false)
                    if (alive) update("创建未确认，草稿已保留")
                }
            }
        }
    }
    override fun pause() {
        if (paused && lastRun?.optString("status") == "paused") return
        suspendLocally()
        val runId = gateway.prefs.getString("active_run", "").orEmpty()
        if (runId.isNotEmpty()) TaskControl.request(this, runId, "pause") { run, _ ->
            if (run != null && run.optString("status") == "paused" && gateway.prefs.getString("active_run", "") == runId)
                showPausedRun(PauseDetails.resolve(this, run, true))
        }
        else TaskControl.invalidate()
    }
    fun suspendLocally() = suspendLocallyPreservingPauseNotice(false)
    internal fun suspendLocallyPreservingPauseNotice(preservePauseNotice: Boolean) {
        val wasPaused = paused
        paused = true
        val saved = runCatching { DirectRuntime.interrupt(this, "你已暂停任务，继续时将重新观察屏幕") }.isSuccess
        DoppelAccessibilityService.instance?.setTouchGuard(false)
        DoppelAccessibilityService.instance?.stopActionFeedback()
        LoginAssist.clearSession()
        // A repeat pause is an acknowledgement, not dismissal of its queued/visible explanation.
        if (::completion.isInitialized && !(wasPaused && preservePauseNotice)) completion.dismiss()
        val run = lastRun
        if (!wasPaused && run != null && run.optString("status") == "running" && gateway.prefs.getString("active_run", "") == run.optString("id")) {
            lastRun = JSONObject(run.toString()).put("status", "paused").put("message", "你已暂停任务，继续时将重新观察屏幕")
                .put("updated_at", System.currentTimeMillis())
            PauseDetails.remember(this, lastRun!!)
        }
        update(if (saved) "已暂停" else "已暂停，本机记录未保存")
    }
    private fun showPausedRun(run: JSONObject) {
        val id = run.optString("id")
        if (!alive || id.isBlank() || gateway.prefs.getString("active_run", "") != id) return
        lastRun = JSONObject(run.toString())
        suspendLocallyPreservingPauseNotice(true)
        PauseDetails.remember(this, run)
        update("已暂停")
        completion.deliverPauseIf(run) { alive && paused && gateway.prefs.getString("active_run", "") == id }
    }
    private fun stopWithReason(reason: String, takeover: String = "") {
        val id = gateway.prefs.getString("active_run", "").orEmpty()
        val previous = lastRun?.takeIf { it.optString("id") == id }
        val run = if (previous?.optString("status") == "paused" && !PausePresentation.generic(previous.optString("message"))) JSONObject(previous.toString())
            else JSONObject(previous?.toString() ?: "{}").put("id", id).put("status", "paused").put("message", reason)
                .put("updated_at", System.currentTimeMillis())
        if (previous?.optString("status") != "paused" && takeover in setOf("verification", "payment", "login", "interruption"))
            run.put("pending_request", JSONObject().put("reason", takeover))
        runCatching { DirectRuntime.interrupt(this, run.optString("message")) }
        if (id.isNotBlank()) showPausedRun(run) else { suspendLocally(); update(reason) }
    }
    private fun pauseForTakeover(result: JSONObject): Boolean {
        if (result.optString("run_id") != gateway.prefs.getString("active_run", "")) return false
        val reason = result.optJSONObject("data")?.optString("human_takeover").orEmpty()
        if (reason !in setOf("verification", "payment", "login", "interruption")) return false
        stopWithReason(result.optString("message").ifBlank { "当前步骤需要你手动处理，请核对手机后继续" }, reason)
        return true
    }
    override fun resume() {
        if (submittingVoice) return
        if (PaymentConsent.settingsVisible) { suspendLocally(); paymentSettingsVisibilityChanged(); return }
        completion.clearPause(gateway.prefs.getString("active_run", "").orEmpty())
        PauseDetails.clear(this)
        paused = false; update("等待任务")
    }
    override fun cancel() { TaskControl.invalidate(); suspendLocally(); update("已停止") }
    internal fun revealPauseControls(requestedId: String? = null): Boolean {
        val run = lastRun ?: return false
        val id = run.optString("id")
        if (requestedId != null && requestedId != id) return false
        return completion.revealPause(PauseDetails.resolve(this, run, paused)) {
            alive && paused && gateway.prefs.getString("active_run", "") == id
        }
    }
    internal fun acceptEndedRun(run: JSONObject) {
        val id = run.optString("id")
        if (!alive || id.isBlank() || gateway.prefs.getString("active_run", "") != id || !TaskPresentation.terminal(run.optString("status"))) return
        lastRun = run
        gateway.prefs.edit().remove("active_run").remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
        PauseDetails.clear(this, id); completion.clearPause(id)
        update("等待任务")
    }
    private fun loop() {
        val results = try { ResultStore(this) } catch (error: Exception) {
            logLoopFailure(WorkerLoopDiagnostic.Stage.RESULT_STORE, error)
            stopWithReason("命令存储不可用，任务已暂停"); return
        }
        val ledger = results.ledger
        var cleanupAt = 0L
        while (alive) {
            runCatching { ScheduleManager.get(this).tick() }
                .onFailure { logLoopFailure(WorkerLoopDiagnostic.Stage.SCHEDULE_TICK, it) }
            val device = gateway.prefs.getString("device_id", "").orEmpty()
            if (device.isNotEmpty() && System.currentTimeMillis() - cleanupAt > 30000) {
                cleanupAt = System.currentTimeMillis()
                var cleanupStage = WorkerLoopDiagnostic.Stage.CLEANUP_POLL
                try {
                    val cleanups = gateway.request("GET", "/devices/$device/data-cleanup").optJSONArray("items")
                    if (cleanups != null) for (i in 0 until cleanups.length()) {
                        cleanupStage = WorkerLoopDiagnostic.Stage.CLEANUP_LOCAL
                        val item = cleanups.getJSONObject(i); val ids = item.optJSONArray("command_ids")
                        results.erase(item.getString("run_id"), if (ids == null) emptySet() else (0 until ids.length()).map { ids.getString(it) }.toSet())
                        DoppelAccessibilityService.instance?.clearObservationHistory()
                        gateway.clearDocumentCache()
                        cleanupStage = WorkerLoopDiagnostic.Stage.CLEANUP_ACK
                        gateway.request("POST", "/devices/$device/data-cleanup/${item.getString("id")}/ack", JSONObject())
                    }
                } catch (error: Exception) {
                    logLoopFailure(cleanupStage, error)
                    // Deletion stays in the server outbox until a confirmed local purge.
                }
            }
            if (paused) { Thread.sleep(300); continue }
            var stage = WorkerLoopDiagnostic.Stage.CONTROL_STATE
            var directMode: Boolean? = null
            try {
                val generation = TaskControl.currentGeneration()
                if (!AutomaticUnlockSession.awaitHandoff { alive && !paused && TaskControl.isCurrent(generation) }) continue
                directMode = gateway.isDirectMode()
                if (device.isEmpty()) { paused = true; update("请先绑定设备"); continue }
                val activeRun = gateway.prefs.getString("active_run", "").orEmpty()
                if (activeRun.isNotBlank()) {
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_RUN_POLL
                    val active = gateway.request("GET", "/runs/$activeRun")
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_RUN_STATE
                    fun currentPoll() = alive && !paused && TaskControl.isCurrent(generation) && gateway.prefs.getString("active_run", "") == activeRun
                    if (!currentPoll() || active.optString("id") != activeRun) continue
                    lastRun = active
                    AutomaticUnlockSession.updateProgress(active, paused)
                    if (WorkerLoopDiagnostic.isRecovering(state)) update("等待任务")
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_OVERLAY
                    overlay?.display(active, state)
                    val running = active.optString("status") == "running"
                    val service = DoppelAccessibilityService.instance
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_FEEDBACK
                    if (!running) service?.stopActionFeedback()
                    val outsideClient = try { service?.foregroundPackage() != packageName } catch (_: Exception) { false }
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_TOUCH_GUARD
                    service?.setTouchGuard(running && outsideClient && gateway.prefs.getBoolean("touch_pause", true), ::currentPoll)
                    stage = WorkerLoopDiagnostic.Stage.ACTIVE_PAUSE
                    if (active.optString("status") == "paused") {
                        showPausedRun(PauseDetails.resolve(this, active, true))
                        AutomaticUnlockSession.taskState(activeRun, "paused")
                        continue
                    }
                    if (active.optString("status") in setOf("cancelled", "completed", "failed")) {
                        stage = WorkerLoopDiagnostic.Stage.ACTIVE_COMPLETION
                        val cleared = synchronized(gateway.prefs) {
                            if (!currentPoll()) false else { gateway.prefs.edit().remove("active_run").apply(); true }
                        }
                        if (!cleared) continue
                        service?.setTouchGuard(false)
                        LoginAssist.clearSession()
                        completion.deliverIf(active) { alive && !paused && TaskControl.isCurrent(generation) && gateway.prefs.getString("active_run", "").isNullOrBlank() }
                        update("等待任务")
                        // Relocking invalidates this poll; publish completion and clear ownership first.
                        AutomaticUnlockSession.taskState(activeRun, active.optString("status"))
                    }
                }
                if (!AutomaticUnlockSession.awaitHandoff { alive && !paused && TaskControl.isCurrent(generation) }) continue
                stage = WorkerLoopDiagnostic.Stage.COMMAND_POLL
                // An active run with no command currently available means the planner is
                // examining the latest observation. Keep this distinct from the brief
                // execution state published immediately before dispatching a command.
                if (gateway.prefs.getString("active_run", "").orEmpty().isNotBlank() &&
                    lastRun?.optString("status") == "running") update("正在思考")
                val polled = gateway.request("GET", "/devices/$device/commands?timeout=1")
                stage = WorkerLoopDiagnostic.Stage.COMMAND_STATE
                if (WorkerLoopDiagnostic.isRecovering(state)) update("等待任务")
                val command = polled.optJSONObject("command") ?: continue
                val id = command.getString("id")
                stage = WorkerLoopDiagnostic.Stage.LEDGER_READ
                val cached = ledger.cached(id)
                if (cached != null) {
                    val value = JSONObject(cached)
                    if (value.optJSONObject("data")?.optBoolean("acknowledged") == true) {
                        stage = WorkerLoopDiagnostic.Stage.CACHED_RESULT_POST
                        try { gateway.request("POST", "/devices/$device/results", value) } finally { stopWithReason("已确认命令被重复派发，已暂停") }
                    } else {
                        stage = WorkerLoopDiagnostic.Stage.CACHED_RESULT_POST
                        gateway.request("POST", "/devices/$device/results", value)
                        stage = WorkerLoopDiagnostic.Stage.RESULT_ACK
                        if (!results.acknowledge(id)) stopWithReason("本机结果清理失败，已暂停")
                    }
                    continue
                }
                stage = WorkerLoopDiagnostic.Stage.COMMAND_STATE
                val runId = command.getString("run_id")
                stage = WorkerLoopDiagnostic.Stage.COMMAND_RUN_POLL
                val run = gateway.request("GET", "/runs/$runId")
                stage = WorkerLoopDiagnostic.Stage.COMMAND_STATE
                if (paused || !alive || !TaskControl.isCurrent(generation) || run.optString("id") != runId || run.optString("status") != "running") continue
                // A visible companion/voice Activity owns the foreground until dismissed.
                if (TaskPanelActivity.isVisible || VoiceActivity.isVisible) { Thread.sleep(200); continue }
                gateway.prefs.edit().putString("active_run", runId).apply()
                val uncertain = JSONObject().put("command_id", id).put("run_id", runId).put("status", "error").put("message", "执行曾中断，结果不确定；禁止自动重放").put("data", JSONObject())
                stage = WorkerLoopDiagnostic.Stage.LEDGER_CLAIM
                if (!ledger.claim(id, uncertain.toString())) { stopWithReason("命令记录失败，已暂停"); continue }
                val result = if (paused || !alive || !TaskControl.isCurrent(generation)) uncertain.put("status", "cancelled").put("message", "派发前已暂停") else {
                    stage = WorkerLoopDiagnostic.Stage.ACTION_STATUS
                    update(when (command.optString("kind")) { "observe", "screenshot" -> "正在查看屏幕"; "visual_gesture" -> command.optJSONObject("gesture")?.optString("label")?.take(32)?.let { "正在操作：$it" } ?: "正在操作画面"; "tap" -> "正在点击"; "long_press" -> "正在长按"; "scroll" -> "正在滑动"; "type" -> "正在输入"; "launch" -> "正在打开应用"; else -> "正在执行" })
                    stage = WorkerLoopDiagnostic.Stage.ACTION_EXECUTE
                    DoppelAccessibilityService.instance?.execute(command) ?: uncertain.put("message", "无障碍服务未启用")
                }
                stage = WorkerLoopDiagnostic.Stage.LEDGER_FINISH
                if (!ledger.finish(id, result.toString())) { stopWithReason("结果保存失败，已暂停"); continue }
                // Keep the accepted receipt; a local password prompt must not wake another model request.
                AutomaticUnlockSession.awaitHandoff { alive && TaskControl.isCurrent(generation) }
                if (!alive) continue
                stage = WorkerLoopDiagnostic.Stage.RESULT_POST
                gateway.request("POST", "/devices/$device/results", result)
                // Capture the executor's reason before stopping the polling loop.
                stage = WorkerLoopDiagnostic.Stage.RESULT_TAKEOVER
                pauseForTakeover(result)
                stage = WorkerLoopDiagnostic.Stage.RESULT_ACK
                if (!results.acknowledge(id)) { stopWithReason("本机结果清理失败，已暂停"); continue }
                stage = WorkerLoopDiagnostic.Stage.IDLE_STATUS
                update(if (paused) "已暂停" else "等待任务")
            } catch (error: Exception) {
                logLoopFailure(stage, error)
                DoppelAccessibilityService.instance?.setTouchGuard(false)
                DoppelAccessibilityService.instance?.stopActionFeedback()
                update(if (paused) "已暂停" else WorkerLoopDiagnostic.recoveryMessage(directMode, stage, error))
                Thread.sleep(2000)
            }
        }
        results.close()
    }
    private fun logLoopFailure(stage: WorkerLoopDiagnostic.Stage, error: Throwable) {
        // Never pass the Throwable to Log: its message/cause can contain URLs, tokens or request content.
        android.util.Log.e("DoppelWorkerLoop", WorkerLoopDiagnostic.describe(stage, error))
    }
    override fun onDestroy() {
        AutomaticUnlockSession.interrupted()
        runCatching { DirectRuntime.interrupt(this, "设备服务已停止") }
        TaskControl.invalidate()
        alive = false; paused = true; DoppelAccessibilityService.instance?.setTouchGuard(false); DoppelAccessibilityService.instance?.stopActionFeedback(); LoginAssist.clearSession(); instance = null; handler.removeCallbacksAndMessages(null)
        closeCompanion()
        completion.close()
        executor.shutdown(); submissionExecutor.shutdown(); super.onDestroy()
    }
}

/** Fixed operation names and bounded JVM frame metadata; no exception messages or runtime payloads. */
internal object WorkerLoopDiagnostic {
    const val NETWORK_RECOVERY = "连接中断，等待重连"
    const val SERVICE_RECOVERY = "执行服务短暂异常，等待恢复"
    enum class Stage(val networkRequest: Boolean = false) {
        RESULT_STORE, SCHEDULE_TICK, CLEANUP_POLL(true), CLEANUP_LOCAL, CLEANUP_ACK(true), CONTROL_STATE,
        ACTIVE_RUN_POLL(true), ACTIVE_RUN_STATE, ACTIVE_OVERLAY, ACTIVE_FEEDBACK, ACTIVE_TOUCH_GUARD,
        ACTIVE_PAUSE, ACTIVE_COMPLETION, COMMAND_POLL(true), COMMAND_STATE, LEDGER_READ,
        CACHED_RESULT_POST(true), RESULT_ACK, COMMAND_RUN_POLL(true), LEDGER_CLAIM, ACTION_STATUS,
        ACTION_EXECUTE, LEDGER_FINISH, RESULT_POST(true), RESULT_TAKEOVER, IDLE_STATUS
    }
    fun isRecovering(state: String) = state == NETWORK_RECOVERY || state == SERVICE_RECOVERY
    fun recoveryMessage(directMode: Boolean?, stage: Stage, error: Throwable): String {
        var cause: Throwable? = error
        repeat(4) {
            if (directMode == false && stage.networkRequest && cause is java.io.IOException) return NETWORK_RECOVERY
            cause = cause?.cause
        }
        return SERVICE_RECOVERY
    }
    fun describe(stage: Stage, error: Throwable): String = buildString {
        append("stage=").append(stage.name)
        var cause: Throwable? = error
        repeat(3) { depth ->
            val current = cause ?: return@repeat
            append(if (depth == 0) " exception=" else "\ncause=").append(symbol(current.javaClass.name))
            current.stackTrace.take(6).forEach { frame ->
                append("\n at ").append(symbol(frame.className)).append('.').append(symbol(frame.methodName))
                    .append(':').append(frame.lineNumber)
            }
            cause = current.cause?.takeUnless { it === current || it === error }
        }
    }.take(3600)
    private fun symbol(value: String) = value.take(150).replace(Regex("[^A-Za-z0-9_.$<>]"), "_")
}
