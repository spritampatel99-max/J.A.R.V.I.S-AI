package com.jarvis.ai

import com.jarvis.ai.ai.providers.OkHttpTransport
import com.jarvis.ai.ai.providers.ProviderError
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/**
 * Integration test for the one class that touches OkHttp: the real transport against a local
 * MockWebServer on the loopback interface (no internet involved). It talks to the transport
 * directly, so plain http is fine here; the https-only rule lives in JsonHttp and is covered
 * in [JsonHttpTest]. The cloud client's TLS-only setting is not exercised by this test.
 *
 * Needs OkHttp and MockWebServer, which Gradle downloads. It has not been run in the
 * environment that wrote it (see docs/testing/BUILD_AND_TEST_REPORT.md).
 */
class OkHttpTransportTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    /** Explicit loopback address: avoids depending on how "localhost" resolves in the test container. */
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    @Test
    fun sendsTheHeadersAndBodyAndReturnsStatusBodyAndRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"slow":"down"}"""))

        val result = OkHttpTransport(OkHttpClient()).postJson(url("/v1/x"), mapOf("X-Test" to "abc"), """{"a":1}""")

        assertEquals(429, result.code)
        assertEquals("""{"slow":"down"}""", result.body)
        assertEquals("7", result.retryAfter)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/x", recorded.path)
        assertEquals("abc", recorded.getHeader("X-Test"))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("""{"a":1}""", recorded.body.readUtf8())
    }

    @Test
    fun errorStatusesAreResultsNotExceptions() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("overloaded"))

        val result = OkHttpTransport(OkHttpClient()).postJson(url("/"), emptyMap(), "{}")

        assertEquals(503, result.code)
        assertEquals("overloaded", result.body)
        assertNull(result.retryAfter)
    }

    @Test
    fun anUnreachableServerIsANetworkError() {
        val error = assertFailsWith<ProviderError.Network> {
            runBlocking { OkHttpTransport(OkHttpClient()).postJson("http://127.0.0.1:1/", emptyMap(), "{}") }
        }
        assertTrue(error.retryable)
    }

    @Test
    fun aServerThatNeverAnswersIsATimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        val error = assertFailsWith<ProviderError.Timeout> {
            runBlocking { OkHttpTransport(impatient).postJson(url("/"), emptyMap(), "{}") }
        }
        assertTrue(error.retryable)
    }

    @Test
    fun anAddressThatIsNotAUrlIsAConfigurationError() {
        assertFailsWith<ProviderError.MissingConfig> {
            runBlocking { OkHttpTransport(OkHttpClient()).postJson("not a url", emptyMap(), "{}") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun getSendsAPlainGetWithHeadersAndNoBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val result = OkHttpTransport(OkHttpClient()).get(url("/api/models/x/tree/main"), mapOf("Accept" to "application/json"))

        assertEquals(200, result.code)
        assertEquals("[]", result.body)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/models/x/tree/main", recorded.path)
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun getReportsErrorStatusesAndBadAddressesLikePost() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val result = runBlocking { OkHttpTransport(OkHttpClient()).get(url("/"), emptyMap()) }
        assertEquals(404, result.code)
        assertFailsWith<ProviderError.MissingConfig> {
            runBlocking { OkHttpTransport(OkHttpClient()).get("not a url", emptyMap()) }
        }
    }
}
