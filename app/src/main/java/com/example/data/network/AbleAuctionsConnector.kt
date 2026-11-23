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
 * Dedicated live connector for Able Auctions (www.ableauctions.ca),
 * British Columbia's premier online commercial, vehicle, and equipment auction house.
 */
class AbleAuctionsConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.ABLE_AUCTIONS_BC

    private val baseOrigin = "https://www.ableauctions.ca"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val term = BilingualQueryTranslator.toEnglishSearchQuery(query)
        return if (term.isNotBlank()) {
            "$baseOrigin/auctions/?Keywords=${AuctionHttpClient.urlEncode(term)}"
        } else {
            "$baseOrigin/auctions/"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val requestUrl = buildDirectSearchUrl(query, province)

        if (province != CanadianProvince.ALL && province != CanadianProvince.BC) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Basé en Colombie-Britannique (Vancouver / Langley)",
                directSearchUrl = requestUrl
            )
        }

        val lots = runCatching { scrapeAuctions(requestUrl, plan, category) }.getOrDefault(emptyList())
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Able Auctions en direct (${lots.size} ventes)",
                directSearchUrl = requestUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail Able Auctions BC prêt",
                directSearchUrl = requestUrl
            )
        }
    }

    private fun scrapeAuctions(
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
            val links = doc.select("a[href*=aAuctionId=]")
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()

            for (link in links) {
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                val auctionId = Regex("""aAuctionId=([a-zA-Z0-9\-]+)""").find(absUrl)?.groupValues?.get(1) ?: continue
                if (!seen.add(auctionId)) continue

                val text = link.text().replace(Regex("\\s+"), " ").trim()
                if (text.length < 10 || text.equals("Sign-up", true) || text.equals("Buy", true) || text.equals("Sell", true)) {
                    continue
                }

                val container = link.parents().firstOrNull { it.select("img[src*=/auctions/]").isNotEmpty() && it.text().length < 1500 }
                    ?: link.parent()
                val imgEl = container?.selectFirst("img[src*=/auctions/], img")
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(
                    imgEl?.attr("src")?.replace(" ", "%20"),
                    baseOrigin
                ) ?: continue

                val fullText = container?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { text }

                if (plan.expandedTerms.isNotEmpty()) {
                    val hay = BilingualQueryTranslator.normalize("$text $fullText")
                    if (plan.expandedTerms.none { hay.contains(it) }) continue
                }

                val inferredCat = AuctionCategory.inferFromText("$text $fullText")
                if (plan.rawQuery.isBlank() && category != AuctionCategory.ALL && inferredCat != category) {
                    continue
                }

                val dateMatch = Regex("""(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+[A-Z][a-z]{2}\s+\d{1,2},\s+\d{4}""")
                    .find(fullText)?.value ?: "En cours"

                lots.add(
                    AuctionLot(
                        id = "ABLE_$auctionId",
                        sourceId = sourceId,
                        title = text.substringBefore(" | ").take(160),
                        description = fullText.take(450),
                        currentBid = null,
                        currency = "CAD",
                        bidCount = 0,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = CanadianProvince.BC,
                        city = "Vancouver / Langley, BC",
                        auctionHouse = "Able Auctions",
                        category = inferredCat,
                        closingLabel = dateMatch,
                        buyersPremiumPercent = 17.0
                    )
                )
                if (lots.size >= 16) break
            }
            return lots
        }
    }
}
