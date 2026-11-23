package com.example.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.network.AuctionHttpClient
import com.example.model.AuctionLot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlin.math.max

object AuctionDetailScraper {

    private val vinRegex = Regex("""\b([A-HJ-NPR-Z0-9]{17})\b""")
    private val mileageRegex = Regex(
        """(?i)(?:odometer|mileage|kilom[èe]trage|km|miles?)[:\s-]*([0-9]{1,3}(?:[,\s][0-9]{3})+|[0-9]{4,7})\s*(km|kms|kilometers|kilometres|mi|miles)?|\b([0-9]{1,3}(?:[,\s][0-9]{3})+)\s*(km|kms|mi|miles)\b"""
    )
    private val retailRegex = Regex(
        """(?i)(?:msrp|retail(?:\s+price|\s+value)?|valeur\s+au\s+d[ée]tail|prix\s+d[ée]tail|regular\s+price)[:\s]*\$?\s*([0-9][0-9,]*(?:\.[0-9]{2})?)"""
    )
    private val premiumRegex = Regex(
        """(?i)(?:buyer'?s?\s+premium|frais\s+d'?acheteur)[:\s]*([0-9]{1,2}(?:\.[0-9]+)?\s*%)"""
    )
    private val carfaxUrlInTextRegex = Regex(
        """https?://[^\s"'<>\\)]*(?:carfax\.(?:ca|com)|carproof\.com|autocheck\.com)[^\s"'<>\\)]*""",
        RegexOption.IGNORE_CASE
    )

    suspend fun scrapeLotEvidence(lot: AuctionLot): ScrapedLotEvidence = withContext(Dispatchers.IO) {
        val seedText = "${lot.title}\n${lot.description}"
        var detectedVin = extractVin(seedText)
        var detectedMileage = extractMileage(seedText)
        var retailHint = extractRetailPrice(seedText)
        var carfaxUrl = extractCarfaxUrlFromRawText(seedText)
        var inspectionUrl: String? = null
        var buyersPremiumHint: String? = null
        var pageTitle = ""
        var fullListingText = ""
        var scrapedOk = false

        val targetUrl = lot.auctionUrl.trim()
        if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            val html = fetchTextSafely(targetUrl, maxBytes = 900_000)
            if (!html.isNullOrBlank()) {
                scrapedOk = true
                val doc = Jsoup.parse(html, targetUrl)
                pageTitle = doc.title().trim()

                // 1. Discover CARFAX & inspection links from anchors, iframes, data attributes, and raw scripts
                val discoveredLinks = extractReportLinks(doc, html, targetUrl)
                if (carfaxUrl == null) {
                    carfaxUrl = discoveredLinks.carfaxUrl
                }
                inspectionUrl = discoveredLinks.inspectionUrl

                // 2. Extract embedded HiBid / Next.js JSON state snippets if present
                val embeddedStateText = extractEmbeddedStateHighlights(doc)

                // 3. Clean visible body text
                doc.select("script, style, noscript, svg, nav, footer, header, iframe").remove()
                val visibleText = doc.body()?.text().orEmpty()
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                val combinedForExtraction = buildString {
                    append(seedText)
                    if (embeddedStateText.isNotBlank()) {
                        append('\n').append(embeddedStateText)
                    }
                    if (visibleText.isNotBlank()) {
                        append('\n').append(visibleText)
                    }
                }

                if (detectedVin == null) detectedVin = extractVin(combinedForExtraction)
                if (detectedMileage == null) detectedMileage = extractMileage(combinedForExtraction)
                if (retailHint == null) retailHint = extractRetailPrice(combinedForExtraction)
                buyersPremiumHint = extractBuyersPremium(combinedForExtraction)

                fullListingText = buildString {
                    if (embeddedStateText.isNotBlank()) {
                        append("[Données structurées de l'annonce]\n")
                        append(embeddedStateText.take(2200))
                        append("\n\n")
                    }
                    if (visibleText.isNotBlank()) {
                        append("[Contenu de la page officielle]\n")
                        append(visibleText.take(4500))
                    }
                }.trim()
            }
        }

        // 4. If a CARFAX / vehicle history URL was found on the lot page, try fetching its public summary
        var carfaxReportText: String? = null
        var carfaxAccessNote: String? = null
        if (!carfaxUrl.isNullOrBlank()) {
            val carfaxHtml = fetchTextSafely(carfaxUrl, maxBytes = 700_000)
            if (!carfaxHtml.isNullOrBlank()) {
                val extracted = extractCarfaxSummary(carfaxHtml, carfaxUrl)
                if (extracted.isNotBlank()) {
                    carfaxReportText = extracted.take(3200)
                    if (detectedVin == null) detectedVin = extractVin(extracted)
                    if (detectedMileage == null) detectedMileage = extractMileage(extracted)
                } else {
                    carfaxAccessNote = "Lien CARFAX officiel détecté sur l'annonce, mais son contenu détaillé est chargé dynamiquement ou protégé ; consultez-le directement via le bouton CARFAX."
                }
            } else {
                carfaxAccessNote = "Lien CARFAX officiel détecté sur l'annonce, mais inaccessible en lecture automatique ; ouvrez-le directement via le bouton CARFAX."
            }
        }

        ScrapedLotEvidence(
            scrapedSuccessfully = scrapedOk,
            pageTitle = pageTitle,
            fullListingText = fullListingText,
            detectedVin = detectedVin,
            detectedMileage = detectedMileage,
            retailPriceHint = retailHint,
            carfaxUrl = carfaxUrl,
            carfaxReportText = carfaxReportText,
            carfaxAccessNote = carfaxAccessNote,
            inspectionReportUrl = inspectionUrl,
            pageBuyersPremiumHint = buyersPremiumHint
        )
    }

    suspend fun downloadLotBitmapIfSmall(imageUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        val url = imageUrl?.trim().orEmpty()
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return@withContext null
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "image/webp,image/jpeg,image/png,*/*;q=0.8")
                .build()
            AuctionHttpClient.imageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return@withContext null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                var sampleSize = 1
                val maxDim = max(bounds.outWidth, bounds.outHeight)
                while (maxDim / sampleSize > 1024) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractVin(text: String): String? {
        if (text.isBlank()) return null
        val matches = vinRegex.findAll(text.uppercase())
        for (match in matches) {
            val candidate = match.groupValues[1]
            if (isPlausibleVin(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isPlausibleVin(candidate: String): Boolean {
        if (candidate.length != 17) return false
        val digitCount = candidate.count { it.isDigit() }
        val letterCount = candidate.count { it.isLetter() }
        // Real North American VINs contain a mix of letters & digits (never all digits or almost all letters)
        if (digitCount < 4 || letterCount < 3) return false
        // Last 4 characters of North American VINs are almost always numeric serial digits
        if (candidate.takeLast(4).count { it.isDigit() } < 3) return false
        // Exclude pure hex hashes (0-9A-F only with no non-hex VIN chars AND weird prefixes)
        val wmiFirst = candidate[0]
        val validWmiStarts = "123456789ABCDEFGHJKLMNPRSTUVWXYZ"
        if (wmiFirst !in validWmiStarts) return false
        return true
    }

    internal fun extractCarfaxUrlFromRawText(raw: String): String? {
        val found = carfaxUrlInTextRegex.find(raw)?.value ?: return null
        return cleanDiscoveredUrl(found)
    }

    internal fun extractMileage(text: String): String? {
        val match = mileageRegex.find(text) ?: return null
        val numA = match.groupValues[1]
        val unitA = match.groupValues[2]
        val numB = match.groupValues[3]
        val unitB = match.groupValues[4]
        val number = numA.ifBlank { numB }.trim()
        val unit = unitA.ifBlank { unitB }.ifBlank { "km" }.trim()
        if (number.isBlank()) return null
        return "$number $unit"
    }

    internal fun extractRetailPrice(text: String): String? {
        val match = retailRegex.find(text) ?: return null
        val amount = match.groupValues[1].trim()
        return if (amount.isNotBlank()) "$amount $ CAD" else null
    }

    private fun extractBuyersPremium(text: String): String? {
        return premiumRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private data class ReportLinks(
        val carfaxUrl: String?,
        val inspectionUrl: String?
    )

    private fun extractReportLinks(doc: Document, rawHtml: String, baseUrl: String): ReportLinks {
        var carfaxUrl: String? = null
        var inspectionUrl: String? = null

        val anchors = doc.select("a[href], iframe[src]")
        for (el in anchors) {
            val rawHref = el.attr("abs:href").ifBlank { el.attr("abs:src") }.ifBlank {
                el.attr("href").ifBlank { el.attr("src") }
            }
            val resolved = resolveUrl(baseUrl, rawHref) ?: continue
            val lowerHref = resolved.lowercase()
            val label = "${el.text()} ${el.attr("title")} ${el.attr("aria-label")}".lowercase()

            if (carfaxUrl == null && (
                    "carfax.ca" in lowerHref ||
                        "carfax.com" in lowerHref ||
                        "carproof.com" in lowerHref ||
                        "autocheck.com" in lowerHref ||
                        "carfax" in label
                    )
            ) {
                if (!lowerHref.endsWith(".png") && !lowerHref.endsWith(".jpg") && !lowerHref.endsWith(".svg")) {
                    carfaxUrl = cleanDiscoveredUrl(resolved)
                }
            }

            if (inspectionUrl == null && (
                    "inspection" in label ||
                        "condition report" in label ||
                        "rapport d'inspection" in label ||
                        "mechanical" in label && "report" in label
                    ) && !lowerHref.startsWith("javascript:")
            ) {
                inspectionUrl = cleanDiscoveredUrl(resolved)
            }
        }

        if (carfaxUrl == null) {
            carfaxUrl = extractCarfaxUrlFromRawText(rawHtml.replace("\\/", "/"))
        }

        return ReportLinks(carfaxUrl = carfaxUrl, inspectionUrl = inspectionUrl)
    }

    private fun extractEmbeddedStateHighlights(doc: Document): String {
        val interestingScripts = doc.select("script#hibid-state, script#__NEXT_DATA__, script[type=application/ld+json]")
        if (interestingScripts.isEmpty()) return ""
        val sb = StringBuilder()
        for (script in interestingScripts) {
            val raw = script.data().trim()
            if (raw.isBlank()) continue
            if (script.attr("type").equals("application/ld+json", ignoreCase = true)) {
                sb.append(raw.take(1500)).append('\n')
            } else {
                // Pull out descriptive fields without dumping giant unrelated catalog arrays
                val fieldRegex = Regex(
                    """(?i)"(?:description|lead|lotNotes|condition|mileage|odometer|vin|serialNumber|buyersPremium|shipping|inspection|carfaxUrl|vehicleHistoryUrl|retailPrice|msrp)"\s*:\s*"([^"]{2,600})""""
                )
                fieldRegex.findAll(raw).take(15).forEach { match ->
                    sb.append(match.value.replace("\\n", " ").replace("\\/", "/")).append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    private fun extractCarfaxSummary(carfaxHtml: String, carfaxUrl: String): String {
        val doc = Jsoup.parse(carfaxHtml, carfaxUrl)
        val jsonLd = doc.select("script[type=application/ld+json], script#__NEXT_DATA__")
            .joinToString("\n") { it.data().take(2000) }
        doc.select("script, style, noscript, svg, nav, footer").remove()
        val bodyText = doc.body()?.text().orEmpty()
            .replace(Regex("""\s+"""), " ")
            .trim()

        val combined = buildString {
            if (bodyText.isNotBlank()) append(bodyText.take(2600))
            if (jsonLd.isNotBlank()) {
                if (isNotBlank()) append("\n")
                append(jsonLd.take(1200))
            }
        }

        val lower = combined.lowercase()
        val hasUsefulVehicleSignals = listOf(
            "accident", "damage", "lien", "salvage", "rebuilt", "odometer",
            "service record", "recall", "registration", "stolen", "normal",
            "aucun accident", "dommage", "rappel", "kilom", "carfax"
        ).any { it in lower }

        // Avoid returning empty JS-shell boilerplate like "Enable JavaScript to run this app"
        if (!hasUsefulVehicleSignals && combined.length < 140) return ""
        if ("enable javascript" in lower && combined.length < 300) return ""
        return combined
    }

    private fun resolveUrl(baseUrl: String, rawHref: String): String? {
        val trimmed = rawHref.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("javascript:") || trimmed.startsWith("mailto:")) {
            return null
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else null
        }
    }

    private fun cleanDiscoveredUrl(url: String): String {
        return url.trim()
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trimEnd('.', ',', ';', '"', '\'', ')', ']')
    }

    private fun fetchTextSafely(url: String, maxBytes: Int): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", "fr-CA,fr;q=0.9,en-CA;q=0.8,en;q=0.7")
                .build()
            AuctionHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotBlank() &&
                    "text" !in contentType &&
                    "html" !in contentType &&
                    "json" !in contentType &&
                    "xml" !in contentType
                ) {
                    return null
                }
                val source = response.body?.source() ?: return null
                source.request(maxBytes.toLong())
                val buffer = source.buffer.clone()
                val readBytes = minOf(buffer.size, maxBytes.toLong())
                buffer.readUtf8(readBytes)
            }
        } catch (_: Exception) {
            null
        }
    }
}
