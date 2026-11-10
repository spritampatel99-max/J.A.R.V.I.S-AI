package com.jarvis.ai.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessKind
import com.jarvis.ai.android.device.AccessState

/** The Access tab: what the app can be allowed to do, with the real state read from Android. */
@Composable
fun AccessScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AccessList()
    }
}

/**
 * The list itself, without its own scrolling, so it can sit inside the setup wizard as well.
 *
 * Android has no switch that grants everything at once, and an app cannot enable special access on its own. "Allow all"
 * therefore walks through the items one after another: Android's own dialog for each normal permission, and the right
 * Settings page for each special one. Every step can be declined, and nothing is granted without the user's tap.
 */
@Composable
fun AccessList() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val states = remember(refresh) { AccessKey.entries.associateWith { AccessChecks.state(context, it) } }
    val blocked = remember(refresh) { AccessKey.entries.filter { AccessChecks.isBlocked(context, it) }.toSet() }

    var queue by remember { mutableStateOf(emptyList<AccessKey>()) }
    val advance = remember { mutableStateOf<() -> Unit>({}) }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        advance.value()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
        advance.value()
    }

    /** Starts the request for one item. @return false when nothing could be started, so the walk can move on. */
    fun launchFor(key: AccessKey): Boolean {
        val permission = AccessChecks.runtimePermission(key)
        val page = AccessChecks.settingsIntent(context, key)
        return when {
            key.kind == AccessKind.UNAVAILABLE -> false
            key.kind == AccessKind.RUNTIME && AccessChecks.isBlocked(context, key) -> {
                settingsLauncher.launch(AccessChecks.appSettingsIntent(context))
                true
            }
            key.kind == AccessKind.RUNTIME && permission != null -> {
                AccessChecks.markAsked(context, key)
                runtimeLauncher.launch(permission)
                true
            }
            page == null -> false
            else -> try {
                settingsLauncher.launch(page)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }

    fun advanceWalk() {
        while (queue.isNotEmpty()) {
            val next = queue.first()
            queue = queue.drop(1)
            if (AccessChecks.state(context, next) != AccessState.NOT_ALLOWED) continue
            if (launchFor(next)) return
        }
    }
    SideEffect { advance.value = { advanceWalk() } }

    val requestable = AccessKey.entries.filter { it.kind != AccessKind.UNAVAILABLE }
    val allowed = requestable.count { states[it] != AccessState.NOT_ALLOWED }
    val missing = requestable.filter { states[it] == AccessState.NOT_ALLOWED }

    AppearIn {
        GlassCard {
            Text("$allowed of ${requestable.size} allowed", style = MaterialTheme.typography.titleMedium)
            Text(
                "J.A.R.V.I.S. asks Android for real access here, and shows what Android actually says. " +
                    "Android has no single switch that grants everything, so \"Allow all\" goes through the items one by one. " +
                    "You can decline any of them, and everything you allow can be turned off again in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlowButton(
                text = if (missing.isEmpty()) "Everything is allowed" else "Allow all (${missing.size})",
                enabled = missing.isNotEmpty() && queue.isEmpty(),
                onClick = {
                    queue = missing
                    advanceWalk()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    AccessKey.entries.forEachIndexed { index, key ->
        AppearIn(delayMillis = 60 + index * 55) {
            AccessRow(
                key = key,
                state = states.getValue(key),
                blocked = key in blocked,
                onClick = {
                    when {
                        states[key] != AccessState.NOT_ALLOWED -> {
                            val page = AccessChecks.settingsIntent(context, key) ?: AccessChecks.appSettingsIntent(context)
                            try {
                                settingsLauncher.launch(page)
                            } catch (e: ActivityNotFoundException) {
                                settingsLauncher.launch(AccessChecks.appSettingsIntent(context))
                            }
                        }
                        else -> launchFor(key)
                    }
                },
            )
        }
    }
}

@Composable
private fun AccessRow(key: AccessKey, state: AccessState, blocked: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GlassCard(accent = if (state == AccessState.NOT_ALLOWED) colors.outline else JarvisColors.Good) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(key.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                key.kind == AccessKind.UNAVAILABLE -> StatusPill("Not available", colors.onSurfaceVariant)
                state == AccessState.ALLOWED -> StatusPill("Allowed", JarvisColors.Good)
                state == AccessState.NOT_NEEDED -> StatusPill("Automatic", JarvisColors.Good)
                blocked -> StatusPill("Blocked", colors.error)
                else -> StatusPill("Off", JarvisColors.Warning)
            }
        }
        Text(key.purpose, style = MaterialTheme.typography.bodyMedium)
        Text("What it reaches: ${key.reach}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text("Turn off later: ${key.revoke}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(if (key.activeNow) "Used by the app now" else "Not used yet", if (key.activeNow) colors.primary else colors.onSurfaceVariant)
        }
        if (key.kind != AccessKind.UNAVAILABLE && state != AccessState.NOT_NEEDED) {
            val label = when {
                state != AccessState.NOT_ALLOWED -> "Manage in Settings"
                blocked || key.kind == AccessKind.SPECIAL -> "Open Settings"
                else -> "Allow"
            }
            GlowButton(label, onClick = onClick, subtle = state != AccessState.NOT_ALLOWED)
        }
    }
}
