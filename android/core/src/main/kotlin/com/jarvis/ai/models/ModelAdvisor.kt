package com.jarvis.ai.models

import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.device.ConnectionType
import com.jarvis.ai.device.DeviceProfile

enum class Verdict {
    /** Nothing found that argues against it. Still only confirmed by a test load after download. */
    LOOKS_SUITABLE,

    /** Possible, but at least one measured value is tight. */
    USE_WITH_CAUTION,

    /** A measured value is clearly short. */
    NOT_RECOMMENDED,

    /** Something needed for a judgement is unknown, so no judgement is made. */
    CANNOT_ASSESS,
}

enum class Level { OK, WARNING, PROBLEM, INFO }

/** Where the number behind a finding came from. */
enum class Basis {
    /** Read from this phone. */
    MEASURED,

    /** Size taken from a catalogue estimate, not yet read from the repository. */
    ESTIMATE,

    /** A rule of thumb chosen by this app. It has not been calibrated on real devices. */
    HEURISTIC,

    /** Not verified at all. */
    UNVERIFIED,
}

data class Finding(val level: Level, val text: String, val basis: Basis)

/**
 * Tunable rules of thumb. They are not vendor requirements and have not been calibrated on devices;
 * they exist so the numbers are visible and can be corrected once the first real model loads have been measured.
 */
data class AdvisorPolicy(
    /** Working memory assumed on top of the model file (context cache, runtime buffers). */
    val ramWorkingMarginBytes: Long = 512 * Bytes.MIB,
    /** The phone's total RAM must be at least this multiple of the estimate, since Android and other apps need room too. */
    val totalRamToEstimateRatio: Double = 2.0,
    val storageHeadroomRatio: Double = 0.10,
    val storageMinHeadroomBytes: Long = ModelCatalog.MIN_HEADROOM_BYTES,
    val lowBatteryPercent: Int = 20,
    val largeDownloadBytes: Long = 100 * Bytes.MIB,
)

data class ModelAssessment(
    val info: ModelInfo,
    val verdict: Verdict,
    val findings: List<Finding>,
    val ramEstimateBytes: Long?,
    val freeStorageNeededBytes: Long?,
    /** Things to know before downloading (mobile data, low battery). They do not change the verdict. */
    val downloadAdvice: List<String>,
)

data class ModelRecommendation(
    val assessments: List<ModelAssessment>,
    val recommended: ModelId?,
    val explanation: String,
) {
    fun assessment(id: ModelId): ModelAssessment = assessments.first { it.info.spec.id == id }
}

class ModelAdvisor(private val policy: AdvisorPolicy = AdvisorPolicy()) {

    fun assess(profile: DeviceProfile, info: ModelInfo): ModelAssessment {
        val findings = mutableListOf<Finding>()
        var cannotAssess = false

        // 1. Architecture
        if (profile.supportsArm64) {
            findings += Finding(Level.OK, "This phone reports the arm64-v8a architecture.", Basis.MEASURED)
        } else {
            cannotAssess = true
            val abis = profile.supportedAbis.joinToString().ifEmpty { "none reported" }
            findings += Finding(
                Level.INFO,
                "This phone reports $abis. Runtime support for that architecture has not been verified, so no judgement is made.",
                Basis.UNVERIFIED,
            )
        }

        // 2. Size
        val sizeBytes = info.sizeBytes
        val sizeBasis = if (info.verified) Basis.MEASURED else Basis.ESTIMATE
        var ramEstimate: Long? = null
        var storageNeeded: Long? = null

        if (sizeBytes == null) {
            cannotAssess = true
            findings += Finding(Level.INFO, "The file size is not known yet, so storage and memory cannot be checked.", Basis.UNVERIFIED)
        } else {
            val sizeText = (if (info.verified) "" else "about ") + ByteFormat.format(sizeBytes)

            // 3. Storage (a download goes to a temporary file in the same folder and is then renamed, so one copy is needed)
            val headroom = maxOf((sizeBytes * policy.storageHeadroomRatio).toLong(), policy.storageMinHeadroomBytes)
            storageNeeded = sizeBytes + headroom
            val free = profile.freeStorageBytes.value
            if (free == null) {
                cannotAssess = true
                findings += Finding(Level.INFO, "Free storage could not be read.", Basis.UNVERIFIED)
            } else if (free < sizeBytes) {
                findings += Finding(
                    Level.PROBLEM,
                    "Not enough free storage: the file is $sizeText and ${ByteFormat.format(free)} is free.",
                    sizeBasis,
                )
            } else if (free < storageNeeded) {
                findings += Finding(
                    Level.WARNING,
                    "Storage is tight: the file is $sizeText, ${ByteFormat.format(free)} is free, and " +
                        "${ByteFormat.format(storageNeeded)} leaves a safe margin.",
                    sizeBasis,
                )
            } else {
                findings += Finding(Level.OK, "Enough free storage (${ByteFormat.format(free)} free, file $sizeText).", sizeBasis)
            }

            // 4. Memory (rule of thumb: file size plus working memory)
            ramEstimate = sizeBytes + policy.ramWorkingMarginBytes
            val total = profile.totalRamBytes.value
            if (total == null) {
                cannotAssess = true
                findings += Finding(Level.INFO, "Total memory could not be read.", Basis.UNVERIFIED)
            } else {
                val needTotal = (ramEstimate * policy.totalRamToEstimateRatio).toLong()
                val rule = "Rule of thumb: about ${ByteFormat.format(ramEstimate)} in use, on a phone with at least " +
                    "${ByteFormat.format(needTotal)} in total. Real use is measured when the model is first loaded."
                val available = profile.availableRamBytes.value
                when {
                    total < needTotal -> findings += Finding(
                        Level.PROBLEM,
                        "This phone reports ${ByteFormat.format(total)} of memory in total, below the rule of thumb. $rule",
                        Basis.HEURISTIC,
                    )
                    available != null && available < ramEstimate -> findings += Finding(
                        Level.WARNING,
                        "Only ${ByteFormat.format(available)} of memory is free right now. Close other apps before loading. $rule",
                        Basis.HEURISTIC,
                    )
                    profile.lowMemory.value == true -> findings += Finding(
                        Level.WARNING,
                        "Android reports low memory right now. $rule",
                        Basis.MEASURED,
                    )
                    else -> findings += Finding(Level.OK, "Total memory ${ByteFormat.format(total)} is above the rule of thumb. $rule", Basis.HEURISTIC)
                }
            }
        }

        if (info.spec.gated) {
            findings += Finding(
                Level.INFO,
                "Download needs a Hugging Face account that has accepted the model's licence (token), or a file you downloaded yourself.",
                Basis.UNVERIFIED,
            )
        }
        info.spec.vendorMemoryNote?.let { findings += Finding(Level.INFO, it, Basis.UNVERIFIED) }

        val verdict = when {
            findings.any { it.level == Level.PROBLEM } -> Verdict.NOT_RECOMMENDED
            cannotAssess -> Verdict.CANNOT_ASSESS
            findings.any { it.level == Level.WARNING } -> Verdict.USE_WITH_CAUTION
            else -> Verdict.LOOKS_SUITABLE
        }
        return ModelAssessment(info, verdict, findings, ramEstimate, storageNeeded, downloadAdvice(profile, sizeBytes))
    }

    private fun downloadAdvice(profile: DeviceProfile, sizeBytes: Long?): List<String> {
        val advice = mutableListOf<String>()
        val large = sizeBytes == null || sizeBytes >= policy.largeDownloadBytes
        if (profile.online.value == false) advice += "There is no internet connection right now."
        if (large && (profile.connection.value == ConnectionType.CELLULAR || profile.metered.value == true)) {
            advice += "You are on a metered or mobile connection. Wi-Fi is better for a download this size."
        }
        val battery = profile.batteryPercent.value
        if (large && battery != null && battery < policy.lowBatteryPercent && profile.charging.value != true) {
            advice += "Battery is at $battery% and not charging."
        }
        return advice
    }

    /**
     * Picks a starting point. The lighter model comes first when it looks suitable (a smaller download, and the
     * advanced one can be added later). A model is never recommended because of memory size alone: storage,
     * architecture and a known size must all have been checked.
     */
    fun recommend(profile: DeviceProfile, infos: List<ModelInfo>): ModelRecommendation {
        val assessments = infos.map { assess(profile, it) }
        val ordered = assessments.sortedBy { it.info.spec.tier.ordinal }
        val pick = ordered.firstOrNull { it.verdict == Verdict.LOOKS_SUITABLE }
            ?: ordered.firstOrNull { it.verdict == Verdict.USE_WITH_CAUTION }
        val explanation = when {
            pick == null && assessments.all { it.verdict == Verdict.CANNOT_ASSESS } ->
                "No model can be judged yet because some measurements are missing. You can still choose one and the first load will show whether it runs."
            pick == null ->
                "None of the models looks suitable on this phone right now. Cloud AI and the non-AI features still work; you can skip the download and add a model later."
            pick.verdict == Verdict.USE_WITH_CAUTION ->
                "${pick.info.spec.displayName} is possible, but check the warnings first."
            pick.info.spec.tier == ModelTier.LIGHT && ordered.any { it.info.spec.tier == ModelTier.ADVANCED && it.verdict == Verdict.LOOKS_SUITABLE } ->
                "${pick.info.spec.displayName} is the smaller download and a good first step. The advanced model also looks possible and can be added later."
            else -> "${pick.info.spec.displayName} looks suitable. It is only confirmed after a test load."
        }
        return ModelRecommendation(assessments, pick?.info?.spec?.id, explanation)
    }

    companion object {
        const val CONFIRMATION_NOTE = "Offline AI is only marked ready after the model has been downloaded, verified and test-loaded on this phone."
    }
}
