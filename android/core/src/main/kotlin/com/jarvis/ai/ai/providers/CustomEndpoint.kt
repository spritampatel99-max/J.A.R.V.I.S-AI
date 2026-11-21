package com.jarvis.ai.ai.providers

import java.net.URI
import java.net.URISyntaxException

/**
 * The address of an OpenAI-compatible service that the person typed in themselves (the "FreeLLM / own endpoint"
 * provider). The app does not know or vouch for any such service: it only checks that the address is safe to send a
 * request to, and then talks to it exactly like Groq or Mistral.
 *
 * What is accepted: `https://host[:port][/path]`, for example `https://my-gateway.example.com/v1`. A trailing
 * `/chat/completions` is accepted and removed, so pasting either the base address or the full chat address works.
 *
 * What is refused, and why:
 *  - anything but https (a prompt must not travel in clear text);
 *  - a user name or password in the address (it would end up in logs and error text);
 *  - a query string or a `#fragment` (an access token placed there would be sent on every request and shown in errors);
 *  - whitespace or control characters, and addresses with no host.
 */
object CustomEndpoint {

    sealed interface Parsed {
        /** [base] has no trailing slash and no `/chat/completions`, for example `https://host/v1`. */
        data class Ok(val base: String) : Parsed

        data class Bad(val reason: String) : Parsed
    }

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed.Bad("Enter the https address of the service.")
        if (text.any { it.isWhitespace() || it.isISOControl() }) return Parsed.Bad("The address must not contain spaces or line breaks.")

        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return Parsed.Bad("That is not a valid address.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null) return Parsed.Bad("Start the address with https://")
        if (scheme != "https") return Parsed.Bad("Only https addresses are used, so nothing you send travels in clear text.")
        if (uri.rawUserInfo != null) return Parsed.Bad("Do not put a user name or password in the address; use the API key field.")
        if (uri.rawQuery != null) return Parsed.Bad("Remove everything after the ? in the address; use the API key field for a token.")
        if (uri.rawFragment != null) return Parsed.Bad("Remove everything after the # in the address.")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return Parsed.Bad("The address has no host name.")
        val port = uri.port
        if (port != -1 && port !in 1..65535) return Parsed.Bad("The port number is not valid.")

        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith(CHAT_SUFFIX)) path = path.removeSuffix(CHAT_SUFFIX).trimEnd('/')
        val authority = if (port == -1) host else "$host:$port"
        return Parsed.Ok("https://$authority$path")
    }

    /** The address chat requests are posted to, or null when [raw] is not usable. */
    fun chatUrl(raw: String?): String? = (parse(raw) as? Parsed.Ok)?.let { it.base + CHAT_SUFFIX }

    /** The address the list of models is read from, or null when [raw] is not usable. */
    fun modelsUrl(raw: String?): String? = (parse(raw) as? Parsed.Ok)?.let { it.base + "/models" }

    private const val CHAT_SUFFIX = "/chat/completions"
}
