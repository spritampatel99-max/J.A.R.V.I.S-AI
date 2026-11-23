package com.jarvis.ai.update

import com.jarvis.ai.automation.HealthFinding
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.AuditKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** When the nightly review runs. The review looks, writes down suggestions and notifies; it changes nothing by itself. */
object NightlyClock {
    const val HOUR = 22

    /** The 22:00 at or before [nowMs] in [zone]. */
    fun lastRunSlotMs(nowMs: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var slot = now.toLocalDate().atTime(LocalTime.of(HOUR, 0)).atZone(zone)
        if (slot.isAfter(now)) slot = now.toLocalDate().minusDays(1).atTime(LocalTime.of(HOUR, 0)).atZone(zone)
        return slot.toInstant().toEpochMilli()
    }

    /** The next 22:00 strictly after [nowMs]. */
    fun nextRunMs(nowMs: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var slot = now.toLocalDate().atTime(LocalTime.of(HOUR, 0)).atZone(zone)
        if (!slot.isAfter(now)) slot = LocalDate.from(now).plusDays(1).atTime(LocalTime.of(HOUR, 0)).atZone(zone)
        return slot.toInstant().toEpochMilli()
    }

    /** A review is owed when the last 22:00 has passed since the previous review (or since the person switched the review on). A phone that was off catches up once, not once per missed night. */
    fun due(nowMs: Long, zone: ZoneId, lastRunMs: Long): Boolean = lastRunMs < lastRunSlotMs(nowMs, zone)
}

/** What the schedule engine asks of the nightly review: when it next wants a wake-up, and to run if a review is owed. */
interface NightlyHook {
    fun nextMs(nowMs: Long): Long?

    suspend fun runIfDue(nowMs: Long)
}

enum class ProposalKind { UPDATE, FIX_SETTING, REPEATED_FAILURE }

/** What "Apply" does. It is only ever opening a page or a settings screen for the person: nothing is changed or installed silently. */
sealed interface ProposalAction {
    data class OpenDownload(val url: String) : ProposalAction

    data object OpenAccessSettings : ProposalAction

    data object OpenLog : ProposalAction
}

data class Proposal(
    val id: String,
    val kind: ProposalKind,
    val title: String,
    val detail: String,
    val action: ProposalAction,
)

/** Only counts, tool names, Android's own state and a published version number go in: no message text, no screen or notification text. */
data class ReviewInputs(
    val nowMs: Long,
    val currentVersion: String,
    val newerRelease: ReleaseInfo?,
    val auditEvents: List<AuditEvent>,
    val scheduleFindings: List<HealthFinding>,
    /** Ids the person answered "leave as it is" to. They are not proposed again. */
    val leftAsIs: Set<String>,
)

object NightlyReview {
    const val MAX_PROPOSALS = 6
    const val FAILURES_NEEDED = 2
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun build(input: ReviewInputs): List<Proposal> {
        val out = ArrayList<Proposal>()
        input.newerRelease?.let { release ->
            out += Proposal(
                id = "update:${release.version}",
                kind = ProposalKind.UPDATE,
                title = "Version ${release.version} is available",
                detail = "You have ${input.currentVersion}. Apply opens the download page. You install it yourself, and Android asks you to confirm; nothing installs on its own.",
                action = ProposalAction.OpenDownload(release.apkUrl ?: release.pageUrl),
            )
        }
        for (finding in input.scheduleFindings) {
            out += Proposal("setting:" + finding.title.lowercase().filter { it.isLetterOrDigit() }.take(40), ProposalKind.FIX_SETTING, finding.title, finding.fix, ProposalAction.OpenAccessSettings)
        }
        val failures = input.auditEvents
            .filter { it.timestampMs >= input.nowMs - DAY_MS && (it.kind == AuditKind.FAILED || it.kind == AuditKind.BLOCKED) }
            .groupingBy { it.tool }
            .eachCount()
            .filterValues { it >= FAILURES_NEEDED }
            .entries.sortedByDescending { it.value }
        for ((tool, count) in failures) {
            out += Proposal(
                id = "failure:$tool",
                kind = ProposalKind.REPEATED_FAILURE,
                title = "\"${tool.replace('_', ' ')}\" failed $count times today",
                detail = "Open the log to see why. Often a missing access (screen, notifications, contacts) or an app that was not installed.",
                action = ProposalAction.OpenLog,
            )
        }
        return out.filter { it.id !in input.leftAsIs }.take(MAX_PROPOSALS)
    }

    fun notificationTitle(proposals: List<Proposal>): String = "JARVIS 3.0 nightly review"

    fun notificationText(proposals: List<Proposal>): String = when (proposals.size) {
        0 -> "Nothing to change tonight."
        1 -> "1 suggestion: ${proposals[0].title}. Open the app to choose what to apply and what to leave as it is."
        else -> "${proposals.size} suggestions. Open the app to choose what to apply and what to leave as it is."
    }

    // ---- storage of the pending list (small JSON in the app's own settings) ----------------------------------------------

    fun encode(proposals: List<Proposal>): String = buildJsonArray {
        for (p in proposals) {
            add(
                buildJsonObject {
                    put("id", p.id)
                    put("kind", p.kind.name)
                    put("title", p.title)
                    put("detail", p.detail)
                    when (val a = p.action) {
                        is ProposalAction.OpenDownload -> { put("action", "download"); put("url", a.url) }
                        ProposalAction.OpenAccessSettings -> put("action", "access")
                        ProposalAction.OpenLog -> put("action", "log")
                    }
                },
            )
        }
    }.toString()

    fun decode(json: String?): List<Proposal> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(json) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val kind = ProposalKind.entries.firstOrNull { it.name == s("kind") } ?: return@mapNotNull null
            val action = when (s("action")) {
                "download" -> s("url")?.takeIf { it.startsWith("https://github.com/") }?.let { ProposalAction.OpenDownload(it) }
                "access" -> ProposalAction.OpenAccessSettings
                "log" -> ProposalAction.OpenLog
                else -> null
            } ?: return@mapNotNull null
            Proposal(s("id") ?: return@mapNotNull null, kind, s("title") ?: return@mapNotNull null, s("detail").orEmpty(), action)
        }.take(NightlyReview.MAX_PROPOSALS)
    }
}
