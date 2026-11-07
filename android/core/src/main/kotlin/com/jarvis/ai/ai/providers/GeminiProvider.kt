package com.jarvis.ai.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini via the REST generateContent endpoint. The key goes in the
 * `x-goog-api-key` header so it never appears in a URL (and therefore never in logs).
 *
 * This is the text/reasoning path only. The desktop app's Gemini Live voice pipeline is a
 * separate system with no equivalent on Android in Phase 1.
 */
class GeminiProvider(
    private val http: JsonHttp,
    private val settings: () -> ProviderSettings,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmProvider {

    override val id: ProviderId = ProviderId.GEMINI

    override fun isConfigured(): Boolean {
        val s = settings()
        return !s.apiKey.isNullOrBlank() && s.model.isNotBlank()
    }

    override suspend fun complete(request: LlmRequest): String {
        val s = settings()
        val key = s.apiKey?.takeIf { it.isNotBlank() }
            ?: throw ProviderError.MissingConfig("Gemini API key is not set")
        val model = s.model.trim().removePrefix("models/")
        if (model.isEmpty()) throw ProviderError.MissingConfig("Gemini model is not set")

        val body = buildJsonObject {
            if (request.system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", request.system) } }
                }
            }
            putJsonArray("contents") {
                for (message in request.messages) {
                    addJsonObject {
                        put("role", if (message.role == ChatRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") { addJsonObject { put("text", message.content) } }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens)
                put("temperature", request.temperature)
                if (request.jsonMode) put("responseMimeType", "application/json")
            }
        }

        val json = http.postJson(
            url = "$baseUrl/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to key),
            body = body,
        )
        return extractText(json)
    }

    private fun extractText(json: JsonObject): String {
        val text = try {
            val parts: JsonArray? = json["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
            parts?.joinToString(separator = "") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        } catch (e: IllegalArgumentException) {
            throw ProviderError.InvalidResponse("Unexpected Gemini response shape", e)
        }
        if (text.isNullOrBlank()) {
            val blocked = try {
                json["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            } catch (e: IllegalArgumentException) {
                null
            }
            throw ProviderError.InvalidResponse(
                if (blocked != null) "Gemini blocked the request ($blocked)" else "Gemini returned an empty response",
            )
        }
        return text.trim()
    }
}
