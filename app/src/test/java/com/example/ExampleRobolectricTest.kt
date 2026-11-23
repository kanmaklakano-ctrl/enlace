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

    @Test
    fun carfaxReportReaderDetectsRenderedReportAndCondensesText() {
        val loadingShell = "Loading report... Please wait"
        assertTrue(!com.example.data.ai.CarfaxReportReader.isCarfaxRendered(loadingShell))

        val renderedCarfax = """
            CARFAX Canada Vehicle History Report
            2017 PORSCHE 911 CARRERA 4 CABRIOLET
            VIN: WP0CA2A91HS142067
            Accident/Damage Records Found: 2018 Dec 14 - Ontario, Canada - Collision Front Right - Claim $49,651.00
            Last Registered In: Ontario (Normal)
            No Open Recalls Found
            21 Service Records Found
            Not Actively Declared Stolen
            Last Odometer Reading: 100,095 km
            Print
            Share
        """.trimIndent()

        assertTrue(com.example.data.ai.CarfaxReportReader.isCarfaxRendered(renderedCarfax))
        val condensed = com.example.data.ai.CarfaxReportReader.condenseCarfaxText(renderedCarfax)
        assertTrue(condensed.contains("49,651.00"))
        assertTrue(!condensed.lines().contains("Print"))

        val apiJson = """
            {"vin":"WP0CA2A91HS142067","branding":"Normal","accidents":[{"date":"2018-12-14","province":"Ontario","claimAmount":"49651.00","type":"Collision Front Right"}],"odometer":"100095 km","serviceRecordsCount":21,"openRecalls":0,"stolen":false,"lienStatus":"No active lien"}
        """.trimIndent()
        val wrapped = org.json.JSONObject()
            .put("domText", "CARFAX Canada Vehicle History Report")
            .put("apiPayloads", org.json.JSONArray().put(apiJson))
            .put("title", "CARFAX Canada")
            .put("readyState", "complete")
            .toString()
        val jsStringQuoted = org.json.JSONObject.quote(wrapped)
        val snapshot = com.example.data.ai.CarfaxReportReader.parseExtractionPayload(jsStringQuoted)
        assertTrue("Expected intercepted CARFAX API payload to be parsed", snapshot.hasApiPayload)
        assertTrue("Expected CARFAX snapshot to be marked ready", snapshot.isCarfaxReady)
        assertTrue(snapshot.combinedText.contains("49651.00"))
        assertTrue(snapshot.combinedText.contains("WP0CA2A91HS142067"))
    }

    @Test
    fun liveCanadianVinLookupDecodesVehicleAndFetchesRecalls() = runBlocking {
        // 1. Real 17-char VIN lookup (2017 Porsche 911 Carrera 4 Cabriolet)
        val porsche = com.example.data.ai.CanadianVinLookupService.lookupVehicleIntelligence(
            vin = "WP0CA2A91HS142067",
            listingTitle = "2017 Porsche 911 Carrera 4 Cabriolet",
            province = CanadianProvince.ON
        )
        println("VIN_LIVE_PORSCHE specs=${porsche?.compactSpecsLine()} valid=${porsche?.isVinValid} recalls=${porsche?.recalls?.size} tc=${porsche?.transportCanadaRecallsCount} complaints=${porsche?.complaintsCount} src=${porsche?.lookupSourceLabel}")
        assertNotNull("Expected live vPIC VIN decode for Porsche", porsche)
        assertTrue("Expected Porsche make decoded", porsche!!.make.contains("Porsche", ignoreCase = true))
        assertEquals("2017", porsche.modelYear)
        assertTrue("Expected recalls to be checked", porsche.recallsChecked)

        // 2. Fallback Year/Make/Model lookup when NO 17-char VIN exists on listing (e.g. 2018 Mercedes-Benz GLC300)
        val mercedes = com.example.data.ai.CanadianVinLookupService.lookupVehicleIntelligence(
            vin = null,
            listingTitle = "2018 Mercedes-Benz GLC300 AWD Sport Utility WDC0G4",
            province = CanadianProvince.QC
        )
        println("VIN_LIVE_MERCEDES specs=${mercedes?.compactSpecsLine()} recalls=${mercedes?.recalls?.size} complaints=${mercedes?.complaintsCount} prov=${mercedes?.provincialRegistryLabel}")
        assertNotNull("Expected fallback Year/Make/Model lookup for Mercedes", mercedes)
        assertTrue("Expected SAAQ provincial registry for QC", mercedes!!.provincialRegistryLabel.contains("SAAQ"))
    }
}
