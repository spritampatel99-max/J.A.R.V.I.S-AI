package com.jarvis.ai

import com.jarvis.ai.ai.providers.AnthropicProvider
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.GeminiProvider
import com.jarvis.ai.ai.providers.ImageAttachment
import com.jarvis.ai.ai.providers.JsonHttp
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.OpenAiCompatibleProvider
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.ProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ImageAttachmentTest {
    private val image = ImageAttachment("image/png", "iVBORw0KGgo=")
    private val request = LlmRequest("sys", listOf(ChatMessage(ChatRole.USER, "what is this?", listOf(image))))
    private val key = "k-1234567890"

    private fun body(t: FakeTransport) = Json.parseToJsonElement(t.posts.single().body).jsonObject

    @Test
    fun onlyRealPhotoTypesOfSensibleSizeAreAccepted() {
        assertFailsWith<IllegalArgumentException> { ImageAttachment("text/html", "QUJD") }
        assertFailsWith<IllegalArgumentException> { ImageAttachment("image/svg+xml", "QUJD") }
        assertFailsWith<IllegalArgumentException> { ImageAttachment("image/jpeg", "") }
        assertFailsWith<IllegalArgumentException> { ImageAttachment("image/jpeg", "a".repeat(ImageAttachment.MAX_BASE64_CHARS + 1)) }
        assertFailsWith<IllegalArgumentException> { ImageAttachment("image/jpeg", "abc\",\"x\":\"") }
        assertFalse("iVBOR" in image.toString(), "the photo's data is never printed")
    }

    @Test
    fun onlyThePersonsOwnMessageCanCarryPhotosAndOnlyAFewOfThem() {
        assertFailsWith<IllegalArgumentException> { ChatMessage(ChatRole.ASSISTANT, "x", listOf(image)) }
        assertFailsWith<IllegalArgumentException> { ChatMessage(ChatRole.USER, "x", List(ChatMessage.MAX_IMAGES + 1) { image }) }
        assertEquals(emptyList(), ChatMessage(ChatRole.USER, "plain").images)
    }

    @Test
    fun anthropicGetsAnImageBlockBeforeTheText() = runTest {
        val t = FakeTransport().apply { reply(200, """{"content":[{"type":"text","text":"A cat."}],"stop_reason":"end_turn"}""") }
        val text = AnthropicProvider(JsonHttp(t), { ProviderSettings(apiKey = key, model = "m") }, "https://a.test/v1/messages").complete(request)
        assertEquals("A cat.", text)
        val content = body(t)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[0].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiGetsInlineData() = runTest {
        val t = FakeTransport().apply { reply(200, """{"candidates":[{"content":{"parts":[{"text":"A cat."}]}}]}""") }
        GeminiProvider(JsonHttp(t), { ProviderSettings(apiKey = key, model = "gemini-2.5-flash") }, "https://g.test/v1beta").complete(request)
        val parts = body(t)["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/png", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("what is this?", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiStyleProvidersGetADataUrl() = runTest {
        val t = FakeTransport().apply { reply(200, """{"choices":[{"message":{"role":"assistant","content":"A cat."}}]}""") }
        OpenAiCompatibleProvider(ProviderId.GROQ, JsonHttp(t), "https://o.test/v1/chat/completions", { ProviderSettings(apiKey = key, model = "m") }).complete(request)
        val content = body(t)["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
    }

    @Test
    fun aMessageWithoutPhotosIsSentExactlyAsBefore() = runTest {
        val t = FakeTransport().apply { reply(200, """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""") }
        OpenAiCompatibleProvider(ProviderId.GROQ, JsonHttp(t), "https://o.test/v1/chat/completions", { ProviderSettings(apiKey = key, model = "m") })
            .complete(LlmRequest("sys", listOf(ChatMessage(ChatRole.USER, "hello"))))
        assertEquals("hello", body(t)["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content)
    }
}
