package com.jarvis.ai.models

import com.jarvis.ai.core.common.ByteFormat

/**
 * Measures how fast a download is going, from the byte counts the downloader reports.
 *
 * Only the last [windowMs] milliseconds count, so the figure follows the real speed instead of the average since the start. A new
 * download (the byte count goes down) starts a fresh measurement. [bytesPerSecond] stays null until there are two readings at least
 * a second apart, so the notification never shows a made-up speed.
 */
class SpeedMeter(private val windowMs: Long = 8_000L) {
    private class Sample(val atMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    fun add(atMs: Long, bytesDone: Long) {
        val last = samples.lastOrNull()
        if (last != null && (bytesDone < last.bytes || atMs < last.atMs)) samples.clear()
        samples.addLast(Sample(atMs, bytesDone))
        while (samples.size > 2 && atMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    fun bytesPerSecond(): Long? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        val elapsed = last.atMs - first.atMs
        if (elapsed < MIN_SPAN_MS) return null
        return ((last.bytes - first.bytes) * 1000L / elapsed).takeIf { it > 0 }
    }

    private companion object {
        const val MIN_SPAN_MS = 1_000L
    }
}

/** The one line shown in the download notification: what is downloading, how much, how fast, how long is left. */
object DownloadStatus {

    fun line(name: String, bytesDone: Long, totalBytes: Long?, bytesPerSecond: Long?): String {
        val done = ByteFormat.format(bytesDone.coerceAtLeast(0))
        val parts = ArrayList<String>(3)
        parts += if (totalBytes != null && totalBytes > 0) "$done of ${ByteFormat.format(totalBytes)}" else done
        if (bytesPerSecond != null && bytesPerSecond > 0) {
            parts += "${ByteFormat.format(bytesPerSecond)}/s"
            if (totalBytes != null && totalBytes > bytesDone) timeLeft(totalBytes - bytesDone, bytesPerSecond)?.let { parts += it }
        }
        return "$name: " + parts.joinToString(" · ")
    }

    /** "less than a minute left", "about 3 min left", "about 1 h 20 min left"; null when it would be a guess (no speed, or over two days). */
    fun timeLeft(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (remainingBytes <= 0 || bytesPerSecond <= 0) return null
        val seconds = (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
        return when {
            seconds <= 60 -> "less than a minute left"
            seconds < 3_600 -> "about ${(seconds + 59) / 60} min left"
            seconds <= 2 * 24 * 3_600 -> {
                val minutes = (seconds + 59) / 60
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "about $h h left" else "about $h h $m min left"
            }
            else -> null
        }
    }
}
