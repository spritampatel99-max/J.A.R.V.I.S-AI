package com.jarvis.ai

import com.jarvis.ai.ai.providers.HttpResult
import com.jarvis.ai.ai.providers.HttpTransport
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.voice.AudioFetcher
import com.jarvis.ai.voice.AudioResult
import com.jarvis.ai.voice.ElevenLabsApi
import com.jarvis.ai.voice.ElevenLabsClient
import com.jarvis.ai.voice.ElevenOutcome
import com.jarvis.ai.voice.SpeechRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ElevenLabsTest {
    private val secret = "sk_test_SECRETSECRET1234567890"

    private class Http(var result: HttpResult = HttpResult(200, "{}"), var fail: ProviderError? = null) : HttpTransport {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        override suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult = error("not used")
        override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
            lastUrl = url
            lastHeaders = headers
            fail?.let { throw it }
            return result
        }
    }

    private class Audio(var result: AudioResult = AudioResult(200, byteArrayOf(1, 2, 3), ""), var fail: ProviderError? = null) : AudioFetcher {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String? = null
        override suspend fun postJsonForAudio(url: String, headers: Map<String, String>, body: String, maxBytes: Int): AudioResult {
            this.url = url
            this.headers = headers
            this.body = body
            fail?.let { throw it }
            return result
        }
    }

    private val voicesJson = """{"voices":[
        {"voice_id":"abcDEF123456","name":"Mine","category":"cloned"},
        {"voice_id":"21m00Tcm4TlvDq8ikWAM","name":"Rachel","category":"premade"},
        {"voice_id":"bad id!","name":"Broken"},
        {"name":"NoId"}]}"""

    @Test
    fun theKeyGoesInTheHeaderOnlyNeverInTheUrlOrBody() = runTest {
        val audio = Audio()
        val client = ElevenLabsClient(Http(), audio, { secret })
        assertIs<ElevenOutcome.Ok<ByteArray>>(client.speak("21m00Tcm4TlvDq8ikWAM", "Hello there"))
        assertEquals(secret, audio.headers["xi-api-key"])
        assertFalse(secret in audio.url!!)
        assertFalse(secret in audio.body!!)
        assertTrue("eleven_multilingual_v2" in audio.body!! && "Hello there" in audio.body!!)
        assertTrue(audio.url!!.startsWith("https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM"))
    }

    @Test
    fun voicesAreParsedAndPremadeComeFirstAndBrokenEntriesAreDropped() = runTest {
        val client = ElevenLabsClient(Http(HttpResult(200, voicesJson)), Audio(), { secret })
        val ok = assertIs<ElevenOutcome.Ok<List<com.jarvis.ai.voice.ElevenVoice>>>(client.voices())
        assertEquals(listOf("Rachel", "Mine"), ok.value.map { it.name })
    }

    @Test
    fun withoutAKeyNothingIsSentAndTheKeyIsFlagged() = runTest {
        val http = Http()
        val audio = Audio()
        val client = ElevenLabsClient(http, audio, { null })
        val v = assertIs<ElevenOutcome.Failed>(client.voices())
        val s = assertIs<ElevenOutcome.Failed>(client.speak("21m00Tcm4TlvDq8ikWAM", "hi"))
        assertTrue(v.keyProblem && s.keyProblem)
        assertEquals(null, http.lastUrl)
        assertEquals(null, audio.url)
    }

    @Test
    fun errorsAreExplainedWithoutRepeatingTheServersBodyOrTheKey() = runTest {
        val echo = """{"detail":{"message":"bad key $secret"}}"""
        val client = ElevenLabsClient(Http(HttpResult(401, echo)), Audio(AudioResult(401, ByteArray(0), echo)), { secret })
        val a = assertIs<ElevenOutcome.Failed>(client.voices())
        val b = assertIs<ElevenOutcome.Failed>(client.speak("21m00Tcm4TlvDq8ikWAM", "hi"))
        assertTrue(a.keyProblem && b.keyProblem)
        assertFalse(secret in a.message || secret in b.message)
        assertEquals("ElevenLabs did not accept the key. Check it was copied completely.", a.message)
        assertTrue("run out of characters" in ElevenLabsApi.describeError(402, ""))
        assertTrue("busy" in ElevenLabsApi.describeError(429, ""))
    }

    @Test
    fun aNetworkFailureBecomesAMessageWithoutTheKey() = runTest {
        val client = ElevenLabsClient(Http(fail = ProviderError.Network("failed for header xi-api-key: $secret")), Audio(fail = ProviderError.Timeout("slow")), { secret })
        val a = assertIs<ElevenOutcome.Failed>(client.voices())
        assertFalse(a.keyProblem)
        assertTrue("Could not reach ElevenLabs" in a.message)
        val b = assertIs<ElevenOutcome.Failed>(client.speak("21m00Tcm4TlvDq8ikWAM", "hi"))
        assertTrue("Could not reach ElevenLabs" in b.message)
    }

    @Test
    fun aBadVoiceIdOrEmptyTextIsRefusedBeforeAnyRequest() = runTest {
        val audio = Audio()
        val client = ElevenLabsClient(Http(), audio, { secret })
        assertIs<ElevenOutcome.Failed>(client.speak("../../etc", "hi"))
        assertIs<ElevenOutcome.Failed>(client.speak("21m00Tcm4TlvDq8ikWAM", "   "))
        assertEquals(null, audio.url)
    }

    @Test
    fun anEmptyAudioBodyIsAFailureNotSilence() = runTest {
        val client = ElevenLabsClient(Http(), Audio(AudioResult(200, ByteArray(0), "")), { secret })
        assertIs<ElevenOutcome.Failed>(client.speak("21m00Tcm4TlvDq8ikWAM", "hi"))
    }

    // ---- which voice speaks ----------------------------------------------------------------------------------------

    private fun route(text: String = "Hello", private: Boolean = false, enabled: Boolean = true, hasKey: Boolean = true, voice: String? = "21m00Tcm4TlvDq8ikWAM", online: Boolean = true) =
        SpeechRoute.choose(text, private, enabled, hasKey, voice, online)

    @Test
    fun elevenLabsOnlyWhenEverythingIsInPlace() {
        assertEquals(SpeechRoute.ELEVENLABS, route())
        assertEquals(SpeechRoute.PHONE, route(enabled = false))
        assertEquals(SpeechRoute.PHONE, route(hasKey = false))
        assertEquals(SpeechRoute.PHONE, route(voice = null))
        assertEquals(SpeechRoute.PHONE, route(voice = " "))
        assertEquals(SpeechRoute.PHONE, route(online = false))
    }

    @Test
    fun privateContentAndLongTextStayOnThePhone() {
        assertEquals(SpeechRoute.PHONE, route(private = true))
        assertEquals(SpeechRoute.ELEVENLABS, route(text = "a".repeat(ElevenLabsApi.MAX_CHARS)))
        assertEquals(SpeechRoute.PHONE, route(text = "a".repeat(ElevenLabsApi.MAX_CHARS + 1)))
    }
}
