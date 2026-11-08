package com.jarvis.ai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.AppContainer
import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.features.FeatureCatalog
import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.FeatureResolver
import com.jarvis.ai.features.FeatureSelection
import com.jarvis.ai.features.ProfilePreset
import com.jarvis.ai.features.Resolution
import com.jarvis.ai.models.MetadataResult
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInfo
import com.jarvis.ai.models.ModelRecommendation
import com.jarvis.ai.setup.SetupFlow
import com.jarvis.ai.setup.SetupState
import com.jarvis.ai.setup.SetupStep
import com.jarvis.ai.setup.StepResult
import com.jarvis.ai.setup.StorageReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the exact-size lookup for one model currently shows. */
sealed interface SizeCheck {
    data object Checking : SizeCheck
    data object Verified : SizeCheck
    data class Failed(val reason: String) : SizeCheck
}

data class SetupUiState(
    val setup: SetupState,
    val appVersion: String,
    val device: DeviceProfile? = null,
    val scanning: Boolean = false,
    val models: Map<ModelId, ModelInfo> = emptyMap(),
    val sizeChecks: Map<ModelId, SizeCheck> = emptyMap(),
    val recommendation: ModelRecommendation? = null,
    val resolution: Resolution = FeatureResolver.resolve(emptySet()),
    val storage: StorageReview? = null,
    /** One short message about the last action, for example why a feature could not be selected. */
    val notice: String? = null,
    val saveFailed: Boolean = false,
) {
    val step: SetupStep get() = setup.current
    val completedCount: Int get() = SetupStep.entries.count { it in setup.completed || it in setup.skipped }
}

class SetupViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.setup
    private val state = MutableStateFlow(
        SetupUiState(
            setup = repository.load(),
            appVersion = container.appVersion,
            models = ModelId.entries.associateWith { ModelCatalog.initialInfo(it) },
        ),
    )
    val ui: StateFlow<SetupUiState> = state.asStateFlow()

    init {
        publish(repository.load())
        scan()
    }

    // ---- device analysis (local only, no network) ------------------------------------------------

    fun scan() {
        if (state.value.scanning) return
        state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val profile = try {
                container.deviceAnalyzer.analyze()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(scanning = false, notice = "The device scan failed (${e.javaClass.simpleName}). You can try again.") }
                return@launch
            }
            state.update { it.copy(device = profile, scanning = false) }
            recompute()
        }
    }

    // ---- navigation ---------------------------------------------------------------------------

    fun complete(step: SetupStep) = applyRule { SetupFlow.complete(it, step) }

    fun skip(step: SetupStep) = applyRule { SetupFlow.skip(it, step) }

    fun back() {
        publish(repository.update { SetupFlow.goBack(it) })
    }

    fun finish() = applyRule { SetupFlow.finish(it) }

    /** Lets the user walk through the wizard again with every earlier choice kept. */
    fun reopen() {
        publish(repository.update { SetupFlow.reopen(it) })
    }

    private fun applyRule(rule: (SetupState) -> StepResult) {
        when (val result = repository.apply(rule)) {
            is StepResult.Ok -> publish(result.state, notice = null)
            is StepResult.Refused -> state.update { it.copy(notice = result.reason) }
        }
    }

    fun dismissNotice() {
        state.update { it.copy(notice = null) }
    }

    // ---- privacy ---------------------------------------------------------------------------------

    fun setPrivacyAcknowledged(acknowledged: Boolean) {
        publish(repository.update { SetupFlow.withPrivacyAcknowledged(it, acknowledged) })
    }

    // ---- model choice --------------------------------------------------------------------------

    fun chooseModel(choice: ModelChoice) {
        publish(repository.update { SetupFlow.withModelChoice(it, choice) })
    }

    /**
     * Reads the real file size and checksum from the model repository. Only runs when the user asks for it,
     * because it contacts huggingface.co (which sees the phone's network address, nothing else).
     */
    fun checkExactSizes() {
        if (state.value.sizeChecks.values.any { it is SizeCheck.Checking }) return
        state.update { s -> s.copy(sizeChecks = ModelId.entries.associateWith { SizeCheck.Checking }, notice = null) }
        viewModelScope.launch {
            for (id in ModelId.entries) {
                val spec = ModelCatalog.spec(id)
                val outcome = container.modelMetadata.lookup(spec)
                state.update { s ->
                    when (outcome) {
                        is MetadataResult.Found -> s.copy(
                            models = s.models + (id to outcome.info),
                            sizeChecks = s.sizeChecks + (id to SizeCheck.Verified),
                        )
                        is MetadataResult.FileMissing -> s.copy(
                            sizeChecks = s.sizeChecks + (id to SizeCheck.Failed("${outcome.fileName} is not in the repository listing, so the estimate stays.")),
                        )
                        is MetadataResult.Failed -> s.copy(sizeChecks = s.sizeChecks + (id to SizeCheck.Failed(outcome.reason)))
                    }
                }
            }
            recompute()
        }
    }

    // ---- features ----------------------------------------------------------------------------------

    fun applyPreset(preset: ProfilePreset) {
        val (selection, notBuilt) = FeatureSelection.fromPreset(preset)
        publish(
            repository.update { SetupFlow.withFeatures(it, selection.chosen, preset) },
            notice = if (notBuilt.isEmpty()) null else
                "${preset.title} also includes ${notBuilt.size} feature(s) that are not built yet: " +
                    notBuilt.joinToString { FeatureCatalog.feature(it).title } + ".",
        )
    }

    fun setFeature(id: FeatureId, on: Boolean) {
        val current = FeatureSelection(state.value.setup.features)
        if (on) {
            val change = current.select(id)
            if (change.refused != null) {
                state.update { it.copy(notice = change.refused) }
                return
            }
            publish(repository.update { SetupFlow.withFeatures(it, change.selection.chosen, null) }, notice = null)
        } else {
            val change = current.deselect(id)
            val note = if (change.alsoRemoved.isEmpty()) null else
                "Also turned off because they need ${FeatureCatalog.feature(id).title}: " +
                    change.alsoRemoved.joinToString { FeatureCatalog.feature(it).title } + "."
            publish(repository.update { SetupFlow.withFeatures(it, change.selection.chosen, null) }, notice = note)
        }
    }

    fun selectAllFeatures() {
        publish(repository.update { SetupFlow.withFeatures(it, FeatureSelection().selectAll().chosen, null) }, notice = null)
    }

    fun deselectAllFeatures() {
        publish(repository.update { SetupFlow.withFeatures(it, emptySet(), null) }, notice = null)
    }

    // ---- derived state ---------------------------------------------------------------------------

    private fun publish(setup: SetupState, notice: String? = state.value.notice) {
        state.update { it.copy(setup = setup, notice = notice, saveFailed = repository.lastSaveFailed) }
        recompute()
    }

    private fun recompute() {
        state.update { s ->
            val resolution = FeatureResolver.resolve(s.setup.features)
            val device = s.device
            val infos = ModelId.entries.mapNotNull { s.models[it] }
            val advisor = container.modelAdvisor
            val recommendation = device?.let { advisor.recommend(it, infos) }
            val storage = device?.let {
                container.storageCalculator.review(
                    resolution = resolution,
                    modelChoice = s.setup.modelChoice,
                    models = s.models,
                    profile = it,
                    baseAppBytes = container.baseAppSize,
                    modelRamEstimates = recommendation?.assessments
                        ?.mapNotNull { a -> a.ramEstimateBytes?.let { a.info.spec.id to it } }?.toMap().orEmpty(),
                )
            }
            s.copy(resolution = resolution, recommendation = recommendation, storage = storage)
        }
    }
}

class SetupViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SetupViewModel::class.java)) { "Unknown ViewModel ${modelClass.name}" }
        return SetupViewModel(container) as T
    }
}
