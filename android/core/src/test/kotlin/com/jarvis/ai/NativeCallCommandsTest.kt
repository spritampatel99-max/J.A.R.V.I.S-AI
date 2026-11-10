package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.AnswerResult
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.CallPort
import com.jarvis.ai.ai.tools.builtin.CallStart
import com.jarvis.ai.ai.tools.builtin.ContactSearch
import com.jarvis.ai.ai.tools.builtin.ContactsPort
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.ai.tools.builtin.EndCallResult
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.TorchController
import com.jarvis.ai.ai.tools.builtin.TorchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/** What the built-in matcher does with phone-call wording. Calls are the riskiest thing it can start, so wrong matches matter. */
class NativeCallCommandsTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-08T07:17:00Z"), ZoneOffset.ofHoursMinutes(5, 30))

    private val registry = ToolRegistry(
        BuiltinTools.create(
            DevicePorts(
                torch = object : TorchController {
                    override suspend fun setTorch(on: Boolean): TorchResult = TorchResult.NoFlash
                },
                alarms = object : AlarmLauncher {
                    override fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult = LaunchResult.Launched
                    override fun nextAlarmEpochMs(): Long? = null
                },
                urls = object : UrlOpener {
                    override fun open(url: String): LaunchResult = LaunchResult.Launched
                },
                deviceInfo = object : DeviceInfoReader {
                    override fun read(): DeviceInfo = error("not used")
                },
                contacts = object : ContactsPort {
                    override suspend fun search(query: String): ContactSearch = ContactSearch.Found(emptyList())
                },
                calls = object : CallPort {
                    override fun call(number: String, allowDirect: Boolean): CallStart = CallStart.Placed
                    override fun answerRinging(): AnswerResult = AnswerResult.Requested
                    override fun endCall(): EndCallResult = EndCallResult.Ended
                },
            ),
            clock,
        ),
    )

    private fun match(text: String) = NativeCommands.match(text, clock)

    private fun single(text: String): Pair<String, Map<String, String>> {
        val run = assertIs<NativeCommands.Match.Run>(match(text), "expected a command for: $text")
        val step = run.steps.single()
        return step.tool to step.parameters.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun callingANameUsesTheContactTool() {
        // The name is passed on as typed; the contact search is case-insensitive.
        assertEquals("call_contact" to mapOf("name" to "mom"), single("call mom"))
        assertEquals("call_contact" to mapOf("name" to "John Smith"), single("Call John Smith"))
        assertEquals("call_contact" to mapOf("name" to "Priya"), single("please call Priya now"))
        assertEquals("call_contact" to mapOf("name" to "dad"), single("phone my dad"))
    }

    @Test
    fun callingANumberUsesTheNumberTool() {
        assertEquals("call_number", single("call +91 98765 43210").first)
        assertEquals("call_number", single("dial 9876543210").first)
        assertEquals("98765 43210", single("call 98765 43210").second["number"])
    }

    @Test
    fun carrierCodesAreStillSentToTheNumberToolWhichRefusesThem() {
        // The matcher must not "clean" a code into a name or swallow it; the tool is what refuses to dial it.
        assertEquals("call_number", single("call *#06#").first)
        assertEquals("call_number", single("dial #21#").first)
    }

    @Test
    fun answerAndHangUpPhrases() {
        for (text in listOf("answer the call", "pick up the phone", "take the call", "take call", "accept the call")) {
            assertEquals("answer_call", single(text).first, text)
        }
        for (text in listOf("hang up", "end the call", "end call", "reject the call", "decline the call", "cut the call", "disconnect the call")) {
            assertEquals("end_call", single(text).first, text)
        }
    }

    @Test
    fun everyMatchedCallStepIsValidForItsRealTool() {
        val samples = listOf("call mom", "call +91 98765 43210", "dial 112", "answer the call", "hang up", "call *#06#")
        for (sample in samples) {
            for (step in (match(sample) as NativeCommands.Match.Run).steps) {
                val tool = assertNotNull(registry.get(step.tool), "unknown tool ${step.tool} for $sample")
                val check = ArgValidator.validate(tool.spec, step.parameters)
                assertTrue(check is ValidationResult.Valid, "$sample -> ${step.tool} ${step.parameters}: $check")
            }
        }
    }

    @Test
    fun ordinaryUsesOfTheWordCallAreNotCommands() {
        val notCommands = listOf(
            "call me back", "call it a day", "call off the meeting", "call of duty", "what is a call stack",
            "how do I call a function", "who should I call for a plumber", "call the plumber and ask about the leak tomorrow evening please",
            "can you explain what a conference call is", "it was a close call", "call", "answer", "answer the question", "end", "hang",
        )
        for (text in notCommands) {
            val result = match(text)
            val ok = result == null || (result is NativeCommands.Match.Ask)
            assertTrue(ok, "should not run a call for: '$text' (got $result)")
        }
    }

    @Test
    fun twoPeopleAreNeverMergedIntoOneContactName() {
        for (text in listOf("call mom and dad", "call mom then dad", "call mom or dad")) {
            val result = match(text)
            if (result is NativeCommands.Match.Run) {
                val names = result.steps.map { it.parameters["name"]?.jsonPrimitive?.content }
                assertTrue(names.none { it?.contains(" and ") == true || it?.contains(" then ") == true || it?.contains(" or ") == true }, "$text -> $names")
            }
        }
    }

    @Test
    fun withoutCallPortsNothingIsMatchedAsACall() {
        // Matching is independent of ports, but the registry then has no call tools, so the planner/executor refuses it:
        val bare = ToolRegistry(
            BuiltinTools.create(
                DevicePorts(
                    torch = object : TorchController {
                        override suspend fun setTorch(on: Boolean): TorchResult = TorchResult.NoFlash
                    },
                    alarms = object : AlarmLauncher {
                        override fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult = LaunchResult.Launched
                        override fun nextAlarmEpochMs(): Long? = null
                    },
                    urls = object : UrlOpener {
                        override fun open(url: String): LaunchResult = LaunchResult.Launched
                    },
                    deviceInfo = object : DeviceInfoReader {
                        override fun read(): DeviceInfo = error("not used")
                    },
                ),
                clock,
            ),
        )
        assertNull(bare.get("call_contact"))
        assertNull(bare.get("call_number"))
    }
}
