package com.example

import com.example.data.network.AuctionHttpClient
import com.example.data.network.BilingualQueryTranslator
import com.example.model.CanadianProvince
import com.example.model.CostBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
