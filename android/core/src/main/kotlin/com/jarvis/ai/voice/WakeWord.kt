package com.jarvis.ai.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The decision "did the person say the wake word?" for the offline Vosk listener, kept apart from the Android code so it can be tested.
 * The wake word is the single word "Jarvis"; "hello Jarvis", "hey Jarvis" and "ok Jarvis" work because they contain it.
 */
object WakePhrase {
    private val WORD = Regex("(^|[^\\p{L}\\p{N}])jarvis([^\\p{L}\\p{N}]|$)", RegexOption.IGNORE_CASE)

    /** True when [text] contains the whole word "Jarvis" (not "jarvisly" or "jarvis123"). */
    fun heard(text: String): Boolean = WORD.containsMatchIn(text)
}

/** Stops one spoken word from waking the app twice: Vosk reports the same word as a partial result and again as a final result. */
class WakeDebounce(private val cooldownMs: Long = 3_000L) {
    private var lastMs = Long.MIN_VALUE

    /** True the first time, then false until [cooldownMs] has passed. */
    fun accept(nowMs: Long): Boolean {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs in 0 until cooldownMs) return false
        lastMs = nowMs
        return true
    }
}

/** Reads the text out of what Vosk reports: `{"partial":"..."}`, `{"text":"..."}` or `{"alternatives":[{"text":"..."}]}`. Bad input gives "". */
object VoskResult {
    fun text(json: String?): String {
        if (json.isNullOrBlank() || json.length > 20_000) return ""
        val obj: JsonObject = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            return ""
        }
        val direct = (obj["partial"] ?: obj["text"])?.let { stringOrNull(it) }
        if (direct != null) return direct
        val alternatives = obj["alternatives"] as? kotlinx.serialization.json.JsonArray ?: return ""
        return alternatives.mapNotNull { (it as? JsonObject)?.get("text")?.let { t -> stringOrNull(t) } }.joinToString(" ")
    }

    private fun stringOrNull(element: kotlinx.serialization.json.JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Decides where a file from a downloaded zip may be written. Used for the wake-word model, so a hostile zip cannot write outside its folder. */
object SafeZip {
    const val MAX_ENTRIES = 5_000
    const val MAX_TOTAL_BYTES = 400L * 1024 * 1024

    /**
     * The entry's path with `/` separators and no `.` parts, or null when it could escape the target folder: an absolute path, a drive,
     * a `..` part, a backslash, a NUL or other control character, or an empty name.
     */
    fun safeRelativePath(entryName: String): String? {
        if (entryName.isEmpty() || entryName.length > 300) return null
        if (entryName.any { it.isISOControl() || it == '\\' || it == ':' }) return null
        if (entryName.startsWith("/")) return null
        val parts = entryName.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
