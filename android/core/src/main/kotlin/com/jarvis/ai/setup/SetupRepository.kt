package com.jarvis.ai.setup

import com.jarvis.ai.core.common.SystemTimeSource
import com.jarvis.ai.core.common.TimeSource
import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.models.ModelChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Where the serialized setup state lives. The Android module backs it with private app storage. */
interface SetupStorage {
    fun read(): String?

    /** @return false when the write failed. */
    fun write(text: String): Boolean
}

/**
 * Turns [SetupState] into JSON and back. Reading is forgiving on purpose: unknown step or feature names (for example
 * written by a newer version of the app) are ignored, wrong-typed fields fall back to defaults, and text that is not
 * valid JSON gives a fresh state rather than a crash.
 */
object SetupStateCodec {
    private const val VERSION = 1

    fun encode(state: SetupState): String = buildJsonObject {
        put("version", VERSION)
        put("completed", JsonArray(state.completed.map { JsonPrimitive(it.name) }))
        put("skipped", JsonArray(state.skipped.map { JsonPrimitive(it.name) }))
        put("current", state.current.name)
        put("privacyAcknowledged", state.privacyAcknowledged)
        put("modelChoice", state.modelChoice?.name?.let { JsonPrimitive(it) } ?: JsonNull)
        put("preset", state.preset?.name?.let { JsonPrimitive(it) } ?: JsonNull)
        put("features", JsonArray(state.features.map { JsonPrimitive(it.name) }))
        put("finished", state.finished)
        put("updatedAtMs", state.updatedAtMs)
    }.toString()

    /** @return null when [text] is not a usable state; the caller then starts fresh. */
    fun decode(text: String?): SetupState? {
        if (text.isNullOrBlank()) return null
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null

        fun names(key: String): List<String> =
            (root[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        fun text(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        val completed = names("completed").mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet()
        val skipped = names("skipped").mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet() - completed
        val state = SetupState(
            completed = completed,
            skipped = skipped,
            current = SetupStep.entries.firstOrNull { it.name == text("current") } ?: SetupStep.WELCOME,
            privacyAcknowledged = flag(root["privacyAcknowledged"]),
            modelChoice = ModelChoice.entries.firstOrNull { it.name == text("modelChoice") },
            preset = ProfilePreset.entries.firstOrNull { it.name == text("preset") },
            features = names("features").mapNotNull { n -> FeatureId.entries.firstOrNull { it.name == n } }.toSet(),
            finished = flag(root["finished"]),
            updatedAtMs = (root["updatedAtMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
        return repair(state)
    }

    private fun flag(element: JsonElement?): Boolean = (element as? JsonPrimitive)?.booleanOrNull ?: false

    /** A saved file can be edited or damaged. Never trust "finished" unless every required step is really complete. */
    private fun repair(state: SetupState): SetupState {
        val requiredDone = SetupStep.entries.filter { !it.optional && it != SetupStep.SUMMARY }.all { it in state.completed }
        val finished = state.finished && requiredDone
        return state.copy(finished = finished)
    }
}

/**
 * Loads and saves the wizard state. A failed save never loses the in-memory state and never crashes the wizard;
 * [lastSaveFailed] lets the screen say so.
 */
class SetupRepository(
    private val storage: SetupStorage,
    private val time: TimeSource = SystemTimeSource,
) {
    private val lock = Any()
    private var cached: SetupState? = null

    @Volatile
    var lastSaveFailed: Boolean = false
        private set

    fun load(): SetupState = synchronized(lock) {
        cached ?: SetupFlow.onAppOpened(SetupStateCodec.decode(storage.read()) ?: SetupState()).also { cached = it }
    }

    fun update(transform: (SetupState) -> SetupState): SetupState = synchronized(lock) {
        val next = transform(load()).copy(updatedAtMs = time.nowMs())
        cached = next
        lastSaveFailed = !storage.write(SetupStateCodec.encode(next))
        next
    }

    /** Applies a flow rule; the state changes and is saved only when the rule allows it. */
    fun apply(rule: (SetupState) -> StepResult): StepResult = synchronized(lock) {
        val result = rule(load())
        if (result is StepResult.Ok) update { result.state }
        result
    }
}
