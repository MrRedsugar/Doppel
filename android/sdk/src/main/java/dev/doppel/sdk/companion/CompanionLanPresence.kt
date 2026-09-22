package dev.doppel.sdk.companion

import java.net.InetAddress

/** Short, process-local proof from an authenticated request on the current LAN. */
internal class CompanionLanPresence(private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Lease(val auth: CompanionAuthContext, val seenAt: Long)
    private val leases = mutableMapOf<String, Lease>()

    @Synchronized fun update(auth: CompanionAuthContext, present: Boolean) = auth.withAuthorization(emptySet()) {
        if (present) leases[auth.pairId] = Lease(auth, elapsedTime()) else leases.remove(auth.pairId)
        Unit
    }

    @Synchronized fun active(): Boolean {
        val now = elapsedTime()
        leases.entries.removeAll { (_, lease) ->
            now - lease.seenAt !in 0 until LEASE_MS ||
                runCatching { lease.auth.withAuthorization(emptySet()) { true } }.getOrDefault(false).not()
        }
        return leases.isNotEmpty()
    }

    @Synchronized fun clear() = leases.clear()

    companion object {
        const val LEASE_MS = 12_000L

        fun sameLink(peer: InetAddress, local: InetAddress, prefixLength: Int): Boolean {
            val remote = peer.address
            val own = local.address
            if (remote.size != own.size || prefixLength !in 1..(own.size * 8) || peer == local ||
                peer.isLoopbackAddress || peer.isAnyLocalAddress || peer.isMulticastAddress || !localAddress(peer)) return false
            val whole = prefixLength / 8
            val partial = prefixLength % 8
            if ((0 until whole).any { remote[it] != own[it] }) return false
            val mask = (0xff shl (8 - partial)) and 0xff
            return partial == 0 || (remote[whole].toInt() and mask) == (own[whole].toInt() and mask)
        }

        private fun localAddress(address: InetAddress) = address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
}
