package com.example

import com.example.data.network.AuctionHttpClient
import com.example.data.network.BilingualQueryTranslator
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.CostBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncanCoreUnitTest {
    @Test
    fun bilingualTranslationMapsFrenchAuctionTermsToEnglish() {
        assertEquals("car", BilingualQueryTranslator.toEnglishSearchQuery("voiture"))
        assertEquals("truck", BilingualQueryTranslator.toEnglishSearchQuery("camion"))
        assertEquals("tools", BilingualQueryTranslator.toEnglishSearchQuery("outils"))
        assertEquals("tractor", BilingualQueryTranslator.toEnglishSearchQuery("tracteur"))
        assertTrue("toyota" in BilingualQueryTranslator.expandMatchingTerms("voiture"))
        assertTrue("hyundai" in BilingualQueryTranslator.expandMatchingTerms("voiture"))
    }

    @Test
    fun smartQueryEngineStripsFillerWordsAndFiltersPhoneAccessories() {
        val plan = BilingualQueryTranslator.analyzeQuery("lot iphone")
        assertEquals("iphone", plan.englishQuery)
        assertTrue(plan.wantsBulkLot)
        assertTrue(plan.shouldFilterAccessoriesByDefault)
        assertTrue(plan.apiQueries.contains("apple iphone"))

        val realPhone = AuctionLot(
            id = "1",
            sourceId = AuctionSourceId.HIBID_CANADA,
            title = "Apple iPhone 15 Pro 256GB Unlocked",
            description = "Clean IMEI unlocked smartphone",
            currentBid = 340.0,
            imageUrl = "https://cdn.hibid.com/img.axd?id=1",
            auctionUrl = "https://canada.hibid.com/lot/1/iphone",
            province = CanadianProvince.ON,
            city = "London, ON",
            auctionHouse = "Test",
            category = AuctionCategory.ELECTRONICS
        )
        val cheapCase = realPhone.copy(
            id = "2",
            title = "OtterBox Defender Series Case for iPhone 15 Pro",
            description = "Protective silicone cover",
            currentBid = 2.0
        )

        assertFalse(BilingualQueryTranslator.isLikelyAccessory(realPhone, plan))
        assertTrue(BilingualQueryTranslator.isLikelyAccessory(cheapCase, plan))
        assertTrue(
            BilingualQueryTranslator.scoreRelevance(realPhone, plan) >
                BilingualQueryTranslator.scoreRelevance(cheapCase, plan)
        )
    }

    @Test
    fun imageSanitizerBlocksUiIconsAndAllowsRealPhotos() {
        assertNull(AuctionHttpClient.sanitizeImageUrl("https://www.gcsurplus.ca/images/open-bidding.gif"))
        assertNull(AuctionHttpClient.sanitizeImageUrl("https://www.gcsurplus.ca/images/gcsurplus-mini-logo-new.png"))
        assertNotNull(AuctionHttpClient.sanitizeImageUrl("https://www.gcsurplus.ca/lotImages/3010620.jpeg"))
        assertNotNull(AuctionHttpClient.sanitizeImageUrl("https://d3j17a2r8lnfte.cloudfront.net/nta/2026/11/medium/photo.jpeg"))
    }

    @Test
    fun canadianProvincialTaxCalculationIsAccurate() {
        val ontario = CostBreakdown.calculate(1000.0, 15.0, CanadianProvince.ON)
        assertEquals(1150.0, ontario.subtotal, 0.01)
        assertEquals(149.50, ontario.taxAmount, 0.01)
        assertEquals(1299.50, ontario.totalCost, 0.01)

        val alberta = CostBreakdown.calculate(1000.0, 10.0, CanadianProvince.AB)
        assertEquals(1100.0, alberta.subtotal, 0.01)
        assertEquals(55.0, alberta.taxAmount, 0.01)
        assertEquals(1155.0, alberta.totalCost, 0.01)
    }

    @Test
    fun auctionDetailScraperExtractsVinMileageAndCarfaxUrl() {
        val sample = """
            2021 Toyota RAV4 XLE AWD - VIN: 2T3R1RFV5MC184291
            Odometer: 84,520 km | Retail: $29,900
            Full CARFAX report: https://vhr.carfax.ca/?id=abc123XYZ
        """.trimIndent()

        assertEquals("2T3R1RFV5MC184291", com.example.data.ai.AuctionDetailScraper.extractVin(sample))
        assertEquals("84,520 km", com.example.data.ai.AuctionDetailScraper.extractMileage(sample))
        assertEquals("https://vhr.carfax.ca/?id=abc123XYZ", com.example.data.ai.AuctionDetailScraper.extractCarfaxUrlFromRawText(sample))
    }

    @Test
    fun aiAnalystParsesStructuredJsonDirectly() {
        val json = """
            {
              "verdict": "BON_DEAL",
              "dealScore": 82,
              "headline": "Bonne affaire sous la valeur marchande canadienne",
              "estimatedMarketValueCad": "750 $ – 900 $ CAD",
              "recommendedMaxBidCad": "540 $ CAD",
              "estimatedProfitOrSavings": "~220 $ CAD d'économie",
              "priceAnalysis": "Le coût total avec frais et TVH reste nettement sous Kijiji.",
              "conditionSummary": "Appareil déverrouillé en bon état apparent.",
              "carfaxAnalysis": "",
              "pros": ["Prix bien sous le marché", "Déverrouillé"],
              "redFlags": ["Vendu tel quel sans garantie"],
              "verificationChecklist": ["Vérifier l'état de la batterie"]
            }
        """.trimIndent()

        val report = com.example.data.ai.AuctionAiAnalyst.parseStudyReportJson(
            lotId = "lot-42",
            jsonText = json,
            evidence = com.example.data.ai.ScrapedLotEvidence(scrapedSuccessfully = true)
        )

        assertEquals(com.example.data.ai.DealVerdict.BON_DEAL, report.verdict)
        assertEquals(82, report.dealScore)
        assertEquals("540 $ CAD", report.recommendedMaxBidCad)
        assertEquals(2, report.pros.size)
        assertTrue(report.scrapedOfficialPage)
    }
}
