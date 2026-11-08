package com.jarvis.ai

import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.ModelLister
import com.jarvis.ai.ai.providers.ProviderDefaults
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModelListerTest {

    private val transport = FakeTransport()
    private val lister = ModelLister(JsonHttp(transport))

    @Test
    fun geminiKeepsOnlyModelsThatCanGenerateTextAndSendsTheKeyInAHeader() = runTest {
        transport.reply(
            200,
            """{"models":[
              {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-3.5-flash-image","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]}
            ]}""",
        )

        val models = lister.list(ProviderId.GEMINI, "KEY123")

        assertEquals(listOf("gemini-3.6-flash", "gemini-3.8-flash"), models.map { it.id })
        assertEquals("Gemini 3.8 Flash", models.last().label)
        val (url, headers) = transport.gets.single()
        assertEquals("KEY123", headers["x-goog-api-key"])
        assertFalse(url.contains("KEY123"), "the key must never be in the address")
    }

    @Test
    fun groqListDropsSpeechAndGuardModels() = runTest {
        transport.reply(
            200,
            """{"data":[{"id":"llama-3.1-8b-instant"},{"id":"whisper-large-v3"},{"id":"openai/gpt-oss-20b"},{"id":"meta-llama/llama-guard-4-12b"}]}""",
        )

        val models = lister.list(ProviderId.GROQ, "gsk_x")

        assertEquals(listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b"), models.map { it.id })
        assertEquals("Bearer gsk_x", transport.gets.single().second["Authorization"])
    }

    @Test
    fun openRouterListsFreeModelsFirstAndNeedsNoKey() = runTest {
        transport.reply(
            200,
            """{"data":[
              {"id":"vendor/paid-model","name":"Paid","pricing":{"prompt":"0.000001","completion":"0.000002"}},
              {"id":"vendor/zeta-free-model:free","name":"Free","pricing":{"prompt":"0","completion":"0"}}
            ]}""",
        )

        val models = lister.list(ProviderId.OPENROUTER, null)

        assertEquals(listOf("vendor/zeta-free-model:free", "vendor/paid-model"), models.map { it.id })
        assertTrue(models.first().free)
        assertFalse(models.last().free)
        assertFalse(transport.gets.single().second.containsKey("Authorization"))
    }

    @Test
    fun openAiAndClaudeUseTheirOwnAddresses() = runTest {
        transport.reply(200, """{"data":[{"id":"some-chat-model"}]}""")
        lister.list(ProviderId.OPENAI, "sk-test")
        transport.reply(200, """{"data":[{"id":"claude-x","display_name":"Claude X"}]}""")
        val claude = lister.list(ProviderId.ANTHROPIC, "ak-test")

        assertEquals("https://api.openai.com/v1/models", transport.gets[0].first)
        assertTrue(transport.gets[1].first.startsWith("https://api.anthropic.com/v1/models"))
        assertTrue(transport.gets[1].second.containsKey("anthropic-version"))
        assertEquals("Claude X", claude.single().label)
    }

    @Test
    fun aMissingKeyIsReportedWithoutAnyRequest() = runTest {
        assertFailsWith<ProviderError.MissingConfig> { lister.list(ProviderId.GEMINI, null) }
        assertFailsWith<ProviderError.MissingConfig> { lister.list(ProviderId.GROQ, "  ") }
        assertTrue(transport.gets.isEmpty())
    }

    @Test
    fun aRejectedKeyIsAnAuthenticationError() = runTest {
        transport.reply(401, """{"error":"invalid key"}""")
        val error = assertFailsWith<ProviderError.Http> { lister.list(ProviderId.GROQ, "bad") }
        assertEquals(401, error.code)
    }

    @Test
    fun anUnexpectedShapeIsAnInvalidResponseNotACrash() = runTest {
        transport.reply(200, """{"unexpected":true}""")
        assertFailsWith<ProviderError.InvalidResponse> { lister.list(ProviderId.GROQ, "k") }
        transport.reply(200, """{"models":"nope"}""")
        assertFailsWith<ProviderError.InvalidResponse> { lister.list(ProviderId.GEMINI, "k") }
    }

    // ---- the shared GET helper ------------------------------------------------------------------------------

    @Test
    fun getJsonRefusesPlainHttpAndNeverContactsTheServer() = runTest {
        val http = JsonHttp(transport)
        assertFailsWith<ProviderError.MissingConfig> { http.getJson("http://example.com/models", emptyMap()) }
        assertTrue(transport.gets.isEmpty())
    }

    @Test
    fun getJsonRejectsABodyThatIsNotAJsonObject() = runTest {
        val http = JsonHttp(transport)
        transport.reply(200, "[1,2,3]")
        assertFailsWith<ProviderError.InvalidResponse> { http.getJson("https://example.com/models", emptyMap()) }
        transport.reply(200, "<html>")
        assertFailsWith<ProviderError.InvalidResponse> { http.getJson("https://example.com/models", emptyMap()) }
    }

    @Test
    fun getJsonMapsRateLimitsAndKeepsTheKeyOutOfErrors() = runTest {
        val http = JsonHttp(transport, com.jarvis.ai.core.security.SecretRedactor { listOf("SECRETKEY99") })
        transport.reply(429, "slow down", retryAfter = "12")
        val limited = assertFailsWith<ProviderError.RateLimited> { http.getJson("https://example.com/models", emptyMap()) }
        assertEquals(12_000L, limited.retryAfterMs)
        transport.reply(500, "boom SECRETKEY99 boom")
        val error = assertFailsWith<ProviderError.Http> { http.getJson("https://example.com/models", emptyMap()) }
        assertFalse(error.message.orEmpty().contains("SECRETKEY99"))
    }

    // ---- defaults ------------------------------------------------------------------------------------------

    @Test
    fun theRetiredDefaultsAreRecognisedAndTheNewOnesAreNot() {
        assertTrue(ProviderDefaults.isRetiredDefault(ProviderId.GEMINI, "gemini-2.5-flash"))
        assertTrue(ProviderDefaults.isRetiredDefault(ProviderId.GEMINI, " gemini-2.5-flash "))
        assertFalse(ProviderDefaults.isRetiredDefault(ProviderId.GEMINI, ProviderDefaults.GEMINI_MODEL))
        assertFalse(ProviderDefaults.isRetiredDefault(ProviderId.GEMINI, "gemini-my-own-choice"))
        assertFalse(ProviderDefaults.isRetiredDefault(ProviderId.OPENAI, "anything"))
        assertTrue(ProviderDefaults.isRetiredDefault(ProviderId.GROQ, "llama-3.3-70b-versatile"))
        assertFalse(ProviderDefaults.isRetiredDefault(ProviderId.GROQ, ProviderDefaults.GROQ_MODELS))
        assertTrue(ProviderDefaults.isRetiredDefault(ProviderId.OPENROUTER, "meta-llama/llama-3.3-70b-instruct:free,nvidia/nemotron-3-super-120b-a12b:free,google/gemma-4-31b-it:free,qwen/qwen3-next-80b-a3b-instruct:free"))
    }

    @Test
    fun everyProviderButOpenAiHasANonBlankDefault() {
        for (id in ProviderId.entries) {
            if (id == ProviderId.OPENAI) assertEquals("", ProviderDefaults.modelFor(id)) else assertTrue(ProviderDefaults.modelFor(id).isNotBlank())
        }
    }
}
