package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.AppContainer
import com.jarvis.ai.security.Severity

/**
 * A review of this app's own settings and saved data. It says what it looked at and what it did not, and it never calls the phone
 * "secure": it cannot see other apps, Android's own settings or the network.
 */
@Composable
fun SecurityScreen(container: AppContainer) {
    var report by remember { mutableStateOf(container.securityReport()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { report = container.securityReport() }

    PageColumn {
        GlassCard(accent = if (report.warnings > 0) JarvisColors.Coral else if (report.attention > 0) JarvisColors.Warning else JarvisColors.Good) {
            Text(
                when {
                    report.warnings > 0 -> "${report.warnings} thing(s) to fix"
                    report.attention > 0 -> "${report.attention} thing(s) worth a look"
                    else -> "Nothing found by these checks"
                },
                style = MaterialTheme.typography.titleMedium,
                color = JarvisColors.Ink,
            )
            MutedText("This covers only what is listed under \"What was checked\". It is not a virus scan and does not say the phone is secure.")
        }
        GlowButton("Check again", subtle = true, modifier = Modifier.fillMaxWidth(), onClick = { report = container.securityReport() })

        for (f in report.findings) {
            GlassCard(accent = when (f.severity) { Severity.WARNING -> JarvisColors.Coral; Severity.ATTENTION -> JarvisColors.Warning; Severity.INFO -> null }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                    StatusPill(
                        f.severity.label,
                        when (f.severity) { Severity.WARNING -> JarvisColors.Coral; Severity.ATTENTION -> JarvisColors.Warning; Severity.INFO -> JarvisColors.Cyan },
                    )
                }
                Text(f.detail, color = JarvisColors.Ink, style = MaterialTheme.typography.bodyMedium)
                f.action?.let { MutedText("What you can do: $it") }
            }
        }

        SectionTitle(Glyph.SHIELD, "What was checked", null)
        GlassCard { for (c in report.checked) MutedText("- $c") }
        SectionTitle(Glyph.WARNING, "What this does not check", null)
        GlassCard { for (c in report.notChecked) MutedText("- $c") }
    }
}
