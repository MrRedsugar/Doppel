package dev.doppel.developer

import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only lease for IME callbacks. Providers must read local state, never shell/UI observations. */
internal class ShellBridgeImeCallbackGuard(
    private val packageName: String,
    private val editorId: String,
    private val startedAt: Long,
    private val now: () -> Long,
    private val hostCurrent: () -> Boolean,
    private val currentCapability: () -> JSONObject
) : Closeable {
    private val closed = AtomicBoolean(false)
    init { require(packageName.isNotBlank() && editorId.isNotBlank() && startedAt >= 0) }

    private fun live(): Boolean {
        val at=now()
        return !closed.get() && at >= startedAt && at-startedAt < 2000 && hostCurrent()
    }

    fun isCurrent(): Boolean = runCatching {
        if (!live()) return false
        val current=currentCapability()
        current.optBoolean("input_available") && current.optString("package_name") == packageName &&
            current.optString("editor_id") == editorId && live()
    }.getOrDefault(false)

    override fun close() { closed.set(true) }
}
