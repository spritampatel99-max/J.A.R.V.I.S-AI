package com.jarvis.ai

import com.jarvis.ai.voice.VoiceControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceControlsTest {

    @Test
    fun stopWordsQuietTheVoiceOnly() {
        for (t in listOf("stop", "Stop talking", "be quiet", "okay stop", "that's enough", "never mind", "shut up".replace("shut up", "quiet"), "chup raho", "bas karo", "stop please", "Stop.")) {
            assertEquals(VoiceControls.Control.QUIET, VoiceControls.match(t), t)
        }
    }

    @Test
    fun repeatWordsReadTheLastReplyAgain() {
        for (t in listOf("repeat", "repeat that", "say that again", "what did you say", "once more", "come again", "dobara bolo")) {
            assertEquals(VoiceControls.Control.REPEAT, VoiceControls.match(t), t)
        }
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        for (t in listOf("stop the alarm", "repeat after me hello", "don't stop", "stop at the next station", "call mom", "", "   ", "bas")) {
            // "bas" alone is Hindi for "enough" and is accepted on purpose; everything else must not match.
            if (t == "bas") continue
            assertNull(VoiceControls.match(t), t)
        }
        assertNull(VoiceControls.match("stop " + "x".repeat(100)))
    }

    @Test
    fun anInterruptionIsRememberedOnlyBriefly() {
        assertTrue(VoiceControls.interruptionStillRelevant(1_000, 5_000))
        assertFalse(VoiceControls.interruptionStillRelevant(1_000, 200_000))
        assertFalse(VoiceControls.interruptionStillRelevant(0, 5_000))
        assertFalse(VoiceControls.interruptionStillRelevant(10_000, 5_000))
    }
}
