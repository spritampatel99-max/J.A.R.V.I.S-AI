package com.jarvis.ai.ai.providers

import com.jarvis.ai.core.security.SecretRedactor
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** [HttpTransport] backed by OkHttp. Cancelling the calling coroutine cancels the HTTP call. */
class OkHttpTransport(
    private val client: OkHttpClient,
    private val redactor: SecretRedactor = SecretRedactor(),
) : HttpTransport {

    /**
     * Reading a response body is blocking I/O, and Android throws NetworkOnMainThreadException
     * if that happens on the main thread (view models run there), so the exchange runs on
     * Dispatchers.IO.
     */
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw ProviderError.MissingConfig("Invalid URL")
            val builder = Request.Builder().url(httpUrl)
            for ((name, value) in headers) builder.header(name, value)
            builder.post(body.toRequestBody(JSON_MEDIA_TYPE))

            val response = try {
                client.newCall(builder.build()).await()
            } catch (e: InterruptedIOException) {
                throw ProviderError.Timeout("Request timed out", e)
            } catch (e: IOException) {
                throw ProviderError.Network(safeMessage(e), e)
            }
            response.use { resp ->
                val text = try {
                    resp.body?.string().orEmpty()
                } catch (e: InterruptedIOException) {
                    throw ProviderError.Timeout("Reading the response timed out", e)
                } catch (e: IOException) {
                    throw ProviderError.Network(safeMessage(e), e)
                }
                HttpResult(resp.code, text, resp.header("Retry-After"))
            }
        }

    private fun safeMessage(e: IOException): String = redactor.redact(e.message ?: e.javaClass.simpleName)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
