package com.jarvis.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.features.Feature
import com.jarvis.ai.features.FeatureCatalog
import com.jarvis.ai.features.FeatureStatus
import com.jarvis.ai.features.FeatureStatusCalculator
import com.jarvis.ai.features.InternetNeed
import com.jarvis.ai.features.OfflineSupport
import com.jarvis.ai.features.PermissionCatalog
import com.jarvis.ai.features.PermissionKind
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.features.Readiness
import com.jarvis.ai.models.Level
import com.jarvis.ai.models.ModelAdvisor
import com.jarvis.ai.models.ModelAssessment
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.Verdict
import com.jarvis.ai.setup.StorageReview
import com.jarvis.ai.setup.StorageVerdict

// ---- shared bits -------------------------------------------------------------------------------------------

@Composable
private fun StepScroll(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun StepTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

// ---- Device analysis --------------------------------------------------------------------------------------

@Composable
internal fun DeviceStep(ui: SetupUiState, onRescan: () -> Unit) {
    StepScroll {
        StepTitle(
            "Device analysis",
            "Read from this phone. Each value says whether it was measured, is an estimate, or could not be read. Nothing here leaves the phone.",
        )
        val d = ui.device
        if (d == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Reading device information…", style = MaterialTheme.typography.bodySmall)
        } else {
            DeviceReport(d)
        }
        OutlinedButton(onClick = onRescan, enabled = !ui.scanning) { Text(if (ui.scanning) "Scanning…" else "Scan again") }
    }
}

@Composable
private fun DeviceReport(d: DeviceProfile) {
    ReportSection("Device") {
        PlainRow("Device", "${d.manufacturer} ${d.model}")
        PlainRow("Android", "${d.androidRelease} (API ${d.apiLevel})")
        PlainRow("Architecture", d.supportedAbis.joinToString().ifEmpty { "not reported" })
        ReadingRow("Processor cores", d.cpuCores.text { it.toString() })
    }
    ReportSection("Memory") {
        ReadingRow("Total RAM", d.totalRamBytes.text(ByteFormat::format))
        ReadingRow("Available RAM", d.availableRamBytes.text(ByteFormat::format))
        ReadingRow("Low-memory state", d.lowMemory.text { yesNo(it) })
        ReadingRow("App memory class", d.appMemoryClassMb.text { "$it MB" })
        ReadingRow("Large memory class", d.largeMemoryClassMb.text { "$it MB" })
    }
    ReportSection("Storage") {
        ReadingRow("Total storage", d.totalStorageBytes.text(ByteFormat::format))
        ReadingRow("Free storage", d.freeStorageBytes.text(ByteFormat::format))
    }
    ReportSection("Graphics and AI hardware") {
        ReadingRow("OpenGL ES", d.openGlEsVersion.text { it })
        ReadingRow("Vulkan", d.vulkanSupported.text { yesNo(it) })
        ReadingRow("CPU inference", d.cpuInference.text { yesNo(it) })
        ReadingRow("GPU acceleration", d.gpuAcceleration.text { yesNo(it) })
        ReadingRow("NPU acceleration", d.npuAcceleration.text { yesNo(it) })
    }
    ReportSection("Battery") {
        ReadingRow("Battery", d.batteryPercent.text { "$it%" })
        ReadingRow("Charging", d.charging.text { yesNo(it) })
        ReadingRow("Unrestricted battery use", d.ignoringBatteryOptimizations.text { yesNo(it) })
    }
    ReportSection("Network") {
        ReadingRow("Online", d.online.text { yesNo(it) })
        ReadingRow("Connection", d.connection.text { it.label() })
        ReadingRow("Metered", d.metered.text { yesNo(it) })
    }
    ReportSection("Voice and hardware") {
        ReadingRow("Microphone", d.microphone.text { yesNo(it) })
        ReadingRow("Camera", d.camera.text { yesNo(it) })
        ReadingRow("Bluetooth", d.bluetooth.text { yesNo(it) })
        ReadingRow("Audio output", d.audioOutput.text { yesNo(it) })
        ReadingRow("Speech recognition", d.speechRecognition.text { yesNo(it) })
        ReadingRow("On-device speech recognition", d.onDeviceSpeechRecognition.text { yesNo(it) })
        ReadingRow("Text-to-speech", d.textToSpeech.text { yesNo(it) })
    }
}

@Composable
private fun ReportSection(title: String, rows: @Composable () -> Unit) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleSmall)
        rows()
    }
}

@Composable
private fun PlainRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ReadingRow(label: String, reading: ReadingText) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                "${reading.value} · ${reading.tag}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (reading.tag == "unavailable") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        reading.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ---- Models -----------------------------------------------------------------------------------------------

@Composable
internal fun ModelsStep(ui: SetupUiState, vm: SetupViewModel) {
    StepScroll {
        StepTitle(
            "Local AI model",
            "Models run on this phone with no computer or server. Nothing is downloaded now: you are only choosing. " +
                "You download a model yourself from the Models tab, with a clear yes, once setup is finished. " +
                "Chatting with an installed model needs the on-device engine, which is the next step.",
        )
        val recommendation = ui.recommendation
        if (recommendation == null) {
            Text("Waiting for the device analysis…", style = MaterialTheme.typography.bodySmall)
        } else {
            Panel {
                Text("Recommendation", style = MaterialTheme.typography.titleSmall)
                Text(recommendation.explanation, style = MaterialTheme.typography.bodyMedium)
                Text(ModelAdvisor.CONFIRMATION_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val anyChecking = ui.sizeChecks.values.any { it is SizeCheck.Checking }
        OutlinedButton(onClick = vm::checkExactSizes, enabled = !anyChecking) {
            Text(if (anyChecking) "Checking…" else "Check exact sizes online")
        }
        Text(
            "Contacts huggingface.co to read the real file size and checksum. Sizes shown as \"about\" are estimates until you do.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (spec in ModelCatalog.all) {
            val assessment = recommendation?.assessments?.firstOrNull { it.info.spec.id == spec.id }
            ModelCard(ui, spec.id, assessment, recommended = recommendation?.recommended == spec.id)
        }

        Text("What would you like to do?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        val choices = listOf(
            ModelChoice.LIGHT_ONLY to "Gemma 3 1B only",
            ModelChoice.ADVANCED_ONLY to "Gemma 4 E2B only",
            ModelChoice.BOTH to "Both models",
            ModelChoice.SKIP to "Skip for now and finish the rest of setup",
        )
        for ((choice, label) in choices) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = ui.setup.modelChoice == choice, role = Role.RadioButton, onClick = { vm.chooseModel(choice) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.setup.modelChoice == choice, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            "The app stays usable without a local model; cloud AI and the phone tools do not need one.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelCard(ui: SetupUiState, id: ModelId, assessment: ModelAssessment?, recommended: Boolean) {
    val spec = ModelCatalog.spec(id)
    val info = ui.models.getValue(id)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                Text(spec.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recommended) Text("Suggested start", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text("Good for: " + spec.bestFor.joinToString("; "), style = MaterialTheme.typography.bodySmall)
        Text(spec.capabilityNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        PlainRow("Download size", info.size.display())
        info.size.detail()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        PlainRow("Installed storage", info.size.display())
        assessment?.ramEstimateBytes?.let { PlainRow("Memory (rule of thumb)", "about ${ByteFormat.format(it)}") }
        PlainRow("Runtime", "LiteRT-LM (on this phone)")
        PlainRow("Offline once installed", "Yes")
        PlainRow("Source", "${spec.host}/${spec.repo}")
        when (val check = ui.sizeChecks[id]) {
            SizeCheck.Checking -> Text("Checking…", style = MaterialTheme.typography.labelSmall)
            SizeCheck.Verified -> Text("Exact size and checksum read from the repository.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            is SizeCheck.Failed -> Text("Could not check: ${check.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }

        if (assessment != null) {
            HorizontalDivider()
            Text(verdictText(assessment.verdict), style = MaterialTheme.typography.titleSmall, color = verdictColor(assessment.verdict))
            for (f in assessment.findings) {
                Text(
                    "${levelMark(f.level)} ${f.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (f.level == Level.PROBLEM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            for (advice in assessment.downloadAdvice) {
                Text("Before downloading: $advice", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun verdictText(v: Verdict): String = when (v) {
    Verdict.LOOKS_SUITABLE -> "Looks suitable (confirmed only by a test load)"
    Verdict.USE_WITH_CAUTION -> "Possible, with warnings"
    Verdict.NOT_RECOMMENDED -> "Not recommended on this phone"
    Verdict.CANNOT_ASSESS -> "Cannot be judged yet"
}

@Composable
private fun verdictColor(v: Verdict) = when (v) {
    Verdict.LOOKS_SUITABLE -> MaterialTheme.colorScheme.primary
    Verdict.NOT_RECOMMENDED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun levelMark(level: Level): String = when (level) {
    Level.OK -> "OK:"
    Level.WARNING -> "Warning:"
    Level.PROBLEM -> "Problem:"
    Level.INFO -> "Note:"
}

// ---- Features ---------------------------------------------------------------------------------------------

@Composable
internal fun FeaturesStep(ui: SetupUiState, vm: SetupViewModel) {
    StepScroll {
        StepTitle(
            "Features",
            "Choose what you want. Nothing is switched on for you. Features that are not built yet are listed so you can see what is coming, but they cannot be selected.",
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in ProfilePreset.entries) {
                OutlinedButton(onClick = { vm.applyPreset(preset) }) { Text(preset.title) }
            }
            OutlinedButton(onClick = vm::selectAllFeatures) { Text("Select all available") }
            OutlinedButton(onClick = vm::deselectAllFeatures) { Text("Deselect all") }
        }
        ui.setup.preset?.let {
            Text("${it.title}: ${it.description} You can still change any choice.", style = MaterialTheme.typography.labelSmall)
        } ?: Text("Custom selection.", style = MaterialTheme.typography.labelSmall)

        for (feature in FeatureCatalog.all) FeatureCard(feature, ui, vm)
    }
}

@Composable
private fun FeatureCard(feature: Feature, ui: SetupUiState, vm: SetupViewModel) {
    val chosen = feature.id in ui.setup.features
    val impliedBy = ui.resolution.implied[feature.id]
    var expanded by rememberSaveable(feature.id) { mutableStateOf(false) }
    // Runtime permissions only matter for features that exist; no available feature needs one yet (Phase 6 adds a real reader).
    val statuses = FeatureStatusCalculator.statuses(feature, chosen, ui.device, emptySet(), emptySet())

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = chosen || impliedBy != null,
                onCheckedChange = { vm.setFeature(feature.id, it) },
                enabled = feature.selectable && impliedBy == null,
            )
            Column(Modifier.weight(1f)) {
                Text(feature.title, style = MaterialTheme.typography.titleSmall)
                Text(statusLine(statuses, feature), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
        }
        Text(feature.description, style = MaterialTheme.typography.bodySmall)
        if (impliedBy != null) {
            Text(
                "Added because ${impliedBy.joinToString { FeatureCatalog.feature(it).title }} needs it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) FeatureDetails(feature)
    }
}

@Composable
private fun FeatureDetails(feature: Feature) {
    HorizontalDivider()
    PlainRow("Internet", when (feature.internet) {
        InternetNeed.NONE -> "Not needed"
        InternetNeed.OPTIONAL -> "Optional"
        InternetNeed.REQUIRED -> "Required"
    })
    PlainRow("Works offline", when (feature.offline) {
        OfflineSupport.FULL -> "Yes"
        OfflineSupport.PARTIAL -> "Partly"
        OfflineSupport.NONE -> "No"
    })
    PlainRow("Extra download", feature.extraDownload.display())
    PlainRow("Extra installed storage", feature.extraStorage.display())
    feature.ramNote?.let { PlainRow("Memory", it) }
    PlainRow(
        "Needs",
        feature.requires.joinToString { FeatureCatalog.feature(it).title }.ifEmpty { "nothing else" },
    )
    PlainRow(
        "Permissions",
        feature.permissions.joinToString { PermissionCatalog.info(it.permission).title + if (it.required) "" else " (optional)" }.ifEmpty { "none" },
    )
    if (feature.capabilities.isNotEmpty()) PlainRow("Shared parts", feature.capabilities.joinToString { it.label })
}

private fun statusLine(statuses: Set<FeatureStatus>, feature: Feature): String {
    val r = feature.readiness
    if (r is Readiness.Planned) return "Not built yet · planned for Phase ${r.phase}"
    return statuses.joinToString(" · ") { s ->
        when (s) {
            FeatureStatus.AVAILABLE -> "Available"
            FeatureStatus.SELECTED -> "Selected"
            FeatureStatus.NOT_SELECTED -> "Not selected"
            FeatureStatus.REQUIRES_PERMISSION -> "Needs a permission"
            FeatureStatus.REQUIRES_DOWNLOAD -> "Needs a download"
            FeatureStatus.REQUIRES_INTERNET -> "Needs internet"
            FeatureStatus.OFFLINE_READY -> "Works offline"
            FeatureStatus.UNSUPPORTED -> "Not supported on this phone"
            FeatureStatus.PARTIALLY_SUPPORTED -> "Partly supported on this phone"
            FeatureStatus.NOT_BUILT_YET -> "Not built yet"
        }
    }
}

// ---- Storage -----------------------------------------------------------------------------------------------

internal fun storageVerdictText(review: StorageReview): String = when (review.verdict) {
    StorageVerdict.ENOUGH -> "Enough free storage."
    StorageVerdict.TIGHT -> "Free storage is tight."
    StorageVerdict.INSUFFICIENT -> "Not enough free storage."
    StorageVerdict.UNKNOWN -> "Free storage could not be read."
}

@Composable
internal fun StorageStep(ui: SetupUiState) {
    StepScroll {
        StepTitle("Storage review", "What your choices add up to. Estimates are labelled; nothing here is downloaded.")
        val review = ui.storage
        if (review == null) {
            Text("Waiting for the device analysis…", style = MaterialTheme.typography.bodySmall)
            return@StepScroll
        }
        Panel {
            PlainRow("Selected features", "${review.selectedFeatureCount}" + if (review.impliedFeatureCount > 0) " (+${review.impliedFeatureCount} needed by them)" else "")
            PlainRow("Total download", review.totalDownload.display())
            PlainRow("Estimated installed storage", review.totalInstalled.display())
            PlainRow("Available storage", review.availableBytes?.let(ByteFormat::format) ?: "could not be read")
            PlainRow("Extra storage still needed", if (review.extraNeededBytes > 0) ByteFormat.format(review.extraNeededBytes) else "none")
            Text(storageVerdictText(review), style = MaterialTheme.typography.titleSmall)
        }
        Text("Itemised", style = MaterialTheme.typography.titleSmall)
        for (line in review.lines) {
            Panel {
                Text(line.label, style = MaterialTheme.typography.bodyMedium)
                PlainRow("Download", line.download.display())
                PlainRow("Installed", line.installed.display())
                (line.installed.detail() ?: line.download.detail())?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (review.modelRam.isNotEmpty()) {
            Text("Model memory (rule of thumb, not measured)", style = MaterialTheme.typography.titleSmall)
            for ((id, bytes) in review.modelRam) {
                PlainRow(ModelCatalog.spec(id).displayName, "about ${ByteFormat.format(bytes)} while loaded")
            }
        }
        for (w in review.warnings) {
            Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (review.needsDownloadConsent) {
            Text(
                "Large downloads always ask for your explicit yes first, and never start on their own.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ---- Permissions -------------------------------------------------------------------------------------------

@Composable
internal fun PermissionsStep(ui: SetupUiState) {
    StepScroll {
        StepTitle(
            "Permissions",
            "Allow what you want now, or leave everything for later. Each button opens Android's own dialog or Settings page, " +
                "you can decline any of them, and the app works without the optional ones.",
        )
        AccessList()
        Text(
            "What your chosen features use",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val needed = ui.resolution.permissions
        val runtimeOrSpecial = needed.keys.filter { PermissionCatalog.info(it).kind != PermissionKind.INSTALL_TIME }
        if (runtimeOrSpecial.isEmpty()) {
            Panel { Text("None of the features you chose needs a permission that asks you at run time.", style = MaterialTheme.typography.bodyMedium) }
        }
        if (needed.isEmpty()) {
            Text("Choose features first to see which permissions they use.", style = MaterialTheme.typography.bodySmall)
        }
        for ((permission, required) in needed) {
            val info = PermissionCatalog.info(permission)
            Panel {
                Text(info.title, style = MaterialTheme.typography.titleSmall)
                PlainRow("Type", when (info.kind) {
                    PermissionKind.INSTALL_TIME -> "Granted at install"
                    PermissionKind.RUNTIME -> "Asked when needed"
                    PermissionKind.SPECIAL_ACCESS -> "Special access you switch on in Settings"
                })
                PlainRow("Optional", if (required) "No, the feature needs it" else "Yes")
                Text("Why: ${info.purpose}", style = MaterialTheme.typography.bodySmall)
                Text("What it can see: ${info.informationAccessed}", style = MaterialTheme.typography.bodySmall)
                Text("To revoke: ${info.howToRevoke}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Panel {
            Text("Always present", style = MaterialTheme.typography.titleSmall)
            for (id in listOf(com.jarvis.ai.features.PermissionId.INTERNET, com.jarvis.ai.features.PermissionId.ACCESS_NETWORK_STATE)) {
                val info = PermissionCatalog.info(id)
                Text("${info.title}: ${info.purpose}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
