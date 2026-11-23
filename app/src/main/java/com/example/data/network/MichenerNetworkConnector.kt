package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Dedicated live connector for the Canadian Auction Network powering:
 * - Michener Allen Auctioneering (www.maauctions.com)
 * - North Toronto Auction (www.northtorontoauction.com)
 * - Jardine Auctioneers (www.jardineauctioneers.com)
 *
 * Parses `a.imgContainer[href*=/lot/]` + `img.lot-img` CloudFront HD JPEG photos.
 */
class MichenerNetworkConnector(
    override val sourceId: AuctionSourceId,
    private val baseOrigin: String,
    private val defaultProvince: CanadianProvince,
    private val coveredProvinces: Set<CanadianProvince>
) : AuctionConnector {

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val enQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        return if (enQuery.isNotBlank()) {
            "$baseOrigin/all-auction-lots?page=1&pageSize=25&search=${AuctionHttpClient.urlEncode(enQuery)}"
        } else {
            "$baseOrigin/all-auction-lots?page=1&pageSize=25"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val effectiveQuery = buildSearchTerm(query, category)
        val requestUrl = if (effectiveQuery.isNotBlank()) {
            "$baseOrigin/all-auction-lots?page=1&pageSize=25&search=${AuctionHttpClient.urlEncode(effectiveQuery)}"
        } else {
            "$baseOrigin/all-auction-lots?page=1&pageSize=25"
        }

        if (province != CanadianProvince.ALL && coveredProvinces.isNotEmpty() && province !in coveredProvinces) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Couverture : ${sourceId.coverageLabel}",
                directSearchUrl = requestUrl
            )
        }

        val lots = runCatching { scrapeLots(requestUrl, province, query) }
            .getOrDefault(emptyList())
            .ifEmpty {
                val norm = BilingualQueryTranslator.normalize(query)
                if (norm in setOf("voiture", "voitures", "auto", "autos", "car", "cars", "vehicule", "vehicle")) {
                    // Retry with "suv" / "ford" to pull real automotive lots rather than farmland
                    runCatching { scrapeLots("$baseOrigin/all-auction-lots?page=1&pageSize=25&search=ford", province, query) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

        val latency = System.currentTimeMillis() - started
        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Catalogue en direct (${lots.size} lots)",
                directSearchUrl = requestUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail prêt — recherche directe",
                directSearchUrl = requestUrl
            )
        }
    }

    private fun buildSearchTerm(query: String, category: AuctionCategory): String {
        val translated = BilingualQueryTranslator.toEnglishSearchQuery(query)
        if (translated.isNotBlank()) return translated
        return when (category) {
            AuctionCategory.VEHICLES -> "ford"
            AuctionCategory.MACHINERY -> "tractor"
            AuctionCategory.TOOLS_EQUIPMENT -> "tool"
            else -> ""
        }
    }

    private fun scrapeLots(url: String, requestedProvince: CanadianProvince, rawQuery: String): List<AuctionLot> {
        val req = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()

            // Exact DOM pattern verified: <div class="imgWrapper"><a class="imgContainer" href="/auctions/.../lot/..."><img class="lot-img" src="https://d3j17a2r8lnfte.cloudfront.net/..."></a>
            val imgLinks = doc.select("a.imgContainer[href*=/lot/], a[href*=/lot/]")
            for (link in imgLinks) {
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val imgEl = link.selectFirst("img.lot-img, img")
                    ?: link.parent()?.selectFirst("img.lot-img, img")
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(
                    imgEl?.attr("src").orEmpty().ifBlank { imgEl?.attr("data-src").orEmpty() },
                    baseOrigin
                )

                // Climb up to the full lot row/card container that holds text (title, bid, odometer, location)
                val container = link.parents().firstOrNull { parent ->
                    val txt = parent.text()
                    txt.length in 25..1500 && (txt.contains("Bid", ignoreCase = true) || txt.contains("Location", ignoreCase = true) || txt.contains("Control", ignoreCase = true))
                } ?: link.parent()?.parent() ?: continue

                val fullText = container.text().trim()
                val altTitle = imgEl?.attr("alt")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val headingTitle = container.select("h2, h3, h4, h5, .card-title, a[href*=/lot/]").map { it.text().trim() }
                    .firstOrNull { it.length >= 6 }
                val title = altTitle.ifBlank { headingTitle.orEmpty() }
                    .ifBlank { absUrl.substringAfterLast("/").replace("-", " ").trim() }
                if (title.length < 4) continue

                val currentBidText = Regex("""Current Bid:\s*(\$[\d,]+\.\d{2})""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1)
                val parsedBid = AuctionHttpClient.extractPrice(currentBidText ?: fullText)
                val bids = AuctionHttpClient.extractInt(
                    Regex("""Bids:\s*(\d+)""", RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1)
                )
                val locMatch = Regex("""Location:\s*([^|]+?)(?:Control|Current|Bids|Odometer|$)""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1)?.trim().orEmpty()
                val lotNum = Regex("""Control\s*#:\s*(\w+)""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1).orEmpty()

                val detectedProv = CanadianProvince.fromCodeOrText("$locMatch $absUrl $fullText")
                    .let { if (it == CanadianProvince.ALL) defaultProvince else it }

                if (requestedProvince != CanadianProvince.ALL && detectedProv != requestedProvince) {
                    continue
                }

                val queryTerms = BilingualQueryTranslator.expandMatchingTerms(rawQuery)
                if (queryTerms.isNotEmpty()) {
                    val hay = BilingualQueryTranslator.normalize("$title $fullText")
                    if (queryTerms.none { hay.contains(it) }) continue
                }

                lots.add(
                    AuctionLot(
                        id = "${sourceId.name}_${absUrl.hashCode()}",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = fullText.take(450),
                        currentBid = parsedBid,
                        currency = "CAD",
                        bidCount = bids,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = detectedProv,
                        city = locMatch.takeIf { it.isNotBlank() && !it.equals("Offsite", true) }
                            ?: detectedProv.displayNameFr,
                        auctionHouse = sourceId.displayName,
                        category = AuctionCategory.inferFromText("$title $fullText"),
                        closingLabel = "En cours",
                        lotNumber = lotNum
                    )
                )
                if (lots.size >= 25) break
            }
            return lots
        }
    }
}
