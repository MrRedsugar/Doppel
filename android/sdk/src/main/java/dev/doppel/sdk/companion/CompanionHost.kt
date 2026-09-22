package dev.doppel.sdk.companion

import org.json.JSONObject

/** HTTP status and the v1 success/error envelope; no internal runtime JSON may escape. */
internal data class CompanionResponse(val statusCode: Int, val body: JSONObject)

/** Pairing code supplies only a contract 401/403 status and a public, non-secret code. */
internal class CompanionAuthorizationException(
    val statusCode: Int,
    val code: String,
) : IllegalStateException("Companion authorization failed")

/** Created by the pairing service, never populated from request JSON. */
internal interface CompanionAuthContext {
    val pairId: String
    val grantedScopes: Set<String>
    val pairGeneration: Long

    /**
     * Recheck active pairing, generation and permissions under the pairing store lock.
     * The local publication/commit runs in that same boundary, so revocation cannot
     * slip between authorization and commit. Never hold this guard over network I/O
     * or enter an engine method from it. Local write order is host operation/transaction
     * -> engine lock -> this short pairing guard. Pair storage never calls back into
     * the engine while locked; deliver revocation notifications after releasing its lock.
     * A stale or revoked context fails closed; captured grantedScopes alone is insufficient.
     * Failure throws CompanionAuthorizationException (401 revoked, 403 insufficient scope).
     */
    fun <T> withAuthorization(requiredScopes: Set<String>, action: () -> T): T
}

/**
 * Implemented by the SDK host; transport, pairing and discovery belong to companion.
 * Reads construct authorized projections at source and never pump the runtime,
 * capture a screen, invoke a model or change the connection.
 * All calls run off the UI thread. Scope and identity come from the host, not clients.
 * Reads take a consistent engine snapshot and release its lock before authorizing
 * publication. Expected errors return the contract envelope; transport maps unexpected
 * exceptions to a safe 500 without stack traces, credentials or internal JSON.
 */
internal interface CompanionHost {
    fun readTaskEvents(auth: CompanionAuthContext): CompanionResponse = throw CompanionProtocolException(501, "capability_unavailable")

    /** Explicit human commands only. Transport supplies a live check of the actual authenticated LAN peer. */
    fun handoff(auth: CompanionAuthContext, operation: String, body: JSONObject, currentLan: () -> Boolean): CompanionResponse =
        throw CompanionProtocolException(501, "capability_unavailable")

    /** Without state permission return only the agreed minimal handshake fields. */
    fun readSnapshot(auth: CompanionAuthContext, scopeId: String?): CompanionResponse

    /** Active run requires state; terminal history requires history permission. */
    fun readRun(auth: CompanionAuthContext, scopeId: String, runId: String): CompanionResponse

    /** Collection is runs or conversations; cursor is opaque and scope/collection bound. */
    fun readHistory(
        auth: CompanionAuthContext,
        scopeId: String,
        collection: String,
        cursor: String?,
        limit: Int,
    ): CompanionResponse

    /** Public user/assistant messages only; linked runs are separately authorized. */
    fun readConversation(
        auth: CompanionAuthContext,
        scopeId: String,
        conversationId: String,
        cursor: String?,
        limit: Int,
    ): CompanionResponse

    /**
     * Host owns durable deduplication, atomic creation/control and protected-task admission.
     * Check an existing operation before new-operation version preconditions. Revalidate
     * authorization at commit; return accepted/applied/rejected/unknown, never guess success.
     * Gateway I/O is outside the pairing guard: already-dispatched remote work cannot
     * be recalled by a local lock. Preserve its receipt/unknown result for reconciliation.
     */
    fun submitOperation(auth: CompanionAuthContext, body: JSONObject): CompanionResponse

    /** Old scope permits only this active pair's local receipt, without old-domain I/O. */
    fun readOperation(
        auth: CompanionAuthContext,
        scopeId: String,
        requestId: String,
    ): CompanionResponse
}
