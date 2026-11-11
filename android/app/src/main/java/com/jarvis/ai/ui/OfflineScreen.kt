package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.AppContainer
import com.jarvis.ai.offline.CapabilityState
import com.jarvis.ai.offline.SelfTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What works without internet, read from this phone just now. The storage check really writes and reads two scratch files. */
@Composable
fun OfflineScreen(container: AppContainer) {
    var selfTest by remember { mutableStateOf<List<SelfTestResult>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val report = remember(selfTest, refresh) { container.offlineReport(selfTest) }

    PageColumn {
        GlassCard(accent = if (report.aiAnswersPossible) JarvisColors.Good else JarvisColors.Warning) {
            Text(report.headline, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        }
        GlowButton(if (running) "Checking..." else "Check storage now", enabled = !running, modifier = Modifier.fillMaxWidth(), onClick = {
            running = true
            scope.launch {
                selfTest = withContext(Dispatchers.IO) { container.runOfflineSelfTest() }
                running = false
            }
        })
        MutedText("This saves a test note to two temporary files and reads it back, then deletes them. It does not touch your planner or memory.")
        selfTest?.forEach { r ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name, Modifier.weight(1f), color = JarvisColors.Ink)
                    StatusPill(if (r.passed) "Passed" else "Failed", if (r.passed) JarvisColors.Good else JarvisColors.Coral)
                }
                if (!r.passed) Text(r.detail, color = JarvisColors.Coral, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionTitle(Glyph.GLOBE, "What works", "right now")
        for (c in report.capabilities) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(c.name, Modifier.weight(1f), color = JarvisColors.Ink, style = MaterialTheme.typography.titleSmall)
                    StatusPill(
                        c.state.label,
                        when (c.state) {
                            CapabilityState.WORKS -> JarvisColors.Good
                            CapabilityState.LIMITED -> JarvisColors.Warning
                            CapabilityState.UNAVAILABLE -> JarvisColors.Coral
                            CapabilityState.NOT_BUILT -> JarvisColors.Muted
                        },
                    )
                }
                MutedText(c.detail)
            }
        }
    }
}
