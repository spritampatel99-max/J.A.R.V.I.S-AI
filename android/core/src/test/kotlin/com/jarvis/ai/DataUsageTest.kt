package com.jarvis.ai

import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.datausage.AppLabel
import com.jarvis.ai.datausage.AppUsage
import com.jarvis.ai.datausage.ByteSize
import com.jarvis.ai.datausage.DataUsageMath
import com.jarvis.ai.datausage.DataUsagePort
import com.jarvis.ai.datausage.DataUsageReport
import com.jarvis.ai.datausage.DataUsageResult
import com.jarvis.ai.datausage.DataUsageText
import com.jarvis.ai.datausage.DataUsageTool
import com.jarvis.ai.datausage.UidUsage
import com.jarvis.ai.datausage.UsagePeriod
import com.jarvis.ai.datausage.UsagePeriods
import com.jarvis.ai.datausage.UsageRange
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DataUsageTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Wednesday 2026-11-25 15:00 in India.
    private val now = Instant.parse("2026-11-25T09:30:00Z").toEpochMilli()

    private fun start(p: UsagePeriod) = Instant.ofEpochMilli(UsagePeriods.range(p, now, zone).startMs).atZone(zone).toLocalDateTime().toString()

    @Test fun `today starts at local midnight`() {
        assertEquals("2026-11-25T00:00", start(UsagePeriod.TODAY))
    }

    @Test fun `the week starts on Monday`() {
        assertEquals("2026-11-23T00:00", start(UsagePeriod.WEEK))
    }

    @Test fun `on a Monday the week starts that same day`() {
        val monday = Instant.parse("2026-11-23T20:00:00Z").toEpochMilli() // Tuesday 01:30 in India
        assertEquals("2026-11-24T00:00", Instant.ofEpochMilli(UsagePeriods.range(UsagePeriod.TODAY, monday, zone).startMs).atZone(zone).toLocalDateTime().toString())
        val mondayNoon = Instant.parse("2026-11-23T06:30:00Z").toEpochMilli()
        assertEquals("2026-11-23T00:00", Instant.ofEpochMilli(UsagePeriods.range(UsagePeriod.WEEK, mondayNoon, zone).startMs).atZone(zone).toLocalDateTime().toString())
    }

    @Test fun `the month starts on the first`() {
        assertEquals("2026-11-01T00:00", start(UsagePeriod.MONTH))
    }

    @Test fun `the range ends now and uses the zone the phone is in`() {
        val r = UsagePeriods.range(UsagePeriod.TODAY, now, ZoneId.of("America/Los_Angeles"))
        assertEquals(now, r.endMs)
        assertEquals("2026-11-25T00:00", Instant.ofEpochMilli(r.startMs).atZone(ZoneId.of("America/Los_Angeles")).toLocalDateTime().toString())
        assertTrue(r.startMs < now)
    }

    // ---- adding up the apps ------------------------------------------------------------------------------------------------

    private val labels = mapOf(
        10 to AppLabel("yt", "YouTube"),
        11 to AppLabel("yt", "YouTube"), // the same app under a second user
        20 to AppLabel("chrome", "Chrome"),
        30 to AppLabel("wa", "WhatsApp"),
        40 to AppLabel("maps", "Maps"),
    )

    private fun top(rows: List<UidUsage>, limit: Int = 10) = DataUsageMath.topApps(rows, { labels[it] ?: AppLabel("uid$it", "App $it") }, limit)

    @Test fun `rows of one app are added together and the biggest comes first`() {
        val apps = top(listOf(UidUsage(10, 100, 10), UidUsage(11, 50, 5), UidUsage(20, 500, 50), UidUsage(30, 10, 1)))
        assertEquals(listOf("Chrome", "YouTube", "WhatsApp"), apps.map { it.label })
        assertEquals(AppUsage("YouTube", 150, 15), apps[1])
    }

    @Test fun `apps with no traffic and bad numbers do not show up as usage`() {
        val apps = top(listOf(UidUsage(10, 0, 0), UidUsage(20, -50, -5), UidUsage(30, 40, -10)))
        assertEquals(listOf(AppUsage("WhatsApp", 40, 0)), apps, "a negative figure counts as zero, never as negative traffic")
    }

    @Test fun `the rest are added up in one other apps row so nothing is lost`() {
        val rows = listOf(UidUsage(10, 400, 0), UidUsage(20, 300, 0), UidUsage(30, 200, 0), UidUsage(40, 100, 0))
        val apps = top(rows, limit = 2)
        assertEquals(listOf("YouTube", "Chrome", DataUsageMath.OTHER_APPS), apps.map { it.label })
        assertEquals(300L, apps.last().totalBytes)
        assertEquals(rows.sumOf { it.rxBytes }, apps.sumOf { it.rxBytes }, "the rows add up to the same total")
    }

    @Test fun `equal use is ordered by name so the list does not jump about`() {
        val apps = top(listOf(UidUsage(40, 100, 0), UidUsage(30, 100, 0)))
        assertEquals(listOf("Maps", "WhatsApp"), apps.map { it.label })
    }

    @Test fun `a limit of zero or less shows nothing`() {
        assertTrue(top(listOf(UidUsage(10, 100, 0)), limit = 0).isEmpty())
    }

    @Test fun `sizes are written like Android settings write them`() {
        assertEquals("0 B", ByteSize.format(0))
        assertEquals("999 B", ByteSize.format(999))
        assertEquals("1 KB", ByteSize.format(1_000))
        assertEquals("512 KB", ByteSize.format(512_000))
        assertEquals("1.5 MB", ByteSize.format(1_500_000))
        assertEquals("312 MB", ByteSize.format(312_000_000))
        assertEquals("1.40 GB", ByteSize.format(1_400_000_000))
        assertEquals("0 B", ByteSize.format(-5))
    }

    // ---- the tool ----------------------------------------------------------------------------------------------------------

    private class Port(var access: Boolean = true, var result: DataUsageResult? = null) : DataUsagePort {
        var asked: Triple<UsagePeriod, UsageRange, Int>? = null
        override fun hasAccess() = access
        override fun read(period: UsagePeriod, range: UsageRange, appLimit: Int): DataUsageResult {
            asked = Triple(period, range, appLimit)
            return result ?: DataUsageResult.Unavailable("no data")
        }
    }

    private fun report(period: UsagePeriod = UsagePeriod.TODAY) = DataUsageReport(
        period, UsageRange(0, 1), 280_000_000, 32_000_000,
        listOf(AppUsage("YouTube", 150_000_000, 0), AppUsage("Chrome", 80_000_000, 0), AppUsage(DataUsageMath.OTHER_APPS, 82_000_000, 32_000_000)),
    )

    private fun tool(port: DataUsagePort) = DataUsageTool(port, FakeTime(now)) { zone }

    @Test fun `the answer states the total the direction and the biggest apps`() = runTest {
        val r = assertIs<ToolResult.Success>(tool(Port(result = DataUsageResult.Report(report()))).execute(ToolArgs(emptyMap())))
        assertTrue(r.message.startsWith("Mobile data today: 312 MB (280 MB down, 32 MB up)."), r.message)
        assertTrue(r.message.contains("Biggest: YouTube 150 MB; Chrome 80 MB."), r.message)
        assertFalse(r.message.contains("Other apps"), "the leftover row is not presented as an app")
        assertEquals(Verification.CONFIRMED, r.verification)
        assertTrue(r.answer)
        assertTrue(r.private, "app names are personal and are never sent to an outside voice")
    }

    @Test fun `the answer says Android's counters can differ from the operator`() {
        val text = DataUsageText.summary(report())
        assertTrue(text.contains("operator's figure can differ") && text.contains("Wi-Fi is not counted"))
    }

    @Test fun `the period is passed on and the range is worked out from now`() = runTest {
        val port = Port(result = DataUsageResult.Report(report(UsagePeriod.WEEK)))
        tool(port).execute(ToolArgs(mapOf("period" to "week")))
        val (period, range, limit) = port.asked!!
        assertEquals(UsagePeriod.WEEK, period)
        assertEquals(UsagePeriods.range(UsagePeriod.WEEK, now, zone), range)
        assertEquals(10, limit)
        tool(port).execute(ToolArgs(mapOf("period" to "month")))
        assertEquals(UsagePeriod.MONTH, port.asked!!.first)
        tool(port).execute(ToolArgs(emptyMap()))
        assertEquals(UsagePeriod.TODAY, port.asked!!.first, "today is the default")
    }

    @Test fun `without usage access it says so and does not guess a number`() = runTest {
        val port = Port(access = false, result = DataUsageResult.Report(report()))
        val r = assertIs<ToolResult.Failure>(tool(port).execute(ToolArgs(emptyMap())))
        assertIs<ToolError.Unavailable>(r.error)
        assertTrue(r.error.message.contains("Usage access is off"))
        assertEquals(null, port.asked, "nothing is read without access")
    }

    @Test fun `if Android refuses after access was checked the person is told`() = runTest {
        for (answer in listOf(DataUsageResult.NeedsAccess, DataUsageResult.Unavailable("Android did not give any data."))) {
            val r = assertIs<ToolResult.Failure>(tool(Port(result = answer)).execute(ToolArgs(emptyMap())))
            assertIs<ToolError.Unavailable>(r.error)
        }
    }

    @Test fun `a phone with no apps in the list still gets a total`() {
        val text = DataUsageText.summary(report().copy(apps = emptyList()))
        assertTrue(text.startsWith("Mobile data today: 312 MB"))
        assertFalse(text.contains("Biggest"))
    }
}
