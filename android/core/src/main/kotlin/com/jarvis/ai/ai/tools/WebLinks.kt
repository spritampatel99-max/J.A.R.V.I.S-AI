package com.jarvis.ai.ai.tools

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * URL handling for the "open a website" tools. Only http and https are ever opened:
 * javascript:, file:, intent:, content: and similar schemes are rejected because the URL
 * text comes from a language model that may have read untrusted content.
 */
object WebLinks {

    /** A validated, canonical http(s) address. [toString] is the address itself. */
    data class WebUrl(val value: String, val host: String) {
        override fun toString(): String = value
    }

    /** @return a safe http(s) URL, or null if [raw] is not one. A bare "example.com" becomes https. */
    fun normalizeWebUrl(raw: String): WebUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        // user:password@host links are a classic phishing shape; never open them.
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null

        // Canonical form: lower-case scheme and host, and "/" for an empty path.
        val authority = uri.rawAuthority?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return WebUrl("$scheme://$authority$path$query$fragment", host.lowercase())
    }

    fun youtubeSearchUrl(query: String): String =
        YOUTUBE_SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    const val YOUTUBE_HOME = "https://www.youtube.com/"

    private const val YOUTUBE_SEARCH_PREFIX = "https://www.youtube.com/results?search_query="
    private const val MAX_URL_LENGTH = 2_000
}
