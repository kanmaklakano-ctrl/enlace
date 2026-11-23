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
 * Dedicated live connector for McDougall Auctioneers (mcdougallauction.com),
 * parsing `products.php?search=...` (`a[href*=products-full-view]` + `images/repository/...` JPEG thumbs).
 */
class McDougallConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.MCDOUGALL_CA

    private val baseOrigin = "https://mcdougallauction.com"
    private val supported = setOf(
        CanadianProvince.SK, CanadianProvince.AB, CanadianProvince.MB, CanadianProvince.ON, CanadianProvince.BC
    )

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val enQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        return if (enQuery.isNotBlank()) {
            "$baseOrigin/products.php?search=${AuctionHttpClient.urlEncode(enQuery)}"
        } else {
            "$baseOrigin/products.php?category=Vehicles"
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val effectiveQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        val searchUrl = when {
            effectiveQuery.isNotBlank() -> "$baseOrigin/products.php?search=${AuctionHttpClient.urlEncode(effectiveQuery)}"
            category == AuctionCategory.VEHICLES -> "$baseOrigin/products.php?search=car"
            category == AuctionCategory.TOOLS_EQUIPMENT -> "$baseOrigin/products.php?search=tool"
            category == AuctionCategory.MACHINERY -> "$baseOrigin/products.php?search=tractor"
            else -> "$baseOrigin/products.php?search=ford"
        }

        if (province != CanadianProvince.ALL && province !in supported) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Couverture principale : SK, AB, MB, ON",
                directSearchUrl = searchUrl
            )
        }

        val lots = runCatching { scrapeProducts(searchUrl, province) }.getOrDefault(emptyList())
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "McDougall en direct (${lots.size} lots)",
                directSearchUrl = searchUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail McDougall prêt",
                directSearchUrl = searchUrl
            )
        }
    }

    private fun scrapeProducts(url: String, requestedProvince: CanadianProvince): List<AuctionLot> {
        val req = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()

            val imgs = doc.select("img[src*=images/repository]")
            for (img in imgs) {
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(img.attr("src"), baseOrigin) ?: continue
                val container = img.parents().firstOrNull { parent ->
                    parent.select("a[href*=products-full-view], a[href*=auction-event]").isNotEmpty() &&
                        parent.text().length in 20..1200
                } ?: continue

                val linkEl = container.selectFirst("a[href*=products-full-view], a[href*=auction-event]") ?: continue
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(linkEl.attr("href"), baseOrigin) ?: continue
                if (!seen.add(absUrl)) continue

                val fullText = container.text().trim()
                val title = fullText
                    .substringBefore("Location:")
                    .substringBefore("Lot:")
                    .substringBefore("Status:")
                    .trim()
                    .ifBlank { container.select("h2, h3, h4, h5, strong").first()?.text()?.trim().orEmpty() }
                if (title.length < 5) continue

                val bidRaw = Regex("""Current Bid:\s*(\$[\d,]+\.\d{2})""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1)
                val price = AuctionHttpClient.extractPrice(bidRaw ?: fullText)

                val locRaw = Regex("""Location:\s*(.+?)(?:Lot:|Status:|Current Bid:|$)""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1)?.trim().orEmpty()
                val lotNum = Regex("""Lot:\s*(\w+)""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1).orEmpty()
                val closeRaw = Regex("""Close date:\s*(.+)$""", RegexOption.IGNORE_CASE)
                    .find(fullText)?.groupValues?.get(1)?.trim()?.take(28).orEmpty()

                val detectedProv = CanadianProvince.fromCodeOrText("$locRaw $fullText")
                    .let { if (it == CanadianProvince.ALL) CanadianProvince.SK else it }

                if (requestedProvince != CanadianProvince.ALL && detectedProv != requestedProvince) {
                    continue
                }

                lots.add(
                    AuctionLot(
                        id = "MCDOUGALL_${absUrl.hashCode()}",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = fullText.take(450),
                        currentBid = price,
                        currency = "CAD",
                        bidCount = 0,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = detectedProv,
                        city = locRaw.ifBlank { detectedProv.displayNameFr },
                        auctionHouse = "McDougall Auctioneers",
                        category = AuctionCategory.inferFromText("$title $fullText"),
                        closingLabel = closeRaw.ifBlank { "En cours" },
                        lotNumber = lotNum
                    )
                )
                if (lots.size >= 24) break
            }
            return lots
        }
    }
}
