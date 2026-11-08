package com.jarvis.ai

import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.setup.SetupFlow
import com.jarvis.ai.setup.SetupState
import com.jarvis.ai.setup.SetupStep
import com.jarvis.ai.setup.StepResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupFlowTest {

    private fun ok(r: StepResult): SetupState = assertIs<StepResult.Ok>(r).state
    private fun refused(r: StepResult): String = assertIs<StepResult.Refused>(r).reason

    /** Walks a fresh state through every required step, the way a user would. */
    private fun everythingDone(): SetupState {
        var s = SetupState()
        s = ok(SetupFlow.complete(s, SetupStep.WELCOME))
        s = SetupFlow.withPrivacyAcknowledged(s)
        s = ok(SetupFlow.complete(s, SetupStep.PRIVACY))
        s = ok(SetupFlow.complete(s, SetupStep.DEVICE))
        s = SetupFlow.withModelChoice(s, ModelChoice.SKIP)
        s = ok(SetupFlow.complete(s, SetupStep.MODELS))
        s = ok(SetupFlow.skip(s, SetupStep.PROVIDERS))
        s = SetupFlow.withFeatures(s, setOf(FeatureId.AI_ASSISTANT), ProfilePreset.ESSENTIAL)
        s = ok(SetupFlow.complete(s, SetupStep.FEATURES))
        s = ok(SetupFlow.complete(s, SetupStep.STORAGE))
        s = ok(SetupFlow.complete(s, SetupStep.PERMISSIONS))
        return s
    }

    @Test
    fun aFreshInstallStartsAtTheWelcomeScreen() {
        assertEquals(SetupStep.WELCOME, SetupFlow.resumeStep(SetupState()))
    }

    @Test
    fun completingAStepMovesToTheNextOne() {
        val s = ok(SetupFlow.complete(SetupState(), SetupStep.WELCOME))
        assertEquals(SetupStep.PRIVACY, s.current)
        assertTrue(SetupStep.WELCOME in s.completed)
    }

    @Test
    fun privacyCannotBeCompletedUntilItIsAcknowledged() {
        val welcome = ok(SetupFlow.complete(SetupState(), SetupStep.WELCOME))
        assertTrue("privacy" in refused(SetupFlow.complete(welcome, SetupStep.PRIVACY)).lowercase())
        val acked = SetupFlow.withPrivacyAcknowledged(welcome)
        assertTrue(SetupStep.PRIVACY in ok(SetupFlow.complete(acked, SetupStep.PRIVACY)).completed)
    }

    @Test
    fun theModelStepNeedsAChoiceButSkipIsAValidChoice() {
        var s = SetupState(completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY, SetupStep.DEVICE), privacyAcknowledged = true)
        assertTrue("choose" in refused(SetupFlow.complete(s, SetupStep.MODELS)).lowercase())
        s = SetupFlow.withModelChoice(s, ModelChoice.SKIP)
        assertTrue(SetupStep.MODELS in ok(SetupFlow.complete(s, SetupStep.MODELS)).completed)
    }

    @Test
    fun aStepCannotBeCompletedBeforeTheRequiredOnesBeforeIt() {
        val reason = refused(SetupFlow.complete(SetupState(), SetupStep.FEATURES))
        assertTrue("Welcome" in reason)
    }

    @Test
    fun onlyOptionalStepsCanBeSkipped() {
        assertIs<StepResult.Refused>(SetupFlow.skip(SetupState(), SetupStep.DEVICE))
        assertIs<StepResult.Refused>(SetupFlow.skip(SetupState(), SetupStep.MODELS))
        val s = ok(SetupFlow.skip(SetupState(), SetupStep.PROVIDERS))
        assertTrue(SetupStep.PROVIDERS in s.skipped)
    }

    @Test
    fun aSkippedOptionalStepDoesNotBlockLaterSteps() {
        var s = everythingDone()
        assertTrue(SetupStep.PROVIDERS in s.skipped)
        s = ok(SetupFlow.finish(s))
        assertTrue(s.finished)
    }

    @Test
    fun completingAStepRemovesAnEarlierSkipMark() {
        val skipped = ok(SetupFlow.skip(SetupState(), SetupStep.PROVIDERS))
        val s = SetupState(completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY, SetupStep.DEVICE, SetupStep.MODELS), skipped = skipped.skipped, privacyAcknowledged = true)
        val done = ok(SetupFlow.complete(s, SetupStep.PROVIDERS))
        assertFalse(SetupStep.PROVIDERS in done.skipped)
        assertTrue(SetupStep.PROVIDERS in done.completed)
    }

    @Test
    fun reopeningTheAppResumesAtTheFirstUnfinishedStepNotAtTheStart() {
        val s = SetupState(completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY), privacyAcknowledged = true, current = SetupStep.WELCOME)
        assertEquals(SetupStep.DEVICE, SetupFlow.resumeStep(s))
        assertEquals(SetupStep.DEVICE, SetupFlow.onAppOpened(s).current)
    }

    @Test
    fun resumingSkipsOverSkippedSteps() {
        val s = SetupState(
            completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY, SetupStep.DEVICE, SetupStep.MODELS),
            skipped = setOf(SetupStep.PROVIDERS),
        )
        assertEquals(SetupStep.FEATURES, SetupFlow.resumeStep(s))
    }

    @Test
    fun finishedSetupHasNothingToResume() {
        val s = ok(SetupFlow.finish(everythingDone()))
        assertNull(SetupFlow.resumeStep(s))
        assertEquals(s, SetupFlow.onAppOpened(s))
    }

    @Test
    fun youCanGoBackButNeverPastTheFirstUnfinishedStep() {
        val s = SetupState(completed = setOf(SetupStep.WELCOME, SetupStep.PRIVACY), current = SetupStep.DEVICE)
        assertEquals(SetupStep.PRIVACY, SetupFlow.goBack(s).current)
        assertEquals(SetupStep.WELCOME, ok(SetupFlow.goTo(s, SetupStep.WELCOME)).current)
        assertEquals(SetupStep.DEVICE, ok(SetupFlow.goTo(s, SetupStep.DEVICE)).current)
        assertTrue("Device analysis" in refused(SetupFlow.goTo(s, SetupStep.FEATURES)))
        assertEquals(SetupStep.WELCOME, SetupFlow.goBack(SetupState()).current)
    }

    @Test
    fun changingTheModelChoiceMakesStorageAndSummaryBeConfirmedAgain() {
        val s = ok(SetupFlow.finish(everythingDone()))
        val changed = SetupFlow.withModelChoice(s, ModelChoice.BOTH)
        assertFalse(changed.finished)
        assertFalse(SetupStep.STORAGE in changed.completed)
        assertFalse(SetupStep.SUMMARY in changed.completed)
        assertTrue(SetupStep.FEATURES in changed.completed && SetupStep.MODELS in changed.completed)
        assertEquals(SetupStep.STORAGE, SetupFlow.resumeStep(changed))
    }

    @Test
    fun changingFeaturesAlsoResetsPermissionsAndStorage() {
        val s = ok(SetupFlow.finish(everythingDone()))
        val changed = SetupFlow.withFeatures(s, setOf(FeatureId.CLOUD_AI), null)
        assertEquals(setOf(FeatureId.CLOUD_AI), changed.features)
        assertNull(changed.preset)
        assertTrue(SetupStep.STORAGE !in changed.completed && SetupStep.PERMISSIONS !in changed.completed && !changed.finished)
        assertTrue(SetupStep.MODELS in changed.completed)
    }

    @Test
    fun aSkippedPermissionsStepIsAskedAgainAfterFeaturesChange() {
        val s = SetupFlow.withFeatures(ok(SetupFlow.skip(everythingDone(), SetupStep.PERMISSIONS)), setOf(FeatureId.CLOUD_AI), null)
        assertFalse(SetupStep.PERMISSIONS in s.skipped)
    }

    @Test
    fun finishIsRefusedWhileARequiredStepIsMissing() {
        val s = everythingDone().copy(completed = everythingDone().completed - SetupStep.STORAGE)
        assertTrue("Storage review" in refused(SetupFlow.finish(s)))
        assertIs<StepResult.Refused>(SetupFlow.finish(SetupState()))
    }

    @Test
    fun finishingSetsTheSummaryStepAndTheFlag() {
        val s = ok(SetupFlow.finish(everythingDone()))
        assertTrue(s.finished && SetupStep.SUMMARY in s.completed)
        assertEquals(SetupStep.SUMMARY, s.current)
    }

    @Test
    fun noRequiredStepCanBeBypassedByAnyCallOrder() {
        // Try to skip or complete steps out of order: every attempt that would leave a required step undone is refused.
        val fresh = SetupState()
        for (step in SetupStep.entries.drop(1)) {
            if (!step.optional) assertIs<StepResult.Refused>(SetupFlow.complete(fresh, step), "completed $step on a fresh state")
        }
        assertIs<StepResult.Refused>(SetupFlow.finish(fresh))
    }

    @Test
    fun untickingPrivacyTakesBackTheCompletedStep() {
        val done = everythingDone()
        val s = SetupFlow.withPrivacyAcknowledged(done, acknowledged = false)
        assertFalse(s.privacyAcknowledged)
        assertFalse(SetupStep.PRIVACY in s.completed)
        assertEquals(SetupStep.PRIVACY, SetupFlow.resumeStep(s))
        assertTrue("privacy" in refused(SetupFlow.complete(s, SetupStep.PRIVACY)).lowercase())
    }

    @Test
    fun reopeningAFinishedSetupKeepsEveryChoice() {
        val finished = ok(SetupFlow.finish(everythingDone()))
        val again = SetupFlow.reopen(finished)
        assertFalse(again.finished)
        assertEquals(SetupStep.WELCOME, again.current)
        assertEquals(finished.features, again.features)
        assertEquals(finished.modelChoice, again.modelChoice)
        assertEquals(finished.preset, again.preset)
        assertTrue(finished.completed - SetupStep.SUMMARY == again.completed)
        assertEquals(SetupStep.SUMMARY, SetupFlow.resumeStep(again))
    }

    @Test
    fun aReopenedSetupCanBeWalkedThroughAndFinishedAgain() {
        var s = SetupFlow.reopen(ok(SetupFlow.finish(everythingDone())))
        assertEquals(SetupStep.WELCOME, s.current)
        for (step in listOf(SetupStep.WELCOME, SetupStep.PRIVACY, SetupStep.DEVICE, SetupStep.MODELS, SetupStep.FEATURES, SetupStep.STORAGE)) {
            s = ok(SetupFlow.complete(s, step))
        }
        assertTrue(ok(SetupFlow.finish(s)).finished)
    }

    @Test
    fun editingWhileReopenedStillInvalidatesWhatDependsOnIt() {
        val again = SetupFlow.reopen(ok(SetupFlow.finish(everythingDone())))
        val changed = SetupFlow.withModelChoice(again, ModelChoice.BOTH)
        assertFalse(SetupStep.STORAGE in changed.completed)
        assertIs<StepResult.Refused>(SetupFlow.finish(changed))
    }
}
