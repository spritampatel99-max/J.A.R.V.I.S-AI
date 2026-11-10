package com.jarvis.ai.agents

enum class AgentCategory(val label: String) {
    TEACHER("Teacher"),
    STUDY("Study"),
    RESEARCH("Research"),
    BUSINESS("Business"),
    PERSONAL_ASSISTANT("Personal assistant"),
    FITNESS("Fitness planning"),
    TRAVEL("Travel planning"),
    SUPPORT("Customer support"),
    HOUSEHOLD("Household organiser"),
    CUSTOM("Custom"),
}

/** What the person would like to answer for this agent. The router still decides per request what is actually available. */
enum class ModelPreference(val label: String) {
    ROUTER_DEFAULT("Let J.A.R.V.I.S. choose"),
    GEMMA_3_1B("Prefer Gemma 3 1B"),
    GEMMA_4_E2B("Prefer Gemma 4 E2B"),
    CLOUD("Prefer my cloud provider"),
}

enum class NetworkPolicy(val label: String) {
    CLOUD_ALLOWED("Cloud allowed"),
    LOCAL_ONLY("On this phone only"),
}

/** Which saved memory the agent is shown. An agent never sees another agent's memory. */
enum class MemoryPolicy(val label: String) {
    NONE("No memory"),
    OWN_ONLY("Only this agent's memory"),
    OWN_AND_GLOBAL("This agent's memory and my general memory"),
}

enum class AgentStatus(val label: String) {
    /** Being built or previewed. Never used for real until the person approves it. */
    DRAFT("Draft"),
    ACTIVE("Active"),
    DISABLED("Disabled"),
}

enum class ResponseDetail(val label: String) { SHORT("Short"), BALANCED("Balanced"), DETAILED("Detailed") }

/**
 * A custom agent. It is plain data: a persona, a set of chosen features, and the few tools those features need. It never holds code,
 * a system prompt written by the model, a permission, or a key. What it may do is decided by the app (see [AgentRuntime]).
 */
data class AgentConfig(
    val id: String,
    val version: Int,
    val name: String,
    val purpose: String,
    val description: String,
    val category: AgentCategory,
    /** Extra instructions the owner wrote. They add to the built-in safety rules and can never replace them. */
    val instructions: String,
    /** Ids of the chosen [AgentFeature]s. */
    val features: List<String>,
    /** Answers to the category questions, by question id. */
    val answers: Map<String, String>,
    val greeting: String,
    val icon: String,
    val language: String,
    val personality: String,
    val tone: String,
    val detail: ResponseDetail,
    val audience: String,
    val model: ModelPreference,
    val network: NetworkPolicy,
    /** Tools the agent may use, derived from its features. Anything not here is not available to it. */
    val enabledTools: Set<String>,
    val memory: MemoryPolicy,
    /** Extra rules from the owner. They can only make the agent more careful. */
    val extraSafetyRules: List<String>,
    val status: AgentStatus,
    val createdMs: Long,
    val updatedMs: Long,
    val lastUsedMs: Long?,
)
