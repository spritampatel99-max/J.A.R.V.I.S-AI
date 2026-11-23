package com.jarvis.ai

import com.jarvis.ai.voice.SafeZip
import com.jarvis.ai.voice.VoskResult
import com.jarvis.ai.voice.WakeDebounce
import com.jarvis.ai.voice.WakePhrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeWordTest {

    @Test
    fun theWholeWordJarvisWakesIt() {
        for (text in listOf("jarvis", "Jarvis", "JARVIS", "hello jarvis", "hey Jarvis!", "ok jarvis open whatsapp", "jarvis, call mom", "yes Jarvis.")) {
            assertTrue(WakePhrase.heard(text), text)
        }
    }

    @Test
    fun otherWordsDoNot() {
        for (text in listOf("", "service", "jarvisly", "jarvis123", "xjarvis", "j a r v i s", "harvest", "the travis", "[unk]")) {
            assertFalse(WakePhrase.heard(text), text)
        }
    }

    @Test
    fun theSameWordIsAcceptedOnceThenIgnoredUntilTheCooldownEnds() {
        val d = WakeDebounce(3_000)
        assertTrue(d.accept(10_000))
        assertFalse(d.accept(10_500), "the final result of the same word")
        assertFalse(d.accept(12_999))
        assertTrue(d.accept(13_000))
        assertFalse(d.accept(13_001))
    }

    @Test
    fun aClockThatJumpsBackwardsDoesNotLockItOutForever() {
        val d = WakeDebounce(3_000)
        assertTrue(d.accept(100_000))
        assertTrue(d.accept(50_000), "an earlier time is a clock change, not the same word")
    }

    @Test
    fun voskResultsAreReadInAllThreeShapes() {
        assertEquals("jarvis open", VoskResult.text("""{"partial":"jarvis open"}"""))
        assertEquals("hello jarvis", VoskResult.text("""{"text":"hello jarvis"}"""))
        assertEquals("a b", VoskResult.text("""{"alternatives":[{"text":"a","confidence":1},{"text":"b"}]}"""))
        assertEquals("", VoskResult.text("""{"partial":""}"""))
    }

    @Test
    fun badVoskOutputGivesEmptyText() {
        for (bad in listOf(null, "", "   ", "not json", "[1,2]", "{\"partial\":", "{\"text\":5}", "x".repeat(30_000))) {
            assertEquals("", VoskResult.text(bad), bad?.take(20))
        }
    }

    @Test
    fun zipPathsThatCouldEscapeTheFolderAreRefused() {
        for (bad in listOf("../evil", "a/../../evil", "/etc/passwd", "a\\b", "C:/x", "a/\u0000b", "", "..", "./..", "a/b/..", "x".repeat(400))) {
            assertNull(SafeZip.safeRelativePath(bad), bad.take(30))
        }
    }

    @Test
    fun ordinaryZipPathsAreKeptAndTidied() {
        assertEquals("vosk-model/am/final.mdl", SafeZip.safeRelativePath("vosk-model/am/final.mdl"))
        assertEquals("a/b", SafeZip.safeRelativePath("./a//b/"))
        assertEquals("conf", SafeZip.safeRelativePath("conf/"))
    }
}
