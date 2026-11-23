package com.jarvis.ai.android.schedule

import com.jarvis.ai.automation.HealthFinding
import com.jarvis.ai.core.security.AuditLog
import com.jarvis.ai.data.SettingsStore
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.update.NightlyClock
import com.jarvis.ai.update.NightlyHook
import com.jarvis.ai.update.NightlyReview
import com.jarvis.ai.update.Proposal
import com.jarvis.ai.update.ReleaseClient
import com.jarvis.ai.update.ReleaseStore
import com.jarvis.ai.update.ReviewInputs
import com.jarvis.ai.update.UpdateOutcome
import com.jarvis.ai.update.UpdateRules
import java.time.ZoneId
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The 10 pm review. It reads the app's own log (tool names and counts only), Android's schedule settings and, at most every 10 days or when
 * asked, the public release page; writes the suggestions down; and notifies. It never changes a setting, never downloads and never installs:
 * "Apply" in the app only opens a page or a settings screen for the person.
 */
class NightlyReviewJob(
    private val settings: SettingsStore,
    private val audit: AuditLog,
    private val releases: ReleaseClient,
    private val notifier: Notifier,
    private val appVersion: String,
    private val findings: () -> List<HealthFinding>,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : NightlyHook {

    override fun nextMs(nowMs: Long): Long? = if (settings.nightlyOn) NightlyClock.nextRunMs(nowMs, zone()) else null

    override suspend fun runIfDue(nowMs: Long) {
        if (!settings.nightlyOn) return
        if (!NightlyClock.due(nowMs, zone(), settings.nightlyLastRunMs)) return
        val proposals = review(nowMs, forceCheck = false)
        notifier.notify("nightly-review", NightlyReview.notificationTitle(proposals), NightlyReview.notificationText(proposals))
    }

    /** Builds and stores the suggestions. [forceCheck] looks for a release now instead of waiting for the 10-day interval. */
    suspend fun review(nowMs: Long, forceCheck: Boolean): List<Proposal> {
        if (forceCheck || UpdateRules.checkDue(settings.updateLastCheckMs, nowMs)) {
            // Short, because this can run inside an alarm wake-up that Android only keeps alive for a few seconds.
            when (val outcome = withTimeoutOrNull(CHECK_TIMEOUT_MS) { releases.latest() }) {
                is UpdateOutcome.Latest -> {
                    settings.knownRelease = ReleaseStore.encode(outcome.release)
                    settings.updateLastCheckMs = nowMs
                }
                UpdateOutcome.NoRelease -> {
                    settings.knownRelease = null
                    settings.updateLastCheckMs = nowMs
                }
                else -> Unit // Could not look: try again at the next review.
            }
        }
        val proposals = NightlyReview.build(
            ReviewInputs(
                nowMs = nowMs,
                currentVersion = appVersion,
                newerRelease = UpdateRules.newer(appVersion, ReleaseStore.decode(settings.knownRelease)),
                auditEvents = audit.recent(AUDIT_LOOKBACK),
                scheduleFindings = findings(),
                leftAsIs = settings.leftAsIs,
            ),
        )
        settings.nightlyProposals = NightlyReview.encode(proposals)
        settings.nightlyLastRunMs = nowMs
        return proposals
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 6_000L
        const val AUDIT_LOOKBACK = 500
    }
}
