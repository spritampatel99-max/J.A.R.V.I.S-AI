package com.jarvis.ai.android.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.jarvis.ai.android.models.ModelDownloadService
import com.jarvis.ai.knowledge.ClusterDecoder
import com.jarvis.ai.knowledge.KnowledgePackSpec
import com.jarvis.ai.knowledge.KnowledgePacks
import com.jarvis.ai.knowledge.LibraryCheck
import com.jarvis.ai.knowledge.LibraryChecker
import com.jarvis.ai.knowledge.OfflineEncyclopedia
import com.jarvis.ai.knowledge.ZimArchive
import com.jarvis.ai.knowledge.openZim
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.models.ExpectedFile
import com.jarvis.ai.models.InstallPhase
import com.jarvis.ai.models.InstallProgress
import com.jarvis.ai.models.InstallResult
import com.jarvis.ai.models.InstallTarget
import com.jarvis.ai.models.ModelInstaller
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
import kotlinx.coroutines.withContext

enum class LibraryPhase { IDLE, CHECKING, DOWNLOADING, COPYING, VERIFYING }

/** A library file on the phone. [problem] is set when it cannot be used; the person can delete it. */
data class InstalledLibrary(val fileName: String, val bytes: Long, val title: String?, val problem: String? = null)

data class LibraryState(
    val installed: InstalledLibrary? = null,
    val phase: LibraryPhase = LibraryPhase.IDLE,
    val busyWith: String? = null,
    val progress: InstallProgress? = null,
    /** Bytes of an interrupted download, by pack id, that Resume continues from. */
    val partialBytes: Map<String, Long> = emptyMap(),
    val message: String? = null,
    val isError: Boolean = false,
) {
    val busy: Boolean get() = phase != LibraryPhase.IDLE
}

/**
 * The offline library on this phone: one Wikipedia snapshot as a .zim file, downloaded from Kiwix or imported from a file the
 * person already has.
 *
 * It reuses the model installer, so a download goes to a `.part` file, resumes after a break, and only gets its real name by an
 * atomic rename. Kiwix publishes no checksum next to the file, so the check that decides "installed" is structural: the file must be
 * exactly as long as its own header says, and a block of content must decode. A file that fails is not used; a download that
 * stopped early is kept for Resume.
 *
 * Nothing downloads by itself: a tap starts it, and the work runs in this object's own scope so leaving the screen does not stop it.
 */
class KnowledgeLibrary(
    private val context: Context,
    private val installer: ModelInstaller,
    private val dir: File,
    private val decoder: ClusterDecoder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val mutable = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutable.asStateFlow()

    private class Open(val file: File, val archive: ZimArchive, val sourceName: String)

    @Volatile
    private var open: Open? = null

    init {
        scope.launch { reload() }
    }

    /** The encyclopedia to answer from, or null when no usable library is installed. */
    fun encyclopedia(): OfflineEncyclopedia? = open?.let { OfflineEncyclopedia(it.archive, it.sourceName) }

    /** Re-reads the folder, for example when the person returns to the screen. */
    fun refresh() {
        if (!mutable.value.busy) scope.launch { reload() }
    }

    // ---- download ------------------------------------------------------------------------------------------

    fun download(spec: KnowledgePackSpec) {
        if (mutable.value.busy) return
        val have = partial(spec.fileName)
        val need = spec.approxBytes - have + SPACE_MARGIN
        if (dir.apply { mkdirs() }.usableSpace < need) {
            mutable.update {
                it.copy(
                    message = "Not enough free space: about ${ByteFormat.format(need)} is needed and ${ByteFormat.format(dir.usableSpace)} is free.",
                    isError = true,
                )
            }
            return
        }
        begin(spec.displayName)
        job = scope.launch {
            try {
                val result = installer.download(spec, ExpectedFile(null, null), dir, accessToken = null) { progress -> progressed(progress) }
                finishInstall(result)
            } catch (e: CancellationException) {
                mutable.update {
                    it.copy(
                        phase = LibraryPhase.IDLE, progress = null, busyWith = null, partialBytes = partials(), isError = false,
                        message = "Paused. What was downloaded is kept, and Resume continues from there.",
                    )
                }
                throw e
            } catch (e: Exception) {
                fail("The download stopped unexpectedly (${e.javaClass.simpleName}).")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    // ---- import --------------------------------------------------------------------------------------------

    /** Installs a .zim file the person already has, picked with the system file chooser. */
    fun import(uri: Uri) {
        if (mutable.value.busy) return
        val info = describe(uri)
        val name = safeName(info.first ?: "library.zim")
        if (!KnowledgePacks.looksLikeLibraryFile(name)) {
            mutable.update { it.copy(message = "That is not a .zim file. Pick a file whose name ends in .zim.", isError = true) }
            return
        }
        val size = info.second
        if (size != null && dir.apply { mkdirs() }.usableSpace < size + SPACE_MARGIN) {
            mutable.update {
                it.copy(
                    message = "Not enough free space: ${ByteFormat.format(size + SPACE_MARGIN)} is needed and ${ByteFormat.format(dir.usableSpace)} is free.",
                    isError = true,
                )
            }
            return
        }
        begin(name)
        job = scope.launch {
            try {
                val input = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                } ?: return@launch fail("That file could not be opened.")
                val result = installer.importFrom(ImportTarget(name), ExpectedFile(size, null), dir, input) { progress -> progressed(progress) }
                finishInstall(result)
            } catch (e: CancellationException) {
                mutable.update { it.copy(phase = LibraryPhase.IDLE, progress = null, busyWith = null, message = "Import cancelled.", isError = false) }
                throw e
            } catch (e: Exception) {
                fail("The import stopped unexpectedly (${e.javaClass.simpleName}).")
            }
        }
    }

    // ---- delete --------------------------------------------------------------------------------------------

    fun delete() {
        if (mutable.value.busy) return
        scope.launch {
            closeOpen()
            val ok = withContext(Dispatchers.IO) {
                dir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".zim", true) || it.name.endsWith(".zim.part", true)) }.all { it.delete() }
            }
            reload(if (ok) "Removed from this phone." else "Some files could not be removed.", isError = !ok)
        }
    }

    // ---- internals -----------------------------------------------------------------------------------------

    private suspend fun finishInstall(result: InstallResult) {
        when (result) {
            is InstallResult.Failed -> mutable.update {
                it.copy(phase = LibraryPhase.IDLE, progress = null, busyWith = null, partialBytes = partials(), message = result.message, isError = true)
            }
            is InstallResult.Installed -> {
                mutable.update { it.copy(phase = LibraryPhase.CHECKING, progress = null) }
                when (val check = withContext(Dispatchers.IO) { LibraryChecker.check(result.file, decoder) }) {
                    is LibraryCheck.Ready -> {
                        val opened = withContext(Dispatchers.IO) { tryOpen(result.file) }
                        if (opened == null) {
                            result.file.delete()
                            fail("The library file was received but could not be opened, so it was removed.")
                            return
                        }
                        swapIn(opened)
                        reload("Installed. ${check.title ?: result.file.name} is ready to answer questions without internet.", isError = false)
                    }
                    is LibraryCheck.Incomplete -> {
                        // The server stopped sending early. Keep what arrived so Resume can continue.
                        val part = File(dir, result.file.name + ModelInstaller.PART_SUFFIX)
                        val kept = withContext(Dispatchers.IO) { result.file.renameTo(part) }
                        mutable.update {
                            it.copy(
                                phase = LibraryPhase.IDLE, progress = null, busyWith = null, partialBytes = partials(), isError = true,
                                message = "The download stopped at ${ByteFormat.format(check.have)} of ${ByteFormat.format(check.expected)}." +
                                    if (kept) " Tap Resume to continue." else " The partial file could not be kept.",
                            )
                        }
                    }
                    is LibraryCheck.Invalid -> {
                        result.file.delete()
                        fail("The file was received but is not a usable library: ${check.reason} It was removed.")
                    }
                }
            }
        }
    }

    /** Closes the old archive and deletes any other library file, so exactly one library is installed. */
    private suspend fun swapIn(new: Open) {
        val old = open
        open = new
        old?.archive?.close()
        withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zim", true) && it.name != new.file.name }
                .forEach { it.delete() }
        }
    }

    private suspend fun reload(message: String? = null, isError: Boolean = false) {
        withContext(Dispatchers.IO) {
            val files = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zim", true) }.sortedByDescending { it.lastModified() }
            var installed: InstalledLibrary? = null
            val current = open
            if (current != null && files.any { it.name == current.file.name && it.length() == current.file.length() }) {
                installed = InstalledLibrary(current.file.name, current.file.length(), current.archive.metadata("Title"))
            } else {
                closeOpen()
                for (file in files) {
                    when (val check = LibraryChecker.check(file, decoder)) {
                        is LibraryCheck.Ready -> {
                            val opened = tryOpen(file)
                            if (opened != null) {
                                open = opened
                                installed = InstalledLibrary(file.name, file.length(), check.title)
                                break
                            }
                            installed = InstalledLibrary(file.name, file.length(), check.title, "It could not be opened.")
                        }
                        is LibraryCheck.Incomplete -> installed = InstalledLibrary(file.name, file.length(), null, "Only ${ByteFormat.format(check.have)} of ${ByteFormat.format(check.expected)} arrived.")
                        is LibraryCheck.Invalid -> installed = InstalledLibrary(file.name, file.length(), null, check.reason)
                    }
                }
            }
            mutable.update {
                it.copy(
                    installed = installed, partialBytes = partials(), phase = LibraryPhase.IDLE, progress = null, busyWith = null,
                    message = message ?: it.message,
                    isError = if (message != null) isError else it.isError,
                )
            }
        }
    }

    private fun tryOpen(file: File): Open? = try {
        val archive = openZim(file, decoder)
        Open(file, archive, sourceNameFor(file.name, archive))
    } catch (e: Exception) {
        null
    }

    private fun sourceNameFor(fileName: String, archive: ZimArchive): String {
        val pack = KnowledgePacks.all.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
        if (pack != null) return pack.sourceName
        val title = try {
            archive.metadata("Title")
        } catch (e: Exception) {
            null
        }
        return "${title ?: fileName} (offline copy)"
    }

    private fun closeOpen() {
        val old = open
        open = null
        try {
            old?.archive?.close()
        } catch (e: IOException) {
            // Nothing more can be done with a file that will not close.
        }
    }

    private fun begin(name: String) {
        mutable.update { it.copy(phase = LibraryPhase.CHECKING, busyWith = name, progress = null, message = null, isError = false) }
        try {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        } catch (e: RuntimeException) {
            // Android refused to start the service. The work still runs while the app is open.
        }
    }

    private fun progressed(progress: InstallProgress) {
        mutable.update {
            it.copy(
                progress = progress,
                phase = when (progress.phase) {
                    InstallPhase.DOWNLOADING -> LibraryPhase.DOWNLOADING
                    InstallPhase.COPYING -> LibraryPhase.COPYING
                    InstallPhase.VERIFYING -> LibraryPhase.VERIFYING
                },
            )
        }
    }

    private fun fail(message: String) {
        mutable.update { it.copy(phase = LibraryPhase.IDLE, progress = null, busyWith = null, partialBytes = partials(), message = message, isError = true) }
    }

    private fun partial(fileName: String): Long = File(dir, fileName + ModelInstaller.PART_SUFFIX).let { if (it.isFile) it.length() else 0L }

    private fun partials(): Map<String, Long> = KnowledgePacks.all.associate { it.id to partial(it.fileName) }.filterValues { it > 0 }

    /** The file's display name and size as the file chooser reports them. */
    private fun describe(uri: Uri): Pair<String?, Long?> = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                name to size
            } else {
                null to null
            }
        } ?: (null to null)
    } catch (e: Exception) {
        null to null
    }

    private fun safeName(raw: String): String = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100)

    private class ImportTarget(override val fileName: String) : InstallTarget {
        override val displayName: String get() = fileName
        override val downloadUrl: String get() = ""
        override val gated: Boolean get() = false
        override val hostName: String get() = "the file"
        override val noun: String get() = "library file"
    }

    private companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
    }
}
