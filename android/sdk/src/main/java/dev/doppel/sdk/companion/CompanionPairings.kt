package dev.doppel.sdk.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal class CompanionProtocolException(val statusCode: Int, val code: String) :
    IllegalStateException("Companion request failed")

/** Supply a file in Context.noBackupFilesDir. Only activated token hashes are persisted. */
internal class CompanionPairings(
    private val file: File,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Pair(val id: String, val name: String, val hash: String, val scopes: Set<String>, val generation: Long, val activationRequestId: String)
    private class Window(val id: String, val secretHash: String, val deadline: Long, val expiresAt: Long) {
        var wrongSecrets = 0
        var requestId: String? = null
        var pollHash: String? = null
        var clientName = ""
        var state = "pending"
        var pair: Pair? = null
        var bearer: String? = null
        var delivered = false
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val allScopes = setOf("state", "history", "submit", "control", "screen_control")
    private var pairs = load()
    private var window: Window? = null // Deliberately not persisted: restart invalidates unfinished pairing.
    private val failures = ArrayDeque<Long>()

    private fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun hash(value: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun matches(value: String, expected: String) = value.length <= 256 &&
        MessageDigest.isEqual(hash(value).toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))
    private fun fail(status: Int, code: String): Nothing = throw CompanionProtocolException(status, code)
    private fun checkRate() {
        val now = elapsedTime()
        while (failures.isNotEmpty() && now - failures.first() >= 60_000) failures.removeFirst()
        if (failures.size >= 20) fail(429, "rate_limited")
    }
    private fun bad(status: Int, code: String): Nothing {
        // Throttle failures without letting unauthenticated traffic lock out valid credentials.
        checkRate()
        failures.addLast(elapsedTime())
        fail(status, code)
    }
    private fun activeWindow(): Window {
        val current = window ?: fail(410, "pairing_expired")
        if (elapsedTime() >= current.deadline) {
            current.bearer = null
            window = null
            fail(410, "pairing_expired")
        }
        return current
    }

    /** Local phone UI only; callers must require an unlocked phone and explicit user action. */
    fun openWindow(): JSONObject = synchronized(lock) {
        val secret = token()
        window?.bearer = null
        val current = Window(UUID.randomUUID().toString(), hash(secret), elapsedTime() + 300_000, wallTime() + 300_000)
        window = current
        JSONObject().put("pairing_id", current.id).put("secret", secret).put("expires_at_ms", current.expiresAt)
    }

    /** Cancels only the unfinished window; activated pairs remain until explicit revocation. */
    fun cancelWindow() = synchronized(lock) { window?.bearer = null; window = null }

    fun request(pairingId: String, secret: String, clientName: String): JSONObject = synchronized(lock) {
        if (clientName.length !in 1..80 || clientName.any(Char::isISOControl)) bad(422, "invalid_request")
        val current = window
        // Unknown IDs and incorrect secrets use the same error; never disclose a live ID.
        if (current == null || current.id != pairingId || !matches(secret, current.secretHash)) {
            if (current?.id == pairingId && ++current.wrongSecrets >= 5) { current.bearer = null; window = null }
            bad(401, "authentication_required")
        }
        activeWindow()
        if (current.requestId != null) fail(410, "pairing_result_consumed")
        val pollToken = token()
        current.requestId = UUID.randomUUID().toString()
        current.pollHash = hash(pollToken)
        current.clientName = clientName
        JSONObject().put("pairing_request_id", current.requestId).put("poll_token", pollToken)
            .put("expires_at_ms", current.expiresAt).put("state", "pending")
    }

    /** Local phone decision only. Remote requests cannot supply or expand granted scopes. */
    fun decide(requestId: String, approved: Boolean, scopes: Set<String>) = synchronized(lock) {
        val current = activeWindow()
        if (current.requestId != requestId || current.pair != null || current.state != "pending") fail(409, "pairing_result_consumed")
        if (!approved) { current.state = "rejected"; return@synchronized }
        if (scopes.isEmpty() || !allScopes.containsAll(scopes) ||
            (("submit" in scopes || "control" in scopes || "screen_control" in scopes) && "state" !in scopes)) fail(422, "invalid_request")
        val bearer = token()
        current.pair = Pair(UUID.randomUUID().toString(), current.clientName, hash(bearer), scopes.toSet(), random.nextLong(), requestId)
        current.bearer = bearer
        current.state = "approved"
    }

    fun poll(requestId: String, pollToken: String): CompanionResponse = synchronized(lock) {
        val current = activeWindow()
        if (current.requestId != requestId || !matches(pollToken, current.pollHash.orEmpty())) bad(401, "authentication_required")
        if (current.state == "rejected") fail(403, "pairing_rejected")
        if (current.state == "pending") return@synchronized CompanionResponse(202, JSONObject().put("state", "pending"))
        if (current.delivered) fail(410, "pairing_result_consumed")
        val pair = checkNotNull(current.pair)
        val response = JSONObject().put("state", "approved").put("pair_id", pair.id)
            .put("bearer", checkNotNull(current.bearer)).put("granted_scopes", JSONArray(pair.scopes))
            .put("expires_at_ms", current.expiresAt)
        current.delivered = true
        current.bearer = null
        CompanionResponse(200, response)
    }

    fun activate(requestId: String, bearer: String): JSONObject = synchronized(lock) {
        pairs.values.firstOrNull { it.activationRequestId == requestId && matches(bearer, it.hash) }?.let {
            return@synchronized JSONObject().put("state", "active").put("pair_id", it.id).put("granted_scopes", JSONArray(it.scopes))
        }
        val current = activeWindow()
        val pair = current.pair
        if (current.requestId != requestId || !current.delivered || pair == null || !matches(bearer, pair.hash)) bad(401, "authentication_required")
        if (pair.id !in pairs) {
            val updated = pairs + (pair.id to pair)
            save(updated) // Failed persistence cannot turn into an active in-memory authorization.
            pairs = updated
        }
        JSONObject().put("state", "active").put("pair_id", pair.id).put("granted_scopes", JSONArray(pair.scopes))
    }

    fun authenticate(bearer: String): CompanionAuthContext = synchronized(lock) {
        val pair = pairs.values.firstOrNull { matches(bearer, it.hash) }
            ?: bad(401, "authentication_required")
        object : CompanionAuthContext {
            override val pairId = pair.id
            override val grantedScopes: Set<String> get() = pair.scopes.toSet()
            override val pairGeneration = pair.generation
            override fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T = synchronized(lock) {
                val latest = pairs[pair.id]
                if (latest == null || latest.generation != pairGeneration) throw CompanionAuthorizationException(401, "pairing_revoked")
                if (!latest.scopes.containsAll(requiredScopes)) throw CompanionAuthorizationException(403, "capability_denied")
                action()
            }
        }
    }

    /** Local phone UI only. Notifications, engine calls and I/O callbacks belong outside this lock. */
    fun revoke(pairId: String) = synchronized(lock) {
        val updated = pairs - pairId
        save(updated)
        pairs = updated
        if (window?.pair?.id == pairId) { window?.bearer = null; window = null }
    }

    fun phoneState(): JSONObject = synchronized(lock) {
        val pending = window?.takeIf { elapsedTime() < it.deadline }
        JSONObject().put("pairs", JSONArray(pairs.values.map {
            JSONObject().put("pair_id", it.id).put("client_name", it.name).put("granted_scopes", JSONArray(it.scopes))
        })).put("pairing", pending?.let {
            JSONObject().put("pairing_request_id", it.requestId ?: JSONObject.NULL).put("client_name", it.clientName)
                .put("state", if (it.pair?.id in pairs) "active" else it.state).put("expires_at_ms", it.expiresAt)
        } ?: JSONObject.NULL)
    }

    private fun load(): Map<String, Pair> {
        if (!file.exists()) return emptyMap()
        check(file.length() <= 1_048_576) { "Companion pairing storage invalid" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        check(root.getInt("version") == 1) { "Companion pairing storage version invalid" }
        val rows = root.getJSONArray("pairs")
        return (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            val scopes = row.getJSONArray("scopes").let { values -> (0 until values.length()).map(values::getString).toSet() }
            val pair = Pair(row.getString("id"), row.getString("name"), row.getString("hash"), scopes, row.getLong("generation"), row.getString("activation_request_id"))
            check(pair.hash.length == 43 && allScopes.containsAll(scopes))
            pair.id to pair
        }
    }

    private fun save(updated: Map<String, Pair>) {
        val rows = JSONArray(updated.values.map {
            JSONObject().put("id", it.id).put("name", it.name).put("hash", it.hash)
                .put("scopes", JSONArray(it.scopes)).put("generation", it.generation).put("activation_request_id", it.activationRequestId)
        })
        val bytes = JSONObject().put("version", 1).put("pairs", rows).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1_048_576) fail(507, "operation_capacity_reached")
        val parent = checkNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File(file.path + ".new")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        // Same private directory/filesystem; fail closed if atomic replacement is unavailable.
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
