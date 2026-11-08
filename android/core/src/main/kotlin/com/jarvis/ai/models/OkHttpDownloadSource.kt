package com.jarvis.ai.models

import com.jarvis.ai.ai.providers.await
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [DownloadSource] backed by OkHttp.
 *
 * Unlike the AI-provider client, redirects ARE followed here, because Hugging Face answers a model
 * file address with a redirect to its download host. They are still https only (an https-to-http
 * redirect is refused), and OkHttp drops the Authorization header when a redirect leaves the original host.
 */
class OkHttpDownloadSource(private val client: OkHttpClient = defaultClient()) : DownloadSource {

    override suspend fun open(url: String, headers: Map<String, String>, startAt: Long): DownloadResponse =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://", ignoreCase = true)) throw IOException("Only https addresses are allowed")
            val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Invalid address")
            val builder = Request.Builder().url(httpUrl).get()
            for ((name, value) in headers) builder.header(name, value)
            if (startAt > 0) builder.header("Range", "bytes=$startAt-")
            Wrapped(client.newCall(builder.build()).await())
        }

    private class Wrapped(private val response: Response) : DownloadResponse {
        override val code: Int get() = response.code
        override val rangeStart: Long? =
            response.header("Content-Range")?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        override val body: InputStream? get() = response.body?.byteStream()
        override fun close() = response.close()
    }

    companion object {
        private val RANGE = Regex("""bytes (\d+)-""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
