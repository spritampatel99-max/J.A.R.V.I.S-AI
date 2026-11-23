package com.jarvis.ai.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.ai.AppContainer
import com.jarvis.ai.update.NightlyReview
import com.jarvis.ai.update.ProposalAction
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Prefs > Nightly review. At 10 pm JARVIS looks at its own log and Android's settings (and, every 10 days or when you ask, the public release
 * page), writes down suggestions and notifies you. Nothing is changed for you: each suggestion has "Apply", which only opens a page or a
 * settings screen, and "Leave as it is", which stops that suggestion coming back.
 */
@Composable
fun NightlyReviewScreen(container: AppContainer, onNavigate: (SubPage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings
    var on by remember { mutableStateOf(settings.nightlyOn) }
    var proposals by remember { mutableStateOf(NightlyReview.decode(settings.nightlyProposals)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastRun by remember { mutableStateOf(settings.nightlyLastRunMs) }
    var lastCheck by remember { mutableStateOf(settings.updateLastCheckMs) }

    fun stamp(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    PageColumn {
        SectionTitle(Glyph.BELL, "Nightly review", if (on) "on" else "off")
        GlassCard {
            SettingRow(
                "Review every night at 10 pm",
                "JARVIS looks at which of its actions failed today, at Android settings that stop reminders and automations from running, and " +
                    "(every 10 days) at whether a newer version has been published. Then it notifies you. It changes nothing on its own.",
            ) {
                SpectrumSwitch(on, onChange = { value ->
                    on = value
                    settings.nightlyOn = value
                    lastRun = settings.nightlyLastRunMs
                    container.schedule.scheduleNext()
                })
            }
            MutedText("The only thing it ever sends out is a request for this project's public \"latest release\" page. Your messages, screen and notification text are not read for this, not logged and not sent anywhere.")
            if (lastRun > 0) MutedText("Last review: ${stamp(lastRun)}.")
            MutedText("Last look for a new version: ${lastCheck?.let { stamp(it) } ?: "never"}.")
            GlowButton(if (busy) "Checking..." else "Check for updates now", enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        proposals = container.nightlyReview.review(System.currentTimeMillis(), forceCheck = true)
                        lastRun = settings.nightlyLastRunMs
                        lastCheck = settings.updateLastCheckMs
                        status = if (proposals.isEmpty()) "Nothing to change right now." else null
                    } finally {
                        busy = false
                    }
                }
            })
            status?.let { MutedText(it) }
        }

        SectionTitle(Glyph.INFO, "Suggestions", "${proposals.size}")
        if (proposals.isEmpty()) {
            GlassCard { MutedText("Nothing waiting. Suggestions appear here after a review.") }
        }
        for (proposal in proposals) {
            GlassCard {
                Text(proposal.title, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                MutedText(proposal.detail)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (val action = proposal.action) {
                        is ProposalAction.OpenDownload -> GlowButton("Apply: open download page", onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: ActivityNotFoundException) {
                                status = "No app on this phone can open the download page."
                            }
                        })
                        ProposalAction.OpenAccessSettings -> GlowButton("Apply: open access settings", onClick = { onNavigate(SubPage.ACCESS) })
                        ProposalAction.OpenLog -> MutedText("See the Logs tab.")
                    }
                    GlowButton("Leave as it is", subtle = true, onClick = {
                        settings.leftAsIs = settings.leftAsIs + proposal.id
                        proposals = proposals.filterNot { it.id == proposal.id }
                        settings.nightlyProposals = NightlyReview.encode(proposals)
                    })
                }
            }
        }

        SectionTitle(Glyph.SHIELD, "What this cannot do")
        GlassCard {
            MutedText("An app cannot rewrite its own code, and Android does not let one install a new version by itself. A new version is a new file that you tap to install; Android asks you to confirm.")
            MutedText("\"Leave as it is\" is remembered, so that suggestion does not come back. Suggestions are made from counts and settings only; they cannot be made by anything written in a message, web page or notification.")
        }
    }
}
