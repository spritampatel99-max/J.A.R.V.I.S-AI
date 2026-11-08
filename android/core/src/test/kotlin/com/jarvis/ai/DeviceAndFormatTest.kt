package com.jarvis.ai

import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.device.Provenance
import com.jarvis.ai.device.Reading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAndFormatTest {

    @Test
    fun aMeasuredReadingHasAValueAndSaysSo() {
        val r = Reading.measured(8L)
        assertEquals(8L, r.value)
        assertEquals(Provenance.MEASURED, r.provenance)
        assertTrue(r.isKnown)
    }

    @Test
    fun anUnavailableReadingHasNoValueAndAReason() {
        val r = Reading.unavailable<Long>("not readable")
        assertNull(r.value)
        assertEquals(Provenance.UNAVAILABLE, r.provenance)
        assertEquals("not readable", r.note)
        assertFalse(r.isKnown)
    }

    @Test
    fun anEstimateMustCarryItsNoteAndAValue() {
        val r = Reading.estimated(5, "rounded")
        assertEquals(Provenance.ESTIMATED, r.provenance)
        assertEquals("rounded", r.note)
    }

    @Test
    fun aReadingCannotClaimToBeMeasuredWithoutAValue() {
        assertFailsWith<IllegalArgumentException> { Reading<Long>(null, Provenance.MEASURED) }
        assertFailsWith<IllegalArgumentException> { Reading(5L, Provenance.UNAVAILABLE) }
    }

    @Test
    fun theTestPhoneReportsUnreadableValuesAsUnavailableNotAsZero() {
        val p = phone(totalRam = null, freeStorage = null)
        assertNull(p.totalRamBytes.value)
        assertNull(p.freeStorageBytes.value)
        assertEquals(Provenance.UNAVAILABLE, p.totalRamBytes.provenance)
    }

    @Test
    fun arm64IsDetectedFromTheReportedAbis() {
        assertTrue(phone(abis = listOf("arm64-v8a")).supportsArm64)
        assertFalse(phone(abis = listOf("x86_64")).supportsArm64)
        assertFalse(phone(abis = emptyList()).supportsArm64)
    }

    @Test
    fun sizesAreFormattedInDecimalUnits() {
        assertEquals("0 B", ByteFormat.format(0))
        assertEquals("999 B", ByteFormat.format(999))
        assertEquals("1.00 KB", ByteFormat.format(1_000))
        assertEquals("584 MB", ByteFormat.format(584_417_280))
        assertEquals("2.59 GB", ByteFormat.format(2_588_147_712))
        assertEquals("12.3 GB", ByteFormat.format(12_300_000_000))
        assertEquals("256 GB", ByteFormat.format(256_000_000_000))
    }

    @Test
    fun aNegativeSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { ByteFormat.format(-1) }
    }
}
