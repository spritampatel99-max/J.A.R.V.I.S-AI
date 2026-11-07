package com.jarvis.ai

import com.jarvis.ai.ai.fallback.LlmClient
import com.jarvis.ai.ai.fallback.LlmResponse
import com.jarvis.ai.ai.planner.Plan
import com.jarvis.ai.ai.planner.PlanStep
import com.jarvis.ai.ai.providers.LlmProvider
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.permissions.PermissionChecker
import com.jarvis.ai.core.security.Risk
import kotlinx.serialization.json.JsonObject

class FakeTime(var now: Long = 1_000_000L) : TimeSource {
    override fun nowMs(): Long = now
}

typealias Script = suspend () -> String

fun ok(text: String): Script = { text }

fun fail(error: ProviderError): Script = { throw error }

/** Provider that plays back [steps] in order, one per call. */
class FakeProvider(
    override val id: ProviderId,
    var configured: Boolean = true,
    vararg steps: Script,
) : LlmProvider {
    private val queue = ArrayDeque(steps.toList())
    var calls = 0
        private set

    override fun isConfigured(): Boolean = configured

    override suspend fun complete(request: LlmRequest): String {
        calls++
        val next = queue.removeFirstOrNull() ?: error("FakeProvider ${id.name} has no more scripted steps")
        return next()
    }
}

/** LlmClient that returns scripted texts and remembers every request it received. */
class FakeLlm(vararg replies: String) : LlmClient {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val text = queue.removeFirstOrNull() ?: error("FakeLlm has no more scripted replies")
        return LlmResponse(text, ProviderId.GROQ, 1, emptyList())
    }
}

class FakeTool(
    name: String,
    risk: Risk = Risk.LOW,
    params: List<ParamSpec> = emptyList(),
    permissions: Set<String> = emptySet(),
    timeoutMs: Long = 1_000L,
    maxRetries: Int = 0,
    private val behaviour: suspend (ToolArgs) -> ToolResult = { ToolResult.Success("ok", Verification.CONFIRMED) },
) : Tool {
    override val spec = ToolSpec(
        name = name,
        description = "fake tool $name",
        params = params,
        risk = risk,
        requiredPermissions = permissions,
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        resultFormat = "test",
    )

    var executions = 0
        private set

    override fun describe(args: ToolArgs): String = "Do ${spec.name} ${args.asMap()}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        executions++
        return behaviour(args)
    }
}

class FakePermissions(private val denied: Set<String> = emptySet()) : PermissionChecker {
    override fun missing(required: Set<String>): Set<String> = required.intersect(denied)
}

fun step(
    index: Int,
    tool: String,
    params: JsonObject = JsonObject(emptyMap()),
    critical: Boolean = false,
    description: String = tool,
) = PlanStep(index, tool, description, params, critical)

fun plan(vararg steps: PlanStep, goal: String = "test goal") = Plan(goal, steps.toList())
