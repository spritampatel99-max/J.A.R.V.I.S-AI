package com.jarvis.ai.ai.providers

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * The only HTTP client the app builds for AI providers. It speaks modern TLS only, so a
 * provider request can never be downgraded to plain http, and it never follows redirects:
 * these are JSON APIs, and a redirect to another host could forward the Authorization
 * header somewhere unintended.
 */
object HttpClients {
    fun cloud(): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // A person is waiting for the answer. A provider that has said nothing for this long is handed over to the next one.
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}
