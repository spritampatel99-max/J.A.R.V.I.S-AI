package com.jarvis.ai.models

import com.jarvis.ai.ai.providers.HttpTransport
import com.jarvis.ai.ai.providers.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

sealed interface MetadataResult {
    /** Size (and checksum when published) read from the repository itself. */
    data class Found(val info: ModelInfo) : MetadataResult

    data class FileMissing(val fileName: String) : MetadataResult

    data class Failed(val reason: String) : MetadataResult
}

/**
 * Reads a model file's real size and SHA-256 from the Hugging Face file listing (a read-only GET).
 * It sends no personal data; only the phone's network address is visible to the server, as with any
 * web request. A Hugging Face token is sent only when the caller passes one (a gated repository). The caller decides when to run it (the setup does it only on request).
 */
class ModelMetadataClient(private val transport: HttpTransport) {

    /** @param accessToken a Hugging Face token, sent only as a header, for repositories that ask for sign-in. */
    suspend fun lookup(spec: ModelSpec, accessToken: String? = null): MetadataResult {
        val url = spec.metadataUrl
        if (!url.startsWith("https://")) return MetadataResult.Failed("Only https addresses are allowed")
        val headers = buildMap {
            put("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) put("Authorization", "Bearer ${accessToken.trim()}")
        }
        val result = try {
            transport.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderError) {
            return MetadataResult.Failed(e.message ?: "The request failed")
        }
        return when (result.code) {
            in 200..299 -> parse(spec, result.body)
            401, 403 -> MetadataResult.Failed("Hugging Face asked for sign-in to list this repository (HTTP ${result.code}).")
            404 -> MetadataResult.Failed("The model repository was not found (HTTP 404). It may have been renamed.")
            else -> MetadataResult.Failed("Hugging Face answered HTTP ${result.code}.")
        }
    }

    internal fun parse(spec: ModelSpec, body: String): MetadataResult {
        val entries = try {
            Json.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {
            return MetadataResult.Failed("The file listing was not valid JSON.")
        }
        if (entries !is JsonArray) return MetadataResult.Failed("The file listing had an unexpected shape.")

        val entry = entries.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["path"] as? JsonPrimitive)?.contentOrNull == spec.fileName }
            ?: return MetadataResult.FileMissing(spec.fileName)

        val lfs = entry["lfs"] as? JsonObject
        // For files stored with Git LFS the real size and SHA-256 are in the "lfs" object; "size" then
        // describes the real file as well, but the LFS block is what the checksum belongs to.
        val size = (lfs?.get("size") as? JsonPrimitive)?.longOrNull
            ?: (entry["size"] as? JsonPrimitive)?.longOrNull
        if (size == null || size <= 0L) return MetadataResult.Failed("The file listing had no usable size for ${spec.fileName}.")

        val sha = (lfs?.get("oid") as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { SHA256.matches(it) }
        return MetadataResult.Found(ModelInfo(spec, Amount.Known(size), sha))
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
