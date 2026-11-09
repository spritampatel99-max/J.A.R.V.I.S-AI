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
 * Pre-filled model names. They are only suggestions, and they go stale: a first real run
 * on 2026-11-08 showed `gemini-2.5-flash` refused for new Gemini users, `:free` OpenRouter
 * names withdrawn, and one Groq key without access to `llama-3.3-70b-versatile`. The Cloud
 * screen can therefore load the real list from the user's own key, and test the chosen model.
 *
 *  - Gemini: Google's model page (checked 2026-11-08) recommends `gemini-3.8-flash` for new
 *    projects, and the API's own 404 for the old name pointed to the same model.
 *  - Groq: a short pool tried in order, taken from Groq's production-model list (checked
 *    2026-11-08), so one model being unavailable to a key does not stop the provider.
 *  - OpenRouter: `openrouter/free`, OpenRouter's router that picks a currently available free model.
 *  - Claude: from Anthropic's API reference.
 *  - OpenAI has no default on purpose: no current model name was verified, so that provider
 *    stays unconfigured until the user picks one.
 */
object ProviderDefaults {
    const val GEMINI_MODEL = "gemini-3.8-flash"
    const val GROQ_MODELS = "llama-3.3-70b-versatile,openai/gpt-oss-20b,llama-3.1-8b-instant"
    const val OPENROUTER_MODELS = "openrouter/free"
    const val ANTHROPIC_MODEL = "claude-haiku-5-5"

    fun modelFor(id: ProviderId): String = when (id) {
        ProviderId.GEMINI -> GEMINI_MODEL
        ProviderId.GROQ -> GROQ_MODELS
        ProviderId.OPENROUTER -> OPENROUTER_MODELS
        ProviderId.OPENAI -> ""
        ProviderId.ANTHROPIC -> ANTHROPIC_MODEL
    }

    /**
     * Earlier pre-filled values that stopped working. A saved value equal to one of these was
     * almost certainly the untouched default, so it is dropped once (see the app's SettingsStore)
     * and the current default takes over.
     */
    private val retired: Map<ProviderId, Set<String>> = mapOf(
        ProviderId.GEMINI to setOf("gemini-2.5-flash"),
        ProviderId.GROQ to setOf("llama-3.3-70b-versatile"),
        ProviderId.OPENROUTER to setOf(
            "meta-llama/llama-3.3-70b-instruct:free," +
                "nvidia/nemotron-3-super-120b-a12b:free," +
                "google/gemma-4-31b-it:free," +
                "qwen/qwen3-next-80b-a3b-instruct:free",
        ),
    )

    fun isRetiredDefault(id: ProviderId, saved: String): Boolean = saved.trim() in retired[id].orEmpty()
}

enum class ChatRole(val openAiName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(val role: ChatRole, val content: String)

/** How demanding a request is. The router uses it to decide which on-device model to try first. */
enum class TaskWeight {
    /** Short answers and chat. */
    LIGHT,

    /** Strict structured output, such as the planner's JSON plan. */
    HEAVY,
}

data class LlmRequest(
    /** System instruction; passed in the provider's native "system" slot. */
    val system: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val jsonMode: Boolean = false,
    val weight: TaskWeight = TaskWeight.LIGHT,
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
