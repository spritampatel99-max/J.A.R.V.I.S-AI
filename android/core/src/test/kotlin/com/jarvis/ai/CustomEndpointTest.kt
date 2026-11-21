package com.jarvis.ai

import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.CustomEndpoint
import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ModelLister
import com.jarvis.ai.ai.providers.OpenAiCompatibleProvider
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.ProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The "FreeLLM / own endpoint" provider: the typed address is checked, and the provider behaves like the other chat-completions ones. */
class CustomEndpointTest {

    private fun base(raw: String?) = (CustomEndpoint.parse(raw) as? CustomEndpoint.Parsed.Ok)?.base

    // ---- the address check -------------------------------------------------------------

    @Test
    fun aBaseAddressAndAFullChatAddressGiveTheSameResult() {
        assertEquals("https://gw.example.com/v1", base("https://gw.example.com/v1"))
        assertEquals("https://gw.example.com/v1", base("https://gw.example.com/v1/"))
        assertEquals("https://gw.example.com/v1", base("https://gw.example.com/v1/chat/completions"))
        assertEquals("https://gw.example.com/v1", base("  https://gw.example.com/v1/chat/completions/  "))
        assertEquals("https://gw.example.com", base("https://gw.example.com"))
        assertEquals("https://gw.example.com:8443/api", base("https://gw.example.com:8443/api/"))
    }

    @Test
    fun theChatAndModelAddressesAreBuiltFromTheBase() {
        assertEquals("https://gw.example.com/v1/chat/completions", CustomEndpoint.chatUrl("https://gw.example.com/v1"))
        assertEquals("https://gw.example.com/v1/chat/completions", CustomEndpoint.chatUrl("https://gw.example.com/v1/chat/completions"))
        assertEquals("https://gw.example.com/v1/models", CustomEndpoint.modelsUrl("https://gw.example.com/v1/chat/completions"))
    }

    @Test
    fun theSchemeIsAcceptedInAnyCaseButWrittenInLowerCase() {
        assertEquals("https://gw.example.com/v1", base("HTTPS://gw.example.com/v1"))
    }

    @Test
    fun anythingButHttpsIsRefused() {
        for (raw in listOf("http://gw.example.com/v1", "ftp://gw.example.com", "gw.example.com/v1", "//gw.example.com/v1", "file:///etc/passwd", "javascript:alert(1)")) {
            assertNull(CustomEndpoint.chatUrl(raw), raw)
        }
    }

    @Test
    fun passwordsTokensAndFragmentsInTheAddressAreRefused() {
        for (raw in listOf(
            "https://user:pass@gw.example.com/v1",
            "https://user@gw.example.com/v1",
            "https://gw.example.com/v1?key=abc",
            "https://gw.example.com/v1?",
            "https://gw.example.com/v1#frag",
        )) {
            assertNull(CustomEndpoint.chatUrl(raw), raw)
        }
    }

    @Test
    fun emptyBlankSpacedAndHostlessAddressesAreRefused() {
        for (raw in listOf(null, "", "   ", "https://", "https:///v1", "https://gw example.com", "https://gw.example.com/v 1", "https://gw.example.com/v1\nhttps://evil.example", "https://gw.example.com:99999/v1", "https://gw.example.com:0/v1")) {
            assertNull(CustomEndpoint.chatUrl(raw), raw.toString())
        }
    }

    @Test
    fun theReasonShownToThePersonNeverRepeatsTheAddress() {
        val bad = CustomEndpoint.parse("https://user:SECRETPASS@gw.example.com/v1?token=SECRETTOKEN") as CustomEndpoint.Parsed.Bad
        assertFalse(bad.reason.contains("SECRET"))
    }

    // ---- the provider -------------------------------------------------------------------

    private val transport = FakeTransport()
    private val http = JsonHttp(transport)
    private val request = LlmRequest(system = "sys", messages = listOf(ChatMessage(ChatRole.USER, "hi")), maxTokens = 50, temperature = 0.1)
    private val chat = """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""

    private fun provider(settings: ProviderSettings) = OpenAiCompatibleProvider(
        id = ProviderId.FREELLM,
        http = http,
        endpoint = "",
        settings = { settings },
        endpointSource = { CustomEndpoint.chatUrl(settings.endpoint) },
        requireKey = false,
        supportsJsonMode = false, // same as AppContainer: an unknown gateway may reject the JSON-mode field
    )

    @Test
    fun itPostsToTheTypedAddressWithoutAKeyWhenNoneIsSet() = runTest {
        transport.reply(200, chat)
        val p = provider(ProviderSettings(model = "m-1", endpoint = "https://gw.example.com/v1"))

        assertTrue(p.isConfigured())
        assertEquals("pong", p.complete(request))

        val post = transport.posts.single()
        assertEquals("https://gw.example.com/v1/chat/completions", post.url)
        assertFalse(post.headers.containsKey("Authorization"), "no key was saved, so no Authorization header may be sent")
    }

    @Test
    fun theOwnEndpointNeverReceivesTheJsonModeField() = runTest {
        transport.reply(200, chat)
        provider(ProviderSettings(model = "m-1", endpoint = "https://gw.example.com/v1")).complete(request.copy(jsonMode = true))
        assertFalse(transport.posts.single().body.contains("response_format"), "an unknown gateway may reject this field, so it is not sent")
    }

    @Test
    fun anOrdinaryProviderStillGetsTheJsonModeField() = runTest {
        transport.reply(200, chat)
        OpenAiCompatibleProvider(ProviderId.GROQ, http, "https://groq.test/x", { ProviderSettings(apiKey = "k-123456789", model = "m") })
            .complete(request.copy(jsonMode = true))
        assertTrue(transport.posts.single().body.contains("response_format"))
    }

    @Test
    fun aSavedKeyIsStillSentAsABearerToken() = runTest {
        transport.reply(200, chat)
        provider(ProviderSettings(apiKey = "gw-key-123456", model = "m-1", endpoint = "https://gw.example.com/v1")).complete(request)
        assertEquals("Bearer gw-key-123456", transport.posts.single().headers["Authorization"])
    }

    @Test
    fun itIsNotConfiguredWithoutAUsableAddressOrAModel() = runTest {
        assertFalse(provider(ProviderSettings(model = "m-1")).isConfigured())
        assertFalse(provider(ProviderSettings(model = "m-1", endpoint = "http://gw.example.com/v1")).isConfigured())
        assertFalse(provider(ProviderSettings(model = "", endpoint = "https://gw.example.com/v1")).isConfigured())
        assertFailsWith<ProviderError.MissingConfig> { provider(ProviderSettings(model = "m-1", endpoint = "http://gw.example.com/v1")).complete(request) }
        assertFailsWith<ProviderError.MissingConfig> { provider(ProviderSettings(model = "", endpoint = "https://gw.example.com/v1")).complete(request) }
        assertTrue(transport.posts.isEmpty(), "nothing may be sent when the address or model is not usable")
    }

    @Test
    fun theOrdinaryProvidersStillNeedAKey() = runTest {
        val groq = OpenAiCompatibleProvider(ProviderId.GROQ, http, "https://groq.test/x", { ProviderSettings(model = "m") })
        assertFalse(groq.isConfigured())
        assertFailsWith<ProviderError.MissingConfig> { groq.complete(request) }
        assertTrue(transport.posts.isEmpty())
    }

    @Test
    fun theSettingsTextNeverShowsTheAddressOrTheKey() {
        val text = ProviderSettings(apiKey = "gw-key-123456", model = "m", endpoint = "https://secret-host.example.com/tok123").toString()
        assertFalse(text.contains("secret-host"))
        assertFalse(text.contains("tok123"))
        assertFalse(text.contains("gw-key-123456"))
    }

    // ---- listing models -----------------------------------------------------------------

    @Test
    fun theModelListComesFromTheTypedAddressAndNeedsNoKey() = runTest {
        transport.reply(200, """{"data":[{"id":"free-model-a"},{"id":"embed-x"}]}""")
        val models = ModelLister(http).list(ProviderId.FREELLM, null, "https://gw.example.com/v1/chat/completions")
        assertEquals(listOf("free-model-a"), models.map { it.id })
        val (url, headers) = transport.gets.single()
        assertEquals("https://gw.example.com/v1/models", url)
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun theModelListSendsASavedKeyOnlyInTheHeader() = runTest {
        transport.reply(200, """{"data":[{"id":"free-model-a"}]}""")
        ModelLister(http).list(ProviderId.FREELLM, "gw-key-123456", "https://gw.example.com/v1")
        val (url, headers) = transport.gets.single()
        assertEquals("Bearer gw-key-123456", headers["Authorization"])
        assertFalse(url.contains("gw-key"))
    }

    @Test
    fun anUnusableAddressStopsTheListBeforeAnyRequest() = runTest {
        for (raw in listOf(null, "", "http://gw.example.com/v1", "https://u:p@gw.example.com/v1")) {
            assertFailsWith<ProviderError.MissingConfig> { ModelLister(http).list(ProviderId.FREELLM, null, raw) }
        }
        assertTrue(transport.gets.isEmpty())
    }
}
