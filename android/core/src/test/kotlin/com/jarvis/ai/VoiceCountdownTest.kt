package com.jarvis.ai

import com.jarvis.ai.core.security.ConfirmationDecision
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.voice.RequestOrigin
import com.jarvis.ai.voice.VoiceCancel
import com.jarvis.ai.voice.VoiceCountdown
import com.jarvis.ai.voice.VoiceDirectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCountdownTest {

    private fun request(tool: String, id: String = "r1") =
        ConfirmationRequest(id, tool, "Call Asha (mobile) on 98xxxxxx10", Risk.HIGH, expiresAtMs = 0L)

    // ---- who may use the countdown ------------------------------------------------------------------------

    @Test
    fun onlyASpokenBuiltInCallCommandQualifies() {
        for (origin in RequestOrigin.entries) {
            for (tool in listOf("call_contact", "call_number", "answer_call", "end_call", "delete_file", "send_message", "open_url", "add_task")) {
                val expected = origin == RequestOrigin.SPOKEN_BUILT_IN && tool in setOf("call_contact", "call_number", "answer_call", "end_call")
                assertEquals(expected, VoiceDirectPolicy.eligible(origin, request(tool)), "$origin / $tool")
            }
        }
    }

    // ---- what is read back ------------------------------------------------------------------------------------

    private fun spoken(tool: String, summary: String) =
        VoiceDirectPolicy.readBack(ConfirmationRequest("r", tool, summary, Risk.HIGH, 0L))

    @Test
    fun theReadBackNamesTheResolvedContactAndTheNumberType() {
        assertEquals("Calling Asha Rao, mobile. Say stop to cancel.", spoken("call_contact", "Call Asha Rao on +919876543210 (mobile)"))
        assertEquals("Calling Asha. Say stop to cancel.", spoken("call_contact", "Call Asha on 98765 ()"))
    }

    @Test
    fun aDialledNumberIsReadDigitByDigit() {
        assertEquals("Calling plus 9 1 1 2 3. Say stop to cancel.", spoken("call_number", "Call +91123"))
    }

    @Test
    fun answeringAndEndingAreReadBackToo() {
        assertEquals("Answering the call. Say stop to cancel.", spoken("answer_call", "Answer the ringing phone call"))
        assertEquals("Ending the call. Say stop to cancel.", spoken("end_call", "Hang up or reject the current phone call"))
    }

    @Test
    fun anUnexpectedSummaryIsReadAsWrittenAndNeverEmpty() {
        assertEquals("Call somebody odd. Say stop to cancel.", spoken("call_contact", "Call somebody odd"))
    }

    // ---- the countdown itself ----------------------------------------------------------------------------

    private fun TestScopeGate(): ConfirmationGate = ConfirmationGate(ttlMs = 60_000L, time = FakeTime())

    @Test
    fun theCallIsApprovedOnlyWhenThreeSecondsHavePassed() = runTest {
        val gate = TestScopeGate()
        val decision = async { gate.request("call_contact", "Call Asha", Risk.HIGH, "fp-1") }
        runCurrent()
        val pending = assertNotNull(gate.pending.value)
        val ticks = mutableListOf<Int>()

        val outcome = async { VoiceCountdown(gate).run(pending, RequestOrigin.SPOKEN_BUILT_IN) { ticks += it } }
        runCurrent()
        assertEquals(listOf(3), ticks)

        advanceTimeBy(2_999L)
        runCurrent()
        assertNotNull(gate.pending.value, "still waiting one millisecond before the end")
        assertFalse(decision.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(VoiceCountdown.Outcome.APPROVED, outcome.await())
        assertEquals(listOf(3, 2, 1), ticks)
        assertEquals(ConfirmationDecision.Approved("fp-1"), decision.await(), "approval carries the fingerprint of the action that was read back")
    }

    @Test
    fun cancellingTheCountdownApprovesNothing() = runTest {
        val gate = TestScopeGate()
        val decision = async { gate.request("call_contact", "Call Asha", Risk.HIGH, "fp-1") }
        runCurrent()
        val pending = assertNotNull(gate.pending.value)

        val countdown = launch { VoiceCountdown(gate).run(pending, RequestOrigin.SPOKEN_BUILT_IN) }
        runCurrent()
        advanceTimeBy(2_000L)
        countdown.cancel()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()

        assertNotNull(gate.pending.value, "nothing approved it")
        gate.deny(pending.id)
        assertEquals(ConfirmationDecision.Denied, decision.await())
    }

    @Test
    fun anAssistantPlannedCallIsNeverCountedDown() = runTest {
        val gate = TestScopeGate()
        val decision = async { gate.request("call_contact", "Call Asha", Risk.HIGH, "fp-1") }
        runCurrent()
        val pending = assertNotNull(gate.pending.value)

        for (origin in listOf(RequestOrigin.TYPED, RequestOrigin.ASSISTANT_PLANNED)) {
            assertEquals(VoiceCountdown.Outcome.NOT_ELIGIBLE, VoiceCountdown(gate).run(pending, origin))
        }
        advanceTimeBy(30_000L)
        runCurrent()

        assertNotNull(gate.pending.value, "still waiting for a tap")
        assertFalse(decision.isCompleted)
        gate.deny(pending.id)
    }

    @Test
    fun aRequestThatWasAlreadyAnsweredIsReportedAsTooLate() = runTest {
        val gate = TestScopeGate()
        val decision = async { gate.request("end_call", "End the call", Risk.HIGH, "fp-2") }
        runCurrent()
        val pending = assertNotNull(gate.pending.value)
        val result = CompletableDeferred<VoiceCountdown.Outcome>()
        launch { result.complete(VoiceCountdown(gate).run(pending, RequestOrigin.SPOKEN_BUILT_IN)) }
        runCurrent()

        gate.deny(pending.id)
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(VoiceCountdown.Outcome.TOO_LATE, result.await())
        assertIs<ConfirmationDecision.Denied>(decision.await())
        assertNull(gate.pending.value)
    }

    // ---- words that stop it ---------------------------------------------------------------------------------

    @Test
    fun stopWordsCancel() {
        for (heard in listOf("stop", "Stop!", "cancel", "no", "No, don't call", "wait", "don't", "stop calling", "never mind", "hold on", "wrong person", "not that one")) {
            assertTrue(VoiceCancel.isCancel(heard), heard)
        }
    }

    @Test
    fun otherSpeechDoesNotCancel() {
        for (heard in listOf("", "   ", "yes", "go ahead", "call now", "stopwatch", "notification settings", "nothing", "okay")) {
            assertFalse(VoiceCancel.isCancel(heard), heard)
        }
    }
}
