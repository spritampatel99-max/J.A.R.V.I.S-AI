package com.jarvis.ai

import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.setup.SetupFlow
import com.jarvis.ai.setup.SetupRepository
import com.jarvis.ai.setup.SetupState
import com.jarvis.ai.setup.SetupStateCodec
import com.jarvis.ai.setup.SetupStep
import com.jarvis.ai.setup.SetupStorage
import com.jarvis.ai.setup.StepResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupRepositoryTest {

    private class MemoryStorage(var text: String? = null, var failWrites: Boolean = false) : SetupStorage {
        var writes = 0
        override fun read(): String? = text
        override fun write(text: String): Boolean {
            writes++
            if (failWrites) return false
            this.text = text
            return true
        }
    }

    private val time = FakeTime(5_000L)

    private fun repo(storage: MemoryStorage) = SetupRepository(storage, time)

    @Test
    fun anEmptyStorageGivesAFreshState() {
        val s = repo(MemoryStorage()).load()
        assertEquals(SetupStep.WELCOME, s.current)
        assertTrue(s.completed.isEmpty())
    }

    @Test
    fun progressSurvivesRestartingTheApp() {
        val storage = MemoryStorage()
        val first = repo(storage)
        first.apply { SetupFlow.complete(it, SetupStep.WELCOME) }
        first.update { SetupFlow.withPrivacyAcknowledged(it) }
        first.apply { SetupFlow.complete(it, SetupStep.PRIVACY) }

        val reopened = repo(storage).load()
        assertEquals(setOf(SetupStep.WELCOME, SetupStep.PRIVACY), reopened.completed)
        assertEquals(SetupStep.DEVICE, reopened.current)
        assertTrue(reopened.privacyAcknowledged)
    }

    @Test
    fun choicesSurviveRestartingTheApp() {
        val storage = MemoryStorage()
        repo(storage).update {
            SetupFlow.withFeatures(SetupFlow.withModelChoice(it, ModelChoice.BOTH), setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI), ProfilePreset.ESSENTIAL)
        }
        val s = repo(storage).load()
        assertEquals(ModelChoice.BOTH, s.modelChoice)
        assertEquals(setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI), s.features)
        assertEquals(ProfilePreset.ESSENTIAL, s.preset)
    }

    @Test
    fun theTimestampComesFromTheClock() {
        val storage = MemoryStorage()
        val r = repo(storage)
        time.now = 9_000L
        assertEquals(9_000L, r.update { it }.updatedAtMs)
        assertEquals(9_000L, repo(storage).load().updatedAtMs)
    }

    @Test
    fun aRefusedStepWritesNothing() {
        val storage = MemoryStorage()
        val r = repo(storage)
        val result = r.apply { SetupFlow.complete(it, SetupStep.FEATURES) }
        assertIs<StepResult.Refused>(result)
        assertEquals(0, storage.writes)
    }

    @Test
    fun aFailedWriteKeepsTheStateInMemoryAndIsReported() {
        val storage = MemoryStorage(failWrites = true)
        val r = repo(storage)
        val state = r.update { it.copy(privacyAcknowledged = true) }
        assertTrue(state.privacyAcknowledged)
        assertTrue(r.lastSaveFailed)
        assertTrue(r.load().privacyAcknowledged)
        storage.failWrites = false
        r.update { it }
        assertFalse(r.lastSaveFailed)
    }

    // ---- damaged or foreign data ----------------------------------------------------------------

    @Test
    fun garbageStartsFreshInsteadOfCrashing() {
        for (text in listOf("not json", "[1,2]", "42", "", "   ", "{\"completed\":", "null")) {
            assertEquals(SetupState(), repo(MemoryStorage(text)).load(), "input: $text")
        }
    }

    @Test
    fun unknownStepAndFeatureNamesFromAnotherVersionAreIgnored() {
        val text = """{"version":9,"completed":["WELCOME","TELEPORT"],"current":"NOWHERE","features":["AI_ASSISTANT","HOLOGRAMS"],"modelChoice":"EVERYTHING","extra":1}"""
        val s = assertNotNull(SetupStateCodec.decode(text))
        assertEquals(setOf(SetupStep.WELCOME), s.completed)
        assertEquals(setOf(FeatureId.AI_ASSISTANT), s.features)
        assertNull(s.modelChoice)
    }

    @Test
    fun wrongTypedFieldsFallBackToSafeDefaults() {
        val text = """{"completed":"WELCOME","privacyAcknowledged":"yes","finished":1,"updatedAtMs":"later","features":[1,2,null]}"""
        val s = assertNotNull(SetupStateCodec.decode(text))
        assertTrue(s.completed.isEmpty() && !s.privacyAcknowledged && !s.finished && s.features.isEmpty())
        assertEquals(0L, s.updatedAtMs)
    }

    @Test
    fun aHandEditedFinishedFlagIsNotTrustedWithoutTheCompletedSteps() {
        val s = assertNotNull(SetupStateCodec.decode("""{"finished":true}"""))
        assertFalse(s.finished)
        assertEquals(SetupStep.WELCOME, SetupFlow.resumeStep(s))
    }

    @Test
    fun aStepCannotBeBothCompletedAndSkippedAfterLoading() {
        val s = assertNotNull(SetupStateCodec.decode("""{"completed":["PROVIDERS"],"skipped":["PROVIDERS","PERMISSIONS"]}"""))
        assertEquals(setOf(SetupStep.PERMISSIONS), s.skipped)
    }

    @Test
    fun everyFieldRoundTrips() {
        val original = SetupState(
            completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY, SetupStep.DEVICE),
            skipped = setOf(SetupStep.PROVIDERS),
            current = SetupStep.MODELS,
            privacyAcknowledged = true,
            modelChoice = ModelChoice.ADVANCED_ONLY,
            preset = ProfilePreset.LOCAL_AI,
            features = setOf(FeatureId.AI_ASSISTANT, FeatureId.DEVICE_CONTROL),
            finished = false,
            updatedAtMs = 123L,
        )
        assertEquals(original, SetupStateCodec.decode(SetupStateCodec.encode(original)))
    }

    @Test
    fun aFinishedStateRoundTripsAsFinished() {
        var s = SetupState()
        s = (SetupFlow.complete(s, SetupStep.WELCOME) as StepResult.Ok).state
        s = SetupFlow.withPrivacyAcknowledged(s)
        for (step in listOf(SetupStep.PRIVACY, SetupStep.DEVICE)) s = (SetupFlow.complete(s, step) as StepResult.Ok).state
        s = SetupFlow.withModelChoice(s, ModelChoice.SKIP)
        s = (SetupFlow.complete(s, SetupStep.MODELS) as StepResult.Ok).state
        s = SetupFlow.withFeatures(s, emptySet(), null)
        for (step in listOf(SetupStep.FEATURES, SetupStep.STORAGE)) s = (SetupFlow.complete(s, step) as StepResult.Ok).state
        s = (SetupFlow.finish(s) as StepResult.Ok).state
        assertTrue(SetupStateCodec.decode(SetupStateCodec.encode(s))!!.finished)
    }

    @Test
    fun theSavedTextContainsNoApiKeysOrPersonalData() {
        val text = SetupStateCodec.encode(SetupState(features = setOf(FeatureId.CLOUD_AI)))
        assertTrue(text.none { it == '@' } && "key" !in text.lowercase())
    }
}
