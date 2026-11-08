package com.jarvis.ai.models

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class InstallSource { DOWNLOADED, IMPORTED }

/** A model file that is on the phone. */
data class InstalledModel(
    val id: ModelId,
    val fileName: String,
    val sizeBytes: Long,
    /** True only when the file's SHA-256 matched the value the repository publishes. */
    val checksumVerified: Boolean,
    val source: InstallSource,
    val installedAtMs: Long,
)

/** Where the list of installed models is kept. The Android module backs it with private app storage. */
interface InstalledModelsStorage {
    fun read(): String?

    /** @return false when the write failed. */
    fun write(text: String): Boolean
}

/**
 * The record of which models are installed. A record only counts while its file is still there with the
 * recorded size, so a file the user (or Android) removed is never reported as installed.
 */
class InstalledModels(
    private val storage: InstalledModelsStorage,
    private val dir: File,
) {

    fun all(): List<InstalledModel> = InstalledModelsCodec.decode(storage.read()).filter(::fileIsIntact)

    fun get(id: ModelId): InstalledModel? = all().firstOrNull { it.id == id }

    fun fileFor(id: ModelId): File? = get(id)?.let { File(dir, it.fileName) }

    /** @return false when the record could not be saved (the file itself is still on disk). */
    fun record(model: InstalledModel): Boolean {
        val others = InstalledModelsCodec.decode(storage.read()).filter { it.id != model.id }
        return storage.write(InstalledModelsCodec.encode(others + model))
    }

    /** Removes the file and its record. @return true when the model is gone afterwards. */
    fun delete(id: ModelId): Boolean {
        val spec = ModelCatalog.spec(id)
        val target = File(dir, spec.fileName)
        val part = File(dir, spec.fileName + ModelInstaller.PART_SUFFIX)
        val removed = (!target.exists() || target.delete()) && (!part.exists() || part.delete())
        val others = InstalledModelsCodec.decode(storage.read()).filter { it.id != id }
        val saved = storage.write(InstalledModelsCodec.encode(others))
        return removed && saved
    }

    /** Bytes of a partly downloaded file, or 0. Lets the screen offer "Resume". */
    fun partialBytes(id: ModelId): Long {
        val part = File(dir, ModelCatalog.spec(id).fileName + ModelInstaller.PART_SUFFIX)
        return if (part.isFile) part.length() else 0L
    }

    private fun fileIsIntact(model: InstalledModel): Boolean {
        val file = File(dir, model.fileName)
        return file.isFile && file.length() == model.sizeBytes
    }
}

object InstalledModelsCodec {
    private const val VERSION = 1

    fun encode(models: List<InstalledModel>): String = buildJsonObject {
        put("version", VERSION)
        put(
            "models",
            JsonArray(
                models.map { m ->
                    buildJsonObject {
                        put("id", m.id.name)
                        put("fileName", m.fileName)
                        put("sizeBytes", m.sizeBytes)
                        put("checksumVerified", m.checksumVerified)
                        put("source", m.source.name)
                        put("installedAtMs", m.installedAtMs)
                    }
                },
            ),
        )
    }.toString()

    /** Forgiving on purpose: unknown models or malformed entries are skipped, bad text gives an empty list. */
    fun decode(text: String?): List<InstalledModel> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()

        val array = root["models"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val id = ModelId.entries.firstOrNull { it.name == str("id") } ?: return@mapNotNull null
            val fileName = str("fileName")?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && ".." !in it }
                ?: return@mapNotNull null
            val size = (obj["sizeBytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            InstalledModel(
                id = id,
                fileName = fileName,
                sizeBytes = size,
                checksumVerified = (obj["checksumVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                source = InstallSource.entries.firstOrNull { it.name == str("source") } ?: InstallSource.DOWNLOADED,
                installedAtMs = (obj["installedAtMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }
}
