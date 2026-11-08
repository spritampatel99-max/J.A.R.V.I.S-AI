package com.jarvis.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.setup.SetupStep

/** The guided, resumable setup. Each completed step is saved, so closing the app and reopening it continues where it stopped. */
@Composable
fun SetupWizard(setupVm: SetupViewModel, agentVm: AgentViewModel) {
    val ui by setupVm.ui.collectAsStateWithLifecycle()
    val step = ui.step

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { SetupHeader(ui) },
        bottomBar = { SetupFooter(ui, setupVm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ui.notice?.let { Notice(it, onDismiss = setupVm::dismissNotice) }
            if (ui.saveFailed) {
                Text(
                    "Your progress could not be saved on this phone. Setup will continue, but it may start over if the app is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep(ui.appVersion)
                    SetupStep.PRIVACY -> PrivacyStep(ui.setup.privacyAcknowledged, setupVm::setPrivacyAcknowledged)
                    SetupStep.DEVICE -> DeviceStep(ui, onRescan = setupVm::scan)
                    SetupStep.MODELS -> ModelsStep(ui, setupVm)
                    SetupStep.PROVIDERS -> ProvidersStep(agentVm)
                    SetupStep.FEATURES -> FeaturesStep(ui, setupVm)
                    SetupStep.STORAGE -> StorageStep(ui)
                    SetupStep.PERMISSIONS -> PermissionsStep(ui)
                    SetupStep.SUMMARY -> SummaryStep(ui, agentVm)
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(ui: SetupUiState) {
    val total = SetupStep.entries.size
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Step ${ui.step.ordinal + 1} of $total · ${ui.step.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            // Progress is the number of steps actually completed or skipped, never a timer.
            LinearProgressIndicator(progress = { ui.completedCount.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text(
                "${ui.completedCount} of $total steps done",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SetupFooter(ui: SetupUiState, vm: SetupViewModel) {
    val step = ui.step
    val canContinue = when (step) {
        SetupStep.PRIVACY -> ui.setup.privacyAcknowledged
        SetupStep.DEVICE -> ui.device != null && !ui.scanning
        SetupStep.MODELS -> ui.setup.modelChoice != null
        SetupStep.STORAGE -> ui.storage != null
        else -> true
    }
    val label = when (step) {
        SetupStep.WELCOME -> "Start setup"
        SetupStep.SUMMARY -> "Finish setup"
        else -> "Continue"
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step != SetupStep.WELCOME) OutlinedButton(onClick = vm::back) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step.optional) TextButton(onClick = { vm.skip(step) }) { Text("Skip for now") }
            Button(
                enabled = canContinue,
                onClick = { if (step == SetupStep.SUMMARY) vm.finish() else vm.complete(step) },
            ) { Text(label) }
        }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ---- Welcome ---------------------------------------------------------------------------------------

@Composable
private fun WelcomeStep(version: String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulsingRing(MaterialTheme.colorScheme.primary)
        Text("J.A.R.V.I.S.-AI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Your Personal AI Ecosystem", style = MaterialTheme.typography.titleMedium)
        Text(
            "An intelligent assistant built around your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(
            "Setup takes a few minutes. It looks at your phone, helps you choose what to install, and never downloads " +
                "anything until you say so. You can stop at any point; when you come back it continues from the same step.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "This is an early version: some features you will see listed are not built yet and are marked that way.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Version $version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Decorative only; it does not stand for any loading or progress. */
@Composable
private fun PulsingRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "ring")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Canvas(Modifier.size(120.dp)) {
        val radius = size.minDimension / 2 * scale
        drawCircle(color = color.copy(alpha = 0.25f), radius = radius)
        drawCircle(color = color, radius = radius * 0.7f, style = Stroke(width = 6.dp.toPx()))
        drawCircle(color = color, radius = radius * 0.25f)
    }
}

// ---- Privacy ---------------------------------------------------------------------------------------

@Composable
private fun PrivacyStep(acknowledged: Boolean, onAcknowledged: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Privacy", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        PrivacyBlock(
            "Stays on your phone",
            "Your settings, setup choices, the activity log and your API keys. Keys are encrypted with the Android Keystore. " +
                "There is no account, no analytics and no tracking, and nothing is sent to the developer.",
        )
        PrivacyBlock(
            "Device analysis",
            "Setup reads the phone model, Android version, memory, storage, battery level, connection type and which hardware " +
                "features exist (microphone, camera, Bluetooth, speech recognition). It does not read your contacts, accounts, " +
                "location, identifiers or the list of apps you have installed, and the result is not sent anywhere.",
        )
        PrivacyBlock(
            "Sent only if you add a cloud provider",
            "If you add a key for Gemini, Groq, OpenRouter, OpenAI or Claude, the messages you type and the conversation so far " +
                "go to that provider, so its own privacy policy applies. With no key, nothing is sent.",
        )
        PrivacyBlock(
            "Model downloads",
            "Checking a model's exact size or downloading it contacts huggingface.co, which can see your network address. " +
                "This happens only when you press the button or confirm the download.",
        )
        PrivacyBlock(
            "Your control",
            "Remove a key any time in the Cloud tab. Uninstalling the app deletes everything it stored; backups are switched off.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = onAcknowledged)
            Text("I have read this.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PrivacyBlock(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Providers (reuses the same screen as the Cloud tab) -----------------------------------------------

@Composable
private fun ProvidersStep(agentVm: AgentViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Cloud AI is optional. Add a key for any provider you want to use; leave the rest empty. " +
                "You can skip this step and add keys later in the Cloud tab.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        ProvidersScreen(agentVm, showTitle = false)
    }
}

// ---- Summary -----------------------------------------------------------------------------------------------

@Composable
private fun SummaryStep(ui: SetupUiState, agentVm: AgentViewModel) {
    val settings by agentVm.settingsState.collectAsStateWithLifecycle()
    val configured = settings.providers.count { it.configured }
    val choice = ui.setup.modelChoice

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Summary", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "This is what is true right now, not what is planned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SummaryRow(
            "Cloud AI",
            if (configured > 0) "$configured provider(s) ready" else "No provider key added yet. Built-in commands (flashlight, alarm, YouTube) still work; free-form chat needs a key. Add one in the Cloud tab.",
        )
        SummaryRow(
            "Local AI (on-device)",
            "Not ready for chat. " + when (choice) {
                null, ModelChoice.SKIP -> "No model chosen. You can install one later in the Models tab."
                else -> "You chose ${choice.models.joinToString(" and ") { com.jarvis.ai.models.ModelCatalog.spec(it).displayName }}. " +
                    "Setup downloads nothing: open the Models tab and tap Download when you are ready. " +
                    "Chatting with an installed model needs the on-device engine, which is not built yet."
            },
        )
        SummaryRow("Offline AI", "Not ready. It is marked ready only after a model has been downloaded, verified and test-loaded.")
        SummaryRow(
            "Features",
            "${ui.resolution.explicit.size} chosen" +
                if (ui.resolution.implied.isNotEmpty()) ", ${ui.resolution.implied.size} added because others need them" else "",
        )
        ui.storage?.let { SummaryRow("Storage", storageVerdictText(it)) }
        Text(
            "Press Finish to open the assistant. You can run setup again from the Cloud tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SummaryRow(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
