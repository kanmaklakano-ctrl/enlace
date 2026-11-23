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
 * Dedicated live connector for 403 Auction (www.403auction.com, Burlington / GTA, Ontario),
 * specializing in bulk reseller pallets, electronics returns, and unclaimed freight auctions.
 */
class Auction403Connector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.AUCTION_403_ON

    private val baseOrigin = "https://www.403auction.com"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val term = BilingualQueryTranslator.toEnglishSearchQuery(query)
        return if (term.isNotBlank()) {
            "$baseOrigin/search?q=${AuctionHttpClient.urlEncode(term)}"
        } else {
            "$baseOrigin/auctions"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val searchTerm = plan.englishQuery
        val requestUrl = if (searchTerm.isNotBlank()) {
            "$baseOrigin/search?q=${AuctionHttpClient.urlEncode(searchTerm)}"
        } else {
            "$baseOrigin/search?q=pallet"
        }

        if (province != CanadianProvince.ALL && province != CanadianProvince.ON) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Basé en Ontario (Burlington / GTA)",
                directSearchUrl = requestUrl
            )
        }

        val lots = runCatching { scrapeLots(requestUrl, plan, category) }.getOrDefault(emptyList())
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "403 Auction en direct (${lots.size} lots)",
                directSearchUrl = requestUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail 403 Auction prêt",
                directSearchUrl = requestUrl
            )
        }
    }

    private fun scrapeLots(
        url: String,
        plan: SmartQueryPlan,
        category: AuctionCategory
    ): List<AuctionLot> {
        val req = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val links = doc.select("a[href*=/lot/]")
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()

            for (link in links) {
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val card = link.parents().firstOrNull { parent ->
                    parent.select("img").isNotEmpty() && parent.text().length in 20..1200
                } ?: continue

                val imgEl = card.selectFirst("img[src*=amazonaws], img[src*=cloudfront], img")
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(
                    imgEl?.attr("src").orEmpty().ifBlank { imgEl?.attr("data-src").orEmpty() },
                    baseOrigin
                ) ?: continue

                val fullText = card.text().replace(Regex("\\s+"), " ").trim()
                val lotNum = Regex("""Lot\s*([A-Za-z0-9\-]+)\s*:""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1).orEmpty()

                val rawTitle = fullText
                    .replace(Regex("""^Lot\s*[A-Za-z0-9\-]+\s*:\s*""", RegexOption.IGNORE_CASE), "")
                    .substringBefore("Condition:")
                    .substringBefore("UPC:")
                    .substringBefore("Starts:")
                    .substringBefore("Current High Bidder:")
                    .trim()
                val title = rawTitle.ifBlank {
                    absUrl.substringAfterLast("/").replace("-", " ").trim()
                }
                if (title.length < 4) continue

                // Strict bilingual filter when user entered a search query (since /search returns featured lots)
                if (plan.expandedTerms.isNotEmpty()) {
                    val hay = BilingualQueryTranslator.normalize("$title $fullText")
                    if (plan.expandedTerms.none { hay.contains(it) }) continue
                }

                val inferredCat = AuctionCategory.inferFromText("$title $fullText")
                if (plan.rawQuery.isBlank() && category != AuctionCategory.ALL &&
                    category != AuctionCategory.STORAGE_LIQUIDATION &&
                    inferredCat != category
                ) {
                    continue
                }

                val bidCount = AuctionHttpClient.extractInt(
                    Regex("""(\d+)\s*Bids?""", RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1)
                )
                val price = AuctionHttpClient.extractPrice(
                    Regex("""Bids?\s*(\$[\d,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE)
                        .find(fullText)?.groupValues?.get(1) ?: fullText
                )

                lots.add(
                    AuctionLot(
                        id = "A403_${absUrl.hashCode()}",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = fullText.take(450),
                        currentBid = price,
                        currency = "CAD",
                        bidCount = bidCount,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = CanadianProvince.ON,
                        city = "Burlington, ON",
                        auctionHouse = "403 Auction",
                        category = inferredCat,
                        closingLabel = "En cours",
                        lotNumber = lotNum,
                        buyersPremiumPercent = 15.0
                    )
                )
                if (lots.size >= 24) break
            }
            return lots
        }
    }
}
