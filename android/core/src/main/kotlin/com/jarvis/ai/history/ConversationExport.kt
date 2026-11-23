package com.jarvis.ai.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns saved conversations into a plain Markdown text the person can keep in their file manager. The text comes from the history, which
 * has already been through the secret redactor, so a key pasted into a message is not written out. Nothing here reads or sends anything.
 */
object ConversationExport {
    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)
    private val dayStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    fun markdown(conversations: List<Conversation>, zone: ZoneId, exportedAtMs: Long): String = buildString {
        appendLine("# JARVIS 3.0 conversations")
        appendLine()
        appendLine("Saved ${format(exportedAtMs, zone)} · ${conversations.size} conversation${if (conversations.size == 1) "" else "s"}")
        for (conversation in conversations.sortedBy { it.startedMs }) {
            appendLine()
            appendLine("## ${oneLine(conversation.title).ifEmpty { "Conversation" }}")
            appendLine("Started ${format(conversation.startedMs, zone)}")
            for (message in conversation.messages) {
                appendLine()
                appendLine("**${speaker(message)}** · ${format(message.atMs, zone)}${if (message.viaVoice) " · by voice" else ""}")
                appendLine(message.text.trim().ifEmpty { "(empty)" })
            }
        }
    }

    /** A safe file name such as `jarvis-chat-2026-11-22-1031.md` (a single conversation) with no characters a file system dislikes. */
    fun fileName(atMs: Long, zone: ZoneId, label: String = "chat"): String {
        val safe = label.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' }.take(20).ifEmpty { "chat" }
        return "jarvis-$safe-${fileStamp.format(Instant.ofEpochMilli(atMs).atZone(zone))}.md"
    }

    /** The name of the one file that holds every conversation of a given day, used by auto-save. */
    fun dayFileName(atMs: Long, zone: ZoneId): String = "jarvis-chat-${dayStamp.format(Instant.ofEpochMilli(atMs).atZone(zone))}.md"

    /** The conversations that were active on the day that contains [atMs]. */
    fun forDay(all: List<Conversation>, atMs: Long, zone: ZoneId): List<Conversation> {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        return all.filter { c ->
            val start = Instant.ofEpochMilli(c.startedMs).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(c.updatedMs).atZone(zone).toLocalDate()
            !day.isBefore(start) && !day.isAfter(end)
        }
    }

    private fun speaker(message: StoredMessage): String = when (message.sender) {
        "user" -> "You"
        "assistant" -> "JARVIS" + (message.answeredBy?.let { " (${oneLine(it)})" } ?: "")
        else -> "System"
    }

    private fun format(ms: Long, zone: ZoneId): String = dateTime.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun oneLine(text: String): String = text.filter { !it.isISOControl() || it == ' ' }.trim()
}
