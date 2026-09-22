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
    private val windowPackages = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var navigationGeneration = 0
    private var windowSignature = ""
    private val touchGuards = mutableListOf<View>()
    private val guardTouchPass = CompanionTouchPassState()
    private val guardPassDiagnostic = java.util.concurrent.atomic.AtomicReference<TouchHandoffDiagnostic?>()
    private var guardRequested = false
    private val ownGestureGeneration = java.util.concurrent.atomic.AtomicLong(-1)
    private val platformGestures = DeviceActionDrain()
    @Volatile private var gestureReleaseFailed = false
    private fun dispatchTrackedGesture(runId: String, description: GestureDescription, callback: GestureResultCallback,
        handler: android.os.Handler, allowed: () -> Boolean): Boolean = platformGestures.dispatch(runId, allowed) { finish ->
            dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    finish(); callback.onCompleted(gestureDescription)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finish(); callback.onCancelled(gestureDescription)
                }
            }, handler)
    }
    /** HOLD completion retains DOWN; only the terminal continuation releases this drain ticket. */
    private fun dispatchHeldGesture(runId: String, path: Path, start: FeedbackPoint, holdMs: Long, moveMs: Long,
        callback: GestureResultCallback, allowed: () -> Boolean): Boolean = platformGestures.dispatch(runId, allowed) { finish ->
        val stationary = Path().apply { moveTo(start.x, start.y) }
        val hold = GestureDescription.StrokeDescription(stationary, 0, holdMs, true)
        lateinit var deadline: Runnable
        val dispatch=HeldGestureDispatch(allowed, { phase, returned ->
            // Recheck immediately before movement. Cleanup must still release DOWN after revocation.
            if (phase == HeldGestureDispatch.Phase.MOVE && !allowed()) false
            else {
                val stroke = when (phase) {
                    HeldGestureDispatch.Phase.HOLD -> hold
                    HeldGestureDispatch.Phase.MOVE -> hold.continueStroke(path, 0, moveMs, false)
                    HeldGestureDispatch.Phase.RELEASE -> hold.continueStroke(stationary, 0, 1, false)
                }
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { returned(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { returned(false) }
                }, mainHandler)
            }
        }, { completed ->
            mainHandler.removeCallbacks(deadline)
            finish()
            if (completed) callback.onCompleted(null) else callback.onCancelled(null)
        }, {
            mainHandler.removeCallbacks(deadline)
            // Neither a failed cleanup submission nor a missing callback proves UP. Close
            // admission and let Android tear down this service's injector before recovery.
            gestureReleaseFailed=true
            TaskControl.invalidate()
            val reason="触摸结束未确认，任务已暂停。请重新启用 Doppel 无障碍服务，再核对画面后继续。"
            val worker=DeviceWorkerService.instance
            if(worker!=null) worker.stopWithReason(reason,"interruption",preservePrevious=false)
            else runCatching { DirectRuntime.interrupt(this,reason) }
            AutomaticUnlockSession.interrupted()
            android.util.Log.e("DoppelGesture", "held_gesture_release_unconfirmed")
            runCatching { disableSelf() }.onFailure { android.util.Log.e("DoppelGesture","service_disable_failed") }
        })
        deadline=Runnable {dispatch.timeout()}
        dispatch.start().also { accepted -> if(accepted) mainHandler.postDelayed(deadline,holdMs+moveMs+2000) }
    }
    /** Enter after admission is closed; an abandoned wait is not a platform completion callback. */
    @Synchronized internal fun awaitExecutionStopped(runIds: Set<String>? = null): Boolean = platformGestures.awaitStopped(runIds)
    internal fun afterGesturesStopped(release: () -> Unit) = platformGestures.afterStopped(release=release)
    private var guardedCompanion: Rect? = null
    private val feedback by lazy { ActionFeedbackOverlay(this) }
    private val actionGeneration = visualCaptures.generation
    private val sensitiveFingerprintSalt = java.util.UUID.randomUUID().toString()
    private val feedbackSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "action_feedback" && !prefs.getBoolean(key, true)) prefs.edit().putBoolean("action_feedback", true).apply()
    }
    @Volatile private var privateCaptureBounds: List<List<Int>> = emptyList()
    private val privateWindowBounds = mutableMapOf<Int, List<List<Int>>>()
    private val privateWindowGeometry = mutableMapOf<Int, Triple<Int, Int, Int>>()
    private var privacyBackdropWindowId: Int? = null
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
        gestureReleaseFailed=true
        instance=null
        platformGestures.onDisconnected()
        if (AutomaticUnlockSession.active) { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); AutomaticUnlockSession.interrupted() }
        AccessibilityControlPicker.stop()
        if (Build.VERSION.SDK_INT >= 31) (modeListener as? android.media.AudioManager.OnModeChangedListener)?.let { getSystemService(android.media.AudioManager::class.java).removeOnModeChangedListener(it) }
        getSharedPreferences("doppel", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(feedbackSettingsListener); stopActionFeedback(); visualCaptures.clear(); LoginAssist.clearSession(); setTouchGuard(false); targetHistory.clear(); instance = null; super.onDestroy()
    }
    override fun onInterrupt() { logTouchPause("service_interrupt"); DeviceWorkerService.instance?.pauseActiveRun() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { it.isNotBlank() && event.windowId >= 0 }?.let { pkg ->
            // Only system-delivered window identities, never screen text or a model-supplied package.
            if (windowPackages.size >= 64 && !windowPackages.containsKey(event.windowId)) windowPackages.clear()
            windowPackages[event.windowId] = pkg
        }
        val ownOverlayState = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == packageName && windows.any {
                it.id == event.windowId && (it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || ownOverlayWindow(it))
            }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !ownOverlayState) {
            // A delayed event from our overlay may arrive before Android lists that window.
            // It is not navigation when a different window is still in the foreground.
            val foreground = visualWindow()
            val ownBackgroundEvent = event.packageName?.toString() == packageName &&
                foreground != null && event.windowId != foreground.id
            if (!ownBackgroundEvent) navigationGeneration++
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val signature = windows.filter { !ownCaptureOverlay(it) }.joinToString { window ->
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
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && !AutomaticUnlockSession.active) {
            navigationGeneration++; targetHistory.clear()
            if (getSharedPreferences("doppel", MODE_PRIVATE).getBoolean("touch_pause", true)) {
                logTouchPause("accessibility_touch_start"); DeviceWorkerService.instance?.pauseActiveRun()
            }
        }
        // Keep this after interruption detection so a system dialog/call always wins.
        // AutoTriggerEngine is node-only and does not issue model/network requests.
        runCatching { autoTriggers.onEvent(event) }
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
    private fun visualWindow(): AccessibilityWindowInfo? {
        val current = windows.filter { it.displayId == Display.DEFAULT_DISPLAY &&
            it.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_SYSTEM) }
            .sortedByDescending { it.layer }
        return current.firstOrNull { it.isFocused } ?: current.firstOrNull { it.isActive && !ownOverlayWindow(it) }
    }
    private fun visualWindowPackage(window: AccessibilityWindowInfo): String {
        val node = window.root
        val pkg = try { node?.packageName?.toString()?.takeIf { it.isNotBlank() } }
            finally { @Suppress("DEPRECATION") node?.recycle() }
        if (pkg != null) windowPackages[window.id] = pkg
        // A window ID remains authoritative even when Android withholds the package and tree.
        // The explicit window: prefix is not a claim that this is a real application package.
        return pkg ?: windowPackages[window.id] ?: "window:${window.id}"
    }
    private fun currentVisualWindowMatches(windowId: Int?, pkg: String): Boolean {
        // Window events can reach this service later than the new visible window.
        // Read current system metadata before injection instead of trusting the event cache.
        if (Build.VERSION.SDK_INT >= 33) clearCache()
        return visualWindow()?.let { it.id == windowId && visualWindowPackage(it) == pkg } == true
    }

    private fun captureWindowSignature(): String = windows.filter { it.displayId == Display.DEFAULT_DISPLAY &&
        !ownCaptureOverlay(it) }
        .sortedBy { it.id }.joinToString { window ->
            val bounds = Rect().also(window::getBoundsInScreen)
            "${window.id}:${window.type}:${window.layer}:${window.isFocused}:${window.isActive}:$bounds"
        }

    private fun capturePrivacyWindowIds(): Set<Int> = windows.filter { it.displayId == Display.DEFAULT_DISPLAY &&
        !ownCaptureOverlay(it) }.map { it.id }.toSet() +
        listOfNotNull(latestSnapshot?.windowId, privacyBackdropWindowId)

    private fun updateCapturePrivacyBounds() {
        val visible = capturePrivacyWindowIds()
        privateCaptureBounds = privateWindowBounds.filterKeys { it in visible }.values.flatten().distinct()
    }

    private fun rememberPrivacyBackdrop(windowId: Int) {
        val window = windows.firstOrNull { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return
        val bounds = Rect().also(window::getBoundsInScreen)
        val size = displayGeometry()
        val viewport = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            Rect(metrics.bounds).apply { left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom }
        } else Rect(0, 0, size.first, size.second)
        if (bounds.contains(viewport)) privacyBackdropWindowId = windowId
    }

    /** Optional privacy metadata, never a prerequisite for visual navigation. */
    private fun refreshVisibleCapturePrivacy(): Boolean {
        val active = latestSnapshot?.windowId
        val backdrop = privacyBackdropWindowId
        var backdropReadable = backdrop == null || backdrop == active
        for (window in windows) {
            if (window.id == active || ownCaptureOverlay(window)) continue
            val root = window.root
            if (root == null) {
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    val rect = Rect().also(window::getBoundsInScreen)
                    if (!rect.isEmpty) {
                        privateWindowBounds[window.id] = listOf(listOf(rect.left, rect.top, rect.right, rect.bottom))
                        privateWindowGeometry[window.id] = displayGeometry()
                    }
                }
                continue
            }
            val regions = mutableListOf<List<Int>>()
            val nodes = JSONArray()
            var complete = root.isVisibleToUser
            fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                if (!node.isVisibleToUser) return
                if (depth > 30 || nodes.length() >= 300) { complete = false; return }
                val rect = Rect().also(node::getBoundsInScreen)
                val labels = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                    node.hintText?.toString().orEmpty(), if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else "")
                val inputLabel = "${labels[1]} ${labels[2]} ${node.viewIdResourceName.orEmpty()}"
                val bounds = listOf(rect.left, rect.top, rect.right, rect.bottom)
                if (!rect.isEmpty && (node.isPassword || node.isEditable && (Policy.codeInput(inputLabel) || Policy.phoneInput(inputLabel)) ||
                        DeviceReadPrivacy.codeNotification("", labels.joinToString(" ")) || labels.any(LoginAssist::containsPrivateValue))) regions += bounds
                nodes.put(JSONObject().put("id", path).put("parent_id", path.substringBeforeLast('_', ""))
                    .put("class_name", node.className).put("resource_id", node.viewIdResourceName).put("bounds", JSONArray(bounds))
                    .put("text", labels[0]).put("description", labels[1]).put("hint", labels[2]).put("state_description", labels[3]))
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) { complete = false; continue }
                    try { visit(child, "${path}_$index", depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
                }
            }
            try {
                visit(root, "n", 0)
                if (root.packageName?.toString() == "com.android.systemui") regions += SystemUiNotificationPrivacy.find(nodes, regions).bounds
                privateWindowBounds[window.id] = (if (complete) regions else privateWindowBounds[window.id].orEmpty() + regions).distinct()
                if (complete) privateWindowGeometry[window.id] = displayGeometry()
                if (window.id == backdrop && complete) backdropReadable = true
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        // A dialog can remove the covered application from Android's interactive-window list.
        // Keep its known secret regions until a readable observation of that window replaces them.
        updateCapturePrivacyBounds()
        // A hidden password page may scroll/resize while its tree is unavailable. Old
        // coordinates cannot protect its current pixels in a full-display capture.
        return backdropReadable || privateWindowBounds[backdrop].orEmpty().isEmpty()
    }
    fun stopActionFeedback() {
        val invalidatedGeneration = visualCaptures.stopFeedback()
        feedback.clear()
        val companion=DeviceWorkerService.instance
        platformGestures.afterStopped {
            companion?.stopCompanionGestureTouchPass(invalidatedGeneration)
            mainHandler.post { guardTouchPass.clearBefore(invalidatedGeneration) }
        }
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
                DeviceWorkerService.instance?.takeIf { !it.isPaused }?.pauseActiveRun()
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
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) { logTouchPause("guard_down"); navigationGeneration++; targetHistory.clear(); DeviceWorkerService.instance?.pauseActiveRun(); setTouchGuard(false) }
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
                try { TemporaryScreenshotExclusion.addView(manager, guard, params); touchGuards.add(guard) } catch (_: Exception) {
                    removeTouchGuardsImmediately(); stopActionFeedback(); DeviceWorkerService.instance?.pauseActiveRun(); break
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
            mainHandler.postDelayed({
                platformGestures.afterStopped { mainHandler.post { release(owner) } }
            }, durationMs.coerceIn(3000, 60000))
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
    @Synchronized override fun observe(): JSONObject = observeScreen(false)
    @Synchronized private fun observeVisual(): JSONObject = observeScreen(true)
    private fun observeScreen(visual: Boolean): JSONObject {
        val executionGeneration = actionGeneration.get()
        val taskGeneration = TaskControl.currentGeneration()
        val operation = AutomaticUnlockSession.deviceOperation {
            actionGeneration.get() == executionGeneration && TaskControl.isCurrent(taskGeneration) &&
                instance === this && !Thread.currentThread().isInterrupted
        } ?: throw ScreenReadInterruptedException()
        try {
        if (AutomaticUnlockSession.isUnlocking || AutomaticUnlockSession.isAuthenticating) throw ScreenNotReadyException()
        refs.clear()
        // Capture/visual observation boundaries must not bind new pixels to cached old windows.
        if (visual && Build.VERSION.SDK_INT >= 33) clearCache()
        val generation = navigationGeneration
        val visualWindow = if (visual) visualWindow() else null
        val root = if (visual) visualWindow?.root else activeRoot()
        if (root == null) {
            if (!visual || visualWindow == null) throw ScreenNotReadyException()
            val pkg = visualWindowPackage(visualWindow)
            val geometry = displayGeometry()
            val privateUnknown = LoginAssist.sensitiveSessionActive() ||
                privateWindowBounds[visualWindow.id].orEmpty().isNotEmpty() || visualWindow.type == AccessibilityWindowInfo.TYPE_SYSTEM
            // Losing a tree (including opening a dialog) is not evidence that a secret disappeared.
            updateCapturePrivacyBounds()
            val rect = Rect().also(visualWindow::getBoundsInScreen)
            val screenId = Policy.hash("visual|$pkg|${visualWindow.id}|$generation|$geometry|$rect")
            latestSnapshot = TargetScreenSnapshot(screenId, pkg, visualWindow.id, generation, geometry.first, geometry.second,
                android.os.SystemClock.elapsedRealtime(), emptyList(), false)
            targetHistory.clear()
            val observation = JSONObject().put("screen_id", screenId).put("package_name", pkg)
                .put("width", geometry.first).put("height", geometry.second).put("nodes", JSONArray())
                .put("tree_complete", false).put("tree_available", false).put("assistant_surface", pkg == packageName)
                .put("captured_at", System.currentTimeMillis()).put("payment_consent_id", PaymentConsent(this).currentId() ?: JSONObject.NULL)
                .put("login_credentials", JSONArray()).put("login_assist", JSONObject())
            return ScreenCapturePrivacy.attach(observation, privateUnknown || capturePrivateScreen() || PaymentConsent.settingsVisible)
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) windowPackages[root.windowId] = pkg
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
        privateWindowBounds[root.windowId] = if (complete && generation == navigationGeneration) sensitiveBounds.distinct()
            else (privateWindowBounds[root.windowId].orEmpty() + sensitiveBounds).distinct()
        if (complete && generation == navigationGeneration) {
            privateWindowGeometry[root.windowId] = displayGeometry()
            rememberPrivacyBackdrop(root.windowId)
        }
        updateCapturePrivacyBounds()
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
            .put("nodes", nodes).put("tree_complete", snapshot.complete).put("tree_available", true).put("captured_at", System.currentTimeMillis())
            .put("payment_consent_id", paymentConsentId ?: JSONObject.NULL)
        observation.put("login_credentials", credentialLabels)
        observation.put("login_assist", LoginAssist(this).taskStatus(pkg, getSharedPreferences("doppel", MODE_PRIVATE).getString("active_run", null)))
        ObservationCoverage.collectionEvidence(observation)?.let { observation.put("collection_evidence", it) }
        // Match the existing screenshot privacy barrier, but never turn private settings into a usable page.
        return ScreenCapturePrivacy.attach(observation, privateSettings)
        } finally { operation.close() }
    }
    override fun execute(command: JSONObject): JSONObject = execute(command, TaskControl.currentGeneration())

    /** Capture caller authority before waiting for another device operation; never issue a fresh ticket here. */
    internal fun execute(command: JSONObject, taskGeneration: Long, isCurrent: () -> Boolean = { true }): JSONObject =
        executeGuarded(command, TaskControl.captureExecutionPermit(command.getString("run_id"), taskGeneration, isCurrent))

    /** Human LAN view: reuse the normal full-screen capture/privacy pipeline, never invoke a model. */
    @Synchronized internal fun captureHandoffFrame(current: () -> Boolean): JSONObject {
        val command = JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("run_id", "lan-handoff-view")
            .put("kind", "screenshot").put("split_agent", true)
        val receipt = executeGuarded(command, current)
        if (receipt.optString("status") != "ok") throw dev.doppel.sdk.companion.CompanionProtocolException(409,
            receipt.optJSONObject("data")?.optString("reason_code")?.takeIf { it.isNotBlank() } ?: "capture_unavailable")
        val data = receipt.getJSONObject("data")
        val frame = data.getJSONObject("visual_frame")
        val navigation = visualCaptures.remove(frame.getString("capture_id"))?.navigation
            ?: throw dev.doppel.sdk.companion.CompanionProtocolException(409, "frame_changed")
        return JSONObject().put("image_base64", data.getString("image_base64"))
            .put("_window_id", navigation.windowId).put("_navigation_generation", navigation.navigationGeneration)
            .put("_package_name", frame.getString("package_name")).apply {
                for (key in listOf("display_width", "display_height", "image_width", "image_height", "rotation")) put(key, frame.get(key))
            }
    }

    /** Human coordinates are explicit authorization; do not run them through the AI's semantic policy. */
    @Synchronized internal fun executeHandoffAction(runId: String, frame: JSONObject, action: JSONObject,
                                                   current: () -> Boolean): JSONObject {
        val expected = Triple(frame.getInt("display_width"), frame.getInt("display_height"), frame.getInt("rotation"))
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val companion = DeviceWorkerService.instance
        val revision = companion?.companionRevision
        val generation = actionGeneration.get()
        fun currentHost() = current() && instance === this && !callInProgress() &&
            actionGeneration.get() == generation && DeviceWorkerService.instance === companion && companion?.companionRevision == revision &&
            !AutomaticUnlockSession.active && !AutomaticUnlockSession.locked(this) &&
            !capturePrivateScreen() && !PaymentConsent.settingsVisible
        fun permitted() = accepting.get() && currentHost()
        fun sameFrame() = permitted() && expected == displayGeometry() &&
            frame.getInt("_navigation_generation") == navigationGeneration &&
            currentVisualWindowMatches(frame.getInt("_window_id"), frame.getString("_package_name"))
        if (!sameFrame()) return JSONObject().put("status", "stale").put("reason_code", "frame_changed")
        val kind = action.getString("kind")
        val duration = when (kind) { "tap" -> 60L; "swipe", "long_press" -> action.getLong("duration_ms"); else -> 0L }
        val gate = CountDownLatch(1)
        val submitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val physical = kind in setOf("tap", "swipe", "long_press")
        val pass = if (physical) companion?.beginCompanionGestureTouchPass(generation, duration + 3000, ::permitted) else null
        if (physical && companion != null && pass == null)
            return JSONObject().put("status", "cancelled").put("reason_code", "touch_handoff_unavailable")
        var guard: AutoCloseable? = null
        try {
            if (physical) {
                guard = beginGuardGestureTouchPass(generation, duration + 3000, ::currentHost)
                if (guard == null) return JSONObject().put("status", "cancelled").put("reason_code", "touch_handoff_unavailable")
            }
            mainHandler.post {
                try {
                    if (!accepting.get() || !sameFrame()) { gate.countDown(); return@post }
                    if (physical) {
                        fun x(key: String) = (action.getDouble(key) * (expected.first - 1)).toFloat()
                        fun y(key: String) = (action.getDouble(key) * (expected.second - 1)).toFloat()
                        val path = Path().apply {
                            moveTo(x("x"), y("y"))
                            if (kind == "swipe") lineTo(x("end_x"), y("end_y"))
                        }
                        val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                        submitted.set(dispatchTrackedGesture(runId, description, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) { completed.set(true); gate.countDown() }
                            override fun onCancelled(gestureDescription: GestureDescription?) { gate.countDown() }
                        }, mainHandler) { accepting.get() && sameFrame() })
                        if (!submitted.get()) gate.countDown()
                    } else {
                        val accepted = if (kind == "type") {
                            val root = activeRoot()
                            val editor = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            try {
                                editor?.isEditable == true && editor.isEnabled && sameFrame() && editor.performAction(
                                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.getString("text"))
                                    })
                            } finally {
                                @Suppress("DEPRECATION") editor?.recycle()
                                @Suppress("DEPRECATION") root?.recycle()
                            }
                        } else DeviceEnvironment.globalActions[kind]?.let { sameFrame() && performGlobalAction(it) } == true
                        submitted.set(accepted); completed.set(accepted); gate.countDown()
                    }
                } catch (_: Exception) { gate.countDown() }
            }
            val returned = gate.await(duration + 2000, TimeUnit.MILLISECONDS)
            return JSONObject().put("status", when {
                returned && completed.get() -> "ok"
                submitted.get() -> "unconfirmed"
                !currentHost() -> "cancelled"
                else -> "error"
            })
        } finally { accepting.set(false); guard?.close(); pass?.close() }
    }

    private class Execution(val generation: Long, val current: () -> Boolean)

    @Synchronized private fun executeGuarded(command: JSONObject, current: () -> Boolean): JSONObject {
        fun result(status: String, message: String = "", observation: JSONObject? = null, data: JSONObject = JSONObject()) =
            JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
                .put("status", status).put("message", message).put("observation", observation ?: JSONObject.NULL)
                .put("data", data.apply { if (observation != null) put("scroll_directions", scrollDirections(observation)) })
        try {
            val executionGeneration = Execution(actionGeneration.get(), current)
            val operation = AutomaticUnlockSession.deviceOperation {
                !readInterrupted(executionGeneration)
            } ?: return result("cancelled", "执行已暂停，动作未派发")
            operation.use {
            val readiness = ScreenReadyWait(android.os.SystemClock::elapsedRealtime, Thread::sleep,
                { readInterrupted(executionGeneration) }, if (command.optBoolean("split_agent")) 2000 else 5000)
            val kind = command.getString("kind").let { if (it == "pay") "tap" else it }
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
                if (readInterrupted(executionGeneration)) return result("cancelled", "读取应用列表已中断")
                return result("ok", "已读取可启动应用", data = apps)
            }
            if (kind in SplitAgentProtocol.readActions) {
                val read = DeviceReadTools.execute(this, kind, command) {
                    !readInterrupted(executionGeneration)
                }
                return result(read.optString("status", "error"), read.optString("message"),
                    data = read.optJSONObject("data") ?: JSONObject())
            }
            if (kind == "observe") {
                val observation = readiness.read { if (command.optBoolean("include_screenshot")) observeVisual() else observe() }
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
                    if (readInterrupted(executionGeneration) || callInProgress()) return result("cancelled", "等待已中断")
                    Thread.sleep(minOf(100, end - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1))
                }
                return result("ok", observation = if(command.optBoolean("split_agent")) null else readiness.read(::observe))
            }
            val mutation = kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "login_username", "login_password", "ime_action", "scroll", "back", "home", "menu", "launch", "open_document", "visual_gesture", "split_action") || kind in DeviceEnvironment.globalActions || kind in SplitAgentProtocol.nativeActions
            // Protected settings may have no readable accessibility root at all.
            if (mutation && PaymentConsent.settingsVisible) {
                stopActionFeedback(); setTouchGuard(false); targetHistory.clear()
                return result("blocked", "付款授权只能由用户在设置中手动更改", data = JSONObject().put("human_takeover", "payment"))
            }
            val visualCommand = kind in setOf("split_action", "visual_gesture") || kind in SplitAgentProtocol.nativeActions
            val current = if (mutation) { if (visualCommand) observeVisual() else observe() } else null
            if (current != null) {
                if (ScreenCapturePrivacy.unavailable(current) ||
                    (LoginAssist.settingsVisible || DirectMode.settingsVisible) && current.optString("package_name") == packageName) {
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
                val loginPermit = command.optString("login_verification_permit")
                val allowedLoginVerification = kind == "split_action" && loginPermit.isNotBlank() &&
                    DirectRuntime.allowsLoginVerification(this, command.getString("run_id"), current.optString("package_name"), loginPermit)
                if (verification && !allowedLoginVerification || loginPermit.isNotBlank() && !allowedLoginVerification) {
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
            if (kind in setOf("tap", "long_press", "type", "login_phone", "login_code", "login_username", "login_password", "ime_action", "scroll")) {
                val fresh = current!!
                val reference = command.optString("target")
                val loginTargets = if (kind in setOf("login_phone", "login_code")) refs.filterValues { candidate ->
                    val label = "${candidate.hintText?.toString().orEmpty()} ${candidate.contentDescription?.toString().orEmpty()} ${candidate.viewIdResourceName.orEmpty()}"
                    candidate.isEditable && candidate.isEnabled && candidate.isVisibleToUser &&
                        if (kind == "login_phone") Policy.phoneInput(label) else Policy.codeInput(label)
                } else emptyMap()
                val resolved = if (reference.isNotEmpty() && reference != "null") reference else if (kind == "scroll") refs.entries.firstOrNull { it.value.isScrollable }?.key.orEmpty()
                    else if (kind in setOf("login_username", "login_password")) refs.entries.filter {
                        it.value.isFocused && it.value.isPassword == (kind == "login_password") && it.value.isEditable
                    }.singleOrNull()?.key.orEmpty()
                    else loginTargets.entries.singleOrNull { it.value.isFocused }?.key ?: loginTargets.entries.singleOrNull()?.key.orEmpty()
                val node = refs[resolved]
                if (kind in setOf("login_username", "login_password") && node == null) return result("error", "请先点击当前应用对应的账号或密码输入框，再请求填写已授权资料", fresh)
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
                val paymentAction = command.optString("kind") == "pay"
                val consentId = command.optString("payment_consent_id").takeIf { it.isNotBlank() && it != "null" }
                val paymentAuthorized = consentId != null && consentId == PaymentConsent(this).currentId()
                if (consentId != null && !paymentAction || paymentAction && !Policy.canPay(command.optString("mode"), paymentAuthorized, fresh.optString("package_name")))
                    return result("blocked", "付款需要完全访问模式及当前有效的支付授权", fresh, JSONObject().put("human_takeover", "payment"))
                val snapshot = latestSnapshot
                if (snapshot == null || snapshot.navigationGeneration != navigationGeneration) return result("stale", "页面导航已变化，请重新观察", fresh)
                val expected = command.optString("screen_id")
                val privateInputLabel = "${node?.hintText?.toString().orEmpty()} ${node?.contentDescription?.toString().orEmpty()} ${node?.viewIdResourceName.orEmpty()}"
                val privateInput = node != null && (node.isPassword || Policy.codeInput(privateInputLabel) || Policy.phoneInput(privateInputLabel))
                val stable = kind !in SplitAgentProtocol.loginActions && !paymentAction && consentId == null && expected != snapshot.screenId && !(kind == "type" && privateInput) && targetHistory.revalidates(expected, snapshot, resolved, kind)
                val verdict = Policy.validate(if (kind == "ime_action") "type" else kind, if (stable) snapshot.screenId else expected, snapshot.screenId, target, command.optString("mode", "assist"))
                if (verdict != "ok") return result(verdict, if (verdict == "stale") "页面或目标已变化，请重新观察" else "支付或敏感输入必须由用户接管", fresh)
                val input = kind in setOf("type", "login_phone", "login_code", "login_username", "login_password")
                if (input && (!node!!.isEditable || kind == "type" && command.optString("text").length > 8000)) return result("blocked", "输入目标无效", fresh)
                if (kind == "long_press" && !node!!.isLongClickable) return result("blocked", "目标不支持长按", fresh)
                if (kind == "scroll" && (!node!!.isScrollable || command.optString("direction") !in setOf("up", "down", "left", "right"))) return result("blocked", "滚动目标或方向无效", fresh)
                val scrollAction = if (kind == "scroll") ScrollCapabilities.action(command.optString("direction"), node!!.actionList.map { it.id }.toSet()) else null
                if (kind == "scroll" && scrollAction == null) return result("ok", "未执行滚动：当前控件不支持该方向", fresh,
                    JSONObject().put("no_op", true).put("action_state", "direction_unavailable"))
                if (kind in SplitAgentProtocol.loginActions && (command.optString("package_name") != fresh.getString("package_name") || !command.isNull("text"))) return result("blocked", "登录目标应用不匹配", fresh, JSONObject().put("human_takeover", "login"))
                val inputLabel = "${node!!.hintText?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()} ${node.viewIdResourceName.orEmpty()}"
                if (kind == "login_code" && !Policy.codeInput(inputLabel) || kind == "login_phone" && !Policy.phoneInput(inputLabel))
                    return result("blocked", "目标未标识为对应的登录输入框，请人工填写", fresh, JSONObject().put("human_takeover", "login"))
                if (kind in setOf("login_username", "login_password") &&
                    (node.isPassword != (kind == "login_password") || !node.isFocused || command.optString("credential_label").isBlank()))
                    return result("blocked", "请选择已授权的资料并聚焦对应账号或密码输入框", fresh, JSONObject().put("human_takeover", "login"))
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
                if (readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停", fresh)
                if (kind in setOf("login_username", "login_password")) {
                    val filled = CredentialVault(this).fillForTask(fresh.getString("package_name"), command.getString("credential_label"), node,
                        if (kind == "login_username") "username" else "password") {
                        DeviceWorkerService.instance?.allowsCredentialInput(command.getString("run_id")) == true &&
                            !readInterrupted(executionGeneration) && !DirectMode.settingsVisible && !LoginAssist.settingsVisible &&
                            snapshot.navigationGeneration == navigationGeneration && foregroundPackage() == fresh.getString("package_name")
                    }
                    targetHistory.clear()
                    return if (filled) result("ok", "已在本机填写授权登录资料，请核对登录结果", settledObservation(executionGeneration), JSONObject().put("action_state", "accepted"))
                        else result("blocked", "当前任务、应用或密码资料未获填写授权，请检查登录设置", fresh, JSONObject().put("human_takeover", "login"))
                }
                fun loginAllowed(): Boolean {
                    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                    return DeviceWorkerService.instance?.allowsCredentialInput(command.getString("run_id")) == true &&
                        !readInterrupted(executionGeneration) && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
                        !DirectMode.settingsVisible && !LoginAssist.settingsVisible && snapshot.navigationGeneration == navigationGeneration &&
                        foregroundPackage() == fresh.getString("package_name") && node.refresh() && node.isEditable && node.isVisibleToUser && node.isEnabled &&
                        node.packageName?.toString() == fresh.getString("package_name")
                }
                val inputText = if (kind in setOf("login_phone", "login_code")) {
                    if (!loginAllowed()) return result("blocked", "当前任务或设备状态不允许填写登录资料", fresh, JSONObject().put("human_takeover", "login"))
                    val login = LoginAssist(this)
                    val candidate = (command.opt("code_candidate_id") as? String)?.takeIf { it.isNotBlank() }
                    val value = runCatching { login.valueFor(kind, fresh.getString("package_name"), command.getString("run_id"), candidate,
                        startCodeSession = !command.optBoolean("split_agent")) }.getOrNull()
                    if (value == null) {
                        val state = login.taskStatus(fresh.getString("package_name"), command.getString("run_id"))
                        val waiting = kind == "login_code" && state.optBoolean("enabled") && state.optBoolean("notification_access") && state.optBoolean("session_started") && state.optString("code_state") == "waiting"
                        return result(if (waiting) "ok" else "error", if (waiting) "尚未收到本次登录的新验证码，可继续等待后检查状态" else "登录资料或新验证码不可用，请检查本机授权、发送状态或有效期", fresh,
                            JSONObject().put("action_state", if (waiting) "waiting_for_code" else "not_dispatched").put("retry_after_ms", if (waiting) 1500 else 0))
                    }
                    value
                } else command.optString("text")
                if (readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停", fresh)
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
                        !readInterrupted(executionGeneration) && snapshot.navigationGeneration == navigationGeneration &&
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
                if (paymentAttempt == null && readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停，动作未派发", fresh)
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
            if (mutation && readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停", current)
            if (kind in SplitAgentProtocol.nativeActions) {
                if (command.optBoolean("split_agent")) {
                    val capture = visualCaptures.remove(command.optJSONObject("source")?.optString("capture_id").orEmpty())
                    val dimensions = displayGeometry()
                    if (capture?.canVerifyAgainst(latestSnapshot, dimensions.first, dimensions.second, dimensions.third, android.os.SystemClock.elapsedRealtime()) != true)
                        return result("stale", "系统操作前来源画面已失效，请重新观察", current,
                            JSONObject().put("action_state", "not_dispatched").put("reason_code", "source_navigation_changed"))
                } else if (command.optString("screen_id").isBlank() || command.optString("screen_id") != current?.optString("screen_id"))
                    return result("stale", "系统操作前页面已变化，请重新观察", current, JSONObject().put("action_state", "not_dispatched"))
                if (readInterrupted(executionGeneration)) return result("cancelled", "系统操作已中断", current)
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
                if (readInterrupted(executionGeneration)) return result("cancelled", "系统操作已中断", current)
                val accepted = performGlobalAction(action)
                feedback.message(DeviceEnvironment.actionLabel(kind) + if (accepted) "" else "未成功")
                val data = JSONObject().put("action_state", if (accepted) "accepted" else "not_dispatched")
                if (kind == "system_screenshot") data.put("system_screenshot_requested", accepted).put("file_saved_verified", false)
                val receipt = result(if (accepted) "ok" else "error", if (kind == "system_screenshot")
                    "系统截图请求已提交，请检查系统缩略图或保存通知；这不是模型观察截图" else DeviceEnvironment.actionLabel(kind),
                    if (command.optBoolean("split_agent")) null else settledObservation(executionGeneration), data)
                PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) }
                return receipt
            }
            when(kind) {
                "volume", "adjust_volume" -> {
                    val changed = DeviceEnvironment.volume(this, command) { !readInterrupted(executionGeneration) }
                    val receipt = result(changed.getString("status"), changed.getString("message"), data = changed.getJSONObject("data"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) }
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
                        if (readInterrupted(executionGeneration)) return result("cancelled", "文本操作已中断", current)
                        if (selection == "all" && (node.textSelectionStart != 0 || node.textSelectionEnd != raw.length))
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, raw.length)
                            })
                        if (!node.refresh() || node.textSelectionStart < 0 || node.textSelectionEnd <= node.textSelectionStart)
                            return result("error", "当前没有选中的文字，请先选择要复制或剪切的内容", current)
                        if (selection == "all" && (node.text?.toString() != raw || node.textSelectionStart != 0 || node.textSelectionEnd != raw.length))
                            return result("error", "当前控件未确认全选文字，请重新观察后选择", current)
                    }
                    if (readInterrupted(executionGeneration) || !node.refresh() || !node.isFocused)
                        return result("cancelled", "文本操作已中断或焦点已改变", current)
                    if (node.isPassword || node.actionList.none { it.id == requestedAction })
                        return result("error", "当前控件没有提供所需文本操作，请通过页面处理", current, JSONObject().put("action_state", "not_dispatched"))
                    val accepted = node.performAction(requestedAction)
                    val receipt = result(if (accepted) "ok" else "error", if (accepted) "系统已接受文本操作，请核对结果" else "控件未执行文本操作", data = JSONObject().put("action_state", if (accepted) "accepted" else "not_dispatched"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) { readInterrupted(executionGeneration) }
                    return receipt
                }
                "launch" -> {
                    val targetPackage = command.optString("package_name")
                    val data = JSONObject().put("package_name", targetPackage.take(255)).put("action_state", "not_dispatched")
                    if (!targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || targetPackage.length > 255)
                        return result("error", "应用包名无效", current, data.put("reason_code", "app_unavailable"))
                    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        ?: return result("error", "应用未安装或不可启动，请重新查询应用列表", current, data.put("reason_code", "app_unavailable"))
                    if (readInterrupted(executionGeneration)) return result("cancelled", "启动应用已中断", current, data)
                    try { startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: android.content.ActivityNotFoundException) { return result("error", "应用未安装或不可启动", current, data.put("reason_code", "app_unavailable")) }
                    catch (_: SecurityException) { return result("error", "系统未允许打开此应用", current, data.put("reason_code", "app_unavailable")) }
                    targetHistory.clear(); visualCaptures.clear()
                    val receipt = result("ok", "启动请求已提交，请依据新截图确认应用已打开", data = data.put("action_state", "accepted"))
                    PostActionDelay.apply(receipt, android.os.SystemClock::elapsedRealtime, Thread::sleep) {
                        readInterrupted(executionGeneration)
                    }
                    return receipt
                }
                "open_document" -> {
                    val uri = android.net.Uri.parse(command.optString("uri"))
                    val receiver = command.optString("package_name").takeIf { it.isNotBlank() && it != "null" }
                    if (readInterrupted(executionGeneration)) return result("cancelled", "打开文档已中断", current)
                    if (uri.scheme == "doppel-document") { Gateway(this).openDocument(uri.toString(), receiver) { !readInterrupted(executionGeneration) }; return result("ok", "已请求打开文档副本", settledObservation(executionGeneration)) }
                    if (uri.scheme != "content" || contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return result("blocked", "文档需要用户通过系统选择器授权")
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (receiver != null) { intent.setPackage(receiver); if (intent.resolveActivity(packageManager) == null) return result("error", "指定应用不可打开此文档") }
                    if (readInterrupted(executionGeneration)) return result("cancelled", "打开文档已中断", current)
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
    private fun executeSplitAction(command: JSONObject, current: JSONObject, executionGeneration: Execution): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        val data = JSONObject().put("action_state", "not_dispatched").put("completed_strokes", 0).put("feedback_enabled", true)
        fun result(status: String, message: String) = JSONObject().put("command_id",command.getString("id"))
            .put("run_id",command.getString("run_id")).put("status",status).put("message",message)
            .put("observation",current).put("data",data.apply {
                if (status == "stale" && !has("reason_code")) put("reason_code", "gesture_context_changed")
            }.put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started))
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
        fun live() = !readInterrupted(executionGeneration) && instance===this && !callInProgress() &&
            (command.optString("login_verification_permit").isBlank() || DirectRuntime.allowsLoginVerification(this,
                command.getString("run_id"), pkg, command.optString("login_verification_permit")))
        fun sameNavigation() = anchor!=null && anchor.screenId==sourceCapture?.frame?.screenId &&
            anchor.navigationGeneration==navigationGeneration && anchor.windowId==latestSnapshot?.windowId
        fun sameScreen() = live() && sameNavigation() && displayGeometry()==dimensions && currentVisualWindowMatches(anchor?.windowId,pkg)
        if(!live()) return result("cancelled","执行已暂停，动作未派发")
        if(!sameNavigation()) {
            data.put("reason_code","source_navigation_changed")
            return result("stale","截图之后页面导航或窗口已改变，请重新观察")
        }
        // No extra capture, pixel threshold or pixel-verification expiry before gestures.
        // Continuous animation and flat targets must not veto model-selected coordinates.
        data.put("visual_verification",JSONObject().put("performed",false).put("reason","action_pixel_check_disabled"))
        val payment = command.optJSONObject("semantic_intent")?.optString("action") == "pay"
        val consentId = command.optString("payment_consent_id").takeIf { it.isNotBlank() && it != "null" }
        val paymentAuthorized = consentId != null && consentId == PaymentConsent(this).currentId()
        if (consentId != null && !payment || payment && (kind != "tap" || !Policy.canPay(command.optString("mode"), paymentAuthorized, pkg))) {
            data.put("human_takeover", "payment")
            return result("blocked", "付款需要完全访问模式及当前有效的支付授权")
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
        val requestedPlan=GestureSequencePlan.from(action,dimensions.first,dimensions.second)
        val plan=requestedPlan.copy(strokes=requestedPlan.strokes.map { stroke ->
            if(stroke.startHoldMs==0L) stroke else {
                val hold=maxOf(stroke.startHoldMs,android.view.ViewConfiguration.getLongPressTimeout().toLong()+100)
                require(hold<=3000) { "设备长按阈值超过拖动支持范围" }
                stroke.copy(startHoldMs=hold)
            }
        })
        require(plan.strokes.isNotEmpty())
        val companion=DeviceWorkerService.instance
        val revision=companion?.companionRevision
        fun currentHost()=live() && DeviceWorkerService.instance===companion && companion?.companionRevision==revision
        val pass=companion?.beginCompanionGestureTouchPass(executionGeneration.generation,plan.totalMs+5000,::currentHost)
        companion?.companionGestureTouchPassDiagnostic()?.let {data.put("touch_handoff",it)}
        if(companion!=null && pass==null) return result("cancelled","助手触区未能让出，动作未派发")
        var guard:AutoCloseable?=null
        try {
            guard=beginGuardGestureTouchPass(executionGeneration.generation,plan.totalMs+5000,::currentHost)
            data.put("guard_handoff",guardGestureTouchPassDiagnostic())
            if(guard==null) return result("cancelled","触屏暂停层未能让出，动作未派发")
            targetHistory.clear();visualCaptures.clear()
            fun dispatch(strokes:List<PlannedStroke>, interval:Long):Boolean {
                val latch=CountDownLatch(1)
                val submitted=java.util.concurrent.atomic.AtomicBoolean()
                val confirmed=java.util.concurrent.atomic.AtomicBoolean()
                val expired=java.util.concurrent.atomic.AtomicBoolean()
                val tokens=java.util.concurrent.CopyOnWriteArrayList<Long>()
                val loginRequest=java.util.concurrent.atomic.AtomicReference<String?>()
                val held=strokes.singleOrNull()?.takeIf {it.startHoldMs>0}
                require(held!=null || strokes.none {it.startHoldMs>0})
                var heldPath:Path?=null
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
                    if(stroke.startHoldMs>0) heldPath=path
                    else description.addStroke(GestureDescription.StrokeDescription(path,offset,stroke.durationMs))
                    val delay=offset
                    mainHandler.postDelayed({
                        if(!expired.get() && currentHost()) {
                            val start=stroke.points.first();val end=stroke.points.last()
                            val xs=stroke.points.map {it.x};val ys=stroke.points.map {it.y}
                            tokens.add(feedback.begin(ActionFeedbackGeometry(if(stroke.points.size>1) "swipe" else kind,
                                listOf((xs.min()-24).toInt(),(ys.min()-24).toInt(),(xs.max()+24).toInt(),(ys.max()+24).toInt()),
                                start,if(stroke.points.size>1) start else null,if(stroke.points.size>1) end else null,
                                pathPoints,stroke.totalMs)))
                        }
                    },delay)
                    offset+=stroke.totalMs+interval
                }
                val expectedMs=offset-interval
                mainHandler.post {
                    if(expired.get() || !currentHost() || !sameScreen()) {latch.countDown();return@post}
                    try {
                        if (readInterrupted(executionGeneration)) { latch.countDown(); return@post }
                        if (kind == "tap" && command.optJSONObject("semantic_intent")?.opt("request_login_code") == true) {
                            try { loginRequest.set(LoginAssist(this).beginCodeRequest(pkg, command.getString("run_id"))) }
                            catch (error: IllegalStateException) {
                                data.put("reason_code", "login_request_unavailable")
                                    .put("login_request_error", error.message ?: "登录验证码监听不可用")
                                latch.countDown(); return@post
                            }
                        }
                        ownGestureGeneration.set(executionGeneration.generation)
                        val callback=object:GestureResultCallback() {
                            override fun onCompleted(gestureDescription:GestureDescription?) {confirmed.set(true);ownGestureGeneration.compareAndSet(executionGeneration.generation,-1);latch.countDown()}
                            override fun onCancelled(gestureDescription:GestureDescription?) {ownGestureGeneration.compareAndSet(executionGeneration.generation,-1);latch.countDown()}
                        }
                        fun submit():Boolean = if(held==null) dispatchTrackedGesture(command.getString("run_id"),description.build(),callback,mainHandler) {
                            !expired.get() && currentHost()
                        } else dispatchHeldGesture(command.getString("run_id"),requireNotNull(heldPath),held.points.first(),
                            held.startHoldMs,held.durationMs,callback) {
                            // A long press may enter edit mode in this app. Keep the original pointer;
                            // a new navigation generation alone must not turn this into a second touch.
                            !expired.get() && currentHost() && displayGeometry()==dimensions &&
                                visualWindow()?.let {visualWindowPackage(it)==pkg}==true
                        }
                        // Check/claim on the dispatch thread. Never hold the consent lock while waiting for a UI callback.
                        if (payment) {
                            val attempt = PaymentConsent(this).runPayment(consentId!!, command.getString("run_id"), Policy.hash(pkg)) {
                                !expired.get() && currentHost() && sameScreen() && submit()
                            }
                            data.put("payment_guard", attempt.status).put("payment_attempted", attempt.status == "attempted")
                            submitted.set(attempt.accepted)
                        } else submitted.set(submit())
                        if(!submitted.get()) {ownGestureGeneration.compareAndSet(executionGeneration.generation,-1);latch.countDown()}
                    } catch(_:Exception) {ownGestureGeneration.compareAndSet(executionGeneration.generation,-1);latch.countDown()}
                    finally {
                        // A late main-thread dispatch must not reopen a timed-out request.
                        if (expired.get() || !submitted.get()) loginRequest.get()?.let(LoginAssist.session::cancelRequest)
                    }
                }
                val deadline=android.os.SystemClock.elapsedRealtime()+expectedMs+2000
                try {
                    // Revocation closes MOVE admission, but HOLD must reach its stationary UP
                    // before the caller releases the automatic-unlock operation lease.
                    while(latch.count>0 && (held!=null || currentHost()) && android.os.SystemClock.elapsedRealtime()<deadline) latch.await(40,TimeUnit.MILLISECONDS)
                } finally {
                    expired.set(true);ownGestureGeneration.compareAndSet(executionGeneration.generation,-1)
                    if (!confirmed.get()) loginRequest.get()?.let(LoginAssist.session::cancelRequest)
                }
                tokens.forEach {feedback.finish(it,confirmed.get())}
                val humanizedCount = strokes.count { it.points.size == 2 }
                data.put("action_state",if(confirmed.get()) "accepted" else if(submitted.get()) "unconfirmed" else "not_dispatched")
                    .put("backend","accessibility").put("feedback_targets",JSONArray(strokes.map {s -> JSONArray(s.points.map {p -> JSONArray(listOf(p.x,p.y))})}))
                    .put("humanized_swipes", humanizedCount)
                if(kind in setOf("tap","double_tap")) data.put("touch_durations_ms",JSONArray(strokes.map {it.durationMs}))
                if(held!=null) data.put("start_hold_ms",held.startHoldMs).put("move_duration_ms",held.durationMs)
                if(confirmed.get()) data.put("completed_strokes",data.getInt("completed_strokes")+strokes.size)
                else if(submitted.get()) data.put("unconfirmed_strokes",strokes.size)
                return confirmed.get()
            }
            if(payment) {
                if(!dispatch(plan.strokes,0)) {
                    data.put("human_takeover","payment")
                    return result("blocked","付款未确认、授权已撤销或已经尝试过，请核对订单后手动处理")
                }
            } else if(kind=="double_tap") {
                // One Android gesture preserves the inter-tap timing. An interrupted pair is never replayed.
                if(!dispatch(plan.strokes,plan.intervalMs)) return result(if(live()) "error" else "cancelled","双击未完整确认，已提交部分不会自动重放")
            } else {
                for((index,stroke) in plan.strokes.withIndex()) {
                    if(!sameScreen()) return result(if(live()) "stale" else "cancelled","连续动作已停止，已完成部分不会重放")
                    if(!dispatch(listOf(stroke),0)) return result(if(live()) "error" else "cancelled",
                        data.optString("login_request_error", "动作未完整确认，已提交部分不会自动重放"))
                    if(index<plan.strokes.lastIndex && plan.intervalMs>0) Thread.sleep(plan.intervalMs)
                }
            }
            return result(if(live()) "ok" else "cancelled","系统手势已返回，请依据新截图判断实际结果")
        } finally {
            platformGestures.afterStopped(setOf(command.getString("run_id"))) {pass?.close();guard?.close()}
        }
    }
    private fun executeVisualGesture(command: JSONObject, current: JSONObject, executionGeneration: Execution, readiness: ScreenReadyWait): JSONObject {
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
        if (callInProgress() || readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停")
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
        val verified = readiness.read(::observeVisual)
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
        fun currentHost() = !readInterrupted(executionGeneration) && instance === this &&
            DeviceWorkerService.instance === companion && companion?.companionRevision == companionRevision
        fun currentInjection() = !expired.get() && currentHost()
        val touchPass = companion?.beginCompanionGestureTouchPass(executionGeneration.generation, gesture.durationMs + 3000, ::currentInjection)
        if (companion != null && touchPass == null) return result("cancelled", "助手触区未能让出，视觉动作未执行", verified,
            visualDiagnostic("companion_touch_pass_unavailable", "input", verificationDetails))
        var guardPass: AutoCloseable? = null
        try {
        guardPass = beginGuardGestureTouchPass(executionGeneration.generation, gesture.durationMs + 3000, ::currentHost)
        if (guardPass == null) return result("cancelled", "触屏暂停层未能就绪，视觉动作未执行", verified,
            visualDiagnostic("touch_guard_handoff_unavailable", "input", verificationDetails))
        val token = feedback.begin(geometry)
        targetHistory.clear(); visualCaptures.clear()
        fun injectionBlock(): String? = when {
                !currentInjection() -> "宿主执行状态已改变"
                callInProgress() -> "设备正在通话"
                navigationGeneration != latestSnapshot?.navigationGeneration -> "页面导航已变化"
                navigationGeneration != source.navigation?.navigationGeneration -> "来源页面导航已变化"
                !currentVisualWindowMatches(source.navigation?.windowId, source.frame.packageName) -> "来源活动窗口已变化"
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
                ownGestureGeneration.set(executionGeneration.generation)
                try {
                    shellResult = ShellBridgeClient.get(this).executeAuthorized(command.getString("id"), command.getString("run_id"), proof, gesture.kind, args,
                        { ShellBridgeClient.source(observeVisual(), displayGeometry().third) }, { currentInjection() && !callInProgress() })
                    accepted.set(shellResult.optString("action_state") == "accepted")
                    if (accepted.get()) actionCompletedAt.set(android.os.SystemClock.elapsedRealtime())
                    submitted.set(shellResult.optString("action_state") in setOf("accepted", "unconfirmed"))
                } finally { ownGestureGeneration.compareAndSet(executionGeneration.generation, -1) }
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
                if (readInterrupted(executionGeneration)) { dispatchBlock.set("宿主执行状态已改变"); callback.countDown(); return@post }
                ownGestureGeneration.set(executionGeneration.generation)
                submitted.set(dispatchTrackedGesture(command.getString("run_id"), description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { ownGestureGeneration.compareAndSet(executionGeneration.generation, -1); actionCompletedAt.set(android.os.SystemClock.elapsedRealtime()); accepted.set(true); callback.countDown() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { ownGestureGeneration.compareAndSet(executionGeneration.generation, -1); callback.countDown() }
                }, mainHandler, ::currentInjection))
                if (!submitted.get()) { ownGestureGeneration.compareAndSet(executionGeneration.generation, -1); callback.countDown() }
            } catch (_: Exception) { ownGestureGeneration.compareAndSet(executionGeneration.generation, -1); callback.countDown() }
        }
        val returned = try { callback.await(gesture.durationMs + 1800, TimeUnit.MILLISECONDS) }
            finally {
                expired.set(true)
                ownGestureGeneration.compareAndSet(executionGeneration.generation, -1)
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
        if (readInterrupted(executionGeneration)) return result("cancelled", "执行已暂停，已提交的系统手势结果需核对", data = data)
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
            after = settledObservation(executionGeneration, visual = true)
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
    private fun readInterrupted(executionGeneration: Execution) = gestureReleaseFailed || actionGeneration.get() != executionGeneration.generation ||
        !executionGeneration.current() || callInProgress() || instance !== this || Thread.currentThread().isInterrupted

    private fun capturePrivateScreen() = LoginAssist.settingsVisible || DirectMode.settingsVisible

    private fun settledObservation(executionGeneration: Execution, visual: Boolean = false): JSONObject? {
        var previous: JSONObject? = null
        var matches = 0
        repeat(6) {
            if (readInterrupted(executionGeneration)) return null
            Thread.sleep(250)
            if (readInterrupted(executionGeneration)) return null
            val current = try { if (visual) observeVisual() else observe() } catch (_: Exception) { null }
            if (readInterrupted(executionGeneration)) return null
            if (current != null && current.optString("screen_id") == previous?.optString("screen_id")) matches++ else matches = 0
            previous = current
            if (matches >= 2) return current
        }
        return previous
    }
    private data class PixelCapture(val plan: ScreenshotPayloadPlan, val pixels: VisualPixels, val frame: VisualFrame?)
    private fun ownCaptureOverlay(window: AccessibilityWindowInfo, knownPackage: String? = null): Boolean {
        val pkg = knownPackage ?: visualWindowPackage(window)
        return ownOverlayWindow(window, pkg) || window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            pkg in setOf(packageName, "dev.doppel.app", "dev.doppel.developer")
    }
    private fun ownOverlayWindow(window: AccessibilityWindowInfo, knownPackage: String? = null): Boolean {
        if (window.isFocused || window.isActive) return false
        val pkg = knownPackage ?: window.root?.let { node ->
            try { node.packageName?.toString() } finally { @Suppress("DEPRECATION") node.recycle() }
        }
        // The companion uses TYPE_APPLICATION_OVERLAY, reported as a system/application window.
        // Its presentation changes must not invalidate navigation in the underlying app.
        return pkg == packageName && window.type in setOf(AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, AccessibilityWindowInfo.TYPE_SYSTEM)
    }
    private data class CapturedDisplay(val bitmap: Bitmap, val at: Long)
    private class CaptureFailure(val code: Int, val reason: String? = null) : Exception()

    private fun acquireScreenshot(executionGeneration: Execution, timeoutMs: Long = 5000): CapturedDisplay {
        if (Build.VERSION.SDK_INT < 30) {
            val geometry = displayGeometry()
            val frame = LegacyScreenCaptureService.captureWithTimestamp(geometry.first, geometry.second, resources.displayMetrics.densityDpi) {
                readInterrupted(executionGeneration)
            }
            return CapturedDisplay(frame.bitmap, frame.capturedAt)
        }
        // One bounded wait for the full-display API; restore overlay flags before waiting.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        repeat(2) { attempt ->
            val gate = CountDownLatch(1)
            val lock = Any()
            var finished = false
            var image: CapturedDisplay? = null
            var failure = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            fun finish(value: CapturedDisplay?, error: Int) = synchronized(lock) {
                if (finished) { value?.bitmap?.recycle(); return@synchronized }
                image = value; failure = error; finished = true; gate.countDown()
            }
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val at = android.os.SystemClock.elapsedRealtime()
                    var hardware: Bitmap? = null
                    try {
                        hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        finish(software?.let { CapturedDisplay(it, at) }, ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                    } catch (_: Exception) { finish(null, ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) }
                    finally { hardware?.recycle(); result.hardwareBuffer.close() }
                }
                override fun onFailure(errorCode: Int) { finish(null, errorCode) }
            }
            fun requestAndWait() {
                if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw java.util.concurrent.TimeoutException("Screen capture timed out")
                try { takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, callback) }
                catch (_: SecurityException) { callback.onFailure(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) }
                catch (_: Exception) { callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) }
                while (gate.count > 0) {
                    if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) throw java.util.concurrent.TimeoutException("Screen capture timed out")
                    gate.await(minOf(100, left), TimeUnit.MILLISECONDS)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 34) TemporaryScreenshotExclusion.capture(::requestAndWait)
                else requestAndWait()
                if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
                synchronized(lock) { image?.let { image = null; return it } }
                if (failure != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || attempt != 0) throw CaptureFailure(failure)
                if (deadline - android.os.SystemClock.elapsedRealtime() <= 400) throw CaptureFailure(failure)
            } finally {
                synchronized(lock) { finished = true; image?.bitmap?.recycle(); image = null }
            }
            repeat(4) {
                if (readInterrupted(executionGeneration)) throw ScreenReadInterruptedException()
                Thread.sleep(100)
            }
        }
        throw CaptureFailure(ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
    }

    private fun screenshot(command: JSONObject, readiness: ScreenReadyWait, executionGeneration: Execution,
        verification: java.util.concurrent.atomic.AtomicReference<PixelCapture?>? = null): JSONObject =
        readiness.read { captureScreenshotOnce(command, readiness, executionGeneration, verification) }

    private fun captureScreenshotOnce(command: JSONObject, readiness: ScreenReadyWait, executionGeneration: Execution,
        verification: java.util.concurrent.atomic.AtomicReference<PixelCapture?>?): JSONObject {
        verification?.set(null)
        val result = JSONObject().put("command_id", command.getString("id")).put("run_id", command.getString("run_id"))
            .put("status", "error").put("message", "屏幕采集不可用").put("data", JSONObject())
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive) return result.put("status", "blocked")
            .put("message", "设备已锁定或息屏，请解锁后继续")
            .put("data", JSONObject().put("reason_code", "device_locked"))
        val before = readiness.read(::observeVisual)
        var privacyVerified = refreshVisibleCapturePrivacy()
        val beforePrivateBounds = privateCaptureBounds
        val beforeSnapshot = latestSnapshot
        val geometry = displayGeometry()
        val windowsBefore = captureWindowSignature()
        fun privateScreen() = !privacyVerified || capturePrivateScreen() || PaymentConsent.settingsVisible || AutomaticUnlockSession.isAuthenticating ||
            AutomaticUnlockSession.isUnlocking || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive || ScreenCapturePrivacy.unavailable(before) ||
            privateWindowBounds.any { (id, bounds) -> id in capturePrivacyWindowIds() && bounds.isNotEmpty() && privateWindowGeometry[id] != geometry }
        fun blocked() = result.put("status", "blocked").put("message", "登录资料与模型连接设置期间不上传截图")
            .put("data", JSONObject().put("human_takeover", "login"))
        fun stale(reason: String) = result.put("status", "stale").put("message", "截图期间窗口发生变化，请重新观察")
            .put("data", visualDiagnostic(reason, "capture"))
        if (privateScreen()) return blocked()
        if (Build.VERSION.SDK_INT < 30 && !LegacyScreenCaptureService.isReady) return result.put("status", "blocked")
            .put("message", "请在 Doppel 设置的屏幕识别中授权本次屏幕采集")
            .put("data", JSONObject().put("human_takeover", "screen_capture_required"))
        val started = android.os.SystemClock.elapsedRealtime()
        var normal: CapturedDisplay? = null
        var scaled: Bitmap? = null
        var output: Bitmap? = null
        try {
            normal = acquireScreenshot(executionGeneration)
            if (windowsBefore != captureWindowSignature()) return stale("capture_window_changed")
            if (privateScreen()) return blocked()
            val source = normal.bitmap
            if (source.width != geometry.first || source.height != geometry.second || geometry != displayGeometry()) return stale("capture_geometry_changed")
            val plan = ScreenshotPayloadPlan(java.util.UUID.randomUUID().toString(), before.getString("screen_id"),
                before.getString("package_name"), source.width, source.height, geometry.third, normal.at, verificationOnly = verification != null)
            scaled = if (plan.imageWidth != source.width || plan.imageHeight != source.height)
                Bitmap.createScaledBitmap(source, plan.imageWidth, plan.imageHeight, true) else source
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            ScreenPrivacyMask.apply(pixels, scaled.width, scaled.height, plan.displayWidth, plan.displayHeight, beforePrivateBounds)
            output = Bitmap.createBitmap(pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val after = readiness.read(::observeVisual)
            privacyVerified = refreshVisibleCapturePrivacy()
            if (privateScreen() || ScreenCapturePrivacy.unavailable(after)) return blocked()
            if (beforePrivateBounds.toSet() != privateCaptureBounds.toSet()) return stale("capture_private_region_changed")
            if (windowsBefore != captureWindowSignature()) return stale("capture_window_changed")
            val binding = CaptureObservationBinding.bind(plan, beforeSnapshot, latestSnapshot, geometry, displayGeometry(), android.os.SystemClock.elapsedRealtime())
                ?: return stale("capture_screen_changed").put("observation", after)
            var frame: VisualFrame? = null
            val data = binding.plan.encodeForDelivery {
                val bytes = ByteArrayOutputStream().use { stream -> check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray() }
                frame = VisualFrame(plan.captureId, binding.plan.screenId, plan.packageName, plan.displayWidth, plan.displayHeight,
                    plan.imageWidth, plan.imageHeight, plan.rotation, plan.capturedAt, plan.expiresAt,
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mime_type", "image/png").put("visual_frame", frame!!.json())
            } ?: JSONObject()
            data.put("capture_backend", if (Build.VERSION.SDK_INT >= 34) "accessibility_skip_screenshot" else if (Build.VERSION.SDK_INT >= 30) "accessibility_full_display" else "media_projection")
                .put("privacy_mask_count", beforePrivateBounds.size).put("overlay_cleanup_performed", false)
                .put("capture_elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
                .put("capture_pixels_on_main_thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                .put("capture_observation", binding.metadata())
            val paired = PixelCapture(binding.plan, VisualPixels(output.width, output.height, pixels), frame)
            if (verification != null) verification.set(paired)
            else if (frame != null) visualCaptures.put(VisualCapture(frame!!, paired.pixels, binding.navigation))
            return result.put("status", "ok").put("message", "").put("data", data).put("observation", after)
        } catch (_: TemporaryScreenshotExclusion.Changed) {
            return stale("capture_overlay_changed")
        } catch (_: TemporaryScreenshotExclusion.Unavailable) {
            return result.put("status", "error").put("message", "当前设备暂时无法排除悬浮层截图，请重试")
                .put("data", JSONObject().put("reason_code", "capture_overlay_unavailable"))
        } catch (failure: CaptureFailure) {
            val denied = failure.code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW || failure.code == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
            val reason = failure.reason ?: when (failure.code) {
                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure_window"
                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility_access_required"
                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                else -> "unavailable"
            }
            return result.put("status", if (denied) "blocked" else "error").put("message", when {
                denied -> "当前窗口禁止截图或无障碍截图权限不可用"
                reason == "geometry_unsupported" -> "当前窗口尺寸无法可靠映射到屏幕，请更换页面后重试"
                else -> "截图不可用，请重新观察"
            })
                .put("data", JSONObject().put("reason_code", "capture_$reason").put("capture_error_code", failure.code))
        } finally {
            output?.recycle()
            if (scaled !== normal?.bitmap) scaled?.recycle()
            normal?.bitmap?.recycle()
        }
    }
}
