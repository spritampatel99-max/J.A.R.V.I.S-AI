package com.jarvis.ai.setup

import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.device.Reading
import com.jarvis.ai.features.FeatureCatalog
import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.Resolution
import com.jarvis.ai.models.Amount
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInfo

/** A sum of [Amount]s that keeps exact, estimated and unknown parts apart instead of blending them into one false number. */
data class AmountTotal(val knownBytes: Long, val estimatedBytes: Long, val unknownCount: Int) {
    val lowerBoundBytes: Long get() = knownBytes + estimatedBytes
    val exact: Boolean get() = estimatedBytes == 0L && unknownCount == 0

    companion object {
        fun of(amounts: List<Amount>): AmountTotal = AmountTotal(
            knownBytes = amounts.filterIsInstance<Amount.Known>().sumOf { it.bytes },
            estimatedBytes = amounts.filterIsInstance<Amount.Estimate>().sumOf { it.bytes },
            unknownCount = amounts.count { it is Amount.Unknown },
        )
    }
}

data class StorageLine(
    val label: String,
    val download: Amount,
    val installed: Amount,
    /** True for the app itself: it is already on the phone, so it is shown but not added to the totals. */
    val alreadyOnDevice: Boolean = false,
)

enum class StorageVerdict { ENOUGH, TIGHT, INSUFFICIENT, UNKNOWN }

data class StorageReview(
    val selectedFeatureCount: Int,
    val impliedFeatureCount: Int,
    val lines: List<StorageLine>,
    val totalDownload: AmountTotal,
    val totalInstalled: AmountTotal,
    val availableBytes: Long?,
    /** How much more free space is needed, counting only exact and estimated amounts. Zero when there is enough. */
    val extraNeededBytes: Long,
    val verdict: StorageVerdict,
    val warnings: List<String>,
    /** True whenever anything would be downloaded: the user must agree before it starts. */
    val needsDownloadConsent: Boolean,
    val modelRam: List<Pair<ModelId, Long>>,
)

class StorageCalculator(
    private val largeDownloadBytes: Long = 50 * Bytes.MIB,
    private val headroomRatio: Double = 0.10,
    private val minHeadroomBytes: Long = 256 * Bytes.MIB,
) {

    fun review(
        resolution: Resolution,
        modelChoice: ModelChoice?,
        models: Map<ModelId, ModelInfo>,
        profile: DeviceProfile?,
        baseAppBytes: Reading<Long>,
        modelRamEstimates: Map<ModelId, Long> = emptyMap(),
    ): StorageReview {
        val lines = mutableListOf<StorageLine>()
        val warnings = mutableListOf<String>()

        // The app itself: already installed. Shown for completeness.
        lines += StorageLine(
            label = "J.A.R.V.I.S.-AI app (already installed)",
            download = Amount.NONE,
            installed = baseAppBytes.value?.let { Amount.Known(it) } ?: Amount.Unknown("Could not be read"),
            alreadyOnDevice = true,
        )

        // Models
        val chosenModels = modelChoice?.models.orEmpty()
        for (id in chosenModels) {
            val info = models[id]
            val size = info?.size ?: Amount.Unknown("Size not known yet")
            lines += StorageLine(info?.spec?.displayName ?: id.name, download = size, installed = size)
        }
        if (chosenModels.isEmpty() && FeatureId.LOCAL_AI_LAB in resolution.active) {
            warnings += "Local AI Lab is selected but no model is chosen. Local AI will not work until one is installed."
        }

        // Features: each contributes its own extra download/storage; shared capabilities add nothing twice
        // because only features (not capabilities) carry costs and each feature is counted once.
        for (id in resolution.active.sortedBy { it.ordinal }) {
            val f = FeatureCatalog.feature(id)
            if (f.extraDownload == Amount.NONE && f.extraStorage == Amount.NONE) continue
            lines += StorageLine(f.title, f.extraDownload, f.extraStorage)
        }

        // Things that cannot be sized until they exist.
        lines += StorageLine(
            "Database, cache and logs",
            download = Amount.NONE,
            installed = Amount.Unknown("Grows with use; not measured yet"),
        )

        val counted = lines.filterNot { it.alreadyOnDevice }
        val totalDownload = AmountTotal.of(counted.map { it.download })
        val totalInstalled = AmountTotal.of(counted.map { it.installed })

        val available = profile?.freeStorageBytes?.value
        val needed = totalInstalled.lowerBoundBytes
        val headroom = if (needed == 0L) 0L else maxOf((needed * headroomRatio).toLong(), minHeadroomBytes)
        val verdict = when {
            available == null -> StorageVerdict.UNKNOWN
            available < needed -> StorageVerdict.INSUFFICIENT
            available < needed + headroom -> StorageVerdict.TIGHT
            else -> StorageVerdict.ENOUGH
        }
        val extra = if (available == null) 0L else maxOf(0L, needed - available)

        when (verdict) {
            StorageVerdict.INSUFFICIENT -> warnings += "Not enough free storage: about ${ByteFormat.format(needed)} is needed and " +
                "${ByteFormat.format(available ?: 0)} is free. Free up at least ${ByteFormat.format(extra)} or choose less."
            StorageVerdict.TIGHT -> warnings += "Free storage is tight. Keep some space free so the phone keeps working normally."
            StorageVerdict.UNKNOWN -> warnings += "Free storage could not be read, so the check cannot be done."
            StorageVerdict.ENOUGH -> Unit
        }
        if (!totalDownload.exact && totalDownload.lowerBoundBytes > 0) {
            warnings += "Some sizes are estimates. The exact size is read from the model repository before the download starts."
        }

        return StorageReview(
            selectedFeatureCount = resolution.explicit.size,
            impliedFeatureCount = resolution.implied.size,
            lines = lines,
            totalDownload = totalDownload,
            totalInstalled = totalInstalled,
            availableBytes = available,
            extraNeededBytes = extra,
            verdict = verdict,
            warnings = warnings,
            needsDownloadConsent = needsConsent(totalDownload),
            modelRam = chosenModels.mapNotNull { id -> modelRamEstimates[id]?.let { id to it } },
        )
    }

    /** Downloads of unknown size, and anything at or above the large-download threshold, always need an explicit yes. */
    fun needsConsent(total: AmountTotal): Boolean =
        total.unknownCount > 0 || total.lowerBoundBytes >= largeDownloadBytes
}
