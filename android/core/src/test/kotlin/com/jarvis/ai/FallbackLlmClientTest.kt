package com.jarvis.ai

import com.jarvis.ai.ai.fallback.AllProvidersFailedException
import com.jarvis.ai.ai.fallback.FallbackLlmClient
import com.jarvis.ai.ai.fallback.RetryPolicy
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FallbackLlmClientTest {

    private val request = LlmRequest("system", listOf(ChatMessage(ChatRole.USER, "hi")))
    private val time = FakeTime()
    private val policy = RetryPolicy(maxAttemptsPerProvider = 2, baseDelayMs = 500L, maxDelayMs = 4_000L, rateLimitCooldownMs = 60_000L)

    private fun client(vararg providers: FakeProvider) =
        FallbackLlmClient(providers = { providers.toList() }, retry = policy, time = time)

    @Test
    fun usesTheFirstWorkingProviderInOrder() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, ok("from gemini"))
        val groq = FakeProvider(ProviderId.GROQ, true, ok("from groq"))
        val client = client(gemini, groq)

        val response = client.complete(request)

        assertEquals("from gemini", response.text)
        assertEquals(ProviderId.GEMINI, response.provider)
        assertEquals(0, groq.calls)
        assertEquals(ProviderId.GEMINI, client.activeProvider.value)
    }

    @Test
    fun skipsUnconfiguredProvidersWithoutTouchingThem() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, false)
        val groq = FakeProvider(ProviderId.GROQ, true, ok("from groq"))

        val response = client(gemini, groq).complete(request)

        assertEquals(ProviderId.GROQ, response.provider)
        assertEquals(0, gemini.calls)
        assertEquals(ProviderId.GEMINI, response.failures.single().provider)
        assertIs<ProviderError.MissingConfig>(response.failures.single().error)
    }

    @Test
    fun retriesATimeoutWithBackoffThenSucceeds() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.Timeout("slow")), ok("second try"))

        val response = client(gemini).complete(request)

        assertEquals("second try", response.text)
        assertEquals(2, gemini.calls)
        assertEquals(2, response.attempts)
        assertEquals(500L, currentTime, "one backoff of baseDelayMs between the attempts")
    }

    @Test
    fun givesUpOnAProviderAfterTheAttemptLimitAndMovesOn() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.Network("down")), fail(ProviderError.Network("down")))
        val groq = FakeProvider(ProviderId.GROQ, true, ok("groq saved it"))

        val response = client(gemini, groq).complete(request)

        assertEquals(2, gemini.calls, "exactly maxAttemptsPerProvider, never more")
        assertEquals("groq saved it", response.text)
        assertEquals(2, response.failures.single().attempts)
    }

    @Test
    fun serverErrorsAreRetriedButClientErrorsAreNot() = runTest {
        val flaky = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.Http(503, "busy")), ok("recovered"))
        assertEquals("recovered", client(flaky).complete(request).text)

        val badKey = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.Http(401, "bad key")))
        val groq = FakeProvider(ProviderId.GROQ, true, ok("groq"))
        client(badKey, groq).complete(request)
        assertEquals(1, badKey.calls, "an auth failure must not be retried")
    }

    @Test
    fun rateLimitedProviderIsSkippedUntilItsCooldownEnds() = runTest {
        val gemini = FakeProvider(
            ProviderId.GEMINI, true,
            fail(ProviderError.RateLimited("slow down", retryAfterMs = 30_000L)),
            ok("back again"),
        )
        val groq = FakeProvider(ProviderId.GROQ, true, ok("groq 1"), ok("groq 2"))
        val client = client(gemini, groq)

        assertEquals("groq 1", client.complete(request).text)
        assertEquals(1, gemini.calls)

        time.now += 10_000L
        assertEquals("groq 2", client.complete(request).text)
        assertEquals(1, gemini.calls, "still cooling down: Gemini must not be called")

        time.now += 25_000L
        assertEquals("back again", client.complete(request).text)
        assertEquals(2, gemini.calls)
    }

    @Test
    fun anInvalidOrUnexpectedResponseFallsThroughToTheNextProvider() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.InvalidResponse("empty")))
        val groq = FakeProvider(ProviderId.GROQ, true, { throw IllegalStateException("provider bug") })
        val openRouter = FakeProvider(ProviderId.OPENROUTER, true, ok("finally"))

        val response = client(gemini, groq, openRouter).complete(request)

        assertEquals(ProviderId.OPENROUTER, response.provider)
        assertEquals(2, response.failures.size)
        assertTrue(response.failures.last().error.message.orEmpty().contains("IllegalStateException"))
    }

    @Test
    fun whenEverythingFailsTheErrorListsEachProvider() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, fail(ProviderError.Http(500, "boom")), fail(ProviderError.Http(500, "boom")))
        val openAi = FakeProvider(ProviderId.OPENAI, true, fail(ProviderError.Unavailable("service is down")))
        val client = client(gemini, openAi)

        val error = assertFailsWith<AllProvidersFailedException> { client.complete(request) }

        assertEquals(listOf(ProviderId.GEMINI, ProviderId.OPENAI), error.failures.map { it.provider })
        assertTrue(error.message.orEmpty().contains("Gemini"))
        assertTrue(error.message.orEmpty().contains("service is down"))
        assertNull(client.activeProvider.value)
    }

    @Test
    fun noConfiguredProviderGivesAnActionableMessage() = runTest {
        val client = client(FakeProvider(ProviderId.GEMINI, false), FakeProvider(ProviderId.GROQ, false))

        val error = assertFailsWith<AllProvidersFailedException> { client.complete(request) }

        assertTrue(error.message.orEmpty().contains("No AI provider is configured"))
    }

    @Test
    fun cancellationIsNeverSwallowed() = runTest {
        val stuck = FakeProvider(ProviderId.GEMINI, true, { awaitCancellation() })
        val groq = FakeProvider(ProviderId.GROQ, true, ok("must not be reached"))
        val job = launch { client(stuck, groq).complete(request) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(0, groq.calls, "a cancelled request must not fall through to another provider")
    }

    @Test
    fun customOrderIsHonoured() = runTest {
        val gemini = FakeProvider(ProviderId.GEMINI, true, ok("gemini"))
        val claude = FakeProvider(ProviderId.ANTHROPIC, true, ok("claude"))
        val client = FallbackLlmClient(providers = { listOf(claude, gemini) }, retry = policy, time = time)

        assertEquals(ProviderId.ANTHROPIC, client.complete(request).provider)
    }
}
