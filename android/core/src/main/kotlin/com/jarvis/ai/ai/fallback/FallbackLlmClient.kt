package com.jarvis.ai.ai.fallback

import com.jarvis.ai.ai.providers.LlmProvider
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.core.common.TimeSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    /** Which provider actually answered; the UI shows this. */
    val provider: ProviderId,
    val attempts: Int,
    /** Providers that were tried first and failed or were skipped. */
    val failures: List<ProviderFailure>,
)

class AllProvidersFailedException(val failures: List<ProviderFailure>) :
    Exception(
        if (failures.all { it.error is ProviderError.MissingConfig }) {
            "No AI provider is configured. Add an API key under Providers."
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
 *    exponential backoff, then the next provider is tried.
 *  - Rate limited: not retried; the provider is skipped for a cooldown period.
 *  - Bad key, empty or malformed response: next provider immediately.
 *
 * It never retries forever, never swallows cancellation, and when everything fails it
 * throws [AllProvidersFailedException] listing exactly what happened per provider.
 */
class FallbackLlmClient(
    private val providers: () -> List<LlmProvider>,
    private val retry: RetryPolicy = RetryPolicy(),
    private val time: TimeSource = SystemTimeSource,
) : LlmClient {

    private val cooldownUntilMs = ConcurrentHashMap<ProviderId, Long>()
    private val activeState = MutableStateFlow<ProviderId?>(null)

    /** The provider that answered most recently (null before the first answer or after total failure). */
    val activeProvider: StateFlow<ProviderId?> = activeState.asStateFlow()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val failures = mutableListOf<ProviderFailure>()
        var totalAttempts = 0

        for (provider in providers()) {
            if (!provider.isConfigured()) {
                failures += ProviderFailure(provider.id, ProviderError.MissingConfig("Not configured"), 0)
                continue
            }
            val cooldownEnd = cooldownUntilMs[provider.id]
            if (cooldownEnd != null && time.nowMs() < cooldownEnd) {
                failures += ProviderFailure(provider.id, ProviderError.RateLimited("Cooling down after a rate limit"), 0)
                continue
            }

            var attempt = 0
            var lastError: ProviderError = ProviderError.InvalidResponse("No attempt was made")
            while (attempt < retry.maxAttemptsPerProvider) {
                attempt++
                totalAttempts++
                try {
                    val text = provider.complete(request)
                    activeState.value = provider.id
                    return LlmResponse(text, provider.id, totalAttempts, failures.toList())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderError) {
                    lastError = e
                    if (e is ProviderError.RateLimited) {
                        val waitMs = e.retryAfterMs ?: retry.rateLimitCooldownMs
                        cooldownUntilMs[provider.id] = time.nowMs() + waitMs
                        break
                    }
                    if (!e.retryable || attempt >= retry.maxAttemptsPerProvider) break
                    delay(retry.backoffMs(attempt))
                } catch (e: Exception) {
                    // A provider bug must not take the whole client down, and must not be hidden either.
                    lastError = ProviderError.InvalidResponse(
                        "Unexpected ${e.javaClass.simpleName} from ${provider.id.displayName}",
                        e,
                    )
                    break
                }
            }
            failures += ProviderFailure(provider.id, lastError, attempt)
        }

        activeState.value = null
        throw AllProvidersFailedException(failures)
    }
}
