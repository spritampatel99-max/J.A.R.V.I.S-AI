package com.jarvis.ai.memory

import java.util.Locale

/**
 * Recognises the person asking, in so many words, to keep something in memory. Only clear phrases count, so nothing is saved by accident:
 * "remember that my sister is called Asha" saves, while "remember to call mom" is a reminder request and is left to the planner.
 * Questions never save. Deleting is never done by chat; that is only in the Memory screen.
 */
object MemoryCommands {

    private val PATTERNS: List<Regex> = listOf(
        Regex("^(?:please\\s+)?(?:remember|keep in mind)\\s+that\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:please\\s+)?(?:save|add|put|store)\\s+(?:this|that|it)?\\s*(?:to|in|into)\\s+(?:my\\s+)?memory\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:please\\s+)?(?:save|add|put|store)\\s+(.+?)\\s+(?:to|in|into)\\s+(?:my\\s+)?memory$", RegexOption.IGNORE_CASE),
    )

    /** The text to remember, or null when [input] is not a clear request to remember something. */
    fun remember(input: String): String? {
        val text = input.trim().trimEnd('.', '!').trim()
        if (text.isEmpty() || text.endsWith("?")) return null
        for (pattern in PATTERNS) {
            val found = pattern.matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 } ?: continue
            return found.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return null
    }
}
