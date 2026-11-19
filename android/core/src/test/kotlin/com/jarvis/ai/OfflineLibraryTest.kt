package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.knowledge.ArraySource
import com.jarvis.ai.knowledge.ClusterCompression
import com.jarvis.ai.knowledge.ClusterDecoder
import com.jarvis.ai.knowledge.EntryKind
import com.jarvis.ai.knowledge.HtmlText
import com.jarvis.ai.knowledge.KnowledgePacks
import com.jarvis.ai.knowledge.OfflineEncyclopedia
import com.jarvis.ai.knowledge.OfflineLookupTool
import com.jarvis.ai.knowledge.OfflineQuestions
import com.jarvis.ai.knowledge.TitleGuesses
import com.jarvis.ai.knowledge.ZimArchive
import com.jarvis.ai.knowledge.ZimFormatException
import com.jarvis.ai.knowledge.ZimUnsupportedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OfflineLibraryTest {

    private val xorDecoder = ClusterDecoder { method, bytes, _ ->
        if (method != ClusterCompression.ZSTD) throw ZimUnsupportedException("only zstd in this test")
        ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
    }

    private fun einsteinHtml() = """
        <html><body><section>
        <table class="infobox"><tr><td><p>Infobox paragraph that is certainly long enough to be a lead paragraph by length.</p></td></tr></table>
        <p>Albert Einstein (14 March 1879 - 18 April 1955) was a German-born physicist.<sup class="reference">[1]</sup> He developed the theory of relativity.</p>
        <p>He won the Nobel Prize in Physics in 1921 &ndash; for his work on the photoelectric effect &amp; more.</p>
        </section></body></html>
    """.trimIndent()

    private fun sample(builder: ZimTestBuilder = ZimTestBuilder()): ByteArray = builder
        .article("Albert_Einstein", einsteinHtml())
        .redirect("Einstein", "Albert_Einstein")
        .article("Black_hole", "<p>A black hole is a place in space where gravity pulls so much that even light cannot get out of it.</p>")
        .article("Statue_of_Liberty", "<p>The Statue of Liberty is a large statue on Liberty Island in New York Harbor in the United States.</p>")
        .article("NASA", "<p>NASA is the space agency of the United States government, and it explores space and the Earth.</p>")
        .article("Mercury", "<p>Mercury may mean the planet Mercury, the chemical element mercury, or the Roman god Mercury here.</p>")
        .article("Mercury_(planet)", "<p>Mercury is the smallest planet and the closest planet to the Sun in our Solar System.</p>")
        .metadata("Title", "Test Wikipedia")
        .build()

    private fun open(bytes: ByteArray, decoder: ClusterDecoder = xorDecoder) = ZimArchive.open(ArraySource(bytes), decoder)

    // ---- the archive reader ---------------------------------------------------------------------------------------------

    @Test
    fun aWellFormedArchiveOpensAndIsComplete() {
        val zim = open(sample())

        assertTrue(zim.isComplete)
        assertEquals(8L, zim.entryCount)
        assertEquals("Test Wikipedia", zim.metadata("Title"))
    }

    @Test
    fun aFileThatIsNotAnArchiveIsRefusedWithAReadableReason() {
        assertFailsWith<ZimFormatException> { open(ByteArray(10)) }
        assertFailsWith<ZimFormatException> { open("this is certainly not a library file at all, just some plain text.".repeat(5).toByteArray()) }
        val bad = sample().also { it[0] = 0 }
        assertEquals("This is not an offline library file (.zim).", assertFailsWith<ZimFormatException> { open(bad) }.message)
    }

    @Test
    fun anUnsupportedVersionIsRefused() {
        val old = sample().also { it[4] = 4 }

        assertTrue(assertFailsWith<ZimFormatException> { open(old) }.message!!.contains("version 4"))
    }

    @Test
    fun aDownloadThatStoppedEarlyIsNotComplete() {
        val whole = sample()
        // Missing only the end of the checksum: the tables are intact, so it opens, but it must say it is incomplete.
        val nearlyDone = open(whole.copyOf(whole.size - 5))
        assertFalse(nearlyDone.isComplete)
        // Cut in the middle of the tables or content: refused outright.
        assertFailsWith<ZimFormatException> { open(whole.copyOf(whole.size / 2)) }
        assertFailsWith<ZimFormatException> { open(whole.copyOf(whole.size - 200)) }
    }

    @Test
    fun entriesAreFoundByExactAddressAndOnlyThen() {
        val zim = open(sample())

        assertNotNull(zim.find('C', "Albert_Einstein"))
        assertNotNull(zim.find('C', "Black_hole"))
        assertNull(zim.find('C', "black_hole"), "addresses are case sensitive")
        assertNull(zim.find('C', "Black_hol"))
        assertNull(zim.find('C', "Zebra"))
        assertNull(zim.find('A', "Albert_Einstein"), "a different namespace is a different address")
    }

    @Test
    fun everyEntryOfALargerArchiveCanBeFound() {
        val builder = ZimTestBuilder(blobsPerCluster = 7)
        for (i in 0 until 300) builder.article("Article_%03d".format(i), "<p>Body of article number $i, long enough to be kept as a paragraph.</p>")
        val zim = open(builder.build())

        for (i in 0 until 300) {
            val e = assertNotNull(zim.find('C', "Article_%03d".format(i)), "article $i")
            assertTrue(String(zim.read(e)).contains("number $i,"))
        }
        assertNull(zim.find('C', "Article_300"))
        assertNull(zim.find('C', "Article_"))
    }

    @Test
    fun prefixSearchReturnsSortedMatchesUpToTheLimit() {
        val zim = open(sample())

        assertEquals(listOf("Mercury", "Mercury_(planet)"), zim.withPrefix('C', "Mercury", 10).map { it.url })
        assertEquals(listOf("Mercury"), zim.withPrefix('C', "Mercury", 1).map { it.url })
        assertEquals(emptyList(), zim.withPrefix('C', "Venus", 10).map { it.url })
    }

    @Test
    fun redirectsAreFollowedAndLoopsAreRefused() {
        val zim = open(sample())
        val einstein = assertNotNull(zim.find('C', "Einstein"))
        assertEquals(EntryKind.REDIRECT, einstein.kind)
        assertEquals("Albert_Einstein", zim.resolve(einstein)?.url)

        val loop = open(ZimTestBuilder().redirect("A", "B").redirect("B", "A").article("Z", "<p>x</p>").build())
        assertNull(loop.resolve(assertNotNull(loop.find('C', "A"))))
    }

    @Test
    fun contentIsReadFromUncompressedExtendedAndCompressedClusters() {
        for (builder in listOf(ZimTestBuilder(), ZimTestBuilder(extendedOffsets = true), ZimTestBuilder(compress = ZimTestBuilder.xorCompress), ZimTestBuilder(extendedOffsets = true, compress = ZimTestBuilder.xorCompress))) {
            val zim = open(sample(builder))
            val e = assertNotNull(zim.find('C', "Statue_of_Liberty"))
            assertTrue(String(zim.read(e)).contains("Liberty Island"))
            assertEquals("text/html", zim.mimeType(e))
        }
    }

    @Test
    fun aCompressedArchiveWithoutADecoderSaysWhatIsMissing() {
        val bytes = sample(ZimTestBuilder(compress = ZimTestBuilder.xorCompress))
        val zim = ZimArchive.open(ArraySource(bytes))

        val e = assertNotNull(zim.find('C', "Black_hole"))
        assertTrue(assertFailsWith<ZimUnsupportedException> { zim.read(e) }.message!!.contains("zstd"))
    }

    @Test
    fun aDamagedBlockOffsetIsAnErrorNotACrash() {
        val bytes = sample()
        val zim = open(bytes)
        val e = assertNotNull(zim.find('C', "Black_hole"))
        val start = ZimTestBuilder.clusterStart(bytes, e.cluster.toInt())
        // Make the first block offset point far outside the cluster.
        bytes[start + 1] = 0xFF.toByte()
        bytes[start + 2] = 0xFF.toByte()
        bytes[start + 3] = 0x7F

        assertFailsWith<ZimFormatException> { open(bytes).read(open(bytes).find('C', "Black_hole")!!) }
    }

    @Test
    fun aHostileNameWithoutAnEndIsAnErrorNotACrash() {
        val bytes = sample()
        val zim = open(bytes)
        val e = assertNotNull(zim.find('C', "NASA"))
        // Erase every zero byte in the 2 KB after the entry start so its name never ends.
        val pointerAt = ZimTestBuilder.readU64(bytes, 32).toInt() + 8 * e.index.toInt()
        val entryAt = ZimTestBuilder.readU64(bytes, pointerAt).toInt()
        for (i in entryAt + 16 until minOf(bytes.size, entryAt + 2048)) if (bytes[i].toInt() == 0) bytes[i] = 'x'.code.toByte()

        assertFailsWith<ZimFormatException> { open(bytes).entry(e.index) }
    }

    @Test
    fun anEntryNumberOutsideTheArchiveIsRefused() {
        val zim = open(sample())

        assertFailsWith<ZimFormatException> { zim.entry(-1) }
        assertFailsWith<ZimFormatException> { zim.entry(zim.entryCount) }
    }

    // ---- turning HTML into sentences --------------------------------------------------------------------------------

    @Test
    fun theLeadSkipsTheInfoboxCitationsAndEntities() {
        val lead = HtmlText.lead(einsteinHtml())

        assertTrue(lead.startsWith("Albert Einstein (14 March 1879 - 18 April 1955) was a German-born physicist."), lead)
        assertFalse("Infobox" in lead)
        assertFalse("[1]" in lead)
        assertTrue("1921 - for his work on the photoelectric effect & more." in lead, lead)
    }

    @Test
    fun scriptsStylesAndNestedTablesAreDropped() {
        val html = "<style>p{color:red}</style><script>var x='<p>not text, but long enough to be kept as one</p>';</script>" +
            "<table><tr><td><table><tr><td><p>inner table paragraph that is long enough to pass the length check</p></td></tr></table>" +
            "<p>outer table paragraph that is also long enough to pass the length check</p></td></tr></table>" +
            "<p>The real opening paragraph is here and it is long enough to be kept as the lead.</p>"

        assertEquals("The real opening paragraph is here and it is long enough to be kept as the lead.", HtmlText.lead(html))
    }

    @Test
    fun shortParagraphsAreSkippedAndLongOnesAreCutAtASentence() {
        val sentence = "This is a sentence that is quite ordinary. "
        val html = "<p>Hi.</p><p>" + sentence.repeat(40) + "</p>"

        val lead = HtmlText.lead(html, maxChars = 200)

        assertTrue(lead.length <= 200, "${lead.length}")
        assertTrue(lead.endsWith("."), lead)
        assertFalse(lead.startsWith("Hi"))
    }

    @Test
    fun brokenOrHugeHtmlNeverThrowsOrRunsAway() {
        assertEquals("", HtmlText.lead(""))
        assertEquals("", HtmlText.lead("<p>unterminated <b>tag"))
        assertEquals("", HtmlText.lead("</table></sup></p></p></div><<<>>>"))
        val started = System.nanoTime()
        HtmlText.lead("<table>".repeat(100_000))
        HtmlText.lead("<p>".repeat(200_000) + "text ".repeat(200_000))
        assertTrue((System.nanoTime() - started) < 5_000_000_000L, "took too long")
    }

    @Test
    fun numericEntitiesAreDecodedAndBadOnesAreLeftAlone() {
        val html = "<p>Caf&#233; &#x41; &#9999999; &unknown; &amp;amp; and enough more words to pass the length check.</p>"

        val lead = HtmlText.lead(html)

        assertTrue(lead.startsWith("Café A "), lead)
        assertTrue("&unknown;" in lead && "&amp;" in lead, lead)
    }

    // ---- guessing the article name ---------------------------------------------------------------------------------------

    @Test
    fun aSpokenNameBecomesTheLikelyArticleAddresses() {
        assertTrue("Albert_Einstein" in TitleGuesses.of("albert einstein"))
        assertTrue("Eiffel_Tower" in TitleGuesses.of("the eiffel tower"))
        assertTrue("Statue_of_Liberty" in TitleGuesses.of("statue of liberty"))
        assertTrue("Black_hole" in TitleGuesses.of("black hole"))
        assertTrue("NASA" in TitleGuesses.of("nasa"))
        assertEquals("Albert_Einstein", TitleGuesses.of("Albert Einstein?").first())
    }

    @Test
    fun namesThatCouldReachOutsideTheArticleListAreRefused() {
        assertEquals(emptyList(), TitleGuesses.of(""))
        assertEquals(emptyList(), TitleGuesses.of("   "))
        assertEquals(emptyList(), TitleGuesses.of("a/b"))
        assertEquals(emptyList(), TitleGuesses.of("x".repeat(200)))
        assertEquals(emptyList(), TitleGuesses.of("<script>"))
        assertEquals(emptyList(), TitleGuesses.of("line\u0000break"))
        assertTrue("Line_break" in TitleGuesses.of("line\nbreak"), "a line break from speech is just a space")
    }

    // ---- looking things up ---------------------------------------------------------------------------------------------------

    private fun library(decoder: ClusterDecoder = xorDecoder, builder: ZimTestBuilder = ZimTestBuilder()) =
        OfflineEncyclopedia(open(sample(builder), decoder), "Test Wikipedia (CC BY-SA)")

    @Test
    fun aNameInAnyCaseFindsItsArticle() {
        val lib = library()

        for (name in listOf("albert einstein", "Albert Einstein", "ALBERT EINSTEIN", "the Albert Einstein.")) {
            val found = assertIs<OfflineEncyclopedia.Result.Found>(lib.lookup(name), name)
            assertEquals("Albert Einstein", found.title)
            assertTrue(found.text.startsWith("Albert Einstein (14 March"))
        }
        assertIs<OfflineEncyclopedia.Result.Found>(lib.lookup("statue of liberty"))
        assertIs<OfflineEncyclopedia.Result.Found>(lib.lookup("nasa"))
    }

    @Test
    fun aRedirectLeadsToTheRealArticle() {
        val found = assertIs<OfflineEncyclopedia.Result.Found>(library().lookup("einstein"))

        assertEquals("Albert Einstein", found.title)
    }

    @Test
    fun aMissingTopicOffersRealNearbyNames() {
        val missing = assertIs<OfflineEncyclopedia.Result.NotFound>(library().lookup("mercur"))
        assertEquals(listOf("Mercury", "Mercury (planet)"), missing.similar)

        val nothing = assertIs<OfflineEncyclopedia.Result.NotFound>(library().lookup("zzzz unknown thing"))
        assertTrue(nothing.similar.isEmpty())
    }

    @Test
    fun aNonHtmlEntryIsNeverReadAsAnArticle() {
        val zim = open(ZimTestBuilder().article("Picture", "binary-looking", mime = "image/png").article("Other", "<p>x</p>").build())

        assertIs<OfflineEncyclopedia.Result.NotFound>(OfflineEncyclopedia(zim, "t").lookup("picture"))
    }

    @Test
    fun anUnreadableLibraryIsReportedNotThrown() {
        val lib = OfflineEncyclopedia(ZimArchive.open(ArraySource(sample(ZimTestBuilder(compress = ZimTestBuilder.xorCompress)))), "t")

        assertTrue(assertIs<OfflineEncyclopedia.Result.Unreadable>(lib.lookup("black hole")).reason.contains("zstd"))
    }

    // ---- which sentences are encyclopedia questions ---------------------------------------------------------------------

    @Test
    fun worldQuestionsGiveTheirSubject() {
        val cases = mapOf(
            "who is Albert Einstein" to "Albert Einstein",
            "Who was Mahatma Gandhi?" to "Mahatma Gandhi",
            "what is a black hole" to "black hole",
            "what are quasars" to "quasars",
            "what's photosynthesis" to "photosynthesis",
            "tell me about the Taj Mahal" to "Taj Mahal",
            "explain gravity" to "gravity",
            "define democracy" to "democracy",
            "what does entropy mean" to "entropy",
            "meaning of serendipity" to "serendipity",
            "information about Mars" to "Mars",
        )
        for ((question, subject) in cases) assertEquals(subject, OfflineQuestions.subject(question), question)
    }

    @Test
    fun questionsAboutThePhoneOrTheMomentAreNotEncyclopediaQuestions() {
        for (text in listOf(
            "what is my battery", "what is the time", "what is the weather in delhi", "who is calling", "what is this", "what is on my screen",
            "what is today's date", "what is it", "who is there", "open whatsapp", "call mom", "what is " + "very long subject ".repeat(10),
            "what is the score", "explain it", "tell me about my day",
        )) {
            assertNull(OfflineQuestions.subject(text), text)
        }
    }

    // ---- the tool -------------------------------------------------------------------------------------------------------------

    private suspend fun run(tool: OfflineLookupTool, topic: String) = tool.execute(ToolArgs(mapOf("topic" to topic)))

    @Test
    fun theToolSaysSoWhenNoLibraryIsInstalled() = runTest {
        val failure = assertIs<ToolResult.Failure>(run(OfflineLookupTool { null }, "einstein"))

        assertIs<ToolError.Unavailable>(failure.error)
        assertTrue(failure.error.message.contains("Offline library"))
    }

    @Test
    fun theToolAnswersFromTheLibraryAndMarksItAsOutsideContent() = runTest {
        val lib = library()
        val success = assertIs<ToolResult.Success>(run(OfflineLookupTool { lib }, "black hole"))

        assertTrue(success.message.startsWith("Black hole: A black hole is a place in space"), success.message)
        assertTrue("Source: Test Wikipedia (CC BY-SA), a copy saved on this phone; it may be out of date" in success.message)
        assertTrue(success.answer && success.untrusted)
        assertFalse(success.private)
    }

    @Test
    fun theToolReportsAMissingArticleHonestly() = runTest {
        val lib = library()
        val failure = assertIs<ToolResult.Failure>(run(OfflineLookupTool { lib }, "mercur"))

        assertTrue(failure.error.message.contains("no article called \"mercur\""), failure.error.message)
        assertTrue(failure.error.message.contains("Close matches: Mercury, Mercury (planet)"), failure.error.message)
    }

    @Test
    fun theToolIsReadOnlyAndLowRisk() {
        val tool = OfflineLookupTool { null }

        assertEquals(Risk.LOW, tool.spec.risk)
        assertEquals("offline_lookup", tool.spec.name)
        assertTrue(tool.spec.requiredPermissions.isEmpty())
    }

    // ---- spoken commands -----------------------------------------------------------------------------------------------------

    private fun offlineTopic(text: String): String? {
        val match = NativeCommands.match(text) as? NativeCommands.Match.Run ?: return null
        val step = match.steps.singleOrNull() ?: return null
        if (step.tool != "offline_lookup") return null
        return step.parameters["topic"]?.toString()?.trim('"')
    }

    @Test
    fun anExplicitOfflineRequestRunsTheLibraryLookup() {
        assertEquals("black hole", offlineTopic("search offline for black hole"))
        assertEquals("Albert Einstein", offlineTopic("offline who is Albert Einstein"))
        assertEquals("gravity", offlineTopic("look up gravity in the offline library"))
        assertEquals("Taj Mahal", offlineTopic("tell me about the Taj Mahal from offline wikipedia"))
        assertEquals("photosynthesis", offlineTopic("offline wikipedia: photosynthesis"))
        assertEquals("mars", offlineTopic("hey jarvis, search the offline library for mars please"))
    }

    @Test
    fun anOrdinaryQuestionStillGoesToTheAssistant() {
        for (text in listOf("who is Albert Einstein", "what is a black hole", "tell me about the Taj Mahal", "go offline", "turn on offline mode", "is it offline")) {
            assertNull(offlineTopic(text), text)
        }
    }

    // ---- the packs on offer ---------------------------------------------------------------------------------------------------

    @Test
    fun everyPackIsAHttpsKiwixDownloadOfAnArchiveFile() {
        assertEquals(KnowledgePacks.all.size, KnowledgePacks.all.map { it.id }.toSet().size)
        for (pack in KnowledgePacks.all) {
            assertTrue(pack.downloadUrl.startsWith("https://download.kiwix.org/zim/wikipedia/"), pack.downloadUrl)
            assertTrue(KnowledgePacks.looksLikeLibraryFile(pack.fileName), pack.fileName)
            assertFalse(pack.gated)
            assertTrue(pack.approxBytes in 500_000_000L..3_000_000_000L, "${pack.id} ${pack.approxBytes}")
            assertTrue(pack.sourceName.contains("CC BY-SA"))
            assertEquals("Kiwix", pack.hostName)
        }
        assertTrue(KnowledgePacks.looksLikeLibraryFile("x.ZIM"))
        assertFalse(KnowledgePacks.looksLikeLibraryFile("x.gguf"))
    }

    // ---- checking a file on disk ------------------------------------------------------------------------------------------

    private fun fileOf(bytes: ByteArray): java.io.File = java.io.File.createTempFile("library", ".zim").also {
        it.deleteOnExit()
        it.writeBytes(bytes)
    }

    @Test
    fun aCompleteLibraryIsReadyAndNamesItself() {
        val file = fileOf(sample())

        val ready = assertIs<com.jarvis.ai.knowledge.LibraryCheck.Ready>(com.jarvis.ai.knowledge.LibraryChecker.check(file, xorDecoder))
        assertEquals("Test Wikipedia", ready.title)
        assertEquals(file.length(), ready.bytes)
    }

    @Test
    fun anUnfinishedDownloadIsToldApartFromADamagedFile() {
        val whole = sample()
        val half = com.jarvis.ai.knowledge.LibraryChecker.check(fileOf(whole.copyOf(whole.size / 2)), xorDecoder)
        val incomplete = assertIs<com.jarvis.ai.knowledge.LibraryCheck.Incomplete>(half)
        assertEquals(whole.size.toLong(), incomplete.expected)
        assertEquals((whole.size / 2).toLong(), incomplete.have)

        assertIs<com.jarvis.ai.knowledge.LibraryCheck.Invalid>(com.jarvis.ai.knowledge.LibraryChecker.check(fileOf(whole + ByteArray(5)), xorDecoder))
        assertIs<com.jarvis.ai.knowledge.LibraryCheck.Invalid>(com.jarvis.ai.knowledge.LibraryChecker.check(fileOf("not a library".toByteArray()), xorDecoder))
        assertIs<com.jarvis.ai.knowledge.LibraryCheck.Invalid>(com.jarvis.ai.knowledge.LibraryChecker.check(fileOf(ByteArray(0)), xorDecoder))
    }

    @Test
    fun aLibraryThisPhoneCannotDecodeIsNotAccepted() {
        val file = fileOf(sample(ZimTestBuilder(compress = ZimTestBuilder.xorCompress)))

        val invalid = assertIs<com.jarvis.ai.knowledge.LibraryCheck.Invalid>(com.jarvis.ai.knowledge.LibraryChecker.check(file, com.jarvis.ai.knowledge.NoDecoder))
        assertTrue(invalid.reason.contains("zstd"), invalid.reason)
    }

    @Test
    fun aMissingFileIsInvalidNotAnException() {
        val gone = java.io.File.createTempFile("gone", ".zim").also { it.delete() }

        assertIs<com.jarvis.ai.knowledge.LibraryCheck.Invalid>(com.jarvis.ai.knowledge.LibraryChecker.check(gone, xorDecoder))
    }
}
