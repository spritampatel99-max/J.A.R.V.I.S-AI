package com.jarvis.ai

import com.jarvis.ai.models.DownloadResponse
import com.jarvis.ai.models.DownloadSource
import com.jarvis.ai.models.ExpectedFile
import com.jarvis.ai.models.FailureReason
import com.jarvis.ai.models.InstallPhase
import com.jarvis.ai.models.InstallProgress
import com.jarvis.ai.models.InstallResult
import com.jarvis.ai.models.InstallSource
import com.jarvis.ai.models.InstalledModel
import com.jarvis.ai.models.InstalledModels
import com.jarvis.ai.models.InstalledModelsCodec
import com.jarvis.ai.models.InstalledModelsStorage
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInstaller
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Plays a model file the way an HTTP server would, including Range requests, and records what was asked. */
private class FakeSource(private val content: ByteArray) : DownloadSource {
    data class Request(val url: String, val headers: Map<String, String>, val startAt: Long)

    val requests = mutableListOf<Request>()

    /** Replace to change behaviour per request. The default honours Range like a normal server. */
    var respond: (Request) -> DownloadResponse = { honest(it) }

    var openError: IOException? = null

    override suspend fun open(url: String, headers: Map<String, String>, startAt: Long): DownloadResponse {
        val request = Request(url, headers, startAt)
        requests += request
        openError?.let { throw it }
        return respond(request)
    }

    fun honest(request: Request): DownloadResponse =
        if (request.startAt == 0L) {
            Resp(200, null, ByteArrayInputStream(content))
        } else {
            Resp(206, request.startAt, ByteArrayInputStream(content.copyOfRange(request.startAt.toInt(), content.size)))
        }

    class Resp(override val code: Int, override val rangeStart: Long?, override val body: InputStream?) : DownloadResponse {
        override fun close() {}
    }
}

/** A stream that delivers [good] bytes and then either ends normally (truncated) or throws. */
private class CutStream(private val data: ByteArray, private val good: Int, private val thenThrow: Boolean) : InputStream() {
    private var pos = 0

    override fun read(): Int = error("not used")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= good) {
            if (thenThrow) throw IOException("connection reset")
            return -1
        }
        val n = minOf(len, good - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

class ModelInstallerTest {

    private val dirs = mutableListOf<File>()
    private val content = Random(7).nextBytes(2_000_000)
    private val spec = ModelCatalog.gemma4E2b
    private val gated = ModelCatalog.gemma3OneB
    private val expected = ExpectedFile(content.size.toLong(), sha256(content))

    private fun newDir(): File = Files.createTempDirectory("models").toFile().also { dirs += it }

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun installer(source: DownloadSource, free: Long = Long.MAX_VALUE / 2) = ModelInstaller(source) { free }

    private fun File.part() = File(this, spec.fileName + ".part")

    // ---- happy paths ------------------------------------------------------------------------------------

    @Test
    fun aFullDownloadIsVerifiedAndMovedIntoPlace() = runBlocking {
        val dir = newDir()
        val source = FakeSource(content)
        val progress = mutableListOf<InstallProgress>()

        val result = installer(source).download(spec, expected, dir, null) { progress += it }

        val installed = assertIs<InstallResult.Installed>(result)
        assertTrue(installed.checksumVerified)
        assertEquals(content.size.toLong(), installed.bytes)
        assertContentEquals(content, File(dir, spec.fileName).readBytes())
        assertFalse(dir.part().exists(), "no .part file may be left behind")
        assertEquals(1, source.requests.size)
        assertEquals(0L, source.requests.single().startAt)
        assertEquals(spec.downloadUrl, source.requests.single().url)
        assertTrue(progress.any { it.phase == InstallPhase.DOWNLOADING && it.bytesDone == content.size.toLong() })
        assertTrue(progress.any { it.phase == InstallPhase.VERIFYING })
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun anInterruptedDownloadResumesFromTheSavedBytes() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(content.copyOfRange(0, 700_000))
        val source = FakeSource(content)

        val result = installer(source).download(spec, expected, dir, null)

        assertIs<InstallResult.Installed>(result)
        assertEquals(700_000L, source.requests.single().startAt)
        assertContentEquals(content, File(dir, spec.fileName).readBytes())
    }

    @Test
    fun aServerThatIgnoresTheResumeRequestStillGivesACorrectFile() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(content.copyOfRange(0, 300_000))
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(200, null, ByteArrayInputStream(content)) }

        val result = installer(source).download(spec, expected, dir, null)

        assertIs<InstallResult.Installed>(result)
        assertContentEquals(content, File(dir, spec.fileName).readBytes())
    }

    @Test
    fun aResumeAnswerForTheWrongOffsetRestartsFromZero() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(content.copyOfRange(0, 300_000))
        val source = FakeSource(content)
        source.respond = {
            if (it.startAt > 0) FakeSource.Resp(206, 5L, ByteArrayInputStream(content.copyOfRange(5, content.size))) else source.honest(it)
        }

        val result = installer(source).download(spec, expected, dir, null)

        assertIs<InstallResult.Installed>(result)
        assertEquals(listOf(300_000L, 0L), source.requests.map { it.startAt })
        assertContentEquals(content, File(dir, spec.fileName).readBytes())
    }

    @Test
    fun aRangeNotSatisfiableAnswerRestartsOnce() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(content.copyOfRange(0, 300_000))
        val source = FakeSource(content)
        source.respond = { if (it.startAt > 0) FakeSource.Resp(416, null, null) else source.honest(it) }

        val result = installer(source).download(spec, expected, dir, null)

        assertIs<InstallResult.Installed>(result)
        assertEquals(2, source.requests.size)
    }

    @Test
    fun aPartFileLargerThanTheModelIsDiscarded() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(ByteArray(content.size + 10))
        val source = FakeSource(content)

        val result = installer(source).download(spec, expected, dir, null)

        assertIs<InstallResult.Installed>(result)
        assertEquals(0L, source.requests.single().startAt)
    }

    @Test
    fun withNoPublishedChecksumTheModelInstallsButIsNotMarkedVerified() = runBlocking {
        val dir = newDir()
        val result = installer(FakeSource(content)).download(spec, ExpectedFile(content.size.toLong(), null), dir, null)
        assertFalse(assertIs<InstallResult.Installed>(result).checksumVerified)
    }

    // ---- refusals ----------------------------------------------------------------------------------------------

    @Test
    fun notEnoughSpaceStopsBeforeAnyDownloadStarts() = runBlocking {
        val dir = newDir()
        val source = FakeSource(content)

        val result = installer(source, free = 1_000_000L).download(spec, expected, dir, null)

        val failed = assertIs<InstallResult.Failed>(result)
        assertEquals(FailureReason.NOT_ENOUGH_SPACE, failed.reason)
        assertTrue(source.requests.isEmpty())
        assertTrue(failed.message.contains("MB"))
    }

    @Test
    fun aWrongChecksumDeletesTheFileAndInstallsNothing() = runBlocking {
        val dir = newDir()
        val bad = ExpectedFile(content.size.toLong(), "0".repeat(64))

        val result = installer(FakeSource(content)).download(spec, bad, dir, null)

        assertEquals(FailureReason.CHECKSUM_MISMATCH, assertIs<InstallResult.Failed>(result).reason)
        assertFalse(File(dir, spec.fileName).exists())
        assertFalse(dir.part().exists())
    }

    @Test
    fun aTruncatedDownloadKeepsThePartForResumingAndInstallsNothing() = runBlocking {
        val dir = newDir()
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(200, null, CutStream(content, 900_000, thenThrow = false)) }

        val result = installer(source).download(spec, expected, dir, null)

        val failed = assertIs<InstallResult.Failed>(result)
        assertEquals(FailureReason.INCOMPLETE, failed.reason)
        assertTrue(failed.canResume)
        assertEquals(900_000L, dir.part().length())
        assertFalse(File(dir, spec.fileName).exists())
    }

    @Test
    fun aBrokenConnectionIsANetworkFailureThatKeepsTheProgress() = runBlocking {
        val dir = newDir()
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(200, null, CutStream(content, 600_000, thenThrow = true)) }

        val failed = assertIs<InstallResult.Failed>(installer(source).download(spec, expected, dir, null))

        assertEquals(FailureReason.NETWORK, failed.reason)
        assertTrue(failed.canResume)
        assertEquals(600_000L, dir.part().length())
    }

    @Test
    fun anUnreachableServerIsANetworkFailure() = runBlocking {
        val source = FakeSource(content).apply { openError = IOException("no route") }
        val failed = assertIs<InstallResult.Failed>(installer(source).download(spec, expected, newDir(), null))
        assertEquals(FailureReason.NETWORK, failed.reason)
    }

    @Test
    fun aRefusedGatedModelExplainsWhatToDoAndSendsTheTokenOnlyAsAHeader() = runBlocking {
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(403, null, null) }

        val failed = assertIs<InstallResult.Failed>(
            installer(source).download(gated, ExpectedFile(content.size.toLong(), null), newDir(), "hf_secret_token"),
        )

        assertEquals(FailureReason.ACCESS_DENIED, failed.reason)
        assertTrue(failed.message.contains("licence"))
        assertFalse(failed.message.contains("hf_secret_token"), "the token must never appear in a message")
        assertEquals("Bearer hf_secret_token", source.requests.single().headers["Authorization"])
        assertFalse(source.requests.single().url.contains("hf_secret_token"))
    }

    @Test
    fun noTokenMeansNoAuthorizationHeader() = runBlocking {
        val source = FakeSource(content)
        installer(source).download(spec, expected, newDir(), "   ")
        assertFalse(source.requests.single().headers.containsKey("Authorization"))
    }

    @Test
    fun aMissingFileIsReportedAsNotFound() = runBlocking {
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(404, null, null) }
        assertEquals(FailureReason.NOT_FOUND, assertIs<InstallResult.Failed>(installer(source).download(spec, expected, newDir(), null)).reason)
    }

    @Test
    fun serverErrorsKeepWhatWasAlreadyDownloaded() = runBlocking {
        val dir = newDir()
        dir.part().writeBytes(content.copyOfRange(0, 100_000))
        val source = FakeSource(content)
        source.respond = { FakeSource.Resp(503, null, null) }

        val failed = assertIs<InstallResult.Failed>(installer(source).download(spec, expected, dir, null))

        assertEquals(FailureReason.SERVER, failed.reason)
        assertTrue(failed.canResume)
        assertEquals(100_000L, dir.part().length())
    }

    @Test
    fun cancellingKeepsThePartFileAndInstallsNothing() = runBlocking {
        val dir = newDir()
        val source = FakeSource(content)
        lateinit var job: Job
        // Lazy start: the job must be assigned before the installer can report any progress.
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            installer(source).download(spec, expected, dir, null) { progress ->
                if (progress.phase == InstallPhase.DOWNLOADING && progress.bytesDone >= 500_000) job.cancel()
            }
        }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(dir.part().exists())
        assertTrue(dir.part().length() < content.size)
        assertFalse(File(dir, spec.fileName).exists())
    }

    // ---- importing a file the user already has -----------------------------------------------------------------------

    @Test
    fun anImportedFileIsCheckedAgainstTheExpectedSizeAndChecksum() = runBlocking {
        val dir = newDir()

        val result = installer(FakeSource(content)).importFrom(spec, expected, dir, ByteArrayInputStream(content))

        val installed = assertIs<InstallResult.Installed>(result)
        assertTrue(installed.checksumVerified)
        assertContentEquals(content, File(dir, spec.fileName).readBytes())
    }

    @Test
    fun anImportedFileOfTheWrongSizeIsRejectedAndRemoved() = runBlocking {
        val dir = newDir()

        val result = installer(FakeSource(content)).importFrom(spec, expected, dir, ByteArrayInputStream(content.copyOf(1234)))

        assertEquals(FailureReason.SIZE_MISMATCH, assertIs<InstallResult.Failed>(result).reason)
        assertFalse(File(dir, spec.fileName).exists())
        assertFalse(dir.part().exists())
    }

    @Test
    fun anImportedFileWithTheRightSizeButWrongContentFailsTheChecksum() = runBlocking {
        val dir = newDir()
        val other = content.copyOf().also { it[10] = (it[10] + 1).toByte() }

        val result = installer(FakeSource(content)).importFrom(spec, expected, dir, ByteArrayInputStream(other))

        assertEquals(FailureReason.CHECKSUM_MISMATCH, assertIs<InstallResult.Failed>(result).reason)
        assertFalse(File(dir, spec.fileName).exists())
    }

    @Test
    fun anImportWithNoKnownSizeOrChecksumIsInstalledAsUnverified() = runBlocking {
        val result = installer(FakeSource(content)).importFrom(spec, ExpectedFile(null, null), newDir(), ByteArrayInputStream(content))
        assertFalse(assertIs<InstallResult.Installed>(result).checksumVerified)
    }

    // ---- the record of installed models ------------------------------------------------------------------------------------

    private class MemoryStorage(var text: String? = null, var failWrites: Boolean = false) : InstalledModelsStorage {
        override fun read(): String? = text
        override fun write(text: String): Boolean {
            if (failWrites) return false
            this.text = text
            return true
        }
    }

    private fun record(size: Long, id: ModelId = ModelId.GEMMA4_E2B, name: String = spec.fileName) =
        InstalledModel(id, name, size, checksumVerified = true, source = InstallSource.DOWNLOADED, installedAtMs = 5L)

    @Test
    fun aRecordCountsOnlyWhileItsFileIsStillThereWithTheSameSize() {
        val dir = newDir()
        val models = InstalledModels(MemoryStorage(), dir)
        File(dir, spec.fileName).writeBytes(ByteArray(100))
        assertTrue(models.record(record(100)))
        assertEquals(ModelId.GEMMA4_E2B, models.get(ModelId.GEMMA4_E2B)?.id)
        assertEquals(File(dir, spec.fileName), models.fileFor(ModelId.GEMMA4_E2B))

        File(dir, spec.fileName).writeBytes(ByteArray(99)) // changed behind our back
        assertNull(models.get(ModelId.GEMMA4_E2B))

        File(dir, spec.fileName).delete()
        assertTrue(models.all().isEmpty())
    }

    @Test
    fun recordingOneModelKeepsTheOthers() {
        val dir = newDir()
        val models = InstalledModels(MemoryStorage(), dir)
        File(dir, spec.fileName).writeBytes(ByteArray(10))
        File(dir, gated.fileName).writeBytes(ByteArray(20))
        models.record(record(10))
        models.record(record(20, ModelId.GEMMA3_1B, gated.fileName))
        assertEquals(setOf(ModelId.GEMMA4_E2B, ModelId.GEMMA3_1B), models.all().map { it.id }.toSet())
    }

    @Test
    fun deletingRemovesTheFileThePartAndTheRecord() {
        val dir = newDir()
        val models = InstalledModels(MemoryStorage(), dir)
        File(dir, spec.fileName).writeBytes(ByteArray(10))
        dir.part().writeBytes(ByteArray(3))
        models.record(record(10))

        assertTrue(models.delete(ModelId.GEMMA4_E2B))

        assertFalse(File(dir, spec.fileName).exists())
        assertFalse(dir.part().exists())
        assertNull(models.get(ModelId.GEMMA4_E2B))
    }

    @Test
    fun partialBytesReportsAResumableDownload() {
        val dir = newDir()
        val models = InstalledModels(MemoryStorage(), dir)
        assertEquals(0L, models.partialBytes(ModelId.GEMMA4_E2B))
        dir.part().writeBytes(ByteArray(77))
        assertEquals(77L, models.partialBytes(ModelId.GEMMA4_E2B))
    }

    @Test
    fun theCodecIsForgiving() {
        assertTrue(InstalledModelsCodec.decode(null).isEmpty())
        assertTrue(InstalledModelsCodec.decode("not json").isEmpty())
        assertTrue(InstalledModelsCodec.decode("""{"models":"x"}""").isEmpty())
        val mixed = """{"models":[
            {"id":"UNKNOWN_MODEL","fileName":"a","sizeBytes":5},
            {"id":"GEMMA4_E2B","fileName":"../escape","sizeBytes":5},
            {"id":"GEMMA4_E2B","fileName":"ok.litertlm","sizeBytes":-5},
            {"id":"GEMMA4_E2B","fileName":"ok.litertlm","sizeBytes":5,"source":"WHATEVER"}
        ]}"""
        val decoded = InstalledModelsCodec.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals(InstallSource.DOWNLOADED, decoded.single().source)
        assertFalse(decoded.single().checksumVerified)
    }

    @Test
    fun aFailedSaveIsReported() {
        val models = InstalledModels(MemoryStorage(failWrites = true), newDir())
        assertFalse(models.record(record(5)))
    }

    @Test
    fun cancellationExceptionIsNotSwallowedByTheInstaller() = runBlocking {
        val source = FakeSource(content)
        source.respond = { throw CancellationException("stop") }
        var cancelled = false
        try {
            installer(source).download(spec, expected, newDir(), null)
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
