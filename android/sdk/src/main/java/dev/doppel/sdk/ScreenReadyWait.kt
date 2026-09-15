package dev.doppel.sdk

internal class ScreenReadInterruptedException : java.util.concurrent.CancellationException("屏幕读取已中断")

/** Retries only explicit screen readiness failures, never an action or an arbitrary exception. */
internal class ScreenReadyWait(
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val cancelled: () -> Boolean,
    private val maxWaitMs: Long = 5000
) {
    init { require(maxWaitMs in 0..5000) }
    private var waitStarted: Long? = null
    private var waitedMs = 0L

    fun <T> read(observe: () -> T): T {
        while (true) {
            if (cancelled()) throw ScreenReadInterruptedException()
            try {
                val result = observe()
                if (cancelled()) throw ScreenReadInterruptedException()
                return result
            } catch (failure: ScreenNotReadyException) {
                if (cancelled()) throw ScreenReadInterruptedException()
                val started = waitStarted ?: now().also { waitStarted = it }
                // Nested screenshot reads share one budget. Requested sleep also bounds a faulty clock.
                fun remaining() = minOf(maxWaitMs - (now() - started).coerceAtLeast(0), maxWaitMs - waitedMs)
                val available = remaining()
                if (available <= 0) throw failure
                val interval = if (failure.reason == ScreenReadinessReason.EMPTY_FRAME) 400L else 100L
                val delay = minOf(interval, available)
                var left = delay
                while (left > 0) {
                    if (cancelled()) throw ScreenReadInterruptedException()
                    val budget = remaining()
                    if (budget <= 0) throw failure
                    val chunk = minOf(100, left, budget)
                    waitedMs += chunk
                    sleep(chunk)
                    left -= chunk
                }
                if (cancelled()) throw ScreenReadInterruptedException()
                // A partial final wait must not request another screenshot sooner than 400 ms.
                if (delay < interval) throw failure
            }
        }
    }
}
