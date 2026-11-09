package com.jarvis.ai.ai.router

import com.jarvis.ai.ai.fallback.AiUnavailableException
import com.jarvis.ai.ai.fallback.AllProvidersFailedException
import com.jarvis.ai.ai.fallback.LlmClient
import com.jarvis.ai.ai.fallback.LlmResponse
import com.jarvis.ai.ai.local.LocalEngine
import com.jarvis.ai.ai.local.LocalEngineError
import com.jarvis.ai.ai.local.LocalModelRef
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.models.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The user's choices that the router reads on every request. */
data class RoutingSettings(
    val mode: AiMode = AiMode.LOCAL_FIRST,
    val online: Boolean = true,
    val manualChoice: Route? = null,
)

/** Nothing could answer. [message] says why, in plain words, and what the user can do about it. */
class NoRouteException(message: String, val notes: List<String>) : AiUnavailableException(message)

/**
 * Sends each request down the routes [RoutePlanner] picks: on-device models through [engine], and the cloud through
 * [cloud] (the existing fallback client, which keeps trying the user's providers in order).
 *
 * A failed route never ends the request while another route is allowed: the next one is tried, and the answer says what
 * went wrong with the first. Local only and Manual are the exceptions by design: they never fall back to a route the user
 * did not allow. The on-device engine is used from one request at a time, and a model is loaded only when it is not the
 * one already in memory. Cancelling the request also stops the engine.
 */
class RoutedLlmClient(
    private val engine: LocalEngine?,
    /** Verified model files on this phone: model id to the file's path. */
    private val installedModels: () -> Map<ModelId, String>,
    private val cloud: LlmClient,
    private val cloudConfigured: () -> Boolean,
    private val settings: () -> RoutingSettings,
) : LlmClient {

    private val localLock = Mutex()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val installed = installedModels()
        val current = settings()
        val plan = RoutePlanner.plan(
            RoutingContext(
                mode = current.mode,
                online = current.online,
                engineAvailable = engine != null,
                installed = installed.keys,
                cloudConfigured = cloudConfigured(),
                weight = request.weight,
                manualChoice = current.manualChoice,
            ),
        )
        val notes = plan.skipped.map { "${it.route?.label ?: "Manual mode"}: ${it.reason}" }.toMutableList()

        for (route in plan.routes) {
            when (route) {
                is Route.Local -> {
                    val local = engine ?: continue
                    val path = installed[route.model] ?: continue
                    when (val result = answerLocally(local, route.model, path, request)) {
                        is LocalResult.Answer -> return LlmResponse(
                            text = result.text,
                            provider = null,
                            attempts = 1,
                            failures = emptyList(),
                            localModel = route.model,
                            routeNotes = notes,
                        )
                        is LocalResult.Failure -> notes += "${route.label}: ${result.reason}"
                    }
                }
                Route.Cloud -> try {
                    val answer = cloud.complete(request)
                    return answer.copy(routeNotes = notes + answer.routeNotes)
                } catch (e: AllProvidersFailedException) {
                    notes += "Cloud AI: ${e.message}"
                }
            }
        }

        throw NoRouteException(unavailableMessage(plan, notes, installed.isEmpty(), cloudConfigured()), notes)
    }

    private sealed interface LocalResult {
        data class Answer(val text: String) : LocalResult
        data class Failure(val reason: String) : LocalResult
    }

    /**
     * Everything that touches the engine happens inside the lock, including the clean-up after a failure and the stop on
     * cancellation. A request that is only waiting for its turn therefore cannot stop, or unload, another request's work.
     */
    private suspend fun answerLocally(engine: LocalEngine, model: ModelId, path: String, request: LlmRequest): LocalResult =
        localLock.withLock {
            try {
                if (engine.loaded != model) engine.load(LocalModelRef(model, path))
                val text = engine.generate(request)
                if (text.isBlank()) LocalResult.Failure("it returned an empty answer") else LocalResult.Answer(text)
            } catch (e: CancellationException) {
                engine.cancel()
                throw e
            } catch (e: LocalEngineError) {
                // Out of memory: free the model now, so the app is not the next thing Android stops.
                if (e is LocalEngineError.OutOfMemory) engine.unload()
                LocalResult.Failure(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                engine.unload()
                LocalResult.Failure("it failed unexpectedly (${e.javaClass.simpleName})")
            }
        }

    private fun unavailableMessage(plan: RoutePlan, notes: List<String>, nothingInstalled: Boolean, hasCloud: Boolean): String {
        val why = notes.joinToString("; ")
        if (plan.routes.isNotEmpty()) return "Every available AI route failed. $why"
        val hints = buildList {
            if (!hasCloud) add("add a cloud key in the Cloud tab")
            if (nothingInstalled) add("install a local model in the Models tab")
        }
        val tail = if (hints.isEmpty()) "" else " You can ${hints.joinToString(" or ")}."
        return "No AI is available right now. $why.$tail"
    }
}
