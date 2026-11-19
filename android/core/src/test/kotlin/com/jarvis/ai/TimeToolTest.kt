package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.TimeTool
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TimeToolTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:15:00Z"), ZoneOffset.UTC)

    private fun tool(text: String): String? =
        (NativeCommands.match(text, clock) as? NativeCommands.Match.Run)?.steps?.singleOrNull()?.tool

    @Test
    fun timeAndDateQuestionsAreAnsweredWithoutAnAiCall() {
        for (text in listOf(
            "what time is it", "What's the time?", "what is the time", "tell me the time", "current time", "time please",
            "what is the date today", "what's today's date", "what day is it", "what date is it today", "today's date",
            "Hey Jarvis, what time is it?", "jarvis what's the date",
        )) {
            assertEquals("get_time", tool(text), text)
        }
    }

    @Test
    fun questionsThatMerelyMentionTimeAreLeftToTheAssistant() {
        for (text in listOf(
            "what time is the meeting", "what time does the shop open", "what time is sunset in delhi", "time machine movie",
            "what day is the match on", "what is the date of independence day", "how much time is left", "set a timer for 5 minutes",
        )) {
            assertNull(tool(text)?.takeIf { it == "get_time" }, text)
        }
    }

    @Test
    fun theToolReadsThePhoneClockAndIsAPlainAnswer() = runTest {
        val result = TimeTool(clock).execute(ToolArgs(emptyMap()))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("It is 10:15 AM on Thursday 12 November 2026.", success.message)
        assertTrue(success.answer)
        assertEquals(Verification.CONFIRMED, success.verification)
    }
}
