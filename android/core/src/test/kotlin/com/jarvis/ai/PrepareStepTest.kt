package com.jarvis.ai

import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Replanner
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.AuditKind
import com.jarvis.ai.core.security.ConfirmationDecision
import com.jarvis.ai.core.security.ConfirmationRequester
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The prepare step: a tool turns the model's loose request into the exact action (one name, one number) BEFORE the
 * user is asked, so the confirmation shows, and approves, what will really happen.
 */
class PrepareStepTest {

    private val audit = InMemoryAuditLog()
    private var replans = 0
    private val replanner = Replanner { _, _, _, _ ->
        replans++
        PlanResult.Failed("no replanning in this test")
    }

    /** A tool whose prepare() the test scripts. It records what it was asked to execute. */
    private class ResolvingTool(
        risk: Risk = Risk.HIGH,
        private val prepareWith: suspend (ToolArgs) -> Prepared,
    ) : Tool {
        override val spec = ToolSpec(
            name = "call_contact",
            description = "calls someone",
            params = listOf(ParamSpec("name", ParamType.STRING, "who")),
            risk = risk,
            timeoutMs = 1_000L,
            resultFormat = "test",
        )
        val executed = mutableListOf<Map<String, Any>>()
        var prepares = 0

        override suspend fun prepare(args: ToolArgs): Prepared {
            prepares++
            return prepareWith(args)
        }

        override fun describe(args: ToolArgs): String =
            "Call ${args.string("name")}" + (args.stringOrNull("number")?.let { " on $it" } ?: "")

        override suspend fun execute(args: ToolArgs): ToolResult {
            executed += args.asMap()
            return ToolResult.Success("Asked Android to call.", Verification.HANDED_OFF)
        }
    }

    private fun resolved(name: String, number: String) = Prepared.Ready(ToolArgs(mapOf("name" to name, "number" to number)))

    /** Records what the user would have been shown, then answers with [decide]. */
    private class RecordingRequester(val decide: (String) -> ConfirmationDecision) : ConfirmationRequester {
        val summaries = mutableListOf<String>()
        val fingerprints = mutableListOf<String>()
        var order: MutableList<String>? = null

        override suspend fun request(toolName: String, summary: String, risk: Risk, fingerprint: String): ConfirmationDecision {
            summaries += summary
            fingerprints += fingerprint
            order?.add("confirm")
            return decide(fingerprint)
        }
    }

    private fun executor(tool: Tool, requester: ConfirmationRequester, permissions: FakePermissions = FakePermissions()) = Executor(
        registry = ToolRegistry(listOf(tool)),
        permissions = permissions,
        gate = requester,
        auditLog = audit,
        replanner = replanner,
        policy = { com.jarvis.ai.ai.executor.ExecutionPolicy() },
        time = FakeTime(),
        maxReplans = 2,
    )

    private val callMom = plan(step(1, "call_contact", buildJsonObject { put("name", "mom") }, critical = true))

    @Test
    fun theConfirmationShowsTheResolvedNumberNotTheRequestedName() = runTest {
        val tool = ResolvingTool { resolved("Mom Patel", "+919876543210") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        executor(tool, requester).execute(callMom)

        assertEquals(1, requester.summaries.size)
        assertTrue(requester.summaries.single().contains("+919876543210"), requester.summaries.single())
        assertTrue(requester.summaries.single().contains("Mom Patel"))
    }

    @Test
    fun whatRunsIsExactlyWhatWasPreparedAndApproved() = runTest {
        val tool = ResolvingTool { resolved("Mom Patel", "+919876543210") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        executor(tool, requester).execute(callMom)

        assertEquals(listOf<Map<String, Any>>(mapOf("name" to "Mom Patel", "number" to "+919876543210")), tool.executed)
        val expected = ToolArgs(mapOf("name" to "Mom Patel", "number" to "+919876543210")).fingerprint("call_contact")
        assertEquals(expected, requester.fingerprints.single(), "the approval is bound to the prepared arguments")
    }

    @Test
    fun approvingADifferentNumberRunsNothing() = runTest {
        val tool = ResolvingTool { resolved("Mom Patel", "+919876543210") }
        // A gate that "approves" the request the model originally described (name only), not the resolved action.
        val forged = ToolArgs(mapOf("name" to "mom")).fingerprint("call_contact")
        val requester = RecordingRequester { ConfirmationDecision.Approved(forged) }

        val report = executor(tool, requester).execute(callMom)

        assertTrue(tool.executed.isEmpty())
        assertEquals(StepStatus.BLOCKED, report.outcomes.single().status)
        assertTrue(audit.recent().any { it.kind == AuditKind.CONFIRMATION_MISMATCH })
    }

    @Test
    fun prepareRunsBeforeTheUserIsAsked() = runTest {
        val order = mutableListOf<String>()
        val tool = ResolvingTool { order += "prepare"; resolved("Mom Patel", "+919876543210") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }.also { it.order = order }

        executor(tool, requester).execute(callMom)

        assertEquals(listOf("prepare", "confirm"), order)
    }

    @Test
    fun aStopMeansNoQuestionAndNoExecution() = runTest {
        val tool = ResolvingTool { Prepared.Stop(ToolError.Failed("There is no contact called mom.")) }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val report = executor(tool, requester).execute(callMom)

        assertTrue(requester.summaries.isEmpty(), "the user is not asked to confirm something that cannot happen")
        assertTrue(tool.executed.isEmpty())
        assertEquals(StepStatus.FAILED, report.outcomes.single().status)
        assertEquals("There is no contact called mom.", report.outcomes.single().message)
    }

    @Test
    fun aQuestionForTheUserIsNeverReplannedAround() = runTest {
        val tool = ResolvingTool { Prepared.Stop(ToolError.NeedsInput("Which Mom: Mom Home or Mom Work?")) }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val report = executor(tool, requester).execute(callMom)

        assertEquals(0, replans, "asking the model to pick for the user would defeat the question")
        assertEquals("Which Mom: Mom Home or Mom Work?", report.outcomes.single().message)
        assertTrue(tool.executed.isEmpty())
    }

    @Test
    fun anOrdinaryFailureInPrepareMayBeReplanned() = runTest {
        val tool = ResolvingTool { Prepared.Stop(ToolError.Failed("The contacts search failed.")) }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        executor(tool, requester).execute(callMom)

        assertEquals(1, replans)
    }

    @Test
    fun aCrashInPrepareIsContainedAndNothingRuns() = runTest {
        val tool = ResolvingTool { error("boom") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val report = executor(tool, requester).execute(callMom)

        assertTrue(tool.executed.isEmpty())
        assertTrue(requester.summaries.isEmpty())
        assertEquals(StepStatus.FAILED, report.outcomes.single().status)
        assertTrue(report.outcomes.single().message.contains("IllegalStateException"))
    }

    @Test
    fun aPrepareThatNeverFinishesTimesOutInsteadOfHangingTheAssistant() = runTest {
        val tool = ResolvingTool { awaitCancellation() }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val report = executor(tool, requester).execute(callMom)

        assertEquals(StepStatus.FAILED, report.outcomes.single().status)
        assertTrue(tool.executed.isEmpty())
    }

    @Test
    fun cancellingWhilePreparingIsNotSwallowed() = runTest {
        val tool = ResolvingTool { throw CancellationException("cancelled") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val running = async { runCatching { executor(tool, requester).execute(callMom) } }
        runCurrent()

        val result = running.await()
        assertTrue(result.exceptionOrNull() is CancellationException, "cancellation must propagate, not become a failed step")
        assertTrue(tool.executed.isEmpty())
    }

    @Test
    fun theAndroidPermissionIsCheckedBeforePrepareSoNothingIsReadWithoutIt() = runTest {
        val tool = ResolvingTool { resolved("Mom Patel", "+919876543210") }
        val gated = object : Tool by tool {
            override val spec = tool.spec.copy(requiredPermissions = setOf("android.permission.READ_CONTACTS"))
        }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        val report = executor(gated, requester, FakePermissions(setOf("android.permission.READ_CONTACTS"))).execute(callMom)

        assertEquals(0, tool.prepares, "contacts must not be searched when the permission is off")
        assertEquals(StepStatus.FAILED, report.outcomes.single().status)
    }

    @Test
    fun aDeclinedCallIsNotRunAndIsAudited() = runTest {
        val tool = ResolvingTool { resolved("Mom Patel", "+919876543210") }
        val requester = RecordingRequester { ConfirmationDecision.Denied }

        val report = executor(tool, requester).execute(callMom)

        assertEquals(StepStatus.DENIED, report.outcomes.single().status)
        assertTrue(tool.executed.isEmpty())
        assertTrue(audit.recent().any { it.kind == AuditKind.CONFIRMATION_DENIED })
    }

    @Test
    fun aMediumToolStillGetsItsPrepareStepWithoutAQuestion() = runTest {
        val tool = ResolvingTool(Risk.MEDIUM) { resolved("Mom Patel", "+919876543210") }
        val requester = RecordingRequester { ConfirmationDecision.Approved(it) }

        executor(tool, requester).execute(callMom)

        assertEquals(1, tool.prepares)
        assertTrue(requester.summaries.isEmpty(), "medium risk is not confirmed by default")
        assertEquals(1, tool.executed.size)
    }
}
