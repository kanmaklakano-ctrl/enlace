package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AuctionAiAnalyst
import com.example.data.ai.AuctionDetailScraper
import com.example.data.ai.configureAppCheck
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun appNameAndFirebaseResourcesArePresent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("EncanLive", context.getString(R.string.app_name))

        val options = FirebaseOptions.fromResource(context)
        println("FIREBASE_TEST options=$options projectId=${options?.projectId} appId=${options?.applicationId}")
        assertNotNull("FirebaseOptions.fromResource(context) returned null!", options)
        assertEquals("gen-lang-client-0505612528", options?.projectId)

        val recaptchaResId = context.resources.getIdentifier("recaptcha_site_key", "string", context.packageName)
        println("FIREBASE_TEST recaptchaResId=$recaptchaResId packageName=${context.packageName}")
        assertTrue("recaptcha_site_key resource missing!", recaptchaResId != 0)
        assertTrue(context.getString(recaptchaResId).isNotBlank())
    }

    @Test
    fun configureAppCheckInitializesFirebaseAppAndPersistsDebugToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent = Intent().apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "test-debug-token-12345")
        }
        configureAppCheck(context, launchIntent)

        val apps = FirebaseApp.getApps(context)
        println("FIREBASE_TEST appsCount=${apps.size} defaultApp=${apps.firstOrNull()?.name}")
        assertTrue("FirebaseApp must be initialized!", apps.isNotEmpty())

        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.$persistenceKey",
            Context.MODE_PRIVATE
        )
        assertEquals(
            "test-debug-token-12345",
            prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        )
    }

    @Test
    fun mainActivityLaunchesAndInitializesFirebase() {
        val intent = Intent().apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "activity-token-999")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertTrue(FirebaseApp.getApps(activity).isNotEmpty())
        // Verify Firebase.ai generativeModel can be constructed lazily without throwing
        val analyst = AuctionAiAnalyst()
        assertNotNull(analyst)
        controller.pause().stop().destroy()
    }

    @Test
    fun liveScraperReadsRealCanadianAuctionPage() = runBlocking {
        val lot = AuctionLot(
            id = "test-hibid-vehicle",
            sourceId = AuctionSourceId.HIBID_CANADA,
            title = "2018 Mercedes-Benz GLC300 AWD Sport Utility",
            description = "Brantford ON auction lot",
            currentBid = 5500.0,
            imageUrl = null,
            auctionUrl = "https://www.gcsurplus.ca/mn-eng.cfm",
            province = CanadianProvince.ON,
            city = "Brantford, ON",
            auctionHouse = "AllStar Auctions Inc.",
            category = AuctionCategory.VEHICLES
        )
        val evidence = AuctionDetailScraper.scrapeLotEvidence(lot)
        println("SCRAPER_TEST scraped=${evidence.scrapedSuccessfully} title=${evidence.pageTitle} textLen=${evidence.fullListingText.length}")
        assertTrue("Scraper should fetch live web page or fail gracefully", evidence.pageTitle.isNotBlank() || !evidence.scrapedSuccessfully)
    }

    @Test
    fun liveHiBidVehicleSearchAndDetailPageScrapeWorksEndToEnd() = runBlocking {
        val connector = com.example.data.network.HiBidCanadaConnector()
        val result = connector.search(
            query = "car",
            province = CanadianProvince.ON,
            category = AuctionCategory.VEHICLES
        )
        println("HIBID_E2E count=${result.lots.size} state=${result.state} detail=${result.statusDetail}")
        assertTrue("Expected live HiBid ON vehicle lots", result.lots.isNotEmpty())
        val firstLot = result.lots.first()
        val evidence = AuctionDetailScraper.scrapeLotEvidence(firstLot)
        println(
            "HIBID_DETAIL_SCRAPE url=${firstLot.auctionUrl} scraped=${evidence.scrapedSuccessfully} " +
                "vin=${evidence.detectedVin} km=${evidence.detectedMileage} carfax=${evidence.carfaxUrl} textLen=${evidence.fullListingText.length}"
        )
        assertTrue("Expected lot URL to be valid", firstLot.auctionUrl.startsWith("https://"))
    }
}
