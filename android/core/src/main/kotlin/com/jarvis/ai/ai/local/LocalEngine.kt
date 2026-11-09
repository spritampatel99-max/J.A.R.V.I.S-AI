package com.jarvis.ai.ai.local

import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.models.ModelId

/** A verified model file on this phone that an engine can load. */
data class LocalModelRef(val id: ModelId, val path: String)

/** Typed on-device failures. Messages are safe to show to the user; they never contain file contents. */
sealed class LocalEngineError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** This phone or this app version cannot run local models at all. */
    class NotAvailable(val detail: String) : LocalEngineError(detail)

    /** The file could not be opened as a model (damaged file, unsupported format). */
    class LoadFailed(val detail: String, cause: Throwable? = null) : LocalEngineError(detail, cause)

    /** The phone ran out of memory. The engine is unloaded afterwards so the app itself is not killed. */
    class OutOfMemory(val detail: String) : LocalEngineError(detail)

    class GenerationFailed(val detail: String, cause: Throwable? = null) : LocalEngineError(detail, cause)
}

/**
 * Runs one on-device model at a time.
 *
 * The Android implementation (LiteRT-LM) is not part of the app yet; this is the boundary it will implement, so the
 * routing around it can be built and tested now. An engine is not required to be thread-safe: [RoutedLlmClient] calls it
 * from one coroutine at a time.
 */
interface LocalEngine {
    /** The model currently in memory, or null. */
    val loaded: ModelId?

    /**
     * Loads [model], first releasing any model that was loaded. Heavy: can take seconds, so call it off the main thread.
     * @throws LocalEngineError
     */
    suspend fun load(model: LocalModelRef)

    /**
     * Answers [request] with the loaded model. A local model has no JSON mode, so [LlmRequest.jsonMode] is a request, not a promise.
     * @throws LocalEngineError
     */
    suspend fun generate(request: LlmRequest): String

    /** Stops a running [generate] as soon as possible. Safe to call when nothing is running. */
    fun cancel()

    /** Releases the model's memory. Safe to call when nothing is loaded. */
    fun unload()
}
