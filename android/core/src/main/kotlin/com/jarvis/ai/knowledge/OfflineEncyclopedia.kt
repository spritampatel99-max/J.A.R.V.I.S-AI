package com.jarvis.ai.knowledge

import java.util.Locale

/** Looks things up by name in an offline encyclopedia and returns the opening of the article as plain text. */
class OfflineEncyclopedia(private val archive: ZimArchive, val sourceName: String) {

    sealed interface Result {
        data class Found(val title: String, val text: String) : Result

        /** No article has that name. [similar] are real article names that start the same way, or empty. */
        data class NotFound(val similar: List<String>) : Result

        /** The library could not be read. [reason] is safe to show. */
        data class Unreadable(val reason: String) : Result
    }

    /** Blocking: reads from disk. Call it off the main thread. */
    fun lookup(topic: String): Result {
        val guesses = TitleGuesses.of(topic)
        if (guesses.isEmpty()) return Result.NotFound(emptyList())
        try {
            for (guess in guesses) {
                for (ns in NAMESPACES) {
                    val entry = archive.find(ns, guess) ?: continue
                    val real = archive.resolve(entry) ?: continue
                    if (real.kind != EntryKind.ARTICLE) continue
                    val mime = archive.mimeType(real).orEmpty()
                    if (!mime.startsWith("text/html")) continue
                    val text = HtmlText.lead(String(archive.read(real), Charsets.UTF_8))
                    if (text.isBlank()) continue
                    return Result.Found(real.title, text)
                }
            }
            val similar = LinkedHashSet<String>()
            for (guess in guesses.take(3)) {
                for (ns in NAMESPACES) {
                    for (e in archive.withPrefix(ns, guess, 6)) {
                        val real = archive.resolve(e) ?: continue
                        if (real.kind == EntryKind.ARTICLE) similar += real.title
                        if (similar.size >= 5) break
                    }
                }
            }
            return Result.NotFound(similar.toList())
        } catch (e: ZimFormatException) {
            return Result.Unreadable(e.message ?: "The offline library could not be read.")
        } catch (e: ZimUnsupportedException) {
            return Result.Unreadable(e.message ?: "The offline library uses a format this build cannot read.")
        } catch (e: java.io.IOException) {
            return Result.Unreadable("The offline library file could not be read.")
        }
    }

    private companion object {
        /** Recent archives keep articles under C; older ones under A. */
        val NAMESPACES = charArrayOf('C', 'A')
    }
}

/** The address forms an article about [topic] might have, most likely first. Wikipedia-style: spaces become underscores. */
object TitleGuesses {
    private val SMALL = setOf("a", "an", "the", "of", "and", "or", "in", "on", "at", "to", "for", "by", "with", "de", "la", "le", "von", "van", "der")
    private const val MAX_TOPIC = 100

    fun of(topic: String): List<String> {
        val cleaned = topic.trim()
            .trim('"', '\'', '.', '?', '!', ',', ';', ':')
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^(?:the|a|an)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_TOPIC) return emptyList()
        if (cleaned.any { it == '/' || it == '<' || it == '>' || it.isISOControl() }) return emptyList()

        val words = cleaned.split(' ')
        val smallWordTitle = words.mapIndexed { i, w -> if (i > 0 && w.lowercase(Locale.ROOT) in SMALL) w.lowercase(Locale.ROOT) else capitalise(w) }.joinToString(" ")
        val allTitle = words.joinToString(" ") { capitalise(it) }
        // Speech or typing in capitals ("ALBERT EINSTEIN") or in lower case: normalise every word.
        val lowered = words.map { it.lowercase(Locale.ROOT) }
        val normalisedSmall = lowered.mapIndexed { i, w -> if (i > 0 && w in SMALL) w else capitalise(w) }.joinToString(" ")
        val normalisedAll = lowered.joinToString(" ") { capitalise(it) }
        val sentence = capitalise(cleaned.lowercase(Locale.ROOT))
        val acronym = if (words.size == 1 && cleaned.length <= 6) cleaned.uppercase(Locale.ROOT) else null

        return listOfNotNull(cleaned, smallWordTitle, allTitle, normalisedSmall, normalisedAll, sentence, acronym)
            .map { it.replace(' ', '_') }
            .distinct()
    }

    private fun capitalise(word: String): String =
        if (word.isEmpty()) word else word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)
}

/** Recognises "who is X", "what is X", "tell me about X" and similar, and returns X. Null for anything else. */
object OfflineQuestions {
    private val PATTERNS = listOf(
        Regex("""^(?:who|what)\s+(?:is|are|was|were)\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:who|what)'s\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:tell|teach)\s+me\s+(?:something\s+)?about\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:explain|describe|define)\s+(?:what\s+(?:an?\s+|the\s+)?)?(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:what\s+does\s+(.+?)\s+mean)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:meaning|definition)\s+of\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:information|info)\s+(?:about|on)\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    /** Subjects that are about the phone or the person, not the world. */
    private val PERSONAL = Regex(
        """^(?:my|your|our|this|that|these|those|it|there|here|today|tomorrow|now|calling|ringing|winning|next|the\s+(?:time|date|day|weather|temperature|score|news|battery|storage|screen|price))\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "on my screen", "in my pocket": a place or thing of the person's, not a topic. */
    private val ABOUT_THE_PERSON = Regex("""\b(?:my|me|mine|our|your)\b|^(?:on|in|at|under|near|from|with|for)\b""", RegexOption.IGNORE_CASE)

    private val ARTICLE = Regex("""^(?:an?|the)\s+""", RegexOption.IGNORE_CASE)

    fun subject(text: String): String? {
        val line = text.trim().trim('.', '?', '!').replace(Regex("""\s+"""), " ")
        for (p in PATTERNS) {
            val raw = p.matchEntire(line)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: continue
            // The personal check sees the article ("the time"), because "time" alone is also an encyclopedia article.
            if (raw.isEmpty() || raw.length > 90 || PERSONAL.containsMatchIn(raw) || ABOUT_THE_PERSON.containsMatchIn(raw)) return null
            val subject = raw.replace(ARTICLE, "").trim()
            if (subject.isEmpty() || subject.length > 80 || subject.split(' ').size > 8) return null
            return subject
        }
        return null
    }
}
