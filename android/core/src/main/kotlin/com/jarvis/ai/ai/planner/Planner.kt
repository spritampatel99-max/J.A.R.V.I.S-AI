package com.jarvis.ai.ai.planner

import com.jarvis.ai.ai.fallback.AllProvidersFailedException
import com.jarvis.ai.ai.fallback.LlmClient
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.core.security.UntrustedContent
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Turns a user request into a structured plan using only registered tools.
 *
 * The model's output is treated as untrusted input: it is parsed defensively, every tool
 * name must exist in the registry, every step's parameters are checked against the tool's
 * schema, and the number of steps is capped. If the output is invalid, the problems are
 * fed back once so the model can correct itself. There is deliberately NO silent fallback
 * such as "search the web for the goal": if no valid plan comes out, the user is told.
 */
class Planner(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Replanner {

    suspend fun plan(goal: String, history: List<ChatMessage> = emptyList()): PlanResult {
        val messages = history.takeLast(MAX_HISTORY_MESSAGES) + ChatMessage(ChatRole.USER, goal)
        return run(goal, systemPrompt(), messages)
    }

    override suspend fun replan(
        goal: String,
        completed: List<PlanStep>,
        failed: PlanStep,
        error: String,
    ): PlanResult {
        val done = if (completed.isEmpty()) "  (none)" else completed.joinToString("\n") { "  - ${it.tool}: ${it.description}" }
        val prompt = buildString {
            appendLine("Original request: $goal")
            appendLine()
            appendLine("Already completed (do NOT repeat these):")
            appendLine(done)
            appendLine()
            appendLine("This step failed: ${failed.tool} - ${failed.description}")
            appendLine("Failure: ${SecretRedactor.redactPatterns(error).take(300)}")
            appendLine()
            append("Return a revised plan for only the remaining work, using a different approach or tool. ")
            append("If nothing else can work, return no steps and explain in \"reply\".")
        }
        return run(goal, systemPrompt(), listOf(ChatMessage(ChatRole.USER, prompt)))
    }

    private suspend fun run(goal: String, system: String, initial: List<ChatMessage>): PlanResult {
        val messages = initial.toMutableList()
        var lastProblems: List<String> = emptyList()

        repeat(MAX_ATTEMPTS) {
            val response = try {
                llm.complete(
                    LlmRequest(
                        system = system,
                        messages = messages.toList(),
                        maxTokens = 1_536,
                        temperature = 0.2,
                        jsonMode = true,
                    ),
                )
            } catch (e: AllProvidersFailedException) {
                return PlanResult.Failed(e.message ?: "No AI provider could be reached")
            }

            when (val outcome = interpret(goal, response.text)) {
                is Interpretation.Steps -> return PlanResult.Ready(Plan(goal, outcome.steps), response.provider)
                is Interpretation.Clarify -> return PlanResult.NeedsClarification(outcome.question, response.provider)
                is Interpretation.Reply -> return PlanResult.Reply(outcome.text, response.provider)
                is Interpretation.Invalid -> {
                    lastProblems = outcome.problems
                    messages += ChatMessage(ChatRole.ASSISTANT, response.text.take(2_000))
                    messages += ChatMessage(
                        ChatRole.USER,
                        "That plan was rejected:\n" +
                            outcome.problems.joinToString("\n") { "- $it" } +
                            "\nReturn corrected JSON only.",
                    )
                }
            }
        }
        return PlanResult.Failed(
            "I couldn't turn that into a valid plan (${lastProblems.firstOrNull() ?: "unknown problem"}).",
        )
    }

    private sealed interface Interpretation {
        data class Steps(val steps: List<PlanStep>) : Interpretation

        data class Clarify(val question: String) : Interpretation

        data class Reply(val text: String) : Interpretation

        data class Invalid(val problems: List<String>) : Interpretation
    }

    private fun interpret(goal: String, raw: String): Interpretation {
        val root = parseObject(raw)
            ?: return Interpretation.Invalid(listOf("The response was not a single JSON object"))

        val clarification = root["clarification"].text()
        if (clarification != null) return Interpretation.Clarify(clarification)

        val stepsElement = root["steps"]
        if (stepsElement != null && stepsElement !is JsonArray && !(stepsElement is JsonPrimitive && !stepsElement.isString)) {
            return Interpretation.Invalid(listOf("\"steps\" must be a list"))
        }
        val stepElements = (stepsElement as? JsonArray) ?: JsonArray(emptyList())

        if (stepElements.isEmpty()) {
            val reply = root["reply"].text()
            return if (reply != null) {
                Interpretation.Reply(reply)
            } else {
                Interpretation.Invalid(listOf("Return \"steps\", or a \"reply\", or a \"clarification\""))
            }
        }
        if (stepElements.size > MAX_STEPS) {
            return Interpretation.Invalid(listOf("Too many steps (${stepElements.size}); use at most $MAX_STEPS"))
        }

        val problems = mutableListOf<String>()
        val steps = mutableListOf<PlanStep>()
        for ((position, element) in stepElements.withIndex()) {
            val number = position + 1
            val obj = element as? JsonObject
            if (obj == null) {
                problems += "Step $number is not an object"
                continue
            }
            val toolName = obj["tool"].text()
            if (toolName == null) {
                problems += "Step $number has no tool"
                continue
            }
            val tool = registry.get(toolName)
            if (tool == null) {
                problems += "Step $number uses unknown tool '${toolName.take(40)}'. Available tools: " +
                    registry.all().joinToString(", ") { it.spec.name }
                continue
            }
            val parameters = (obj["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
            when (val check = ArgValidator.validate(tool.spec, parameters)) {
                is ValidationResult.Invalid -> problems += check.errors.map { "Step $number ($toolName): $it" }
                is ValidationResult.Valid -> steps += PlanStep(
                    index = number,
                    tool = toolName,
                    description = obj["description"].text()?.take(200) ?: toolName,
                    parameters = parameters,
                    critical = (obj["critical"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
        }
        return if (problems.isEmpty()) Interpretation.Steps(steps) else Interpretation.Invalid(problems)
    }

    /** Accepts plain JSON or JSON wrapped in a markdown fence / stray prose. */
    private fun parseObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun JsonElement?.text(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun systemPrompt(): String {
        val now: ZonedDateTime = ZonedDateTime.now(clock)
        return buildString {
            appendLine("You are the planning module of J.A.R.V.I.S., an assistant running on an Android phone.")
            appendLine("Decide whether the user's request needs tools, and if so produce a plan using ONLY the tools below.")
            appendLine()
            appendLine("Current local time: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} (${now.zone}), ${now.dayOfWeek}.")
            appendLine("Resolve words like \"tomorrow\" yourself and pass dates as YYYY-MM-DD.")
            appendLine()
            appendLine("RULES:")
            appendLine("- Use at most $MAX_STEPS steps, and the fewest that do the job.")
            appendLine("- Steps are independent: never refer to the result of an earlier step.")
            appendLine("- Never invent a tool or a parameter. If no tool fits, answer in \"reply\" and say honestly what you cannot do on this phone.")
            appendLine("- If something essential is missing (for example the time of an alarm), ask ONE short question in \"clarification\" and return no steps.")
            appendLine("- Set \"critical\": true only when the request fails without that step AND a different tool or approach could plausibly achieve it. Otherwise false.")
            appendLine("- You cannot confirm, approve or cancel anything. Sensitive actions are confirmed by the user in the app, outside this conversation.")
            appendLine("- ${UntrustedContent.SYSTEM_RULE}")
            appendLine()
            appendLine("TOOLS:")
            append(registry.promptCatalog())
            appendLine()
            appendLine("OUTPUT: return ONLY one JSON object, no markdown:")
            appendLine("{")
            appendLine("  \"reply\": \"text to say when no tool is needed, otherwise null\",")
            appendLine("  \"clarification\": \"one question, otherwise null\",")
            appendLine("  \"steps\": [")
            appendLine("    {\"tool\": \"tool_name\", \"description\": \"what this step does\", \"parameters\": {}, \"critical\": false}")
            appendLine("  ]")
            appendLine("}")
        }
    }

    companion object {
        const val MAX_STEPS = 5
        private const val MAX_ATTEMPTS = 2
        private const val MAX_HISTORY_MESSAGES = 10
    }
}

/** Convenience for the UI: which provider planned this. */
val PlanResult.provider: ProviderId?
    get() = when (this) {
        is PlanResult.Ready -> provider
        is PlanResult.Reply -> provider
        is PlanResult.NeedsClarification -> provider
        is PlanResult.Failed -> null
    }
