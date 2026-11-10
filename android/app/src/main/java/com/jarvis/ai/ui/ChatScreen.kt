package com.jarvis.ai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.Risk
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** Commands the app runs itself, with no cloud AI. Each one is understood by the built-in matcher. */
private val SUGGESTIONS = listOf(
    "Turn on the flashlight",
    "Set an alarm for 7 AM",
    "Open YouTube",
    "How much battery do I have",
)

@Composable
fun ChatScreen(vm: AgentViewModel, onOpenPrefs: () -> Unit) {
    val state by vm.chatState.collectAsStateWithLifecycle()
    val active by vm.activeProvider.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val input by vm.draft.collectAsStateWithLifecycle()
    val toggleMic = rememberMicToggle(vm)
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val anyProvider = settings.providers.any { it.configured }
    val newestId = state.items.lastOrNull()?.id
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    fun submit() {
        if (input.isNotBlank() && !state.running) {
            vm.send(input)
            vm.setDraft("")
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat", style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink)
                Text(
                    when {
                        active != null -> "Last AI reply: ${active?.displayName}"
                        anyProvider -> "Cloud AI is set up"
                        else -> "Built-in commands only. No cloud AI set up yet."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = JarvisColors.Muted,
                )
            }
            if (state.items.isNotEmpty() && !state.running) GlassChip("New chat", onClick = vm::newChat, glyph = Glyph.SPARK)
        }

        if (state.items.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppearIn {
                    GlassCard {
                        Text("At your service", style = MaterialTheme.typography.titleLarge, color = JarvisColors.Ink)
                        Text(
                            "Tap a command to fill it in, or type your own. These commands run on the phone itself, so they work with " +
                                "no internet and no AI key. Say \"call\" and a name to place a call: you always confirm it on screen first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JarvisColors.Muted,
                        )
                    }
                }
                SUGGESTIONS.forEachIndexed { index, text ->
                    AppearIn(delayMillis = 100 + index * 60) { CommandRow(text, onClick = { vm.setDraft(text) }) }
                }
                if (!anyProvider) {
                    AppearIn(delayMillis = 100 + SUGGESTIONS.size * 60) {
                        GlassCard(accent = JarvisColors.Warning) {
                            Text("Free-form chat needs a cloud AI", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Warning)
                            Text(
                                "Questions and longer plans are answered by an AI provider. Add a key under Prefs > Cloud AI. " +
                                    "An on-device model can be installed under Prefs > On-device models, but chatting with it is not built yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JarvisColors.Muted,
                            )
                            GlowButton("Open Prefs", onClick = onOpenPrefs, subtle = true)
                        }
                    }
                }
                Box(Modifier.size(8.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    MessageBubble(item, animate = item.id == newestId, time = timeFormat.format(Date(item.atMs)), modifier = Modifier.animateItem())
                }
            }
        }

        if (state.running) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TypingDots()
                Text(state.currentStep ?: "Working…", style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
            }
        }

        // Room below the input for the floating tab bar.
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = if (androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0) 8.dp else ScreenBottomPadding - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = if (voice.listening && voice.partial.isNotBlank()) voice.partial else input,
                onValueChange = { vm.setDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (voice.listening) "Listening…" else "Ask or command…") },
                readOnly = voice.listening,
                maxLines = 4,
                shape = RoundedCornerShape(28.dp),
                colors = jarvisFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            GlyphButton(
                glyph = if (voice.listening || voice.speaking) Glyph.STOP else Glyph.MIC,
                onClick = toggleMic,
                tint = if (voice.listening) JarvisColors.Magenta else Color.White,
                description = if (voice.listening) "Stop listening" else "Speak a command",
            )
            SendButton(canSend = input.isNotBlank(), running = state.running, onSend = ::submit, onStop = vm::cancel)
        }
    }
}

@Composable
private fun CommandRow(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlyphIcon(Glyph.CHEVRON_RIGHT, JarvisColors.Cyan, size = 16.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink)
    }
}

@Composable
private fun SendButton(canSend: Boolean, running: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val usable = canSend || running
    Box(
        Modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (usable) 1f else 0.4f }
            .clip(CircleShape)
            .background(JarvisColors.spectrum())
            .semantics { contentDescription = if (running) "Stop" else "Send" }
            .clickable(enabled = usable, role = Role.Button, onClick = if (running) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (running) Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(3.dp)))
        else GlyphIcon(Glyph.ARROW_UP, Color.White, size = 24.dp, strokeWidth = 2.2f)
    }
}

@Composable
private fun MessageBubble(item: ChatItem, animate: Boolean, time: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val appear = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) appear.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }

    val isUser = item.sender == Sender.USER
    val isSystem = item.sender == Sender.SYSTEM
    val shape = RoundedCornerShape(
        topStart = 22.dp, topEnd = 22.dp,
        bottomStart = if (isUser) 22.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 22.dp,
    )
    val fill: Brush = when {
        isUser -> Brush.linearGradient(listOf(JarvisColors.Violet.copy(alpha = 0.45f), JarvisColors.Blue.copy(alpha = 0.35f)))
        isSystem -> SolidColor(colors.error.copy(alpha = 0.12f))
        else -> Brush.verticalGradient(listOf(Color(0xFF141A2E).copy(alpha = 0.95f), Color(0xFF0D1222).copy(alpha = 0.95f)))
    }
    val edge = when {
        isUser -> JarvisColors.Violet.copy(alpha = 0.55f)
        isSystem -> colors.error.copy(alpha = 0.5f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val who = when (item.sender) {
        Sender.USER -> "You"
        Sender.ASSISTANT -> item.source ?: item.provider?.displayName ?: "Jarvis"
        Sender.SYSTEM -> "System"
    }
    val meta = buildString {
        append(who)
        if (item.viaVoice) append(" · Voice")
        append(" · ").append(time)
    }

    Box(
        modifier.fillMaxWidth().graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 24.dp.toPx()
        },
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, edge, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isSystem -> colors.error
                    isUser -> JarvisColors.Lilac
                    else -> JarvisColors.Cyan
                },
            )
            Text(reportText(item.text), style = MaterialTheme.typography.bodyMedium, color = JarvisColors.Ink)
        }
    }
}

/** Colours the result marks at the start of an execution report line (done, handed to Android, failed, declined). */
private fun reportText(text: String) = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val mark = line.firstOrNull()
        val color = when (mark) {
            '✔' -> JarvisColors.Good
            '➜' -> JarvisColors.Cyan
            '✘' -> JarvisColors.Magenta
            '⊘', '⛔' -> JarvisColors.Warning
            else -> null
        }
        if (color != null) {
            withStyle(SpanStyle(color = color)) { append(mark.toString()) }
            append(line.drop(1))
        } else {
            append(line)
        }
    }
}

// ---- The confirmation gate -------------------------------------------------------------------------------------------

/**
 * The only place a sensitive action can be approved, shown over whichever tab is open. The text comes from the tool's
 * own description of the exact, validated action (for a call: the resolved contact and number), never from model output
 * or from speech. It can only be answered by tapping: voice, notifications and the AI have no way to press these buttons.
 * Tapping outside or going back counts as Cancel, and an unanswered request expires.
 */
@Composable
fun ConfirmationOverlay(request: ConfirmationRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    val call = CallSummary.parse(request.toolName, request.summary)
    var now by remember(request.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = ((request.expiresAtMs - now) / 1000).coerceAtLeast(0)
    val high = request.risk == Risk.HIGH

    Dialog(onDismissRequest = onDeny, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF182038), Color(0xFF0D1222))))
                .border(1.dp, Brush.linearGradient(JarvisColors.Spectrum.map { it.copy(alpha = 0.6f) }), RoundedCornerShape(32.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                (if (high) "CONFIRMATION REQUIRED" else "PLEASE CONFIRM"),
                style = MaterialTheme.typography.labelMedium,
                color = if (high) JarvisColors.Magenta else JarvisColors.Cyan,
            )
            if (call != null) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(JarvisColors.spectrum()).padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(JarvisColors.Void), contentAlignment = Alignment.Center) {
                        if (call.name != null) Text(initials(call.name), style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Cyan)
                        else GlyphIcon(Glyph.CALL, JarvisColors.Cyan, size = 32.dp)
                    }
                }
                Text("Call this number?", style = MaterialTheme.typography.titleMedium, color = JarvisColors.Muted)
                if (call.name != null) {
                    Text(call.name, style = MaterialTheme.typography.headlineMedium, color = JarvisColors.Ink, textAlign = TextAlign.Center)
                }
                Text(call.number, style = MaterialTheme.typography.headlineSmall, color = JarvisColors.Cyan, textAlign = TextAlign.Center)
                call.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = JarvisColors.Muted) }
            } else {
                Text(request.summary, style = MaterialTheme.typography.titleMedium, color = JarvisColors.Ink, textAlign = TextAlign.Center)
            }
            Text(
                "Risk: ${request.risk.name.lowercase()}. Only a tap here can allow this. It expires in ${seconds}s if you do not answer.",
                style = MaterialTheme.typography.bodySmall,
                color = JarvisColors.Muted,
                textAlign = TextAlign.Center,
            )
            GlowButton(if (call != null) "Confirm & Call" else "Allow", onClick = onAllow, modifier = Modifier.fillMaxWidth())
            GlowButton("Cancel", onClick = onDeny, modifier = Modifier.fillMaxWidth(), subtle = true)
        }
    }
}

/** What the call confirmation text says, split so the name and number can be shown large. Only used for presentation. */
internal data class CallSummary(val name: String?, val number: String, val label: String?) {
    companion object {
        private val CONTACT = Regex("""^Call (.+) on (\S+) \((.*)\)$""")
        private val NUMBER = Regex("""^Call (\S+)$""")

        fun parse(toolName: String, summary: String): CallSummary? = when (toolName) {
            "call_contact" -> CONTACT.matchEntire(summary)?.let { CallSummary(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifBlank { null }) }
            "call_number" -> NUMBER.matchEntire(summary)?.let { CallSummary(null, it.groupValues[1], null) }
            else -> null
        }
    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
