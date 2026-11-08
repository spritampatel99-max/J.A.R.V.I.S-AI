package com.jarvis.ai.android.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jarvis.ai.models.Amount
import com.jarvis.ai.models.ExpectedFile
import com.jarvis.ai.models.InstallPhase
import com.jarvis.ai.models.InstallProgress
import com.jarvis.ai.models.InstallResult
import com.jarvis.ai.models.InstallSource
import com.jarvis.ai.models.InstalledModel
import com.jarvis.ai.models.InstalledModels
import com.jarvis.ai.models.MetadataResult
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInstaller
import com.jarvis.ai.models.ModelMetadataClient
import com.jarvis.ai.models.ModelSpec
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModelPhase { IDLE, CHECKING, DOWNLOADING, COPYING, VERIFYING }

/** Everything the Models screen shows about one model. */
data class ModelState(
    val spec: ModelSpec,
    /** [Amount.Known] once the repository has been asked; an estimate before that. */
    val size: Amount,
    val installed: InstalledModel? = null,
    /** Bytes of an interrupted download that can be resumed. */
    val partialBytes: Long = 0L,
    val phase: ModelPhase = ModelPhase.IDLE,
    val progress: InstallProgress? = null,
    val message: String? = null,
    val isError: Boolean = false,
) {
    val busy: Boolean get() = phase != ModelPhase.IDLE
}

/**
 * Downloads, imports and removes model files, and keeps the state the Models screen shows.
 *
 * The work runs in this object's own scope, not in a screen's, so leaving the screen does not stop a download. While a
 * download runs, [ModelDownloadService] keeps the app alive in the foreground with a notification; Android would
 * otherwise stop a large download as soon as the app is no longer on screen.
 *
 * Nothing is reported as installed until the file passed the size check and, when Hugging Face publishes one, the
 * SHA-256 check. A download is only ever started by an explicit tap; this class never starts one on its own.
 */
class ModelManager(
    private val context: Context,
    private val metadata: ModelMetadataClient,
    private val installer: ModelInstaller,
    private val installed: InstalledModels,
    private val modelsDir: File,
    private val hfToken: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<ModelId, Job>()

    private val state = MutableStateFlow(initialStates())
    val states: StateFlow<Map<ModelId, ModelState>> = state.asStateFlow()

    private fun initialStates(): Map<ModelId, ModelState> =
        ModelCatalog.all.associate { spec ->
            spec.id to ModelState(
                spec = spec,
                size = ModelCatalog.initialInfo(spec.id).size,
                installed = installed.get(spec.id),
                partialBytes = installed.partialBytes(spec.id),
            )
        }

    /** Re-reads the disk, for example after the user returns to the screen. */
    fun refresh() {
        state.update { current ->
            current.mapValues { (id, s) ->
                if (s.busy) s else s.copy(installed = installed.get(id), partialBytes = installed.partialBytes(id))
            }
        }
    }

    // ---- download ------------------------------------------------------------------------------------------

    fun download(id: ModelId) {
        val spec = ModelCatalog.spec(id)
        val token = hfToken()?.takeIf { it.isNotBlank() }
        if (spec.gated && token == null) {
            change(id) {
                it.copy(
                    message = "${spec.displayName} is gated by its publisher. Add your Hugging Face access token below " +
                        "(the steps are shown), or import the file if you already have it.",
                    isError = true,
                )
            }
            return
        }
        if (!begin(id)) return
        change(id) { it.copy(phase = ModelPhase.CHECKING, progress = null, message = null, isError = false) }
        startService()

        track(id, scope.launch {
            try {
                val info = when (val found = metadata.lookup(spec, token)) {
                    is MetadataResult.Found -> found.info
                    is MetadataResult.FileMissing -> return@launch fail(id, "The file ${found.fileName} is not in the model repository any more.")
                    is MetadataResult.Failed -> return@launch fail(id, "Could not read the file details from Hugging Face. ${found.reason}")
                }
                change(id) { it.copy(size = info.size) }

                val result = installer.download(spec, ExpectedFile.from(info), modelsDir, token) { progress ->
                    change(id) { it.copy(phase = progress.phase.toModelPhase(), progress = progress) }
                }
                finish(id, spec, result, InstallSource.DOWNLOADED)
            } catch (e: CancellationException) {
                change(id) {
                    it.copy(
                        phase = ModelPhase.IDLE,
                        progress = null,
                        partialBytes = installed.partialBytes(id),
                        message = "Paused. What was downloaded is kept, and Resume continues from there.",
                        isError = false,
                    )
                }
                throw e
            } catch (e: Exception) {
                fail(id, "The download stopped unexpectedly (${e.javaClass.simpleName}).")
            }
        })
    }

    fun cancel(id: ModelId) {
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    /** Stops every running download. Used when Android ends the foreground service. */
    fun cancelAll() {
        synchronized(jobs) { jobs.values.toList() }.forEach { it.cancel() }
    }

    // ---- import --------------------------------------------------------------------------------------------

    /** Installs a model file the user already has, picked with the system file chooser. */
    fun import(id: ModelId, uri: Uri) {
        val spec = ModelCatalog.spec(id)
        if (!begin(id)) return
        change(id) { it.copy(phase = ModelPhase.CHECKING, progress = null, message = null, isError = false) }
        startService()

        track(id, scope.launch {
            try {
                // Best effort: when the repository can be reached, the file is checked against its real size and checksum.
                val expected = when (val found = metadata.lookup(spec, hfToken()?.takeIf { it.isNotBlank() })) {
                    is MetadataResult.Found -> ExpectedFile.from(found.info)
                    else -> ExpectedFile(null, null)
                }
                val input = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                } ?: return@launch fail(id, "That file could not be opened.")

                val result = installer.importFrom(spec, expected, modelsDir, input) { progress ->
                    change(id) { it.copy(phase = progress.phase.toModelPhase(), progress = progress) }
                }
                finish(id, spec, result, InstallSource.IMPORTED)
            } catch (e: CancellationException) {
                change(id) { it.copy(phase = ModelPhase.IDLE, progress = null, message = "Import cancelled.", isError = false) }
                throw e
            } catch (e: Exception) {
                fail(id, "The import stopped unexpectedly (${e.javaClass.simpleName}).")
            }
        })
    }

    // ---- delete --------------------------------------------------------------------------------------------

    /** Removes the installed file and any partly downloaded one. */
    fun delete(id: ModelId) {
        if (state.value[id]?.busy == true) return
        val gone = installed.delete(id)
        change(id) {
            it.copy(
                installed = installed.get(id),
                partialBytes = installed.partialBytes(id),
                message = if (gone) "Removed from this phone." else "Some files could not be removed.",
                isError = !gone,
            )
        }
    }

    // ---- internals -----------------------------------------------------------------------------------------

    private fun finish(id: ModelId, spec: ModelSpec, result: InstallResult, source: InstallSource) {
        when (result) {
            is InstallResult.Installed -> {
                val saved = installed.record(
                    InstalledModel(id, spec.fileName, result.bytes, result.checksumVerified, source, now()),
                )
                change(id) {
                    it.copy(
                        phase = ModelPhase.IDLE,
                        progress = null,
                        installed = installed.get(id),
                        partialBytes = 0L,
                        isError = !saved,
                        message = when {
                            !saved -> "The model is on the phone, but the app could not save its record. Open this screen again to check."
                            result.checksumVerified -> "Installed. Its checksum matches the one Hugging Face publishes."
                            else -> "Installed, but it could not be checked against an official checksum, so it is marked as not verified."
                        },
                    )
                }
            }
            is InstallResult.Failed -> change(id) {
                it.copy(
                    phase = ModelPhase.IDLE,
                    progress = null,
                    partialBytes = installed.partialBytes(id),
                    message = result.message,
                    isError = true,
                )
            }
        }
    }

    private fun fail(id: ModelId, message: String) {
        change(id) {
            it.copy(phase = ModelPhase.IDLE, progress = null, partialBytes = installed.partialBytes(id), message = message, isError = true)
        }
    }

    /** @return false when this model is already being downloaded, imported or checked. */
    private fun begin(id: ModelId): Boolean = state.value[id]?.busy != true

    private fun track(id: ModelId, job: Job) {
        synchronized(jobs) { jobs[id] = job }
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) } }
    }

    private fun change(id: ModelId, transform: (ModelState) -> ModelState) {
        state.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to transform(existing))
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        } catch (e: RuntimeException) {
            // Android refused to start the service (for example the app is not in the foreground). The download still
            // runs while the app is open; it just is not protected from being stopped in the background.
        }
    }

    private fun InstallPhase.toModelPhase(): ModelPhase = when (this) {
        InstallPhase.DOWNLOADING -> ModelPhase.DOWNLOADING
        InstallPhase.COPYING -> ModelPhase.COPYING
        InstallPhase.VERIFYING -> ModelPhase.VERIFYING
    }
}
