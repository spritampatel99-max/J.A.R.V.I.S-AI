package com.jarvis.ai

import com.jarvis.ai.models.DownloadStatus
import com.jarvis.ai.models.SpeedMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadStatusTest {

    @Test
    fun noSpeedUntilTwoReadingsAtLeastASecondApart() {
        val meter = SpeedMeter()
        assertNull(meter.bytesPerSecond())
        meter.add(0, 0)
        assertNull(meter.bytesPerSecond())
        meter.add(500, 1_000_000)
        assertNull(meter.bytesPerSecond(), "half a second is too short to state a speed")
        meter.add(2_000, 4_000_000)
        assertEquals(2_000_000L, meter.bytesPerSecond())
    }

    @Test
    fun onlyTheRecentWindowCounts() {
        val meter = SpeedMeter(windowMs = 4_000)
        meter.add(0, 0)
        meter.add(2_000, 20_000_000) // fast start: 10 MB/s
        meter.add(10_000, 21_000_000)
        meter.add(12_000, 23_000_000) // now about 1 MB/s
        assertEquals(1_000_000L, meter.bytesPerSecond())
    }

    @Test
    fun aNewDownloadStartsAFreshMeasurement() {
        val meter = SpeedMeter()
        meter.add(0, 0)
        meter.add(2_000, 8_000_000)
        meter.add(3_000, 100) // the count went down: a different download
        assertNull(meter.bytesPerSecond())
        meter.add(5_000, 2_000_100)
        assertEquals(1_000_000L, meter.bytesPerSecond())
    }

    @Test
    fun aStalledDownloadHasNoSpeedAndNoTime() {
        val meter = SpeedMeter()
        meter.add(0, 5_000)
        meter.add(3_000, 5_000)
        assertNull(meter.bytesPerSecond())
        assertEquals("Model: 5.00 KB of 100 KB", DownloadStatus.line("Model", 5_000, 100_000, null))
    }

    @Test
    fun timeLeftWording() {
        assertEquals("less than a minute left", DownloadStatus.timeLeft(30_000_000, 1_000_000))
        assertEquals("about 2 min left", DownloadStatus.timeLeft(61_000_000, 1_000_000)) // 61 s rounds up to 2 min
        assertEquals("about 17 min left", DownloadStatus.timeLeft(1_000_000_000, 1_000_000))
        assertEquals("about 1 h left", DownloadStatus.timeLeft(3_600_000_000, 1_000_000))
        assertEquals("about 1 h 30 min left", DownloadStatus.timeLeft(5_400_000_000, 1_000_000))
        assertNull(DownloadStatus.timeLeft(500_000_000_000, 1_000_000), "over two days is not worth stating")
        assertNull(DownloadStatus.timeLeft(0, 1_000_000))
        assertNull(DownloadStatus.timeLeft(100, 0))
    }

    @Test
    fun theLineShowsSizeSpeedAndTimeLeft() {
        assertEquals(
            "Gemma 3 1B: 120 MB of 584 MB · 4.00 MB/s · about 2 min left",
            DownloadStatus.line("Gemma 3 1B", 120_000_000, 584_000_000, 4_000_000),
        )
        assertEquals("Library: 120 MB · 4.00 MB/s", DownloadStatus.line("Library", 120_000_000, null, 4_000_000))
        assertEquals("Library: 0 B", DownloadStatus.line("Library", -5, null, null))
    }
}
