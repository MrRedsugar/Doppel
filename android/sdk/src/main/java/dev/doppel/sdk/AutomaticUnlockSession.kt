package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An explicitly enabled local unlock session, never a system-level security boundary. */
internal object AutomaticUnlockSession {
    private enum class Phase { UNLOCKING, READY, RUNNING, AUTHENTICATING, LOCKING, RELOCK_FAILED }
    private class Session(val context: Context, val key: String, val valid: () -> Boolean, val ready: () -> Unit) {
        val generation = TaskControl.currentGeneration()
        val connection = AutomaticTaskNotice.connectionStamp(context)
        @Volatile var phase = Phase.UNLOCKING
        @Volatile var attemptUnconfirmed = true
        @Volatile var ending = false
        @Volatile var runId = ""
        @Volatile var ownsGate = true
        var controlSent = false
        var readyAt = 0L
        @Volatile var handoffAction: String? = null
        var handoffStartedAt = 0L
        var handoffInputAt = 0L
        var handoffFailures = 0
        var handoffAvailableAt = 0L
        var handoffReturnPhase = Phase.RUNNING
        var message = "正在自动执行"
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "doppel-local-unlock").apply { isDaemon = true } }
    @Volatile private var session: Session? = null
    private val operationLock = Object()
    private val operationDepth = ThreadLocal.withInitial { 0 }
    private var operations = 0
    val active get() = session != null
    val isUnlocking get() = session?.phase == Phase.UNLOCKING
    val isAuthenticating get() = session?.phase == Phase.AUTHENTICATING
    fun matches(key: String) = session?.key == key
    fun approved(key: String) = session?.let { it.key == key && it.phase == Phase.READY && live(it) } == true
    fun generation(key: String): Long? = session?.takeIf { it.key == key }?.generation
    private fun state(context: Context) = context.getSharedPreferences("doppel_automatic_unlock_state", Context.MODE_PRIVATE)
    fun locked(context: Context): Boolean {
        val lock = context.getSystemService(KeyguardManager::class.java)
        return lock.isDeviceLocked || lock.isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive
    }
    private fun live(owner: Session): Boolean {
        fun current() = session === owner && !owner.ending && TaskControl.isCurrent(owner.generation) &&
            owner.connection == AutomaticTaskNotice.connectionStamp(owner.context)
        if (!current()) return false
        val valid = owner.phase in setOf(Phase.RUNNING, Phase.AUTHENTICATING) || runCatching(owner.valid).getOrDefault(false)
        // The schedule can advance its occurrence and enter RUNNING while valid() waits for its lock.
        return current() && (valid || owner.phase in setOf(Phase.RUNNING, Phase.AUTHENTICATING))
    }

    @JvmOverloads
    fun prepare(context: Context, key: String, valid: () -> Boolean, ready: () -> Unit,
                onAlreadyUnlocked: () -> Unit = {}): Boolean {
        synchronized(this) {
            if (session != null) return matches(key)
            // Scheduler and service startup can race. Both must resolve persisted recovery first.
            if (!recoverPending(context, DoppelAccessibilityService.instance)) return false
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (!keyguard.isDeviceLocked || !AutomaticUnlockCredentials.isEnabled(context) || !keyguard.isDeviceSecure ||
                DoppelAccessibilityService.instance == null || !TaskSubmissionGate.creating.compareAndSet(false, true)) return false
            val owner = Session(context.applicationContext, key, valid, ready)
            session = owner
            // A killed/uncertain input attempt must never be replayed on the next scheduler tick.
            if (!state(context).edit().putBoolean("attempt", true).putBoolean("protected", true).commit()) {
                session = null; TaskSubmissionGate.creating.set(false); return false
            }
            main.post {
                val service = DoppelAccessibilityService.instance
                // A user who unlocked during dispatch must keep normal, unprotected manual control.
                if (!keyguard.isDeviceLocked) {
                    val retry = live(owner)
                    if (!confirmAttempt(owner)) return@post
                    clear(owner)
                    if (retry) onAlreadyUnlocked()
                    return@post
                }
                if (!live(owner) || service == null || !AutomaticRunShield.show(service, ::beginHandoff)) { fail("自动解锁准备失败"); return@post }
                service.setTouchGuard(false)
                AutomaticRunShield.update("正在本机解锁")
                try { owner.context.startActivity(Intent(owner.context, AutomaticUnlockWakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { fail("无法打开系统解锁界面"); return@post }
                main.post(watchdog)
                io.execute {
                    var credentialInput = false
                    val success = runCatching {
                        AutomaticUnlockCredentials.read(owner.context)?.use { credential ->
                            AutomaticScreenUnlocker.unlock(service, credential, onCredentialInput = { credentialInput = true }) {
                                live(owner) && owner.phase == Phase.UNLOCKING
                            }
                        } == true
                    }.getOrDefault(false)
                    main.post {
                        if (session !== owner || owner.ending || owner.phase != Phase.UNLOCKING) return@post
                        AutomaticUnlockWakeActivity.finishCurrent()
                        if (success && !credentialInput) {
                            val retry = live(owner)
                            if (!confirmAttempt(owner)) return@post
                            clear(owner)
                            if (retry) onAlreadyUnlocked()
                            return@post
                        }
                        if (!success || !live(owner) || locked(owner.context)) { fail("自动解锁未成功，已停用；请检查密码和设备兼容性"); return@post }
                        if (!confirmAttempt(owner)) return@post
                        owner.phase = Phase.READY; owner.readyAt = SystemClock.elapsedRealtime()
                        releaseGate(owner)
                        AutomaticRunShield.update("已解锁，正在准备任务")
                        if (live(owner)) owner.ready()
                    }
                }
            }
            return true
        }
    }

    fun bindRun(key: String, runId: String) {
        session?.takeIf { it.key == key && !it.ending && it.phase in setOf(Phase.READY, Phase.RUNNING) }?.let { it.runId = runId; it.phase = Phase.RUNNING }
    }
    fun dispatching(key: String) {
        session?.takeIf { it.key == key && !it.ending && it.phase == Phase.READY }?.let { it.phase = Phase.RUNNING }
    }
    fun dispatchFailed(key: String) { if (matches(key)) finish("任务未能启动，正在重新锁屏") }
    fun taskState(runId: String, status: String) {
        val owner = session ?: return
        if (owner.runId == runId && owner.phase in setOf(Phase.RUNNING, Phase.AUTHENTICATING) && status in setOf("completed", "failed", "cancelled", "paused")) {
            owner.controlSent = true
            finish("任务已结束或暂停，正在重新锁屏")
        }
    }
    /** Called off the main thread after exact-run termination; wait for the queued freeze/relock cleanup. */
    internal fun awaitRunCleanup(runId: String): Boolean {
        val owner = session?.takeIf { it.runId == runId } ?: return true
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (session === owner && owner.phase != Phase.RELOCK_FAILED && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        return session !== owner || owner.phase == Phase.RELOCK_FAILED
    }
    fun update(message: String) { main.post {
        session?.let { owner ->
            owner.message = message
            if (owner.phase == Phase.RUNNING && owner.handoffAction == null) AutomaticRunShield.update(message)
        }
    } }
    fun interrupted() {
        if (session?.phase in setOf(Phase.UNLOCKING, Phase.READY, Phase.RUNNING, Phase.AUTHENTICATING)) finish("执行已中断，正在重新锁屏")
    }
    fun updateProgress(run: org.json.JSONObject, locallyPaused: Boolean) {
        val owner = session ?: return
        if (run.optString("id") != owner.runId || owner.runId.isBlank()) return
        val snapshot = org.json.JSONObject().put("id", run.optString("id")).put("status", run.optString("status"))
        run.optJSONObject("task_state")?.optJSONObject("progress")?.let {
            snapshot.put("task_state", org.json.JSONObject().put("progress", org.json.JSONObject(it.toString())))
        }
        main.post {
            if (session === owner && snapshot.optString("id") == owner.runId &&
                owner.phase in setOf(Phase.RUNNING, Phase.AUTHENTICATING))
                AutomaticRunShield.updateProgress(snapshot, locallyPaused || owner.phase == Phase.AUTHENTICATING)
        }
    }
    fun fail(message: String) { finish(message) }
    private fun confirmAttempt(owner: Session): Boolean {
        if (session !== owner || owner.ending) return false
        if (!state(owner.context).edit().putBoolean("attempt", false).commit()) {
            finish("无法确认自动解锁状态，已停用，等待用户检查")
            return false
        }
        if (session !== owner || owner.ending) return false
        owner.attemptUnconfirmed = false
        return true
    }
    private fun suspendUnconfirmed(owner: Session): Boolean {
        if (!owner.attemptUnconfirmed) return true
        return runCatching { AutomaticUnlockCredentials.suspendAfterFailedAttempt(owner.context) }
            .onFailure { android.util.Log.w("DoppelAutoUnlock", "suspension_write_failed_recovery_retained") }.isSuccess
    }
    private fun releaseGate(owner: Session) {
        synchronized(owner) { if (owner.ownsGate) { owner.ownsGate = false; TaskSubmissionGate.creating.set(false) } }
    }
    private fun freeze(owner: Session, action: String = "pause") {
        TaskControl.invalidate()
        DeviceWorkerService.instance?.suspendLocally()
        if (!owner.controlSent && owner.runId.isNotBlank() && owner.connection == AutomaticTaskNotice.connectionStamp(owner.context)) {
            owner.controlSent = true
            val runId = owner.runId
            TaskControl.request(owner.context, runId, action) { run, error ->
                if (action == "cancel" && error == null && run?.optString("id") == runId && TaskPresentation.terminal(run.optString("status"))) {
                    val prefs = Gateway(owner.context).prefs
                    synchronized(prefs) {
                        if (owner.connection == AutomaticTaskNotice.connectionStamp(owner.context) && prefs.getString("active_run", "") == runId) {
                            DeviceWorkerService.instance?.acceptEndedRun(run)
                            // A suspended/absent worker may never poll this terminal status again.
                            if (prefs.getString("active_run", "") == runId) {
                                val edit = prefs.edit().remove("active_run")
                                if (prefs.getString("voice_pending_worker_run", "") == runId)
                                    edit.remove("voice_pending_worker_run").remove("voice_pending_worker_generation")
                                edit.commit()
                                PauseDetails.clear(owner.context, runId)
                            }
                        }
                    }
                }
            }
        }
    }
    private fun beginHandoff(action: String) {
        val owner = session ?: return
        if (action !in setOf("pause", "cancel") || owner.phase !in setOf(Phase.RUNNING, Phase.RELOCK_FAILED) || owner.handoffAction != null) return
        if (SystemClock.elapsedRealtime() < owner.handoffAvailableAt) {
            AutomaticRunShield.update("任务继续执行，请稍后再长按验证")
            return
        }
        synchronized(operationLock) { owner.handoffReturnPhase = owner.phase; owner.handoffAction = action }
        AutomaticRunShield.update("正在等待当前动作完成")
        showPendingHandoff(owner)
    }

    private fun lanHandoffAvailable(owner: Session): Boolean =
        !owner.attemptUnconfirmed && !owner.ending &&
            (owner.phase == Phase.RUNNING || owner.phase == Phase.AUTHENTICATING && owner.handoffReturnPhase == Phase.RUNNING) &&
            AutomaticUnlockCredentials.isLanHandoffEnabled(owner.context) && AutomaticUnlockCredentials.isEnabled(owner.context) &&
            !locked(owner.context) && SdkCompanionService.hasTrustedLanPresence(owner.context)

    private fun showPendingHandoff(owner: Session) {
        if (session !== owner || owner.phase !in setOf(Phase.RUNNING, Phase.RELOCK_FAILED) || owner.handoffAction == null) return
        synchronized(operationLock) {
            if (operations != 0) return
            owner.phase = Phase.AUTHENTICATING
        }
        // Recheck after the long hold AND after in-flight actions drain. Never cache approval at unlock.
        if (owner.handoffAction == "pause" && live(owner) && lanHandoffAvailable(owner)) {
            completeHandoff(owner)
            Toast.makeText(owner.context, "已通过附近电脑确认，已接管", Toast.LENGTH_SHORT).show()
            return
        }
        val kind = AutomaticUnlockCredentials.readSaved(owner.context)?.use { it.kind }
        owner.handoffStartedAt = SystemClock.elapsedRealtime()
        owner.handoffInputAt = owner.handoffStartedAt
        owner.handoffFailures = 0
        val shown = kind != null && AutomaticRunShield.showAuthentication(kind, owner.handoffAction ?: "pause",
            onSubmit = { value ->
                if (session === owner) submitHandoffPassword(value) else value.fill('\u0000')
            },
            onActivity = { if (session === owner) handoffInputActivity() },
            onCancel = { if (session === owner) authenticationCancelled() })
        if (!shown) { authenticationCancelled(); return }
        scheduleHandoffTimeout(owner)
    }

    fun handoffInputActivity() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = session?.takeIf { it.phase == Phase.AUTHENTICATING } ?: return
        owner.handoffInputAt = SystemClock.elapsedRealtime()
        scheduleHandoffTimeout(owner)
    }

    private fun scheduleHandoffTimeout(owner: Session) {
        main.removeCallbacks(handoffTimeout)
        val due = minOf(owner.handoffInputAt + 5000, owner.handoffStartedAt + 30000)
        val remaining = (due - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        AutomaticRunShield.authenticationCountdown(((remaining + 999) / 1000).toInt())
        main.postDelayed(handoffTimeout, minOf(1000, remaining))
    }

    private val handoffTimeout = Runnable {
        val owner = session?.takeIf { it.phase == Phase.AUTHENTICATING } ?: return@Runnable
        if (SystemClock.elapsedRealtime() >= minOf(owner.handoffInputAt + 5000, owner.handoffStartedAt + 30000)) authenticationCancelled()
        else scheduleHandoffTimeout(owner)
    }

    /** Compare only with the owner-provided encrypted credential, never with a model or system prompt. */
    fun submitHandoffPassword(value: CharArray) {
        check(Looper.myLooper() == Looper.getMainLooper())
        try {
            val owner = session?.takeIf { it.phase == Phase.AUTHENTICATING } ?: return
            val now = SystemClock.elapsedRealtime()
            if (now >= owner.handoffInputAt + 5000 || now >= owner.handoffStartedAt + 30000) {
                authenticationCancelled(); return
            }
            val matches = AutomaticUnlockCredentials.readSaved(owner.context)?.use { saved ->
                var difference = saved.value.size xor value.size
                for (i in 0 until maxOf(saved.value.size, value.size)) {
                    difference = difference or ((saved.value.getOrNull(i)?.code ?: 0) xor (value.getOrNull(i)?.code ?: 0))
                }
                difference == 0
            } == true
            if (matches) {
                completeHandoff(owner)
            } else {
                owner.handoffFailures++
                if (owner.handoffFailures >= 3) authenticationCancelled()
                else {
                    AutomaticRunShield.authenticationError("密码错误，还可尝试 ${3 - owner.handoffFailures} 次")
                    handoffInputActivity()
                }
            }
        } finally { value.fill('\u0000') }
    }

    private fun completeHandoff(owner: Session) {
        main.removeCallbacks(handoffTimeout)
        freeze(owner, owner.handoffAction ?: "pause")
        clear(owner)
    }

    fun authenticationCancelled() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = session?.takeIf { it.phase == Phase.AUTHENTICATING } ?: return
        main.removeCallbacks(handoffTimeout)
        if (!AutomaticRunShield.hideAuthentication()) { finish("触摸保护恢复失败，已停止任务"); return }
        AutomaticRunShield.update(if (owner.handoffReturnPhase == Phase.RELOCK_FAILED) "系统未能重新锁屏，请用电源键锁屏或长按验证后接管" else owner.message)
        owner.handoffAvailableAt = SystemClock.elapsedRealtime() + 10000
        synchronized(operationLock) {
            owner.handoffAction = null
            owner.phase = owner.handoffReturnPhase
            operationLock.notifyAll()
        }
        if (owner.phase == Phase.RUNNING) Toast.makeText(owner.context, "已返回任务", Toast.LENGTH_SHORT).show()
    }

    /** Wait without changing the run or its generation; only successful authentication stops it. */
    fun awaitHandoff(current: () -> Boolean): Boolean {
        if ((operationDepth.get() ?: 0) > 0) return current()
        try {
            synchronized(operationLock) {
                while (session?.let { it.handoffAction != null || it.phase == Phase.AUTHENTICATING } == true) {
                    if (Looper.myLooper() == Looper.getMainLooper() || !current()) return false
                    operationLock.wait(100)
                }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        return current()
    }

    /** Finish the current observation/action before showing secrets; nested observations reuse the lease. */
    fun deviceOperation(current: () -> Boolean): AutoCloseable? {
        while (true) {
            if (!awaitHandoff(current)) return null
            synchronized(operationLock) {
                val depth = operationDepth.get() ?: 0
                if (depth > 0 || session?.handoffAction == null) {
                    if (depth == 0) operations++
                    operationDepth.set(depth + 1)
                    var closed = false
                    return AutoCloseable {
                        if (!closed) {
                            closed = true
                            synchronized(operationLock) {
                                val remaining = (operationDepth.get() ?: 0) - 1
                                operationDepth.set(remaining)
                                if (remaining == 0) {
                                    operations--
                                    session?.let { owner -> main.post { showPendingHandoff(owner) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun finish(message: String) {
        val owner = session ?: return
        // Persist uncertainty before posting cleanup or changing phase; interruption must not retry.
        owner.ending = true
        suspendUnconfirmed(owner)
        main.post {
            if (session !== owner) return@post
            if (owner.phase == Phase.LOCKING) return@post
            owner.phase = Phase.LOCKING
            main.removeCallbacks(handoffTimeout)
            AutomaticUnlockWakeActivity.finishCurrent()
            freeze(owner)
            AutomaticRunShield.hideAuthentication()
            AutomaticRunShield.update(message)
            synchronized(operationLock) { owner.handoffAction = null; operationLock.notifyAll() }
            releaseGate(owner)
            relock(owner) { clear(owner) }
        }
    }
    private fun relock(owner: Session, after: () -> Unit) {
        val lock = owner.context.getSystemService(KeyguardManager::class.java)
        val service = DoppelAccessibilityService.instance
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        val deadline = SystemClock.elapsedRealtime() + 2000
        val poll = object : Runnable {
            override fun run() {
                if (session !== owner) return
                if (lock.isDeviceSecure && lock.isDeviceLocked) after()
                else if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 80)
                else {
                    // Keep the visible shield if the OS refused locking. This is still not a system lock.
                    AutomaticRunShield.update("系统未能重新锁屏。请用电源键锁屏；长按可再次验证身份。")
                    owner.phase = Phase.RELOCK_FAILED
                }
            }
        }
        main.post(poll)
    }
    @Synchronized private fun clear(owner: Session) {
        if (session !== owner) return
        main.removeCallbacks(watchdog)
        main.removeCallbacks(handoffTimeout)
        releaseGate(owner)
        // Never erase the last durable attempt marker if suspending it could not be saved.
        if (suspendUnconfirmed(owner)) clearRecovery(state(owner.context))
        AutomaticRunShield.hide()
        synchronized(operationLock) { session = null; operationLock.notifyAll() }
        DoppelAccessibilityService.instance?.setTouchGuard(false)
    }
    private val watchdog = object : Runnable {
        override fun run() {
            val owner = session ?: return
            AutomaticRunShield.setLanHandoffAvailable(lanHandoffAvailable(owner))
            if (owner.phase in setOf(Phase.UNLOCKING, Phase.READY, Phase.RUNNING) ||
                owner.phase == Phase.AUTHENTICATING && owner.handoffReturnPhase == Phase.RUNNING) {
                if (!live(owner) || DoppelAccessibilityService.instance == null ||
                    owner.runId.isBlank() && owner.phase != Phase.UNLOCKING && SystemClock.elapsedRealtime() - owner.readyAt > 30000 ||
                    owner.phase in setOf(Phase.RUNNING, Phase.AUTHENTICATING) && locked(owner.context)) {
                    finish("设备状态已改变，自动任务已停止"); return
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    /** Recovery can relock when the service restarts; it cannot protect the process-dead interval. */
    @Synchronized fun recover(service: DoppelAccessibilityService) {
        if (active) return
        recoverPending(service, service)
    }
    /** Called only under the session lock, before any new owner or password input can start. */
    private fun recoverPending(context: Context, service: DoppelAccessibilityService?): Boolean {
        val prefs = state(context)
        if (prefs.getBoolean("attempt", false) && runCatching { AutomaticUnlockCredentials.suspendAfterFailedAttempt(context) }
                .onFailure { android.util.Log.w("DoppelAutoUnlock", "recovery_suspension_write_failed") }.isFailure) return false
        if (prefs.getBoolean("protected", false)) {
            if (service == null) return false
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
            val locked = runCatching {
                val lock = service.getSystemService(KeyguardManager::class.java)
                lock.isDeviceSecure && lock.isDeviceLocked
            }.getOrDefault(false)
            if (!locked) {
                // Lock requests can be asynchronous or refused; retain recovery evidence until confirmed.
                android.util.Log.w("DoppelAutoUnlock", "recovery_relock_unconfirmed")
                return false
            }
        }
        return clearRecovery(prefs)
    }
    private fun clearRecovery(prefs: android.content.SharedPreferences): Boolean {
        val attempt = prefs.getBoolean("attempt", false)
        val wasProtected = prefs.getBoolean("protected", false)
        if (!attempt && !wasProtected) return true
        if (prefs.edit().remove("attempt").remove("protected").commit()) return true
        // commit() also changes the in-memory map on failure; keep this process blocked as well.
        prefs.edit().putBoolean("attempt", attempt).putBoolean("protected", wasProtected).commit()
        android.util.Log.w("DoppelAutoUnlock", "recovery_marker_clear_failed")
        return false
    }

    fun capturePass(): AutoCloseable? {
        val owner = session ?: return AutoCloseable { }
        if (owner.phase != Phase.RUNNING || Looper.myLooper() == Looper.getMainLooper()) return null
        val ready = CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        fun restore() { main.post {
            if (session === owner && !AutomaticRunShield.setCaptureHidden(false) && owner.phase == Phase.RUNNING)
                finish("保护画面恢复失败，已停止任务")
        } }
        main.post {
            if (!expired.get() && session === owner && owner.phase == Phase.RUNNING) ok.set(AutomaticRunShield.setCaptureHidden(true))
            ready.countDown()
        }
        try {
            if (!ready.await(2, TimeUnit.SECONDS) || !ok.get()) { expired.set(true); restore(); return null }
            Thread.sleep(80)
            if (session !== owner || owner.phase != Phase.RUNNING) { restore(); return null }
        } catch (_: InterruptedException) { expired.set(true); restore(); Thread.currentThread().interrupt(); return null }
        return AutoCloseable { restore() }
    }

    /** UI thread acknowledgment before gestures; restoration runs even after task cancellation. */
    fun gesturePass(): AutoCloseable? {
        if (!active) return AutoCloseable { }
        val owner = session ?: return AutoCloseable { }
        if (owner.phase != Phase.RUNNING || Looper.myLooper() == Looper.getMainLooper()) return null
        val ready = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val parked = java.util.concurrent.atomic.AtomicBoolean(false)
        val mainSeenAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
        // A gesture awaiting the user's hold is not in flight. Let authentication drain the other
        // operations, then restore this same lease; no retry/result/model call is introduced.
        synchronized(operationLock) {
            if ((operationDepth.get() ?: 0) > 0) { operations--; parked.set(true) }
        }
        fun unpark() { synchronized(operationLock) { if (parked.compareAndSet(true, false)) operations++ } }
        fun restore() { main.post {
            if (session === owner && !AutomaticRunShield.setPassing(false) && owner.phase == Phase.RUNNING)
                finish("触摸保护恢复失败，已停止任务")
        } }
        val tryPass = object : Runnable {
            override fun run() {
                mainSeenAt.set(SystemClock.elapsedRealtime())
                if (expired.get() || session !== owner || !TaskControl.isCurrent(owner.generation) ||
                    owner.phase !in setOf(Phase.RUNNING, Phase.AUTHENTICATING)) { ready.countDown(); return }
                if (owner.handoffAction != null) showPendingHandoff(owner)
                if (AutomaticRunShield.isHolding || owner.handoffAction != null || owner.phase == Phase.AUTHENTICATING) {
                    main.postDelayed(this, 50); return
                }
                unpark()
                ok.set(AutomaticRunShield.setPassing(true))
                ready.countDown()
            }
        }
        main.post(tryPass)
        var delivered = false
        try {
            while (!ready.await(100, TimeUnit.MILLISECONDS)) {
                if (session !== owner || !TaskControl.isCurrent(owner.generation) ||
                    SystemClock.elapsedRealtime() - mainSeenAt.get() >= 2000) return null
            }
            if (!ok.get()) return null
            Thread.sleep(80)
            if (session !== owner || owner.phase != Phase.RUNNING) return null
            delivered = true
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        finally {
            unpark()
            if (!delivered) {
                expired.set(true)
                main.removeCallbacks(tryPass)
                restore()
            }
        }
        return AutoCloseable { restore() }
    }
}
