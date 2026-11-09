package com.jarvis.ai.ai.router

import com.jarvis.ai.ai.providers.TaskWeight
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId

/** How the app chooses between on-device and cloud AI. */
enum class AiMode(val label: String) {
    /** Never leaves the phone. */
    LOCAL_ONLY("Local only"),

    /** On-device models first; the cloud only when they cannot answer. The default. */
    LOCAL_FIRST("Local first"),

    /** The cloud first (it is usually stronger); on-device models when the cloud cannot answer. */
    CLOUD_ASSISTED("Cloud assisted"),

    /** Exactly the one route the user picked, with no fallback. */
    MANUAL("Manual"),
}

sealed interface Route {
    val label: String

    data class Local(val model: ModelId) : Route {
        override val label: String get() = ModelCatalog.spec(model).displayName
    }

    /** The configured cloud providers, tried in the user's own order by the fallback client. */
    data object Cloud : Route {
        override val label: String = "Cloud AI"
    }
}

/** Everything the choice depends on. It is a plain value so the rules can be tested without Android. */
data class RoutingContext(
    val mode: AiMode,
    val online: Boolean,
    /** True when this app version contains an on-device engine. */
    val engineAvailable: Boolean,
    /** Models whose files are installed and verified on this phone. */
    val installed: Set<ModelId>,
    /** True when at least one cloud provider has a key and a model saved. */
    val cloudConfigured: Boolean,
    val weight: TaskWeight = TaskWeight.LIGHT,
    /** Used only by [AiMode.MANUAL]. */
    val manualChoice: Route? = null,
)

/** [route] is null when the reason is not about one route, for example Manual mode with nothing chosen. */
data class SkippedRoute(val route: Route?, val reason: String)

data class RoutePlan(
    /** Routes to try, in order. Empty means nothing can answer. */
    val routes: List<Route>,
    /** Routes that were considered and left out, with the reason, so the user can be told why. */
    val skipped: List<SkippedRoute>,
)

/**
 * Decides which routes to try, and in which order. Native commands never reach this: they run before any AI.
 *
 * Rules:
 *  - An on-device model is usable only when the app has an engine for it and its file is installed.
 *  - The cloud is usable only when the phone is online, a provider is configured and the mode allows it.
 *    Local only never uses it, however the other conditions look, and Manual never adds a route the user did not pick.
 *  - Light requests try Gemma 3 1B before Gemma 4 E2B. Heavy ones (the planner's strict JSON) try Gemma 4 E2B first,
 *    because the smaller model is the weaker one at structured output.
 */
object RoutePlanner {

    const val ENGINE_MISSING = "the on-device engine is not part of this version of the app yet"

    fun plan(context: RoutingContext): RoutePlan {
        val skipped = mutableListOf<SkippedRoute>()

        fun localUsable(model: ModelId): Boolean {
            val route = Route.Local(model)
            return when {
                !context.engineAvailable -> false.also { skipped += SkippedRoute(route, ENGINE_MISSING) }
                model !in context.installed -> false.also { skipped += SkippedRoute(route, "it is not installed") }
                else -> true
            }
        }

        fun cloudUsable(): Boolean = when {
            context.mode == AiMode.LOCAL_ONLY -> false.also { skipped += SkippedRoute(Route.Cloud, "Local only mode never uses the cloud") }
            !context.online -> false.also { skipped += SkippedRoute(Route.Cloud, "the phone is offline") }
            !context.cloudConfigured -> false.also { skipped += SkippedRoute(Route.Cloud, "no cloud provider is set up") }
            else -> true
        }

        val order = when (context.weight) {
            TaskWeight.LIGHT -> listOf(ModelId.GEMMA3_1B, ModelId.GEMMA4_E2B)
            TaskWeight.HEAVY -> listOf(ModelId.GEMMA4_E2B, ModelId.GEMMA3_1B)
        }

        val routes: List<Route> = when (context.mode) {
            AiMode.LOCAL_ONLY -> {
                val locals = order.filter { localUsable(it) }.map { Route.Local(it) }
                cloudUsable()
                locals
            }
            AiMode.LOCAL_FIRST -> {
                val locals = order.filter { localUsable(it) }.map { Route.Local(it) }
                locals + listOfNotNull(Route.Cloud.takeIf { cloudUsable() })
            }
            AiMode.CLOUD_ASSISTED -> {
                val cloud = listOfNotNull(Route.Cloud.takeIf { cloudUsable() })
                cloud + order.filter { localUsable(it) }.map { Route.Local(it) }
            }
            AiMode.MANUAL -> when (val choice = context.manualChoice) {
                null -> emptyList<Route>().also { skipped += SkippedRoute(null, "no model has been chosen") }
                is Route.Local -> listOf(choice).filter { localUsable(choice.model) }
                Route.Cloud -> listOf(Route.Cloud).filter { cloudUsable() }
            }
        }
        return RoutePlan(routes, skipped)
    }
}
