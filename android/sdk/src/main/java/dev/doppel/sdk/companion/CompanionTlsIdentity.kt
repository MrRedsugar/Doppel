package dev.doppel.sdk.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Platform keystore retains a non-exportable TLS private key; only its public pin leaves here. */
internal class CompanionTlsIdentity(context: Context) {
    // v1 pre-release keys lack raw-digest signing and cannot complete Android TLS handshakes.
    private val alias = "${context.packageName}.companion.tls.v2"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript itself, then requests NONEwithECDSA.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                    .setCertificateSubject(X500Principal("CN=Doppel Companion"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(now - 86_400_000))
                    .setCertificateNotAfter(Date(now + 3650L * 86_400_000)).build())
                generateKeyPair()
            }
        }
    }

    val spkiSha256: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).publicKey.encoded))

    fun sslContext(): SSLContext {
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "EC") arrayOf(alias) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                if (keyType == "EC") alias else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                if (keyType == "EC") alias else null
            override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
                if (requestedAlias == alias) store.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray() else null
            override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
                if (requestedAlias == alias) store.getKey(alias, null) as PrivateKey else null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
    }
}
