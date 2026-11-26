package com.jarvis.ai.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.AppContainer
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.datausage.ByteSize
import com.jarvis.ai.datausage.DataUsageResult
import com.jarvis.ai.datausage.UsagePeriod
import com.jarvis.ai.datausage.UsagePeriods
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How much mobile data the phone and each app used today, this week or this month, from Android's own counters. It needs "Usage access", which
 * the person turns on in Settings; until then this page says so and shows no numbers. Wi-Fi is not counted.
 */
@Composable
fun DataMonitorScreen(container: AppContainer) {
    val context = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(UsagePeriod.TODAY) }
    var result by remember { mutableStateOf<DataUsageResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Coming back from Settings: look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck++ }

    val allowed = remember(recheck) { container.dataUsage.hasAccess() }

    LaunchedEffect(period, recheck, allowed) {
        if (!allowed) {
            result = null
            return@LaunchedEffect
        }
        loading = true
        val range = UsagePeriods.range(period, System.currentTimeMillis(), ZoneId.systemDefault())
        result = withContext(Dispatchers.IO) { container.dataUsage.read(period, range, appLimit = 15) }
        loading = false
    }

    PageColumn {
        SectionTitle(Glyph.WAVE, "Mobile data", "from Android's counters")

        if (!allowed) {
            GlassCard {
                Text("Android needs your OK before an app can see data counts.", color = JarvisColors.Ink)
                MutedText(
                    "Open Usage access, find JARVIS 3.0 in the list and switch it on. Android uses this one switch for data counts and screen time; " +
                        "Jarvis reads only how many bytes each app used on mobile data.",
                )
                GlowButton("Open Usage access", onClick = {
                    val intent = AccessChecks.settingsIntent(context, AccessKey.USAGE_ACCESS)
                    if (intent != null) {
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            // Some phones hide this page; the person can search "Usage access" in Settings.
                        }
                    }
                }, modifier = Modifier.fillMaxWidth())
                GlowButton("Check again", subtle = true, onClick = { recheck++ }, modifier = Modifier.fillMaxWidth())
            }
            return@PageColumn
        }

        ChoiceChips(UsagePeriod.entries, period, { it.label.replaceFirstChar { c -> c.uppercase() } }, { period = it })

        when (val r = result) {
            null -> MutedText(if (loading) "Reading Android's counters..." else "")
            DataUsageResult.NeedsAccess -> GlassCard(accent = JarvisColors.Warning) {
                Text("Android no longer allows this. Turn Usage access on again.", color = JarvisColors.Warning)
            }
            is DataUsageResult.Unavailable -> GlassCard(accent = JarvisColors.Warning) { Text(r.reason, color = JarvisColors.Warning) }
            is DataUsageResult.Report -> {
                val report = r.report
                GlassCard {
                    Text(ByteSize.format(report.totalBytes), style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink)
                    MutedText("${ByteSize.format(report.rxBytes)} down, ${ByteSize.format(report.txBytes)} up, mobile data ${report.period.label}")
                }
                if (report.apps.isEmpty()) {
                    MutedText("No app used mobile data in this time.")
                } else {
                    val biggest = report.apps.maxOf { it.totalBytes }.coerceAtLeast(1)
                    GlassCard {
                        for (app in report.apps) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(app.label, Modifier.weight(1f), color = JarvisColors.Ink)
                                    MutedText(ByteSize.format(app.totalBytes))
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(JarvisColors.SwitchOff),
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth((app.totalBytes.toFloat() / biggest).coerceIn(0.02f, 1f)).height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)).background(JarvisColors.Cyan),
                                    )
                                }
                            }
                        }
                    }
                }
                MutedText(
                    "These are Android's own counters for this phone, with both SIMs together. Your mobile operator's figure can differ, " +
                        "and Wi-Fi is not counted. Apps this app cannot name are shown as \"Other app\".",
                )
            }
        }
    }
}
