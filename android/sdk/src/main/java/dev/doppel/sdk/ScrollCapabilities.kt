package dev.doppel.sdk

/** Platform action IDs are constants so the affordance policy can be tested without Android. */
internal object ScrollCapabilities {
    private val directional = linkedMapOf("up" to 16908344, "down" to 16908346, "left" to 16908345, "right" to 16908347)
    fun action(direction: String, advertised: Set<Int>): Int? {
        val explicit = directional[direction] ?: return null
        if (explicit in advertised) return explicit
        if (advertised.any { it in directional.values }) return null
        // Legacy forward/backward has no trustworthy horizontal axis information.
        val legacy = when (direction) { "up" -> 8192; "down" -> 4096; else -> return null }
        return legacy.takeIf { it in advertised }
    }
    fun directions(advertised: Set<Int>): List<String> = directional.keys.filter { action(it, advertised) != null }
}
