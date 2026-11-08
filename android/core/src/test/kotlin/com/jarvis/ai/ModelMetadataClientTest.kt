package com.jarvis.ai

import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.models.Amount
import com.jarvis.ai.models.MetadataResult
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelMetadataClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * The fixtures follow the shape of the Hugging Face file listing as reported by a web fetch on 2026-11-07
 * (a JSON array; each file has type, oid, size, path and, for LFS files, an "lfs" object with oid and size).
 * They are written by hand, not captured from a live response, and the checksum below is made up.
 */
class ModelMetadataClientTest {

    private val sha = "0123456789abcdef".repeat(4)
    private val spec = ModelCatalog.spec(ModelId.GEMMA3_1B)

    private fun listing(file: String = spec.fileName, size: Long = 584_417_280L, lfsOid: String? = sha, lfsSize: Long? = size) = """
        [
          {"type":"file","oid":"a1","size":4273,"path":".gitattributes"},
          {"type":"file","oid":"b2","size":$size,"path":"$file"${
        if (lfsOid != null) ""","lfs":{"oid":"$lfsOid","size":$lfsSize,"pointerSize":135}""" else ""
    }},
          {"type":"file","oid":"c3","size":20876,"path":"README.md"}
        ]
    """.trimIndent()

    private fun client(configure: FakeTransport.() -> Unit): Pair<ModelMetadataClient, FakeTransport> {
        val t = FakeTransport().apply(configure)
        return ModelMetadataClient(t) to t
    }

    @Test
    fun readsTheExactSizeAndChecksumOfTheNamedFile() = runTest {
        val (c, t) = client { reply(200, listing()) }
        val found = assertIs<MetadataResult.Found>(c.lookup(spec))
        assertEquals(Amount.Known(584_417_280L), found.info.size)
        assertEquals(sha, found.info.sha256)
        assertTrue(found.info.verified)
        assertEquals(1, t.gets.size)
        assertEquals("https://huggingface.co/api/models/litert-community/Gemma3-1B-IT/tree/main", t.gets.single().first)
        assertTrue(t.posts.isEmpty())
    }

    @Test
    fun sendsNoCredentialsAndNoPersonalData() = runTest {
        val (c, t) = client { reply(200, listing()) }
        c.lookup(spec)
        assertEquals(setOf("Accept"), t.gets.single().second.keys)
    }

    @Test
    fun prefersTheLfsSizeOverThePointerEntry() = runTest {
        val (c, _) = client { reply(200, listing(size = 100, lfsSize = 584_417_280L)) }
        val found = assertIs<MetadataResult.Found>(c.lookup(spec))
        assertEquals(Amount.Known(584_417_280L), found.info.size)
    }

    @Test
    fun aFileWithoutLfsHasASizeButNoChecksum() = runTest {
        val (c, _) = client { reply(200, listing(lfsOid = null)) }
        val found = assertIs<MetadataResult.Found>(c.lookup(spec))
        assertNull(found.info.sha256)
    }

    @Test
    fun aMalformedChecksumIsIgnoredRatherThanTrusted() = runTest {
        val (c, _) = client { reply(200, listing(lfsOid = "not-a-sha")) }
        val found = assertIs<MetadataResult.Found>(c.lookup(spec))
        assertNull(found.info.sha256)
    }

    @Test
    fun anUppercaseChecksumIsNormalised() = runTest {
        val (c, _) = client { reply(200, listing(lfsOid = sha.uppercase())) }
        assertEquals(sha, assertIs<MetadataResult.Found>(c.lookup(spec)).info.sha256)
    }

    @Test
    fun reportsWhenTheFileIsNotInTheListing() = runTest {
        val (c, _) = client { reply(200, listing(file = "something-else.litertlm")) }
        assertEquals(MetadataResult.FileMissing(spec.fileName), c.lookup(spec))
    }

    @Test
    fun aListingWithoutAUsableSizeFails() = runTest {
        val (c, _) = client { reply(200, """[{"type":"file","path":"${spec.fileName}"}]""") }
        assertIs<MetadataResult.Failed>(c.lookup(spec))
        val (c2, _) = client { reply(200, listing(size = 0, lfsSize = 0)) }
        assertIs<MetadataResult.Failed>(c2.lookup(spec))
    }

    @Test
    fun httpErrorsBecomeReadableFailures() = runTest {
        for ((code, word) in listOf(401 to "sign-in", 403 to "sign-in", 404 to "not found", 500 to "500")) {
            val (c, _) = client { reply(code, "") }
            val failed = assertIs<MetadataResult.Failed>(c.lookup(spec))
            assertTrue(word in failed.reason.lowercase(), "HTTP $code gave: ${failed.reason}")
        }
    }

    @Test
    fun networkProblemsBecomeFailuresNotCrashes() = runTest {
        val (c, _) = client { fail(ProviderError.Network("no route")) }
        assertEquals("no route", assertIs<MetadataResult.Failed>(c.lookup(spec)).reason)
        val (c2, _) = client { fail(ProviderError.Timeout("slow")) }
        assertIs<MetadataResult.Failed>(c2.lookup(spec))
    }

    @Test
    fun badJsonAndWrongShapesFail() = runTest {
        for (body in listOf("<html>", "{}", "42", "")) {
            val (c, _) = client { reply(200, body) }
            assertIs<MetadataResult.Failed>(c.lookup(spec), "body: $body")
        }
    }

    @Test
    fun aTokenIsSentOnlyAsAHeaderAndOnlyWhenGiven() = runTest {
        val t = FakeTransport()
        t.reply(404, "")
        ModelMetadataClient(t).lookup(spec)
        t.reply(404, "")
        ModelMetadataClient(t).lookup(spec, "hf_token_value")
        t.reply(404, "")
        ModelMetadataClient(t).lookup(spec, "  ")

        assertTrue(!t.gets[0].second.containsKey("Authorization"))
        assertEquals("Bearer hf_token_value", t.gets[1].second["Authorization"])
        assertTrue(!t.gets[1].first.contains("hf_token_value"))
        assertTrue(!t.gets[2].second.containsKey("Authorization"))
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val t = FakeTransport().apply { replyThrowing(CancellationException("stop")) }
        assertFailsWith<CancellationException> { ModelMetadataClient(t).lookup(spec) }
    }

    @Test
    fun bothCatalogueModelsUseHttpsAddresses() {
        for (s in ModelCatalog.all) {
            assertTrue(s.metadataUrl.startsWith("https://"))
            assertTrue(s.downloadUrl.startsWith("https://"))
            assertTrue(s.downloadUrl.endsWith(s.fileName))
        }
    }

    @Test
    fun theCatalogueStartsWithEstimatesNeverVerifiedSizes() {
        for (id in ModelId.entries) {
            val info = ModelCatalog.initialInfo(id)
            assertIs<Amount.Estimate>(info.size)
            assertTrue(!info.verified)
            assertNull(info.sha256)
        }
    }
}
