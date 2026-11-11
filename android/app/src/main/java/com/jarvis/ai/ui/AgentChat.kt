package com.jarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.ai.AppContainer
import com.jarvis.ai.agents.AgentAvailability
import com.jarvis.ai.agents.AgentConfig
import com.jarvis.ai.ai.executor.ExecutionPolicy
import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Planner
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.core.security.Risk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One line in an agent chat. Agent chats are kept only while this screen is open; nothing is written to the history. */
class AgentLine(val fromUser: Boolean, val text: String, val isSystem: Boolean = false)

/**
 * Runs one agent conversation. The agent gets a role text and only its own tools; everything else goes through the same planner,
 * confirmation gate and executor as the main chat. In a preview there are no tools at all, so testing an agent changes nothing.
 */
class AgentChatController(private val container: AppContainer, private val config: AgentConfig, private val preview: Boolean) {
    val lines = androidx.compose.runtime.mutableStateListOf<AgentLine>()
    var running by mutableStateOf(false)
        private set
    var step by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    init {
        if (config.greeting.isNotBlank()) lines.add(AgentLine(false, config.greeting))
    }

    fun availability(): AgentAvailability = container.agentRuntime.availability(
        config,
        preview = preview,
        localEngineReady = container.localEngineBuilt,
        cloudConfigured = ProviderId.entries.any { container.isConfigured(it) },
    )

    val toolNames: List<String> get() = container.agentRuntime.registryFor(config, preview).all().map { it.spec.name }

    fun send(text: String, scope: kotlinx.coroutines.CoroutineScope) {
        val clean = text.trim()
        if (clean.isEmpty() || running) return
        val unavailable = availability() as? AgentAvailability.Unavailable
        lines.add(AgentLine(true, clean))
        if (unavailable != null) {
            lines.add(AgentLine(false, unavailable.reason, isSystem = true))
            return
        }
        running = true
        job = scope.launch {
            try {
                val registry = container.agentRuntime.registryFor(config, preview)
                val planner = Planner(container.llm, registry, persona = container.agentRuntime.persona(config, preview))
                val history = lines.dropLast(1).filter { !it.isSystem }.takeLast(10)
                    .map { ChatMessage(if (it.fromUser) ChatRole.USER else ChatRole.ASSISTANT, it.text) }
                when (val result = planner.plan(clean, history)) {
                    is PlanResult.Reply -> lines.add(AgentLine(false, result.text))
                    is PlanResult.NeedsClarification -> lines.add(AgentLine(false, result.question))
                    is PlanResult.Failed -> lines.add(AgentLine(false, result.reason, isSystem = true))
                    is PlanResult.Ready -> {
                        val executor = Executor(
                            registry = registry,
                            permissions = container.permissions,
                            gate = container.gate,
                            auditLog = container.audit,
                            replanner = planner,
                            policy = { ExecutionPolicy(confirmAt = Risk.MEDIUM) },
                        )
                        val report = executor.execute(result.plan, object : com.jarvis.ai.ai.executor.ExecutionListener {
                            override fun onStepStarted(step: com.jarvis.ai.ai.planner.PlanStep) {
                                this@AgentChatController.step = step.description
                            }

                            override fun onReplan(attempt: Int) {
                                this@AgentChatController.step = "Re-planning..."
                            }
                        })
                        // Built from recorded outcomes only; no model summarises it.
                        lines.add(AgentLine(false, report.summary()))
                    }
                }
                if (!preview) container.agents.markUsed(config.id)
            } catch (e: CancellationException) {
                lines.add(AgentLine(false, "Stopped.", isSystem = true))
                throw e
            } finally {
                running = false
                step = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}

@Composable
fun AgentChatScreen(container: AppContainer, config: AgentConfig, preview: Boolean) {
    val controller = remember(config.id, preview) { AgentChatController(container, config, preview) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val availability = controller.availability()

    LaunchedEffect(controller.lines.size) {
        if (controller.lines.isNotEmpty()) listState.animateScrollToItem(controller.lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), accent = if (preview) JarvisColors.Warning else null) {
            Text(config.name, style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
            MutedText(
                if (preview) "Preview. Tools are off and nothing here changes your planner. This chat is not saved."
                else if (controller.toolNames.isEmpty()) "This agent writes text only. Agent chats are not saved."
                else "Tools: ${controller.toolNames.joinToString(", ") { it.replace('_', ' ') }}. Agent chats are not saved.",
            )
            if (availability is AgentAvailability.Unavailable) Text(availability.reason, color = JarvisColors.Warning, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(controller.lines) { line ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        line.text,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (line.fromUser) JarvisColors.Violet.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (line.isSystem) JarvisColors.Warning else JarvisColors.Ink,
                    )
                }
            }
            if (controller.running) item { MutedText(controller.step ?: "Thinking...") }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FormField("Message", input, { input = it }, Modifier.weight(1f), maxLength = 2_000)
            if (controller.running) {
                GlyphButton(Glyph.STOP, onClick = { controller.stop() }, description = "Stop")
            } else {
                GlyphButton(Glyph.SEND_PLANE, onClick = {
                    controller.send(input, scope)
                    input = ""
                }, description = "Send")
            }
        }
    }
}
