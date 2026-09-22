package dev.doppel.sdk.companion

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.Base64

/** One instance per app process, owned by the visible foreground companion service. */
internal class CompanionEndpoint(context: Context, private val host: CompanionHost) : Closeable {
    companion object {
        private var sharedPairs: CompanionPairings? = null

        @Synchronized private fun pairings(context: Context): CompanionPairings = sharedPairs
            ?: CompanionPairings(File(context.applicationContext.noBackupFilesDir, "companion/pairs.json"))
                .also { sharedPairs = it }

        /** Local management stays available without starting networking or creating TLS keys. */
        fun localPairingState(context: Context): JSONObject = pairings(context).phoneState()
        fun revokeLocalPair(context: Context, pairId: String) = pairings(context).revoke(pairId)
    }

    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val nsd = app.getSystemService(NsdManager::class.java)
    val companionDeviceId = CompanionIdentity.loadOrCreate(app)
    private val identity = CompanionTlsIdentity(app)
    private val pairs = pairings(app)
    private val presence = CompanionLanPresence(android.os.SystemClock::elapsedRealtime)
    private var listener: CompanionTlsListener? = null
    private var selectedNetwork: Network? = null
    private var networkLinks = emptySet<Pair<InetAddress, Int>>()
    private var networkGeneration = 0L
    private var address: InetAddress? = null
    private var origin: String? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var watching = false
    private var watchingWifi = false
    private var discoveryReady = false
    private var errorCode: String? = null

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            synchronized(this@CompanionEndpoint) {
                if (watchingWifi && state in setOf(WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED))
                    stop("network_unavailable")
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { synchronized(this@CompanionEndpoint) { if (network == selectedNetwork) stop("network_unavailable") } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@CompanionEndpoint) { if (network == selectedNetwork && !isLan(capabilities)) stop("network_unavailable") }
        }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
            synchronized(this@CompanionEndpoint) {
                if (network == selectedNetwork && properties.linkAddresses.map { it.address to it.prefixLength }.toSet() != networkLinks)
                    stop("network_changed")
            }
        }
    }

    /** Call only after explicit user enable and foreground notification, never from a GET. */
    @Synchronized fun start() {
        if (listener != null) return
        val network = connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.let(::isLan) == true }
            ?: throw CompanionProtocolException(503, "network_unavailable")
        val links = connectivity.getLinkProperties(network)?.linkAddresses
            ?: throw CompanionProtocolException(503, "network_unavailable")
        val localAddress = links.map { it.address }
            .filter { it.isSiteLocalAddress || it.isLinkLocalAddress && it is Inet4Address || (it.address[0].toInt() and 0xfe) == 0xfc }
            .sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull()
            ?: throw CompanionProtocolException(503, "network_unavailable")
        selectedNetwork = network
        address = localAddress
        networkLinks = links.map { it.address to it.prefixLength }.toSet()
        val generation = ++networkGeneration
        try {
            val http = CompanionHttp(CompanionRouter(host, pairs, lanAccess = { peer ->
                { synchronized(this) { generation == networkGeneration && sameLanPeer(peer) } }
            }) { auth, peer, present ->
                synchronized(this) {
                    if (generation != networkGeneration || !sameLanPeer(peer))
                        throw CompanionProtocolException(403, "lan_presence_unavailable")
                    presence.update(auth, present)
                }
            })
            val server = CompanionTlsListener(identity.sslContext(), localAddress, http)
            listener = server
            origin = URI("https", null, localAddress.hostAddress, server.port, null, null, null).toASCIIString()
            errorCode = null
            connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), networkCallback)
            watching = true
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                // Explicit Wi-Fi shutdown can precede ConnectivityManager.onLost on vendor builds.
                app.registerReceiver(wifiReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
                watchingWifi = true
            }
            val callback = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { synchronized(this@CompanionEndpoint) { if (registration === this) discoveryReady = true } }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { synchronized(this@CompanionEndpoint) { if (registration === this) stop("discovery_unavailable") } }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            }
            registration = callback
            val info = NsdServiceInfo().apply {
                serviceName = "Doppel-${companionDeviceId.take(8)}"
                serviceType = "_doppel._tcp."
                port = server.port
                setAttribute("api", "1")
                setAttribute("id", companionDeviceId)
                if (Build.VERSION.SDK_INT >= 33) setNetwork(network)
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (error: Exception) { stop("service_unavailable"); throw error }
    }

    /** Returned URI is secret: display/copy only on an unlocked, explicitly opened phone page. */
    @Synchronized fun openPairing(): String {
        val endpoint = origin ?: throw CompanionProtocolException(503, "service_unavailable")
        val payload = pairs.openWindow().put("v", 1).put("endpoint", endpoint)
            .put("companion_device_id", companionDeviceId).put("spki_sha256", identity.spkiSha256)
        return "doppel-pair://v1#" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    fun decidePairing(requestId: String, approved: Boolean, scopes: Set<String>) = pairs.decide(requestId, approved, scopes)
    fun cancelPairing() = pairs.cancelWindow()
    fun revokePair(pairId: String) = pairs.revoke(pairId)

    @Synchronized fun phoneState(): JSONObject {
        val nearby = hasTrustedLanPresence()
        return pairs.phoneState().put("service_enabled", listener != null)
            .put("discovery_ready", discoveryReady).put("endpoint", origin ?: JSONObject.NULL)
            .put("companion_device_id", companionDeviceId).put("error_code", errorCode ?: JSONObject.NULL)
            .put("trusted_lan_presence", nearby)
    }

    @Synchronized fun hasTrustedLanPresence(): Boolean = currentNetwork() && presence.active()

    private fun sameLanPeer(peer: InetAddress): Boolean = currentNetwork() &&
        connectivity.allNetworks.none { net -> connectivity.getLinkProperties(net)?.linkAddresses?.any { it.address == peer } == true } &&
        networkLinks.any { CompanionLanPresence.sameLink(peer, it.first, it.second) }

    /** Recheck now, rather than relying on delivery timing of Android network callbacks. */
    private fun currentNetwork(): Boolean {
        val network = selectedNetwork ?: return false
        val links = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address to it.prefixLength }?.toSet()
        if (listener == null || connectivity.getNetworkCapabilities(network)?.let(::isLan) != true || links != networkLinks) {
            stop("network_changed")
            return false
        }
        return true
    }

    @Synchronized private fun stop(reason: String?) {
        networkGeneration++
        presence.clear()
        pairs.cancelWindow()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        if (watching) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        watching = false
        if (watchingWifi) runCatching { app.unregisterReceiver(wifiReceiver) }
        watchingWifi = false
        runCatching { listener?.close() }
        listener = null
        selectedNetwork = null
        networkLinks = emptySet()
        address = null
        origin = null
        discoveryReady = false
        errorCode = reason
    }

    override fun close() = synchronized(this) { stop(null) }

    private fun isLan(capabilities: NetworkCapabilities) = !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
}
