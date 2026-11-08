package com.jarvis.ai.setup

import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.models.ModelChoice

/** The wizard's screens in order. "Optional" steps may be skipped; the others must be completed. */
enum class SetupStep(val title: String, val optional: Boolean) {
    WELCOME("Welcome", false),
    PRIVACY("Privacy", false),
    DEVICE("Device analysis", false),
    MODELS("Local AI model", false),
    PROVIDERS("Cloud AI providers", true),
    FEATURES("Features", false),
    STORAGE("Storage review", false),
    PERMISSIONS("Permissions", true),
    SUMMARY("Summary", false),
}

/** Everything the wizard remembers. It is saved after every change so setup can be stopped and resumed. */
data class SetupState(
    val completed: Set<SetupStep> = emptySet(),
    val skipped: Set<SetupStep> = emptySet(),
    val current: SetupStep = SetupStep.WELCOME,
    val privacyAcknowledged: Boolean = false,
    val modelChoice: ModelChoice? = null,
    val preset: ProfilePreset? = null,
    val features: Set<FeatureId> = emptySet(),
    val finished: Boolean = false,
    val updatedAtMs: Long = 0L,
)

sealed interface StepResult {
    data class Ok(val state: SetupState) : StepResult
    data class Refused(val reason: String) : StepResult
}

/**
 * Pure rules for moving through setup. Nothing here touches Android or the disk, so the resume,
 * skip and invalidation behaviour is fully unit-tested.
 */
object SetupFlow {
    private val order = SetupStep.entries

    /** The step to show when the app opens: the first one not yet completed or skipped, or null when setup is finished. */
    fun resumeStep(state: SetupState): SetupStep? {
        if (state.finished) return null
        return order.firstOrNull { it !in state.completed && it !in state.skipped } ?: SetupStep.SUMMARY
    }

    /** Reopening the app lands on the resume step; completed steps are not repeated. */
    fun onAppOpened(state: SetupState): SetupState = resumeStep(state)?.let { state.copy(current = it) } ?: state

    fun complete(state: SetupState, step: SetupStep): StepResult {
        val blocker = when (step) {
            SetupStep.PRIVACY -> if (!state.privacyAcknowledged) "Please read and acknowledge the privacy information first." else null
            SetupStep.MODELS -> if (state.modelChoice == null) "Choose a model option, or choose to skip for now." else null
            else -> null
        }
        if (blocker != null) return StepResult.Refused(blocker)
        val missing = order.takeWhile { it != step }.filter { !it.optional && it !in state.completed }
        if (missing.isNotEmpty()) return StepResult.Refused("Finish \"${missing.first().title}\" first.")
        val next = order.getOrNull(order.indexOf(step) + 1) ?: step
        return StepResult.Ok(state.copy(completed = state.completed + step, skipped = state.skipped - step, current = next))
    }

    fun skip(state: SetupState, step: SetupStep): StepResult {
        if (!step.optional) return StepResult.Refused("\"${step.title}\" cannot be skipped.")
        val next = order.getOrNull(order.indexOf(step) + 1) ?: step
        return StepResult.Ok(state.copy(skipped = state.skipped + step, completed = state.completed - step, current = next))
    }

    fun goBack(state: SetupState): SetupState {
        val index = order.indexOf(state.current)
        return if (index <= 0) state else state.copy(current = order[index - 1])
    }

    /** You can go to any step up to the first unfinished one, never past it. */
    fun goTo(state: SetupState, step: SetupStep): StepResult {
        val limit = resumeStep(state) ?: SetupStep.SUMMARY
        return if (order.indexOf(step) <= order.indexOf(limit)) StepResult.Ok(state.copy(current = step))
        else StepResult.Refused("Finish \"${limit.title}\" first.")
    }

    // ---- edits to earlier choices: everything that depended on them has to be confirmed again -------------

    /** Unticking the box takes back the completed privacy step, so it has to be acknowledged again. */
    fun withPrivacyAcknowledged(state: SetupState, acknowledged: Boolean = true): SetupState =
        if (acknowledged) state.copy(privacyAcknowledged = true)
        else invalidate(state.copy(privacyAcknowledged = false), SetupStep.PRIVACY, SetupStep.SUMMARY)

    /**
     * Opens the wizard again after it was finished. Every earlier choice is kept; only the final summary has to be
     * confirmed again. The user can walk through the steps and change any of them.
     */
    fun reopen(state: SetupState): SetupState =
        state.copy(finished = false, completed = state.completed - SetupStep.SUMMARY, current = SetupStep.WELCOME)

    fun withModelChoice(state: SetupState, choice: ModelChoice): SetupState =
        invalidate(state.copy(modelChoice = choice), SetupStep.STORAGE, SetupStep.SUMMARY)

    fun withFeatures(state: SetupState, features: Set<FeatureId>, preset: ProfilePreset?): SetupState =
        invalidate(state.copy(features = features, preset = preset), SetupStep.STORAGE, SetupStep.PERMISSIONS, SetupStep.SUMMARY)

    private fun invalidate(state: SetupState, vararg steps: SetupStep): SetupState =
        state.copy(completed = state.completed - steps.toSet(), skipped = state.skipped - steps.toSet(), finished = false)

    /** Marks setup finished. Allowed only once every required step before the summary has been completed. */
    fun finish(state: SetupState): StepResult {
        val missing = order.filter { !it.optional && it != SetupStep.SUMMARY && it !in state.completed }
        if (missing.isNotEmpty()) return StepResult.Refused("Finish \"${missing.first().title}\" first.")
        return StepResult.Ok(state.copy(completed = state.completed + SetupStep.SUMMARY, current = SetupStep.SUMMARY, finished = true))
    }
}
