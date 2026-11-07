package com.jarvis.ai.ai.providers

/** The optional cloud providers. Local (on-device) models are not providers in this sense. */
enum class ProviderId(val displayName: String) {
    GEMINI("Gemini"),
    GROQ("Groq"),
    OPENROUTER("OpenRouter"),
    OPENAI("OpenAI"),
    ANTHROPIC("Claude"),
    ;

    companion object {
        /** Starting order; the user can reorder it and any provider may be left unconfigured. */
        val DEFAULT_ORDER: List<ProviderId> = listOf(GEMINI, GROQ, OPENROUTER, OPENAI, ANTHROPIC)
    }
}

/**
 * Pre-filled model names. The Gemini and Groq values are carried over from the desktop
 * project (llm_client.py / or_client.py) and the Claude value appears in Anthropic's API
 * reference. They are only suggestions: model names change, so the user can replace them.
 * OpenAI has no default on purpose: no current model name has been verified, so the
 * provider stays unconfigured until the user picks one.
 */
object ProviderDefaults {
    const val GEMINI_MODEL = "gemini-2.5-flash"
    const val GROQ_MODEL = "llama-3.3-70b-versatile"
    const val ANTHROPIC_MODEL = "claude-haiku-5-5"

    /** Comma-separated pool, tried in order, like the desktop client's free-model pool. */
    const val OPENROUTER_MODELS =
        "meta-llama/llama-3.3-70b-instruct:free," +
            "nvidia/nemotron-3-super-120b-a12b:free," +
            "google/gemma-4-31b-it:free," +
            "qwen/qwen3-next-80b-a3b-instruct:free"

    fun modelFor(id: ProviderId): String = when (id) {
        ProviderId.GEMINI -> GEMINI_MODEL
        ProviderId.GROQ -> GROQ_MODEL
        ProviderId.OPENROUTER -> OPENROUTER_MODELS
        ProviderId.OPENAI -> ""
        ProviderId.ANTHROPIC -> ANTHROPIC_MODEL
    }
}

enum class ChatRole(val openAiName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(val role: ChatRole, val content: String)

data class LlmRequest(
    /** System instruction; passed in the provider's native "system" slot. */
    val system: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val jsonMode: Boolean = false,
)

/** Per-provider configuration. [toString] never prints the key. */
data class ProviderSettings(
    val apiKey: String? = null,
    val model: String = "",
) {
    override fun toString(): String =
        "ProviderSettings(model=$model, apiKey=${if (apiKey.isNullOrBlank()) "unset" else "***"})"
}

/** Typed provider failures. Messages are safe to show and log (keys are redacted upstream). */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Missing or invalid configuration (no key, bad URL). Not retryable. */
    class MissingConfig(val detail: String) : ProviderError(detail)

    class Network(val detail: String, cause: Throwable? = null) : ProviderError(detail, cause)

    class Timeout(val detail: String, cause: Throwable? = null) : ProviderError(detail, cause)

    class RateLimited(val detail: String, val retryAfterMs: Long? = null) : ProviderError(detail)

    class Http(val code: Int, val detail: String) : ProviderError("HTTP $code: $detail")

    class InvalidResponse(val detail: String, cause: Throwable? = null) : ProviderError(detail, cause)

    /** The provider cannot be used right now for a reason that retrying will not fix. */
    class Unavailable(val detail: String) : ProviderError(detail)

    /** Worth trying the same provider again after a short wait. */
    val retryable: Boolean
        get() = when (this) {
            is Network, is Timeout -> true
            is Http -> code >= 500 || code == 408
            is MissingConfig, is RateLimited, is InvalidResponse, is Unavailable -> false
        }
}

interface LlmProvider {
    val id: ProviderId

    /** True when the provider has what it needs (key, URL). Never touches the network. */
    fun isConfigured(): Boolean

    /** @throws ProviderError for every provider-side failure. */
    suspend fun complete(request: LlmRequest): String
}
