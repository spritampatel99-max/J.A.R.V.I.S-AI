package com.jarvis.ai.models

import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.core.common.ByteFormat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One open HTTP response for a model file. Closing it releases the connection. */
interface DownloadResponse : Closeable {
    val code: Int

    /** The first byte the body starts at when the server answered 206 (from Content-Range), else null. */
    val rangeStart: Long?

    val body: InputStream?
}

/** Opens a model file for download. The Android build backs this with OkHttp; tests use a fake. */
fun interface DownloadSource {
    /**
     * @param startAt byte offset to resume from; 0 asks for the whole file.
     * @throws IOException when the connection cannot be made or breaks.
     */
    suspend fun open(url: String, headers: Map<String, String>, startAt: Long): DownloadResponse
}

/** What the repository says the finished file must be. Either value may be unknown. */
data class ExpectedFile(val sizeBytes: Long?, val sha256: String?) {
    companion object {
        fun from(info: ModelInfo): ExpectedFile =
            ExpectedFile((info.size as? Amount.Known)?.bytes, info.sha256)
    }
}

enum class InstallPhase { DOWNLOADING, COPYING, VERIFYING }

data class InstallProgress(val phase: InstallPhase, val bytesDone: Long, val totalBytes: Long?) {
    /** 0..1, or null when the total is unknown. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDone.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

enum class FailureReason {
    NOT_ENOUGH_SPACE,
    ACCESS_DENIED,
    NOT_FOUND,
    NETWORK,
    STORAGE,
    INCOMPLETE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    SERVER,
}

sealed interface InstallResult {
    data class Installed(val file: File, val bytes: Long, val checksumVerified: Boolean) : InstallResult

    /** [message] is safe to show. When [canResume] is true the partly downloaded file was kept. */
    data class Failed(val reason: FailureReason, val message: String, val canResume: Boolean = false) : InstallResult
}

/**
 * Downloads (or imports) a model file into the app's private storage, safely:
 *
 *  - the data goes into `<file>.part`; the real file name only appears after every check passed,
 *    and then by an atomic rename, so a half-written model can never be mistaken for an installed one;
 *  - an interrupted download is resumed from where it stopped (HTTP Range) instead of starting again;
 *  - free space is checked first, using the size the repository reported;
 *  - the size must match, and the SHA-256 must match the repository's published value when there is one.
 *    A file that fails a check is deleted. A model with no published checksum is installed but reported
 *    as not verified;
 *  - an access token is sent only as an Authorization header to the model host. Redirects to a download
 *    host drop it (that is OkHttp's behaviour for a different host), and it is never logged.
 */
class ModelInstaller(
    private val source: DownloadSource,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {

    suspend fun download(
        spec: ModelSpec,
        expected: ExpectedFile,
        dir: File,
        accessToken: String?,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return@withContext InstallResult.Failed(FailureReason.STORAGE, "Could not create the model folder.")
        }
        val part = partFile(dir, spec)
        val total = expected.sizeBytes
        var resumeFrom = if (part.isFile) part.length() else 0L
        if (total != null && resumeFrom > total) {
            part.delete()
            resumeFrom = 0L
        }

        if (total == null || resumeFrom < total) {
            if (total != null) {
                val need = total - resumeFrom + SPACE_MARGIN
                val free = freeSpace(dir)
                if (free < need) {
                    return@withContext InstallResult.Failed(
                        FailureReason.NOT_ENOUGH_SPACE,
                        "Not enough free space: ${ByteFormat.format(need)} is needed and ${ByteFormat.format(free)} is free.",
                        canResume = resumeFrom > 0,
                    )
                }
            }
            transfer(spec, part, resumeFrom, total, accessToken, onProgress)?.let { return@withContext it }
        }
        finish(spec, expected, dir, part, resumable = true, onProgress = onProgress)
    }

    /** Installs a file the user already has (picked with the system file chooser). */
    suspend fun importFrom(
        spec: ModelSpec,
        expected: ExpectedFile,
        dir: File,
        input: InputStream,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return@withContext InstallResult.Failed(FailureReason.STORAGE, "Could not create the model folder.")
        }
        val part = partFile(dir, spec)
        val total = expected.sizeBytes
        if (total != null) {
            val free = freeSpace(dir)
            if (free < total + SPACE_MARGIN) {
                return@withContext InstallResult.Failed(
                    FailureReason.NOT_ENOUGH_SPACE,
                    "Not enough free space: ${ByteFormat.format(total + SPACE_MARGIN)} is needed and ${ByteFormat.format(free)} is free.",
                )
            }
        }
        val failure = input.use { copyStream(it, part, append = false, alreadyDone = 0L, total = total, phase = InstallPhase.COPYING, onProgress = onProgress) }
        if (failure != null) {
            part.delete()
            return@withContext failure
        }
        finish(spec, expected, dir, part, resumable = false, onProgress = onProgress)
    }

    // ---- network transfer ----------------------------------------------------------------------------

    /** @return null when the body was fully written to [part]; otherwise why not. */
    private suspend fun transfer(
        spec: ModelSpec,
        part: File,
        resumeFrom: Long,
        total: Long?,
        accessToken: String?,
        onProgress: (InstallProgress) -> Unit,
    ): InstallResult.Failed? {
        var from = resumeFrom
        var restarted = false
        val headers = if (accessToken.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${accessToken.trim()}")

        while (true) {
            val response = try {
                source.open(spec.downloadUrl, headers, from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return InstallResult.Failed(FailureReason.NETWORK, "Could not reach the download server. Check the connection and try again.", canResume = part.isFile)
            } catch (e: ProviderError) {
                return InstallResult.Failed(FailureReason.NETWORK, "Could not reach the download server. Check the connection and try again.", canResume = part.isFile)
            }

            var restart = false
            val failure: InstallResult.Failed? = response.use { r ->
                when (r.code) {
                    200 -> {
                        // The server sent the whole file (a fresh download, or it ignored the resume request).
                        copyStream(r.body, part, append = false, alreadyDone = 0L, total = total, phase = InstallPhase.DOWNLOADING, onProgress = onProgress)
                    }
                    206 -> {
                        if (r.rangeStart == from && from > 0) {
                            copyStream(r.body, part, append = true, alreadyDone = from, total = total, phase = InstallPhase.DOWNLOADING, onProgress = onProgress)
                        } else {
                            restart = true
                            null
                        }
                    }
                    416 -> {
                        restart = true
                        null
                    }
                    401, 403 -> InstallResult.Failed(
                        FailureReason.ACCESS_DENIED,
                        if (spec.gated) {
                            "Hugging Face refused access. ${spec.displayName} is gated: accept its licence on the model page " +
                                "and use an access token that has read permission."
                        } else {
                            "Hugging Face refused access (HTTP ${r.code})."
                        },
                        canResume = part.isFile,
                    )
                    404 -> InstallResult.Failed(FailureReason.NOT_FOUND, "The model file was not found on Hugging Face (HTTP 404). It may have been renamed.")
                    else -> InstallResult.Failed(FailureReason.SERVER, "The download server answered HTTP ${r.code}.", canResume = part.isFile)
                }
            }

            if (!restart) return failure
            if (restarted || from == 0L) {
                return InstallResult.Failed(FailureReason.SERVER, "The server did not accept the download request.")
            }
            // The saved part does not line up with what the server offers: start over, once.
            part.delete()
            from = 0L
            restarted = true
        }
    }

    // ---- shared copying and checking -----------------------------------------------------------------

    /** @return null on success, or the failure. Never leaves the caller guessing whether [input] was read. */
    private suspend fun copyStream(
        input: InputStream?,
        part: File,
        append: Boolean,
        alreadyDone: Long,
        total: Long?,
        phase: InstallPhase,
        onProgress: (InstallProgress) -> Unit,
    ): InstallResult.Failed? {
        if (input == null) return InstallResult.Failed(FailureReason.NETWORK, "The server sent an empty answer.", canResume = part.isFile)
        val out = try {
            FileOutputStream(part, append)
        } catch (e: IOException) {
            return InstallResult.Failed(FailureReason.STORAGE, "Could not write to the model folder.")
        }
        var done = alreadyDone
        var sinceReport = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            out.use { sink ->
                onProgress(InstallProgress(phase, done, total))
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        return InstallResult.Failed(FailureReason.NETWORK, "The connection was lost during the download.", canResume = true)
                    }
                    if (n < 0) break
                    try {
                        sink.write(buffer, 0, n)
                    } catch (e: IOException) {
                        return InstallResult.Failed(FailureReason.STORAGE, "Writing to storage failed. The phone may be out of space.", canResume = true)
                    }
                    done += n
                    sinceReport += n
                    if (sinceReport >= REPORT_EVERY) {
                        sinceReport = 0
                        onProgress(InstallProgress(phase, done, total))
                    }
                }
            }
        } finally {
            input.close()
        }
        onProgress(InstallProgress(phase, done, total))
        return null
    }

    private suspend fun finish(
        spec: ModelSpec,
        expected: ExpectedFile,
        dir: File,
        part: File,
        resumable: Boolean,
        onProgress: (InstallProgress) -> Unit,
    ): InstallResult {
        val actual = if (part.isFile) part.length() else 0L
        val total = expected.sizeBytes
        if (actual == 0L) {
            part.delete()
            return InstallResult.Failed(FailureReason.INCOMPLETE, "Nothing was received.")
        }
        if (total != null && actual != total) {
            return if (resumable && actual < total) {
                InstallResult.Failed(
                    FailureReason.INCOMPLETE,
                    "The download stopped at ${ByteFormat.format(actual)} of ${ByteFormat.format(total)}. It can be resumed.",
                    canResume = true,
                )
            } else {
                part.delete()
                InstallResult.Failed(
                    FailureReason.SIZE_MISMATCH,
                    "The file is ${ByteFormat.format(actual)} but ${spec.displayName} should be ${ByteFormat.format(total)}. " +
                        "It is incomplete or the wrong file, so it was not installed.",
                )
            }
        }

        var verified = false
        val sha = expected.sha256
        if (sha != null) {
            val computed = try {
                sha256Of(part, onProgress)
            } catch (e: IOException) {
                return InstallResult.Failed(FailureReason.STORAGE, "The downloaded file could not be read back to check it.")
            }
            if (!computed.equals(sha, ignoreCase = true)) {
                part.delete()
                return InstallResult.Failed(
                    FailureReason.CHECKSUM_MISMATCH,
                    "The file's checksum does not match the one Hugging Face publishes, so it was deleted and not installed.",
                )
            }
            verified = true
        }

        val target = File(dir, spec.fileName)
        try {
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            return InstallResult.Failed(FailureReason.STORAGE, "The file could not be moved into place.", canResume = false)
        }
        return InstallResult.Installed(target, actual, verified)
    }

    private suspend fun sha256Of(file: File, onProgress: (InstallProgress) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        var sinceReport = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                sinceReport += n
                if (sinceReport >= REPORT_EVERY * 4) {
                    sinceReport = 0
                    onProgress(InstallProgress(InstallPhase.VERIFYING, done, total))
                }
            }
        }
        onProgress(InstallProgress(InstallPhase.VERIFYING, total, total))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun partFile(dir: File, spec: ModelSpec) = File(dir, spec.fileName + PART_SUFFIX)

    companion object {
        const val PART_SUFFIX = ".part"

        /** Extra free space asked for beyond the file itself, for the checksum pass and the file system. */
        val SPACE_MARGIN: Long = 64 * Bytes.MIB
        private const val BUFFER_SIZE = 256 * 1024
        private const val REPORT_EVERY = 512L * 1024
    }
}
