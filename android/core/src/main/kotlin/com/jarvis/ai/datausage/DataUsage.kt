package com.jarvis.ai.datausage

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.security.Risk
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class UsagePeriod(val label: String) {
    TODAY("today"),
    WEEK("this week"),
    MONTH("this month"),
}

/** [startMs] is included, [endMs] is the moment the question was asked. */
data class UsageRange(val startMs: Long, val endMs: Long)

object UsagePeriods {
    /** Today starts at midnight where the phone is. This week starts on Monday at midnight. This month starts on the 1st at midnight. */
    fun range(period: UsagePeriod, nowMs: Long, zone: ZoneId): UsageRange {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = when (period) {
            UsagePeriod.TODAY -> today
            UsagePeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            UsagePeriod.MONTH -> today.withDayOfMonth(1)
        }
        return UsageRange(first.atStartOfDay(zone).toInstant().toEpochMilli(), nowMs)
    }
}

/** One row of Android's per-app counters, before names are looked up. */
data class UidUsage(val uid: Int, val rxBytes: Long, val txBytes: Long)

/** What an app is called on screen. [key] groups rows that are really one app (the same package under two users, for example). */
data class AppLabel(val key: String, val label: String)

data class AppUsage(val label: String, val rxBytes: Long, val txBytes: Long) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class DataUsageReport(
    val period: UsagePeriod,
    val range: UsageRange,
    val rxBytes: Long,
    val txBytes: Long,
    /** The biggest users first. Anything beyond the limit is added up in one "Other apps" row. */
    val apps: List<AppUsage>,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

sealed interface DataUsageResult {
    data class Report(val report: DataUsageReport) : DataUsageResult
    data object NeedsAccess : DataUsageResult
    data class Unavailable(val reason: String) : DataUsageResult
}

/** Reads Android's own mobile-data counters. The Android side implements it with NetworkStatsManager, which needs "Usage access". */
interface DataUsagePort {
    fun hasAccess(): Boolean

    /** Mobile data (not Wi-Fi) used in [range]. Never throws: a refusal or a failure comes back as a result. */
    fun read(period: UsagePeriod, range: UsageRange, appLimit: Int): DataUsageResult
}

object DataUsageMath {
    const val OTHER_APPS = "Other apps"

    /**
     * Adds up per-uid rows into per-app rows, biggest first, keeping [limit] of them and putting the rest in one "Other apps" row.
     * Negative numbers (never expected) count as zero, and an app that used nothing is left out.
     */
    fun topApps(rows: List<UidUsage>, labelOf: (Int) -> AppLabel, limit: Int): List<AppUsage> {
        val byKey = LinkedHashMap<String, AppUsage>()
        for (row in rows) {
            val rx = row.rxBytes.coerceAtLeast(0)
            val tx = row.txBytes.coerceAtLeast(0)
            if (rx + tx == 0L) continue
            val l = labelOf(row.uid)
            val old = byKey[l.key]
            byKey[l.key] = if (old == null) AppUsage(l.label, rx, tx) else old.copy(rxBytes = old.rxBytes + rx, txBytes = old.txBytes + tx)
        }
        val sorted = byKey.values.sortedWith(compareByDescending<AppUsage> { it.totalBytes }.thenBy { it.label })
        if (limit <= 0) return emptyList()
        if (sorted.size <= limit) return sorted
        val shown = sorted.take(limit)
        val rest = sorted.drop(limit)
        return shown + AppUsage(OTHER_APPS, rest.sumOf { it.rxBytes }, rest.sumOf { it.txBytes })
    }
}

/** Sizes the way Android's own settings write them: 1 KB is 1000 bytes. */
object ByteSize {
    fun format(bytes: Long): String {
        val n = bytes.coerceAtLeast(0)
        return when {
            n < 1_000 -> "$n B"
            n < 1_000_000 -> "%.0f KB".format(Locale.ROOT, n / 1_000.0)
            n < 1_000_000_000 -> if (n < 10_000_000) "%.1f MB".format(Locale.ROOT, n / 1_000_000.0) else "%.0f MB".format(Locale.ROOT, n / 1_000_000.0)
            else -> "%.2f GB".format(Locale.ROOT, n / 1_000_000_000.0)
        }
    }
}

object DataUsageText {
    fun summary(r: DataUsageReport, topCount: Int = 5): String = buildString {
        append("Mobile data ").append(r.period.label).append(": ").append(ByteSize.format(r.totalBytes))
        append(" (").append(ByteSize.format(r.rxBytes)).append(" down, ").append(ByteSize.format(r.txBytes)).append(" up).")
        val named = r.apps.filter { it.label != DataUsageMath.OTHER_APPS }.take(topCount)
        if (named.isNotEmpty()) {
            append("\nBiggest: ").append(named.joinToString("; ") { "${it.label} ${ByteSize.format(it.totalBytes)}" }).append('.')
        }
        append("\nThese are Android's own counters for this phone. Your mobile operator's figure can differ, and Wi-Fi is not counted.")
    }
}

/** "How much mobile data have I used?" Read-only. App names are the person's own information, so the answer is private. */
class DataUsageTool(private val port: DataUsagePort, private val time: TimeSource, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Say how much mobile data the phone used today, this week (from Monday) or this month, and which apps used the most. Read from Android's own counters; Wi-Fi is not counted.",
        params = listOf(
            ParamSpec("period", ParamType.ENUM, "today, week or month. Default today.", required = false, enumValues = listOf("today", "week", "month")),
        ),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED: numbers read from Android. Private: spoken by the phone's own voice only.",
    )

    override fun describe(args: ToolArgs): String = "Check mobile data used ${period(args).label}"

    private fun period(args: ToolArgs): UsagePeriod = when (args.stringOrNull("period")?.lowercase(Locale.ROOT)) {
        "week", "this week" -> UsagePeriod.WEEK
        "month", "this month" -> UsagePeriod.MONTH
        else -> UsagePeriod.TODAY
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val period = period(args)
        if (!port.hasAccess()) {
            return ToolResult.Failure(ToolError.Unavailable("Usage access is off. Turn it on in Prefs > Mobile data, then ask again."))
        }
        val range = UsagePeriods.range(period, time.nowMs(), zone())
        return when (val r = port.read(period, range, appLimit = 10)) {
            is DataUsageResult.Report -> ToolResult.Success(DataUsageText.summary(r.report), Verification.CONFIRMED, answer = true, private = true)
            DataUsageResult.NeedsAccess -> ToolResult.Failure(ToolError.Unavailable("Usage access is off. Turn it on in Prefs > Mobile data, then ask again."))
            is DataUsageResult.Unavailable -> ToolResult.Failure(ToolError.Unavailable(r.reason))
        }
    }

    companion object {
        const val NAME = "get_data_usage"
    }
}
