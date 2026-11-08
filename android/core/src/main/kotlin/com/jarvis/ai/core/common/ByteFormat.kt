package com.jarvis.ai.core.common

import java.util.Locale

/** Binary units, used for thresholds and headroom. */
object Bytes {
    const val KIB: Long = 1024L
    const val MIB: Long = 1024L * KIB
    const val GIB: Long = 1024L * MIB
}

/**
 * Human-readable sizes in decimal units (1 MB = 1,000,000 bytes), the way Android's own storage
 * screens and Hugging Face quote them. Never used for calculations, only for display.
 */
object ByteFormat {
    private val units = listOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        require(bytes >= 0) { "size cannot be negative" }
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000.0 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val text = when {
            unit == 0 -> bytes.toString()
            value >= 100.0 -> String.format(Locale.ROOT, "%.0f", value)
            value >= 10.0 -> String.format(Locale.ROOT, "%.1f", value)
            else -> String.format(Locale.ROOT, "%.2f", value)
        }
        return "$text ${units[unit]}"
    }
}
