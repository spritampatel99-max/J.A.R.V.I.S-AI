package com.jarvis.ai.core.security

import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.core.common.TimeSource
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the user is being asked to approve. Shown by native UI. */
data class ConfirmationRequest(
    val id: String,
    val toolName: String,
    /** Built by the tool from validated arguments (Tool.describe), never from model text. */
    val summary: String,
    val risk: Risk,
    val expiresAtMs: Long,
)

sealed interface ConfirmationDecision {
    /** Carries the fingerprint of the action that was approved so the caller can re-check it. */
    data class Approved(val fingerprint: String) : ConfirmationDecision

    data object Denied : ConfirmationDecision

    data object Expired : ConfirmationDecision
}

/**
 * The only way to answer a confirmation. It is deliberately a separate interface from
 * [ConfirmationGate.request]: the UI layer holds this, the agent code never does, and no
 * model-callable tool is ever given it.
 */
interface ConfirmationResponder {
    /** @return true if this call resolved the pending request. */
    fun approve(requestId: String): Boolean

    /** @return true if this call resolved the pending request. */
    fun deny(requestId: String): Boolean
}

/**
 * The one thing the executor needs from the confirmation system: ask, then wait for the
 * user's answer. [ConfirmationGate] is the real implementation; being an interface lets the
 * executor's own safety checks be tested against a gate that misbehaves.
 */
fun interface ConfirmationRequester {
    suspend fun request(toolName: String, summary: String, risk: Risk, fingerprint: String): ConfirmationDecision
}

/**
 * Confirmation for sensitive actions, bound to the *action* being approved.
 *
 * Design rules (these are what the desktop version's token scheme lacked):
 *  - The request id is never put into any prompt or tool result, so a model cannot
 *    "confirm" anything: approving requires a call from the UI layer.
 *  - Approval is bound to a fingerprint of (tool, validated arguments). The executor
 *    re-computes the fingerprint right before running the tool.
 *  - A request is single-use and expires after [ttlMs].
 *  - Only one request is pending at a time; others wait their turn.
 */
class ConfirmationGate(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val time: TimeSource = SystemTimeSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ConfirmationResponder, ConfirmationRequester {

    private class Pending(
        val request: ConfirmationRequest,
        val fingerprint: String,
        val decision: CompletableDeferred<ConfirmationDecision>,
    )

    private val queue = Mutex()
    private val lock = Any()
    private var current: Pending? = null
    private val pendingState = MutableStateFlow<ConfirmationRequest?>(null)

    /** The request the UI should currently be showing, or null. */
    val pending: StateFlow<ConfirmationRequest?> = pendingState.asStateFlow()

    /**
     * Suspends until the user approves or denies, or the request expires.
     * If the calling coroutine is cancelled the request is withdrawn.
     */
    override suspend fun request(
        toolName: String,
        summary: String,
        risk: Risk,
        fingerprint: String,
    ): ConfirmationDecision = queue.withLock {
        val request = ConfirmationRequest(
            id = newId(),
            toolName = toolName,
            summary = summary,
            risk = risk,
            expiresAtMs = time.nowMs() + ttlMs,
        )
        val entry = Pending(request, fingerprint, CompletableDeferred())
        synchronized(lock) { current = entry }
        pendingState.value = request
        try {
            withTimeoutOrNull(ttlMs) { entry.decision.await() } ?: ConfirmationDecision.Expired
        } finally {
            synchronized(lock) {
                if (current === entry) current = null
            }
            pendingState.value = null
        }
    }

    override fun approve(requestId: String): Boolean =
        resolve(requestId) { entry -> ConfirmationDecision.Approved(entry.fingerprint) }

    override fun deny(requestId: String): Boolean =
        resolve(requestId) { ConfirmationDecision.Denied }

    private fun resolve(requestId: String, decide: (Pending) -> ConfirmationDecision): Boolean {
        val entry = synchronized(lock) {
            current?.takeIf { it.request.id == requestId }
        } ?: return false
        return entry.decision.complete(decide(entry))
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 120_000L
    }
}
