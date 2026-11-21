package com.jarvis.ai.android.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.jarvis.ai.ai.local.LocalEngine
import com.jarvis.ai.ai.local.LocalEngineError
import com.jarvis.ai.ai.local.LocalModelRef
import com.jarvis.ai.ai.providers.ChatRole
import com.jarvis.ai.ai.providers.LlmRequest
import com.jarvis.ai.models.ModelId
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs an installed `.litertlm` model on this phone with Google's LiteRT-LM library.
 *
 * NOT COMPILED OR RUN YET. It is written from Google's Android guide for LiteRT-LM
 * (developers.google.com/edge/litert-lm/android), which shows `Engine(EngineConfig(...))`, `engine.initialize()`,
 * `engine.createConversation(ConversationConfig(...))`, a Flow from `sendMessageAsync`, and `close()`. Two things are not
 * confirmed by that page and may need a small fix on first build: the exact `Backend.CPU()` constructor and the name of
 * the call that stops a running answer (`cancelProcess()` here). It also needs a Kotlin compiler new enough to read the
 * library's metadata (see docs/ai/LOCAL_MODELS.md). This file is compiled only when the build is made with
 * `-PenableLiteRt=true`, so it cannot break the normal build.
 *
 * The CPU backend is used on purpose: it needs no extra manifest entries and works on every phone; GPU is faster but is a
 * later, measured choice. One request at a time: [RoutedLlmClient] already holds a lock around every call here.
 */
class LiteRtLmEngine(private val cacheDir: File) : LocalEngine {

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedId: ModelId? = null
    @Volatile private var running: Conversation? = null

    override val loaded: ModelId? get() = loadedId

    override suspend fun load(model: LocalModelRef) {
        withContext(Dispatchers.Default) {
            unload()
            val file = File(model.path)
            if (!file.isFile) throw LocalEngineError.LoadFailed("The model file is missing from this phone. Install it again in the Models tab.")
            try {
                val created = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), cacheDir = cacheDir.absolutePath))
                created.initialize() // can take several seconds
                engine = created
                loadedId = model.id
            } catch (e: OutOfMemoryError) {
                throw LocalEngineError.OutOfMemory("This phone ran out of memory loading the model.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw LocalEngineError.LoadFailed("The model could not be loaded (${e.javaClass.simpleName}). The file may be damaged or not a LiteRT-LM model.", e)
            }
        }
    }

    override suspend fun generate(request: LlmRequest): String = withContext(Dispatchers.Default) {
        val current = engine ?: throw LocalEngineError.NotAvailable("No on-device model is loaded.")
        val turns = request.messages
        val last = turns.lastOrNull()?.takeIf { it.role == ChatRole.USER }
            ?: throw LocalEngineError.GenerationFailed("There was no question to answer.")
        val earlier = turns.dropLast(1).map { if (it.role == ChatRole.USER) Message.user(it.content) else Message.model(it.content) }
        val config = ConversationConfig(
            systemInstruction = Contents.of(request.system),
            initialMessages = earlier,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.temperature.coerceIn(0.0, 1.0)),
        )
        try {
            current.createConversation(config).use { conversation ->
                running = conversation
                try {
                    val text = StringBuilder()
                    conversation.sendMessageAsync(last.content).collect { text.append(it.toString()) }
                    text.toString().trim()
                } finally {
                    running = null
                }
            }
        } catch (e: OutOfMemoryError) {
            throw LocalEngineError.OutOfMemory("This phone ran out of memory while the model was answering.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalEngineError) {
            throw e
        } catch (e: Exception) {
            throw LocalEngineError.GenerationFailed("The model could not answer (${e.javaClass.simpleName}).", e)
        }
    }

    override fun cancel() {
        try {
            running?.cancelProcess()
        } catch (e: Exception) {
            // Nothing running, or already closed: stopping is best effort, so the failure is ignored.
        }
    }

    override fun unload() {
        val old = engine
        engine = null
        loadedId = null
        try {
            old?.close()
        } catch (e: Exception) {
            // The memory is released either way, so the failure is ignored.
        }
    }
}
