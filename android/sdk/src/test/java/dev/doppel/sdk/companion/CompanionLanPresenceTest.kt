package dev.doppel.sdk.companion

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files

class CompanionLanPresenceTest {
    @Test fun onlyAnotherLocalAddressInsideActualInterfacePrefixQualifies() {
        fun matches(peer: String, local: String, prefix: Int) = CompanionLanPresence.sameLink(
            InetAddress.getByName(peer), InetAddress.getByName(local), prefix)
        assertTrue(matches("192.168.10.9", "192.168.10.2", 24))
        assertTrue(matches("10.2.3.129", "10.2.3.130", 25))
        assertFalse(matches("10.2.3.100", "10.2.3.130", 25))
        assertFalse(matches("192.168.11.9", "192.168.10.2", 24))
        assertFalse(matches("192.168.10.2", "192.168.10.2", 24))
        assertFalse(matches("127.0.0.2", "127.0.0.1", 8))
        assertFalse(matches("8.8.8.8", "8.8.8.9", 24))
        assertFalse(matches("0.0.0.0", "10.2.3.4", 8))
        assertFalse(matches("224.0.0.2", "224.0.0.1", 24))
        assertFalse(matches("10.0.0.2", "10.0.0.1", 0))
        assertFalse(matches("10.0.0.2", "10.0.0.1", 33))
        assertTrue(matches("fd12:3456::2", "fd12:3456::1", 64))
        assertFalse(matches("fd12:3457::2", "fd12:3456::1", 64))
        assertFalse(matches("::1", "fd12:3456::1", 64))
        assertFalse(matches("10.0.0.1", "fd12:3456::1", 24))
    }

    @Test fun leaseExpiresClearsAndRechecksActualPairRevocation() {
        val directory = Files.createTempDirectory("companion-presence").toFile()
        try {
            var elapsed = 100L
            val pairs = CompanionPairings(directory.resolve("pairs.json"))
            fun pair(): CompanionAuthContext {
                val window = pairs.openWindow()
                val request = pairs.request(window.getString("pairing_id"), window.getString("secret"), "Nearby PC")
                val id = request.getString("pairing_request_id")
                pairs.decide(id, true, setOf("state"))
                val token = pairs.poll(id, request.getString("poll_token")).body.getString("bearer")
                pairs.activate(id, token)
                return pairs.authenticate(token)
            }
            val auth = pair()
            val other = pair()
            val presence = CompanionLanPresence { elapsed }
            assertFalse(presence.active())
            presence.update(auth, true)
            elapsed += 11_999
            assertTrue(presence.active())
            elapsed++
            assertFalse(presence.active())
            presence.update(auth, true)
            presence.update(other, true)
            presence.update(other, false)
            assertTrue("One PC disconnect must not erase another PC", presence.active())
            pairs.revoke(auth.pairId)
            assertFalse("Previously authenticated context cannot survive revocation", presence.active())
            assertTrue(runCatching { presence.update(auth, true) }.isFailure)
            presence.update(other, true)
            presence.clear()
            assertFalse(presence.active())
            assertFalse("New process/endpoint never restores presence", CompanionLanPresence().active())
            presence.update(other, true)
            elapsed--
            assertFalse("Clock regression fails closed", presence.active())
        } finally { directory.deleteRecursively() }
    }
}
