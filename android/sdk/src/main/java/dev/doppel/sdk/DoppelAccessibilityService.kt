package dev.doppel.sdk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.WindowManager
import android.view.View
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class DoppelAccessibilityService : AccessibilityService(), ObservationProvider, ActionExecutor {
    companion object {
        @Volatile var instance: DoppelAccessibilityService? = null
        private val screenshotExecutor=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task,"doppel-screenshot-pixels").apply {isDaemon=true}
        }
    }
    private val refs = mutableMapOf<String, AccessibilityNodeInfo>()
    private val targetHistory = TargetHistory()
    private val notificationRecovery = NotificationRecovery()
    /** Event-driven local automation; deliberately runs only while no task is active. */
    private val autoTriggers by lazy { AutoTriggerEngine(this) }
    private val visualCaptures = VisualCaptureStore { android.os.SystemClock.elapsedRealtime() }
    private var latestSnapshot: TargetScreenSnapshot? = null
    @Volatile private var navigationGeneration = 0
    private var windowSignature = ""
    private val touchGuards = mutableListOf<View>()
    private val guardTouchPass = CompanionTouchPassState()
    private val guardPassDiagnostic = java.util.concurrent.atomic.AtomicReference<TouchHandoffDiagnostic?>()
    private var guardRequested = false
    private val ownGestureGeneration = java.util.concurrent.atomic.AtomicLong(-1)
    private var guardedCompanion: Rect? = null
    private val feedback by lazy { ActionFeedbackOverlay(this) }
    private val actionGeneration = visualCaptures.generation
    private val sensitiveFingerprintSalt = java.util.UUID.randomUUID().toString()
    private val feedbackSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "action_feedback" && !prefs.getBoolean(key, true)) prefs.edit().putBoolean("action_feedback", true).apply()
    }
    @Volatile private var privateCaptureBounds: List<List<Int>> = emptyList()
    val feedbackVisible: Boolean get() = feedback.visible
    @Volatile var guardVisible = false
        private set
    private val mainHandler = androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper())
    private var modeListener: Any? = null
    private fun callInProgress() = getSystemService(android.media.AudioManager::class.java).mode in setOf(
        android.media.AudioManager.MODE_RINGTONE, android.media.AudioManager.MODE_IN_CALL, android.media.AudioManager.MODE_IN_COMMUNICATION)
    private fun shellBridgeReady(): Boolean = ShellBridgeIntegration.ready(ShellBridgeClient.get(this).status(false))
    override fun onServiceConnected() {
        instance = this; getSharedPreferences("doppel", MODE_PRIVATE).edit().putBoolean("action_feedback", true).apply(); getSharedPreferences("doppel", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(feedbackSettingsListener)
        AutomaticUnlockSession.recover(this)
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = android.media.AudioManager.OnModeChangedListener { if (callInProgress()) DeviceWorkerService.instance?.interruptForSystem("call") }
            getSystemService(android.media.AudioManager::class.java).addOnModeChangedListener(mainExecutor, listener)
            modeListener = listener
        }
    }
    override fun onDestroy() {
        if (AutomaticUnlockSession.active) { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); AutomaticUnlockSession.interrupted() }
        DemonstrationSession.cancel()
        AccessibilityControlPicker.stop()
        if (Build.VERSION.SDK_INT >= 31) (modeListener as? android.media.AudioManager.OnModeChangedListener)?.let { getSystemService(android.media.AudioManager::class.java).removeOnModeChangedListener(it) }
        getSharedPreferences("doppel", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(feedbackSettingsListener); stopActionFeedback(); visualCaptures.clear(); LoginAssist.clearSession(); setTouchGuard(false); targetHistory.clear(); instance = null; super.onDestroy()
    }
    override fun onInterrupt() { logTouchPause("service_interrupt"); DeviceWorkerService.instance?.pause() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ownOverlayState = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == packageName && windows.any {
                it.id == event.windowId && (it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || ownOverlayWindow(it))
            }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !ownOverlayState) {
            navigationGeneration++
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val signature = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && !ownOverlayWindow(it) }.joinToString { window ->
                val rect = Rect(); window.getBoundsInScreen(rect)
                "${window.id}:${window.isFocused}:${window.isActive}:$rect"
            }
            if (windowSignature.isNotEmpty() && signature != windowSignature) {
                navigationGeneration++
            }
            windowSignature = signature
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val reason = InterruptionPolicy.reason(event.packageName?.toString().orEmpty(), event.text.map { it.toString() })
            if (reason != null) DeviceWorkerService.instance?.interruptForSystem(reason)
        }
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && !AutomaticUnlockSession.active) { logTouchPause("accessibility_touch_start"); navigationGeneration++; targetHistory.clear(); stopActionFeedback(); DeviceWorkerService.instance?.pause() }
        // Keep this after interruption detection so a system dialog/call always wins.
        // AutoTriggerEngine is node-only and does not issue model/network requests.
        runCatching { autoTriggers.onEvent(event) }
        // Application learning was removed from the product flow. Keep legacy
        // demonstration state cancellable on service shutdown, but never collect
        // new screenshots or accessibility traces at runtime.
    }
    /** Current application/system root, preferring the focused non-overlay window.
     * Android 14 can make rootInActiveWindow point at an accessibility overlay;
     * local automation must inspect the focused application window instead. */
    internal fun activeRoot(): AccessibilityNodeInfo? {
        fun read(): AccessibilityNodeInfo? {
            val current = windows.sortedByDescending { it.layer }
            for (window in current) {
                if (window.isFocused && window.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM))
                    window.root?.let { return it }
            }
            for (window in current) {
                if (window.isActive && window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
                    window.root?.let { return it }
            }
            return rootInActiveWindow
        }
        read()?.let { return it }
        // A window transition can leave Android's accessibility cache without a root.
        // Refresh it once, without replaying an action or asking the model again.
        if (Build.VERSION.SDK_INT >= 33 && clearCache()) {
            return read().also {
                android.util.Log.i("DoppelFrameReadiness", "root_cache_refresh recovered=${it != null}")
            }
        }
        return null
    }
    fun foregroundPackage(): String = activeRoot()?.packageName?.toString().orEmpty()
    fun stopActionFeedback() {
        val invalidatedGeneration = visualCaptures.stopFeedback()
        feedback.clear(); DeviceWorkerService.instance?.stopCompanionGestureTouchPass(invalidatedGeneration)
        mainHandler.post { guardTouchPass.clearBefore(invalidatedGeneration) }
    }
    private fun logTouchPause(source: String) {
        android.util.Log.i("DoppelTouchPause", "source=$source elapsed_ms=${android.os.SystemClock.elapsedRealtime()} generation=${actionGeneration.get()} own_injection=${ownGestureGeneration.get() >= 0}")
    }
    @Synchronized fun clearObservationHistory() { targetHistory.clear(); refs.clear(); latestSnapshot = null; visualCaptures.clear() }
    private fun displayGeometry(): Triple<Int, Int, Int> {
        val manager = getSystemService(WindowManager::class.java)
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(size)
        @Suppress("DEPRECATION")
        val rotation = manager.defaultDisplay.rotation
        return Triple(size.x, size.y, rotation)
    }
    fun setTouchGuard(enabled: Boolean) = setTouchGuard(enabled) { true }
    internal fun setTouchGuard(enabled: Boolean, isCurrent: () -> Boolean) {
        mainHandler.post {
            if (!isCurrent()) return@post
            if (AutomaticUnlockSession.active) { guardRequested = false; removeTouchGuardsImmediately(); return@post }
            guardRequested = enabled
            if (enabled && guardTouchPass.passing) return@post
            val manager = getSystemService(WindowManager::class.java)
            val companion = if (enabled) DeviceWorkerService.instance?.companionBounds() else null
            if (enabled && touchGuards.isNotEmpty() && companion == guardedCompanion) return@post
            if (!removeTouchGuardsImmediately()) {
                stopActionFeedback()
                DeviceWorkerService.instance?.takeIf { !it.isPaused }?.pause()
                return@post
            }
            guardedCompanion = companion
            if (!enabled) {
                guardedCompanion = null
            } else {
                val displayBounds = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds else {
                    val size = android.graphics.Point()
                    @Suppress("DEPRECATION")
                    manager.defaultDisplay.getRealSize(size)
                    Rect(0, 0, size.x, size.y)
                }
                val width = displayBounds.width()
                val height = displayBounds.height()
                val cutout = companion?.let { Rect(it).apply { intersect(0, 0, width, height) } }
                // Accessibility windows are above application overlays. Leave the assistant
                // entry reachable while the surrounding screen still triggers takeover.
                val regions = if (cutout == null || cutout.isEmpty) listOf(Rect(0, 0, width, height)) else listOf(
                    Rect(0, 0, width, cutout.top), Rect(0, cutout.bottom, width, height),
                    Rect(0, cutout.top, cutout.left, cutout.bottom), Rect(cutout.right, cutout.top, width, cutout.bottom))
                for (region in regions.filter { !it.isEmpty }) {
                val guard = View(this).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) { logTouchPause("guard_down"); navigationGeneration++; targetHistory.clear(); DeviceWorkerService.instance?.pause(); setTouchGuard(false) }
                        true
                    }
                }
                val params = WindowManager.LayoutParams(region.width(), region.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT; x = region.left; y = region.top
                    // Regions use physical display coordinates, including system bars and cutouts.
                    if (Build.VERSION.SDK_INT >= 30) {
                        setFitInsetsTypes(0)
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else if (Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                try { manager.addView(guard, params); touchGuards.add(guard) } catch (_: Exception) {
                    removeTouchGuardsImmediately(); stopActionFeedback(); DeviceWorkerService.instance?.pause(); break
                }
                }
                guardVisible = touchGuards.isNotEmpty()
            }
        }
    }
    /** Runs on the main thread. A failed removal remains tracked and must prevent injection. */
    private fun removeTouchGuardsImmediately(): Boolean {
        val manager = getSystemService(WindowManager::class.java)
        var removed = true
        for (guard in touchGuards.toList()) {
            try { manager.removeViewImmediate(guard) } catch (_: Exception) { removed = false }
            if (guard.isAttachedToWindow) removed = false else touchGuards.remove(guard)
        }
        guardVisible = touchGuards.isNotEmpty()
        if (!guardVisible) guardedCompanion = null
        return removed && !guardVisible
    }

    /** No gesture is dispatched until the old guard windows detach and the compositor settles. */
    internal fun beginGuardGestureTouchPass(generation: Long, durationMs: Long, isCurrent: () -> Boolean): AutoCloseable? {
        if (AutomaticUnlockSession.active) return if (isCurrent()) AutomaticUnlockSession.gesturePass() else null
        val diagnostic=TouchHandoffDiagnostic(android.os.SystemClock::elapsedRealtime)
        guardPassDiagnostic.set(diagnostic)
        fun finish(outcome:String) {diagnostic.finish(outcome);android.util.Log.i("DoppelGuardPass",diagnostic.snapshot().toString())}
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {finish("main_thread");return null}
        val gate=TouchHandoffGate()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val ticket = java.util.concurrent.atomic.AtomicReference<CompanionTouchPassState.Ticket?>()
        val restore = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release(owner: CompanionTouchPassState.Ticket) {
            if (guardTouchPass.release(owner) && restore.get() && guardRequested && isCurrent() && actionGeneration.get() == generation)
                setTouchGuard(true) { isCurrent() && actionGeneration.get() == generation }
            diagnostic.mark("released")
        }
        val queued=mainHandler.post {
            diagnostic.mark("main_entered")
            fun reject(outcome:String) {diagnostic.finish(outcome);gate.reject()}
            if (abandoned.get() || !isCurrent()) {reject("stale_host");return@post}
            diagnostic.mark("host_checked")
            val owner = guardTouchPass.acquire(generation, actionGeneration.get()) ?: run {reject("ticket_busy");return@post}
            ticket.set(owner)
            diagnostic.mark("ticket_acquired")
            restore.set(guardVisible || touchGuards.isNotEmpty())
            val oldViews = touchGuards.toList()
            diagnostic.mark("layout_started")
            if (!removeTouchGuardsImmediately() || oldViews.any {it.isAttachedToWindow} || touchGuards.isNotEmpty()) {
                reject("layout_failed");release(owner);return@post
            }
            diagnostic.mark("layout_applied")
            mainHandler.postDelayed({ release(owner) }, durationMs.coerceIn(3000, 6000))
            if(abandoned.get() || !isCurrent() || actionGeneration.get()!=generation || !guardTouchPass.owns(owner)) {
                reject("stale_host");release(owner);return@post
            }
            // removeViewImmediate detaches the old input windows synchronously;
            // composition can settle off-main without another delayed UI callback.
            diagnostic.mark("main_acknowledged");gate.applied()
        }
        if(!queued) {finish("queue_rejected");return null}
        fun stillCurrent()= !abandoned.get() && isCurrent() && actionGeneration.get()==generation &&
            ticket.get()?.let(guardTouchPass::owns)!=false
        val acquired=try {gate.await(::stillCurrent) {diagnostic.mark("compositor_wait_started")}}
            catch (_:InterruptedException) {diagnostic.finish("interrupted");Thread.currentThread().interrupt();TouchHandoffGate.Result.CANCELLED}
        if(acquired!=TouchHandoffGate.Result.READY || !stillCurrent()) {
            abandoned.set(true)
            finish(if(acquired==TouchHandoffGate.Result.TIMED_OUT) "timeout" else "stale_host")
            ticket.get()?.let { owner -> diagnostic.mark("release_queued");mainHandler.post { release(owner) } }
            return null
        }
        diagnostic.mark("compositor_wait_finished");diagnostic.mark("ready");finish("ready")
        return AutoCloseable {
            abandoned.set(true)
            ticket.get()?.let { owner -> diagnostic.mark("release_queued");mainHandler.post { release(owner) } }
        }
    }
    internal fun guardGestureTouchPassDiagnostic():JSONObject=guardPassDiagnostic.get()?.snapshot()?:JSONObject()
    @Synchronized override fun observe(): JSONObject {
        val executionGeneration = actionGeneration.get()
        val taskGeneration = TaskControl.currentGeneration()
        val operation = AutomaticUnlockSession.deviceOperation {
            actionGeneration.get() == executionGeneration && TaskControl.isCurrent(taskGeneration) &&
                instance === this && !Thread.currentThread().isInterrupted
        } ?: throw ScreenReadInterruptedException()
        try {
        if (AutomaticUnlockSession.isUnlocking || AutomaticUnlockSession.isAuthenticating) throw ScreenNotReadyException()
        refs.clear()
        val generation = navigationGeneration
        val root = activeRoot() ?: throw ScreenNotReadyException()
        val pkg = root.packageName?.toString().orEmpty()
        val credentialLabels = CredentialVault(this).taskLabels(pkg)
        val privateSettings = pkg == packageName && (LoginAssist.settingsVisible || DirectMode.settingsVisible)
        val nodes = JSONArray()
        val hiddenInputState = StringBuilder()
        val capabilityState = StringBuilder()
        val targetNodes = mutableListOf<TargetNodeSnapshot>()
        val sensitiveBounds = mutableListOf<List<Int>>()
        var complete = true
        var characters = 0
        fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (!node.isVisibleToUser) return
            if (depth > 30 || nodes.length() >= 300 || characters >= 24000) { complete = false; return }
            val rect = Rect(); node.getBoundsInScreen(rect)
            val hint = node.hintText?.toString().orEmpty()
            val inputLabel = "$hint ${node.contentDescription?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}"
            val otpInput = node.isEditable && Policy.codeInput(inputLabel)
            val phoneInput = node.isEditable && Policy.phoneInput(inputLabel)
            val loginInput = otpInput || phoneInput
            val ownLabels = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                hint, if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else "")
            val otpEcho = DeviceReadPrivacy.codeNotification("", ownLabels.joinToString(" "))
            if (node.isPassword || loginInput || otpEcho || ownLabels.any(LoginAssist::containsPrivateValue)) {
                if (!rect.isEmpty) sensitiveBounds.add(listOf(rect.left, rect.top, rect.right, rect.bottom))
            }
            val hiddenInput = node.isPassword || privateSettings && node.isEditable || otpEcho
            val text = if (hiddenInput || loginInput) "" else LoginAssist.redact(pkg, node.text?.toString().orEmpty()).take(400)
            val redactedDescription = LoginAssist.redact(pkg, node.contentDescription?.toString().orEmpty()).let { if (loginInput) Policy.redactLoginLabel(it) else it }
            val description = when {
                hiddenInput -> ""
                otpInput && !Policy.codeInput(redactedDescription) -> "登录验证码"
                phoneInput && !Policy.phoneInput(redactedDescription) -> "手机号"
                else -> redactedDescription.take(400)
            }
            val stateDescription = if (hiddenInput || loginInput || Build.VERSION.SDK_INT < 30) "" else
                LoginAssist.redact(pkg, node.stateDescription?.toString().orEmpty()).take(400)
            characters += text.length + description.length + stateDescription.length
            val id = "n" + path
            if (hiddenInput || loginInput) {
                // Preserve freshness without publishing a brute-forceable digest of a short OTP.
                hiddenInputState.append(id).append(':').append(Policy.hash("$sensitiveFingerprintSalt|${node.text?.toString().orEmpty()}|${node.contentDescription?.toString().orEmpty()}|$hint"))
            }
            refs[id] = node
            if (node.isScrollable) capabilityState.append(id).append('=').append(ScrollCapabilities.directions(node.actionList.map { it.id }.toSet())).append(';')
            targetNodes.add(TargetNodeSnapshot(id, listOf(rect.left, rect.top, rect.right, rect.bottom), text, description,
                if (hiddenInput) "" else LoginAssist.redact(pkg, hint).let { if (loginInput) Policy.redactLoginLabel(it) else it }.take(400), node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), node.isClickable, node.isEditable, node.isEnabled, node.isPassword, node.isScrollable, node.childCount,
                node.isCheckable, node.isChecked, node.isSelected, stateDescription))
            nodes.put(JSONObject().put("id", id).put("text", text).put("description", description)
                .put("parent_id", if (depth == 0) JSONObject.NULL else "n" + path.substringBeforeLast('_'))
                .put("role", if (node.isEditable) "input" else if (node.isClickable || node.isLongClickable) "button" else node.className?.toString().orEmpty())
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                .put("clickable", node.isClickable).put("long_clickable", node.isLongClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
                .put("scrollable", node.isScrollable).put("password", node.isPassword).put("resource_id", node.viewIdResourceName.orEmpty())
                .put("checkable", node.isCheckable).put("checked", node.isChecked).put("selected", node.isSelected).put("focused", node.isFocused)
                .put("state_description", stateDescription).apply {
                    node.collectionInfo?.let { collection ->
                        put("collection_info", JSONObject().put("row_count", collection.rowCount).put("column_count", collection.columnCount)
                            .put("hierarchical", collection.isHierarchical).put("selection_mode", collection.selectionMode))
                    }
                    node.collectionItemInfo?.let { item ->
                        put("collection_item_info", JSONObject().put("row_index", item.rowIndex).put("row_span", item.rowSpan)
                            .put("column_index", item.columnIndex).put("column_span", item.columnSpan)
                            .put("heading", item.isHeading).put("selected", item.isSelected))
                    }
                })
            if (node.childCount > 300) complete = false
            for (i in 0 until minOf(node.childCount, 300)) {
                val child = node.getChild(i)
                if (child == null) complete = false else walk(child, "${path}_$i", depth + 1)
            }
        }
        walk(root, "0", 0)
        if (pkg == "com.android.systemui") {
            // Native notification titles and bodies are often separate siblings. Resolve each
            // card locally before exporting nodes so neither those fields nor its pixels leak.
            targetNodes.forEachIndexed { index, node -> nodes.getJSONObject(index).put("class_name", node.className) }
            val notifications = SystemUiNotificationPrivacy.find(nodes, sensitiveBounds)
            repeat(nodes.length()) { index ->
                val node = nodes.getJSONObject(index)
                node.remove("class_name")
                if (node.optString("id") in notifications.nodeIds) {
                    hiddenInputState.append(node.optString("id")).append(':').append(Policy.hash("$sensitiveFingerprintSalt|$node"))
                    listOf("text", "description", "hint", "state_description").forEach { node.put(it, "") }
                    targetNodes[index] = targetNodes[index].copy(text = "", description = "", hint = "", stateDescription = "")
                }
            }
            sensitiveBounds.addAll(notifications.bounds)
        }
        privateCaptureBounds = sensitiveBounds.distinct()
        if (shellBridgeReady()) {
            ShellBridgeIntegration.attachIme(nodes,pkg,ShellBridgeImeService.capability())
        }
        val metrics = resources.displayMetrics
        val paymentConsentId = PaymentConsent(this).currentId()
        val screenId = Policy.hash("$pkg|${root.windowId}|$generation|${metrics.widthPixels}|${metrics.heightPixels}|$nodes|$hiddenInputState|$capabilityState|${paymentConsentId.orEmpty()}")
        val snapshot = TargetScreenSnapshot(screenId, pkg, root.windowId, generation, metrics.widthPixels, metrics.heightPixels,
            android.os.SystemClock.elapsedRealtime(), targetNodes.toList(), complete && generation == navigationGeneration)
        latestSnapshot = snapshot; targetHistory.remember(snapshot)
        val observation = JSONObject().put("screen_id", screenId)
            .put("assistant_surface", pkg == packageName)
            .put("package_name", pkg).put("width", metrics.widthPixels).put("height", metrics.heightPixels)
            .put("nodes", nodes).put("tree_complete", snapshot.complete).put("captured_at", System.currentTimeMillis())
            .put("payment_consent_id", paymentConsentId ?: JSONObject.NULL)
        observation.put("login_credentials", credentialLabels)
        observation.put("login_assist", LoginAssist(this).taskStatus(pkg, getSharedPreferences("doppel", MODE_PRIVATE).getString("active_run", null)))
        ObservationCoverage.collectionEvidence(observation)?.let { observation.put("collection_evidence", it) }
        // Match the existing screenshot privacy barrier, but never turn private settings into a usable page.
        return ScreenCapturePrivacy.attach(observation, privateSettings)
        } finally { operation.close() }
    }
    @Synchronized override fun execute(command: JSONObject): JSONObject {
        fun result(status: String, message: String = "", observation: JSONObject? = null, data: JSONObject = JSONObject()) =
            JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
                .put("status", status).put("message", message).put("observation", observation ?: JSONObject.NULL)
                .put("data", data.apply { if (observation != null) put("scroll_directions", scrollDirections(observation)) })
        try {
            val executionGeneration = actionGeneration.get()
            val taskGeneration = TaskControl.currentGeneration()
            val operation = AutomaticUnlockSession.deviceOperation {
                actionGeneration.get() == executionGeneration && TaskControl.isCurrent(taskGeneration) &&
                    instance === this && !Thread.currentThread().isInterrupted
            } ?: return result("cancelled", "执行已暂停")
            operation.use {
            val readiness = ScreenReadyWait(android.os.SystemClock::elapsedRealtime, Thread::sleep,
                { readInterrupted(executionGeneration) }, if (command.optBoolean("split_agent")) 2000 else 5000)
            val kind = command.getString("kind")
            if (callInProgress()) {
                stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                return result("blocked", InterruptionPolicy.message("call"), data = JSONObject().put("human_takeover", "interruption").put("interruption", "call"))
            }
            // Recover only during an authorized task command, never from the public observe() reader.
            val recovery = if (!command.optBoolean("split_agent") && kind != "screenshot" && kind !in setOf("notifications", "quick_settings") &&
                !PaymentConsent.settingsVisible && !readInterrupted(executionGeneration))
                notificationRecovery.attempt(this, command.getString("run_id")) {
                    !readInterrupted(executionGeneration) && !callInProgress()
                } else false
            if (recovery) {
                targetHistory.clear()
                if (kind != "observe") return result("stale", "已请求收起遮挡通知，重新观察后定位，旧动作未执行", readiness.read(::observe),
                    JSONObject().put("notification_recovery", "dismiss_requested"))
            }
            if (kind == "screenshot") return screenshot(command, readiness, executionGeneration)
            if (kind == "list_apps") {
                val query = command.opt("query") as? String ?: ""
                if (query.length > 120) return result("error", "应用查询内容过长")
                val apps = DeviceEnvironment.launchableApps(this, query)
                if (readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration)) return result("cancelled", "读取应用列表已中断")
                return result("ok", "已读取可启动应用", data = apps)
            }
            if (kind in SplitAgentProtocol.readActions) {
                val read = DeviceReadTools.execute(this, kind, command) {
                    !readInterrupted(executionGeneration) && TaskControl.isCurrent(taskGeneration)
                }
                return result(read.optString("status", "error"), read.optString("message"),
                    data = read.optJSONObject("data") ?: JSONObject())
            }
            if (kind == "observe") {
                val observation = readiness.read(::observe)
                val omitPrivateScreenshot = command.optBoolean("include_screenshot") && ScreenCapturePrivacy.unavailable(observation)
                if (command.optBoolean("include_screenshot") && !omitPrivateScreenshot) {
                    val shot = screenshot(command, readiness, executionGeneration)
                    if (readInterrupted(executionGeneration)) return result("cancelled", "屏幕读取已中断")
                    val after = shot.optJSONObject("observation")
                    if (shot.optString("status") == "ok" && (capturePrivateScreen() || ScreenCapturePrivacy.unavailable(after)))
                        return result("blocked", "登录资料与模型连接设置期间不上传截图", data = JSONObject().put("human_takeover", "login"))
                    // screenshot owns its before/pixel/after association. A third tree read must not replace that source.
                    if (after != null) (shot.optJSONObject("data") ?: JSONObject().also { shot.put("data", it) })
                        .put("scroll_directions", scrollDirections(after))
                    return shot
                }
                val apps = DeviceEnvironment.launchableApps(this).getJSONArray("apps")
                val labels = (0 until observation.getJSONArray("nodes").length()).map { observation.getJSONArray("nodes").getJSONObject(it).optString("text") }
                val interruption = InterruptionPolicy.reason(observation.optString("package_name"), labels)
                val bridge = ShellBridgeClient.get(this).status()
                val shellReady = ShellBridgeIntegration.ready(bridge)
                val captureAvailable = shellReady || Build.VERSION.SDK_INT >= 30 || LegacyScreenCaptureService.isReady
                val profile = DeviceEnvironment.profile(this).put("screen_capture_ready", captureAvailable)
                    .put("screen_capture_source", if (shellReady) "adb_shell" else if (Build.VERSION.SDK_INT >= 30) "accessibility" else "media_projection")
                    .put("adb_shell", bridge).put("adb_ime", if (shellReady) ShellBridgeImeService.capability() else JSONObject().put("available", false))
                    .put("visual_gestures", captureAvailable &&
                    serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
                val environment = JSONObject().put("apps", apps).put("device_profile", profile).put("window_layers", DeviceEnvironment.layers(this))
                if (omitPrivateScreenshot) environment.put("screenshot_omitted_reason", ScreenCapturePrivacy.LOGIN_SENSITIVE)
                if (recovery) environment.put("notification_recovery", "dismiss_requested")
                if (interruption != null) {
                    setTouchGuard(false)
                    return result("blocked", InterruptionPolicy.message(interruption), observation, environment.put("human_takeover", "interruption").put("interruption", interruption))
                }
                return result("ok", observation = observation, data = environment)
            }
            if (kind == "wait") {
                val end = android.os.SystemClock.elapsedRealtime() + command.optLong("duration_ms", 500).coerceIn(0, 30000)
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    if (actionGeneration.get() != executionGeneration || callInProgress()) return result("cancelled", "等待已中断")
                    Thread.sleep(minOf(100, end - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1))
                }
                return result("ok", observation = if(command.optBoolean("split_agent")) null else readiness.read(::observe))
            }
            val mutation = kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "login_password", "ime_action", "scroll", "back", "home", "menu", "launch", "open_document", "visual_gesture", "split_action") || kind in DeviceEnvironment.globalActions || kind in SplitAgentProtocol.nativeActions
            // Protected settings may have no readable accessibility root at all.
            if (mutation && PaymentConsent.settingsVisible) {
                stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                return result("blocked", "付款授权只能由用户在设置中手动更改", data = JSONObject().put("human_takeover", "payment"))
            }
            val current = if (mutation) observe() else null
            if (current != null) {
                if ((LoginAssist.settingsVisible || DirectMode.settingsVisible) && current.optString("package_name") == packageName) {
                    stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                    return result("blocked", "本机登录资料只能由用户编辑", current, JSONObject().put("human_takeover", "login"))
                }
                val nodes = current.getJSONArray("nodes")
                val interruption = InterruptionPolicy.reason(current.optString("package_name"), (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") + " " + nodes.getJSONObject(it).optString("description") })
                if (interruption != null) {
                    stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                    return result("blocked", InterruptionPolicy.message(interruption), current, JSONObject().put("human_takeover", "interruption").put("interruption", interruption))
                }
                val verification = (0 until nodes.length()).any { index ->
                    val node = nodes.getJSONObject(index)
                    listOf(node.optString("text"), node.optString("description")).any { label ->
                        Policy.verificationLabel(label, node.optBoolean("clickable") || node.optBoolean("long_clickable") || node.optBoolean("editable"))
                    }
                }
                if (verification) {
                    stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                    return result("blocked", "安全验证需要人工完成，请完成后明确继续", current, JSONObject().put("human_takeover", "verification"))
                }
            }
            if (kind == "split_action") return executeSplitAction(command, current!!, executionGeneration).also { receipt ->
                PostActionDelay.apply(receipt,android.os.SystemClock::elapsedRealtime,Thread::sleep) {
                    readInterrupted(executionGeneration) || instance !== this || callInProgress()
                }
            }
            if (kind == "visual_gesture") return executeVisualGesture(command, current!!, executionGeneration, readiness)
            if (kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "login_password", "ime_action", "scroll")) {
                val fresh = current!!
                val reference = command.optString("target")
                val loginTargets = if (kind in setOf("login_phone", "login_code")) refs.filterValues { candidate ->
                    val label = "${candidate.hintText?.toString().orEmpty()} ${candidate.contentDescription?.toString().orEmpty()} ${candidate.viewIdResourceName.orEmpty()}"
                    candidate.isEditable && candidate.isEnabled && candidate.isVisibleToUser &&
                        if (kind == "login_phone") Policy.phoneInput(label) else Policy.codeInput(label)
                } else emptyMap()
                val resolved = if (reference.isNotEmpty() && reference != "null") reference else if (kind == "scroll") refs.entries.firstOrNull { it.value.isScrollable }?.key.orEmpty()
                    else if (kind == "login_password") refs.entries.filter { it.value.isFocused && it.value.isPassword && it.value.isEditable }.singleOrNull()?.key.orEmpty()
                    else loginTargets.entries.singleOrNull { it.value.isFocused }?.key ?: loginTargets.entries.singleOrNull()?.key.orEmpty()
                val node = refs[resolved]
                if (kind == "login_password" && node == null) return result("error", "请先点击当前应用的登录密码输入框，再请求填写已授权资料", fresh)
                if (kind in setOf("login_phone", "login_code") && node == null) return result("error", "未找到唯一的对应登录输入框，请先聚焦手机号或验证码输入框", fresh)
                fun labels(targetNode: AccessibilityNodeInfo, depth: Int = 0): String {
                    if (depth > 4 || targetNode.isPassword) return ""
                    return "${targetNode.text?.toString().orEmpty()} ${targetNode.contentDescription?.toString().orEmpty()} " +
                        (0 until minOf(targetNode.childCount, 30)).joinToString(" ") { targetNode.getChild(it)?.let { child -> labels(child, depth + 1) }.orEmpty() }.take(4000)
                }
                var ancestor = node
                val ancestorLabels = mutableListOf<String>()
                var ancestorPassword = false
                repeat(4) {
                    ancestor?.let {
                        ancestorPassword = ancestorPassword || it.isPassword
                        ancestorLabels.add("${it.text?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}")
                    }
                    ancestor = ancestor?.parent
                }
                val target = node?.let { Target(if (kind == "scroll") "" else labels(it) + " " + ancestorLabels.joinToString(" ") +
                    if (kind == "login_code") " ${it.hintText?.toString().orEmpty()} ${it.viewIdResourceName.orEmpty()}" else "",
                    it.isPassword || ancestorPassword, it.isEnabled) }
                val nodes = fresh.getJSONArray("nodes")
                val visibleLabels = (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") + " " + nodes.getJSONObject(it).optString("description") }
                val paymentAction = kind != "scroll" && Policy.paymentTarget(target?.label.orEmpty(), visibleLabels)
                val consentId = command.optString("payment_consent_id").takeIf { it.isNotBlank() && it != "null" }
                val paymentAuthorized = kind == "tap" && consentId != null && consentId == PaymentConsent(this).currentId()
                val financialCredentialInput = refs.values.any { item ->
                    item.isEditable && (item.isPassword || Policy.financialCredential("${item.hintText?.toString().orEmpty()} ${item.contentDescription?.toString().orEmpty()} ${item.viewIdResourceName.orEmpty()}"))
                }
                if (kind != "scroll" && Policy.manualFinancialContext(visibleLabels, financialCredentialInput) && !(kind == "tap" && Policy.leavesFinancialScreen(target?.label.orEmpty())))
                    return result("blocked", "支付验证、转账及长期扣款授权需由用户操作", fresh, JSONObject().put("human_takeover", "payment"))
                if ((paymentAction && !paymentAuthorized) || (consentId != null && !paymentAction))
                    return result("blocked", "付款授权未开启、已撤销或与当前操作不匹配", fresh, JSONObject().put("human_takeover", "payment"))
                val snapshot = latestSnapshot
                if (snapshot == null || snapshot.navigationGeneration != navigationGeneration) return result("stale", "页面导航已变化，请重新观察", fresh)
                val expected = command.optString("screen_id")
                val privateInputLabel = "${node?.hintText?.toString().orEmpty()} ${node?.contentDescription?.toString().orEmpty()} ${node?.viewIdResourceName.orEmpty()}"
                val privateInput = node != null && (node.isPassword || Policy.codeInput(privateInputLabel) || Policy.phoneInput(privateInputLabel))
                val stable = kind !in SplitAgentProtocol.loginActions && !paymentAction && consentId == null && expected != snapshot.screenId && !(kind == "type" && privateInput) && targetHistory.revalidates(expected, snapshot, resolved, kind)
                val verdict = Policy.validate(if (kind == "ime_action") "type" else kind, if (stable) snapshot.screenId else expected, snapshot.screenId, target, paymentAuthorized = paymentAuthorized)
                if (verdict != "ok") return result(verdict, if (verdict == "stale") "页面或目标已变化，请重新观察" else "支付或敏感输入必须由用户接管", fresh)
                val input = kind in setOf("type", "login_phone", "login_code", "login_password")
                if (input && (!node!!.isEditable || kind == "type" && command.optString("text").length > 8000)) return result("blocked", "输入目标无效", fresh)
                if (kind == "long_press" && !node!!.isLongClickable) return result("blocked", "目标不支持长按", fresh)
                if (kind == "scroll" && (!node!!.isScrollable || command.optString("direction") !in setOf("up", "down", "left", "right"))) return result("blocked", "滚动目标或方向无效", fresh)
                val scrollAction = if (kind == "scroll") ScrollCapabilities.action(command.optString("direction"), node!!.actionList.map { it.id }.toSet()) else null
                if (kind == "scroll" && scrollAction == null) return result("ok", "未执行滚动：当前控件不支持该方向", fresh,
                    JSONObject().put("no_op", true).put("action_state", "direction_unavailable"))
                if (kind in setOf("login_phone", "login_code", "login_password") && (command.optString("package_name") != fresh.getString("package_name") || !command.isNull("text"))) return result("blocked", "登录目标应用不匹配", fresh, JSONObject().put("human_takeover", "login"))
                val inputLabel = "${node!!.hintText?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}"
                if (kind == "login_code" && !Policy.codeInput(inputLabel) || kind == "login_phone" && !Policy.phoneInput(inputLabel))
                    return result("blocked", "目标未标识为对应的登录输入框，请人工填写", fresh, JSONObject().put("human_takeover", "login"))
                if (kind == "login_password" && (!node.isPassword || command.optString("credential_label").isBlank()))
                    return result("blocked", "请选择已授权的资料和登录密码输入框", fresh, JSONObject().put("human_takeover", "login"))
                if (kind == "ime_action") {
                    val ime = ShellBridgeImeService.capability()
                    if (!shellBridgeReady() || !node.isEditable || !node.isFocused || privateInput || Policy.financialCredential(inputLabel) ||
                        command.optString("editor_id") != ime.optString("editor_id") || command.optString("editor_id").isBlank())
                        return result("blocked", "当前焦点或任务输入法不支持该编辑器操作", fresh, JSONObject().put("action_state", "not_dispatched"))
                    val data = ShellBridgeImeService.performAuthorized(fresh.getString("package_name"), command.getString("editor_id"), command.optString("action")) {
                        !readInterrupted(executionGeneration) && node.refresh() && node.isFocused && foregroundPackage() == fresh.getString("package_name")
                    }
                    targetHistory.clear()
                    return result(data.optString("status", "error"), "编辑器操作${if (data.optString("action_state") == "accepted") "已接受，请核对结果" else "未确认"}", settledObservation(executionGeneration), data)
                }
                val bounds = Rect().also { node!!.getBoundsInScreen(it) }
                val geometry = ActionFeedbackGeometry.create(kind, listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), fresh.getInt("width"), fresh.getInt("height"), command.optString("direction"))
                    ?: return result("stale", "目标不在可见屏幕内，请重新观察", fresh)
                if (actionGeneration.get() != executionGeneration) return result("cancelled", "执行已暂停", fresh)
                if (kind == "login_password") {
                    val filled = CredentialVault(this).fillForTask(fresh.getString("package_name"), command.getString("credential_label"), node) {
                        DeviceWorkerService.instance?.allowsCredentialInput(command.getString("run_id")) == true &&
                            !readInterrupted(executionGeneration) && !DirectMode.settingsVisible && !LoginAssist.settingsVisible &&
                            snapshot.navigationGeneration == navigationGeneration && foregroundPackage() == fresh.getString("package_name")
                    }
                    targetHistory.clear()
                    return if (filled) result("ok", "已在本机填写授权登录密码，请核对登录结果", settledObservation(executionGeneration), JSONObject().put("action_state", "accepted"))
                        else result("blocked", "当前任务、应用或密码资料未获填写授权，请检查密码管理设置", fresh, JSONObject().put("human_takeover", "login"))
                }
                fun loginAllowed(): Boolean {
                    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                    return DeviceWorkerService.instance?.allowsCredentialInput(command.getString("run_id")) == true &&
                        !readInterrupted(executionGeneration) && TaskControl.isCurrent(taskGeneration) && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
                        !DirectMode.settingsVisible && !LoginAssist.settingsVisible && snapshot.navigationGeneration == navigationGeneration &&
                        foregroundPackage() == fresh.getString("package_name") && node.refresh() && node.isEditable && node.isVisibleToUser && node.isEnabled &&
                        node.packageName?.toString() == fresh.getString("package_name")
                }
                val inputText = if (kind in setOf("login_phone", "login_code")) {
                    if (!loginAllowed()) return result("blocked", "当前任务或设备状态不允许填写登录资料", fresh, JSONObject().put("human_takeover", "login"))
                    val login = LoginAssist(this)
                    val value = runCatching { login.valueFor(kind, fresh.getString("package_name"), command.getString("run_id")) }.getOrNull()
                    if (value == null) {
                        val state = login.taskStatus(fresh.getString("package_name"), command.getString("run_id"))
                        val waiting = kind == "login_code" && state.optBoolean("enabled") && state.optBoolean("notification_access") && state.optBoolean("session_started") && state.optString("code_state") == "waiting"
                        return result(if (waiting) "ok" else "error", if (waiting) "尚未收到本次登录的新验证码，可继续等待后检查状态" else "登录资料或新验证码不可用，请检查本机授权、发送状态或有效期", fresh,
                            JSONObject().put("action_state", if (waiting) "waiting_for_code" else "not_dispatched").put("retry_after_ms", if (waiting) 1500 else 0))
                    }
                    value
                } else command.optString("text")
                if (actionGeneration.get() != executionGeneration) return result("cancelled", "执行已暂停", fresh)
                if (snapshot.navigationGeneration != navigationGeneration) return result("stale", "页面导航已变化，请重新观察", observe())
                if (kind in setOf("login_phone", "login_code") && !loginAllowed()) return result("cancelled", "登录填写已中断", fresh)
                if (kind == "tap") {
                    val desiredChecked = if (command.isNull("desired_checked")) null else {
                        command.get("desired_checked") as? Boolean ?: return result("blocked", "desired_checked 必须是布尔值", fresh)
                    }
                    when (CheckableTap.decide(node.isCheckable, node.isChecked, desiredChecked)) {
                        CheckableTap.Decision.REQUIRE_STATE -> return result("blocked", "可勾选控件需要 desired_checked 目标状态，不能用重复点击验证", fresh)
                        CheckableTap.Decision.NOT_CHECKABLE -> return result("blocked", "当前控件不可勾选，请重新观察", fresh)
                        CheckableTap.Decision.ALREADY_SET -> return result("ok", "控件已处于目标状态，未执行点击", fresh,
                            JSONObject().put("action_state", "already_set").put("no_op", true).put("checked", node.isChecked).put("desired_checked", desiredChecked))
                        CheckableTap.Decision.CLICK -> Unit
                    }
                } else if (!command.isNull("desired_checked")) return result("blocked", "desired_checked 仅适用于点击", fresh)
                // A revalidated target is single-use across device actions; all current safety checks still apply.
                targetHistory.clear()
                val token = feedback.begin(geometry)
                val paymentAttempt = if (paymentAction) {
                    // Without a trustworthy order ID, label/amount/resource changes cannot justify another charge.
                    val fingerprint = Policy.hash(fresh.getString("package_name"))
                    PaymentConsent(this).runPayment(consentId!!, command.getString("run_id"), fingerprint) {
                        actionGeneration.get() == executionGeneration && snapshot.navigationGeneration == navigationGeneration &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } else null
                if (paymentAttempt != null && paymentAttempt.status != "attempted") {
                    if (token != null) feedback.finish(token, false)
                    val message = when (paymentAttempt.status) {
                        "duplicate" -> "本任务已在此应用尝试付款，请核对订单与扣款记录，后续付款确认需你接手"
                        "storage_error" -> "无法保存付款操作记录，已停止付款"
                        else -> "付款授权已撤销，操作未执行"
                    }
                    return result("blocked", message, fresh, JSONObject().put("human_takeover", "payment").put("payment_guard", paymentAttempt.status))
                }
                val acted = when(kind) {
                    "tap" -> paymentAttempt?.accepted ?: node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "long_press" -> node!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    "type", "login_phone", "login_code", "login_password" -> node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText) })
                    else -> {
                        node!!.performAction(requireNotNull(scrollAction))
                    }
                }
                if (!acted && kind == "type" && !privateInput && !Policy.financialCredential(inputLabel) && shellBridgeReady() && node.isFocused) {
                    val ime = ShellBridgeImeService.capability()
                    if (ime.optBoolean("input_available") && ime.optString("package_name") == fresh.getString("package_name")) {
                        val data = ShellBridgeImeService.performTextAuthorized(fresh.getString("package_name"), ime.optString("editor_id"), inputText, true) {
                            !readInterrupted(executionGeneration) && node.refresh() && node.isFocused && foregroundPackage() == fresh.getString("package_name")
                        }.put("native_action_state", "failed")
                        if (token != null) feedback.finish(token, data.optString("action_state") == "accepted")
                        return result(data.optString("status", "error"), "任务输入法${if (data.optString("action_state") == "accepted") "已接受文字，请核对内容" else "未确认输入，未自动重放"}", settledObservation(executionGeneration), data)
                    }
                }
                if (token != null) feedback.finish(token, acted)
                val actionData = JSONObject().put("target_revalidated", stable).put("action_state", if (acted) "accepted" else "failed")
                    .put("feedback_enabled", token != null).put("feedback_target", JSONArray(geometry.bounds))
                if (!acted) {
                    val requestedAction = when (kind) {
                        "tap" -> AccessibilityNodeInfo.ACTION_CLICK
                        "long_press" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                        "type", "login_phone", "login_code", "login_password" -> AccessibilityNodeInfo.ACTION_SET_TEXT
                        else -> requireNotNull(scrollAction)
                    }
                    actionData.put("action_diagnostic", JSONObject().put("node_present", true)
                        .put("enabled", node.isEnabled).put("clickable", node.isClickable)
                        .put("long_clickable", node.isLongClickable).put("editable", node.isEditable)
                        .put("scrollable", node.isScrollable).put("password", node.isPassword)
                        .put("action_ids", JSONArray(node.actionList.take(32).map { it.id }))
                        .put("requested_action_advertised", node.actionList.any { it.id == requestedAction })
                        .put("requested_action_id", requestedAction))
                }
                if (paymentAttempt != null) {
                    actionData.put("payment_attempted", true).put("payment_action_accepted", acted)
                    if (!acted) actionData.put("human_takeover", "payment")
                }
                return result(if (acted) "ok" else if (paymentAttempt != null) "blocked" else "error", if (acted) "系统已接受操作，请检查后续屏幕" else if (paymentAttempt != null) "付款结果未确认，请核对订单后手动处理" else "控件未执行操作", settledObservation(executionGeneration), actionData)
            }
            if (mutation && actionGeneration.get() != executionGeneration) return result("cancelled", "执行已暂停", current)
            if (kind in SplitAgentProtocol.nativeActions) {
                if (command.optBoolean("split_agent")) {
                    val capture = visualCaptures.remove(command.optJSONObject("source")?.optString("capture_id").orEmpty())
                    val dimensions = displayGeometry()
                    if (capture?.canVerifyAgainst(latestSnapshot, dimensions.first, dimensions.second, dimensions.third, android.os.SystemClock.elapsedRealtime()) != true)
                        return result("stale", "系统操作前来源画面已失效，请重新观察", current,
                            JSONObject().put("action_state", "not_dispatched").put("reason_code", "source_navigation_changed"))
                } else if (command.optString("screen_id").isBlank() || command.optString("screen_id") != current?.optString("screen_id"))
                    return result("stale", "系统操作前页面已变化，请重新观察", current, JSONObject().put("action_state", "not_dispatched"))
                if (readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration)) return result("cancelled", "系统操作已中断", current)
            }
            targetHistory.clear()
            if (!command.optBoolean("split_agent") && kind in setOf("back", "home", "recents", "menu") && shellBridgeReady() && current != null) {
                val rotation = displayGeometry().third
                val source = ShellBridgeClient.source(current, rotation)
                val data = ShellBridgeClient.get(this).executeAuthorized(command.getString("id"), command.getString("run_id"), source, kind, JSONObject(),
                    { ShellBridgeClient.source(observe(), displayGeometry().third) }, { !readInterrupted(executionGeneration) })
                return result(data.optString("status", "error"), "ADB系统操作${if (data.optString("action_state") == "accepted") "已接受，请核对画面" else "未确认，未自动重放"}", settledObservation(executionGeneration), data)
            }
            DeviceEnvironment.globalActions[kind]?.let { action ->
                if (Build.VERSION.SDK_INT >= 30 && systemActions.none { it.id == action }) return result("error", "本机未提供此系统动作", current,
                    JSONObject().put("action_state", "not_dispatched").put("reason_code", "system_action_unavailable"))
                val accepted = performGlobalAction(action)
                feedback.message(DeviceEnvironment.actionLabel(kind) + if (accepted) "" else "未成功")
                val data = JSONObject().put("action_state", if (accepted) "accepted" else "not_dispatched")
                if (kind == "system_screenshot") data.put("system_screenshot_requested", accepted).put("file_saved_verified", false)
                val receipt = result(if (accepted) "ok" else "error", if (kind == "system_screenshot")
                    "系统截图请求已提交，请检查系统缩略图或保存通知；这不是模型观察截图" else DeviceEnvironment.actionLabel(kind),
                    if (command.optBoolean("split_agent")) null else settledObservation(executionGeneration), data)
                PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration) }
                return receipt
            }
            when(kind) {
                "volume", "adjust_volume" -> {
                    val changed = DeviceEnvironment.volume(this, command) { !readInterrupted(executionGeneration) && TaskControl.isCurrent(taskGeneration) }
                    val receipt = result(changed.getString("status"), changed.getString("message"), data = changed.getJSONObject("data"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration) }
                    return receipt
                }
                "copy", "cut", "paste" -> {
                    val requestedAction = when(kind) { "copy" -> AccessibilityNodeInfo.ACTION_COPY; "cut" -> AccessibilityNodeInfo.ACTION_CUT; else -> AccessibilityNodeInfo.ACTION_PASTE }
                    val node = refs.values.filter { it.isFocused && it.isVisibleToUser && it.isEnabled }.singleOrNull { candidate ->
                        candidate.isEditable || candidate.actionList.any { it.id == requestedAction }
                    } ?: return result("error", "请先聚焦可复制的文字或输入框；当前控件未提供对应剪贴板操作", current, JSONObject().put("action_state", "not_dispatched"))
                    val raw = node.text?.toString().orEmpty()
                    val label = "${node.hintText?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}"
                    if (node.isPassword || LoginAssist.containsPrivateValue(raw) || Policy.codeInput(label) || Policy.phoneInput(label) || Policy.manualFinancial(label))
                        return result("error", "密码及登录验证资料请使用本机登录辅助，不能通过普通剪贴板操作", current, JSONObject().put("action_state", "not_dispatched"))
                    if (kind != "copy" && !node.isEditable) return result("error", "剪切或粘贴需要可编辑输入框", current)
                    if (kind != "paste") {
                        val selection = SplitAgentProtocol.text(command, "selection", 10)
                        if (selection !in setOf("all", "current")) return result("error", "文本选区无效", current)
                        if (selection == "all" && raw.isEmpty()) return result("error", "当前没有可选择的文字", current)
                        // TextView may return false for an already-current selection, for example
                        // when cut follows copy. Verify the actual range instead of requiring a change.
                        if (selection == "all" && (node.textSelectionStart != 0 || node.textSelectionEnd != raw.length))
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, raw.length)
                            })
                        if (!node.refresh() || node.textSelectionStart < 0 || node.textSelectionEnd <= node.textSelectionStart)
                            return result("error", "当前没有选中的文字，请先选择要复制或剪切的内容", current)
                        if (selection == "all" && (node.text?.toString() != raw || node.textSelectionStart != 0 || node.textSelectionEnd != raw.length))
                            return result("error", "当前控件未确认全选文字，请重新观察后选择", current)
                    }
                    if (readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration) || !node.refresh() || !node.isFocused)
                        return result("cancelled", "文本操作已中断或焦点已改变", current)
                    if (node.isPassword || node.actionList.none { it.id == requestedAction })
                        return result("error", "当前控件没有提供所需文本操作，请通过页面处理", current, JSONObject().put("action_state", "not_dispatched"))
                    val accepted = node.performAction(requestedAction)
                    val receipt = result(if (accepted) "ok" else "error", if (accepted) "系统已接受文本操作，请核对结果" else "控件未执行文本操作", data = JSONObject().put("action_state", if (accepted) "accepted" else "not_dispatched"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration) }
                    return receipt
                }
                "launch" -> {
                    val targetPackage = command.optString("package_name")
                    val data = JSONObject().put("package_name", targetPackage.take(255)).put("action_state", "not_dispatched")
                    if (!targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || targetPackage.length > 255)
                        return result("error", "应用包名无效", current, data.put("reason_code", "app_unavailable"))
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        ?: return result("error", "应用未安装或不可启动，请重新查询应用列表", current, data.put("reason_code", "app_unavailable"))
                    if (readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration)) return result("cancelled", "启动应用已中断", current, data)
                    try { startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: android.content.ActivityNotFoundException) { return result("error", "应用未安装或不可启动", current, data.put("reason_code", "app_unavailable")) }
                    catch (_: SecurityException) { return result("error", "系统未允许打开此应用", current, data.put("reason_code", "app_unavailable")) }
                    targetHistory.clear(); visualCaptures.clear()
                    val receipt = result("ok", "启动请求已提交，请依据新截图确认应用已打开", data = data.put("action_state", "accepted"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) {
                        readInterrupted(executionGeneration) || !TaskControl.isCurrent(taskGeneration)
                    }
                    return receipt
                }
                "open_document" -> {
                    val uri = android.net.Uri.parse(command.optString("uri"))
                    val receiver = command.optString("package_name").takeIf { it.isNotBlank() && it != "null" }
                    if (uri.scheme == "doppel-document") { Gateway(this).openDocument(uri.toString(), receiver); return result("ok", "已请求打开文档副本", settledObservation(executionGeneration)) }
                    if (uri.scheme != "content" || contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return result("blocked", "文档需要用户通过系统选择器授权")
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (receiver != null) { intent.setPackage(receiver); if (intent.resolveActivity(packageManager) == null) return result("error", "指定应用不可打开此文档") }
                    startActivity(intent)
                    return result("ok", "已请求打开授权文档", settledObservation(executionGeneration))
                }
            }
            return result("blocked", "不支持的命令")
            }
        } catch (_: ScreenReadInterruptedException) {
            feedback.clear()
            return result("cancelled", "屏幕读取已中断")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            feedback.clear()
            return result("cancelled", "屏幕读取已中断")
        } catch (failure: Exception) {
            feedback.clear()
            val diagnostic = DeviceReadDiagnostic.from(failure)
            android.util.Log.w("DoppelDeviceRead", diagnostic.toString())
            val emptyFrame = failure is ScreenNotReadyException && failure.reason == ScreenReadinessReason.EMPTY_FRAME
            val rootUnavailable = failure is ScreenNotReadyException && failure.reason == ScreenReadinessReason.ROOT
            return result("error", when {
                emptyFrame -> "当前画面尚未显示内容，正在等待新画面"
                rootUnavailable -> "当前界面暂时不可读取；若出现系统权限弹窗，请手动选择后继续"
                else -> "设备操作失败，请重新观察并检查权限"
            },
                data = JSONObject().put("read_diagnostic", diagnostic)
                    .put("reason_code", if (emptyFrame) "capture_empty_frame" else "capture_unavailable"))
        }
    }
    /** A or B supplies coordinates. The host reports injection facts; A judges outcomes from the next image. */
    private fun executeSplitAction(command: JSONObject, current: JSONObject, executionGeneration: Long): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        val data = JSONObject().put("action_state", "not_dispatched").put("completed_strokes", 0).put("feedback_enabled", true)
        fun result(status: String, message: String) = JSONObject().put("command_id",command.getString("id"))
            .put("run_id",command.getString("run_id")).put("status",status).put("message",message)
            .put("observation",current).put("data",data.put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started))
        val requested = command.getJSONObject("action")
        val action = SplitAgentProtocol.grounding(requested,requested.getString("action"))
        val kind = action.getString("action")
        val dimensions = displayGeometry()
        val source = command.getJSONObject("source")
        val sourceCapture = visualCaptures.remove(source.optString("capture_id"))
        val anchor = sourceCapture?.navigation
        val pkg = current.optString("package_name")
        // Preserve the original sequence navigation boundary across inter-stroke screenshots.
        val sequenceNavigation=JSONObject().put("generation",navigationGeneration).put("window_id",latestSnapshot?.windowId)
        data.put("sequence_navigation",sequenceNavigation)
        command.optJSONObject("sequence_navigation")?.let {original ->
            if(original.optInt("generation",-1)!=navigationGeneration || original.optInt("window_id",-1)!=latestSnapshot?.windowId) {
                data.put("reason_code","source_navigation_changed")
                return result("stale","连续滑动期间页面导航已改变，剩余动作未执行，请重新观察")
            }
        }
        if(source.optInt("display_width")!=dimensions.first || source.optInt("display_height")!=dimensions.second ||
            source.optInt("rotation")!=dimensions.third || source.optString("package_name")!=pkg) {
            data.put("reason_code","source_geometry_or_app_changed")
            return result("stale","截图之后应用或屏幕方向已改变，请重新观察")
        }
        fun live() = !readInterrupted(executionGeneration) && instance===this && !callInProgress()
        fun sameNavigation() = anchor!=null && anchor.screenId==sourceCapture?.frame?.screenId &&
            anchor.navigationGeneration==navigationGeneration && anchor.windowId==latestSnapshot?.windowId
        fun sameScreen() = live() && sameNavigation() && displayGeometry()==dimensions && foregroundPackage()==pkg
        if(!live()) return result("cancelled","执行已暂停，动作未派发")
        if(!sameNavigation()) {
            data.put("reason_code","source_navigation_changed")
            return result("stale","截图之后页面导航或窗口已改变，请重新观察")
        }
        // No extra capture, pixel threshold or pixel-verification expiry before gestures.
        // Continuous animation and flat targets must not veto model-selected coordinates.
        data.put("visual_verification",JSONObject().put("performed",false).put("reason","action_pixel_check_disabled"))
        // Use the current native observation for existing authorization checks.
        val nodes=current.getJSONArray("nodes")
        val labels=(0 until nodes.length()).map {nodes.getJSONObject(it).let {n -> n.optString("text")+" "+n.optString("description")}}
        val target=requested.optString("target")
        val sensitive=refs.values.any {it.isPassword && it.isEditable}
        val leaves=kind in setOf("back","home","recents") || Policy.leavesFinancialScreen(target)
        if(!leaves && (Policy.manualFinancial(target) || Policy.manualFinancialContext(labels,sensitive))) {
            data.put("human_takeover","payment");return result("blocked","支付验证、转账及长期扣款授权需由用户操作")
        }
        val payment=!leaves && Policy.paymentTarget(target,labels)
        val consentId=command.optString("payment_consent_id").takeIf {it.isNotBlank() && it!="null"}
        if(payment && (kind!="tap" || consentId==null || consentId!=PaymentConsent(this).currentId())) {
            data.put("human_takeover","payment");return result("blocked","付款授权未开启或已撤销，请手动处理")
        }
        if(kind=="type" || kind=="enter") {
            val node=refs.values.firstOrNull {it.isFocused && it.isEditable}
            if(node?.isPassword==true) { data.put("human_takeover","private_input");return result("blocked","密码输入需要你接手") }
            if(kind=="type" && node!=null && sameScreen() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,action.getString("text")) })) {
                feedback.message("已输入文字");data.put("action_state","accepted");return result("ok","系统接受文字输入，请依据新截图核对")
            }
            val ime=ShellBridgeImeService.capability()
            if(!ime.optBoolean("input_available") || ime.optString("package_name")!=pkg) {
                data.put("reason_code","input_connection_unavailable")
                return result("error","当前输入连接不可用，可点击输入框、使用屏幕键盘提交或切换任务输入法后重试")
            }
            val response=if(kind=="type") ShellBridgeImeService.performTextAuthorized(pkg,ime.optString("editor_id"),action.getString("text"),true,::sameScreen)
                else ShellBridgeImeService.submitAuthorized(pkg,ime.optString("editor_id"),::sameScreen)
            response.keys().forEach { key -> data.put(key,response.get(key)) }
            feedback.message(if(kind=="type") "输入文字" else "回车")
            return result(response.optString("status","error"),"编辑器操作已返回，请核对新画面")
        }
        DeviceEnvironment.globalActions[kind]?.let { global ->
            val accepted=sameScreen() && performGlobalAction(global)
            data.put("action_state",if(accepted) "accepted" else "not_dispatched")
            feedback.message(DeviceEnvironment.actionLabel(kind))
            return result(if(accepted) "ok" else "error","系统操作已返回，请依据新截图核对")
        }
        val plan=GestureSequencePlan.from(action,dimensions.first,dimensions.second)
        require(plan.strokes.isNotEmpty())
        val companion=DeviceWorkerService.instance
        val revision=companion?.companionRevision
        fun currentHost()=live() && DeviceWorkerService.instance===companion && companion?.companionRevision==revision
        val pass=companion?.beginCompanionGestureTouchPass(executionGeneration,plan.totalMs+5000,::currentHost)
        companion?.companionGestureTouchPassDiagnostic()?.let {data.put("touch_handoff",it)}
        if(companion!=null && pass==null) return result("cancelled","助手触区未能让出，动作未派发")
        var guard:AutoCloseable?=null
        try {
            guard=beginGuardGestureTouchPass(executionGeneration,plan.totalMs+5000,::currentHost)
            data.put("guard_handoff",guardGestureTouchPassDiagnostic())
            if(guard==null) return result("cancelled","触屏暂停层未能让出，动作未派发")
            targetHistory.clear();visualCaptures.clear()
            fun dispatch(strokes:List<PlannedStroke>, interval:Long):Boolean {
                val latch=CountDownLatch(1)
                val submitted=java.util.concurrent.atomic.AtomicBoolean()
                val confirmed=java.util.concurrent.atomic.AtomicBoolean()
                val expired=java.util.concurrent.atomic.AtomicBoolean()
                val tokens=java.util.concurrent.CopyOnWriteArrayList<Long>()
                var offset=0L
                val description=GestureDescription.Builder()
                strokes.forEach {stroke ->
                    // A normal two-point swipe must use the same bounded smooth path as
                    // visual swipes.  Previously this branch connected the model points
                    // directly, so the humanized trajectory was silently skipped for the
                    // regular A/B grounding protocol.  Keep explicit multi-waypoint paths
                    // intact because their intermediate points are part of the gesture.
                    val pathPoints = if (stroke.points.size == 2) {
                        val start = stroke.points.first()
                        val end = stroke.points.last()
                        HumanGesturePath.samples(
                            android.graphics.PointF(start.x, start.y),
                            android.graphics.PointF(end.x, end.y), dimensions.first, dimensions.second
                        ).map { FeedbackPoint(it.x, it.y) }
                    } else stroke.points
                    val path=Path().apply {moveTo(pathPoints.first().x,pathPoints.first().y);pathPoints.drop(1).forEach {lineTo(it.x,it.y)}}
                    description.addStroke(GestureDescription.StrokeDescription(path,offset,stroke.durationMs))
                    val delay=offset
                    mainHandler.postDelayed({
                        if(!expired.get() && currentHost()) {
                            val start=stroke.points.first();val end=stroke.points.last()
                            val xs=stroke.points.map {it.x};val ys=stroke.points.map {it.y}
                            tokens.add(feedback.begin(ActionFeedbackGeometry(if(stroke.points.size>1) "swipe" else kind,
                                listOf((xs.min()-24).toInt(),(ys.min()-24).toInt(),(xs.max()+24).toInt(),(ys.max()+24).toInt()),
                                start,if(stroke.points.size>1) start else null,if(stroke.points.size>1) end else null,
                                pathPoints,stroke.durationMs)))
                        }
                    },delay)
                    offset+=stroke.durationMs+interval
                }
                val expectedMs=offset-interval
                mainHandler.post {
                    if(expired.get() || !currentHost() || !sameScreen()) {latch.countDown();return@post}
                    ownGestureGeneration.set(executionGeneration)
                    try {
                        submitted.set(dispatchGesture(description.build(),object:GestureResultCallback() {
                            override fun onCompleted(gestureDescription:GestureDescription?) {confirmed.set(true);ownGestureGeneration.compareAndSet(executionGeneration,-1);latch.countDown()}
                            override fun onCancelled(gestureDescription:GestureDescription?) {ownGestureGeneration.compareAndSet(executionGeneration,-1);latch.countDown()}
                        },mainHandler))
                        if(!submitted.get()) {ownGestureGeneration.compareAndSet(executionGeneration,-1);latch.countDown()}
                    } catch(_:Exception) {ownGestureGeneration.compareAndSet(executionGeneration,-1);latch.countDown()}
                }
                val deadline=android.os.SystemClock.elapsedRealtime()+expectedMs+2000
                try {
                    while(latch.count>0 && currentHost() && android.os.SystemClock.elapsedRealtime()<deadline) latch.await(40,TimeUnit.MILLISECONDS)
                } finally {expired.set(true);ownGestureGeneration.compareAndSet(executionGeneration,-1)}
                tokens.forEach {feedback.finish(it,confirmed.get())}
                val humanizedCount = strokes.count { it.points.size == 2 }
                data.put("action_state",if(confirmed.get()) "accepted" else if(submitted.get()) "unconfirmed" else "not_dispatched")
                    .put("backend","accessibility").put("feedback_targets",JSONArray(strokes.map {s -> JSONArray(s.points.map {p -> JSONArray(listOf(p.x,p.y))})}))
                    .put("humanized_swipes", humanizedCount)
                if(kind in setOf("tap","double_tap")) data.put("touch_durations_ms",JSONArray(strokes.map {it.durationMs}))
                if(confirmed.get()) data.put("completed_strokes",data.getInt("completed_strokes")+strokes.size)
                else if(submitted.get()) data.put("unconfirmed_strokes",strokes.size)
                return confirmed.get()
            }
            if(payment) {
                val point=plan.strokes.single().points.single()
                val paymentNode=refs.values.firstOrNull { node ->
                    val bounds=Rect();node.getBoundsInScreen(bounds)
                    node.isClickable && node.isEnabled && !node.isPassword && bounds.contains(point.x.toInt(),point.y.toInt()) &&
                        Policy.paymentTarget("${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}",labels)
                }
                if(paymentNode==null) {data.put("human_takeover","payment");return result("blocked","无法将付款位置核对到明确控件，请手动付款")}
                val attempt=PaymentConsent(this).runPayment(consentId!!,command.getString("run_id"),Policy.hash(pkg)) {
                    val token=feedback.begin(ActionFeedbackGeometry("tap",listOf(point.x.toInt()-24,point.y.toInt()-24,point.x.toInt()+24,point.y.toInt()+24),point))
                    val accepted=sameScreen() && paymentNode.refresh() && paymentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    feedback.finish(token,accepted);data.put("action_state",if(accepted) "accepted" else "unconfirmed");accepted
                }
                data.put("payment_guard",attempt.status).put("payment_attempted",attempt.status=="attempted")
                if(!attempt.accepted) {data.put("human_takeover","payment");return result("blocked","付款未确认或已经尝试过，请核对订单后手动处理")}
            } else if(kind=="double_tap") {
                // One Android gesture preserves the inter-tap timing. An interrupted pair is never replayed.
                if(!dispatch(plan.strokes,plan.intervalMs)) return result(if(live()) "error" else "cancelled","双击未完整确认，已提交部分不会自动重放")
            } else {
                for((index,stroke) in plan.strokes.withIndex()) {
                    if(!sameScreen()) return result(if(live()) "stale" else "cancelled","连续动作已停止，已完成部分不会重放")
                    if(!dispatch(listOf(stroke),0)) return result(if(live()) "error" else "cancelled","动作未完整确认，已提交部分不会自动重放")
                    if(index<plan.strokes.lastIndex && plan.intervalMs>0) Thread.sleep(plan.intervalMs)
                }
            }
            return result(if(live()) "ok" else "cancelled","系统手势已返回，请依据新截图判断实际结果")
        } finally {pass?.close();guard?.close()}
    }
    private fun executeVisualGesture(command: JSONObject, current: JSONObject, executionGeneration: Long, readiness: ScreenReadyWait): JSONObject {
        fun result(status: String, message: String, observation: JSONObject? = current, data: JSONObject = JSONObject()) =
            JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
                .put("status", status).put("message", message).put("observation", observation ?: JSONObject.NULL).put("data", data)
        fun stale(code: String, message: String, stage: String, observation: JSONObject = current, details: JSONObject = JSONObject()) =
            result("stale", message, observation, visualDiagnostic(code, stage, details))
        val gesture = try { VisualGesture.parse(command.getJSONObject("gesture")) }
            catch (_: Exception) { return result("blocked", "视觉动作格式无效") }
        if (!VisualGesturePermits.consume(command.optString("visual_permit"), command.getString("run_id"), gesture))
            return result("blocked", "视觉动作缺少当前宿主授权，旧操作未重放")
        val source = visualCaptures.remove(gesture.captureId)
            ?: return stale("source_capture_missing", "来源截图已失效，请重新观察", "source")
        fun matchingSnapshot(observation: JSONObject) = latestSnapshot?.takeIf {
            it.screenId == observation.optString("screen_id") && it.navigationGeneration == navigationGeneration
        }
        val dimensions = displayGeometry()
        val sourceDetails = source.contextDiagnostic(matchingSnapshot(current), dimensions.first, dimensions.second, dimensions.third, android.os.SystemClock.elapsedRealtime())
        if (!sourceDetails.optBoolean("frame_matches"))
            return stale("source_frame_mismatch", "来源截图的应用、窗口、导航、尺寸、方向或有效期已变化，请重新观察", "source", details = sourceDetails)
        fun blocked(observation: JSONObject): String? {
            val nodes = observation.getJSONArray("nodes")
            val labels = (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") + " " + nodes.getJSONObject(it).optString("description") }
            val sensitive = (0 until nodes.length()).any { index ->
                val node = nodes.getJSONObject(index)
                node.optBoolean("password") || node.optBoolean("editable") && Policy.financialCredential(node.optString("text") + " " + node.optString("description"))
            }
            return gesture.blockedReason(labels, sensitive)
        }
        blocked(current)?.let { reason -> return result("blocked", "此视觉操作涉及付款、验证、敏感输入或无法确认的目标，请手动处理", data = JSONObject().put("human_takeover", reason)) }
        if (callInProgress() || actionGeneration.get() != executionGeneration) return result("cancelled", "执行已暂停")
        val verificationCapture = java.util.concurrent.atomic.AtomicReference<PixelCapture?>()
        val verificationShot = screenshot(command, readiness, executionGeneration, verificationCapture)
        if (verificationShot.optString("status") != "ok") {
            val details = JSONObject()
            val captureReason = verificationShot.optJSONObject("data")?.optString("reason_code")
            if (captureReason in setOf("capture_screen_changed", "capture_geometry_changed", "capture_companion_changed")) details.put("capture_reason_code", captureReason)
            return stale("verification_capture_failed", "无法核验当前目标像素，请重新观察", "verify", details = details).apply {
                ShellBridgeDiagnostic.sanitize(verificationShot.optJSONObject("data")?.optJSONObject("shell_diagnostic"))
                    ?.let { getJSONObject("data").put("shell_diagnostic", it) }
            }
        }
        val checked = verificationCapture.get()
            ?: return stale("verification_frame_missing", "目标像素核验缺少来源帧", "verify")
        val verified = readiness.read(::observe)
        val verifiedDimensions = displayGeometry()
        val verifiedAt = android.os.SystemClock.elapsedRealtime()
        val verificationDetails = source.contextDiagnostic(matchingSnapshot(verified), verifiedDimensions.first, verifiedDimensions.second, verifiedDimensions.third, verifiedAt)
        val frameMatches = verificationDetails.optBoolean("frame_matches")
        val pixelCheck = source.pixels.compare(checked.pixels, gesture)
        val pixelsMatch = pixelCheck.matches
        verificationDetails.put("pixels_match", pixelsMatch).put("verification_age_ms", verifiedAt - checked.plan.capturedAt)
            .put("pixel_check", pixelCheck.json())
        (verificationShot.optJSONObject("data")?.optJSONObject("capture_observation")?.opt("semantic_changed_during_capture") as? Boolean)
            ?.let { verificationDetails.put("semantic_changed_during_capture", it) }
        if (!frameMatches || !pixelsMatch) return stale(if (!frameMatches) "screen_context_changed" else "target_pixels_changed",
            "目标区域或当前画面已变化，旧视觉动作未执行", "verify", verified, verificationDetails)
        blocked(verified)?.let { reason -> return result("blocked", "当前画面需要手动处理", verified, JSONObject().put("human_takeover", reason)) }
        val callback = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val actionCompletedAt = java.util.concurrent.atomic.AtomicLong(0)
        val submitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatchBlock = java.util.concurrent.atomic.AtomicReference<String?>()
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val geometry = gesture.geometry(source.frame)
        val companion = DeviceWorkerService.instance
        val companionRevision = companion?.companionRevision
        fun currentHost() = actionGeneration.get() == executionGeneration && instance === this &&
            DeviceWorkerService.instance === companion && companion?.companionRevision == companionRevision
        fun currentInjection() = !expired.get() && currentHost()
        val touchPass = companion?.beginCompanionGestureTouchPass(executionGeneration, gesture.durationMs + 3000, ::currentInjection)
        if (companion != null && touchPass == null) return result("cancelled", "助手触区未能让出，视觉动作未执行", verified,
            visualDiagnostic("companion_touch_pass_unavailable", "input", verificationDetails))
        var guardPass: AutoCloseable? = null
        try {
        guardPass = beginGuardGestureTouchPass(executionGeneration, gesture.durationMs + 3000, ::currentHost)
        if (guardPass == null) return result("cancelled", "触屏暂停层未能就绪，视觉动作未执行", verified,
            visualDiagnostic("touch_guard_handoff_unavailable", "input", verificationDetails))
        val token = feedback.begin(geometry)
        targetHistory.clear(); visualCaptures.clear()
        fun injectionBlock(): String? = when {
                !currentInjection() -> "宿主执行状态已改变"
                callInProgress() -> "设备正在通话"
                navigationGeneration != latestSnapshot?.navigationGeneration -> "页面导航已变化"
                navigationGeneration != source.navigation?.navigationGeneration -> "来源页面导航已变化"
                activeRoot()?.windowId != source.navigation?.windowId -> "来源活动窗口已变化"
                foregroundPackage() != source.frame.packageName -> "前台应用已变化"
                displayGeometry() != verifiedDimensions -> "屏幕尺寸或方向已变化"
                android.os.SystemClock.elapsedRealtime() - checked.plan.capturedAt !in 0..1000 -> "核验截图超过派发时限"
                else -> null
            }
        var shellResult: JSONObject? = null
        // The shell bridge can issue only one straight input swipe. Use the
        // accessibility dispatcher for swipes so the bounded humanized path
        // below is preserved; taps/long presses keep the lower-latency shell
        // path when it is available.
        val useShell = gesture.kind != "swipe" && shellBridgeReady() && verificationShot.optJSONObject("data")?.optString("capture_backend") == "adb_shell"
        if (useShell) {
            val blocked = injectionBlock()
            if (blocked != null) dispatchBlock.set(blocked)
            else {
                val point = gesture.start(source.frame); val end = gesture.end(source.frame)
                val args = JSONObject().put("x", point.x.toInt()).put("y", point.y.toInt())
                if (gesture.kind != "tap") args.put("duration_ms", gesture.durationMs)
                if (end != null) args.put("end_x", end.x.toInt()).put("end_y", end.y.toInt())
                val proof = ShellBridgeClient.source(verified, checked.plan.rotation, checked.plan.captureId)
                    .put("captured_at", checked.plan.capturedAt).put("pixel_verification", "host_target_rgb_edges")
                ownGestureGeneration.set(executionGeneration)
                try {
                    shellResult = ShellBridgeClient.get(this).executeAuthorized(command.getString("id"), command.getString("run_id"), proof, gesture.kind, args,
                        { ShellBridgeClient.source(observe(), displayGeometry().third) }, { currentInjection() && !callInProgress() })
                    accepted.set(shellResult.optString("action_state") == "accepted")
                    if (accepted.get()) actionCompletedAt.set(android.os.SystemClock.elapsedRealtime())
                    submitted.set(shellResult.optString("action_state") in setOf("accepted", "unconfirmed"))
                } finally { ownGestureGeneration.compareAndSet(executionGeneration, -1) }
            }
            callback.countDown()
        } else mainHandler.post {
            val blocked = injectionBlock()
            if (blocked != null) { dispatchBlock.set(blocked); callback.countDown(); return@post }
            try {
                val start = gesture.start(source.frame); val end = gesture.end(source.frame)
                val path = if (end != null) Path().apply {
                    val preview = geometry.path
                    if (preview.size > 1) {
                        moveTo(preview.first().x, preview.first().y)
                        preview.drop(1).forEach { lineTo(it.x, it.y) }
                    } else {
                        val generated = HumanGesturePath.path(
                            android.graphics.PointF().apply { x = start.x; y = start.y },
                            android.graphics.PointF().apply { x = end.x; y = end.y }, source.frame.displayWidth, source.frame.displayHeight
                        )
                        addPath(generated)
                    }
                } else Path().apply { moveTo(start.x, start.y) }
                val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, gesture.durationMs)).build()
                ownGestureGeneration.set(executionGeneration)
                submitted.set(dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { ownGestureGeneration.compareAndSet(executionGeneration, -1); actionCompletedAt.set(android.os.SystemClock.elapsedRealtime()); accepted.set(true); callback.countDown() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { ownGestureGeneration.compareAndSet(executionGeneration, -1); callback.countDown() }
                }, mainHandler))
                if (!submitted.get()) { ownGestureGeneration.compareAndSet(executionGeneration, -1); callback.countDown() }
            } catch (_: Exception) { ownGestureGeneration.compareAndSet(executionGeneration, -1); callback.countDown() }
        }
        val returned = try { callback.await(gesture.durationMs + 1800, TimeUnit.MILLISECONDS) }
            finally {
                expired.set(true)
                ownGestureGeneration.compareAndSet(executionGeneration, -1)
                touchPass?.close()
                guardPass?.close()
            }
        if (token != null) feedback.finish(token, returned && accepted.get())
        val data = JSONObject().put("action_state", VisualDispatchGate.actionState(submitted.get(), accepted.get()))
            .put("backend", if (useShell) "adb_shell_local_socket" else "accessibility")
            .put("source_capture_id", source.frame.captureId).put("verification_capture_id", checked.plan.captureId)
            .put("feedback_enabled", token != null).put("feedback_target", JSONArray(geometry.bounds))
            .put("visual_diagnostic", verificationDetails.put("reason_code", if (dispatchBlock.get() == null) "visual_pixels_verified" else "gesture_context_changed")
                .put("stage", if (dispatchBlock.get() == null) "verify" else "input"))
        if (accepted.get()) data.put("action_completed_at_elapsed_ms", actionCompletedAt.get())
        ShellBridgeDiagnostic.sanitize(shellResult?.optJSONObject("shell_diagnostic"))?.let { data.put("shell_diagnostic", it) }
        if (actionGeneration.get() != executionGeneration) return result("cancelled", "执行已暂停，已提交的系统手势结果需核对", data = data)
        dispatchBlock.get()?.let {
            val status = VisualDispatchGate.blockedStatus(data.optString("action_state"))
            val message = if (status == "stale") "视觉动作未派发：$it，请重新观察" else "视觉动作结果未确认：$it，旧动作未重放，请核对画面"
            return result(status, message, data = data.put("reason_code", "gesture_context_changed"))
        }
        if (shellResult != null && shellResult.optString("status") != "ok") return result(shellResult.optString("status", "error"),
            "ADB视觉动作未确认，未自动重放，请核对画面", data = data.put("reason_code", shellResult.optString("reason_code")))
        if (!returned || !accepted.get()) return result("error", "系统手势未确认完成，未自动重放，请核对画面", data = data)
        var after: JSONObject? = null
        val afterShot = try {
            after = settledObservation(executionGeneration)
            screenshot(command, readiness, executionGeneration)
        } catch (_: java.util.concurrent.CancellationException) {
            JSONObject().put("status", "cancelled").put("message", "屏幕读取已中断")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            JSONObject().put("status", "cancelled").put("message", "屏幕读取已中断")
        } catch (failure: Exception) {
            JSONObject().put("status", "error").put("message", "动作后的屏幕采集未确认，请重新观察")
                .put("data", JSONObject().put("read_diagnostic", DeviceReadDiagnostic.from(failure)))
        }
        // Preserve accepted input even if its result frame is unavailable. Capture facts never overwrite input facts.
        if (readInterrupted(executionGeneration)) {
            PostActionCaptureResult.attach(data, JSONObject().put("status", "cancelled").put("message", "屏幕读取已中断"))
            return result("cancelled", "执行已暂停，已提交的系统手势结果需核对", data = data)
        }
        PostActionCaptureResult.attach(data, afterShot)
        if (afterShot.optString("status") == "cancelled")
            return result("cancelled", "执行已暂停，已提交的系统手势结果需核对", data = data)
        val message = PostActionCaptureResult.takeoverMessage(data)
            ?: if (data.has("image_base64")) "系统已完成视觉手势，请依据新截图核验结果"
            else "系统已完成视觉手势，后续画面尚未确认，动作未重放"
        return result("ok", message, afterShot.optJSONObject("observation") ?: after, data)
        } finally { touchPass?.close(); guardPass?.close() }
    }
    private fun visualDiagnostic(code: String, stage: String, details: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("reason_code", code).put("visual_diagnostic", details.put("reason_code", code).put("stage", stage))
    private fun scrollDirections(observation: JSONObject): JSONObject = JSONObject().apply {
        if (observation.optString("screen_id") != latestSnapshot?.screenId) return@apply
        val nodes = observation.optJSONArray("nodes") ?: return@apply
        for (i in 0 until nodes.length()) {
            val item = nodes.optJSONObject(i) ?: continue
            if (!item.optBoolean("scrollable") || item.optBoolean("password")) continue
            val id = item.optString("id")
            val node = refs[id] ?: continue
            put(id, JSONArray(ScrollCapabilities.directions(node.actionList.map { it.id }.toSet())))
        }
    }
    private fun readInterrupted(executionGeneration: Long) = actionGeneration.get() != executionGeneration ||
        callInProgress() || instance !== this || Thread.currentThread().isInterrupted

    private fun capturePrivateScreen() = LoginAssist.settingsVisible || DirectMode.settingsVisible

    private fun settledObservation(executionGeneration: Long): JSONObject? {
        var previous: JSONObject? = null
        var matches = 0
        repeat(6) {
            if (readInterrupted(executionGeneration)) return null
            Thread.sleep(250)
            if (readInterrupted(executionGeneration)) return null
            val current = try { observe() } catch (_: Exception) { null }
            if (readInterrupted(executionGeneration)) return null
            if (current != null && current.optString("screen_id") == previous?.optString("screen_id")) matches++ else matches = 0
            previous = current
            if (matches >= 2) return current
        }
        return previous
    }
    private data class PixelCapture(val plan: ScreenshotPayloadPlan, val pixels: VisualPixels, val frame: VisualFrame?)
    private fun ownOverlayWindow(window: AccessibilityWindowInfo, knownPackage: String? = null): Boolean {
        if (window.isFocused || window.isActive) return false
        val pkg = knownPackage ?: window.root?.let { node ->
            try { node.packageName?.toString() } finally { @Suppress("DEPRECATION") node.recycle() }
        }
        // The companion uses TYPE_APPLICATION_OVERLAY, reported as a system/application window.
        // Its capture hide/restore must not invalidate navigation in the underlying app.
        return pkg == packageName && window.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, AccessibilityWindowInfo.TYPE_SYSTEM)
    }
    private fun windowCaptureRoute(snapshot:TargetScreenSnapshot?,geometry:Triple<Int,Int,Int>):WindowCaptureRouting.Route {
        if(Build.VERSION.SDK_INT<34 || snapshot==null) return WindowCaptureRouting.Route(reason="unsupported_or_missing_window")
        val candidates=windows.filter {it.displayId==Display.DEFAULT_DISPLAY}.map {window ->
            val bounds=Rect();window.getBoundsInScreen(bounds)
            val node=window.root
            val pkg=try {node?.packageName?.toString().orEmpty()} finally {@Suppress("DEPRECATION") node?.recycle()}
            val ownOverlay=ownOverlayWindow(window,pkg)
            WindowCaptureRouting.Window(window.id,window.type==AccessibilityWindowInfo.TYPE_APPLICATION,ownOverlay,
                window.isFocused,window.isActive,pkg,listOf(bounds.left,bounds.top,bounds.right,bounds.bottom),window.isInPictureInPictureMode,
                systemUi = window.type == AccessibilityWindowInfo.TYPE_SYSTEM && pkg == "com.android.systemui")
        }
        return WindowCaptureRouting.choose(Build.VERSION.SDK_INT,geometry.first,geometry.second,snapshot.windowId,snapshot.packageName,candidates)
    }
    private fun screenshot(command: JSONObject, readiness: ScreenReadyWait, executionGeneration: Long,
        verification: java.util.concurrent.atomic.AtomicReference<PixelCapture?>? = null): JSONObject =
        readiness.read { captureScreenshotOnce(command, readiness, executionGeneration, verification) }

    private fun captureScreenshotOnce(command: JSONObject, readiness: ScreenReadyWait, executionGeneration: Long,
        verification: java.util.concurrent.atomic.AtomicReference<PixelCapture?>?): JSONObject {
        verification?.set(null)
        val result = JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id")).put("status", "error").put("message", "屏幕采集不可用").put("data", JSONObject())
        val before = readiness.read(::observe)
        val beforePrivateBounds = privateCaptureBounds
        val beforeSnapshot = latestSnapshot?.takeIf {
            it.screenId == before.optString("screen_id") && it.navigationGeneration == navigationGeneration
        }
        val beforeGeometry = displayGeometry()
        fun privateScreen() = capturePrivateScreen()
        fun blocked() = result.put("status", "blocked").put("message", "登录资料与模型连接设置期间不上传截图").put("data", JSONObject().put("human_takeover", "login"))
        if (privateScreen()) return blocked()
        val route=windowCaptureRoute(beforeSnapshot,beforeGeometry)
        val windowId=route.windowId
        if (Build.VERSION.SDK_INT >= 34 && windowId == null) return result
            .put("message", "当前主应用窗口尚不可读取，请重新观察")
            .put("data", JSONObject().put("reason_code", "capture_window_unavailable").put("window_reason", route.reason))
        val shellCapture = windowId==null && shellBridgeReady()
        if (!shellCapture && Build.VERSION.SDK_INT < 30 && !LegacyScreenCaptureService.isReady) return result.put("status", "blocked")
            .put("message", "请在 Doppel 设置的屏幕识别中授权本次屏幕采集")
            .put("data", JSONObject().put("human_takeover", "screen_capture_required"))
        if (windowId==null && !feedback.clearBeforeScreenshot()) return result.put("message", "屏幕反馈仍在清理，请重新截图")
            .put("data", visualDiagnostic("capture_feedback_pending", "capture").put("feedback_cleanup",feedback.captureCleanupDiagnostic()))
        val companion = DeviceWorkerService.instance
        val companionRevision = companion?.companionRevision
        val shieldCapture = if (windowId == null) AutomaticUnlockSession.capturePass() else AutoCloseable { }
        if (shieldCapture == null) return result.put("message", "自动任务保护界面未能隐藏，截图已取消")
        try {
        if (windowId==null && companion?.hideCompanionForScreenshot() == false) return result.put("message", "助手界面仍在清理，请重新截图")
            .put("data", visualDiagnostic("capture_companion_pending", "capture"))
        val latch = CountDownLatch(1)
        val captured = java.util.concurrent.atomic.AtomicReference<JSONObject?>()
        val visualCapture = java.util.concurrent.atomic.AtomicReference<PixelCapture?>()
        val captureRotation = beforeGeometry.third
        fun acceptBitmap(software: Bitmap, capturedAt: Long, shellSource: JSONObject? = null) {
            var outputBitmap: Bitmap? = null
            var windowBitmap: Bitmap? = null
            try {
                // Only the target window's pixels are captured. Place smaller windows at their
                // screen bounds so the existing normalized-coordinate executor stays exact.
                val source = if (windowId != null) {
                    val bounds = requireNotNull(route.bounds)
                    if (bounds == listOf(0, 0, beforeGeometry.first, beforeGeometry.second) &&
                        software.width == beforeGeometry.first && software.height == beforeGeometry.second) software
                    else Bitmap.createBitmap(beforeGeometry.first, beforeGeometry.second, Bitmap.Config.ARGB_8888).also { mapped ->
                        windowBitmap = mapped
                        android.graphics.Canvas(mapped).apply {
                            drawColor(android.graphics.Color.BLACK)
                            drawBitmap(software, null, Rect(bounds[0], bounds[1], bounds[2], bounds[3]), null)
                        }
                    }
                } else software
                val plan = ScreenshotPayloadPlan(shellSource?.getString("capture_id") ?: java.util.UUID.randomUUID().toString(),
                    before.getString("screen_id"), before.getString("package_name"), source.width, source.height, captureRotation,
                    capturedAt, verificationOnly = verification != null)
                val scaled = if (plan.imageWidth != source.width || plan.imageHeight != source.height)
                    Bitmap.createScaledBitmap(source, plan.imageWidth, plan.imageHeight, true) else source
                outputBitmap = scaled
                val pixels = IntArray(scaled.width * scaled.height); scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                val visiblePixels = ScreenPixelContent.hasVisibleRgb(pixels)
                ScreenPrivacyMask.apply(pixels, scaled.width, scaled.height, plan.displayWidth, plan.displayHeight, beforePrivateBounds)
                val output = if (beforePrivateBounds.isEmpty()) scaled else Bitmap.createBitmap(pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                outputBitmap = output
                if (scaled !== output && scaled !== source) scaled.recycle()
                if (!visiblePixels) {
                    captured.set(JSONObject().put("status", "not_ready"))
                } else {
                    var frame: VisualFrame? = null
                    val data = plan.encodeForDelivery {
                        val stream = ByteArrayOutputStream(); check(output.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        val bytes = stream.toByteArray()
                        val exportedFrame = VisualFrame(plan.captureId, plan.screenId, plan.packageName, plan.displayWidth, plan.displayHeight,
                            plan.imageWidth, plan.imageHeight, plan.rotation, plan.capturedAt, plan.expiresAt,
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        frame = exportedFrame
                        JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("mime_type", "image/png").put("visual_frame", exportedFrame.json())
                    } ?: JSONObject()
                    data.put("capture_backend", if(windowId!=null) "accessibility_window" else if (shellSource != null) "adb_shell" else if (Build.VERSION.SDK_INT < 30) "media_projection" else "accessibility")
                        .put("privacy_mask_count", beforePrivateBounds.size)
                        .put("overlay_cleanup_performed",windowId==null)
                        .put("capture_pixels_on_main_thread",android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
                    if(windowId!=null) data.put("capture_window_id",windowId).put("capture_window_bounds",JSONArray(route.bounds))
                    visualCapture.set(PixelCapture(plan, VisualPixels(output.width, output.height, pixels), frame))
                    captured.set(JSONObject().put("status", "ok").put("message", "").put("data", data))
                }
            } finally {
                if (outputBitmap !== software && outputBitmap !== windowBitmap) outputBitmap?.recycle()
                windowBitmap?.recycle()
            }
        }
        if (shellCapture) {
            val shot = ShellBridgeClient.get(this).captureAuthorized({ !readInterrupted(executionGeneration) }, { !privateScreen() })
            if (shot.optString("status") != "ok") return result.put("status", shot.optString("status", "error"))
                .put("message", "ADB截图未确认，请检查辅助权限").put("data", shot)
            val bytes = Base64.decode(shot.getString("image_base64"), Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return result.put("message", "ADB截图无法解码")
            try { acceptBitmap(bitmap, shot.getLong("captured_at"), shot) } finally { bitmap.recycle(); latch.countDown() }
        } else if (Build.VERSION.SDK_INT < 30) {
            var bitmap: Bitmap? = null
            try {
                val geometry = displayGeometry()
                val frame = LegacyScreenCaptureService.captureWithTimestamp(geometry.first, geometry.second, resources.displayMetrics.densityDpi) {
                    readInterrupted(executionGeneration)
                }
                bitmap = frame.bitmap
                acceptBitmap(frame.bitmap, frame.capturedAt)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = when (error) {
                    is java.util.concurrent.TimeoutException -> "timeout"
                    is SecurityException -> "authorization_required"
                    is IllegalArgumentException -> "invalid_geometry"
                    else -> "unavailable"
                }
                captured.set(JSONObject().put("status", if (reason == "authorization_required") "blocked" else "error")
                    .put("message", "屏幕采集不可用，请检查屏幕识别授权后重新观察")
                    .put("data", JSONObject().put("reason_code", "capture_$reason")
                        .put("capture_diagnostic", JSONObject().put("source", "media_projection").put("reason", reason))
                        .apply { if (reason == "authorization_required") put("human_takeover", "screen_capture_required") }))
            }
            finally { bitmap?.recycle(); latch.countDown() }
        } else {
        val callback=object : TakeScreenshotCallback {
            override fun onSuccess(value: ScreenshotResult) {
                // Capture age begins at callback entry, before copying, scaling or delivery encoding.
                val capturedAt = android.os.SystemClock.elapsedRealtime()
                var hardwareBitmap: Bitmap? = null
                var softwareBitmap: Bitmap? = null
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(value.hardwareBuffer, value.colorSpace)
                    hardwareBitmap = bitmap
                    if (bitmap != null) {
                        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("截图像素不可读")
                        softwareBitmap = software
                        acceptBitmap(software, capturedAt)
                    }
                } catch (_: Exception) {
                    captured.set(JSONObject().put("status", "error").put("message", "截图像素处理失败，请重新观察")); visualCapture.set(null)
                } finally {
                    softwareBitmap?.recycle(); hardwareBitmap?.recycle()
                    value.hardwareBuffer.close(); latch.countDown()
                }
            }
            override fun onFailure(errorCode: Int) {
                val denied=errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW || errorCode==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                val reason=when(errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure_window"
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility_access_required"
                    ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid_window"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid_display"
                    else -> "internal_error"
                }
                captured.set(JSONObject().put("status",if(denied) "blocked" else "error")
                        .put("message",if(denied) "当前窗口禁止截图或无障碍截图权限不可用" else "截图不可用，请重新观察")
                        .put("data",JSONObject().put("reason_code","capture_$reason").put("capture_error_code",errorCode)
                            .put("capture_backend",if(windowId!=null) "accessibility_window" else "accessibility")))
                latch.countDown()
            }
        }
        try {
            if(windowId!=null && Build.VERSION.SDK_INT>=34) takeScreenshotOfWindow(windowId,screenshotExecutor,callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY,screenshotExecutor,callback)
        } catch(_:SecurityException) {callback.onFailure(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)}
        catch(_:UnsupportedOperationException) {callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)}
        catch(_:IllegalArgumentException) {callback.onFailure(ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)}
        }
        // Late callbacks own a separate result and cannot attach an image after timeout.
        val captureDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (latch.count > 0) {
            if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
            val remaining = captureDeadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return result.put("status", "error").put("message", "截图超时")
                .put("data", visualDiagnostic("capture_timeout", "capture"))
            latch.await(minOf(100, remaining), TimeUnit.MILLISECONDS)
        }
        if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
        val shot = captured.get() ?: return result.put("message", "截图处理失败")
        if (shot.optString("status") == "not_ready") {
            android.util.Log.i("DoppelFrameReadiness", "reason=empty_frame elapsed_ms=${android.os.SystemClock.elapsedRealtime()}")
            throw ScreenNotReadyException(ScreenReadinessReason.EMPTY_FRAME)
        }
        result.put("status", shot.optString("status", "error")).put("message", shot.optString("message")).put("data", shot.optJSONObject("data") ?: JSONObject())
        if (result.optString("status") == "ok") {
            val after = readiness.read(::observe)
            if (privateScreen()) return blocked()
            if (beforePrivateBounds.toSet() != privateCaptureBounds.toSet()) return result.put("status", "stale")
                .put("message", "敏感输入区域在截图期间变化，请重新观察").put("data", visualDiagnostic("capture_private_region_changed", "capture"))
            if (DeviceWorkerService.instance !== companion || companion?.companionRevision != companionRevision) return result.put("status", "stale").put("message", "助手窗口在截图期间重建，请重新观察").put("data", visualDiagnostic("capture_companion_changed", "capture"))
            val capture = visualCapture.get() ?: return result.put("status", "error").put("message", "截图来源记录缺失").put("data", JSONObject())
            val dimensions = displayGeometry()
            if (beforeGeometry != dimensions || capture.plan.displayWidth != dimensions.first || capture.plan.displayHeight != dimensions.second || capture.plan.rotation != dimensions.third)
                return result.put("status", "stale").put("message", "截图尺寸或方向已变化，请重新观察").put("data", visualDiagnostic("capture_geometry_changed", "capture"))
            val afterSnapshot = latestSnapshot?.takeIf {
                it.screenId == after.optString("screen_id") && it.navigationGeneration == navigationGeneration
            }
            if(windowId!=null && windowCaptureRoute(afterSnapshot,dimensions).let { it.windowId!=windowId || it.bounds!=route.bounds })
                return result.put("status", "stale").put("message", "主应用窗口位置已变化，请重新观察")
                    .put("data", visualDiagnostic("capture_window_changed", "capture"))
            val binding = CaptureObservationBinding.bind(capture.plan, beforeSnapshot, afterSnapshot, beforeGeometry, dimensions, android.os.SystemClock.elapsedRealtime())
                ?: return result.put("status", "stale").put("message", "截图期间窗口、导航或采集来源发生变化，请重新观察")
                    .put("data", visualDiagnostic("capture_screen_changed", "capture")).put("observation", after)
            val paired = PixelCapture(binding.plan, capture.pixels, capture.frame?.let(binding::bindFrame))
            result.getJSONObject("data").put("capture_observation", binding.metadata())
            paired.frame?.let { result.getJSONObject("data").put("visual_frame", it.json()) }
            if (verification != null) verification.set(paired)
            else visualCaptures.put(VisualCapture(requireNotNull(paired.frame), paired.pixels, binding.navigation))
            result.put("observation", after)
        }
        return result
        } finally { try { if(windowId==null) companion?.restoreCompanionAfterScreenshot() } finally { shieldCapture.close() } }
    }
}
