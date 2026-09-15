package dev.doppel.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Main publishes the window update; the caller waits for composition without another main callback. */
internal class TouchHandoffGate(
    private val timeoutMs:Long=800,
    private val compositorMs:Long=80,
    private val now:()->Long={System.nanoTime()/1_000_000}
) {
    enum class Result { READY, REJECTED, TIMED_OUT, CANCELLED }
    private val deadline=now()+timeoutMs
    private val changed=CountDownLatch(1)
    private val appliedAt=AtomicReference<Long?>()
    @Volatile private var rejected=false
    fun applied() {appliedAt.compareAndSet(null,now());changed.countDown()}
    fun reject() {rejected=true;changed.countDown()}
    @Throws(InterruptedException::class)
    fun await(current:()->Boolean,onCompositorWait:()->Unit={}):Result {
        while(changed.count>0) {
            if(!current()) return Result.CANCELLED
            val remaining=deadline-now()
            if(remaining<=0) return Result.TIMED_OUT
            changed.await(minOf(remaining,20),TimeUnit.MILLISECONDS)
        }
        val applied=appliedAt.get()
        if(rejected || applied==null) return Result.REJECTED
        onCompositorWait()
        val wait=CountDownLatch(1)
        while(true) {
            if(!current()) return Result.CANCELLED
            val time=now()
            if(time>=deadline) return Result.TIMED_OUT
            val remaining=applied+compositorMs-time
            if(remaining<=0) return Result.READY
            wait.await(minOf(remaining,deadline-time,20),TimeUnit.MILLISECONDS)
        }
    }
}
