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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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

    /**
     * Runs every automation that is due, each on its own: one that fails or is cut off never stops the others. Each occurrence is claimed and saved
     * before it runs ([AutomationStore.claim]), so the same occurrence cannot run twice, and it is never run again after being interrupted.
     */
    suspend fun runDue(): List<Outcome> {
        val outcomes = ArrayList<Outcome>()
        for (automation in store.due(time.nowMs())) {
            if (!store.claim(automation.id, zone())) continue
            outcomes += run(automation)
        }
        return outcomes
    }

    /** "Run now" from the person. Runs the same restricted way and does not move the schedule. */
    suspend fun runNow(id: String): Outcome? {
        val automation = store.automations.value.firstOrNull { it.id == id } ?: return null
        if (!store.claim(id, zone(), scheduled = false)) return Outcome(id, automation.name, false, "It is already running.")
        return run(automation)
    }

    /** Runs an automation that has already been claimed, and always records how it ended. */
    private suspend fun run(automation: Automation): Outcome {
        val outcome = try {
            execute(automation)
        } catch (e: CancellationException) {
            // The wake-up ran out of time or the app is closing. The result is written even so, as "interrupted": the actions may or may not
            // have happened, which is different from a failure, and they are not run again.
            withContext(NonCancellable) { store.finishRun(automation.id, ok = false, summary = AutomationStore.INTERRUPTED_NOTE, interrupted = true) }
            throw e
        } catch (e: Exception) {
            Outcome(automation.id, automation.name, false, "Failed unexpectedly (${e.javaClass.simpleName}).")
        }
        store.finishRun(automation.id, outcome.ok, outcome.summary)
        return outcome
    }

    private suspend fun execute(automation: Automation): Outcome {
        val problems = AutomationRules.validate(automation.name, automation.trigger, automation.actions, fullRegistry)
        if (problems.isNotEmpty()) {
            // The stored automation no longer passes today's rules (a tool changed risk, for example): nothing runs.
            return Outcome(automation.id, automation.name, false, "Not run: ${problems.first()}")
        }
        val plan = Plan(
            goal = automation.name,
            steps = automation.actions.mapIndexed { i, a -> PlanStep(i + 1, a.tool, a.tool, a.parameters, critical = false) },
        )
        val report = executor.execute(plan)
        val failed = report.outcomes.filter { it.status != StepStatus.SUCCEEDED }
        val ok = failed.isEmpty() && report.outcomes.size == automation.actions.size
        return Outcome(automation.id, automation.name, ok, if (ok) "Ran ${report.outcomes.size} action(s)." else failed.firstOrNull()?.message ?: "Nothing ran.")
    }
}
