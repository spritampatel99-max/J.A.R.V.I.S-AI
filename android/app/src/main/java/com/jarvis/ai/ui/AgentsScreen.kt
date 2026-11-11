package com.jarvis.ai.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.AppContainer
import com.jarvis.ai.agents.AgentBlueprint
import com.jarvis.ai.agents.AgentCategory
import com.jarvis.ai.agents.AgentConfig
import com.jarvis.ai.agents.AgentFeature
import com.jarvis.ai.agents.AgentResult
import com.jarvis.ai.agents.AgentStatus
import com.jarvis.ai.agents.AgentValidator
import com.jarvis.ai.agents.Blueprint
import com.jarvis.ai.agents.CustomFeatureAssessment
import com.jarvis.ai.agents.CustomFeatureKind
import com.jarvis.ai.agents.FeatureSupport
import com.jarvis.ai.agents.MemoryPolicy
import com.jarvis.ai.agents.ModelPreference
import com.jarvis.ai.agents.NetworkPolicy
import com.jarvis.ai.agents.ResponseDetail
import com.jarvis.ai.memory.MemoryScope

private sealed interface AgentMode {
    data object Listing : AgentMode
    data class Wizard(val editing: AgentConfig?) : AgentMode
    data class Chat(val config: AgentConfig, val preview: Boolean) : AgentMode
}

private val ICONS = listOf(Glyph.SPARK, Glyph.CHAT, Glyph.CALENDAR, Glyph.BOLT, Glyph.GLOBE, Glyph.SHIELD, Glyph.PERSON, Glyph.HUB)

private fun iconFor(name: String): Glyph = ICONS.firstOrNull { it.name == name } ?: Glyph.SPARK

private val EXAMPLES = listOf(
    "I am a teacher. I want an assistant that can create lesson plans, worksheets and quizzes for my students.",
    "I am a student preparing for exams. I want help with revision plans, flashcards and practice questions.",
    "I run a small shop. I want help writing replies to customers and planning my week.",
)

/** Agents: build, check, approve and use your own assistants. Nothing becomes active until you approve it. */
@Composable
fun AgentsScreen(container: AppContainer) {
    var mode by remember { mutableStateOf<AgentMode>(AgentMode.Listing) }
    BackHandler(enabled = mode != AgentMode.Listing) { mode = AgentMode.Listing }
    when (val m = mode) {
        AgentMode.Listing -> AgentList(container, onCreate = { mode = AgentMode.Wizard(null) }, onEdit = { mode = AgentMode.Wizard(it) }, onChat = { c, p -> mode = AgentMode.Chat(c, p) })
        is AgentMode.Wizard -> AgentWizard(container, m.editing, onClose = { mode = AgentMode.Listing }, onPreview = { mode = AgentMode.Chat(it, true) })
        is AgentMode.Chat -> AgentChatScreen(container, m.config, m.preview)
    }
}

// ---------------------------------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentList(container: AppContainer, onCreate: () -> Unit, onEdit: (AgentConfig) -> Unit, onChat: (AgentConfig, Boolean) -> Unit) {
    val agents by container.agents.agents.collectAsStateWithLifecycle()
    val memories by container.memory.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var renaming by remember { mutableStateOf<AgentConfig?>(null) }
    var deleting by remember { mutableStateOf<AgentConfig?>(null) }
    var exporting by remember { mutableStateOf<Pair<AgentConfig, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    PageColumn {
        GlassCard {
            Text("Your own assistants", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            MutedText("An agent is a role and a short list of things it may do, such as write lesson plans or add tasks. It cannot grant itself anything, and it stays a draft until you approve it. Agents cannot read files or search the web yet.")
            GlowButton("Create an agent", onClick = onCreate, modifier = Modifier.fillMaxWidth(), enabled = agents.size < com.jarvis.ai.agents.AgentStore.MAX_AGENTS)
        }
        message?.let { MutedText(it) }
        if (container.agents.saveFailed) {
            GlassCard(accent = JarvisColors.Warning) { Text("Agents could not be saved to the phone's storage. Free up space.", color = JarvisColors.Warning) }
        }
        if (agents.isEmpty()) GlassCard { MutedText("No agents yet.") }
        for (agent in agents) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlyphIcon(iconFor(agent.icon), JarvisColors.Cyan, size = 26.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(agent.name, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
                        MutedText(agent.category.label)
                    }
                    StatusPill(
                        agent.status.label,
                        when (agent.status) {
                            AgentStatus.ACTIVE -> JarvisColors.Good
                            AgentStatus.DRAFT -> JarvisColors.Warning
                            AgentStatus.DISABLED -> JarvisColors.Muted
                        },
                    )
                }
                Text(agent.purpose, color = JarvisColors.Ink, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                MutedText(agent.features.mapNotNull { AgentBlueprint.feature(it)?.name }.take(5).joinToString(", ").ifEmpty { "No features chosen" })
                MutedText("${agent.network.label}. Memory: ${agent.memory.label}. ${memories.count { it.agentId == agent.id }} note(s) kept.")
                MutedText("Created ${formatWhen(agent.createdMs)}. " + (agent.lastUsedMs?.let { "Last used ${formatWhen(it)}." } ?: "Not used yet."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (agent.status) {
                        AgentStatus.ACTIVE -> GlassChip("Open", onClick = { onChat(agent, false) }, glyph = Glyph.CHAT)
                        AgentStatus.DRAFT -> GlassChip("Review and approve", onClick = { onEdit(agent) }, glyph = Glyph.CHECK_CIRCLE)
                        AgentStatus.DISABLED -> Unit
                    }
                    if (agent.status != AgentStatus.DRAFT) {
                        GlassChip(if (agent.status == AgentStatus.ACTIVE) "Disable" else "Enable", onClick = {
                            container.agents.setEnabled(agent.id, agent.status != AgentStatus.ACTIVE)
                        })
                    }
                    GlassChip("Edit", onClick = { onEdit(agent) })
                    GlassChip("Rename", onClick = { renaming = agent })
                    GlassChip("Duplicate", onClick = {
                        message = when (val r = container.agents.duplicate(agent.id)) {
                            is AgentResult.Saved -> "Copied as \"${r.agent.name}\" (a draft, with no memory)."
                            is AgentResult.Rejected -> r.problems.first()
                        }
                    })
                    GlassChip("Export", onClick = { container.agents.export(agent.id)?.let { exporting = agent to it } })
                    GlassChip("Delete", onClick = { deleting = agent }, glyph = Glyph.TRASH)
                }
            }
        }
    }

    renaming?.let { agent ->
        var text by remember(agent.id) { mutableStateOf(agent.name) }
        var error by remember(agent.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("Name", text, { text = it }, maxLength = AgentValidator.MAX_NAME)
                    error?.let { Text(it, color = JarvisColors.Coral, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = container.agents.rename(agent.id, text)) {
                        is AgentResult.Saved -> renaming = null
                        is AgentResult.Rejected -> error = r.problems.first()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { agent ->
        val kept = memories.count { it.agentId == agent.id }
        ConfirmDialog(
            "Delete \"${agent.name}\"?",
            "The agent is removed" + if (kept > 0) ", and so are the $kept note(s) it remembered." else ". It has no saved memory.",
            "Delete",
            onConfirm = {
                container.agents.delete(agent.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    exporting?.let { (agent, text) ->
        AlertDialog(
            onDismissRequest = { exporting = null },
            title = { Text("Export \"${agent.name}\"") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MutedText("This is the agent's setup only. It contains no memory, no chat history and no keys.")
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Ink)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share agent setup")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                    TextButton(onClick = { exporting = null }) { Text("Close") }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Creator
// ---------------------------------------------------------------------------------------------------

private enum class Step(val title: String) { DESCRIBE("1 Describe"), FEATURES("2 Features"), QUESTIONS("3 Details"), IDENTITY("4 Identity"), REVIEW("5 Review") }

private class WizardState(editing: AgentConfig?) {
    var step by mutableStateOf(if (editing == null) Step.DESCRIBE else Step.REVIEW)
    var request by mutableStateOf(editing?.purpose.orEmpty())
    var analysed by mutableStateOf(editing != null)
    var category by mutableStateOf(editing?.category ?: AgentCategory.CUSTOM)
    var blueprint by mutableStateOf<Blueprint?>(null)
    val shown = mutableStateListOf<String>().apply {
        if (editing != null) {
            addAll(AgentBlueprint.featuresFor(editing.category).map { it.id })
            addAll(editing.features.filter { it !in this })
        }
    }
    var selected by mutableStateOf(editing?.features?.toSet() ?: emptySet())
    val answers = mutableStateMapOf<String, String>().apply { if (editing != null) putAll(editing.answers) }
    var expanded by mutableStateOf(emptySet<String>())
    var customText by mutableStateOf("")
    var customResult by mutableStateOf<CustomFeatureAssessment?>(null)

    var name by mutableStateOf(editing?.name.orEmpty())
    var greeting by mutableStateOf(editing?.greeting ?: "Hello! How can I help?")
    var language by mutableStateOf(editing?.language ?: "English")
    var personality by mutableStateOf(editing?.personality ?: "Friendly")
    var tone by mutableStateOf(editing?.tone ?: "Clear")
    var detail by mutableStateOf(editing?.detail ?: ResponseDetail.BALANCED)
    var audience by mutableStateOf(editing?.audience.orEmpty())
    var icon by mutableStateOf(editing?.icon?.ifBlank { null } ?: Glyph.SPARK.name)
    var model by mutableStateOf(editing?.model ?: ModelPreference.ROUTER_DEFAULT)
    var network by mutableStateOf(editing?.network ?: NetworkPolicy.CLOUD_ALLOWED)
    var memory by mutableStateOf(editing?.memory ?: MemoryPolicy.NONE)
    var instructions by mutableStateOf(editing?.instructions.orEmpty())
    var rules by mutableStateOf(editing?.extraSafetyRules?.joinToString("\n").orEmpty())

    var problems by mutableStateOf<List<String>>(emptyList())

    fun toConfig(container: AppContainer, editing: AgentConfig?): AgentConfig {
        val features = AgentBlueprint.FEATURES.map { it.id }.filter { it in selected && AgentBlueprint.feature(it)?.selectable == true }
        return AgentConfig(
            id = editing?.id ?: "", version = editing?.version ?: 1, name = name.trim(), purpose = request.trim(), description = "",
            category = category, instructions = instructions.trim(), features = features,
            answers = answers.filterValues { it.isNotBlank() }.mapValues { it.value.trim() },
            greeting = greeting.trim(), icon = icon, language = language.trim(), personality = personality.trim(), tone = tone.trim(),
            detail = detail, audience = audience.trim(), model = model, network = network,
            enabledTools = AgentValidator.toolsFor(features, container.registry), memory = memory,
            extraSafetyRules = rules.lines().map { it.trim() }.filter { it.isNotEmpty() },
            status = editing?.status ?: AgentStatus.DRAFT, createdMs = editing?.createdMs ?: 0L, updatedMs = 0L, lastUsedMs = editing?.lastUsedMs,
        )
    }
}

@Composable
private fun AgentWizard(container: AppContainer, editing: AgentConfig?, onClose: () -> Unit, onPreview: (AgentConfig) -> Unit) {
    val s = remember { WizardState(editing) }
    var confirmApprove by remember { mutableStateOf(false) }

    PageColumn {
        ChoiceChips(Step.entries.filter { it == Step.DESCRIBE || s.analysed }, s.step, { it.title }, { s.step = it })
        when (s.step) {
            Step.DESCRIBE -> DescribeStep(s)
            Step.FEATURES -> FeaturesStep(s)
            Step.QUESTIONS -> QuestionsStep(s)
            Step.IDENTITY -> IdentityStep(s)
            Step.REVIEW -> ReviewStep(container, s, editing, onClose, onPreview, onApprove = { confirmApprove = true })
        }
    }

    if (confirmApprove) {
        val candidate = s.toConfig(container, editing)
        ConfirmDialog(
            "Approve \"${candidate.name}\"?",
            "It becomes active and can be used from the Agents list. You can disable, edit or delete it at any time.",
            "Approve",
            onConfirm = {
                confirmApprove = false
                saveAgent(container, s, editing, approve = true, onClose)
            },
            onDismiss = { confirmApprove = false },
        )
    }
}

private fun saveAgent(container: AppContainer, s: WizardState, editing: AgentConfig?, approve: Boolean, onClose: () -> Unit) {
    val config = s.toConfig(container, editing)
    val result = if (editing == null) container.agents.createDraft(config) else container.agents.update(editing.id, config)
    when (result) {
        is AgentResult.Rejected -> s.problems = result.problems
        is AgentResult.Saved -> {
            if (approve && result.agent.status == AgentStatus.DRAFT) container.agents.approve(result.agent.id)
            onClose()
        }
    }
}

@Composable
private fun DescribeStep(s: WizardState) {
    GlassCard {
        Text("Describe the agent you want", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        MutedText("Say who it is for and what it should help with. J.A.R.V.I.S. suggests features from your words. This does not use the internet or an AI model.")
        FormField("What kind of agent would you like to create?", s.request, { s.request = it }, singleLine = false, minLines = 3, maxLength = AgentValidator.MAX_PURPOSE)
        MutedText("Or start from an example:")
        for (example in EXAMPLES) GlassChip(example.take(48) + "...", onClick = { s.request = example })
        s.blueprint?.missing?.forEach { Text(it, color = JarvisColors.Warning, style = MaterialTheme.typography.bodySmall) }
        GlowButton("Suggest features", enabled = s.request.trim().length >= AgentValidator.MIN_PURPOSE, onClick = {
            val b = AgentBlueprint.analyze(s.request)
            s.blueprint = b
            s.category = b.category
            s.shown.clear()
            s.shown.addAll(b.suggested.map { it.id })
            s.selected = emptySet()
            s.answers.clear()
            s.analysed = true
            if (b.missing.isEmpty()) s.step = Step.FEATURES
        })
        if (s.analysed && s.blueprint != null && s.blueprint!!.missing.isNotEmpty()) {
            GlassChip("Continue anyway", onClick = { s.step = Step.FEATURES })
        }
    }
}

@Composable
private fun FeaturesStep(s: WizardState) {
    val features = s.shown.mapNotNull { AgentBlueprint.feature(it) }
    GlassCard {
        Text("This looks like a ${s.category.label.lowercase()} agent", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        MutedText("Nothing is switched on for you. Choose only what you want. Features marked \"Not built yet\" are listed so you know they are missing, and cannot be chosen.")
        ChoiceChips(AgentCategory.entries, s.category, { it.label }, { c ->
            s.category = c
            val keep = s.shown.filter { it in s.selected }
            s.shown.clear()
            s.shown.addAll(AgentBlueprint.featuresFor(c).map { it.id })
            s.shown.addAll(keep.filter { it !in s.shown })
            s.answers.clear()
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Select all", onClick = { s.selected = s.selected + features.filter { it.selectable }.map { it.id } })
            GlassChip("Clear all", onClick = { s.selected = s.selected - features.map { it.id }.toSet() })
        }
        MutedText("${s.selected.size} chosen")
    }
    for (f in features) FeatureCard(f, checked = f.id in s.selected, expanded = f.id in s.expanded,
        onToggle = { s.selected = if (f.id in s.selected) s.selected - f.id else s.selected + f.id },
        onExpand = { s.expanded = if (f.id in s.expanded) s.expanded - f.id else s.expanded + f.id })

    GlassCard {
        GlassChip("More suggestions", onClick = { s.shown.addAll(AgentBlueprint.moreSuggestions(s.category, s.shown.toSet()).map { it.id }) })
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Text("Add your own feature", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        FormField("What else should it do?", s.customText, { s.customText = it; s.customResult = null }, maxLength = 200)
        GlowButton("Check this feature", enabled = s.customText.isNotBlank(), subtle = true, onClick = {
            s.customResult = AgentBlueprint.assessCustom(s.customText, s.category)
        })
        s.customResult?.let { r ->
            Text(
                when (r.kind) {
                    CustomFeatureKind.EXISTING -> "Available"
                    CustomFeatureKind.TEXT_ONLY -> "Possible as text only"
                    CustomFeatureKind.UNSUPPORTED -> "Not possible in this version"
                },
                color = if (r.kind == CustomFeatureKind.UNSUPPORTED) JarvisColors.Coral else JarvisColors.Good,
                style = MaterialTheme.typography.titleSmall,
            )
            MutedText(r.explanation)
            MutedText("Needs: ${r.permissions}")
            when (r.kind) {
                CustomFeatureKind.EXISTING -> r.feature?.let { f ->
                    GlassChip("Choose \"${f.name}\"", onClick = {
                        if (f.id !in s.shown) s.shown.add(f.id)
                        s.selected = s.selected + f.id
                        s.customResult = null
                        s.customText = ""
                    })
                }
                CustomFeatureKind.TEXT_ONLY -> GlassChip("Add as an instruction", onClick = {
                    s.instructions = (s.instructions + "\nAlso help with: " + s.customText.trim()).trim().take(AgentValidator.MAX_INSTRUCTIONS)
                    s.customResult = null
                    s.customText = ""
                })
                CustomFeatureKind.UNSUPPORTED -> Unit
            }
        }
    }
    GlowButton("Next: details", onClick = { s.step = Step.QUESTIONS }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FeatureCard(f: AgentFeature, checked: Boolean, expanded: Boolean, onToggle: () -> Unit, onExpand: () -> Unit) {
    GlassCard(accent = if (checked) JarvisColors.Cyan else null) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = checked,
                enabled = f.selectable,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = JarvisColors.Cyan, uncheckedColor = JarvisColors.Muted, checkmarkColor = JarvisColors.Void),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = if (f.selectable) JarvisColors.Ink else JarvisColors.Muted)
                    StatusPill(
                        f.support.label,
                        when (f.support) {
                            FeatureSupport.READY -> JarvisColors.Good
                            FeatureSupport.PARTIAL -> JarvisColors.Warning
                            FeatureSupport.NOT_BUILT -> JarvisColors.Muted
                        },
                    )
                }
                MutedText(f.description)
                if (expanded) {
                    MutedText("Benefit: ${f.benefit}")
                    MutedText("Tools: " + if (f.tools.isEmpty()) "none (writes text)" else f.tools.joinToString(", ") { it.replace('_', ' ') })
                    MutedText("Permissions: ${f.permissions}")
                    MutedText("Storage: ${f.storage}")
                    MutedText("Offline: " + when (f.offline) {
                        com.jarvis.ai.features.OfflineSupport.FULL -> "works without internet"
                        com.jarvis.ai.features.OfflineSupport.PARTIAL -> "needs an AI model; no on-device model can answer yet"
                        com.jarvis.ai.features.OfflineSupport.NONE -> "needs internet"
                    })
                    MutedText("Model: ${f.modelNeed}")
                    f.limitation?.let { Text("Limit: $it", color = JarvisColors.Warning, style = MaterialTheme.typography.bodySmall) }
                }
                GlassChip(if (expanded) "Hide details" else "Details", onClick = onExpand)
            }
        }
    }
}

@Composable
private fun QuestionsStep(s: WizardState) {
    val questions = AgentBlueprint.questionsFor(s.category)
    GlassCard {
        Text("A few details", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        MutedText("Optional. Your answers are added to the agent's instructions so it can fit you. Do not enter passwords or keys.")
    }
    for (q in questions) {
        GlassCard {
            Text(q.text, color = JarvisColors.Ink)
            if (q.choices.isNotEmpty()) ChoiceChips(q.choices, s.answers[q.id], { it }, { s.answers[q.id] = it })
            FormField(if (q.choices.isEmpty()) "Your answer" else "Or write your own", s.answers[q.id].orEmpty(), { s.answers[q.id] = it }, maxLength = 300)
        }
    }
    GlowButton("Next: identity", onClick = { s.step = Step.IDENTITY }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun IdentityStep(s: WizardState) {
    GlassCard {
        FormField("Agent name", s.name, { s.name = it }, maxLength = AgentValidator.MAX_NAME)
        FormField("Greeting", s.greeting, { s.greeting = it }, maxLength = AgentValidator.MAX_GREETING)
        FormField("Language it replies in", s.language, { s.language = it }, maxLength = AgentValidator.MAX_SHORT)
        FormField("Personality", s.personality, { s.personality = it }, maxLength = AgentValidator.MAX_SHORT)
        FormField("Tone", s.tone, { s.tone = it }, maxLength = AgentValidator.MAX_SHORT)
        FormField("Who will use it", s.audience, { s.audience = it }, maxLength = AgentValidator.MAX_AUDIENCE)
        MutedText("Answer length")
        ChoiceChips(ResponseDetail.entries, s.detail, { it.label }, { s.detail = it })
        MutedText("Icon")
        FlowRowIcons(s)
    }
    GlassCard {
        Text("Where it runs", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        ChoiceChips(NetworkPolicy.entries, s.network, { it.label }, { s.network = it })
        MutedText(
            if (s.network == NetworkPolicy.LOCAL_ONLY) "Nothing is sent to a cloud provider. The on-device engine is not built yet, so a phone-only agent cannot answer in this version."
            else "Your messages to this agent go to the cloud provider you set up in Prefs > Cloud AI.",
        )
        MutedText("Model")
        ChoiceChips(ModelPreference.entries, s.model, { it.label }, { s.model = it })
        MutedText("Today every answer comes from your cloud providers in the order you set. A model choice is kept for when the on-device engine exists.")
    }
    GlassCard {
        Text("Memory", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        ChoiceChips(MemoryPolicy.entries, s.memory, { it.label }, { s.memory = it })
        MutedText("The agent never sees another agent's notes. Notes it can see are sent to the provider with each request unless you choose \"No memory\" or phone-only.")
    }
    GlassCard {
        FormField("Extra instructions (optional)", s.instructions, { s.instructions = it }, singleLine = false, minLines = 3, maxLength = AgentValidator.MAX_INSTRUCTIONS)
        FormField("Extra safety rules, one per line (optional)", s.rules, { s.rules = it }, singleLine = false, minLines = 2, maxLength = 1_000)
        MutedText("These add to the built-in rules. They can make the agent more careful but cannot remove a rule.")
    }
    GlowButton("Next: review", onClick = { s.step = Step.REVIEW }, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowIcons(s: WizardState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in ICONS) GlassChip(g.name.lowercase().replace('_', ' '), onClick = { s.icon = g.name }, glyph = g, selected = s.icon == g.name)
    }
}

@Composable
private fun ReviewStep(container: AppContainer, s: WizardState, editing: AgentConfig?, onClose: () -> Unit, onPreview: (AgentConfig) -> Unit, onApprove: () -> Unit) {
    val candidate = s.toConfig(container, editing)
    val checks = AgentValidator.validate(candidate, container.registry)
    val chosen = candidate.features.mapNotNull { AgentBlueprint.feature(it) }
    val needs = chosen.map { it.permissions }.filter { it != "None" }.distinct()

    GlassCard {
        Text(candidate.name.ifBlank { "(no name yet)" }, style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink)
        MutedText(candidate.category.label)
        Text(candidate.purpose.ifBlank { "(no description yet)" }, color = JarvisColors.Ink)
    }
    GlassCard {
        Text("What it can do", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        if (chosen.isEmpty()) MutedText("No features chosen. It can only chat.")
        for (f in chosen) MutedText("- ${f.name}" + if (f.support == FeatureSupport.PARTIAL) " (partly: ${f.limitation})" else "")
        MutedText("Tools: " + if (candidate.enabledTools.isEmpty()) "none. It only writes text." else candidate.enabledTools.joinToString(", ") { it.replace('_', ' ') })
        MutedText("Access it needs: " + if (needs.isEmpty()) "none" else needs.joinToString("; "))
        MutedText("It cannot read files, search the web, call, message or use your contacts. Those are not built, and agents are never given them.")
    }
    GlassCard {
        Text("Where it runs and what it keeps", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        MutedText("${candidate.network.label}. Memory: ${candidate.memory.label}. Answers: ${candidate.detail.label.lowercase()}, in ${candidate.language.ifBlank { "the language you write in" }}.")
        MutedText("Agent chats are not saved.")
    }
    if (checks.isNotEmpty() || s.problems.isNotEmpty()) {
        GlassCard(accent = JarvisColors.Coral) {
            Text("Fix before saving", color = JarvisColors.Coral, style = MaterialTheme.typography.titleSmall)
            for (p in (checks + s.problems).distinct()) Text("- $p", color = JarvisColors.Coral, style = MaterialTheme.typography.bodySmall)
        }
    }
    val ready = checks.isEmpty()
    GlowButton("Try it in a preview chat", subtle = true, enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = {
        onPreview(candidate.copy(id = "preview", status = AgentStatus.ACTIVE))
    })
    MutedText("The preview has no tools and saves nothing.")
    when {
        editing != null && editing.status != AgentStatus.DRAFT ->
            GlowButton("Save changes", enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = { saveAgent(container, s, editing, approve = false, onClose) })
        else -> {
            GlowButton("Approve and save", enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = onApprove)
            GlowButton("Save as draft", subtle = true, enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = { saveAgent(container, s, editing, approve = false, onClose) })
            MutedText("A draft cannot be used until you approve it.")
        }
    }
    GlowButton("Discard", subtle = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
}
