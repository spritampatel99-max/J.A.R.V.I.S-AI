package com.jarvis.ai.ai.providers

/** The outcome of one completed HTTP exchange. Any status code is a result, including 4xx and 5xx. */
data class HttpResult(
    val code: Int,
    val body: String,
    /** Raw `Retry-After` header, if the server sent one. */
    val retryAfter: String? = null,
)

/**
 * The only place that touches an HTTP library. Providers and [JsonHttp] work purely in
 * terms of this interface, so all request/response logic is unit-tested with a fake and
 * the OkHttp-specific code stays small enough to review at a glance.
 */
interface HttpTransport {
    /**
     * @throws ProviderError.Timeout when the exchange timed out
     * @throws ProviderError.Network for any other failure to complete the exchange
     * @throws ProviderError.MissingConfig when [url] cannot be parsed
     */
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult
}
