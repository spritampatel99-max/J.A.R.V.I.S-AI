package com.jarvis.ai.voice

import com.jarvis.ai.ai.providers.HttpTransport
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.core.security.SecretRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One voice the person's ElevenLabs account can use. */
data class ElevenVoice(val id: String, val name: String, val category: String?)

/** What a request for audio gave back. [bytes] is empty unless [code] is 200. */
class AudioResult(val code: Int, val bytes: ByteArray, val text: String)

/** The only place that fetches audio over the network, so everything else can be tested with a fake. */
interface AudioFetcher {
    /** POSTs JSON and returns the body as bytes, refusing to read more than [maxBytes]. Throws [ProviderError] on a network failure. */
    suspend fun postJsonForAudio(url: String, headers: Map<String, String>, body: String, maxBytes: Int): AudioResult
}

sealed interface ElevenOutcome<out T> {
    data class Ok<T>(val value: T) : ElevenOutcome<T>

    /** [message] is safe to show: it never contains the key. */
    data class Failed(val message: String, val keyProblem: Boolean = false) : ElevenOutcome<Nothing>
}

/**
 * ElevenLabs text-to-speech. The key is read from [key] on every call and only ever placed in the `xi-api-key` header; it is never put
 * in a URL, a message or a log. Text sent here leaves the phone, which is why [SpeechRoute] keeps private content away from it.
 */
class ElevenLabsClient(
    private val http: HttpTransport,
    private val audio: AudioFetcher,
    private val key: () -> String?,
    private val redactor: SecretRedactor = SecretRedactor(),
) {
    suspend fun voices(): ElevenOutcome<List<ElevenVoice>> {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: return ElevenOutcome.Failed("Add your ElevenLabs key first.", keyProblem = true)
        return try {
            val result = http.get(ElevenLabsApi.VOICES_URL, headers(apiKey))
            if (result.code != 200) {
                ElevenOutcome.Failed(ElevenLabsApi.describeError(result.code, result.body), keyProblem = result.code == 401)
            } else {
                val voices = ElevenLabsApi.parseVoices(result.body)
                if (voices.isEmpty()) ElevenOutcome.Failed("This ElevenLabs account has no voices to use yet.") else ElevenOutcome.Ok(voices)
            }
        } catch (e: ProviderError) {
            ElevenOutcome.Failed("Could not reach ElevenLabs: ${redactor.redact(e.message.orEmpty())}")
        }
    }

    suspend fun speak(voiceId: String, text: String, model: String = ElevenLabsApi.DEFAULT_MODEL): ElevenOutcome<ByteArray> {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: return ElevenOutcome.Failed("Add your ElevenLabs key first.", keyProblem = true)
        if (!ElevenLabsApi.VOICE_ID.matches(voiceId)) return ElevenOutcome.Failed("Pick a voice first.")
        val clean = text.trim()
        if (clean.isEmpty()) return ElevenOutcome.Failed("There was nothing to say.")
        return try {
            val result = audio.postJsonForAudio(ElevenLabsApi.ttsUrl(voiceId), headers(apiKey) + ("Accept" to "audio/mpeg"), ElevenLabsApi.ttsBody(clean, model), ElevenLabsApi.MAX_AUDIO_BYTES)
            if (result.code == 200 && result.bytes.isNotEmpty()) {
                ElevenOutcome.Ok(result.bytes)
            } else {
                ElevenOutcome.Failed(ElevenLabsApi.describeError(result.code, result.text), keyProblem = result.code == 401)
            }
        } catch (e: ProviderError) {
            ElevenOutcome.Failed("Could not reach ElevenLabs: ${redactor.redact(e.message.orEmpty())}")
        }
    }

    private fun headers(apiKey: String): Map<String, String> = mapOf("xi-api-key" to apiKey, "Content-Type" to "application/json")
}

object ElevenLabsApi {
    const val BASE = "https://api.elevenlabs.io"
    const val VOICES_URL = "$BASE/v1/voices"

    /** Speaks many languages, Hindi included. */
    const val DEFAULT_MODEL = "eleven_multilingual_v2"
    const val MAX_AUDIO_BYTES = 4 * 1024 * 1024

    /** Replies longer than this stay on the phone's own voice, so one long answer cannot use up a month's allowance. */
    const val MAX_CHARS = 600

    val VOICE_ID = Regex("""[A-Za-z0-9]{8,40}""")

    private val json = Json { ignoreUnknownKeys = true }

    fun ttsUrl(voiceId: String): String = "$BASE/v1/text-to-speech/$voiceId?output_format=mp3_44100_64"

    fun ttsBody(text: String, model: String): String = buildJsonObject {
        put("text", text)
        put("model_id", model)
    }.toString()

    fun parseVoices(body: String): List<ElevenVoice> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val array = (root["voices"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = (o["voice_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (!VOICE_ID.matches(id)) return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty().ifEmpty { "Voice" }
            ElevenVoice(id, name.take(40), (o["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }.sortedBy { if (it.category == "premade") 0 else 1 }
    }

    /** Plain words for the failures people actually meet. The response body is never repeated, because it can echo the request. */
    fun describeError(code: Int, body: String): String = when (code) {
        401 -> "ElevenLabs did not accept the key. Check it was copied completely."
        402 -> "Your ElevenLabs plan does not allow that voice or has run out of characters."
        403 -> "ElevenLabs refused the request for this voice."
        404 -> "ElevenLabs does not know that voice."
        422 -> "ElevenLabs could not use that text or voice."
        429 -> "ElevenLabs is busy or you reached its limit. Try again soon."
        in 500..599 -> "ElevenLabs had a problem on its side."
        0 -> "No answer from ElevenLabs."
        else -> "ElevenLabs answered with an error ($code)."
    }
}

/** Why a reply was spoken by the phone's own voice instead of ElevenLabs. */
enum class SpeechRoute {
    ELEVENLABS,
    PHONE,
    ;

    companion object {
        /**
         * ElevenLabs only when the person turned it on, a key and voice are set, the phone is online, the text is short, and the text is
         * not the person's private content (messages, notifications, files), which never leaves the phone for a voice.
         */
        fun choose(text: String, private: Boolean, enabled: Boolean, hasKey: Boolean, voiceId: String?, online: Boolean): SpeechRoute = when {
            !enabled || !hasKey || voiceId.isNullOrBlank() || !online -> PHONE
            private -> PHONE
            text.length > ElevenLabsApi.MAX_CHARS -> PHONE
            else -> ELEVENLABS
        }
    }
}
