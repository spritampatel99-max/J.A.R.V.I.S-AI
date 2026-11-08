package com.jarvis.ai

import com.jarvis.ai.core.common.Bytes
import com.jarvis.ai.device.Reading
import com.jarvis.ai.features.FeatureId
import com.jarvis.ai.features.FeatureResolver
import com.jarvis.ai.features.Resolution
import com.jarvis.ai.models.Amount
import com.jarvis.ai.models.ModelCatalog
import com.jarvis.ai.models.ModelChoice
import com.jarvis.ai.models.ModelId
import com.jarvis.ai.models.ModelInfo
import com.jarvis.ai.setup.AmountTotal
import com.jarvis.ai.setup.StorageCalculator
import com.jarvis.ai.setup.StorageVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageCalculatorTest {

    private val calc = StorageCalculator()
    private val baseApk = Reading.measured(30_000_000L)
    private val essential = FeatureResolver.resolve(setOf(FeatureId.AI_ASSISTANT, FeatureId.CLOUD_AI, FeatureId.DEVICE_CONTROL))

    private val estimated = ModelId.entries.associateWith { ModelCatalog.initialInfo(it) }
    private val verified = mapOf(
        ModelId.GEMMA3_1B to ModelInfo(ModelCatalog.spec(ModelId.GEMMA3_1B), Amount.Known(584_417_280L), "a".repeat(64)),
        ModelId.GEMMA4_E2B to ModelInfo(ModelCatalog.spec(ModelId.GEMMA4_E2B), Amount.Known(2_588_147_712L), "b".repeat(64)),
    )

    private fun review(
        choice: ModelChoice? = ModelChoice.SKIP,
        models: Map<ModelId, ModelInfo> = estimated,
        free: Long? = 50 * Bytes.GIB,
        resolution: Resolution = essential,
        apk: Reading<Long> = baseApk,
    ) = calc.review(resolution, choice, models, phone(freeStorage = free), apk)

    @Test
    fun skippingTheModelDownloadsNothingAndNeedsNoConsent() {
        val r = review(ModelChoice.SKIP)
        assertEquals(0L, r.totalDownload.lowerBoundBytes)
        assertFalse(r.needsDownloadConsent)
        assertEquals(StorageVerdict.ENOUGH, r.verdict)
    }

    @Test
    fun theInstalledAppIsListedButNotAddedToTheTotals() {
        val r = review(ModelChoice.SKIP)
        val app = r.lines.first()
        assertTrue(app.alreadyOnDevice)
        assertEquals(Amount.Known(30_000_000L), app.installed)
        assertEquals(0L, r.totalInstalled.knownBytes)
    }

    @Test
    fun anUnreadableAppSizeIsShownAsUnknownNotZero() {
        val r = review(apk = Reading.unavailable("no"))
        assertTrue(r.lines.first().installed is Amount.Unknown)
    }

    @Test
    fun estimatedModelSizesStayEstimatesAndAreSaidToBe() {
        val r = review(ModelChoice.LIGHT_ONLY)
        assertEquals(584_417_280L, r.totalDownload.estimatedBytes)
        assertEquals(0L, r.totalDownload.knownBytes)
        assertFalse(r.totalDownload.exact)
        assertTrue(r.warnings.any { "estimates" in it })
    }

    @Test
    fun verifiedSizesAreExactAndSumAcrossBothModels() {
        val r = review(ModelChoice.BOTH, verified)
        assertEquals(584_417_280L + 2_588_147_712L, r.totalDownload.knownBytes)
        assertEquals(0L, r.totalDownload.estimatedBytes)
        assertTrue(r.totalDownload.exact)
        assertTrue(r.warnings.none { "estimates" in it })
        assertEquals(r.totalDownload.knownBytes, r.totalInstalled.knownBytes)
    }

    @Test
    fun aModelOfUnknownSizeIsCountedAsUnknownAndNeedsConsent() {
        val unknown = mapOf(ModelId.GEMMA3_1B to ModelInfo(ModelCatalog.spec(ModelId.GEMMA3_1B), Amount.Unknown("not read")))
        val r = review(ModelChoice.LIGHT_ONLY, unknown)
        assertEquals(1, r.totalDownload.unknownCount)
        assertTrue(r.needsDownloadConsent)
    }

    @Test
    fun aModelMissingFromTheMapIsUnknownRatherThanZero() {
        val r = review(ModelChoice.ADVANCED_ONLY, emptyMap())
        assertEquals(1, r.totalDownload.unknownCount)
    }

    @Test
    fun anyRealModelDownloadNeedsAnExplicitYes() {
        assertTrue(review(ModelChoice.LIGHT_ONLY).needsDownloadConsent)
        assertTrue(review(ModelChoice.BOTH, verified).needsDownloadConsent)
    }

    @Test
    fun theConsentThresholdIsExactlyFiftyMebibytes() {
        assertTrue(calc.needsConsent(AmountTotal(50 * Bytes.MIB, 0, 0)))
        assertFalse(calc.needsConsent(AmountTotal(50 * Bytes.MIB - 1, 0, 0)))
        assertTrue(calc.needsConsent(AmountTotal(0, 0, 1)))
        assertTrue(calc.needsConsent(AmountTotal(10, 50 * Bytes.MIB, 0)))
    }

    @Test
    fun notEnoughFreeStorageIsReportedWithTheShortfall() {
        val r = review(ModelChoice.BOTH, verified, free = 2 * Bytes.GIB)
        assertEquals(StorageVerdict.INSUFFICIENT, r.verdict)
        assertEquals(584_417_280L + 2_588_147_712L - 2 * Bytes.GIB, r.extraNeededBytes)
        assertTrue(r.warnings.any { "Not enough free storage" in it })
    }

    @Test
    fun almostEnoughIsTightNotFine() {
        val need = 584_417_280L
        val r = review(ModelChoice.LIGHT_ONLY, verified, free = need + 10 * Bytes.MIB)
        assertEquals(StorageVerdict.TIGHT, r.verdict)
        assertEquals(0L, r.extraNeededBytes)
    }

    @Test
    fun plentyOfRoomIsEnough() {
        assertEquals(StorageVerdict.ENOUGH, review(ModelChoice.BOTH, verified, free = 64 * Bytes.GIB).verdict)
    }

    @Test
    fun unreadableFreeStorageCannotBeJudged() {
        val r = review(ModelChoice.LIGHT_ONLY, free = null)
        assertEquals(StorageVerdict.UNKNOWN, r.verdict)
        assertEquals(null, r.availableBytes)
        assertEquals(0L, r.extraNeededBytes)
        assertTrue(r.warnings.any { "could not be read" in it })
    }

    @Test
    fun databaseAndCacheGrowthIsListedAsUnknownNotInvented() {
        val line = review().lines.single { it.label.startsWith("Database") }
        assertTrue(line.installed is Amount.Unknown)
        assertEquals(Amount.NONE, line.download)
    }

    @Test
    fun featureCostsAreCountedOnceEvenWhenAFeatureIsBothChosenAndNeeded() {
        val res = FeatureResolver.resolve(setOf(FeatureId.OFFLINE_KNOWLEDGE, FeatureId.LOCAL_AI_LAB), { builtFeature(it) })
        val r = review(ModelChoice.SKIP, resolution = res)
        assertEquals(1, r.lines.count { it.label == "Offline Knowledge" })
        assertEquals(1, r.totalDownload.unknownCount)
    }

    @Test
    fun localAiLabWithoutAModelGetsAWarning() {
        val res = FeatureResolver.resolve(setOf(FeatureId.LOCAL_AI_LAB), { builtFeature(it) })
        val r = review(ModelChoice.SKIP, resolution = res)
        assertTrue(r.warnings.any { "no model is chosen" in it })
        assertTrue(review(ModelChoice.LIGHT_ONLY, resolution = res).warnings.none { "no model is chosen" in it })
    }

    @Test
    fun theFeatureCountsComeFromTheResolution() {
        val r = review()
        assertEquals(3, r.selectedFeatureCount)
        assertEquals(0, r.impliedFeatureCount)
        val implied = review(resolution = FeatureResolver.resolve(setOf(FeatureId.DEVICE_CONTROL)))
        assertEquals(1, implied.selectedFeatureCount)
        assertEquals(1, implied.impliedFeatureCount)
    }

    @Test
    fun modelMemoryEstimatesArePassedThroughForChosenModelsOnly() {
        val r = calc.review(essential, ModelChoice.LIGHT_ONLY, estimated, phone(), baseApk, mapOf(ModelId.GEMMA3_1B to 1L, ModelId.GEMMA4_E2B to 2L))
        assertEquals(listOf(ModelId.GEMMA3_1B to 1L), r.modelRam)
    }

    @Test
    fun amountTotalsKeepTheThreeKindsApart() {
        val t = AmountTotal.of(listOf(Amount.Known(5), Amount.Estimate(7, "x"), Amount.Unknown("y"), Amount.Known(1)))
        assertEquals(6L, t.knownBytes)
        assertEquals(7L, t.estimatedBytes)
        assertEquals(1, t.unknownCount)
        assertEquals(13L, t.lowerBoundBytes)
        assertFalse(t.exact)
        assertTrue(AmountTotal.of(listOf(Amount.Known(5))).exact)
    }
}

private fun builtFeature(id: FeatureId) =
    com.jarvis.ai.features.FeatureCatalog.feature(id).copy(readiness = com.jarvis.ai.features.Readiness.Available)
