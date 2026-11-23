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
 * Dedicated live connector for GCSurplus Canada (www.gcsurplus.ca).
 * Parses GCSurplus's verified paired-row table structure:
 * - Row A (`td[headers=itemPic]`): lot photo (`/lotImages/...` or `/ic-ci/images/...`) & `a[href*=sc=enc-bid]`
 * - Row B (`nextElementSibling()`): full lot title, Current Bid / Minimum bid, Remaining time, Closing date, Location.
 */
class GcSurplusConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.GCSURPLUS_CA

    private val baseOrigin = "https://www.gcsurplus.ca"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val enQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        val base = "$baseOrigin/mn-eng.cfm?snc=wfsav&sc=ach-shop&sr=1&sf=ferm-clos&so=ASC"
        return if (enQuery.isNotBlank()) {
            "$base&srch=${AuctionHttpClient.urlEncode(enQuery)}"
        } else {
            base
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val effectiveQuery = BilingualQueryTranslator.toEnglishSearchQuery(query).ifBlank {
            when (category) {
                AuctionCategory.VEHICLES -> "ford"
                AuctionCategory.TOOLS_EQUIPMENT -> "tool"
                AuctionCategory.ELECTRONICS -> "computer"
                else -> ""
            }
        }
        val searchUrl = if (effectiveQuery.isNotBlank()) {
            "$baseOrigin/mn-eng.cfm?snc=wfsav&sc=ach-shop&sr=1&sf=ferm-clos&so=ASC&srch=${AuctionHttpClient.urlEncode(effectiveQuery)}"
        } else {
            "$baseOrigin/mn-eng.cfm?snc=wfsav&sc=ach-shop&sr=1&sf=ferm-clos&so=ASC"
        }

        val lots = runCatching { scrapeGcSurplusRows(searchUrl, province, query) }.getOrDefault(emptyList())
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "GCSurplus en direct (${lots.size} lots)",
                directSearchUrl = searchUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail GCSurplus prêt",
                directSearchUrl = searchUrl
            )
        }
    }

    private fun scrapeGcSurplusRows(
        url: String,
        requestedProvince: CanadianProvince,
        rawQuery: String
    ): List<AuctionLot> {
        val req = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, baseOrigin)
            val lots = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()
            val queryTerms = BilingualQueryTranslator.expandMatchingTerms(rawQuery)

            val picCells = doc.select("td[headers=itemPic]")
            for (cell in picCells) {
                val link = cell.selectFirst("a[href*=sc=enc-bid]") ?: continue
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), baseOrigin) ?: continue
                val lcn = Regex("""lcn=(\d+)""").find(absUrl)?.groupValues?.get(1) ?: absUrl.hashCode().toString()
                if (!seen.add(lcn)) continue

                val imgEl = cell.selectFirst("img[src*=lotImages], img[src*=ic-ci/images], img")
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(imgEl?.attr("src"), baseOrigin)

                // Match ONLY the details row/cells that actually reference THIS lot number (lcn)
                val row = cell.parents().firstOrNull { it.tagName() == "tr" }
                val sameRowText = row?.text().orEmpty()
                val prevText = row?.previousElementSibling()?.text().orEmpty().takeIf { it.contains(lcn) }.orEmpty()
                val nextText = row?.nextElementSibling()?.text().orEmpty().takeIf { it.contains(lcn) }.orEmpty()
                val combinedText = "$prevText $sameRowText $nextText".trim()

                val titleFromImg = imgEl?.attr("title").orEmpty()
                    .removePrefix("Picture of ")
                    .substringBefore(" Lot on Sale:")
                    .trim()
                    .ifBlank { imgEl?.attr("alt").orEmpty().removePrefix("Picture of ").trim() }
                val titleFromDetails = combinedText
                    .substringBefore("-$lcn")
                    .substringBefore("Open Bidding")
                    .substringBefore("Current Bid")
                    .trim()
                    .takeIf { it.length >= 6 && !it.all { ch -> ch.isDigit() || ch.isWhitespace() } }
                val title = titleFromImg.takeIf { it.length >= 4 } ?: titleFromDetails.orEmpty()
                if (title.length < 4) continue

                if (queryTerms.isNotEmpty()) {
                    val hay = BilingualQueryTranslator.normalize("$title $combinedText")
                    if (queryTerms.none { hay.contains(it) }) continue
                }

                val currentBidRaw = Regex("""Current Bid\s*\(CAD\):\s*(\$[\d,]+\.\d{2})""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.get(1)
                val minBidRaw = Regex("""Minimum bid:\s*(\$[\d,]+\.\d{2})""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.get(1)
                val price = AuctionHttpClient.extractPrice(currentBidRaw)
                    ?: AuctionHttpClient.extractPrice(minBidRaw)

                val remaining = Regex("""Remaining:\s*([0-9]+\s*(?:days?|hours?|minutes?)(?:\s+[0-9]+\s*(?:hours?|minutes?))?)""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.get(1)?.trim()
                    ?: Regex("""Closing:\s*([0-9]{1,2}-[A-Za-z]+-\d{4})""").find(combinedText)?.groupValues?.get(1)
                    ?: "En cours"

                val locMatch = Regex("""Location:\s*([^|]+)$""", RegexOption.IGNORE_CASE)
                    .find(combinedText)?.groupValues?.get(1)?.trim().orEmpty()
                val detectedProv = CanadianProvince.fromCodeOrText("$locMatch $combinedText")

                if (requestedProvince != CanadianProvince.ALL &&
                    detectedProv != CanadianProvince.ALL &&
                    detectedProv != requestedProvince
                ) {
                    continue
                }

                lots.add(
                    AuctionLot(
                        id = "GCS_$lcn",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = combinedText.take(450).ifBlank { title },
                        currentBid = price,
                        currency = "CAD",
                        bidCount = 0,
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = if (detectedProv != CanadianProvince.ALL) detectedProv else requestedProvince,
                        city = locMatch.ifBlank {
                            if (detectedProv != CanadianProvince.ALL) detectedProv.displayNameFr else "Canada (Fédéral)"
                        },
                        auctionHouse = "GCSurplus Canada",
                        category = AuctionCategory.inferFromText("$title $combinedText"),
                        closingLabel = remaining,
                        lotNumber = lcn,
                        buyersPremiumPercent = 0.0
                    )
                )
                if (lots.size >= 25) break
            }
            return lots
        }
    }
}
