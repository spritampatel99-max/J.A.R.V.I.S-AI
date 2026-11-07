package com.jarvis.ai.ai.providers

import com.jarvis.ai.core.security.SecretRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * JSON-over-HTTPS layer shared by every provider: enforces https, turns HTTP status codes
 * into typed [ProviderError]s and parses the body. It knows nothing about OkHttp (see
 * [HttpTransport]).
 *
 * Plain http is refused outright. API keys travel in request headers, so there is no
 * address, local or otherwise, that may be used without TLS.
 */
class JsonHttp(
    private val transport: HttpTransport,
    private val redactor: SecretRedactor = SecretRedactor(),
) {
    suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        if (!url.startsWith(HTTPS_PREFIX, ignoreCase = true)) {
            throw ProviderError.MissingConfig("Only https addresses are allowed for AI providers")
        }
        val result = transport.postJson(url, headers, body.toString())
        if (result.code !in 200..299) throw mapHttpError(result)
        return try {
            Json.parseToJsonElement(result.body).jsonObject
        } catch (e: IllegalArgumentException) {
            // Covers malformed JSON (SerializationException) and a JSON value that is not an object.
            throw ProviderError.InvalidResponse("Response was not valid JSON", e)
        }
    }

    private fun mapHttpError(result: HttpResult): ProviderError {
        val excerpt = redactor.redact(result.body.take(MAX_ERROR_BODY_CHARS).replace('\n', ' ')).trim()
        return when (result.code) {
            429 -> ProviderError.RateLimited(
                detail = "Rate limit reached",
                retryAfterMs = result.retryAfter?.trim()?.toLongOrNull()?.let { it * 1_000L },
            )
            401, 403 -> ProviderError.Http(result.code, "Authentication failed - check the API key. $excerpt".trim())
            else -> ProviderError.Http(result.code, excerpt.ifEmpty { "request failed" })
        }
    }

    private companion object {
        const val HTTPS_PREFIX = "https://"
        const val MAX_ERROR_BODY_CHARS = 200
    }
}
