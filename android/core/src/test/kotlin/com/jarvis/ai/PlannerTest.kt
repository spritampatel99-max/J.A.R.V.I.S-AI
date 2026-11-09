package com.jarvis.ai

import com.jarvis.ai.ai.fallback.AllProvidersFailedException
import com.jarvis.ai.ai.fallback.LlmClient
import com.jarvis.ai.ai.fallback.LlmResponse
import com.jarvis.ai.ai.fallback.ProviderFailure
import com.jarvis.ai.ai.planner.Plan
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Planner
import com.jarvis.ai.ai.planner.answeredBy
import com.jarvis.ai.ai.planner.localModel
import com.jarvis.ai.ai.planner.provider
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.ToolRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class PlannerTest {

    private val registry = ToolRegistry(
        listOf(
            FakeTool("flashlight", params = listOf(ParamSpec("state", ParamType.ENUM, "state", enumValues = listOf("on", "off")))),
            FakeTool(
                "set_alarm",
                params = listOf(
                    ParamSpec("hour", ParamType.INT, "hour", min = 0, max = 23),
                    ParamSpec("minute", ParamType.INT, "minute", min = 0, max = 59),
                ),
            ),
            FakeTool("open_youtube", params = listOf(ParamSpec("query", ParamType.STRING, "q", required = false))),
        ),
    )
    private val clock = Clock.fixed(Instant.parse("2026-11-06T03:47:00Z"), ZoneOffset.UTC)

    private fun planner(llm: LlmClient) = Planner(llm, registry, clock)

    private val validPlan = """
        {"reply": null, "clarification": null, "steps": [
          {"tool": "flashlight", "description": "Turn on the flashlight", "parameters": {"state": "on"}, "critical": false},
          {"tool": "set_alarm", "description": "Alarm at 06:00", "parameters": {"hour": 6, "minute": 0}, "critical": true}
        ]}
    """.trimIndent()

    @Test
    fun parsesAValidPlanIntoSteps() = runTest {
        val result = planner(FakeLlm(validPlan)).plan("flashlight on and alarm at 6")

        val ready = assertIs<PlanResult.Ready>(result)
        assertEquals(ProviderId.GROQ, ready.provider)
        assertEquals(listOf("flashlight", "set_alarm"), ready.plan.steps.map { it.tool })
        assertEquals(listOf(1, 2), ready.plan.steps.map { it.index })
        assertEquals(listOf(false, true), ready.plan.steps.map { it.critical })
        assertEquals("on", ready.plan.steps[0].parameters.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun toleratesMarkdownFencesAndChatterAroundTheJson() = runTest {
        val wrapped = "Sure! Here is the plan:\n```json\n$validPlan\n```\nAnything else?"
        assertIs<PlanResult.Ready>(planner(FakeLlm(wrapped)).plan("x"))
    }

    @Test
    fun criticalDefaultsToFalseWhenOmitted() = runTest {
        val reply = """{"steps":[{"tool":"flashlight","parameters":{"state":"off"}}]}"""
        val ready = assertIs<PlanResult.Ready>(planner(FakeLlm(reply)).plan("x"))
        assertFalse(ready.plan.steps.single().critical)
        assertEquals("flashlight", ready.plan.steps.single().description)
    }

    @Test
    fun anUnknownToolIsRejectedAndTheModelGetsOneChanceToCorrectIt() = runTest {
        val bad = """{"steps":[{"tool":"run_shell","parameters":{"cmd":"rm -rf /"}}]}"""
        val llm = FakeLlm(bad, validPlan)

        val result = planner(llm).plan("do things")

        assertIs<PlanResult.Ready>(result)
        assertEquals(2, llm.requests.size)
        val feedback = llm.requests[1].messages.last().content
        assertTrue(feedback.contains("unknown tool 'run_shell'"))
        assertTrue(feedback.contains("flashlight"), "the correction request lists the real tools")
    }

    @Test
    fun parameterProblemsAreFedBackWithTheStepNumber() = runTest {
        val bad = """{"steps":[{"tool":"set_alarm","parameters":{"hour":99,"minute":0}}]}"""
        val llm = FakeLlm(bad, validPlan)

        assertIs<PlanResult.Ready>(planner(llm).plan("alarm"))

        assertTrue(llm.requests[1].messages.last().content.contains("Step 1 (set_alarm)"))
    }

    @Test
    fun givesUpAfterTwoInvalidAnswersWithoutInventingAFallback() = runTest {
        val bad = """{"steps":[{"tool":"nope","parameters":{}}]}"""
        val llm = FakeLlm(bad, bad)

        val result = planner(llm).plan("x")

        val failed = assertIs<PlanResult.Failed>(result)
        assertTrue(failed.reason.contains("valid plan"))
        assertEquals(2, llm.requests.size)
    }

    @Test
    fun garbageThatIsNotJsonIsRetriedThenFailed() = runTest {
        val result = planner(FakeLlm("I think you should relax.", "still no json")).plan("x")
        assertIs<PlanResult.Failed>(result)
    }

    @Test
    fun moreThanFiveStepsIsRejected() = runTest {
        val step = """{"tool":"flashlight","parameters":{"state":"on"}}"""
        val six = """{"steps":[${List(6) { step }.joinToString(",")}]}"""
        val llm = FakeLlm(six, validPlan)

        assertIs<PlanResult.Ready>(planner(llm).plan("x"))

        assertTrue(llm.requests[1].messages.last().content.contains("Too many steps"))
    }

    @Test
    fun clarificationQuestionsArePassedThroughAndNothingRuns() = runTest {
        val reply = """{"clarification":"What time should the alarm be?","steps":[{"tool":"flashlight","parameters":{"state":"on"}}]}"""
        val result = planner(FakeLlm(reply)).plan("set an alarm")
        assertEquals("What time should the alarm be?", assertIs<PlanResult.NeedsClarification>(result).question)
    }

    @Test
    fun plainConversationNeedsNoTools() = runTest {
        val result = planner(FakeLlm("""{"reply":"Paris.","clarification":null,"steps":[]}""")).plan("capital of France?")
        assertEquals("Paris.", assertIs<PlanResult.Reply>(result).text)
    }

    @Test
    fun anEmptyAnswerIsInvalid() = runTest {
        assertIs<PlanResult.Failed>(planner(FakeLlm("""{"steps":[]}""", """{"steps":[]}""")).plan("x"))
    }

    @Test
    fun providerFailureBecomesAFailedResultNotACrash() = runTest {
        val llm = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse =
                throw AllProvidersFailedException(listOf(ProviderFailure(ProviderId.GEMINI, ProviderError.Timeout("slow"), 2)))
        }

        val failed = assertIs<PlanResult.Failed>(planner(llm).plan("x"))

        assertTrue(failed.reason.contains("Gemini"))
    }

    @Test
    fun planningRequestsAreMarkedHeavySoTheRouterPrefersTheBiggerLocalModel() = runTest {
        val llm = FakeLlm(validPlan)
        planner(llm).plan("flashlight on and alarm at 6")

        assertEquals(com.jarvis.ai.ai.providers.TaskWeight.HEAVY, llm.requests.single().weight)
    }

    @Test
    fun aLocalModelsAnswerIsLabelledAsOnDeviceNotAsACloudProvider() = runTest {
        val local = object : LlmClient {
            override suspend fun complete(request: LlmRequest) =
                LlmResponse("""{"reply":"Paris.","clarification":null,"steps":[]}""", null, 1, emptyList(), com.jarvis.ai.models.ModelId.GEMMA3_1B)
        }

        val result = planner(local).plan("capital of France?")

        assertEquals("On-device Gemma 3 1B", result.answeredBy)
        assertEquals(null, result.provider)
        assertEquals(com.jarvis.ai.models.ModelId.GEMMA3_1B, result.localModel)
    }

    @Test
    fun anyAiUnavailableErrorBecomesAFailedResult() = runTest {
        val llm = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse =
                throw com.jarvis.ai.ai.router.NoRouteException("No AI is available right now.", emptyList())
        }

        val failed = assertIs<PlanResult.Failed>(planner(llm).plan("x"))

        assertEquals("No AI is available right now.", failed.reason)
    }

    @Test
    fun thePromptCarriesTheDateToolCatalogAndSafetyRules() = runTest {
        val llm = FakeLlm(validPlan)
        planner(llm).plan(
            "alarm for 6 AM tomorrow",
            history = listOf(ChatMessage(ChatRole.USER, "hello"), ChatMessage(ChatRole.ASSISTANT, "hi")),
        )

        val request = llm.requests.single()
        assertTrue(request.jsonMode)
        assertTrue(request.system.contains("2026-11-06 03:47"), "current local time lets the model resolve 'tomorrow'")
        assertTrue(request.system.contains("set_alarm"))
        assertTrue(request.system.contains("hour (whole number 0-23, required)"))
        assertTrue(request.system.contains("<untrusted_data>"))
        assertTrue(request.system.contains("You cannot confirm, approve or cancel"))
        assertEquals(listOf("hello", "hi", "alarm for 6 AM tomorrow"), request.messages.map { it.content })
    }

    @Test
    fun replanTellsTheModelWhatAlreadyHappenedAndWhatFailed() = runTest {
        val llm = FakeLlm("""{"reply":"I can't do that on this phone.","steps":[]}""")
        val original: Plan = plan(
            step(1, "flashlight", description = "Turn on the flashlight"),
            step(2, "set_alarm", critical = true, description = "Alarm at 06:00"),
        )

        val result = planner(llm).replan(
            goal = "flashlight and alarm",
            completed = listOf(original.steps[0]),
            failed = original.steps[1],
            error = "No clock app. key=AIzaSyA1234567890abcdefghijklmnop",
        )

        assertIs<PlanResult.Reply>(result)
        val prompt = llm.requests.single().messages.single().content
        assertTrue(prompt.contains("do NOT repeat"))
        assertTrue(prompt.contains("flashlight: Turn on the flashlight"))
        assertTrue(prompt.contains("set_alarm - Alarm at 06:00"))
        assertFalse(prompt.contains("AIzaSy"), "keys echoed in an error must not be sent onward")
    }
}
