package com.jarvis.ai

import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.device.ConnectionType
import com.jarvis.ai.models.Amount
import com.jarvis.ai.models.Basis
import com.jarvis.ai.models.Level
import com.jarvis.ai.models.ModelAdvisor
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInfo
import com.jarvis.ai.models.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelAdvisorTest {

    private val advisor = ModelAdvisor()
    private val light = ModelCatalog.initialInfo(ModelId.GEMMA3_1B)
    private val advanced = ModelCatalog.initialInfo(ModelId.GEMMA4_E2B)

    @Test
    fun aRoomyArm64PhoneLooksSuitableForTheLightModel() {
        val a = advisor.assess(phone(), light)
        assertEquals(Verdict.LOOKS_SUITABLE, a.verdict)
        assertTrue(a.findings.none { it.level == Level.PROBLEM })
    }

    @Test
    fun lotsOfRamDoesNotMakeAModelSuitableWhenStorageIsShort() {
        val a = advisor.assess(phone(totalRam = 16 * Bytes.GIB, freeStorage = 300 * Bytes.MIB), light)
        assertEquals(Verdict.NOT_RECOMMENDED, a.verdict)
        assertTrue(a.findings.any { it.level == Level.PROBLEM && "storage" in it.text.lowercase() })
    }

    @Test
    fun aSmallRamPhoneIsNotRecommendedTheAdvancedModelAndTheReasonIsExplained() {
        val a = advisor.assess(phone(totalRam = 4 * Bytes.GIB, availableRam = 2 * Bytes.GIB), advanced)
        assertEquals(Verdict.NOT_RECOMMENDED, a.verdict)
        val f = a.findings.single { it.level == Level.PROBLEM }
        assertEquals(Basis.HEURISTIC, f.basis)
        assertTrue("rule of thumb" in f.text.lowercase())
    }

    @Test
    fun theSamePhoneCanStillLookSuitableForTheLightModel() {
        assertEquals(Verdict.LOOKS_SUITABLE, advisor.assess(phone(totalRam = 4 * Bytes.GIB, availableRam = 2 * Bytes.GIB), light).verdict)
    }

    @Test
    fun lowFreeMemoryRightNowIsAWarningNotAFailure() {
        val a = advisor.assess(phone(totalRam = 12 * Bytes.GIB, availableRam = 500 * Bytes.MIB), light)
        assertEquals(Verdict.USE_WITH_CAUTION, a.verdict)
    }

    @Test
    fun anAndroidLowMemoryFlagIsAWarning() {
        assertEquals(Verdict.USE_WITH_CAUTION, advisor.assess(phone(lowMemory = true), light).verdict)
    }

    @Test
    fun tightStorageIsAWarning() {
        val a = advisor.assess(phone(freeStorage = 700 * Bytes.MIB), light)
        assertEquals(Verdict.USE_WITH_CAUTION, a.verdict)
    }

    @Test
    fun anUnknownArchitectureIsNotJudgedAndNotCalledUnsuitable() {
        val a = advisor.assess(phone(abis = listOf("x86_64")), light)
        assertEquals(Verdict.CANNOT_ASSESS, a.verdict)
        assertTrue(a.findings.any { it.basis == Basis.UNVERIFIED && "x86_64" in it.text })
    }

    @Test
    fun aDefiniteProblemBeatsAnUnknown() {
        val a = advisor.assess(phone(abis = listOf("x86_64"), freeStorage = 100 * Bytes.MIB), light)
        assertEquals(Verdict.NOT_RECOMMENDED, a.verdict)
    }

    @Test
    fun anUnknownSizeCannotBeAssessed() {
        val a = advisor.assess(phone(), ModelInfo(light.spec, Amount.Unknown("not read")))
        assertEquals(Verdict.CANNOT_ASSESS, a.verdict)
        assertNull(a.ramEstimateBytes)
    }

    @Test
    fun unreadableStorageOrMemoryCannotBeAssessed() {
        assertEquals(Verdict.CANNOT_ASSESS, advisor.assess(phone(freeStorage = null), light).verdict)
        assertEquals(Verdict.CANNOT_ASSESS, advisor.assess(phone(totalRam = null), light).verdict)
    }

    @Test
    fun anEstimatedSizeIsLabelledAsAnEstimateAndAVerifiedOneAsMeasured() {
        val estimated = advisor.assess(phone(), light).findings.first { "storage" in it.text.lowercase() }
        assertEquals(Basis.ESTIMATE, estimated.basis)
        assertTrue("about" in estimated.text)

        val verified = advisor.assess(phone(), ModelInfo(light.spec, Amount.Known(584_417_280L), "a".repeat(64)))
            .findings.first { "storage" in it.text.lowercase() }
        assertEquals(Basis.MEASURED, verified.basis)
        assertTrue("about" !in verified.text)
    }

    @Test
    fun theGatedModelExplainsTheLicenceStepAndTheOpenOneDoesNot() {
        assertTrue(advisor.assess(phone(), light).findings.any { it.level == Level.INFO && "licence" in it.text })
        assertTrue(advisor.assess(phone(), advanced).findings.none { "licence" in it.text })
    }

    @Test
    fun theVendorMemoryFigureIsShownAsOneDeviceNotARequirement() {
        val note = advisor.assess(phone(), advanced).findings.single { it.text.startsWith("Model card") }
        assertEquals(Basis.UNVERIFIED, note.basis)
        assertTrue("one phone" in note.text && "not a requirement" in note.text)
    }

    @Test
    fun downloadAdviceMentionsMobileDataLowBatteryAndNoConnection() {
        val a = advisor.assess(phone(connection = ConnectionType.CELLULAR, metered = true, battery = 10, charging = false, online = false), advanced)
        assertEquals(3, a.downloadAdvice.size)
        assertTrue(a.downloadAdvice.any { "metered" in it })
        assertTrue(a.downloadAdvice.any { "10%" in it })
        assertTrue(a.downloadAdvice.any { "no internet" in it.lowercase() })
    }

    @Test
    fun noAdviceOnWifiWithGoodBattery() {
        assertTrue(advisor.assess(phone(), advanced).downloadAdvice.isEmpty())
    }

    @Test
    fun chargingSuppressesTheBatteryAdvice() {
        assertTrue(advisor.assess(phone(battery = 5, charging = true), advanced).downloadAdvice.none { "Battery" in it })
    }

    @Test
    fun whenBothLookSuitableTheLighterOneIsRecommendedFirst() {
        val r = advisor.recommend(phone(), listOf(advanced, light))
        assertEquals(ModelId.GEMMA3_1B, r.recommended)
        assertTrue("advanced" in r.explanation.lowercase())
    }

    @Test
    fun aPhoneThatFitsOnlyTheLightModelGetsTheLightModel() {
        val r = advisor.recommend(phone(totalRam = 4 * Bytes.GIB, availableRam = 2 * Bytes.GIB), listOf(light, advanced))
        assertEquals(ModelId.GEMMA3_1B, r.recommended)
        assertEquals(Verdict.NOT_RECOMMENDED, r.assessment(ModelId.GEMMA4_E2B).verdict)
    }

    @Test
    fun nothingIsRecommendedForAPhoneWithNoRoomEvenWithHugeRam() {
        val r = advisor.recommend(phone(totalRam = 24 * Bytes.GIB, freeStorage = 50 * Bytes.MIB), listOf(light, advanced))
        assertNull(r.recommended)
        assertTrue("cloud" in r.explanation.lowercase())
    }

    @Test
    fun whenNothingCanBeJudgedNothingIsRecommended() {
        val r = advisor.recommend(phone(abis = listOf("x86_64")), listOf(light, advanced))
        assertNull(r.recommended)
        assertTrue("missing" in r.explanation || "judged" in r.explanation)
    }

    @Test
    fun aCautionModelIsOfferedWithItsWarning() {
        val r = advisor.recommend(phone(lowMemory = true), listOf(light))
        assertEquals(ModelId.GEMMA3_1B, r.recommended)
        assertTrue("warning" in r.explanation.lowercase())
    }

    @Test
    fun theStorageRequirementIncludesHeadroom() {
        val a = advisor.assess(phone(), light)
        val size = light.sizeBytes!!
        assertNotNull(a.freeStorageNeededBytes)
        assertTrue(a.freeStorageNeededBytes!! >= size + ModelCatalog.MIN_HEADROOM_BYTES)
    }

    @Test
    fun theAdvisorNeverClaimsOfflineAiIsReady() {
        assertTrue("test-loaded" in ModelAdvisor.CONFIRMATION_NOTE)
    }
}
