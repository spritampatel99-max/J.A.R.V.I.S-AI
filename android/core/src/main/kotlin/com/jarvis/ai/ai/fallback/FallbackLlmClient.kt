package com.jarvis.ai.ai.fallback

import com.jarvis.ai.ai.providers.LlmProvider
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.core.common.TimeSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Anything the planner/executor can ask a question. Tests substitute a fake. */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

data class ProviderFailure(val provider: ProviderId, val error: ProviderError, val attempts: Int)

data class LlmResponse(
    val text: String,
    /** The cloud provider that answered, or null when an on-device model did. */
    val provider: ProviderId?,
    val attempts: Int,
    /** Providers that were tried first and failed or were skipped. */
    val failures: List<ProviderFailure>,
    /** The on-device model that answered, or null when a cloud provider did. */
    val localModel: ModelId? = null,
    /** Plain-language notes about routes that were skipped or failed first, for example a local model that ran out of memory. */
    val routeNotes: List<String> = emptyList(),
) {
    init {
        require((provider == null) != (localModel == null)) { "A response comes from exactly one source" }
    }

    /** What the UI shows as the source of the answer. */
    val answeredBy: String
        get() = provider?.displayName ?: localModel?.let { "On-device ${ModelCatalog.spec(it).displayName}" }.orEmpty()
}

/** Nothing could answer. Callers show [message] to the user as it is. */
open class AiUnavailableException(message: String) : Exception(message)

class AllProvidersFailedException(val failures: List<ProviderFailure>) :
    AiUnavailableException(
        if (failures.all { it.error is ProviderError.MissingConfig }) {
            "No AI provider is configured. Add an API key in the Cloud tab."
        } else {
            "All AI providers failed: " +
                failures.joinToString("; ") { "${it.provider.displayName}: ${it.error.message}" }
        },
    )

data class RetryPolicy(
    /** Attempts per provider for retryable errors (timeouts, network, 5xx). */
    val maxAttemptsPerProvider: Int = 2,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 4_000L,
    /** How long a rate-limited provider is skipped when the server gave no Retry-After. */
    val rateLimitCooldownMs: Long = 60_000L,
    /**
     * Whether a timed-out provider is asked again before moving on. A timeout already cost the person the whole wait, so for
     * interactive chat the app sets this to false and goes straight to the next provider.
     */
    val retryTimeouts: Boolean = true,
) {
    init {
        require(maxAttemptsPerProvider >= 1) { "maxAttemptsPerProvider must be at least 1" }
    }

    fun backoffMs(attemptJustFailed: Int): Long {
        val exponent = (attemptJustFailed - 1).coerceIn(0, 20)
        return minOf(maxDelayMs, baseDelayMs shl exponent)
    }
}

/**
 * Tries providers in the order given until one answers.
 *
 *  - Not configured (no key / URL): skipped without a network call.
 *  - Timeout / network / 5xx: retried up to [RetryPolicy.maxAttemptsPerProvider] with
 *    exponential backoff (timeouts only when [RetryPolicy.retryTimeouts]), then the next provider is tried.
 *  - Rate limited: not retried; the provider is skipped for a cooldown period.
 *  - Bad key, empty or malformed response: next provider immediately.
 *  - Slow: when [hedgeAfterMs] is set and the provider in flight has not answered by then, the next configured provider is
 *    asked too and the first answer wins; the loser is cancelled. A slow first provider therefore costs at most [hedgeAfterMs]
 *    extra, instead of its whole timeout.
 *
 * It never retries forever, never swallows cancellation, and when everything fails it
 * throws [AllProvidersFailedException] listing exactly what happened per provider.
 */
class FallbackLlmClient(
    private val providers: () -> List<LlmProvider>,
    private val retry: RetryPolicy = RetryPolicy(),
    private val time: TimeSource = SystemTimeSource,
    private val hedgeAfterMs: Long? = null,
) : LlmClient {

    init {
        require(hedgeAfterMs == null || hedgeAfterMs > 0) { "hedgeAfterMs must be positive" }
    }

    private val cooldownUntilMs = ConcurrentHashMap<ProviderId, Long>()
    private val activeState = MutableStateFlow<ProviderId?>(null)

    /** The provider that answered most recently (null before the first answer or after total failure). */
    val activeProvider: StateFlow<ProviderId?> = activeState.asStateFlow()

    /** What one provider ended with: an answer, or why it gave none. [position] is its place in the configured order. */
    private class Finished(val position: Int, val provider: LlmProvider, val text: String?, val failure: ProviderFailure?)

    override suspend fun complete(request: LlmRequest): LlmResponse = coroutineScope {
        val failures = mutableListOf<Pair<Int, ProviderFailure>>()
        val candidates = mutableListOf<Pair<Int, LlmProvider>>()

        for ((position, provider) in providers().withIndex()) {
            if (!provider.isConfigured()) {
                failures += position to ProviderFailure(provider.id, ProviderError.MissingConfig("Not configured"), 0)
                continue
            }
            val cooldownEnd = cooldownUntilMs[provider.id]
            if (cooldownEnd != null && time.nowMs() < cooldownEnd) {
                failures += position to ProviderFailure(provider.id, ProviderError.RateLimited("Cooling down after a rate limit"), 0)
                continue
            }
            candidates += position to provider
        }

        fun ordered() = failures.sortedBy { it.first }.map { it.second }

        val attempts = AtomicInteger(0)
        val results = Channel<Finished>(Channel.UNLIMITED)
        val running = mutableListOf<Job>()
        var next = 0
        var inFlight = 0

        fun startNext() {
            val (position, provider) = candidates[next++]
            inFlight++
            running += launch { results.send(runProvider(position, provider, request, attempts)) }
        }

        if (candidates.isNotEmpty()) startNext()
        while (inFlight > 0) {
            val hedgeDelay = if (next < candidates.size) hedgeAfterMs else null
            val finished = if (hedgeDelay == null) results.receive() else withTimeoutOrNull(hedgeDelay) { results.receive() }
            if (finished == null) {
                // Still waiting on the provider in flight: ask the next one as well.
                startNext()
                continue
            }
            inFlight--
            if (finished.text != null) {
                running.forEach { it.cancel() }
                activeState.value = finished.provider.id
                return@coroutineScope LlmResponse(finished.text, finished.provider.id, attempts.get(), ordered())
            }
            failures += finished.position to checkNotNull(finished.failure)
            if (inFlight == 0 && next < candidates.size) startNext()
        }

        activeState.value = null
        throw AllProvidersFailedException(ordered())
    }

    /** Asks one provider, with this provider's own retries. Never throws except for cancellation. */
    private suspend fun runProvider(position: Int, provider: LlmProvider, request: LlmRequest, attempts: AtomicInteger): Finished {
        var attempt = 0
        var lastError: ProviderError = ProviderError.InvalidResponse("No attempt was made")
        while (attempt < retry.maxAttemptsPerProvider) {
            attempt++
            attempts.incrementAndGet()
            try {
                return Finished(position, provider, provider.complete(request), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderError) {
                lastError = e
                if (e is ProviderError.RateLimited) {
                    val waitMs = e.retryAfterMs ?: retry.rateLimitCooldownMs
                    cooldownUntilMs[provider.id] = time.nowMs() + waitMs
                    break
                }
                if (e is ProviderError.Timeout && !retry.retryTimeouts) break
                if (!e.retryable || attempt >= retry.maxAttemptsPerProvider) break
                delay(retry.backoffMs(attempt))
            } catch (e: Exception) {
                // A provider bug must not take the whole client down, and must not be hidden either.
                lastError = ProviderError.InvalidResponse("Unexpected ${e.javaClass.simpleName} from ${provider.id.displayName}", e)
                break
            }
        }
        return Finished(position, provider, null, ProviderFailure(provider.id, lastError, attempt))
    }
}
