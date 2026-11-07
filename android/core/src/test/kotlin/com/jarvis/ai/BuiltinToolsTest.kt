package com.jarvis.ai

import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Planner
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.ai.tools.builtin.DeviceInfoTool
import com.jarvis.ai.ai.tools.builtin.FlashlightTool
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.OpenUrlTool
import com.jarvis.ai.ai.tools.builtin.OpenYoutubeTool
import com.jarvis.ai.ai.tools.builtin.SetAlarmTool
import com.jarvis.ai.ai.tools.builtin.TorchController
import com.jarvis.ai.ai.tools.builtin.TorchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FakeTorch(var result: TorchResult) : TorchController {
    val requests = mutableListOf<Boolean>()

    override suspend fun setTorch(on: Boolean): TorchResult {
        requests += on
        return result
    }
}

private class FakeAlarms(
    var launch: LaunchResult = LaunchResult.Launched,
    var next: () -> Long? = { null },
) : AlarmLauncher {
    val launched = mutableListOf<Triple<Int, Int, String?>>()

    override fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult {
        launched += Triple(hour, minute, label)
        return launch
    }

    override fun nextAlarmEpochMs(): Long? = next()
}

private class FakeUrls(var result: LaunchResult = LaunchResult.Launched) : UrlOpener {
    val opened = mutableListOf<String>()

    override fun open(url: String): LaunchResult {
        opened += url
        return result
    }
}

private class FakeDevice(var info: DeviceInfo) : DeviceInfoReader {
    override fun read(): DeviceInfo = info
}

class BuiltinToolsTest {

    // 2026-11-06 09:17 in India (03:47 UTC)
    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:47:00Z"), zone)
    private val tomorrow6am = Instant.parse("2026-11-07T00:30:00Z").toEpochMilli() // 06:00 at +05:30

    private fun args(vararg pairs: Pair<String, Any>) = ToolArgs(mapOf(*pairs))

    // ---- flashlight -----------------------------------------------------------------------

    @Test
    fun flashlightReportsWhatAndroidConfirmed() = runTest {
        val torch = FakeTorch(TorchResult.Confirmed(true))
        val result = FlashlightTool(torch).execute(args("state" to "on"))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals(Verification.CONFIRMED, success.verification)
        assertTrue(success.message.contains("on"))
        assertEquals(listOf(true), torch.requests)
    }

    @Test
    fun flashlightDoesNotClaimSuccessWhenAndroidReportsTheOppositeState() = runTest {
        val result = FlashlightTool(FakeTorch(TorchResult.Confirmed(false))).execute(args("state" to "on"))

        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.error.retryable)
    }

    @Test
    fun flashlightExplainsMissingHardwareAndBusyCamera() = runTest {
        val noFlash = FlashlightTool(FakeTorch(TorchResult.NoFlash)).execute(args("state" to "on"))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(noFlash).error)

        val busy = FlashlightTool(FakeTorch(TorchResult.Unavailable("camera in use"))).execute(args("state" to "off"))
        assertTrue(assertIs<ToolResult.Failure>(busy).error.message.contains("camera in use"))
    }

    // ---- alarm ----------------------------------------------------------------------------

    @Test
    fun alarmIsConfirmedWhenTheSystemListsItAsTheNextAlarm() = runTest {
        val alarms = FakeAlarms(next = { tomorrow6am })

        val result = SetAlarmTool(alarms, clock).execute(args("hour" to 6, "minute" to 0, "label" to "Wake up", "date" to "2026-11-07"))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals(Verification.CONFIRMED, success.verification)
        assertTrue(success.message.contains("Sat 7 Nov, 06:00"))
        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(6, 0, "Wake up")), alarms.launched)
    }

    @Test
    fun alarmIsOnlyHandedOffWhenTheSystemDoesNotConfirmIt() = runTest {
        val alarms = FakeAlarms(next = { null })

        val success = assertIs<ToolResult.Success>(SetAlarmTool(alarms, clock).execute(args("hour" to 6, "minute" to 0)))

        assertEquals(Verification.HANDED_OFF, success.verification)
        assertTrue(success.message.contains("check the Clock app"))
        assertFalse(success.message.contains("confirmed"))
    }

    @Test
    fun anEarlierExistingAlarmDoesNotCountAsConfirmation() = runTest {
        val alarms = FakeAlarms(next = { tomorrow6am - 3_600_000L })

        val success = assertIs<ToolResult.Success>(SetAlarmTool(alarms, clock).execute(args("hour" to 6, "minute" to 0)))

        assertEquals(Verification.HANDED_OFF, success.verification)
    }

    @Test
    fun alarmRefusesADateAndroidCannotHonourInsteadOfSettingTheWrongDay() = runTest {
        val alarms = FakeAlarms()

        val result = SetAlarmTool(alarms, clock).execute(args("hour" to 6, "minute" to 0, "date" to "2026-11-09"))

        val error = assertIs<ToolResult.Failure>(result).error
        assertIs<ToolError.Unsupported>(error)
        assertTrue(error.message.contains("next occurrence"))
        assertTrue(alarms.launched.isEmpty(), "nothing may be sent to the Clock app")
    }

    @Test
    fun alarmRejectsAMalformedDateAndReportsMissingClockApp() = runTest {
        val bad = SetAlarmTool(FakeAlarms(), clock).execute(args("hour" to 6, "minute" to 0, "date" to "tomorrow"))
        assertIs<ToolError.InvalidArguments>(assertIs<ToolResult.Failure>(bad).error)

        val noApp = SetAlarmTool(FakeAlarms(launch = LaunchResult.NoHandler("alarms")), clock).execute(args("hour" to 6, "minute" to 0))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(noApp).error)
    }

    @Test
    fun alarmDescriptionForTheConfirmationDialogComesFromValidatedValues() {
        val text = SetAlarmTool(FakeAlarms(), clock).describe(args("hour" to 6, "minute" to 5, "label" to "Gym"))
        assertEquals("Set an alarm \"Gym\" for 06:05", text)
        assertEquals(Risk.MEDIUM, SetAlarmTool(FakeAlarms(), clock).spec.risk)
    }

    // ---- browser / youtube ------------------------------------------------------------------

    @Test
    fun openUrlLaunchesOnlyNormalisedWebAddresses() = runTest {
        val urls = FakeUrls()
        val tool = OpenUrlTool(urls)

        val ok = assertIs<ToolResult.Success>(tool.execute(args("url" to "example.com/docs")))
        assertEquals(listOf("https://example.com/docs"), urls.opened)
        assertEquals(Verification.HANDED_OFF, ok.verification, "the page load is not observable, so this is not CONFIRMED")

        val bad = tool.execute(args("url" to "javascript:alert(1)"))
        assertIs<ToolError.InvalidArguments>(assertIs<ToolResult.Failure>(bad).error)
        assertEquals(1, urls.opened.size, "a rejected URL must never reach Android")
    }

    @Test
    fun openUrlReportsWhenNoAppCanHandleIt() = runTest {
        val result = OpenUrlTool(FakeUrls(LaunchResult.NoHandler("web links"))).execute(args("url" to "example.com"))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(result).error)
    }

    @Test
    fun youtubeOpensHomeOrASearch() = runTest {
        val urls = FakeUrls()
        val tool = OpenYoutubeTool(urls)

        tool.execute(args())
        tool.execute(args("query" to "lofi beats"))

        assertEquals("https://www.youtube.com/", urls.opened[0])
        assertTrue(urls.opened[1].startsWith("https://www.youtube.com/results?search_query=lofi"))
        assertEquals("Open YouTube", tool.describe(args()))
    }

    // ---- device info ------------------------------------------------------------------------

    @Test
    fun deviceInfoReportsOnlyWhatWasRead() = runTest {
        val full = DeviceInfo(82, true, "Google", "Pixel 7", "14", 34, "Wi-Fi", 45_200_000_000L, 128_000_000_000L)
        val success = assertIs<ToolResult.Success>(DeviceInfoTool(FakeDevice(full)).execute(args()))

        assertEquals(Verification.CONFIRMED, success.verification)
        assertTrue(success.message.contains("Battery 82%, charging"))
        assertTrue(success.message.contains("Google Pixel 7, Android 14 (API 34)"))
        assertTrue(success.message.contains("Network: Wi-Fi"))
        assertTrue(success.message.contains("45.2 GB free of 128.0 GB"))
        assertEquals("82", success.data["battery_percent"])

        val sparse = DeviceInfo(null, null, "Acme", "X1", "11", 30, "Offline", null, null)
        val partial = assertIs<ToolResult.Success>(DeviceInfoTool(FakeDevice(sparse)).execute(args()))
        assertFalse(partial.message.contains("Battery"), "no invented battery value when none could be read")
        assertFalse(partial.message.contains("Storage"))
        assertNull(partial.data["battery_percent"])
    }

    // ---- the whole pipeline: the example from the master prompt ------------------------------

    private val planJson = """
        {"reply":null,"clarification":null,"steps":[
          {"tool":"flashlight","description":"Turn on the flashlight","parameters":{"state":"on"},"critical":false},
          {"tool":"set_alarm","description":"Alarm for 6 AM tomorrow","parameters":{"hour":6,"minute":0,"date":"2026-11-07"},"critical":false},
          {"tool":"open_youtube","description":"Open YouTube","parameters":{},"critical":false}
        ]}
    """.trimIndent()

    private fun pipeline(torch: FakeTorch, alarms: FakeAlarms, urls: FakeUrls, llm: FakeLlm): Pair<Planner, Executor> {
        val ports = DevicePorts(torch, alarms, urls, FakeDevice(DeviceInfo(50, false, "A", "B", "14", 34, "Wi-Fi", null, null)))
        val registry = ToolRegistry(BuiltinTools.create(ports, clock))
        val planner = Planner(llm, registry, clock)
        val executor = Executor(registry, FakePermissions(), ConfirmationGate(time = FakeTime()), InMemoryAuditLog(), planner, time = FakeTime())
        return planner to executor
    }

    @Test
    fun flashlightAlarmAndYoutubeAreExecutedAndReportedHonestly() = runTest {
        val torch = FakeTorch(TorchResult.Confirmed(true))
        val alarms = FakeAlarms(next = { tomorrow6am })
        val urls = FakeUrls()
        val (planner, executor) = pipeline(torch, alarms, urls, FakeLlm(planJson))

        val planned = assertIs<PlanResult.Ready>(planner.plan("Jarvis, turn on my flashlight, set an alarm for 6 AM tomorrow and open YouTube."))
        val report = executor.execute(planned.plan)

        assertTrue(report.allSucceeded)
        assertEquals(listOf(true), torch.requests)
        assertEquals(listOf<Triple<Int, Int, String?>>(Triple(6, 0, null)), alarms.launched)
        assertEquals(listOf("https://www.youtube.com/"), urls.opened)
        val lines = report.summary().lines()
        assertTrue(lines[0].startsWith("✔"), "torch state was read back from Android")
        assertTrue(lines[1].startsWith("✔"), "the alarm shows up as the system's next alarm")
        assertTrue(lines[2].startsWith("➜"), "YouTube was launched but its content cannot be verified")
    }

    @Test
    fun whenTheFlashlightFailsTheOtherTwoStillRunAndTheFailureIsReported() = runTest {
        val torch = FakeTorch(TorchResult.NoFlash)
        val alarms = FakeAlarms(next = { tomorrow6am })
        val urls = FakeUrls()
        val (planner, executor) = pipeline(torch, alarms, urls, FakeLlm(planJson))

        val planned = assertIs<PlanResult.Ready>(planner.plan("do the three things"))
        val report = executor.execute(planned.plan)

        assertEquals(listOf(StepStatus.FAILED, StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), report.outcomes.map { it.status })
        assertEquals(1, alarms.launched.size)
        assertEquals(1, urls.opened.size)
        assertTrue(report.summary().lines().first().startsWith("✘"))
        assertTrue(report.summary().contains("no flashlight"))
    }
}
