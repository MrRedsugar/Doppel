package dev.doppel.developer

import android.app.UiAutomation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.doppel.sdk.LegacyScreenCaptureService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Failure diagnostics only: existing system consent, original flags, no production source substitution. */
internal object LegacyMirrorProbe {
    fun collect(context: Context, ui: UiAutomation, width: Int, height: Int, dpi: Int, pulse: (Int) -> Unit): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 3000
        val result = JSONObject().put("diagnostic_only", true).put("max_frames", 8).put("deadline_ms", 3000)
        val frames = mutableListOf<JSONObject>()
        val lock = Any()
        val closed = AtomicBoolean(false)
        val count = AtomicInteger()
        val errorClass = AtomicReference<String?>()
        val reader = AtomicReference<ImageReader?>()
        val display = AtomicReference<VirtualDisplay?>()
        val dumpInput = AtomicReference<InputStream?>()
        val thread = HandlerThread("legacy-mirror-probe").apply { start() }
        val handler = Handler(thread.looper)
        val dumpExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "legacy-mirror-dump").apply { isDaemon = true } }
        try {
            check(LegacyScreenCaptureService.isReady)
            val instance = LegacyScreenCaptureService::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)
                ?: error("No authorized service instance")
            @Suppress("UNCHECKED_CAST")
            val reference = LegacyScreenCaptureService::class.java.getDeclaredField("projection").apply { isAccessible = true }.get(instance)
                as AtomicReference<MediaProjection?>
            val projection = reference.get() ?: error("No active system grant")
            val created = CountDownLatch(1)
            check(handler.post {
                try {
                    if (closed.get()) return@post
                    val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    reader.set(source)
                    source.setOnImageAvailableListener({ available ->
                        while (!closed.get() && count.get() < 8 && SystemClock.elapsedRealtime() < deadline &&
                            LegacyScreenCaptureService.isReady && reference.get() === projection) {
                            var image: android.media.Image? = null
                            try {
                                image = available.acquireNextImage() ?: break
                                val received = SystemClock.elapsedRealtimeNanos()
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val offset = buffer.position() + image.height / 2 * plane.rowStride + image.width / 2 * plane.pixelStride
                                check(plane.pixelStride >= 3 && offset + 2 < buffer.limit())
                                val rgb = JSONArray(listOf(buffer.get(offset).toInt() and 255,
                                    buffer.get(offset + 1).toInt() and 255, buffer.get(offset + 2).toInt() and 255))
                                val frame = JSONObject().put("ordinal", count.incrementAndGet()).put("image_timestamp", image.timestamp)
                                    .put("received_elapsed_nanos", received).put("center_rgb", rgb)
                                    .put("width", image.width).put("height", image.height)
                                synchronized(lock) { frames.add(frame) }
                            } catch (error: Exception) { errorClass.compareAndSet(null, error.javaClass.simpleName); break }
                            finally { image?.close() }
                        }
                    }, handler)
                    display.set(projection.createVirtualDisplay("Doppel test mirror probe", width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, source.surface, null, handler))
                    check(display.get() != null)
                } catch (error: Exception) { errorClass.compareAndSet(null, error.javaClass.simpleName) }
                finally { created.countDown() }
            })
            check(created.await(maxOf(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) && display.get() != null)
            result.put("virtual_display_id", display.get()!!.display.displayId)
            val dump = dumpExecutor.submit<Int> {
                val input = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys display"))
                dumpInput.set(input)
                var bytes = 0
                input.use {
                    if (closed.get()) return@submit 0
                    File(context.filesDir, "legacy-mirror-display.txt").outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (!closed.get()) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            val kept = minOf(size, maxOf(0, 2 * 1024 * 1024 - bytes))
                            if (kept > 0) output.write(buffer, 0, kept)
                            bytes += kept
                        }
                    }
                }
                bytes
            }
            var pulses = 0
            var nextPulse = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < deadline && (count.get() < 8 || !dump.isDone)) {
                if (count.get() < 8 && SystemClock.elapsedRealtime() >= nextPulse) {
                    pulse(++pulses); nextPulse = SystemClock.elapsedRealtime() + 150
                }
                Thread.sleep(minOf(30, maxOf(1, deadline - SystemClock.elapsedRealtime())))
            }
            result.put("fixture_pulses", pulses).put("dump_completed_while_display_alive", dump.isDone && display.get() != null)
            if (dump.isDone) runCatching { dump.get(0, TimeUnit.MILLISECONDS) }
                .onSuccess { result.put("dump_bytes", it) }.onFailure { result.put("dump_failure_class", it.javaClass.simpleName) }
        } catch (error: Exception) { result.put("failure_class", error.javaClass.simpleName) }
        finally {
            closed.set(true)
            runCatching { dumpInput.getAndSet(null)?.close() }
            dumpExecutor.shutdownNow()
            val released = CountDownLatch(1)
            handler.post {
                try { display.getAndSet(null)?.release() }
                finally { try { reader.getAndSet(null)?.close() } finally { released.countDown() } }
            }
            result.put("resources_released", released.await(500, TimeUnit.MILLISECONDS))
            thread.quitSafely(); thread.join(500)
            result.put("capture_thread_stopped", !thread.isAlive)
            synchronized(lock) { result.put("frames", JSONArray(frames)) }
            errorClass.get()?.let { result.put("frame_failure_class", it) }
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        }
        return result
    }
}
