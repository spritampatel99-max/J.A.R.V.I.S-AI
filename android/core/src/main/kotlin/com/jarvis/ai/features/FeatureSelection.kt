package com.jarvis.ai.features

import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.models.ModelId

enum class ProfilePreset(val title: String, val description: String) {
    ESSENTIAL("Essential", "Basic assistant and productivity functions."),
    SMART_ASSISTANT("Smart Assistant", "Voice, memory, planner, file access and selected Android operations."),
    LOCAL_AI("Local AI", "On-device AI, offline knowledge, local speech and selected offline capabilities."),
    ;

    /** What the preset is meant to contain. Features that are not built yet are dropped when it is applied. */
    val intended: Set<FeatureId>
        get() = when (this) {
            ESSENTIAL -> setOf(
                FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL,
                FeatureId.APPLICATION_LAUNCHER, FeatureId.SMART_PLANNER, FeatureId.SECURITY_CENTER,
            )
            SMART_ASSISTANT -> setOf(
                FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL,
                FeatureId.APPLICATION_LAUNCHER, FeatureId.VOICE_ASSISTANT, FeatureId.AI_MEMORY,
                FeatureId.SMART_PLANNER, FeatureId.FILE_MANAGER, FeatureId.PHONE_ASSISTANT,
                FeatureId.SECURITY_CENTER,
            )
            LOCAL_AI -> setOf(
                FeatureId.AI_ASSISTANT, FeatureId.DEVICE_CONTROL, FeatureId.LOCAL_AI_LAB,
                FeatureId.OFFLINE_KNOWLEDGE, FeatureId.OFFLINE_PREPARATION_CENTER,
                FeatureId.VOICE_ASSISTANT, FeatureId.SECURITY_CENTER,
            )
        }
}

/** What a set of chosen features adds up to. Shared dependencies appear once. */
data class Resolution(
    /** The features the user chose and that can be used today. */
    val explicit: Set<FeatureId>,
    /** Features pulled in because a chosen feature needs them, each with the features that need it. */
    val implied: Map<FeatureId, Set<FeatureId>>,
    /** Chosen features that cannot be used, with the reason (not built yet, or unknown). */
    val rejected: Map<FeatureId, String>,
    /** A chosen feature that depends on something that is not built yet, with the missing dependencies. */
    val unmet: Map<FeatureId, Set<FeatureId>>,
    val capabilities: Set<Capability>,
    /** Every permission any active feature may ask for, and whether at least one of them cannot work without it. */
    val permissions: Map<PermissionId, Boolean>,
    val needsInternet: Boolean,
) {
    val active: Set<FeatureId> get() = explicit + implied.keys
}

object FeatureResolver {

    /** [lookup] is the feature catalogue; tests pass a modified one to exercise dependency rules on features that are not built yet. */
    fun resolve(chosen: Set<FeatureId>, lookup: (FeatureId) -> Feature = FeatureCatalog::feature): Resolution {
        val explicit = linkedSetOf<FeatureId>()
        val rejected = linkedMapOf<FeatureId, String>()
        for (id in chosen.sortedBy { it.ordinal }) {
            val feature = lookup(id)
            when (val r = feature.readiness) {
                is Readiness.Available -> explicit += id
                is Readiness.Planned -> rejected[id] = "Not built yet (planned for Phase ${r.phase})."
            }
        }

        // Transitive closure over dependencies, recording who needs what.
        val implied = linkedMapOf<FeatureId, MutableSet<FeatureId>>()
        val unmet = linkedMapOf<FeatureId, MutableSet<FeatureId>>()
        val active = linkedSetOf<FeatureId>().apply { addAll(explicit) }
        val queue = ArrayDeque(explicit)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (dep in lookup(current).requires) {
                if (!lookup(dep).selectable) {
                    unmet.getOrPut(current) { linkedSetOf() } += dep
                    continue
                }
                if (dep !in explicit) implied.getOrPut(dep) { linkedSetOf() } += current
                if (active.add(dep)) queue.addLast(dep)
            }
        }

        val capabilities = linkedSetOf<Capability>()
        val permissions = linkedMapOf<PermissionId, Boolean>()
        var internet = false
        for (id in active) {
            val f = lookup(id)
            capabilities += f.capabilities
            for (p in f.permissions) permissions[p.permission] = (permissions[p.permission] ?: false) || p.required
            if (f.internet == InternetNeed.REQUIRED) internet = true
        }
        return Resolution(
            explicit = explicit,
            implied = implied.mapValues { it.value.toSet() },
            rejected = rejected,
            unmet = unmet.mapValues { it.value.toSet() },
            capabilities = capabilities,
            permissions = permissions,
            needsInternet = internet,
        )
    }
}

/** The user's choices, edited one tap at a time. Immutable; every edit returns a new value. */
data class FeatureSelection(val chosen: Set<FeatureId> = emptySet()) {

    data class Change(val selection: FeatureSelection, val alsoRemoved: Set<FeatureId> = emptySet(), val refused: String? = null)

    /** Turns a feature on. A feature that is not built yet is refused. Needed features are added by [FeatureResolver]. */
    fun select(id: FeatureId, lookup: (FeatureId) -> Feature = FeatureCatalog::feature): Change {
        val feature = lookup(id)
        val readiness = feature.readiness
        if (readiness is Readiness.Planned) {
            return Change(this, refused = "${feature.title} is not built yet (planned for Phase ${readiness.phase}).")
        }
        return Change(FeatureSelection(chosen + id))
    }

    /**
     * Turns a feature off, together with every chosen feature that depends on it,
     * and reports those so the screen can say so.
     */
    fun deselect(id: FeatureId, lookup: (FeatureId) -> Feature = FeatureCatalog::feature): Change {
        val removed = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (other in chosen) {
                if (other !in removed && lookup(other).requires.any { it in removed }) {
                    removed += other
                    grew = true
                }
            }
        }
        return Change(FeatureSelection(chosen - removed), alsoRemoved = removed - id)
    }

    fun selectAll(): FeatureSelection = FeatureSelection(FeatureCatalog.all.filter { it.selectable }.map { it.id }.toSet())

    fun deselectAll(): FeatureSelection = FeatureSelection()

    companion object {
        /** The usable part of a preset, plus the features the preset intends but that are not built yet. */
        fun fromPreset(preset: ProfilePreset): Pair<FeatureSelection, Set<FeatureId>> {
            val (usable, notBuilt) = preset.intended.partition { FeatureCatalog.feature(it).selectable }
            return FeatureSelection(usable.toSet()) to notBuilt.toSet()
        }
    }
}

/** Status labels from Part 23, plus [NOT_BUILT_YET] so a feature that does not exist is never shown as merely "not selected". */
enum class FeatureStatus {
    AVAILABLE, SELECTED, NOT_SELECTED, REQUIRES_PERMISSION, REQUIRES_DOWNLOAD, REQUIRES_INTERNET,
    OFFLINE_READY, UNSUPPORTED, PARTIALLY_SUPPORTED, NOT_BUILT_YET,
}

object FeatureStatusCalculator {

    fun statuses(
        feature: Feature,
        selected: Boolean,
        device: DeviceProfile?,
        grantedPermissions: Set<PermissionId>,
        installedModels: Set<ModelId>,
    ): Set<FeatureStatus> {
        if (!feature.selectable) return setOf(FeatureStatus.NOT_BUILT_YET)

        val out = linkedSetOf<FeatureStatus>()
        out += if (selected) FeatureStatus.SELECTED else FeatureStatus.NOT_SELECTED

        // Hardware: a measured "no" makes the feature unsupported; an unreadable value changes nothing.
        var hardwareMissing = 0
        if (device != null) {
            for (need in feature.hardware) {
                val reading = when (need) {
                    HardwareNeed.MICROPHONE -> device.microphone
                    HardwareNeed.SPEECH_RECOGNITION -> device.speechRecognition
                    HardwareNeed.TEXT_TO_SPEECH -> device.textToSpeech
                    HardwareNeed.AUDIO_OUTPUT -> device.audioOutput
                }
                if (reading.value == false) hardwareMissing++
            }
        }
        when {
            feature.hardware.isNotEmpty() && hardwareMissing == feature.hardware.size -> out += FeatureStatus.UNSUPPORTED
            hardwareMissing > 0 -> out += FeatureStatus.PARTIALLY_SUPPORTED
            else -> out += FeatureStatus.AVAILABLE
        }

        val needsPermission = feature.permissions.any { need ->
            need.required && PermissionCatalog.info(need.permission).kind != PermissionKind.INSTALL_TIME && need.permission !in grantedPermissions
        }
        if (needsPermission) out += FeatureStatus.REQUIRES_PERMISSION
        val needsDownload = Capability.LOCAL_MODEL in feature.capabilities && installedModels.isEmpty()
        if (needsDownload) out += FeatureStatus.REQUIRES_DOWNLOAD
        if (feature.internet == InternetNeed.REQUIRED) out += FeatureStatus.REQUIRES_INTERNET
        if (feature.offline == OfflineSupport.FULL && !needsPermission && !needsDownload && FeatureStatus.UNSUPPORTED !in out) {
            out += FeatureStatus.OFFLINE_READY
        }
        return out
    }
}
