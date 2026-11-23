package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class BcAuctionConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.BC_AUCTION

    private val baseOrigin = "https://www.bcauction.ca"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        return "$baseOrigin/open.dll/welcome"
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val directUrl = buildDirectSearchUrl(query, province)

        if (province != CanadianProvince.ALL && province != CanadianProvince.BC) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Réservé aux lots de Colombie-Britannique",
                directSearchUrl = directUrl
            )
        }

        val lots = runCatching { scrapeBcAuction(query) }.getOrNull().orEmpty()
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Flux HTML BC Auction (${lots.size} lots)",
                directSearchUrl = directUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail BC Auction prêt (accès direct)",
                directSearchUrl = directUrl
            )
        }
    }

    private fun scrapeBcAuction(query: String): List<AuctionLot> {
        val request = AuctionHttpClient.newGetRequest("$baseOrigin/open.dll/welcome")
        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()
            val links = doc.select("a[href*=showDisplayDocument], a[href*=auction], a[href*=lot], a[href*=Item]")
            val queryLower = query.trim().lowercase()

            for (link in links) {
                val title = link.text().trim()
                if (title.length < 5) continue
                if (queryLower.isNotBlank() && !title.lowercase().contains(queryLower)) continue

                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val row = link.parents().firstOrNull {
                    it.tagName() == "tr" || it.tagName() == "article" || it.hasClass("card") || it.hasClass("item")
                } ?: link.parent() ?: continue

                val rowText = row.text()
                val imgEl = row.select("img").first()
                val imgUrl = AuctionHttpClient.sanitizeHttpsUrl(imgEl?.attr("src"), baseOrigin)
                val price = AuctionHttpClient.extractPrice(rowText)

                lots.add(
                    AuctionLot(
                        id = "BCA_${absUrl.hashCode()}",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = rowText.take(450).ifBlank { "Surplus public de la Colombie-Britannique." },
                        currentBid = price,
                        currency = "CAD",
                        bidCount = 0,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = CanadianProvince.BC,
                        city = "Colombie-Britannique",
                        auctionHouse = "BC Auction",
                        category = AuctionCategory.inferFromText("$title $rowText"),
                        closingLabel = "En cours",
                        buyersPremiumPercent = 0.0
                    )
                )
                if (lots.size >= 20) break
            }
            return lots
        }
    }
}
