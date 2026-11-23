package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Dedicated live connector for Maxx Auctions (www.maxx.ca), one of Ontario's largest
 * retail-return, electronics (Apple, Samsung, TVs, tools), and overstock online auction houses.
 *
 * Parses `div.listing-thumbnail.Auction[data-listingid]` from `/Browse?FullTextQuery=...`
 * and enriches matched lots in parallel with real product photo URLs (`og:image` / listing image).
 */
class MaxxAuctionsConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.MAXX_AUCTIONS_ON

    private val baseOrigin = "https://www.maxx.ca"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val term = plan.englishQuery
        return if (term.isNotBlank()) {
            val sort = if (plan.isDeviceSearch) "&SortFilterOptions=3" else ""
            "$baseOrigin/Browse?FullTextQuery=${AuctionHttpClient.urlEncode(term)}$sort"
        } else {
            "$baseOrigin/Browse?SortFilterOptions=0"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val searchTerm = plan.englishQuery.ifBlank {
            when (category) {
                AuctionCategory.ELECTRONICS -> "samsung"
                AuctionCategory.TOOLS_EQUIPMENT -> "dewalt"
                AuctionCategory.HOME_FURNITURE -> "furniture"
                AuctionCategory.VEHICLES -> "automotive"
                else -> ""
            }
        }
        val sortOption = if (plan.isDeviceSearch || searchTerm.isNotBlank()) "3" else "0"
        val requestUrl = if (searchTerm.isNotBlank()) {
            "$baseOrigin/Browse?FullTextQuery=${AuctionHttpClient.urlEncode(searchTerm)}&SortFilterOptions=$sortOption"
        } else {
            "$baseOrigin/Browse?SortFilterOptions=0"
        }

        if (province != CanadianProvince.ALL && province != CanadianProvince.ON) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Basé en Ontario (GTA / Toronto)",
                directSearchUrl = requestUrl
            )
        }

        val rawLots = runCatching { scrapeBrowseCards(requestUrl) }.getOrDefault(emptyList())
        val enrichedLots = enrichPhotosInParallel(rawLots.take(12)) + rawLots.drop(12)
        val latency = System.currentTimeMillis() - started

        if (enrichedLots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = enrichedLots,
                latencyMs = latency,
                statusDetail = "Maxx Auctions en direct (${enrichedLots.size} lots)",
                directSearchUrl = requestUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail Maxx Auctions prêt",
                directSearchUrl = requestUrl
            )
        }
    }

    private fun scrapeBrowseCards(url: String): List<AuctionLot> {
        val req = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val cards = doc.select("div.listing-thumbnail.Auction, div.listing-thumbnail[data-listingid]")
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()

            for (card in cards) {
                val link = card.selectFirst("span.title a.listing-link, a.listing-link[href*=/Event/LotDetails/], a[href*=/Listing/Details/]")
                    ?: continue
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val listingId = card.attr("data-listingid").ifBlank {
                    Regex("""/LotDetails/(\d+)""").find(absUrl)?.groupValues?.get(1)
                        ?: absUrl.hashCode().toString()
                }
                val title = link.attr("title").trim()
                    .ifBlank { link.text().trim() }
                    .replace(Regex("\\s+"), " ")
                if (title.length < 4) continue

                val condition = card.selectFirst("a.item-condition, .condition")?.text()?.trim().orEmpty()
                val categoryLabel = card.selectFirst("div.bel")?.text()?.trim().orEmpty()
                val fullText = card.text().replace(Regex("\\s+"), " ").trim()

                val allPrices = Regex("""\$([0-9]{1,3}(?:,[0-9]{3})*(?:\.\d{2})?|[0-9]+(?:\.\d{2})?)""")
                    .findAll(fullText)
                    .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
                    .toList()
                val currentBid = allPrices.firstOrNull()
                val retailEstimate = allPrices.getOrNull(1)

                val closingLabel = Regex("""\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}(?:st|nd|rd|th)?\b""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.value
                    ?: "En cours"

                // Check for any non-placeholder inline photo URL first
                val imgEl = card.selectFirst("img.card-img-top, img")
                val rawImg = listOf(
                    imgEl?.attr("data-src").orEmpty(),
                    imgEl?.attr("data-original").orEmpty(),
                    imgEl?.attr("src").orEmpty()
                ).firstOrNull { it.isNotBlank() && !it.contains("thumb_blank", ignoreCase = true) }
                val cardImageUrl = AuctionHttpClient.sanitizeImageUrl(rawImg, baseOrigin)

                val desc = buildString {
                    if (condition.isNotBlank()) append("État : $condition. ")
                    if (retailEstimate != null && retailEstimate > (currentBid ?: 0.0)) {
                        append("Valeur au détail estimée : ${"%.0f".format(retailEstimate)} $ CAD. ")
                    }
                    if (categoryLabel.isNotBlank()) append("Catégorie : $categoryLabel. ")
                    append("Ramassage Ontario (GTA) sur Maxx Auctions.")
                }

                lots.add(
                    AuctionLot(
                        id = "MAXX_$listingId",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = desc.take(450),
                        currentBid = currentBid,
                        currency = "CAD",
                        bidCount = 0,
                        imageUrl = cardImageUrl,
                        auctionUrl = absUrl,
                        province = CanadianProvince.ON,
                        city = "Toronto / GTA, ON",
                        auctionHouse = "Maxx Auctions",
                        category = AuctionCategory.inferFromText("$title $categoryLabel"),
                        closingLabel = closingLabel,
                        lotNumber = listingId,
                        buyersPremiumPercent = 15.0
                    )
                )
                if (lots.size >= 24) break
            }
            return lots
        }
    }

    private suspend fun enrichPhotosInParallel(lots: List<AuctionLot>): List<AuctionLot> = coroutineScope {
        lots.map { lot ->
            async(Dispatchers.IO) {
                if (!lot.imageUrl.isNullOrBlank()) return@async lot
                val fetchedPhoto = runCatching { fetchDetailPhotoUrl(lot.auctionUrl) }.getOrNull()
                if (fetchedPhoto != null) lot.copy(imageUrl = fetchedPhoto) else lot
            }
        }.awaitAll()
    }

    private fun fetchDetailPhotoUrl(lotUrl: String): String? {
        val req = AuctionHttpClient.newGetRequest(lotUrl)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html, baseOrigin)

            // Exact verified Maxx Auctions HD product photo path: /assets/listingimages/<guid>_fullsize.jpg
            val fullSize = doc.select("img[data-src*=/listingimages/], img[src*=/listingimages/]")
                .mapNotNull { img ->
                    img.attr("data-src").takeIf { it.contains("/listingimages/", true) }
                        ?: img.attr("src").takeIf { it.contains("/listingimages/", true) }
                }
                .sortedByDescending { it.contains("fullsize", ignoreCase = true) }
                .firstOrNull()
            if (fullSize != null) {
                return AuctionHttpClient.sanitizeImageUrl(fullSize, baseOrigin)
            }

            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { !it.contains("/content/images/", ignoreCase = true) }
            return AuctionHttpClient.sanitizeImageUrl(ogImage, baseOrigin)
        }
    }
}
