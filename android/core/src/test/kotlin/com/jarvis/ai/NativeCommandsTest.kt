package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.DevicePorts
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

class NativeCommandsTest {

    // 2026-11-08 12:47 in India (07:17 UTC)
    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val clock = Clock.fixed(Instant.parse("2026-11-08T07:17:00Z"), zone)

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
            ),
            clock,
        ),
    )

    private fun run(text: String): NativeCommands.Match.Run {
        val match = NativeCommands.match(text, clock)
        return assertIs<NativeCommands.Match.Run>(match, "expected a command for: $text")
    }

    private fun firstParam(text: String, name: String): String = run(text).steps.first().parameters[name]!!.jsonPrimitive.content

    // ---- every produced step must pass the real tool's own argument check -----------------------------

    @Test
    fun everyMatchedStepIsValidForItsRealTool() {
        val samples = listOf(
            "turn on the flashlight", "torch off", "open youtube", "play lofi beats on youtube",
            "set an alarm for 6:30 am", "set alarm for 18:45", "wake me up at 7 pm tomorrow",
            "set an alarm in 20 minutes", "open example.com", "what's my battery",
            "turn on the flashlight and open youtube",
        )
        for (sample in samples) {
            for (step in run(sample).steps) {
                val tool = assertNotNull(registry.get(step.tool), "unknown tool ${step.tool} for $sample")
                val check = ArgValidator.validate(tool.spec, step.parameters)
                assertTrue(check is ValidationResult.Valid, "$sample -> ${step.tool} ${step.parameters}: $check")
            }
        }
    }

    // ---- flashlight ------------------------------------------------------------------------------------

    @Test
    fun flashlightPhrases() {
        assertEquals("on", firstParam("Turn on the flashlight", "state"))
        assertEquals("off", firstParam("turn the torch off", "state"))
        assertEquals("on", firstParam("flashlight on", "state"))
        assertEquals("off", firstParam("please switch off my flash light.", "state"))
        assertEquals("on", firstParam("Jarvis, can you turn on the torch for me?", "state"))
        assertEquals("off", firstParam("disable flashlight", "state"))
        assertEquals("flashlight", run("torch on").steps.single().tool)
    }

    // ---- alarm -------------------------------------------------------------------------------------------

    @Test
    fun alarmWithAmPm() {
        val step = run("set an alarm for 6:30 am").steps.single()
        assertEquals("set_alarm", step.tool)
        assertEquals("6", step.parameters["hour"]!!.jsonPrimitive.content)
        assertEquals("30", step.parameters["minute"]!!.jsonPrimitive.content)
        assertNull(step.parameters["date"])
    }

    @Test
    fun alarmTwelveHourEdgeCases() {
        assertEquals("0", firstParam("set alarm for 12 am", "hour"))
        assertEquals("12", firstParam("set alarm for 12 pm", "hour"))
        assertEquals("12", firstParam("set an alarm for noon", "hour"))
        assertEquals("0", firstParam("set an alarm for midnight", "hour"))
        assertEquals("23", firstParam("set an alarm for 11 pm", "hour"))
        assertEquals("7", firstParam("set an alarm for 7 in the morning", "hour"))
        assertEquals("19", firstParam("set an alarm for 7 in the evening", "hour"))
    }

    @Test
    fun twentyFourHourTimesNeedNoAmPm() {
        assertEquals("18", firstParam("set an alarm for 18:45", "hour"))
        assertEquals("45", firstParam("set an alarm for 18:45", "minute"))
        assertEquals("6", firstParam("set an alarm for 06:30", "hour"))
        assertEquals("0", firstParam("set an alarm for 00:15", "hour"))
    }

    @Test
    fun anAmbiguousHourIsAskedAboutNotGuessed() {
        val ask = assertIs<NativeCommands.Match.Ask>(NativeCommands.match("set an alarm for 6", clock))
        assertTrue(ask.question.contains("AM or PM"))
        assertIs<NativeCommands.Match.Ask>(NativeCommands.match("wake me up at 7:30", clock))
    }

    @Test
    fun tomorrowAndTodayBecomeADate() {
        assertEquals("2026-11-09", firstParam("set an alarm for 6 am tomorrow", "date"))
        assertEquals("2026-11-09", firstParam("set an alarm for tomorrow at 6 am", "date"))
        assertEquals("2026-11-08", firstParam("set an alarm for 6 pm today", "date"))
    }

    @Test
    fun relativeAlarmsUseTheClock() {
        val step = run("set an alarm in 30 minutes").steps.single()
        // 12:47 + 30 min = 13:17 local
        assertEquals("13", step.parameters["hour"]!!.jsonPrimitive.content)
        assertEquals("17", step.parameters["minute"]!!.jsonPrimitive.content)
        assertEquals("14", firstParam("set an alarm in 2 hours", "hour"))
    }

    @Test
    fun impossibleTimesAreNotCommands() {
        assertNull(NativeCommands.match("set an alarm for 25:00", clock))
        assertNull(NativeCommands.match("set an alarm for 13 pm", clock))
        assertNull(NativeCommands.match("set an alarm for 6:75 am", clock))
        assertNull(NativeCommands.match("set an alarm in 0 minutes", clock))
        assertNull(NativeCommands.match("set an alarm in 5000 minutes", clock))
    }

    // ---- YouTube and websites ------------------------------------------------------------------------------

    @Test
    fun youtubeOpenAndSearch() {
        assertTrue(run("open youtube").steps.single().parameters.isEmpty())
        assertEquals("Lo-fi Beats", firstParam("Play Lo-fi Beats on YouTube", "query"))
        assertEquals("cats", firstParam("search youtube for cats", "query"))
        assertEquals("tom and jerry", firstParam("search for tom and jerry on youtube", "query"))
        assertEquals("cooking pasta", firstParam("open youtube and search for cooking pasta", "query"))
    }

    @Test
    fun websitesOnlyWhenTheTextLooksLikeAnAddress() {
        assertEquals("example.com", firstParam("open example.com", "url"))
        assertEquals("https://news.site.org/a?b=1", firstParam("go to https://news.site.org/a?b=1", "url"))
        assertEquals("open_url", run("visit the website wikipedia.org").steps.single().tool)
        assertNull(NativeCommands.match("open notes.txt", clock))
        assertNull(NativeCommands.match("open facebook", clock))
        assertNull(NativeCommands.match("open javascript:alert(1)", clock))
    }

    // ---- device status ----------------------------------------------------------------------------------------

    @Test
    fun deviceStatusPhrases() {
        for (text in listOf("battery", "what's my battery level", "how much battery do I have", "check storage", "device info", "show me my battery")) {
            assertEquals("get_device_info", run(text).steps.single().tool, text)
        }
    }

    // ---- several commands in one sentence -------------------------------------------------------------------------

    @Test
    fun severalCommandsRunInOrder() {
        val steps = run("turn on the flashlight, set an alarm for 6 AM tomorrow and open YouTube").steps
        assertEquals(listOf("flashlight", "set_alarm", "open_youtube"), steps.map { it.tool })
        assertEquals(listOf(1, 2, 3), steps.map { it.index })
    }

    @Test
    fun oneUnknownPartMeansNothingRunsByItself() {
        assertNull(NativeCommands.match("turn on the flashlight and write me a poem", clock))
    }

    @Test
    fun anAmbiguousPartAsksBeforeAnythingRuns() {
        assertIs<NativeCommands.Match.Ask>(NativeCommands.match("turn on the flashlight and set an alarm for 6", clock))
    }

    // ---- things that must NOT be treated as commands -----------------------------------------------------------------

    @Test
    fun questionsAndChatGoToTheModel() {
        val notCommands = listOf(
            "how do I set an alarm for tomorrow?",
            "what is a flashlight",
            "tell me about youtube",
            "why is my battery draining so fast",
            "explain how alarms work",
            "turn on the flashlight if it is dark outside",
            "",
            "   ",
            "hello",
        )
        for (text in notCommands) assertNull(NativeCommands.match(text, clock), "should not match: '$text'")
    }

    @Test
    fun veryLongTextIsLeftToTheModel() {
        assertNull(NativeCommands.match("turn on the flashlight " + "and ".repeat(200), clock))
    }
}
