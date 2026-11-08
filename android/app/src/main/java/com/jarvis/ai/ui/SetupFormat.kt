package com.jarvis.ai.ui

import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.device.ConnectionType
import com.jarvis.ai.device.Provenance
import com.jarvis.ai.device.Reading
import com.jarvis.ai.models.Amount
import com.jarvis.ai.setup.AmountTotal

/** Words for sizes. Exact, estimated and unknown amounts are always worded differently. */
internal fun Amount.display(): String = when (this) {
    is Amount.Known -> ByteFormat.format(bytes)
    is Amount.Estimate -> "about ${ByteFormat.format(bytes)} (estimate)"
    is Amount.Unknown -> "not known yet"
}

internal fun Amount.detail(): String? = when (this) {
    is Amount.Known -> null
    is Amount.Estimate -> basis
    is Amount.Unknown -> reason
}

internal fun AmountTotal.display(): String {
    if (exact) return ByteFormat.format(knownBytes)
    val parts = mutableListOf<String>()
    parts += if (lowerBoundBytes > 0) {
        (if (estimatedBytes > 0) "about " else "at least ") + ByteFormat.format(lowerBoundBytes)
    } else {
        "nothing sized yet"
    }
    if (unknownCount > 0) parts += "plus $unknownCount item(s) not sized yet"
    return parts.joinToString(", ")
}

internal data class ReadingText(val value: String, val tag: String, val note: String?)

internal fun <T : Any> Reading<T>.text(format: (T) -> String = { it.toString() }): ReadingText {
    val v = value
    return when (provenance) {
        Provenance.MEASURED -> ReadingText(format(requireNotNull(v)), "measured", null)
        Provenance.ESTIMATED -> ReadingText(format(requireNotNull(v)), "estimate", note)
        Provenance.UNAVAILABLE -> ReadingText("not available", "unavailable", note)
    }
}

internal fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

internal fun ConnectionType.label(): String = when (this) {
    ConnectionType.WIFI -> "Wi-Fi"
    ConnectionType.CELLULAR -> "Mobile data"
    ConnectionType.ETHERNET -> "Ethernet"
    ConnectionType.OTHER -> "Other connection"
    ConnectionType.NONE -> "No connection"
}
