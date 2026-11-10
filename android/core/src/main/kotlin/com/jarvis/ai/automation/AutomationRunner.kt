package com.jarvis.ai.automation

import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.planner.Plan
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.PlanStep
import com.jarvis.ai.ai.planner.Replanner
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.core.permissions.PermissionChecker
import com.jarvis.ai.core.security.AuditLog
import com.jarvis.ai.core.security.ConfirmationDecision
import com.jarvis.ai.core.security.ConfirmationRequester
import com.jarvis.ai.core.security.Risk
import java.time.ZoneId

/**
 * Runs automations through the normal [Executor], with three safeguards that do not depend on the stored data being honest:
 *  - the registry it runs against holds only allow-listed tools that are LOW risk right now;
 *  - the confirmation gate always answers "denied", so nothing that asks for approval can run unattended, and nothing is approved for the person;
 *  - a failing step is reported as failed, never retried through another plan (there is no replanner).
 */
class AutomationRunner(
    private val store: AutomationStore,
    private val fullRegistry: ToolRegistry,
    permissions: PermissionChecker,
    auditLog: AuditLog,
    private val time: TimeSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val registry = ToolRegistry(
        fullRegistry.all().filter { it.spec.name in AutomationRules.ALLOWED_TOOLS && it.spec.risk == Risk.LOW },
    )

    private val executor = Executor(
        registry = registry,
        permissions = permissions,
        gate = ConfirmationRequester { _, _, _, _ -> ConfirmationDecision.Denied },
        auditLog = auditLog,
        replanner = Replanner { _, _, _, _ -> PlanResult.Failed("Automations never replan.") },
        time = time,
        maxReplans = 0,
    )

    data class Outcome(val automationId: String, val name: String, val ok: Boolean, val summary: String)

    /** Runs every automation that is due. Safe to call at any time; one that is not due is left alone. */
    suspend fun runDue(): List<Outcome> = store.due(time.nowMs()).map { run(it) }

    /** "Run now" from the person. Runs the same restricted way and does not move the schedule. */
    suspend fun runNow(id: String): Outcome? {
        val automation = store.automations.value.firstOrNull { it.id == id } ?: return null
        return run(automation, reschedule = false)
    }

    private suspend fun run(automation: Automation, reschedule: Boolean = true): Outcome {
        val problems = AutomationRules.validate(automation.name, automation.trigger, automation.actions, fullRegistry)
        val outcome = if (problems.isNotEmpty()) {
            // The stored automation no longer passes today's rules (a tool changed risk, for example): nothing runs.
            Outcome(automation.id, automation.name, false, "Not run: ${problems.first()}")
        } else {
            val plan = Plan(
                goal = automation.name,
                steps = automation.actions.mapIndexed { i, a -> PlanStep(i + 1, a.tool, a.tool, a.parameters, critical = false) },
            )
            val report = executor.execute(plan)
            val failed = report.outcomes.filter { it.status != StepStatus.SUCCEEDED }
            val ok = failed.isEmpty() && report.outcomes.size == automation.actions.size
            Outcome(automation.id, automation.name, ok, if (ok) "Ran ${report.outcomes.size} action(s)." else failed.firstOrNull()?.message ?: "Nothing ran.")
        }
        store.recordRun(automation.id, outcome.ok, outcome.summary, zone(), reschedule)
        return outcome
    }
}
