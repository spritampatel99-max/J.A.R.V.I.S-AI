package com.jarvis.ai

import com.jarvis.ai.ai.fallback.AiUnavailableException
import com.jarvis.ai.ai.fallback.AllProvidersFailedException
import com.jarvis.ai.ai.fallback.LlmClient
import com.jarvis.ai.ai.fallback.LlmResponse
import com.jarvis.ai.ai.fallback.ProviderFailure
import com.jarvis.ai.ai.local.LocalEngine
import com.jarvis.ai.ai.local.LocalEngineError
import com.jarvis.ai.ai.local.LocalModelRef
import com.jarvis.ai.ai.providers.ChatMessage
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.ai.providers.ProviderId
import com.jarvis.ai.ai.providers.TaskWeight
import com.jarvis.ai.ai.router.AiMode
import com.jarvis.ai.ai.router.NoRouteException
import com.jarvis.ai.ai.router.Route
import com.jarvis.ai.ai.router.RoutePlanner
import com.jarvis.ai.ai.router.RoutedLlmClient
import com.jarvis.ai.ai.router.RoutingContext
import com.jarvis.ai.ai.router.RoutingSettings
import com.jarvis.ai.models.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RoutePlannerTest {

    private val both = setOf(ModelId.GEMMA3_1B, ModelId.GEMMA4_E2B)

    private fun context(
        mode: AiMode = AiMode.LOCAL_FIRST,
        online: Boolean = true,
        engine: Boolean = true,
        installed: Set<ModelId> = both,
        cloud: Boolean = true,
        weight: TaskWeight = TaskWeight.LIGHT,
        manual: Route? = null,
    ) = RoutingContext(mode, online, engine, installed, cloud, weight, manual)

    private val small = Route.Local(ModelId.GEMMA3_1B)
    private val big = Route.Local(ModelId.GEMMA4_E2B)

    @Test
    fun localFirstTriesTheSmallModelThenTheBigOneThenTheCloud() {
        assertEquals(listOf(small, big, Route.Cloud), RoutePlanner.plan(context()).routes)
    }

    @Test
    fun heavyRequestsTryTheBigModelFirst() {
        assertEquals(listOf(big, small, Route.Cloud), RoutePlanner.plan(context(weight = TaskWeight.HEAVY)).routes)
    }

    @Test
    fun cloudAssistedTriesTheCloudFirst() {
        assertEquals(listOf(Route.Cloud, small, big), RoutePlanner.plan(context(mode = AiMode.CLOUD_ASSISTED)).routes)
    }

    @Test
    fun localOnlyNeverUsesTheCloudAndSaysWhy() {
        val plan = RoutePlanner.plan(context(mode = AiMode.LOCAL_ONLY))

        assertEquals(listOf(small, big), plan.routes)
        assertFalse(Route.Cloud in plan.routes)
        assertTrue(plan.skipped.any { it.route == Route.Cloud && it.reason.contains("Local only") })
    }

    @Test
    fun offlineSkipsTheCloudButKeepsTheLocalModels() {
        val plan = RoutePlanner.plan(context(online = false))

        assertEquals(listOf(small, big), plan.routes)
        assertTrue(plan.skipped.single { it.route == Route.Cloud }.reason.contains("offline"))
    }

    @Test
    fun anUnconfiguredCloudIsSkipped() {
        val plan = RoutePlanner.plan(context(cloud = false))

        assertEquals(listOf(small, big), plan.routes)
        assertTrue(plan.skipped.single { it.route == Route.Cloud }.reason.contains("no cloud provider"))
    }

    @Test
    fun withoutAnEngineNoLocalModelIsUsedEvenIfInstalled() {
        val plan = RoutePlanner.plan(context(engine = false))

        assertEquals(listOf<Route>(Route.Cloud), plan.routes)
        assertEquals(2, plan.skipped.count { it.reason == RoutePlanner.ENGINE_MISSING })
    }

    @Test
    fun aModelThatIsNotInstalledIsSkipped() {
        val plan = RoutePlanner.plan(context(installed = setOf(ModelId.GEMMA4_E2B)))

        assertEquals(listOf(big, Route.Cloud), plan.routes)
        assertEquals("it is not installed", plan.skipped.single { it.route == small }.reason)
    }

    @Test
    fun manualUsesOnlyTheChosenRouteAndNeverFallsBack() {
        assertEquals(listOf<Route>(big), RoutePlanner.plan(context(mode = AiMode.MANUAL, manual = big)).routes)
        assertEquals(listOf<Route>(Route.Cloud), RoutePlanner.plan(context(mode = AiMode.MANUAL, manual = Route.Cloud)).routes)

        val unusable = RoutePlanner.plan(context(mode = AiMode.MANUAL, manual = small, installed = emptySet()))
        assertTrue(unusable.routes.isEmpty(), "a manual choice that cannot run must not be replaced by another route")
    }

    @Test
    fun manualWithNothingChosenHasNoRoute() {
        val plan = RoutePlanner.plan(context(mode = AiMode.MANUAL))

        assertTrue(plan.routes.isEmpty())
        assertTrue(plan.skipped.single().reason.contains("no model has been chosen"))
    }

    @Test
    fun manualCloudStillNeedsTheCloudToBeUsable() {
        assertTrue(RoutePlanner.plan(context(mode = AiMode.MANUAL, manual = Route.Cloud, online = false)).routes.isEmpty())
    }

    @Test
    fun nothingAvailableMeansNoRoutes() {
        val plan = RoutePlanner.plan(context(engine = false, cloud = false))

        assertTrue(plan.routes.isEmpty())
        assertEquals(3, plan.skipped.size)
    }
}

class RoutedLlmClientTest {

    private val request = LlmRequest("system", listOf(ChatMessage(ChatRole.USER, "hi")))
    private val paths = mapOf(ModelId.GEMMA3_1B to "/models/1b.litertlm", ModelId.GEMMA4_E2B to "/models/e2b.litertlm")

    private class FakeEngine(private val script: suspend (LlmRequest, ModelId?) -> String = { _, _ -> "local answer" }) : LocalEngine {
        override var loaded: ModelId? = null
            private set
        val loads = mutableListOf<LocalModelRef>()
        var unloads = 0
        var cancels = 0
        private var running = 0
        var maxRunning = 0

        override suspend fun load(model: LocalModelRef) {
            loads += model
            loaded = model.id
        }

        override suspend fun generate(request: LlmRequest): String {
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                return script(request, loaded)
            } finally {
                running--
            }
        }

        override fun cancel() {
            cancels++
        }

        override fun unload() {
            unloads++
            loaded = null
        }
    }

    private class FakeCloud(private val fails: Boolean = false) : LlmClient {
        var calls = 0
        override suspend fun complete(request: LlmRequest): LlmResponse {
            calls++
            if (fails) {
                throw AllProvidersFailedException(listOf(ProviderFailure(ProviderId.GEMINI, ProviderError.Timeout("slow"), 2)))
            }
            return LlmResponse("cloud answer", ProviderId.GROQ, 1, emptyList())
        }
    }

    private fun client(
        engine: LocalEngine? = FakeEngine(),
        installed: Map<ModelId, String> = paths,
        cloud: LlmClient = FakeCloud(),
        cloudConfigured: Boolean = true,
        settings: RoutingSettings = RoutingSettings(),
    ) = RoutedLlmClient(engine, { installed }, cloud, { cloudConfigured }, { settings })

    @Test
    fun aLocalModelAnswersFirstAndTheCloudIsNotTouched() = runTest {
        val cloud = FakeCloud()
        val engine = FakeEngine()

        val response = client(engine, cloud = cloud).complete(request)

        assertEquals("local answer", response.text)
        assertNull(response.provider)
        assertEquals(ModelId.GEMMA3_1B, response.localModel)
        assertEquals("On-device Gemma 3 1B", response.answeredBy)
        assertEquals(0, cloud.calls)
        assertEquals("/models/1b.litertlm", engine.loads.single().path)
    }

    @Test
    fun aFailingLocalModelFallsBackToTheCloudAndSaysSo() = runTest {
        val engine = FakeEngine { _, _ -> throw LocalEngineError.GenerationFailed("the model crashed") }

        val response = client(engine).complete(request)

        assertEquals("cloud answer", response.text)
        assertEquals(ProviderId.GROQ, response.provider)
        assertTrue(response.routeNotes.any { it.contains("Gemma 3 1B") && it.contains("the model crashed") })
        assertTrue(response.routeNotes.any { it.contains("Gemma 4 E2B") })
    }

    @Test
    fun theBigModelIsTriedAfterTheSmallOneFails() = runTest {
        val engine = FakeEngine { _, loaded ->
            if (loaded == ModelId.GEMMA3_1B) throw LocalEngineError.GenerationFailed("too small") else "big answer"
        }

        val response = client(engine).complete(request)

        assertEquals("big answer", response.text)
        assertEquals(ModelId.GEMMA4_E2B, response.localModel)
        assertEquals(listOf(ModelId.GEMMA3_1B, ModelId.GEMMA4_E2B), engine.loads.map { it.id })
    }

    @Test
    fun localOnlyNeverFallsBackToTheCloudEvenWhenLocalFails() = runTest {
        val cloud = FakeCloud()
        val engine = FakeEngine { _, _ -> throw LocalEngineError.GenerationFailed("no luck") }

        val error = assertFailsWith<NoRouteException> {
            client(engine, cloud = cloud, settings = RoutingSettings(AiMode.LOCAL_ONLY)).complete(request)
        }

        assertEquals(0, cloud.calls)
        assertTrue(error.message.orEmpty().contains("Every available AI route failed"))
        assertTrue(error.notes.any { it.contains("no luck") })
    }

    @Test
    fun offlineUsesTheLocalModelAndNeverCallsTheCloud() = runTest {
        val cloud = FakeCloud()

        val response = client(cloud = cloud, settings = RoutingSettings(online = false)).complete(request)

        assertEquals("local answer", response.text)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun cloudAssistedAsksTheCloudFirst() = runTest {
        val engine = FakeEngine()

        val response = client(engine, settings = RoutingSettings(AiMode.CLOUD_ASSISTED)).complete(request)

        assertEquals("cloud answer", response.text)
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun cloudAssistedFallsBackToLocalWhenTheCloudFails() = runTest {
        val response = client(cloud = FakeCloud(fails = true), settings = RoutingSettings(AiMode.CLOUD_ASSISTED)).complete(request)

        assertEquals("local answer", response.text)
        assertTrue(response.routeNotes.any { it.startsWith("Cloud AI:") })
    }

    @Test
    fun aModelIsLoadedOnlyWhenItIsNotAlreadyInMemory() = runTest {
        val engine = FakeEngine()
        val client = client(engine)

        client.complete(request)
        client.complete(request)
        assertEquals(1, engine.loads.size)

        client.complete(request.copy(weight = TaskWeight.HEAVY))
        assertEquals(listOf(ModelId.GEMMA3_1B, ModelId.GEMMA4_E2B), engine.loads.map { it.id })
    }

    @Test
    fun runningOutOfMemoryUnloadsTheModelAndMovesOn() = runTest {
        val engine = FakeEngine { _, loaded ->
            if (loaded == ModelId.GEMMA4_E2B) throw LocalEngineError.OutOfMemory("not enough memory") else "small answer"
        }

        val response = client(engine).complete(request.copy(weight = TaskWeight.HEAVY))

        assertEquals("small answer", response.text)
        assertEquals(1, engine.unloads)
        assertTrue(response.routeNotes.any { it.contains("not enough memory") })
    }

    @Test
    fun anUnexpectedEngineCrashIsContainedAndTheModelUnloaded() = runTest {
        val engine = FakeEngine { _, _ -> throw IllegalStateException("native crash") }

        val response = client(engine).complete(request)

        assertEquals("cloud answer", response.text)
        assertTrue(engine.unloads >= 1)
        assertTrue(response.routeNotes.any { it.contains("IllegalStateException") })
    }

    @Test
    fun anEmptyLocalAnswerCountsAsAFailure() = runTest {
        val engine = FakeEngine { _, _ -> "   " }

        val response = client(engine).complete(request)

        assertEquals("cloud answer", response.text)
        assertTrue(response.routeNotes.any { it.contains("empty answer") })
    }

    @Test
    fun aLoadFailureIsReportedAndTheNextRouteIsTried() = runTest {
        val engine = object : LocalEngine {
            override val loaded: ModelId? = null
            override suspend fun load(model: LocalModelRef) = throw LocalEngineError.LoadFailed("damaged file")
            override suspend fun generate(request: LlmRequest): String = error("never loaded")
            override fun cancel() = Unit
            override fun unload() = Unit
        }

        val response = client(engine).complete(request)

        assertEquals("cloud answer", response.text)
        assertTrue(response.routeNotes.any { it.contains("damaged file") })
    }

    @Test
    fun cancellingARunningRequestStopsTheEngine() = runTest {
        val engine = FakeEngine { _, _ -> awaitCancellation() }
        val job = launch { client(engine).complete(request) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(1, engine.cancels)
    }

    @Test
    fun cancellingARequestThatIsOnlyWaitingDoesNotStopAnotherRequestsWork() = runTest {
        val gate = CompletableDeferred<String>()
        val engine = FakeEngine { _, _ -> gate.await() }
        val client = client(engine)
        val first = async { client.complete(request) }
        runCurrent()
        val second = launch { client.complete(request) }
        runCurrent()

        second.cancel()
        runCurrent()
        assertEquals(0, engine.cancels, "the waiting request must not cancel the running one")
        assertEquals(0, engine.unloads)

        gate.complete("first done")
        assertEquals("first done", first.await().text)
    }

    @Test
    fun theEngineIsNeverUsedByTwoRequestsAtOnce() = runTest {
        val engine = FakeEngine { _, _ ->
            delay(100)
            "ok"
        }
        val client = client(engine)

        val answers = listOf(async { client.complete(request) }, async { client.complete(request) }, async { client.complete(request) })
        answers.forEach { it.await() }

        assertEquals(1, engine.maxRunning)
    }

    @Test
    fun withoutAnEngineAndWithoutACloudTheMessageSaysWhatToDo() = runTest {
        val error = assertFailsWith<NoRouteException> {
            client(engine = null, installed = emptyMap(), cloudConfigured = false).complete(request)
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("No AI is available right now"))
        assertTrue(message.contains("add a cloud key in the Cloud tab"))
        assertTrue(message.contains("install a local model in the Models tab"))
    }

    @Test
    fun theNoRouteErrorIsAnAiUnavailableErrorSoThePlannerCatchesIt() = runTest {
        val error = assertFailsWith<AiUnavailableException> {
            client(engine = null, cloudConfigured = false).complete(request)
        }
        assertIs<NoRouteException>(error)
    }

    @Test
    fun cloudFailureWithNothingElseListsWhatFailed() = runTest {
        val error = assertFailsWith<NoRouteException> {
            client(engine = null, cloud = FakeCloud(fails = true)).complete(request)
        }
        assertTrue(error.message.orEmpty().contains("Gemini"))
    }

    @Test
    fun aResponseMustComeFromExactlyOneSource() {
        assertFailsWith<IllegalArgumentException> { LlmResponse("x", null, 1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { LlmResponse("x", ProviderId.GROQ, 1, emptyList(), ModelId.GEMMA3_1B) }
    }
}
