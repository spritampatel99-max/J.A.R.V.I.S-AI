package com.jarvis.ai.ai.executor

import com.jarvis.ai.ai.planner.Plan
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.PlanStep
import com.jarvis.ai.ai.planner.Replanner
import com.jarvis.ai.ai.tools.ArgValidator
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ValidationResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.permissions.PermissionChecker
import com.jarvis.ai.core.security.AuditEvent
import com.jarvis.ai.core.security.AuditKind
import com.jarvis.ai.core.security.AuditLog
import com.jarvis.ai.core.security.ConfirmationDecision
import com.jarvis.ai.core.security.ConfirmationRequester
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.core.security.SecretRedactor
import com.jarvis.ai.core.security.UntrustedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** User-adjustable safety settings. */
data class ExecutionPolicy(
    /**
     * Tools at or above this risk need a native confirmation. [Risk.HIGH] is the top of the
     * scale, so HIGH tools are always confirmed no matter what is configured here.
     */
    val confirmAt: Risk = Risk.HIGH,
)

enum class StepStatus {
    SUCCEEDED,
    FAILED,

    /** The user said no. Never retried and never replanned around. */
    DENIED,

    /** The confirmation timed out with no answer. Treated like a denial. */
    EXPIRED,

    /** Refused by the app before running (unknown tool, approval no longer matches). */
    BLOCKED,
}

data class StepOutcome(
    val step: PlanStep,
    val status: StepStatus,
    /** Safe to show the user. For successes this is the tool's own message. */
    val message: String,
    /** Only set for [StepStatus.SUCCEEDED]. */
    val verification: Verification? = null,
    val attempts: Int = 0,
    val data: Map<String, String> = emptyMap(),
)

data class ExecutionReport(
    val goal: String,
    val outcomes: List<StepOutcome>,
    val replans: Int,
) {
    val succeededCount: Int get() = outcomes.count { it.status == StepStatus.SUCCEEDED }
    val allSucceeded: Boolean get() = outcomes.isNotEmpty() && outcomes.all { it.status == StepStatus.SUCCEEDED }

    /**
     * Plain-text report built only from recorded outcomes. No model writes this, so it
     * cannot claim success that did not happen. A step that was merely handed to another
     * app is marked as such rather than as confirmed.
     */
    fun summary(): String = buildString {
        for (o in outcomes) {
            val mark = when (o.status) {
                StepStatus.SUCCEEDED -> if (o.verification == Verification.CONFIRMED) "✔" else "➜"
                StepStatus.FAILED -> "✘"
                StepStatus.DENIED, StepStatus.EXPIRED -> "⊘"
                StepStatus.BLOCKED -> "⛔"
            }
            appendLine("$mark ${o.step.description}: ${o.message}")
        }
        if (replans > 0) appendLine("(Re-planned $replans time${if (replans == 1) "" else "s"} after a failure.)")
    }.trimEnd()
}

/** Progress callbacks for the UI. All have empty defaults. */
interface ExecutionListener {
    fun onStepStarted(step: PlanStep) {}

    fun onStepFinished(outcome: StepOutcome) {}

    fun onReplan(attempt: Int) {}
}

object NoListener : ExecutionListener

/**
 * Runs a plan one step at a time. For every step, in this order:
 *
 *  1. The tool must exist in the registry (anything else is BLOCKED).
 *  2. Parameters are validated against the tool's schema.
 *  3. Required Android permissions are checked.
 *  4. The tool resolves the request into the exact action ([Tool.prepare], for example which
 *     contact and which number). It may stop here with a question or a reason, and nothing is
 *     confirmed or run.
 *  5. If the tool's risk is at or above the confirmation threshold, the user must approve
 *     in native UI. The approval is bound to a fingerprint of (tool, resolved arguments).
 *  6. The tool runs with a timeout; retryable failures are retried (never for HIGH risk).
 *  7. The real result is recorded and audited.
 *
 * Steps are independent: one failing does not stop the others, so the final report can
 * list successes and failures separately. Only a failed step the planner marked
 * `critical` (and whose failure a different approach could fix) triggers a replan, at
 * most [maxReplans] times. A denial, an expired confirmation or a missing permission is
 * never replanned around.
 *
 * Once any tool returns untrusted outside content, the threshold drops to MEDIUM for the
 * rest of the run, so injected text cannot ride along to a sensitive action unnoticed.
 */
class Executor(
    private val registry: ToolRegistry,
    private val permissions: PermissionChecker,
    private val gate: ConfirmationRequester,
    private val auditLog: AuditLog,
    private val replanner: Replanner,
    private val policy: () -> ExecutionPolicy = { ExecutionPolicy() },
    private val time: TimeSource = SystemTimeSource,
    private val maxReplans: Int = 2,
) {

    private class StepRun(
        val outcome: StepOutcome,
        val replannable: Boolean = false,
        val untrusted: Boolean = false,
    )

    suspend fun execute(plan: Plan, listener: ExecutionListener = NoListener): ExecutionReport {
        val outcomes = mutableListOf<StepOutcome>()
        var untrustedSeen = false
        var steps = plan.steps
        var replans = 0

        while (true) {
            var replanTarget: StepOutcome? = null

            for (step in steps) {
                currentCoroutineContext().ensureActive()
                listener.onStepStarted(step)
                val run = runStep(step, untrustedSeen)
                if (run.untrusted) untrustedSeen = true
                outcomes += run.outcome
                listener.onStepFinished(run.outcome)
                if (replanTarget == null && step.critical && run.replannable) replanTarget = run.outcome
            }

            val target = replanTarget ?: break
            if (replans >= maxReplans) break
            replans++
            listener.onReplan(replans)

            val completed = outcomes.filter { it.status == StepStatus.SUCCEEDED }.map { it.step }
            val revised = replanner.replan(plan.goal, completed, target.step, target.message)
            if (revised is PlanResult.Ready) steps = revised.plan.steps else break
        }
        return ExecutionReport(plan.goal, outcomes.toList(), replans)
    }

    private suspend fun runStep(step: PlanStep, untrustedSeen: Boolean): StepRun {
        val tool = registry.get(step.tool)
        if (tool == null) {
            val message = "Unknown tool '${step.tool.take(40)}'; nothing was run."
            audit(AuditKind.BLOCKED, step.tool.take(40), Risk.HIGH, message, null)
            return StepRun(StepOutcome(step, StepStatus.BLOCKED, message))
        }
        val spec = tool.spec

        return try {
            val validated = when (val check = ArgValidator.validate(spec, step.parameters)) {
                is ValidationResult.Invalid ->
                    return failed(step, ToolError.InvalidArguments(check.errors.joinToString("; ")), 0)
                is ValidationResult.Valid -> check.args
            }

            val missing = permissions.missing(spec.requiredPermissions)
            if (missing.isNotEmpty()) {
                val error = ToolError.PermissionDenied(
                    missing,
                    "Permission needed: ${missing.joinToString(", ") { it.substringAfterLast('.') }}. Grant it and ask again.",
                )
                if (spec.risk >= Risk.MEDIUM) audit(AuditKind.BLOCKED, spec.name, spec.risk, tool.describe(validated), error.message)
                return failed(step, error, 0)
            }

            // Resolve the request into the exact action first, so the confirmation shows what will really happen.
            val args = when (val prepared = runPrepare(tool, validated)) {
                is Prepared.Stop -> {
                    if (spec.risk >= Risk.MEDIUM) audit(AuditKind.BLOCKED, spec.name, spec.risk, tool.describe(validated), prepared.error.message)
                    return failed(step, prepared.error, 0)
                }
                is Prepared.Ready -> prepared.args
            }

            val threshold = if (untrustedSeen) minOf(policy().confirmAt, Risk.MEDIUM) else policy().confirmAt
            if (spec.risk >= threshold) {
                confirmationBlock(step, tool, args)?.let { return it }
            }

            val (result, attempts) = executeWithRetries(tool, args)
            when (result) {
                is ToolResult.Success -> {
                    if (spec.risk >= Risk.MEDIUM) audit(AuditKind.EXECUTED, spec.name, spec.risk, tool.describe(args), result.message)
                    val shown = if (result.untrusted) UntrustedContent.wrap(spec.name, result.message) else result.message
                    StepRun(
                        StepOutcome(step, StepStatus.SUCCEEDED, shown, result.verification, attempts, result.data),
                        untrusted = result.untrusted,
                    )
                }
                is ToolResult.Failure -> {
                    if (spec.risk >= Risk.MEDIUM) audit(AuditKind.FAILED, spec.name, spec.risk, tool.describe(args), result.error.message)
                    failed(step, result.error, attempts)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A tool bug must not abort the whole plan, and must not be hidden: it is reported as a failure.
            failed(step, ToolError.Failed("Unexpected ${e.javaClass.simpleName}: ${SecretRedactor.redactPatterns(e.message.orEmpty()).take(200)}"), 0)
        }
    }

    /** @return null when the user approved and the action may run; otherwise the outcome to report. */
    private suspend fun confirmationBlock(step: PlanStep, tool: Tool, args: ToolArgs): StepRun? {
        val spec = tool.spec
        val summary = tool.describe(args)
        val fingerprint = args.fingerprint(spec.name)

        audit(AuditKind.CONFIRMATION_REQUESTED, spec.name, spec.risk, summary, null)
        return when (val decision = gate.request(spec.name, summary, spec.risk, fingerprint)) {
            is ConfirmationDecision.Approved -> {
                if (decision.fingerprint != args.fingerprint(spec.name)) {
                    val message = "The approved action no longer matches what was about to run; nothing was run."
                    audit(AuditKind.CONFIRMATION_MISMATCH, spec.name, spec.risk, summary, null)
                    StepRun(StepOutcome(step, StepStatus.BLOCKED, message))
                } else {
                    audit(AuditKind.CONFIRMATION_APPROVED, spec.name, spec.risk, summary, null)
                    null
                }
            }
            ConfirmationDecision.Denied -> {
                audit(AuditKind.CONFIRMATION_DENIED, spec.name, spec.risk, summary, null)
                StepRun(StepOutcome(step, StepStatus.DENIED, "You declined this action, so it was not run."))
            }
            ConfirmationDecision.Expired -> {
                audit(AuditKind.CONFIRMATION_EXPIRED, spec.name, spec.risk, summary, null)
                StepRun(StepOutcome(step, StepStatus.EXPIRED, "No answer to the confirmation, so it was not run."))
            }
        }
    }

    private suspend fun runPrepare(tool: Tool, args: ToolArgs): Prepared {
        val timeoutMs = tool.spec.timeoutMs
        return try {
            withTimeoutOrNull(timeoutMs) { tool.prepare(args) }
                ?: Prepared.Stop(ToolError.Timeout(timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Prepared.Stop(
                ToolError.Failed("Unexpected ${e.javaClass.simpleName}: ${SecretRedactor.redactPatterns(e.message.orEmpty()).take(200)}"),
            )
        }
    }

    private suspend fun executeWithRetries(tool: Tool, args: ToolArgs): Pair<ToolResult, Int> {
        val spec = tool.spec
        // Repeating a sensitive action automatically could send two messages or delete twice.
        val maxRetries = if (spec.risk == Risk.HIGH) 0 else spec.maxRetries
        var attempt = 0
        while (true) {
            attempt++
            val result = runOnce(tool, args)
            val retry = result is ToolResult.Failure && result.error.retryable && attempt <= maxRetries
            if (!retry) return result to attempt
            delay(RETRY_BASE_DELAY_MS * attempt)
        }
    }

    private suspend fun runOnce(tool: Tool, args: ToolArgs): ToolResult {
        val timeoutMs = tool.spec.timeoutMs
        return try {
            withTimeoutOrNull(timeoutMs) { tool.execute(args) }
                ?: ToolResult.Failure(ToolError.Timeout(timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure(
                ToolError.Failed("Unexpected ${e.javaClass.simpleName}: ${SecretRedactor.redactPatterns(e.message.orEmpty()).take(200)}"),
            )
        }
    }

    private fun failed(step: PlanStep, error: ToolError, attempts: Int): StepRun =
        StepRun(
            outcome = StepOutcome(step, StepStatus.FAILED, error.message, attempts = attempts),
            replannable = error.replannable,
        )

    private fun audit(kind: AuditKind, tool: String, risk: Risk, summary: String, detail: String?) {
        auditLog.record(AuditEvent(time.nowMs(), kind, tool, risk, summary, detail))
    }

    private companion object {
        const val RETRY_BASE_DELAY_MS = 300L
    }
}
