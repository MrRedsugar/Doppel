package dev.doppel.sdk

/** One physical hold. A result is consumable only after its matching release. */
internal class VoiceHoldState {
    enum class Phase { HOLDING, RELEASED, CANCELLED }
    var phase = Phase.HOLDING
        private set
    var hasFinal = false
        private set
    private var started = false
    private var result: String? = null
    private var consumed = false

    fun startRecording(): Boolean {
        if (phase != Phase.HOLDING || started) return false
        started = true
        return true
    }

    fun release() {
        if (phase != Phase.HOLDING) return
        if (started) phase = Phase.RELEASED else cancel()
    }

    fun cancel() { phase = Phase.CANCELLED; result = null }

    fun finalText(value: String) {
        if (!started || phase == Phase.CANCELLED || hasFinal || consumed) return
        hasFinal = true
        result = value.trim().takeIf { it.isNotEmpty() }
    }

    fun takeResult(): String? {
        if (phase != Phase.RELEASED || !hasFinal || consumed) return null
        consumed = true
        return result.also { result = null }
    }
}
