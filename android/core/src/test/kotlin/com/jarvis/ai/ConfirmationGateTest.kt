package com.jarvis.ai

import com.jarvis.ai.core.security.ConfirmationDecision
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.Risk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ConfirmationGateTest {

    private var counter = 0
    private fun newGate(ttlMs: Long = 10_000L) =
        ConfirmationGate(ttlMs = ttlMs, time = FakeTime(), newId = { "req-${++counter}" })

    @Test
    fun approvalCarriesTheFingerprintAndClearsThePendingRequest() = runTest {
        val gate = newGate()
        val decision = async { gate.request("delete_file", "Delete a.txt", Risk.HIGH, "fp-1") }
        runCurrent()

        val pending = assertNotNull(gate.pending.value)
        assertEquals("delete_file", pending.toolName)
        assertEquals("Delete a.txt", pending.summary)
        assertTrue(gate.approve(pending.id))

        assertEquals(ConfirmationDecision.Approved("fp-1"), decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun denyReturnsDenied() = runTest {
        val gate = newGate()
        val decision = async { gate.request("send_sms", "Send SMS", Risk.HIGH, "fp") }
        runCurrent()
        assertTrue(gate.deny(assertNotNull(gate.pending.value).id))
        assertIs<ConfirmationDecision.Denied>(decision.await())
    }

    @Test
    fun anUnknownRequestIdCannotApproveAnything() = runTest {
        val gate = newGate()
        val decision = async { gate.request("t", "s", Risk.HIGH, "fp") }
        runCurrent()

        assertFalse(gate.approve("not-the-id"))
        assertFalse(gate.deny("not-the-id"))
        assertNotNull(gate.pending.value, "the real request must still be waiting")

        gate.deny(assertNotNull(gate.pending.value).id)
        decision.await()
    }

    @Test
    fun aRequestCanOnlyBeAnsweredOnce() = runTest {
        val gate = newGate()
        val decision = async { gate.request("t", "s", Risk.HIGH, "fp") }
        runCurrent()
        val id = assertNotNull(gate.pending.value).id

        assertTrue(gate.approve(id))
        assertFalse(gate.approve(id), "second approval must not resolve anything")
        assertFalse(gate.deny(id), "a denial after approval must not change the outcome")
        assertIs<ConfirmationDecision.Approved>(decision.await())
    }

    @Test
    fun unansweredRequestExpiresAndIsWithdrawn() = runTest {
        val gate = newGate(ttlMs = 5_000L)
        val decision = async { gate.request("t", "s", Risk.HIGH, "fp") }
        runCurrent()
        assertNotNull(gate.pending.value)

        advanceTimeBy(5_001L)
        runCurrent()

        assertIs<ConfirmationDecision.Expired>(decision.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun requestsAreShownOneAtATime() = runTest {
        val gate = newGate()
        val first = async { gate.request("a", "first", Risk.HIGH, "fp-a") }
        val second = async { gate.request("b", "second", Risk.HIGH, "fp-b") }
        runCurrent()

        assertEquals("first", assertNotNull(gate.pending.value).summary)
        gate.approve(assertNotNull(gate.pending.value).id)
        runCurrent()

        assertEquals("second", assertNotNull(gate.pending.value).summary)
        gate.deny(assertNotNull(gate.pending.value).id)

        assertIs<ConfirmationDecision.Approved>(first.await())
        assertIs<ConfirmationDecision.Denied>(second.await())
    }

    @Test
    fun cancellingTheCallerWithdrawsTheRequest() = runTest {
        val gate = newGate()
        val job = launch { gate.request("t", "s", Risk.HIGH, "fp") }
        runCurrent()
        assertNotNull(gate.pending.value)

        job.cancelAndJoin()

        assertNull(gate.pending.value)
    }
}
