package com.jarvis.ai.ai.planner

import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.models.ModelId
import kotlinx.serialization.json.JsonObject

data class PlanStep(
    /** 1-based position in the plan. */
    val index: Int,
    val tool: String,
    val description: String,
    /** Raw model-written parameters. They are validated against the tool schema before use. */
    val parameters: JsonObject,
    /**
     * True when the user's goal cannot be met without this step AND a different approach
     * might work, so a failure should trigger a replan. Failures of other steps are just
     * reported.
     */
    val critical: Boolean,
)

data class Plan(val goal: String, val steps: List<PlanStep>)

sealed interface PlanResult {
    /** Tools are needed: run [plan]. */
    data class Ready(val plan: Plan, val provider: ProviderId?, val localModel: ModelId? = null) : PlanResult

    /** No tool is needed; this is the answer to say. */
    data class Reply(val text: String, val provider: ProviderId?, val localModel: ModelId? = null) : PlanResult

    /** The request is ambiguous in a way that matters; ask the user this. */
    data class NeedsClarification(val question: String, val provider: ProviderId?, val localModel: ModelId? = null) : PlanResult

    /** Planning was impossible (every provider failed, or the model never produced a valid plan). */
    data class Failed(val reason: String) : PlanResult
}

/** Produces a revised plan after a critical step failed. Implemented by [Planner]; faked in tests. */
fun interface Replanner {
    suspend fun replan(
        goal: String,
        completed: List<PlanStep>,
        failed: PlanStep,
        error: String,
    ): PlanResult
}
