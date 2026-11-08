package com.jarvis.ai.models

import com.jarvis.ai.core.common.Bytes

/**
 * A quantity of storage. Which of the three it is decides how the UI words it:
 * [Known] is an exact, verified number (or a genuine zero), [Estimate] is shown as "about",
 * and [Unknown] is shown as "known after download" or similar. Nothing is ever turned into a
 * made-up exact number.
 */
sealed interface Amount {
    data class Known(val bytes: Long) : Amount {
        init {
            require(bytes >= 0) { "size cannot be negative" }
        }
    }

    data class Estimate(val bytes: Long, val basis: String) : Amount {
        init {
            require(bytes >= 0) { "size cannot be negative" }
        }
    }

    data class Unknown(val reason: String) : Amount

    companion object {
        val NONE: Amount = Known(0)
    }
}

enum class ModelId { GEMMA3_1B, GEMMA4_E2B }

enum class ModelTier { LIGHT, ADVANCED }

/** The four choices the setup offers (Part 7.2). Nothing downloads because of a choice alone. */
enum class ModelChoice {
    LIGHT_ONLY,
    ADVANCED_ONLY,
    BOTH,
    SKIP,
    ;

    val models: List<ModelId>
        get() = when (this) {
            LIGHT_ONLY -> listOf(ModelId.GEMMA3_1B)
            ADVANCED_ONLY -> listOf(ModelId.GEMMA4_E2B)
            BOTH -> listOf(ModelId.GEMMA3_1B, ModelId.GEMMA4_E2B)
            SKIP -> emptyList()
        }
}

/** Static facts about a model that do not depend on the phone. Sizes are deliberately not part of it. */
data class ModelSpec(
    val id: ModelId,
    val tier: ModelTier,
    val displayName: String,
    val category: String,
    val repo: String,
    val fileName: String,
    /** True when the repository needs the user to accept a licence and sign in before downloading. */
    val gated: Boolean,
    val license: String,
    val bestFor: List<String>,
    val capabilityNote: String,
    /** What the vendor says about memory use, with its source. It is not a requirement for other phones. */
    val vendorMemoryNote: String?,
) {
    val host: String get() = "huggingface.co"
    val metadataUrl: String get() = "https://$host/api/models/$repo/tree/main"
    val downloadUrl: String get() = "https://$host/$repo/resolve/main/$fileName"
}

/**
 * A model with the best size information currently available.
 * [size] is [Amount.Known] only when it came from the repository's own metadata at run time.
 */
data class ModelInfo(
    val spec: ModelSpec,
    val size: Amount,
    /** SHA-256 of the file as published by the repository; needed to verify a download. */
    val sha256: String? = null,
) {
    val verified: Boolean get() = size is Amount.Known
    val sizeBytes: Long?
        get() = when (size) {
            is Amount.Known -> size.bytes
            is Amount.Estimate -> size.bytes
            is Amount.Unknown -> null
        }
}

object ModelCatalog {
    private const val SOURCE_NOTE =
        "Hugging Face file listing read on 2026-11-07 through a web fetch; the app re-reads the real value before any download."

    val gemma3OneB = ModelSpec(
        id = ModelId.GEMMA3_1B,
        tier = ModelTier.LIGHT,
        displayName = "Gemma 3 1B",
        category = "Lightweight local AI",
        repo = "litert-community/Gemma3-1B-IT",
        fileName = "gemma3-1b-it-int4.litertlm",
        gated = true,
        license = "Gemma terms of use (accepted by you on Hugging Face)",
        bestFor = listOf(
            "Basic conversations",
            "Everyday questions",
            "Short summaries",
            "Simple explanations",
            "Basic text processing",
            "Routine assistant tasks",
        ),
        capabilityNote = "Text only.",
        vendorMemoryNote = null,
    )

    val gemma4E2b = ModelSpec(
        id = ModelId.GEMMA4_E2B,
        tier = ModelTier.ADVANCED,
        displayName = "Gemma 4 E2B",
        category = "Advanced local AI",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        gated = false,
        license = "Apache-2.0",
        bestFor = listOf(
            "More demanding reasoning",
            "Complex conversations",
            "More complex document questions",
            "Advanced local assistance",
            "Image and audio understanding where supported",
        ),
        capabilityNote = "The model card lists image and audio input. Whether this app can use them on Android is not verified yet.",
        vendorMemoryNote = "Model card: about 1,733 MB peak memory on CPU and 676 MB on GPU, measured on one phone (Galaxy S26 Ultra). " +
            "That is one device, not a requirement for other phones.",
    )

    val all: List<ModelSpec> = listOf(gemma3OneB, gemma4E2b)

    fun spec(id: ModelId): ModelSpec = all.first { it.id == id }

    /** Sizes from the file listing, labelled as estimates until the app reads them from the repository itself. */
    val estimatedSizes: Map<ModelId, Amount> = mapOf(
        ModelId.GEMMA3_1B to Amount.Estimate(584_417_280L, SOURCE_NOTE),
        ModelId.GEMMA4_E2B to Amount.Estimate(2_588_147_712L, SOURCE_NOTE),
    )

    fun initialInfo(id: ModelId): ModelInfo {
        val spec = spec(id)
        return ModelInfo(spec, estimatedSizes[id] ?: Amount.Unknown("Size not known yet"))
    }

    /** Smallest free space the advisor and calculator treat as meaningful headroom. */
    val MIN_HEADROOM_BYTES: Long = 256 * Bytes.MIB
}
