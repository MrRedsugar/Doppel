package dev.doppel.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ownership changes atomically; cancellation can dispose only a frame the caller has not taken. */
internal class LegacyCaptureMailbox<T : Any>(private val dispose: (T) -> Unit) {
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private var completed = false
    private var taken = false
    private var frame: T? = null
    private var failure: Exception? = null
    val isComplete: Boolean get() = synchronized(lock) { completed }
    fun await(millis: Long) = ready.await(millis, TimeUnit.MILLISECONDS)
    fun deliver(value: T) {
        val accepted = synchronized(lock) {
            if (completed) false else { completed = true; frame = value; true }
        }
        if (!accepted) dispose(value)
        ready.countDown()
    }
    fun cancel(error: Exception) {
        val discarded = synchronized(lock) {
            if (taken) null else {
                completed = true; if (failure == null) failure = error
                frame.also { frame = null }
            }
        }
        discarded?.let(dispose)
        ready.countDown()
    }
    fun take(): T = synchronized(lock) {
        check(!taken) { "屏幕帧已交付" }
        failure?.let { throw it }
        check(completed) { "屏幕帧尚未就绪" }
        checkNotNull(frame).also { frame = null; taken = true }
    }
}
