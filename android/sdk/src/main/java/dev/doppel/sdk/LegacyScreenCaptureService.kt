package dev.doppel.sdk

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Android 8–10 only. System consent lives only in this visible, non-restarting service session. */
class LegacyScreenCaptureService : Service() {
    companion object {
        private const val CHANNEL = "legacy_screen_capture"
        private const val NOTIFICATION = 98
        private const val AUTHORIZE = "dev.doppel.LEGACY_CAPTURE_AUTHORIZE"
        private const val STOP = "dev.doppel.LEGACY_CAPTURE_STOP"
        private const val RESULT = "projection_result"
        private const val CONSENT = "projection_consent"
        @Volatile private var instance: LegacyScreenCaptureService? = null

        @JvmStatic val isReady: Boolean get() = Build.VERSION.SDK_INT in 26..29 &&
            instance?.let { !it.stopping.get() && it.mirrorReady.get() && it.projection.get() != null && it.hasCurrentConsent() } == true

        /** Must run off the main thread. The caller owns and must recycle the returned bitmap. */
        @JvmStatic fun capture(width: Int, height: Int, densityDpi: Int, shouldCancel: () -> Boolean): Bitmap {
            return captureWithTimestamp(width, height, densityDpi, shouldCancel).bitmap
        }

        internal data class CapturedFrame(val bitmap: Bitmap, val capturedAt: Long)

        /** Timestamp is fixed when ImageReader delivers the frame, before packing or copying its pixels. */
        internal fun captureWithTimestamp(width: Int, height: Int, densityDpi: Int, shouldCancel: () -> Boolean): CapturedFrame {
            check(Build.VERSION.SDK_INT in 26..29) { "此系统不使用兼容屏幕采集" }
            return (instance ?: throw SecurityException("请先允许屏幕共享"))
                .captureFrame(width, height, densityDpi, shouldCancel)
        }

        internal fun authorize(context: Context, resultCode: Int, data: Intent) {
            check(Build.VERSION.SDK_INT in 26..29) { "此系统无需额外屏幕共享授权" }
            FirstUseConsent.requireAccepted(context)
            context.startForegroundService(Intent(context, LegacyScreenCaptureService::class.java).setAction(AUTHORIZE)
                .putExtra(RESULT, resultCode).putExtra(CONSENT, data))
        }
    }

    private val stopping = AtomicBoolean(false)
    private val projection = AtomicReference<MediaProjection?>()
    private val pending = AtomicReference<FrameRequest?>()
    private val mirrorReady = AtomicBoolean(false)
    private val mirrorGeneration = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var consentPreferences: SharedPreferences
    private var acceptedConsent: ConsentRecord? = null
    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!hasCurrentConsent()) {
            shutdown(SecurityException("本机同意已撤回，请重新同意并开启屏幕共享"))
            stopSelf()
        }
    }
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var displayManager: DisplayManager
    // These resources belong to captureHandler only; callers observe the atomic readiness/generation.
    private var mirror: MirrorSession? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayListenerRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) failSession(SecurityException("当前屏幕已不可用，请重新授权"))
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || stopping.get() || projection.get() == null) return
            try {
                val geometry = defaultGeometry()
                if (mirror?.geometry != geometry) configureMirror(geometry)
            } catch (_: Exception) { failSession(IllegalStateException("屏幕尺寸变更后未能恢复读取，请重新授权")) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acceptedConsent = FirstUseConsent.record(this)
        consentPreferences = getSharedPreferences("doppel_consent", MODE_PRIVATE)
        consentPreferences.registerOnSharedPreferenceChangeListener(consentListener)
        if (Build.VERSION.SDK_INT !in 26..29 || !hasCurrentConsent() || !ReleaseIntegrity.isTrusted(this)) {
            stopping.set(true); stopSelf(); return
        }
        captureThread = HandlerThread("doppel-legacy-screen").apply { start() }
        captureHandler = Handler(captureThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "屏幕读取", NotificationManager.IMPORTANCE_LOW))
        try {
            // Android 10 requires an active mediaProjection foreground service before getMediaProjection.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION, notification())
            instance = this
        } catch (_: Exception) { stopping.set(true); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping.get() || intent?.action != AUTHORIZE || !hasCurrentConsent()) {
            shutdown(SecurityException("屏幕共享已停止，请重新授权")); stopSelf(); return START_NOT_STICKY
        }
        if (projection.get() != null) { intent.removeExtra(CONSENT); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val consent = intent.getParcelableExtra<Intent>(CONSENT)
        val code = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
        intent.removeExtra(CONSENT); intent.removeExtra(RESULT)
        if (code != Activity.RESULT_OK || consent == null) {
            shutdown(SecurityException("未获得屏幕共享授权")); stopSelf(); return START_NOT_STICKY
        }
        try {
            val granted = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, consent)
                ?: throw SecurityException("未获得屏幕共享授权")
            projection.set(granted)
            granted.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection.get() !== granted) return
                    shutdown(SecurityException("屏幕共享授权已被撤销，请重新开启"))
                    main.post { stopSelf() }
                }
            }, captureHandler)
            requireCurrentConsent()
            if (!captureHandler.post {
                try {
                    requireCurrentConsent()
                    if (stopping.get() || projection.get() !== granted) return@post
                    displayManager.registerDisplayListener(displayListener, captureHandler)
                    displayListenerRegistered = true
                    configureMirror(defaultGeometry())
                } catch (_: Exception) { failSession(IllegalStateException("无法建立屏幕共享，请重新授权")) }
                catch (_: OutOfMemoryError) { failSession(IllegalStateException("屏幕采集内存不足，请关闭其他任务后重试")) }
            }) throw IllegalStateException("屏幕采集服务正在退出")
        } catch (_: Exception) {
            shutdown(SecurityException("屏幕共享未能开启，请重新授权")); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 98, Intent(this, LegacyScreenCaptureActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 99, Intent(this, LegacyScreenCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.doppel_ic_layers_2)
            .setContentTitle("Doppel · 屏幕共享中").setContentText("系统持续共享，Doppel 仅处理任务请求的新画面")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止读取", stop).build()).build()
    }

    private fun captureFrame(width: Int, height: Int, densityDpi: Int, shouldCancel: () -> Boolean): CapturedFrame {
        check(Looper.myLooper() != Looper.getMainLooper() && Looper.myLooper() != captureThread.looper) { "屏幕采集需要在后台线程调用" }
        LegacyCaptureBuffers.byteCount(width, height)
        require(densityDpi in 72..1280) { "屏幕密度无效" }
        requireCurrentConsent()
        val owner = projection.get() ?: throw SecurityException("屏幕共享已停止，请重新授权")
        check(mirrorReady.get()) { "屏幕共享尚未就绪，请稍后重试" }
        if (stopping.get() || shouldCancel() || Thread.currentThread().isInterrupted) throw CancellationException("屏幕采集已取消")
        val request = FrameRequest(owner, width, height, densityDpi, shouldCancel)
        check(pending.compareAndSet(null, request)) { "已有屏幕采集正在进行" }
        val deadline = SystemClock.elapsedRealtime() + 4000
        try {
            if (!captureHandler.post { request.prepare() }) throw IllegalStateException("屏幕采集服务正在退出")
            while (true) {
                requireCurrentConsent()
                if (stopping.get() || projection.get() !== owner) throw SecurityException("屏幕共享已停止，请重新授权")
                if (shouldCancel() || Thread.currentThread().isInterrupted) throw CancellationException("屏幕采集已取消")
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw TimeoutException("等待屏幕画面超时，请重新查看当前页面")
                if (request.result.await(minOf(50, remaining))) {
                    requireCurrentConsent()
                    if (stopping.get() || projection.get() !== owner) throw SecurityException("屏幕共享已停止，请重新授权")
                    if (shouldCancel()) throw CancellationException("屏幕采集已取消")
                    requireCurrentConsent()
                    check(request.gate?.generation == mirrorGeneration.get()) { "屏幕尺寸已变化，请重新观察" }
                    return request.result.take()
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            request.result.cancel(CancellationException("屏幕采集已取消"))
            throw CancellationException("屏幕采集已取消")
        } catch (error: Exception) {
            request.result.cancel(error)
            throw error
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    private fun hasCurrentConsent(): Boolean {
        val current = FirstUseConsent.record(this)
        // A new acceptance cannot revive a projection from before withdrawal, even if its listener was queued.
        return current == acceptedConsent && current.accepts(FirstUseConsent.TERMS_VERSION, FirstUseConsent.PRIVACY_VERSION)
    }

    private fun requireCurrentConsent() {
        if (hasCurrentConsent()) return
        val reason = SecurityException("本机同意已撤回，请重新同意并开启屏幕共享")
        shutdown(reason)
        stopSelf()
        throw reason
    }

    private data class ScreenGeometry(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int)
    private class MirrorSession(val reader: ImageReader, val geometry: ScreenGeometry, val generation: Long)

    @Suppress("DEPRECATION")
    private fun defaultGeometry(): ScreenGeometry {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("当前屏幕不可用")
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        LegacyCaptureBuffers.byteCount(metrics.widthPixels, metrics.heightPixels)
        require(metrics.densityDpi in 72..1280) { "屏幕密度无效" }
        return ScreenGeometry(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation)
    }

    /** One virtual display for the grant; a read gets a fresh output queue without changing the user's screen. */
    private fun configureMirror(geometry: ScreenGeometry, request: FrameRequest? = null) {
        try { configureMirrorResources(geometry, request) }
        catch (_: SecurityException) {
            val reason = SecurityException("屏幕共享授权已失效，请重新开启")
            failSession(reason); throw reason
        } catch (_: Exception) {
            val reason = IllegalStateException("屏幕共享未能建立或恢复，请重新授权")
            failSession(reason); throw reason
        } catch (_: OutOfMemoryError) {
            val reason = IllegalStateException("屏幕采集内存不足，请关闭其他任务后重试")
            failSession(reason); throw reason
        }
    }

    private fun configureMirrorResources(geometry: ScreenGeometry, request: FrameRequest?) {
        requireCurrentConsent()
        if (stopping.get()) return
        val owner = projection.get() ?: throw SecurityException("屏幕共享已停止，请重新授权")
        if (request == null && mirror?.geometry == geometry && virtualDisplay != null) return
        if (request != null && !request.valid()) return
        val previous = mirror
        val sameGeometry = previous?.geometry == geometry && virtualDisplay != null
        // Only this request may survive its own output swap; rotation invalidates every waiting read.
        val preserveRequest = request?.takeIf { sameGeometry && pending.get() === it }
        mirrorReady.set(false)
        val generation = mirrorGeneration.incrementAndGet()
        pending.get()?.takeUnless { it === preserveRequest }?.result
            ?.cancel(IllegalStateException("屏幕尺寸已变化，请重新观察"))
        mirror = null
        try { virtualDisplay?.surface = null } finally { closeReader(previous?.reader) }
        val reader = ImageReader.newInstance(geometry.width, geometry.height, PixelFormat.RGBA_8888, 2)
        val next = MirrorSession(reader, geometry, generation)
        try {
            reader.setOnImageAvailableListener({ source -> receive(next, source) }, captureHandler)
            val existing = virtualDisplay
            // Arm before attaching: a static screen may produce exactly one frame during setSurface.
            // This reader has a new BufferQueue, so no previous request's producer timestamp is inherited.
            preserveRequest?.gate = LegacyCaptureFrameGate(generation, System.nanoTime(), 0L)
            if (existing == null) {
                virtualDisplay = owner.createVirtualDisplay("Doppel screen sharing", geometry.width, geometry.height, geometry.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, captureHandler)
                    ?: throw IllegalStateException("无法建立屏幕采集窗口")
            } else {
                if (!sameGeometry) existing.resize(geometry.width, geometry.height, geometry.densityDpi)
                existing.surface = reader.surface
            }
            requireCurrentConsent()
            check(!stopping.get() && projection.get() === owner) { "屏幕共享已停止" }
            mirror = next
            mirrorReady.set(true)
        } catch (error: Throwable) {
            closeReader(reader)
            throw error
        }
    }

    private inner class FrameRequest(val owner: MediaProjection, val width: Int, val height: Int, val densityDpi: Int,
                                     val shouldCancel: () -> Boolean) {
        val result = LegacyCaptureMailbox<CapturedFrame> { it.bitmap.recycle() }
        @Volatile var gate: LegacyCaptureFrameGate? = null
        fun valid(): Boolean = try {
            requireCurrentConsent()
            !stopping.get() && projection.get() === owner && pending.get() === this && !result.isComplete && !shouldCancel()
        } catch (error: SecurityException) {
            result.cancel(error); false
        } catch (_: Exception) {
            result.cancel(IllegalStateException("屏幕采集状态未能确认")); false
        }

        fun prepare() {
            if (!valid()) { result.cancel(CancellationException("屏幕采集已取消")); return }
            try {
                val geometry = defaultGeometry()
                if (mirror?.geometry != geometry) { configureMirror(geometry); return }
                check(mirror != null && virtualDisplay != null) { "屏幕共享尚未就绪" }
                check(width == geometry.width && height == geometry.height && densityDpi == geometry.densityDpi) { "屏幕尺寸已变化" }
                configureMirror(geometry, this)
            } catch (_: SecurityException) {
                result.cancel(SecurityException("屏幕共享授权已失效，请重新开启"))
            } catch (_: Exception) {
                result.cancel(IllegalStateException("屏幕读取状态已变化，请重新观察"))
            } catch (_: OutOfMemoryError) {
                result.cancel(IllegalStateException("屏幕采集内存不足，请关闭其他任务后重试"))
            }
        }
    }

    private fun receive(session: MirrorSession, source: ImageReader) {
        var image: android.media.Image? = null
        var bitmap: Bitmap? = null
        var request: FrameRequest? = null
        try {
            if (stopping.get() || mirror !== session || source !== session.reader || session.generation != mirrorGeneration.get()) return
            image = source.acquireLatestImage() ?: return
            val capturedAt = SystemClock.elapsedRealtime()
            val receivedNanos = System.nanoTime()
            request = pending.get() ?: return // The system streams; the app neither decodes nor retains idle frames.
            if (request.result.isComplete || !request.valid()) return
            val gate = request.gate ?: return
            if (!gate.accepts(session.generation, image.timestamp, receivedNanos)) return
            val geometry = defaultGeometry()
            if (geometry != session.geometry) { configureMirror(geometry); return }
            check(image.width == request.width && image.height == request.height) { "屏幕尺寸已变化" }
            val plane = image.planes.first()
            val pixels = LegacyCaptureBuffers.packRgba(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride)
            bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            if (!request.valid() || mirror !== session || session.generation != mirrorGeneration.get()) {
                request.result.cancel(CancellationException("屏幕采集已取消")); return
            }
            request.result.deliver(CapturedFrame(bitmap, capturedAt))
            bitmap = null // The mailbox now owns delivery or disposal; a taken frame belongs to its caller.
        } catch (_: SecurityException) {
            failSession(SecurityException("屏幕共享授权已失效，请重新开启"))
        } catch (_: Exception) {
            val reason = IllegalStateException("屏幕画面未能完整读取，请重新观察")
            if (request == null && mirror === session && !stopping.get()) failSession(reason)
            else request?.result?.cancel(reason)
        } catch (_: OutOfMemoryError) {
            request?.result?.cancel(IllegalStateException("屏幕采集内存不足，请关闭其他任务后重试"))
        } finally {
            bitmap?.recycle()
            try { image?.close() } catch (_: Exception) { }
        }
    }

    private fun closeReader(reader: ImageReader?) {
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
    }

    private fun closeMirror() {
        if (displayListenerRegistered) {
            try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
            displayListenerRegistered = false
        }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        closeReader(mirror?.reader)
        mirror = null
    }

    private fun failSession(reason: Exception) {
        shutdown(reason)
        main.post { stopSelf() }
    }

    private fun shutdown(reason: Exception) {
        if (!stopping.compareAndSet(false, true)) return
        mirrorReady.set(false)
        mirrorGeneration.incrementAndGet()
        if (instance === this) instance = null
        val request = pending.getAndSet(null)
        request?.result?.cancel(reason) // Wake a caller before touching the accessibility service's monitor.
        val granted = projection.getAndSet(null)
        DoppelAccessibilityService.instance?.stopActionFeedback()
        // Pausing may acquire the host engine lock; keep projection callbacks free to release resources.
        main.post { DeviceWorkerService.instance?.suspendLocally() }
        if (::captureHandler.isInitialized) captureHandler.post {
            closeMirror()
            try { granted?.stop() } catch (_: Exception) { }
            DoppelAccessibilityService.instance?.clearObservationHistory()
        }
    }

    override fun onDestroy() {
        if (::consentPreferences.isInitialized) consentPreferences.unregisterOnSharedPreferenceChangeListener(consentListener)
        shutdown(SecurityException("屏幕共享已停止，请重新授权"))
        if (instance === this) instance = null
        if (::captureThread.isInitialized) captureThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
