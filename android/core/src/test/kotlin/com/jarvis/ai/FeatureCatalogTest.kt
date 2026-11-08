package com.jarvis.ai

import com.jarvis.ai.features.Capability
import com.jarvis.ai.features.Feature
import com.jarvis.ai.features.FeatureCatalog
import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.FeatureResolver
import com.jarvis.ai.features.FeatureSelection
import com.jarvis.ai.features.FeatureStatus
import com.jarvis.ai.features.FeatureStatusCalculator
import com.jarvis.ai.features.PermissionCatalog
import com.jarvis.ai.features.PermissionId
import com.jarvis.ai.features.PermissionKind
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.features.Readiness
import com.jarvis.ai.models.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureCatalogTest {

    /** Same catalogue with every feature treated as built, to exercise dependency rules that matter once the phases land. */
    private val everythingBuilt: (FeatureId) -> Feature =
        { id -> FeatureCatalog.feature(id).copy(readiness = Readiness.Available) }

    // ---- catalogue integrity -----------------------------------------------------------------

    @Test
    fun allTwentyFiveFeaturesFromTheProductPromptArePresentOnce() {
        assertEquals(25, FeatureId.entries.size)
        assertEquals(FeatureId.entries.toSet(), FeatureCatalog.all.map { it.id }.toSet())
        assertEquals(25, FeatureCatalog.all.size)
    }

    @Test
    fun everyFeatureHasATitleAndADescription() {
        for (f in FeatureCatalog.all) {
            assertTrue(f.title.isNotBlank() && f.description.isNotBlank(), f.id.name)
        }
    }

    @Test
    fun dependenciesPointToRealFeaturesAndNeverToThemselves() {
        for (f in FeatureCatalog.all) {
            assertFalse(f.id in f.requires, "${f.id} requires itself")
            assertTrue(f.requires.all { it in FeatureId.entries }, f.id.name)
        }
    }

    @Test
    fun noDependencyCycles() {
        fun reaches(from: FeatureId, target: FeatureId, seen: MutableSet<FeatureId> = mutableSetOf()): Boolean {
            if (!seen.add(from)) return false
            return FeatureCatalog.feature(from).requires.any { it == target || reaches(it, target, seen) }
        }
        for (f in FeatureCatalog.all) assertFalse(reaches(f.id, f.id), "cycle through ${f.id}")
    }

    @Test
    fun aFeatureThatIsBuiltNeverDependsOnOneThatIsNot() {
        for (f in FeatureCatalog.all.filter { it.selectable }) {
            for (dep in f.requires) assertTrue(FeatureCatalog.feature(dep).selectable, "${f.id} needs unbuilt $dep")
        }
    }

    @Test
    fun plannedFeaturesNameARealFuturePhase() {
        for (f in FeatureCatalog.all) {
            val r = f.readiness
            if (r is Readiness.Planned) assertTrue(r.phase in 4..10, "${f.id} phase ${r.phase}")
        }
    }

    @Test
    fun onlyFeaturesThatExistTodayAreSelectable() {
        assertEquals(
            setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL),
            FeatureCatalog.all.filter { it.selectable }.map { it.id }.toSet(),
        )
    }

    @Test
    fun everyPermissionAFeatureMentionsIsExplained() {
        for (f in FeatureCatalog.all) for (p in f.permissions) assertNotNull(PermissionCatalog.all.find { it.id == p.permission })
    }

    @Test
    fun everyPermissionHasAllFiveExplanationsAndNoPermissionIsUnused() {
        val used = FeatureCatalog.all.flatMap { f -> f.permissions.map { it.permission } }.toSet()
        for (p in PermissionCatalog.all) {
            assertTrue(p.title.isNotBlank() && p.purpose.isNotBlank() && p.informationAccessed.isNotBlank() && p.howToRevoke.isNotBlank(), p.id.name)
            if (p.id !in setOf(PermissionId.INTERNET, PermissionId.ACCESS_NETWORK_STATE)) assertTrue(p.id in used, "${p.id} is listed but no feature needs it")
        }
        assertEquals(PermissionId.entries.toSet(), PermissionCatalog.all.map { it.id }.toSet())
    }

    @Test
    fun noPermissionIsRequestedThatNoFeatureNeeds_andNoBroadStorageAccessExists() {
        assertTrue(PermissionId.entries.none { "STORAGE" in it.name || "ACCESSIBILITY" in it.name || "SMS" in it.name })
    }

    @Test
    fun specialAccessIsNeverInstallTime() {
        assertEquals(
            setOf(PermissionId.NOTIFICATION_LISTENER, PermissionId.USAGE_ACCESS, PermissionId.EXACT_ALARMS),
            PermissionCatalog.all.filter { it.kind == PermissionKind.SPECIAL_ACCESS }.map { it.id }.toSet(),
        )
    }

    // ---- resolving a selection ---------------------------------------------------------------

    @Test
    fun choosingDeviceControlPullsInTheAssistantAndSaysWhy() {
        val r = FeatureResolver.resolve(setOf(FeatureId.DEVICE_CONTROL))
        assertEquals(setOf(FeatureId.DEVICE_CONTROL), r.explicit)
        assertEquals(mapOf(FeatureId.AI_ASSISTANT to setOf(FeatureId.DEVICE_CONTROL)), r.implied)
        assertEquals(setOf(FeatureId.DEVICE_CONTROL, FeatureId.AI_ASSISTANT), r.active)
        assertTrue(r.needsInternet)
    }

    @Test
    fun aFeatureChosenAndAlsoNeededIsNotCountedAsImplied() {
        val r = FeatureResolver.resolve(setOf(FeatureId.DEVICE_CONTROL, FeatureId.AI_ASSISTANT))
        assertTrue(r.implied.isEmpty())
        assertEquals(2, r.active.size)
    }

    @Test
    fun aFeatureThatIsNotBuiltIsRejectedWithItsPhase() {
        val r = FeatureResolver.resolve(setOf(FeatureId.VOICE_ASSISTANT, FeatureId.CLOUD_AI))
        assertEquals(setOf(FeatureId.CLOUD_AI), r.explicit)
        assertTrue("Phase 6" in r.rejected.getValue(FeatureId.VOICE_ASSISTANT))
        assertTrue(r.capabilities.isEmpty())
    }

    @Test
    fun nothingChosenMeansNothingActive() {
        val r = FeatureResolver.resolve(emptySet())
        assertTrue(r.active.isEmpty() && r.permissions.isEmpty() && !r.needsInternet)
    }

    @Test
    fun sharedCapabilitiesAreCountedOnceAndDependenciesChainThrough() {
        val r = FeatureResolver.resolve(setOf(FeatureId.HANDS_FREE, FeatureId.VOICE_ASSISTANT), everythingBuilt)
        assertEquals(setOf(FeatureId.HANDS_FREE, FeatureId.VOICE_ASSISTANT), r.explicit)
        assertEquals(setOf(FeatureId.AI_ASSISTANT), r.implied.keys)
        // Both features need the audio system; the set holds it once.
        assertEquals(1, r.capabilities.count { it == Capability.AUDIO_SYSTEM })
        assertTrue(Capability.SPEECH_RECOGNITION in r.capabilities && Capability.AI_ROUTER in r.capabilities)
    }

    @Test
    fun transitiveDependenciesAreAllPulledIn() {
        val r = FeatureResolver.resolve(setOf(FeatureId.HANDS_FREE), everythingBuilt)
        assertEquals(setOf(FeatureId.VOICE_ASSISTANT, FeatureId.AI_ASSISTANT), r.implied.keys)
        assertEquals(setOf(FeatureId.HANDS_FREE), r.implied.getValue(FeatureId.VOICE_ASSISTANT))
    }

    @Test
    fun aPermissionIsRequiredIfAnyActiveFeatureRequiresIt() {
        val r = FeatureResolver.resolve(setOf(FeatureId.HANDS_FREE), everythingBuilt)
        assertEquals(true, r.permissions[PermissionId.RECORD_AUDIO])
        assertEquals(false, r.permissions[PermissionId.NOTIFICATION_LISTENER])
    }

    @Test
    fun anOptionalNeedBecomesRequiredWhenAnotherFeatureRequiresItToo() {
        val custom: (FeatureId) -> Feature = {
            val f = everythingBuilt(it)
            if (it == FeatureId.WHATSAPP_ASSISTANT) f.copy(permissions = f.permissions + com.jarvis.ai.features.PermissionNeed(PermissionId.RECORD_AUDIO, false)) else f
        }
        val r = FeatureResolver.resolve(setOf(FeatureId.WHATSAPP_ASSISTANT, FeatureId.VOICE_ASSISTANT), custom)
        assertEquals(true, r.permissions[PermissionId.RECORD_AUDIO])
    }

    @Test
    fun aBuiltFeatureNeedingAnUnbuiltOneReportsItAsUnmet() {
        val custom: (FeatureId) -> Feature = {
            val f = FeatureCatalog.feature(it)
            if (it == FeatureId.DEVICE_CONTROL) f.copy(requires = f.requires + FeatureId.VOICE_ASSISTANT) else f
        }
        val r = FeatureResolver.resolve(setOf(FeatureId.DEVICE_CONTROL), custom)
        assertEquals(mapOf(FeatureId.DEVICE_CONTROL to setOf(FeatureId.VOICE_ASSISTANT)), r.unmet)
        assertFalse(FeatureId.VOICE_ASSISTANT in r.active)
    }

    // ---- editing a selection -----------------------------------------------------------------

    @Test
    fun selectingABuiltFeatureAddsItAndAnUnbuiltOneIsRefusedWithAReason() {
        val ok = FeatureSelection().select(FeatureId.CLOUD_AI)
        assertEquals(setOf(FeatureId.CLOUD_AI), ok.selection.chosen)
        assertNull(ok.refused)

        val refused = FeatureSelection().select(FeatureId.AI_MEMORY)
        assertTrue(refused.selection.chosen.isEmpty())
        assertTrue("not built yet" in refused.refused!!)
    }

    @Test
    fun deselectingAFeatureAlsoRemovesTheChosenFeaturesThatNeedIt() {
        val start = FeatureSelection(setOf(FeatureId.AI_ASSISTANT, FeatureId.DEVICE_CONTROL, FeatureId.CLOUD_AI))
        val change = start.deselect(FeatureId.AI_ASSISTANT)
        assertEquals(setOf(FeatureId.CLOUD_AI), change.selection.chosen)
        assertEquals(setOf(FeatureId.DEVICE_CONTROL), change.alsoRemoved)
    }

    @Test
    fun deselectingFollowsLongerChains() {
        val start = FeatureSelection(setOf(FeatureId.AI_ASSISTANT, FeatureId.VOICE_ASSISTANT, FeatureId.HANDS_FREE))
        val change = start.deselect(FeatureId.VOICE_ASSISTANT, everythingBuilt)
        assertEquals(setOf(FeatureId.AI_ASSISTANT), change.selection.chosen)
        assertEquals(setOf(FeatureId.HANDS_FREE), change.alsoRemoved)
    }

    @Test
    fun selectAllOnlySelectsBuiltFeaturesAndDeselectAllClears() {
        val all = FeatureSelection().selectAll()
        assertEquals(setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL), all.chosen)
        assertTrue(all.deselectAll().chosen.isEmpty())
    }

    // ---- presets -----------------------------------------------------------------------------

    @Test
    fun presetsKeepOnlyBuiltFeaturesAndReportTheRest() {
        val (sel, notBuilt) = FeatureSelection.fromPreset(ProfilePreset.ESSENTIAL)
        assertEquals(setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL), sel.chosen)
        assertTrue(FeatureId.SMART_PLANNER in notBuilt && FeatureId.SECURITY_CENTER in notBuilt)
    }

    @Test
    fun theLocalAiPresetNamesLocalFeaturesEvenThoughTheyAreNotBuilt() {
        val (sel, notBuilt) = FeatureSelection.fromPreset(ProfilePreset.LOCAL_AI)
        assertTrue(FeatureId.LOCAL_AI_LAB in notBuilt && FeatureId.OFFLINE_KNOWLEDGE in notBuilt)
        assertFalse(FeatureId.CLOUD_AI in sel.chosen, "the local preset should not switch cloud on")
    }

    @Test
    fun smartAssistantIsAtLeastAsBigAsEssentialAndNoPresetEnablesEverything() {
        assertTrue(ProfilePreset.SMART_ASSISTANT.intended.containsAll(ProfilePreset.ESSENTIAL.intended - FeatureId.SMART_PLANNER))
        for (p in ProfilePreset.entries) assertTrue(p.intended.size < FeatureId.entries.size, p.name)
    }

    @Test
    fun aPresetStaysEditable() {
        val (sel, _) = FeatureSelection.fromPreset(ProfilePreset.ESSENTIAL)
        val edited = sel.deselect(FeatureId.CLOUD_AI).selection
        assertFalse(FeatureId.CLOUD_AI in edited.chosen)
        assertTrue(FeatureId.AI_ASSISTANT in edited.chosen)
    }

    // ---- statuses ----------------------------------------------------------------------------

    private fun status(
        id: FeatureId,
        selected: Boolean = true,
        device: com.jarvis.ai.device.DeviceProfile? = phone(),
        granted: Set<PermissionId> = emptySet(),
        models: Set<ModelId> = emptySet(),
        built: Boolean = true,
    ): Set<FeatureStatus> {
        val f = if (built) everythingBuilt(id) else FeatureCatalog.feature(id)
        return FeatureStatusCalculator.statuses(f, selected, device, granted, models)
    }

    @Test
    fun aFeatureThatIsNotBuiltOnlyEverSaysSo() {
        assertEquals(setOf(FeatureStatus.NOT_BUILT_YET), status(FeatureId.VOICE_ASSISTANT, built = false))
    }

    @Test
    fun aBuiltOfflineFeatureWithNoRuntimePermissionIsOfflineReady() {
        val s = status(FeatureId.DEVICE_CONTROL)
        assertTrue(FeatureStatus.SELECTED in s && FeatureStatus.AVAILABLE in s && FeatureStatus.OFFLINE_READY in s)
        assertFalse(FeatureStatus.REQUIRES_PERMISSION in s, "SET_ALARM is granted at install")
    }

    @Test
    fun notSelectedIsReportedForAnUnselectedFeature() {
        assertTrue(FeatureStatus.NOT_SELECTED in status(FeatureId.DEVICE_CONTROL, selected = false))
    }

    @Test
    fun aMissingRuntimePermissionIsReportedAndClearedWhenGranted() {
        assertTrue(FeatureStatus.REQUIRES_PERMISSION in status(FeatureId.VOICE_ASSISTANT))
        assertFalse(FeatureStatus.REQUIRES_PERMISSION in status(FeatureId.VOICE_ASSISTANT, granted = setOf(PermissionId.RECORD_AUDIO)))
    }

    @Test
    fun anOptionalPermissionDoesNotBlockAFeature() {
        // SMS_ASSISTANT only has optional contact access.
        assertFalse(FeatureStatus.REQUIRES_PERMISSION in status(FeatureId.SMS_ASSISTANT))
    }

    @Test
    fun aMeasuredMissingMicrophoneMakesVoiceUnsupportedAndPartialHardwareIsPartial() {
        val none = status(FeatureId.VOICE_ASSISTANT, device = phone(microphone = false, speech = false, tts = false))
        assertTrue(FeatureStatus.UNSUPPORTED in none && FeatureStatus.OFFLINE_READY !in none)
        val partial = status(FeatureId.VOICE_ASSISTANT, device = phone(tts = false))
        assertTrue(FeatureStatus.PARTIALLY_SUPPORTED in partial && FeatureStatus.UNSUPPORTED !in partial)
    }

    @Test
    fun anUnreadableHardwareValueIsNotTreatedAsMissing() {
        val s = status(FeatureId.VOICE_ASSISTANT, device = phone(microphone = null, speech = null, tts = null))
        assertTrue(FeatureStatus.UNSUPPORTED !in s && FeatureStatus.PARTIALLY_SUPPORTED !in s)
    }

    @Test
    fun withoutADeviceProfileHardwareIsNotJudged() {
        assertTrue(FeatureStatus.UNSUPPORTED !in status(FeatureId.VOICE_ASSISTANT, device = null))
    }

    @Test
    fun localAiNeedsADownloadUntilAModelIsInstalled() {
        val before = status(FeatureId.LOCAL_AI_LAB)
        assertTrue(FeatureStatus.REQUIRES_DOWNLOAD in before && FeatureStatus.OFFLINE_READY !in before)
        val after = status(FeatureId.LOCAL_AI_LAB, models = setOf(ModelId.GEMMA3_1B))
        assertTrue(FeatureStatus.REQUIRES_DOWNLOAD !in after && FeatureStatus.OFFLINE_READY in after)
    }

    @Test
    fun cloudFeaturesRequireInternetAndAreNeverOfflineReady() {
        val s = status(FeatureId.CLOUD_AI)
        assertTrue(FeatureStatus.REQUIRES_INTERNET in s && FeatureStatus.OFFLINE_READY !in s)
    }
}
