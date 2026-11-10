package com.jarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.AppContainer
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.core.security.Risk

/** A phrase that the built-in matcher turns into this tool. "TRY" only fills the chat box; nothing runs until the person sends it. */
private val EXAMPLES = mapOf(
    "flashlight" to "Turn on the flashlight",
    "set_alarm" to "Set an alarm for 7 AM",
    "open_url" to "Open example.com",
    "open_youtube" to "Open YouTube",
    "get_device_info" to "How much battery do I have",
    "call_contact" to "Call ",
    "call_number" to "Call ",
    "answer_call" to "Answer the call",
    "end_call" to "Hang up",
)

private val QUICK = listOf(
    Triple("Flashlight", Glyph.FLASHLIGHT, "Turn on the flashlight"),
    Triple("Alarm", Glyph.CLOCK, "Set an alarm for 7 AM"),
    Triple("Battery", Glyph.BOLT, "How much battery do I have"),
    Triple("Call", Glyph.CALL, "Call "),
)

/**
 * Tools: the real list of what the assistant can do, read from the tool registry, with each tool's risk level and whether
 * Android currently lets it run. Nothing on this page is a switch for safety: sensitive actions always ask first.
 */
@Composable
fun ToolsScreen(container: AppContainer, onTry: (String) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val tools = remember { container.registry.all() }
    val missing = remember(refresh) { tools.associate { it.spec.name to container.permissions.missing(it.spec.requiredPermissions) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppearIn {
            GlassCard(accent = JarvisColors.Cyan) {
                Text("TOOLS", style = MaterialTheme.typography.labelMedium, color = JarvisColors.Cyan)
                Text("${tools.size} things I can do on this phone", style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink)
                Text(
                    "Everything here runs through the same checks: the request is validated, Android's permission is checked, and " +
                        "anything sensitive waits for your tap before it happens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisColors.Muted,
                )
            }
        }

        SectionTitle(Glyph.BOLT, "Quick commands", "tap to fill in")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QUICK.take(2).forEach { (label, glyph, text) -> QuickTile(label, glyph, Modifier.weight(1f)) { onTry(text) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QUICK.drop(2).forEach { (label, glyph, text) -> QuickTile(label, glyph, Modifier.weight(1f)) { onTry(text) } }
        }

        SectionTitle(Glyph.TOOLS, "Available tools")
        tools.forEachIndexed { index, tool ->
            AppearIn(delayMillis = 60 + index * 45) {
                ToolCard(tool, missing[tool.spec.name].orEmpty(), onTry = EXAMPLES[tool.spec.name]?.let { text -> { onTry(text) } })
            }
        }

        AppearIn {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlyphIcon(Glyph.SHIELD, JarvisColors.Cyan, size = 22.dp)
                    Text("Confirmation gate", style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
                    StatusPill("ALWAYS ON", JarvisColors.Good)
                }
                Text(
                    "High-risk actions, such as placing a call, always show you the exact name and number and wait for you to tap " +
                        "Confirm. This cannot be switched off, and neither a spoken word nor the AI can answer it for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JarvisColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun QuickTile(label: String, glyph: Glyph, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickableTile(onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.size(40.dp).clip(CircleShape).background(JarvisColors.Cyan.copy(alpha = 0.14f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GlyphIcon(glyph, JarvisColors.Cyan, size = 20.dp)
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
    }
}

private fun Modifier.clickableTile(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))

@Composable
private fun ToolCard(tool: Tool, missingPermissions: Set<String>, onTry: (() -> Unit)?) {
    val spec = tool.spec
    val riskColor = when (spec.risk) {
        Risk.HIGH -> JarvisColors.Magenta
        Risk.MEDIUM -> JarvisColors.Warning
        else -> JarvisColors.Good
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title(spec.name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
            StatusPill(spec.risk.name, riskColor)
        }
        Text(spec.description, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                spec.requiredPermissions.isEmpty() -> StatusPill("No permission needed", JarvisColors.Muted)
                missingPermissions.isEmpty() -> StatusPill("Allowed by Android", JarvisColors.Good)
                else -> StatusPill("Needs access: " + missingPermissions.joinToString { it.substringAfterLast('.').lowercase().replace('_', ' ') }, JarvisColors.Warning)
            }
            if (onTry != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                GlassChip("TRY", onClick = onTry, selected = true)
            }
        }
    }
}

private fun title(name: String): String = name.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
