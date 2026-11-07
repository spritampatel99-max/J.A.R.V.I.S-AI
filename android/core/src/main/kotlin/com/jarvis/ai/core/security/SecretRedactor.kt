package com.jarvis.ai.core.security

/**
 * Removes API keys from text before it is logged, stored or shown in an error.
 *
 * Two layers: exact secrets the app currently holds, and well-known key shapes
 * (Gemini `AIza...`, Groq `gsk_...`, OpenRouter `sk-or-...`, bearer tokens).
 */
class SecretRedactor(
    private val knownSecrets: () -> Collection<String> = { emptyList() },
) {
    fun redact(text: String): String {
        var out = text
        for (secret in knownSecrets()) {
            if (secret.length >= MIN_SECRET_LENGTH) out = out.replace(secret, REPLACEMENT)
        }
        return redactPatterns(out)
    }

    companion object {
        const val REPLACEMENT = "[REDACTED]"
        private const val MIN_SECRET_LENGTH = 8

        private val rules: List<Pair<Regex, String>> = listOf(
            Regex("AIza[0-9A-Za-z_\\-]{20,}") to REPLACEMENT,
            Regex("gsk_[0-9A-Za-z]{20,}") to REPLACEMENT,
            Regex("sk-or-[0-9A-Za-z_\\-]{16,}") to REPLACEMENT,
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}") to REPLACEMENT,
            Regex("(?i)(api[_-]?key[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-]{12,}") to "\$1$REPLACEMENT",
        )

        /** Pattern-only redaction, for call sites that do not know the current keys. */
        fun redactPatterns(text: String): String {
            var out = text
            for ((regex, replacement) in rules) out = regex.replace(out, replacement)
            return out
        }
    }
}
