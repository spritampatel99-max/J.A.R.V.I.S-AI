package com.jarvis.ai.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One model a provider says this key can use. */
data class RemoteModel(
    val id: String,
    /** Human-readable name when the provider gives one. */
    val label: String? = null,
    /** True only when the provider's own listing says the model costs nothing. */
    val free: Boolean = false,
)

/**
 * Asks a provider which models the user's own key can see. This is the only reliable source
 * of model names: they change often, and an account can have access to a different set than
 * the documentation shows. The list is the provider's answer, filtered only to drop models
 * that obviously are not text chat models (embeddings, speech, image generation).
 *
 * A successful call is also a cheap proof that the key is accepted, because no tokens are used.
 */
class ModelLister(private val http: JsonHttp) {

    /**
     * @param apiKey needed by every provider except OpenRouter, whose public list works without one.
     * @throws ProviderError for every failure (bad key, network, unexpected response).
     */
    suspend fun list(provider: ProviderId, apiKey: String?): List<RemoteModel> {
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key == null && provider != ProviderId.OPENROUTER) {
            throw ProviderError.MissingConfig("${provider.displayName} API key is not set")
        }
        return when (provider) {
            ProviderId.GEMINI -> gemini(key.orEmpty())
            ProviderId.GROQ -> openAiStyle("https://api.groq.com/openai/v1/models", bearer(key))
            ProviderId.OPENAI -> openAiStyle("https://api.openai.com/v1/models", bearer(key))
            ProviderId.OPENROUTER -> openAiStyle("https://openrouter.ai/api/v1/models", if (key == null) emptyMap() else bearer(key))
            ProviderId.ANTHROPIC -> anthropic(key.orEmpty())
        }
    }

    private fun bearer(key: String?): Map<String, String> = mapOf("Authorization" to "Bearer ${key.orEmpty()}")

    private suspend fun gemini(key: String): List<RemoteModel> {
        val json = http.getJson(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            mapOf("x-goog-api-key" to key),
        )
        val models = json.array("models") ?: throw ProviderError.InvalidResponse("Gemini returned no model list")
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = obj.array("supportedGenerationMethods")?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if ("generateContent" !in methods) return@mapNotNull null
            if (NOT_TEXT_CHAT.containsMatchIn(name)) return@mapNotNull null
            RemoteModel(id = name, label = obj.text("displayName"))
        }.sortedBy { it.id }
    }

    private suspend fun openAiStyle(url: String, headers: Map<String, String>): List<RemoteModel> {
        val json = http.getJson(url, headers)
        val data = json.array("data") ?: throw ProviderError.InvalidResponse("The provider returned no model list")
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id") ?: return@mapNotNull null
            if (NOT_TEXT_CHAT.containsMatchIn(id)) return@mapNotNull null
            val pricing = obj["pricing"] as? JsonObject
            val free = pricing != null && pricing.text("prompt") == "0" && pricing.text("completion") == "0"
            RemoteModel(id = id, label = obj.text("name"), free = free)
        }.sortedWith(compareByDescending<RemoteModel> { it.free }.thenBy { it.id })
    }

    private suspend fun anthropic(key: String): List<RemoteModel> {
        val json = http.getJson(
            "https://api.anthropic.com/v1/models?limit=1000",
            mapOf("Authorization" to "Bearer $key", "anthropic-version" to AnthropicProvider.API_VERSION),
        )
        val data = json.array("data") ?: throw ProviderError.InvalidResponse("Claude returned no model list")
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id") ?: return@mapNotNull null
            RemoteModel(id = id, label = obj.text("display_name"))
        }.sortedBy { it.id }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.array(name: String): JsonArray? = try {
        this[name]?.jsonArray
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        /** Embeddings, speech, image/video generation, moderation and similar: never useful as a chat model here. */
        val NOT_TEXT_CHAT = Regex("(embed|whisper|tts|speech|transcribe|dall-e|imagen|image|veo|lyria|moderation|guard|realtime|audio|sora|rerank|orpheus|aqa)", RegexOption.IGNORE_CASE)
    }
}
