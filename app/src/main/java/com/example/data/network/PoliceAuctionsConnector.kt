package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class PoliceAuctionsConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.POLICE_AUCTIONS_CA

    private val baseOrigin = "https://www.policeauctionscanada.com"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val enQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        return if (enQuery.isBlank()) {
            "$baseOrigin/Browse?SortFilterOptions=1"
        } else {
            "$baseOrigin/Browse?FullTextQuery=${AuctionHttpClient.urlEncode(enQuery)}&SortFilterOptions=1"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val directUrl = buildDirectSearchUrl(query, province)

        // Police Auctions Canada operates primarily out of Toronto, Ontario (shipping across Canada)
        if (province != CanadianProvince.ALL && province != CanadianProvince.ON) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Basé en Ontario (livraison Canada disponible sur le portail)",
                directSearchUrl = directUrl
            )
        }

        val lots = runCatching { scrapeBrowsePage(directUrl) }.getOrNull()
        val latency = System.currentTimeMillis() - started

        if (lots != null && lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Flux HTML en direct (${lots.size} lots)",
                directSearchUrl = directUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail prêt — ouvrir pour parcourir en direct",
                directSearchUrl = directUrl
            )
        }
    }

    private fun scrapeBrowsePage(url: String): List<AuctionLot> {
        val request = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val jsonLd = AuctionHttpClient.parseJsonLdLots(
                doc = doc,
                sourceId = sourceId,
                baseOrigin = baseOrigin,
                defaultProvince = CanadianProvince.ON,
                defaultCity = "Toronto, ON"
            )
            if (jsonLd.isNotEmpty()) return jsonLd

            val lots = mutableListOf<AuctionLot>()
            val cards = doc.select("section.galleryUnit, div.list.clearfix, div.listing-card, article.listing")
            val elementsToParse = if (cards.isNotEmpty()) cards else doc.select("a[href*=/Listing/Details/]")
            val seen = mutableSetOf<String>()

            for (el in elementsToParse) {
                val linkEl = if (el.tagName() == "a") el else el.select("a[href*=/Listing/Details/]").first()
                val rawHref = linkEl?.attr("href") ?: continue
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(rawHref, baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val container = if (el.tagName() == "a") el.parent() ?: el else el
                val title = container.select("h1, h2, h3, h4, .galleryTitle, .title").first()?.text()?.trim()
                    .orEmpty()
                    .ifBlank { linkEl.attr("title").trim() }
                    .ifBlank { linkEl.text().trim() }
                if (title.length < 4) continue

                val imgEl = container.select("img").first()
                val imgUrl = AuctionHttpClient.sanitizeHttpsUrl(
                    imgEl?.attr("data-src").orEmpty()
                        .ifBlank { imgEl?.attr("src").orEmpty() },
                    baseOrigin
                )

                val priceText = container.select(".galleryPrice, .NumberPart, .price, .awe-rt-CurrentPrice").text()
                    .ifBlank { container.text() }
                val price = AuctionHttpClient.extractPrice(priceText)

                val bidsText = container.select(".awe-rt-AcceptedListingActionCount, .bids").text()
                val bidCount = AuctionHttpClient.extractInt(bidsText)

                val timeText = container.select(".galleryTime, .time, [data-epoch-time], .awe-rt-endingDTTM").text().trim()
                val epochSec = container.select("[data-epoch-time]").attr("data-epoch-time").toLongOrNull()

                val lotIdMatch = Regex("""/Details/(\d+)""").find(absUrl)?.groupValues?.get(1) ?: absUrl.hashCode().toString()

                lots.add(
                    AuctionLot(
                        id = "PAC_$lotIdMatch",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = container.select(".subtitle, .description").text().trim().ifBlank {
                            "Lot vérifié sur Police Auctions Canada (Toronto, Ontario — expédition canadienne disponible)."
                        },
                        currentBid = price,
                        currency = "CAD",
                        bidCount = bidCount,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = CanadianProvince.ON,
                        city = "Toronto, ON",
                        auctionHouse = "Police Auctions Canada",
                        category = AuctionCategory.inferFromText(title),
                        closingTimeMillis = epochSec?.let { it * 1000L },
                        closingLabel = timeText.ifBlank { "En cours" },
                        lotNumber = lotIdMatch
                    )
                )
                if (lots.size >= 24) break
            }
            return lots
        }
    }
}
