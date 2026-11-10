package com.jarvis.ai.agents

import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.memory.MemoryStore

sealed interface AgentAvailability {
    data object Ready : AgentAvailability
    data class Unavailable(val reason: String) : AgentAvailability
}

/**
 * Turns a saved agent into what the rest of the app needs to run it: a restricted tool registry, the role text, and an honest answer to
 * "can it run right now?". The agent's own saved list of tools is never trusted on its own: it is checked against the chosen features,
 * the live registry and the risk level every time.
 */
class AgentRuntime(
    private val fullRegistry: ToolRegistry,
    private val memory: MemoryStore,
    private val time: TimeSource,
) {
    /** Tools this agent may use. Empty in a preview, and for an agent that is not active. */
    fun registryFor(config: AgentConfig, preview: Boolean = false): ToolRegistry {
        if (preview || config.status != AgentStatus.ACTIVE) return ToolRegistry(emptyList())
        val allowed = AgentBlueprint.toolsFor(config.features)
        return ToolRegistry(
            fullRegistry.all().filter { it.spec.name in config.enabledTools && it.spec.name in allowed && it.spec.risk == Risk.LOW },
        )
    }

    /** What the model is shown of the owner's memory, according to the agent's memory policy. */
    fun memoryBlock(config: AgentConfig): String = when (config.memory) {
        MemoryPolicy.NONE -> ""
        MemoryPolicy.OWN_ONLY -> memory.forModel(config.id, includeGlobal = false)
        MemoryPolicy.OWN_AND_GLOBAL -> memory.forModel(config.id, includeGlobal = true)
    }

    /** The role text for the planner. */
    fun persona(config: AgentConfig, preview: Boolean = false): String =
        AgentPrompt.build(config, memoryBlock(config), preview, registryFor(config, preview).all().map { it.spec.name }.toSet())

    /**
     * Whether the agent can answer right now, given what the phone has. A "local only" agent cannot answer while there is no on-device
     * engine, and the app says so rather than quietly using the cloud.
     */
    fun availability(config: AgentConfig, preview: Boolean, localEngineReady: Boolean, cloudConfigured: Boolean): AgentAvailability = when {
        !preview && config.status == AgentStatus.DRAFT -> AgentAvailability.Unavailable("This agent is a draft. Approve it first, or use the preview.")
        !preview && config.status == AgentStatus.DISABLED -> AgentAvailability.Unavailable("This agent is disabled.")
        config.network == NetworkPolicy.LOCAL_ONLY && !localEngineReady ->
            AgentAvailability.Unavailable("This agent is set to run on this phone only, and no on-device model is ready to answer. Change it to allow the cloud, or set up a local model when it is available.")
        config.network == NetworkPolicy.CLOUD_ALLOWED && !cloudConfigured && !localEngineReady ->
            AgentAvailability.Unavailable("No AI provider is set up. Add a cloud key in Prefs > Cloud AI.")
        else -> AgentAvailability.Ready
    }

    /** Whether this agent's requests may be sent to a cloud provider. */
    fun mayUseCloud(config: AgentConfig): Boolean = config.network == NetworkPolicy.CLOUD_ALLOWED

    fun nowMs(): Long = time.nowMs()
}
