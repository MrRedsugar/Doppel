package dev.doppel.sdk

import java.util.concurrent.atomic.AtomicBoolean

internal object TaskSubmissionGate {
    val creating = AtomicBoolean(false)
}
