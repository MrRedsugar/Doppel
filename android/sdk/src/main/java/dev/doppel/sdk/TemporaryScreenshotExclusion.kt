package dev.doppel.sdk

import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Excludes only this process's overlay surfaces for one capture, without changing pixels or input. */
internal object TemporaryScreenshotExclusion {
    class Changed(reason: String) : IllegalStateException(reason)
    class Unavailable(reason: String, cause: Throwable? = null) : IllegalStateException(reason, cause)
    private val main = androidx.core.os.HandlerCompat.createAsync(Looper.getMainLooper())
    private val lock = ReentrantLock()
    private val generation = AtomicLong()
    private val executor = ForkJoinPool.commonPool()
    private val windowGlobal by lazy { Class.forName("android.view.WindowManagerGlobal") }
    private val skipScreenshot by lazy {
        HiddenApiBypass.getDeclaredMethod(SurfaceControl.Transaction::class.java, "setSkipScreenshot",
            SurfaceControl::class.java, java.lang.Boolean.TYPE)
    }
    private data class Layer(val view: View, val id: Int, val surface: SurfaceControl)
    private class Snapshot(val generation: Long, val layers: List<Layer>) : AutoCloseable {
        override fun close() { layers.forEach { it.surface.release() } }
    }

    /** Track even a short-lived add/remove that would be absent from both endpoint snapshots. */
    fun addView(manager: WindowManager, view: View, params: WindowManager.LayoutParams) {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation.incrementAndGet()
        manager.addView(view, params)
    }

    fun <T> capture(capture: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!lock.isHeldByCurrentThread) { "Overlay capture cannot be nested" }
        lock.lockInterruptibly()
        try {
            val before = snapshot()
            var failure: Throwable? = null
            val flagStarted = SystemClock.elapsedRealtime()
            var offConfirmed = false
            try {
                setSkip(before.layers, true)
                val value = capture()
                snapshot().use { after ->
                    if (after.generation != before.generation || after.layers.any { current ->
                            before.layers.none { it.view === current.view && it.id == current.id }
                        }) throw Changed("owned_overlay_changed")
                }
                return value
            } catch (error: Throwable) {
                failure = error
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw error
            }
            finally {
                // Restoration must run even if enable/commit timed out or the capture was cancelled.
                try {
                    try { setSkip(before.layers, false) }
                    catch (first: Unavailable) {
                        // A transient commit failure must not leave a live overlay excluded.
                        try { setSkip(before.layers, false) }
                        catch (retry: Throwable) { retry.addSuppressed(first); throw retry }
                    }
                    offConfirmed = true
                }
                catch (cleanup: Throwable) {
                    android.util.Log.e("DoppelScreenshotExclusion", "Could not restore overlay screenshot flags", cleanup)
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    before.close()
                    android.util.Log.i("DoppelScreenshotExclusion", "overlay_count=${before.layers.size} flag_elapsed_ms=${SystemClock.elapsedRealtime() - flagStarted} off_confirmed=$offConfirmed")
                }
            }
        } finally { lock.unlock() }
    }

    /** Clone references while ViewRoot owns the originals; never release framework SurfaceControls. */
    private fun collect(): Snapshot {
        val layers = ArrayList<Layer>()
        try {
            val global = HiddenApiBypass.invoke(windowGlobal, null, "getInstance")
            val views = HiddenApiBypass.invoke(windowGlobal, global, "getWindowViews") as List<*>
            for (item in views) {
                val view = item as View
                val params = view.layoutParams as? WindowManager.LayoutParams ?: continue
                if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                    params.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) continue
                // Pending/invisible roots can become visible during capture: do not silently omit them.
                val root = view.rootSurfaceControl ?: throw Changed("owned_overlay_surface_pending")
                val original = HiddenApiBypass.invoke(root.javaClass, root, "getSurfaceControl") as SurfaceControl
                if (!original.isValid) throw Changed("owned_overlay_surface_pending")
                val id = HiddenApiBypass.invoke(SurfaceControl::class.java, original, "getLayerId") as Int
                val parcel = Parcel.obtain()
                val clone = try {
                    original.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    SurfaceControl.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                if (!clone.isValid) { clone.release(); throw Changed("owned_overlay_surface_replaced") }
                layers += Layer(view, id, clone)
            }
            return Snapshot(generation.get(), layers)
        } catch (error: Throwable) {
            layers.forEach { it.surface.release() }
            if (error is Changed) throw error
            throw Unavailable("owned_overlay_surface_unavailable", error)
        }
    }

    private fun snapshot(): Snapshot {
        val ready = CountDownLatch(1)
        val gate = Any()
        var abandoned = false
        var result: Snapshot? = null
        var failure: Throwable? = null
        if (!main.post {
            synchronized(gate) { if (abandoned) { ready.countDown(); return@post } }
            var acquired: Snapshot? = null
            var error: Throwable? = null
            try { acquired = collect() } catch (caught: Throwable) { error = caught }
            synchronized(gate) {
                if (abandoned) acquired?.close() else { result = acquired; failure = error }
            }
            ready.countDown()
        }) throw Unavailable("owned_overlay_main_unavailable")
        try {
            if (!ready.await(1500, TimeUnit.MILLISECONDS)) throw Changed("owned_overlay_main_timeout")
            synchronized(gate) {
                failure?.let { throw it }
                return requireNotNull(result).also { result = null }
            }
        } catch (error: Throwable) {
            // A late main callback releases its clones itself; completed but unclaimed clones are ours.
            synchronized(gate) { abandoned = true; result?.close(); result = null }
            throw error
        }
    }

    private fun setSkip(layers: List<Layer>, enabled: Boolean) {
        if (layers.isEmpty()) return
        var interrupted = if (!enabled) Thread.interrupted() else false
        try {
            val committed = CountDownLatch(1)
            SurfaceControl.Transaction().use { transaction ->
                for (layer in layers) if (layer.surface.isValid) skipScreenshot.invoke(transaction, layer.surface, enabled)
                transaction.addTransactionCommittedListener(executor) { committed.countDown() }
                transaction.apply()
                val deadline = SystemClock.elapsedRealtime() + 1500
                while (true) {
                    try {
                        if (!committed.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS))
                            throw Unavailable(if (enabled) "owned_overlay_exclusion_timeout" else "owned_overlay_restore_timeout")
                        break
                    } catch (cancelled: InterruptedException) {
                        if (enabled) throw cancelled
                        interrupted = true // Finish the bounded restore wait before restoring cancellation.
                    }
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException || error is Unavailable) throw error
            throw Unavailable(if (enabled) "owned_overlay_exclusion_failed" else "owned_overlay_restore_failed", error)
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
