package com.jarvis.ai.ai.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Anthropic Claude via the Messages API (`POST /v1/messages`).
 *
 * Contract used here, from Anthropic's API reference (platform.claude.com/docs):
 *  - `Authorization: Bearer <key>` (the older `x-api-key` header is documented as a legacy
 *    fallback), `anthropic-version: 2026-10-21`, `content-type: application/json`.
 *  - Body: `model`, `max_tokens`, `messages`, and `system` as a plain string.
 *  - In API version 2026-10-21 `temperature`, `top_p` and `top_k` were removed: a request
 *    that sends any of them is rejected with HTTP 400 on every model. This adapter
 *    therefore never sends them, whatever [LlmRequest.temperature] says.
 *  - The answer is the concatenation of the `text` blocks in `content`.
 *  - There is no JSON-mode switch in the parts of the reference this was built from, so
 *    [LlmRequest.jsonMode] is honoured by asking for JSON in the system text; the planner
 *    validates the result either way.
 *
 * If Anthropic retires this version the header value in [API_VERSION] is the one line to change.
 */
class AnthropicProvider(
    private val http: JsonHttp,
    private val settings: () -> ProviderSettings,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : LlmProvider {

    override val id: ProviderId = ProviderId.ANTHROPIC

    override fun isConfigured(): Boolean {
        val s = settings()
        return !s.apiKey.isNullOrBlank() && s.model.isNotBlank()
    }

    override suspend fun complete(request: LlmRequest): String {
        val s = settings()
        val key = s.apiKey?.takeIf { it.isNotBlank() }
            ?: throw ProviderError.MissingConfig("Claude API key is not set")
        val model = s.model.trim()
        if (model.isEmpty()) throw ProviderError.MissingConfig("Claude model is not set")

        val system = buildString {
            append(request.system.trim())
            if (request.jsonMode) {
                if (isNotEmpty()) append("\n\n")
                append(JSON_ONLY_INSTRUCTION)
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                for (message in request.messages) {
                    addJsonObject {
                        put("role", message.role.openAiName)
                        put("content", message.content)
                    }
                }
            }
        }

        val json = http.postJson(
            url = endpoint,
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "anthropic-version" to API_VERSION,
            ),
            body = body,
        )
        return extractText(json)
    }

    private fun extractText(json: JsonObject): String {
        val text = try {
            json["content"]?.jsonArray
                ?.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull else null
                }
                ?.joinToString(separator = "")
        } catch (e: IllegalArgumentException) {
            throw ProviderError.InvalidResponse("Unexpected Claude response shape", e)
        }
        if (text.isNullOrBlank()) {
            val stopReason = try {
                json["stop_reason"]?.jsonPrimitive?.contentOrNull
            } catch (e: IllegalArgumentException) {
                null
            }
            throw ProviderError.InvalidResponse(
                if (stopReason != null) "Claude returned no text (stop reason: $stopReason)" else "Claude returned an empty response",
            )
        }
        return text.trim()
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        private const val JSON_ONLY_INSTRUCTION = "Reply with a single JSON object and nothing else."
    }
}
