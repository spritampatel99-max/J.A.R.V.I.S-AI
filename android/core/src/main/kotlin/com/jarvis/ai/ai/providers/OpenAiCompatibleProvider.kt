package com.jarvis.ai.ai.providers

import kotlinx.serialization.json.JsonNull
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
 * Groq, OpenRouter and OpenAI all speak the OpenAI chat-completions format, so one class
 * serves them. The configured model may be a comma-separated pool (this is how the desktop
 * OpenRouter client works): each model is tried in order until one answers.
 *
 * Two request details differ by provider and are therefore parameters:
 *  - [maxTokensField]: OpenAI's API reference marks `max_tokens` as deprecated and not
 *    compatible with its reasoning models, so OpenAI is configured with
 *    `max_completion_tokens`. Groq and OpenRouter keep `max_tokens`.
 *  - [sendTemperature]: some OpenAI models only accept their default temperature, so the
 *    OpenAI instance does not send one.
 *
 * A bad key (401/403) or a connectivity problem stops the pool immediately, because the
 * remaining models would fail the same way.
 */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    private val http: JsonHttp,
    private val endpoint: String,
    private val settings: () -> ProviderSettings,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val supportsJsonMode: Boolean = true,
    private val maxTokensField: String = "max_tokens",
    private val sendTemperature: Boolean = true,
) : LlmProvider {

    override fun isConfigured(): Boolean {
        val s = settings()
        return !s.apiKey.isNullOrBlank() && s.model.isNotBlank()
    }

    override suspend fun complete(request: LlmRequest): String {
        val s = settings()
        val key = s.apiKey?.takeIf { it.isNotBlank() }
            ?: throw ProviderError.MissingConfig("${id.displayName} API key is not set")
        val models = s.model.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (models.isEmpty()) throw ProviderError.MissingConfig("${id.displayName} model is not set")

        var lastError: ProviderError? = null
        for (model in models) {
            try {
                return completeWith(model, key, request)
            } catch (e: ProviderError) {
                val authProblem = e is ProviderError.Http && (e.code == 401 || e.code == 403)
                val connectivityProblem = e is ProviderError.Network || e is ProviderError.Timeout
                if (authProblem || connectivityProblem) throw e
                lastError = e
            }
        }
        throw lastError ?: ProviderError.InvalidResponse("${id.displayName} returned no response")
    }

    private suspend fun completeWith(model: String, key: String, request: LlmRequest): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                if (request.system.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    }
                }
                for (message in request.messages) {
                    addJsonObject {
                        put("role", message.role.openAiName)
                        put("content", message.content)
                    }
                }
            }
            put(maxTokensField, request.maxTokens)
            if (sendTemperature) put("temperature", request.temperature)
            if (request.jsonMode && supportsJsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }
        val headers = LinkedHashMap<String, String>()
        headers.putAll(extraHeaders)
        headers["Authorization"] = "Bearer $key"

        val json = http.postJson(endpoint, headers, body)
        return extractText(json)
    }

    private fun extractText(json: JsonObject): String {
        val error = json["error"]
        if (error != null && error !is JsonNull) {
            val message = try {
                error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            } catch (e: IllegalArgumentException) {
                null
            }
            throw ProviderError.InvalidResponse("${id.displayName} reported an error: ${message ?: "unknown"}")
        }
        val content = try {
            json["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
        } catch (e: IllegalArgumentException) {
            throw ProviderError.InvalidResponse("Unexpected ${id.displayName} response shape", e)
        }
        if (content.isNullOrBlank()) {
            throw ProviderError.InvalidResponse("${id.displayName} returned an empty response")
        }
        return content.trim()
    }
}
