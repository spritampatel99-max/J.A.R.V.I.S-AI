package com.jarvis.ai.core.common

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a small store keeps its text. The app backs this with an atomic private file; tests use memory.
 * [write] throws [IOException] when the text could not be saved.
 */
interface TextStorage {
    fun read(): String?

    @Throws(IOException::class)
    fun write(text: String)
}

// Small readers for hand-written JSON, so stores need no serialization compiler plugin. A value of the wrong type reads as
// missing, and a damaged store file reads as empty instead of crashing the app.

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.objs(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal fun parseJson(raw: String?): JsonElement? {
    if (raw.isNullOrBlank()) return null
    return try {
        Json.parseToJsonElement(raw)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Generates ids that are unique enough for a local store: time plus a counter, no randomness needed. */
internal class IdGenerator(private val time: TimeSource, private val prefix: String) {
    private var counter = 0L
    fun next(): String = "$prefix-${time.nowMs().toString(36)}-${(counter++).toString(36)}"
}
