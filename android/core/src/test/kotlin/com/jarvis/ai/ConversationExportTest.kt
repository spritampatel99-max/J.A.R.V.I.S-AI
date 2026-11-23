package com.jarvis.ai

import com.jarvis.ai.history.Conversation
import com.jarvis.ai.history.ConversationExport
import com.jarvis.ai.history.StoredMessage
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationExportTest {
    private val zone: ZoneId = ZoneOffset.ofHoursMinutes(5, 30)

    // 2026-11-22 10:31 in +05:30 is 05:01 UTC.
    private val t0 = java.time.ZonedDateTime.of(2026, 11, 22, 10, 31, 0, 0, zone).toInstant().toEpochMilli()

    private fun convo(id: String, start: Long, vararg messages: StoredMessage, title: String = "hello", updated: Long = start) =
        Conversation(id, start, updated, title, messages.toList())

    @Test
    fun theMarkdownShowsWhoSaidWhatAndWhen() {
        val c = convo(
            "a", t0,
            StoredMessage("user", "call Mom", t0, viaVoice = true),
            StoredMessage("assistant", "Calling Mom, mobile.", t0 + 2_000, answeredBy = "Built-in command"),
            StoredMessage("system", "Call placed.", t0 + 3_000),
            title = "call Mom",
        )
        val text = ConversationExport.markdown(listOf(c), zone, t0 + 60_000)

        assertTrue(text.startsWith("# JARVIS 3.0 conversations\n\nSaved 2026-11-22 10:32 · 1 conversation\n"), text)
        assertTrue(text.contains("## call Mom\nStarted 2026-11-22 10:31"))
        assertTrue(text.contains("**You** · 2026-11-22 10:31 · by voice\ncall Mom"))
        assertTrue(text.contains("**JARVIS (Built-in command)** · 2026-11-22 10:31\nCalling Mom, mobile."))
        assertTrue(text.contains("**System** · 2026-11-22 10:31\nCall placed."))
    }

    @Test
    fun conversationsComeOutOldestFirstAndEmptyTextIsMarked() {
        val newer = convo("n", t0 + 86_400_000, StoredMessage("user", "   ", t0 + 86_400_000), title = "newer")
        val older = convo("o", t0, StoredMessage("user", "x", t0), title = "older")
        val text = ConversationExport.markdown(listOf(newer, older), zone, t0)
        assertTrue(text.indexOf("## older") < text.indexOf("## newer"))
        assertTrue(text.contains("(empty)"))
        assertTrue(text.contains("2 conversations"))
    }

    @Test
    fun aTitleWithLineBreaksCannotInjectMarkdownStructure() {
        val c = convo("a", t0, StoredMessage("user", "hi", t0), title = "hi\n# fake heading\r\nmore")
        val text = ConversationExport.markdown(listOf(c), zone, t0)
        assertFalse(text.lines().any { it == "# fake heading" }, text)
        assertEquals(1, text.lines().count { it.startsWith("## ") })
    }

    @Test
    fun fileNamesAreSafeAndStamped() {
        assertEquals("jarvis-chat-2026-11-22-1031.md", ConversationExport.fileName(t0, zone))
        assertEquals("jarvis-all-2026-11-22-1031.md", ConversationExport.fileName(t0, zone, "All"))
        val hostile = ConversationExport.fileName(t0, zone, "../../etc/\u0000")
        assertEquals("jarvis-etc-2026-11-22-1031.md", hostile)
        assertFalse(hostile.contains('/') || hostile.contains('\\') || hostile.contains(".."), hostile)
        assertEquals("jarvis-chat-2026-11-22-1031.md", ConversationExport.fileName(t0, zone, "///"))
        assertEquals("jarvis-chat-2026-11-22.md", ConversationExport.dayFileName(t0, zone))
    }

    @Test
    fun theDayFilterKeepsConversationsActiveOnThatDay() {
        val day = 86_400_000L
        val today = convo("t", t0, StoredMessage("user", "a", t0))
        val yesterday = convo("y", t0 - day, StoredMessage("user", "b", t0 - day))
        val spans = convo("s", t0 - day, StoredMessage("user", "c", t0 - day), updated = t0)
        val kept = ConversationExport.forDay(listOf(today, yesterday, spans), t0, zone).map { it.id }
        assertEquals(listOf("t", "s"), kept)
    }
}
