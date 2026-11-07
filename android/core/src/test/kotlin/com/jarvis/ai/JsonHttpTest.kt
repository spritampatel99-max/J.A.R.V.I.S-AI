package com.jarvis.ai

import com.jarvis.ai.ai.providers.AnthropicProvider
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.GeminiProvider
import com.jarvis.ai.ai.providers.HttpResult
import com.jarvis.ai.ai.providers.HttpTransport
import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.OpenAiCompatibleProvider
import com.jarvis.ai.ai.providers.ProviderDefaults
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.ProviderSettings
import com.jarvis.ai.core.security.SecretRedactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Scripted [HttpTransport]: records every call and plays back queued replies. */
class FakeTransport : HttpTransport {
    data class Post(val url: String, val headers: Map<String, String>, val body: String)

    val posts = mutableListOf<Post>()
    private val replies = ArrayDeque<() -> HttpResult>()

    fun reply(code: Int, body: String, retryAfter: String? = null) {
        replies += { HttpResult(code, body, retryAfter) }
    }

    fun fail(error: ProviderError) {
        replies += { throw error }
    }

    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        posts += Post(url, headers, body)
        val next = replies.removeFirstOrNull() ?: error("FakeTransport has no more scripted replies")
        return next()
    }
}

/**
 * Request/response logic of [JsonHttp] and the four provider adapters, tested without any
 * HTTP library. The real transport is covered separately by the MockWebServer test.
 */
class JsonHttpTest {

    private val key = "AIzaSyTESTKEY0123456789abcdefghij"
    private val transport = FakeTransport()
    private val request = LlmRequest(
        system = "You are a test.",
        messages = listOf(
            ChatMessage(ChatRole.USER, "hello"),
            ChatMessage(ChatRole.ASSISTANT, "hi"),
            ChatMessage(ChatRole.USER, "again"),
        ),
        maxTokens = 123,
        temperature = 0.2,
        jsonMode = true,
    )

    private fun http(secrets: List<String> = listOf(key)) = JsonHttp(transport, SecretRedactor { secrets })

    private fun lastBody(): JsonObject = Json.parseToJsonElement(transport.posts.last().body).jsonObject

    private suspend fun post(url: String = "https://api.example.com/x") =
        http().postJson(url, emptyMap(), buildJsonObject { put("a", 1) })

    // ---- JsonHttp ----------------------------------------------------------------------

    @Test
    fun onlyHttpsAddressesAreSent() = runTest {
        transport.reply(200, "{}")

        post("https://api.example.com/x")
        assertEquals(1, transport.posts.size)

        for (url in listOf("http://api.example.com/x", "http://192.168.1.20:11434/x", "ftp://example.com", "example.com/x", "")) {
            val error = assertFailsWith<ProviderError.MissingConfig>("url: $url") { post(url) }
            assertTrue(error.message.orEmpty().contains("https"))
        }
        assertEquals(1, transport.posts.size, "a refused address must never reach the transport")
    }

    @Test
    fun theSchemeCheckIgnoresCase() = runTest {
        transport.reply(200, "{}")
        post("HTTPS://api.example.com/x")
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun aBodyThatIsNotAJsonObjectIsAnInvalidResponse() = runTest {
        for (body in listOf("<html>captive portal</html>", "[1,2,3]", "")) {
            transport.reply(200, body)
            assertFailsWith<ProviderError.InvalidResponse>("body: $body") { post() }
        }
    }

    @Test
    fun statusCodesMapToTypedErrors() = runTest {
        transport.reply(429, "{}", retryAfter = " 7 ")
        val limited = assertFailsWith<ProviderError.RateLimited> { post() }
        assertEquals(7_000L, limited.retryAfterMs)
        assertFalse(limited.retryable)

        transport.reply(429, "{}", retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT")
        assertNull(assertFailsWith<ProviderError.RateLimited> { post() }.retryAfterMs, "an HTTP-date Retry-After is ignored, not misread")

        transport.reply(503, "overloaded")
        val busy = assertFailsWith<ProviderError.Http> { post() }
        assertEquals(503, busy.code)
        assertTrue(busy.retryable)

        transport.reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertTrue(assertFailsWith<ProviderError.Http> { post() }.retryable, "Anthropic's 529 overloaded is worth retrying")

        transport.reply(400, "bad request")
        assertFalse(assertFailsWith<ProviderError.Http> { post() }.retryable)
    }

    @Test
    fun anErrorBodyThatEchoesTheKeyIsRedacted() = runTest {
        transport.reply(401, """{"error":"API key $key is invalid"}""")

        val error = assertFailsWith<ProviderError.Http> { post() }

        assertEquals(401, error.code)
        assertFalse(error.message.orEmpty().contains(key))
        assertTrue(error.message.orEmpty().contains("check the API key"))
    }

    @Test
    fun transportFailuresPassThroughUnchanged() = runTest {
        transport.fail(ProviderError.Timeout("Request timed out"))
        assertFailsWith<ProviderError.Timeout> { post() }
    }

    // ---- Gemini ------------------------------------------------------------------------

    private fun gemini(model: String = "gemini-2.5-flash", apiKey: String = key) = GeminiProvider(
        http = http(),
        settings = { ProviderSettings(apiKey = apiKey, model = model) },
        baseUrl = "https://gemini.test/v1beta",
    )

    @Test
    fun geminiSendsTheKeyInAHeaderNeverInTheUrl() = runTest {
        transport.reply(200, """{"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"there"}]}}]}""")

        val text = gemini(model = "models/gemini-2.5-flash").complete(request)

        assertEquals("Hello there", text)
        val post = transport.posts.single()
        assertEquals("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent", post.url)
        assertFalse(post.url.contains(key))
        assertEquals(key, post.headers["x-goog-api-key"])
    }

    @Test
    fun geminiRequestBodyUsesNativeRolesAndSystemSlot() = runTest {
        transport.reply(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")

        gemini().complete(request)

        val body = lastBody()
        val system = body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray.first().jsonObject
        assertEquals("You are a test.", system.getValue("text").jsonPrimitive.content)
        val roles = body.getValue("contents").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("user", "model", "user"), roles)
        val config = body.getValue("generationConfig").jsonObject
        assertEquals("123", config.getValue("maxOutputTokens").jsonPrimitive.content)
        assertEquals("application/json", config.getValue("responseMimeType").jsonPrimitive.content)
    }

    @Test
    fun geminiBlockedAndEmptyAnswersAreInvalidResponses() = runTest {
        transport.reply(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        val blocked = assertFailsWith<ProviderError.InvalidResponse> { gemini().complete(request) }
        assertTrue(blocked.message.orEmpty().contains("SAFETY"))

        transport.reply(200, """{"candidates":[]}""")
        assertFailsWith<ProviderError.InvalidResponse> { gemini().complete(request) }

        transport.reply(200, """{"candidates":[{"content":"not an object"}]}""")
        assertFailsWith<ProviderError.InvalidResponse> { gemini().complete(request) }
    }

    @Test
    fun geminiWithoutAKeyIsNotConfiguredAndNeverCallsTheNetwork() = runTest {
        val provider = gemini(apiKey = "")
        assertFalse(provider.isConfigured())
        assertFailsWith<ProviderError.MissingConfig> { provider.complete(request) }
        assertTrue(transport.posts.isEmpty())
    }

    // ---- Groq / OpenRouter / OpenAI (chat-completions format) ---------------------------

    private val orKey = "sk-or-secret-key-123456"

    private fun chatCompletions(
        models: String,
        id: ProviderId = ProviderId.OPENROUTER,
        maxTokensField: String = "max_tokens",
        sendTemperature: Boolean = true,
    ) = OpenAiCompatibleProvider(
        id = id,
        http = http(listOf(orKey)),
        endpoint = "https://router.test/api/v1/chat/completions",
        settings = { ProviderSettings(apiKey = orKey, model = models) },
        extraHeaders = mapOf("X-Title" to "JARVIS"),
        maxTokensField = maxTokensField,
        sendTemperature = sendTemperature,
    )

    private fun chat(text: String) = """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""

    @Test
    fun chatCompletionsUsesBearerAuthAndSystemMessage() = runTest {
        transport.reply(200, chat("pong"))

        assertEquals("pong", chatCompletions("model-a").complete(request))

        val post = transport.posts.single()
        assertEquals("Bearer $orKey", post.headers["Authorization"])
        assertEquals("JARVIS", post.headers["X-Title"])
        val body = lastBody()
        val roles = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("123", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("0.2", body.getValue("temperature").jsonPrimitive.content)
    }

    @Test
    fun openAiIsAskedForMaxCompletionTokensAndNoTemperature() = runTest {
        transport.reply(200, chat("pong"))

        chatCompletions("some-model", ProviderId.OPENAI, maxTokensField = "max_completion_tokens", sendTemperature = false)
            .complete(request)

        val body = lastBody()
        assertEquals("123", body.getValue("max_completion_tokens").jsonPrimitive.content)
        assertFalse("max_tokens" in body, "OpenAI documents max_tokens as deprecated")
        assertFalse("temperature" in body, "some OpenAI models reject a non-default temperature")
    }

    @Test
    fun theModelPoolFallsThroughOnModelLevelFailures() = runTest {
        transport.reply(404, "model not found")
        transport.reply(200, chat("from second model"))

        assertEquals("from second model", chatCompletions("model-a, model-b").complete(request))

        val models = transport.posts.map {
            Json.parseToJsonElement(it.body).jsonObject.getValue("model").jsonPrimitive.content
        }
        assertEquals(listOf("model-a", "model-b"), models)
    }

    @Test
    fun aBadKeyStopsThePoolImmediately() = runTest {
        transport.reply(401, "no")

        val error = assertFailsWith<ProviderError.Http> { chatCompletions("model-a,model-b,model-c").complete(request) }

        assertEquals(401, error.code)
        assertEquals(1, transport.posts.size, "the other models would fail the same way")
    }

    @Test
    fun aConnectivityFailureStopsThePoolImmediately() = runTest {
        transport.fail(ProviderError.Network("connection refused"))

        assertFailsWith<ProviderError.Network> { chatCompletions("model-a,model-b").complete(request) }

        assertEquals(1, transport.posts.size)
    }

    @Test
    fun whenEveryModelFailsTheLastErrorIsReported() = runTest {
        transport.reply(404, "first")
        transport.reply(500, "second")

        val error = assertFailsWith<ProviderError.Http> { chatCompletions("model-a,model-b").complete(request) }

        assertEquals(500, error.code)
    }

    @Test
    fun anErrorObjectInsideA200IsAnInvalidResponse() = runTest {
        transport.reply(200, """{"error":{"message":"quota exceeded"}}""")
        val error = assertFailsWith<ProviderError.InvalidResponse> { chatCompletions("model-a").complete(request) }
        assertTrue(error.message.orEmpty().contains("quota exceeded"))
    }

    @Test
    fun anEmptyPoolIsAConfigurationError() = runTest {
        val provider = OpenAiCompatibleProvider(
            ProviderId.GROQ,
            http(),
            "https://groq.test/x",
            { ProviderSettings(apiKey = "k-1234567890", model = " , ") },
        )
        assertFailsWith<ProviderError.MissingConfig> { provider.complete(request) }
        assertTrue(transport.posts.isEmpty())
    }

    // ---- Anthropic Claude --------------------------------------------------------------

    private val claudeKey = "sk-ant-secret-key-0123456789"

    private fun claude(model: String = "claude-haiku-5-5", apiKey: String? = claudeKey) = AnthropicProvider(
        http = http(listOf(claudeKey)),
        settings = { ProviderSettings(apiKey = apiKey, model = model) },
        endpoint = "https://claude.test/v1/messages",
    )

    private fun claudeReply(vararg blocks: String) =
        """{"id":"msg_1","type":"message","role":"assistant","content":[${blocks.joinToString(",")}],"stop_reason":"end_turn"}"""

    private fun textBlock(text: String) = """{"type":"text","text":"$text"}"""

    @Test
    fun claudeSendsTheDocumentedHeadersAndBody() = runTest {
        transport.reply(200, claudeReply(textBlock("Hello ")))

        assertEquals("Hello", claude().complete(request))

        val post = transport.posts.single()
        assertEquals("https://claude.test/v1/messages", post.url)
        assertEquals("Bearer $claudeKey", post.headers["Authorization"])
        assertEquals(AnthropicProvider.API_VERSION, post.headers["anthropic-version"])
        assertEquals("2026-10-21", AnthropicProvider.API_VERSION)

        val body = lastBody()
        assertEquals("claude-haiku-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals("123", body.getValue("max_tokens").jsonPrimitive.content)
        val roles = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun claudeNeverSendsTheSamplingParametersTheApiNowRejects() = runTest {
        transport.reply(200, claudeReply(textBlock("ok")))

        claude().complete(request.copy(temperature = 0.9))

        val body = lastBody()
        for (field in listOf("temperature", "top_p", "top_k")) {
            assertFalse(field in body, "API version 2026-10-21 returns HTTP 400 when $field is sent")
        }
    }

    @Test
    fun claudeSystemTextIsAPlainStringAndJsonModeIsRequestedInIt() = runTest {
        transport.reply(200, claudeReply(textBlock("{}")))
        claude().complete(request)
        val system = lastBody().getValue("system").jsonPrimitive.content
        assertTrue(system.startsWith("You are a test."))
        assertTrue(system.contains("single JSON object"))

        transport.reply(200, claudeReply(textBlock("hi")))
        claude().complete(request.copy(jsonMode = false))
        assertEquals("You are a test.", lastBody().getValue("system").jsonPrimitive.content)

        transport.reply(200, claudeReply(textBlock("hi")))
        claude().complete(request.copy(system = "", jsonMode = false))
        assertFalse("system" in lastBody(), "an empty system prompt is omitted, not sent blank")
    }

    @Test
    fun claudeAnswerIsTheTextBlocksJoinedAndOtherBlocksAreIgnored() = runTest {
        transport.reply(
            200,
            claudeReply("""{"type":"thinking","thinking":"hmm"}""", textBlock("Part one. "), """{"type":"tool_use","id":"t","name":"n","input":{}}""", textBlock("Part two.")),
        )

        assertEquals("Part one. Part two.", claude().complete(request))
    }

    @Test
    fun claudeWithoutTextReportsTheStopReason() = runTest {
        transport.reply(200, """{"type":"message","role":"assistant","content":[],"stop_reason":"refusal"}""")
        val refused = assertFailsWith<ProviderError.InvalidResponse> { claude().complete(request) }
        assertTrue(refused.message.orEmpty().contains("refusal"))

        transport.reply(200, """{"type":"message","content":"oops"}""")
        assertFailsWith<ProviderError.InvalidResponse> { claude().complete(request) }
    }

    @Test
    fun claudeErrorsMapLikeTheOthersAndNeverEchoTheKey() = runTest {
        transport.reply(401, """{"type":"error","error":{"type":"authentication_error","message":"bad key $claudeKey"},"request_id":"req_1"}""")
        val auth = assertFailsWith<ProviderError.Http> { claude().complete(request) }
        assertEquals(401, auth.code)
        assertFalse(auth.message.orEmpty().contains(claudeKey))

        transport.reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertTrue(assertFailsWith<ProviderError.Http> { claude().complete(request) }.retryable)
    }

    @Test
    fun claudeNeedsAKeyAndAModel() = runTest {
        assertFalse(claude(apiKey = null).isConfigured())
        assertFalse(claude(model = " ").isConfigured())
        assertTrue(claude().isConfigured())
        assertFailsWith<ProviderError.MissingConfig> { claude(apiKey = "").complete(request) }
        assertFailsWith<ProviderError.MissingConfig> { claude(model = "").complete(request) }
        assertTrue(transport.posts.isEmpty())
    }

    // ---- Provider catalogue --------------------------------------------------------------

    @Test
    fun theFiveCloudProvidersAreListedOnceEach() {
        assertEquals(
            setOf(ProviderId.GEMINI, ProviderId.GROQ, ProviderId.OPENROUTER, ProviderId.OPENAI, ProviderId.ANTHROPIC),
            ProviderId.entries.toSet(),
        )
        assertEquals(ProviderId.entries.toList(), ProviderId.DEFAULT_ORDER.sortedBy { ProviderId.entries.indexOf(it) })
        assertEquals(ProviderId.entries.size, ProviderId.DEFAULT_ORDER.distinct().size)
    }

    @Test
    fun openAiHasNoGuessedDefaultModelSoItStaysUnconfiguredUntilChosen() {
        assertEquals("", ProviderDefaults.modelFor(ProviderId.OPENAI))
        val provider = chatCompletions(ProviderDefaults.modelFor(ProviderId.OPENAI), ProviderId.OPENAI)
        assertFalse(provider.isConfigured())
        for (id in ProviderId.entries - ProviderId.OPENAI) {
            assertTrue(ProviderDefaults.modelFor(id).isNotBlank(), "$id should have a pre-filled model")
        }
    }

    @Test
    fun settingsNeverPrintTheKey() {
        val text = ProviderSettings(apiKey = key, model = "m").toString()
        assertFalse(text.contains(key))
    }
}
